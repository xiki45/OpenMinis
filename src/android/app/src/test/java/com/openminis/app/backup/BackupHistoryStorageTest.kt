package com.openminis.app.backup

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-android-backup-history-atomic] `records.json` used to be rewritten in
 * place (`writeText`) under the same lock every reader takes:
 * - a reader racing the write saw a truncated file — 19 of 68 samples during
 *   the KILO repro — and a process killed mid-write left one behind, which the
 *   next launch decoded to "no history at all", silently;
 * - the backup page's reader waited 111–488 ms for that lock every ~10 s while
 *   a delivery thread was serializing and writing.
 */
class BackupHistoryStorageTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = ListSerializer(BackupHistory.Record.serializer())

    private fun record(i: Int, logLines: Int = 20) = BackupHistory.Record(
        id = "r$i",
        backupId = "b$i",
        startedAt = System.currentTimeMillis() - i,
        status = BackupHistory.Status.RUNNING,
        log = List(logLines) { BackupHistory.LogEntry(it.toLong(), "line $it of run $i") },
    )

    private fun history(dir: File) = BackupHistory(dir, Executors.newSingleThreadExecutor())

    // -- 4. A truncated file is kept aside, not silently discarded --------------

    @Test
    fun `a truncated records file is renamed aside and reading returns an empty list`() {
        val dir = tmp.newFolder("backup-history")
        val full = json.encodeToString(listSer, listOf(record(1), record(2)))
        File(dir, "records.json").writeText(full.substring(0, full.length / 2))

        val h = history(dir)
        assertEquals(emptyList<BackupHistory.Record>(), h.records())
        assertFalse("bad file no longer in place", File(dir, "records.json").exists())
        val kept = dir.listFiles()!!.filter { it.name.startsWith("records.json.corrupt-") }
        assertEquals("kept for inspection: ${dir.list()!!.toList()}", 1, kept.size)
        assertEquals(full.substring(0, full.length / 2), kept.single().readText())

        // History works again from scratch.
        h.upsert(record(3))
        h.flushWrites()
        assertEquals(listOf("r3"), json.decodeFromString(listSer, File(dir, "records.json").readText()).map { it.id })
    }

    // -- 5. Atomic replace: a concurrent reader never sees half a file -----------

    @Test
    fun `a reader racing thousands of writes never reads a truncated file`() {
        val dir = tmp.newFolder("backup-history")
        val h = history(dir)
        h.upsert(record(0))
        h.flushWrites()
        val store = File(dir, "records.json")
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val reads = AtomicInteger()
        val bad = AtomicInteger()
        val reader = Thread {
            while (!stop.get()) {
                val text = runCatching { store.readText() }.getOrNull() ?: continue
                reads.incrementAndGet()
                if (runCatching { json.decodeFromString(listSer, text) }.isFailure) bad.incrementAndGet()
            }
        }.apply { start() }
        repeat(3_000) { i ->
            // A growing run log, like a delivery reporting progress.
            h.upsert(record(0, logLines = 20 + i % 400))
            if (i % 50 == 0) h.flushWrites()
        }
        h.flushWrites()
        stop.set(true)
        reader.join()
        assertTrue("reader actually raced the writer: ${reads.get()}", reads.get() > 100)
        assertEquals("truncated reads out of ${reads.get()}", 0, bad.get())
        assertFalse("no temp file left behind", File(dir, "records.json.tmp").exists())
    }

    // -- Readers never wait for the disk ------------------------------------------

    @Test
    fun `records() answers while a write is stuck on the disk`() {
        val dir = tmp.newFolder("backup-history")
        val h = history(dir)
        h.upsert(record(1))
        h.flushWrites()

        val inWrite = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.writeHook = { inWrite.countDown(); release.await(10, TimeUnit.SECONDS) }
        h.upsert(record(2)) // queues a write that will block inside the disk step
        assertTrue("write started", inWrite.await(5, TimeUnit.SECONDS))

        val t0 = System.nanoTime()
        val seen = h.records()
        h.upsert(record(3))
        val ms = (System.nanoTime() - t0) / 1_000_000
        release.countDown()
        assertTrue("reader and writer of the cache did not wait for the disk: $ms ms", ms < 500)
        assertEquals(listOf("r1", "r2"), seen.map { it.id }.sorted())
        h.writeHook = null
        h.flushWrites()
        assertEquals(
            listOf("r1", "r2", "r3"),
            json.decodeFromString(listSer, File(dir, "records.json").readText()).map { it.id }.sorted(),
        )
    }

    // -- The newest snapshot always wins ---------------------------------------------

    @Test
    fun `concurrent writers end with the file equal to the last in-memory state`() {
        val dir = tmp.newFolder("backup-history")
        val h = history(dir)
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        repeat(4) { w ->
            pool.execute {
                repeat(500) { i -> h.upsert(record(w, logLines = i)) }
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        h.flushWrites()
        val onDisk = json.decodeFromString(listSer, File(dir, "records.json").readText())
        assertEquals("an older snapshot must never overwrite a newer one", h.records(), onDisk)
        assertEquals(List(4) { 499 }, onDisk.sortedBy { it.id }.map { it.log.size })
    }

    @Test
    fun `a failed write is only logged and never thrown at the caller`() {
        val dir = tmp.newFolder("backup-history")
        val h = history(dir)
        h.upsert(record(1))
        h.flushWrites()
        // Make the destination unwritable: a directory where the temp file goes.
        File(dir, "records.json.tmp").mkdirs()
        h.upsert(record(2)) // must not throw
        h.flushWrites()
        assertEquals(2, h.records().size)
    }
}
