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
import java.util.concurrent.Executor

/**
 * [T-android-backup-log-sanitize] Records written before the progress-log fix
 * stay on the device for 30 days: the KILO repro left one with 16,827 lines,
 * and every visit to it froze the app. On first load each record's log is
 * cleaned once — and nothing else in the record changes.
 */
class BackupHistorySanitizeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = ListSerializer(BackupHistory.Record.serializer())
    private val direct = Executor { it.run() }

    private fun e(at: Long, message: String, problem: Boolean = false) =
        BackupHistory.LogEntry(at, message, isProblem = problem)

    /** The shape the KILO repro produced: phases, then ~2,600 lines per percent. */
    private fun bloated(lines: Int = 17_000): BackupHistory.Record {
        val log = mutableListOf(e(1, "Starting…"), e(2, "Exporting chats…"), e(3, "Packaging…"), e(4, "Sending to Folder…"))
        var at = 5L
        while (log.size < lines - 1) log.add(e(at++, "Saving to Folder… ${(log.size - 4) / 2_600}%"))
        log.add(e(at, "stopped", problem = true))
        return BackupHistory.Record(
            id = "kilo", backupId = "m544qb9q0pk", startedAt = System.currentTimeMillis() - 60_000,
            finishedAt = System.currentTimeMillis(), status = BackupHistory.Status.FAILED,
            categories = listOf("chats", "files"), encrypted = false, totalBytes = 2_129_492_577,
            skippedFiles = 1, skippedEntries = listOf(BackupHistory.SkippedEntry("files/x.bin", 9, "too big")),
            packageName = "Pixel-4a-20261017-m544qb9q0pk.minisbak",
            destinations = listOf(BackupHistory.DestinationOutcome("Folder", false, "Delivery stopped.", "local", "Download/B")),
            log = log, errorMessage = "stopped",
        )
    }

    // -- 6. The rule itself ------------------------------------------------------

    @Test
    fun `a 17,000 line log is cleaned to at most 201 lines and cleaning twice changes nothing`() {
        val r = bloated()
        assertEquals(17_000, r.log.size)
        val once = BackupLogRules.sanitize(r.log)
        assertTrue("cleaned to ${once.size}", once.size <= 201)
        assertEquals(
            listOf("Starting…", "Exporting chats…", "Packaging…", "Sending to Folder…", "Saving to Folder… 6%", "stopped"),
            once.map { it.message },
        )
        assertTrue("the kept progress line is transient", once[4].isTransient)
        assertTrue(once.last().isProblem)
        assertEquals("idempotent", once, BackupLogRules.sanitize(once))
    }

    @Test
    fun `only consecutive progress lines merge and a long log keeps its newest 200 lines behind a note`() {
        val log = buildList {
            repeat(150) { i ->
                add(e(i * 10L, "Exporting part $i…"))
                add(e(i * 10L + 1, "Sending to NAS… 1%"))
                add(e(i * 10L + 2, "Sending to NAS… 2%"))
            }
        }
        val out = BackupLogRules.sanitize(log)
        assertEquals(201, out.size)
        val marker = out.first()
        assertEquals("150 phases + 150 merged progress = 300, 100 dropped", 100, BackupLogRules.omittedCount(marker))
        val merged = (50 until 150).flatMap { listOf("Exporting part $it…", "Sending to NAS… 2%") }
        assertEquals(merged, out.drop(1).map { it.message })
        assertEquals("idempotent with the note in place", out, BackupLogRules.sanitize(out))
        // A record already cleaned once and grown again folds the counts together.
        val grown = out + List(50) { e(10_000L + it, "Exporting more $it…") }
        val again = BackupLogRules.sanitize(grown)
        assertEquals(201, again.size)
        assertEquals(150, BackupLogRules.omittedCount(again.first()))
    }

    @Test
    fun `a short clean log is returned unchanged`() {
        val log = listOf(e(1, "Starting…"), e(2, "Sending to NAS… 4%"), e(3, "Delivered to NAS"))
        val out = BackupLogRules.sanitize(log)
        assertEquals(log.map { it.message }, out.map { it.message })
        assertEquals("a single progress line is only marked transient", listOf(false, true, false), out.map { it.isTransient })
        assertEquals(out, BackupLogRules.sanitize(out))
    }

    // -- On first load ------------------------------------------------------------

    @Test
    fun `first load cleans the log only, keeps a bak of the original and every other field`() {
        val dir = tmp.newFolder("backup-history")
        val original = listOf(bloated(), bloated().copy(id = "small", log = listOf(e(1, "Starting…"))))
        val raw = json.encodeToString(listSer, original)
        File(dir, "records.json").writeText(raw)

        val h = BackupHistory(dir, direct)
        val loaded = h.records()
        h.flushWrites()

        val kilo = loaded.single { it.id == "kilo" }
        val before = original.single { it.id == "kilo" }
        assertTrue(kilo.log.size <= 201)
        assertEquals("every field but the log is byte-identical", before.copy(log = emptyList()), kilo.copy(log = emptyList()))
        assertEquals(original.single { it.id == "small" }, loaded.single { it.id == "small" })
        assertEquals("the original is kept once", raw, File(dir, "records.json.bak").readText())
        assertEquals(loaded, json.decodeFromString(listSer, File(dir, "records.json").readText()))

        // A second process start finds nothing to clean and writes nothing.
        val store = File(dir, "records.json")
        val bak = File(dir, "records.json.bak")
        store.setLastModified(1_000_000L)
        bak.setLastModified(1_000_000L)
        BackupHistory(dir, direct).records()
        assertEquals(1_000_000L, store.lastModified())
        assertEquals(1_000_000L, bak.lastModified())
    }

    @Test
    fun `a clean file is not rewritten and gets no bak`() {
        val dir = tmp.newFolder("backup-history")
        val r = bloated().copy(log = listOf(e(1, "Starting…"), e(2, "Delivered to NAS")))
        File(dir, "records.json").writeText(json.encodeToString(listSer, listOf(r)))
        File(dir, "records.json").setLastModified(1_000_000L)
        assertEquals(listOf(r), BackupHistory(dir, direct).records())
        assertEquals(1_000_000L, File(dir, "records.json").lastModified())
        assertFalse(File(dir, "records.json.bak").exists())
    }

    @Test
    fun `a cleaner that throws leaves the records as they were`() {
        val dir = tmp.newFolder("backup-history")
        val original = listOf(bloated(lines = 3_000))
        File(dir, "records.json").writeText(json.encodeToString(listSer, original))
        val h = BackupHistory(dir, direct, sanitize = { error("boom") })
        assertEquals(original, h.records())
        assertFalse(File(dir, "records.json.bak").exists())
    }
}
