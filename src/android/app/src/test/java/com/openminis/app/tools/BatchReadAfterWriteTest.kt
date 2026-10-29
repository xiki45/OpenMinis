package com.openminis.app.tools

import com.openminis.app.ProductionSources
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * [T-batch-read-after-write] / [T-readimage-fail-visible] iOS 1eb3e2bef: a
 * shell that renders q-1.png and a read_image of q-1.png sent in one turn
 * started together, the read ran ~15 ms in, before the shell, and failed with
 * a message that gave no reason.
 */
class BatchReadAfterWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `batch order matches iOS and Windows`() {
        // Windows minis-agent tools.rs batch_order_matches_ios, verbatim.
        val n = listOf("shell_execute", "file_read", "browser_use", "file_write", "read_image", "file_read")
        assertEquals(emptyList<Int>(), BatchToolOrder.writersBefore(0, n))
        assertEquals(listOf(0), BatchToolOrder.writersBefore(1, n))
        assertEquals("only readers wait", emptyList<Int>(), BatchToolOrder.writersBefore(2, n))
        assertEquals(listOf(0, 3), BatchToolOrder.writersBefore(4, n))
        assertEquals(listOf(0, 3), BatchToolOrder.writersBefore(5, n))
        assertEquals(emptyList<Int>(), BatchToolOrder.writersBefore(9, n))
        assertEquals(emptyList<Int>(), BatchToolOrder.writersBefore(0, listOf("file_read")))
        // A reader before the writer does not wait; writers never wait.
        assertEquals(emptyList<Int>(), BatchToolOrder.writersBefore(0, listOf("read_image", "shell_execute")))
        assertEquals(emptyList<Int>(), BatchToolOrder.writersBefore(1, listOf("file_edit", "file_write")))
        assertEquals(listOf(0), BatchToolOrder.writersBefore(1, listOf("file_edit", "read_image")))
    }

    /**
     * The dispatcher shape of ChatViewModel's tool batch, reduced to its
     * ordering parts (guarded against production below): every call is an
     * async, a reader joins the earlier writers' jobs BEFORE taking a slot,
     * results land at their own index.
     */
    private suspend fun runBatch(
        names: List<String>,
        slotsCount: Int,
        body: suspend (Int, String) -> String,
    ): List<String> {
        val slots = Semaphore(slotsCount)
        val slotJobs = arrayOfNulls<Job>(names.size)
        val results = arrayOfNulls<String>(names.size)
        coroutineScope {
            names.mapIndexed { slotIndex, name ->
                async {
                    BatchToolOrder.writersBefore(slotIndex, names).forEach { slotJobs[it]?.join() }
                    slots.withPermit { results[slotIndex] = body(slotIndex, name) }
                }.also { slotJobs[slotIndex] = it }
            }.awaitAll()
        }
        return results.map { it!! }
    }

    @Test
    fun `a read after a write in the same batch sees the written file`() = runBlocking {
        val png = File(tmp.root, "q-1.png")
        val out = runBatch(listOf("shell_execute", "read_image"), slotsCount = 10) { _, name ->
            if (name == "shell_execute") {
                delay(200) // the render takes a while
                png.writeText("pixels")
                "rendered"
            } else {
                if (png.exists()) png.readText() else "missing"
            }
        }
        assertEquals(listOf("rendered", "pixels"), out)
    }

    @Test
    fun `unrelated calls still overlap and results stay in call order`() = runBlocking {
        val running = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val out = runBatch(listOf("shell_execute", "browser_use", "shell_execute", "file_write"), slotsCount = 10) { i, _ ->
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            delay(100L * (4 - i)) // finish in reverse order
            running.decrementAndGet()
            "r$i"
        }
        assertEquals(listOf("r0", "r1", "r2", "r3"), out)
        assertEquals(4, peak.get())
    }

    @Test
    fun `a waiting reader never holds a slot a writer needs`() = runBlocking {
        // One slot, reader queued behind two writers: if the reader took the
        // slot first and then waited, this would deadlock.
        val order = Collections.synchronizedList(mutableListOf<String>())
        withTimeout(5_000) {
            runBatch(listOf("file_write", "file_read", "shell_execute", "read_image"), slotsCount = 1) { i, name ->
                delay(20)
                order += "$i:$name"
                name
            }
        }
        assertTrue(order.indexOf("0:file_write") < order.indexOf("1:file_read"))
        assertTrue(order.indexOf("2:shell_execute") < order.indexOf("3:read_image"))
        assertTrue(order.indexOf("0:file_write") < order.indexOf("3:read_image"))
    }

    @Test
    fun `production dispatcher joins earlier writers before taking a slot`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val batch = vm.substringAfter("val batchNames = toolCalls.map { it.second }").substringBefore("}.awaitAll()")
        assertTrue(batch.contains("val slotJobs = arrayOfNulls<kotlinx.coroutines.Job>(toolCalls.size)"))
        val wait = batch.indexOf("BatchToolOrder.writersBefore(slotIndex, batchNames)")
        val join = batch.indexOf("earlierWriters.forEach { slotJobs[it]?.join() }")
        val permit = batch.indexOf("slots.withPermit {")
        assertTrue("wait, then join, then slot", wait in 0 until join && join < permit)
        assertTrue(batch.contains("}.also { slotJobs[slotIndex] = it }"))
    }

    // ── failure text ───────────────────────────────────────────────────────

    @Test
    fun `read_image says which failure it was`() {
        val p = "/var/minis/workspace/q-1.png"
        assertEquals(
            "Error: '$p' is not a readable Minis path. Use a path under /var/minis/ (e.g. /var/minis/workspace/chart.png) or a minis:// URL.",
            ReadImageTool.failureText(p, resolved = false, file = null),
        )
        assertEquals(
            "Error: No file at '$p'. If a command in this same turn creates it, read it in the next turn after that command has finished.",
            ReadImageTool.failureText(p, resolved = true, file = File(tmp.root, "nope.png")),
        )
        assertEquals(
            "Error: '$p' exists but is empty (0 bytes) - it may still be being written.",
            ReadImageTool.failureText(p, resolved = true, file = tmp.newFile("empty.png")),
        )
        val txt = tmp.newFile("notes.txt").apply { writeText("hello") }
        assertEquals(
            "Error: '$p' exists (5 bytes) but is not a decodable image. Supported: PNG, JPEG, GIF, WebP, BMP, HEIF.",
            ReadImageTool.failureText(p, resolved = true, file = txt, heif = true),
        )
        assertEquals(
            "Error: '$p' exists (5 bytes) but is not a decodable image. Supported: PNG, JPEG, GIF, WebP, BMP.",
            ReadImageTool.failureText(p, resolved = true, file = txt, heif = false),
        )
        assertEquals(
            "Error: '$p' is a folder, not an image file.",
            ReadImageTool.failureText(p, resolved = true, file = tmp.newFolder("dir")),
        )
    }

    @Test
    fun `every read_image failure path uses the failure text`() {
        val src = ProductionSources.read("tools/ReadImageTool.kt")
        assertTrue(!src.contains("\"Error: File not found: \$path\""))
        assertTrue(!src.contains("\"Error: Cannot decode image: \$path\""))
        assertTrue(!src.contains("\"Error: Cannot resolve path: \$path\""))
        assertEquals(3, src.split("failureText(path, resolved = ").size - 1)
    }
}
