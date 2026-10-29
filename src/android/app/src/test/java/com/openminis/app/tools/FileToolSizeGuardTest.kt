package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * [T-port-filetool-size-guard] file_read streams instead of File.readLines(),
 * file_edit refuses files over 8 MB, and all three file tools run their I/O
 * with a timeout and an OutOfMemoryError catch (iOS 26e6e63e9).
 *
 * The large files are generated in a temp dir and deleted afterwards.
 */
class FileToolSizeGuardTest {

    private val tmp: File = Files.createTempDirectory("filetool-guard").toFile()

    @After
    fun cleanUp() {
        tmp.deleteRecursively()
    }

    // --- Reference: the pre-port file_read body (File.readLines) ------------

    /** The file_read logic as it was before the port, kept verbatim. */
    private fun legacyRead(file: File, path: String, offset: Int, requestedLines: Int?, maxLength: Int, direction: String): String {
        val size = file.length()
        val allLines = file.readLines()
        val totalLines = allLines.size
        val selectedLines = if (direction == "tail") {
            val count = requestedLines ?: totalLines
            val start = (totalLines - count).coerceAtLeast(0)
            allLines.subList(start, totalLines)
        } else {
            val start = (offset - 1).coerceIn(0, totalLines)
            val end = if (requestedLines != null) (start + requestedLines).coerceAtMost(totalLines) else totalLines
            allLines.subList(start, end)
        }
        val showStart = if (direction == "tail") (totalLines - selectedLines.size) + 1 else offset
        val showEnd = showStart + selectedLines.size - 1
        var content = selectedLines.joinToString("\n")
        var effectiveStart = showStart
        var effectiveEnd = showEnd
        var nextOffset: Int? = null
        var wasTruncated = false
        if (content.length > maxLength) {
            wasTruncated = true
            if (direction == "tail") {
                content = content.takeLast(maxLength)
                val firstNewline = content.indexOf('\n')
                if (firstNewline in 0 until content.length - 1) content = content.substring(firstNewline + 1)
                effectiveStart = effectiveEnd - content.count { it == '\n' }
            } else {
                content = content.take(maxLength)
                val lastNewline = content.lastIndexOf('\n')
                if (lastNewline > 0) content = content.substring(0, lastNewline)
                effectiveEnd = showStart + content.count { it == '\n' }
                if (effectiveEnd < totalLines) nextOffset = effectiveEnd + 1
            }
        }
        var header = "[$path | $size bytes | $totalLines lines | showing $effectiveStart-$effectiveEnd of $totalLines"
        if (wasTruncated) {
            header += " | truncated at $maxLength chars"
            header += if (nextOffset != null) ", next_offset=$nextOffset" else ", retry with a smaller lines value"
        }
        header += "]"
        return "$header\n$content"
    }

    private fun read(file: File, offset: Int = 1, lines: Int? = null, maxLength: Int = 15000, direction: String = "head") =
        FileReadTool.read(file, "/p", offset, lines, maxLength, direction, "t")

    // Bytes that exercise every line-splitting and decoding path: \n, \r\n,
    // lone \r, 2/3/4-byte UTF-8, and malformed sequences (a lone continuation
    // byte, a truncated 3-byte lead). No 0x00, so nothing is "binary".
    private val alphabet: List<ByteArray> = listOf(
        "a", "b", "xyz", " ", "\n", "\n", "\r\n", "\r", "é", "中", "😀",
    ).map { it.toByteArray() } + listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xE4.toByte(), 0xB8.toByte()))

    private fun randomBytes(rnd: Random, n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        repeat(n) { out.write(alphabet[rnd.nextInt(alphabet.size)]) }
        // Sometimes a long run with no line break, to cut a line mid-sequence.
        if (rnd.nextInt(4) == 0) repeat(rnd.nextInt(1, 80)) { out.write("中é😀a".toByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun `streamed read is byte-identical to the readLines version on random files`() {
        val rnd = Random(4242)
        val f = File(tmp, "r.txt")
        repeat(3000) { iter ->
            val bytes = randomBytes(rnd, rnd.nextInt(0, 60))
            f.writeBytes(bytes)
            val total = f.readLines().size
            val direction = if (rnd.nextBoolean()) "tail" else "head"
            val offset = rnd.nextInt(1, total + 4)
            val lines = if (rnd.nextInt(3) == 0) null else rnd.nextInt(0, total + 3)
            val maxLength = if (rnd.nextInt(4) == 0) 15000 else rnd.nextInt(0, 40)
            val expected = legacyRead(f, "/p", offset, lines, maxLength, direction)
            val actual = read(f, offset, lines, maxLength, direction)
            assertTrue(actual.success)
            assertEquals(
                "iter=$iter dir=$direction offset=$offset lines=$lines max=$maxLength bytes=${bytes.toList()}",
                expected, actual.output,
            )
        }
    }

    @Test
    fun `edge files match the readLines version`() {
        val cases = listOf("", "\n", "\r", "\r\n", "a", "a\n", "a\n\n", "a\r\rb", "\n\n\n", "x\r\ny\rz\n")
        val f = File(tmp, "e.txt")
        for (text in cases) {
            f.writeText(text)
            for (dir in listOf("head", "tail")) for (lines in listOf(null, 0, 1, 2, 5)) for (max in listOf(0, 1, 2, 15000)) {
                assertEquals("text=${text.toByteArray().toList()} $dir $lines $max",
                    legacyRead(f, "/p", 1, lines, max, dir), read(f, 1, lines, max, dir).output)
            }
        }
    }

    // --- Large files --------------------------------------------------------

    /** A ~[mb] MB file of numbered lines "line 0000001 ......". */
    private fun bigLinesFile(mb: Int): Pair<File, Int> {
        val f = File(tmp, "big.log")
        val pad = "x".repeat(50)
        var n = 0
        f.bufferedWriter().use { w ->
            while (n.toLong() * 64 < mb.toLong() * 1024 * 1024) {
                n++
                w.write("line %07d %s\n".format(n, pad))
            }
        }
        return f to n
    }

    @Test
    fun `50 MB log reads head, offset and tail without loading the file`() {
        val (f, n) = bigLinesFile(50)
        val head = read(f, lines = 2)
        assertTrue(head.output, head.output.startsWith("[/p | ${f.length()} bytes | $n lines | showing 1-2 of $n]\n"))
        assertTrue(head.output.endsWith("line 0000002 " + "x".repeat(50)))

        val mid = read(f, offset = 500_000, lines = 3)
        assertTrue(mid.output, mid.output.contains("showing 500000-500002 of $n]"))
        assertTrue(mid.output.contains("line 0500000 ") && mid.output.contains("line 0500002 "))

        val tail = read(f, lines = 3, direction = "tail")
        assertTrue(tail.output, tail.output.contains("showing ${n - 2}-$n of $n]"))
        assertTrue(tail.output.endsWith("line %07d %s".format(n, "x".repeat(50))))

        // No lines limit: the whole file is "selected", the 15000-char cap
        // applies and next_offset points at the first line not returned.
        val all = read(f)
        assertTrue(all.output, all.output.contains("| truncated at 15000 chars, next_offset="))
        val allTail = read(f, direction = "tail")
        assertTrue(allTail.output, allTail.output.contains("| truncated at 15000 chars, retry with a smaller lines value]"))
        assertTrue(allTail.output.endsWith("line %07d %s".format(n, "x".repeat(50))))
    }

    @Test
    fun `a single 40 MB line is truncated, not loaded`() {
        val f = File(tmp, "oneline.txt")
        f.outputStream().buffered().use { out ->
            val chunk = "abcdefghij中".toByteArray()
            repeat(40 * 1024 * 1024 / chunk.size) { out.write(chunk) }
        }
        val head = read(f, maxLength = 100)
        assertTrue(head.output, head.output.startsWith("[/p | ${f.length()} bytes | 1 lines | showing 1-1 of 1 | truncated at 100 chars"))
        assertEquals(100, head.output.substringAfter('\n').length)
        val tail = read(f, maxLength = 100, direction = "tail")
        assertEquals(100, tail.output.substringAfter('\n').length)
        assertTrue(tail.output.endsWith("abcdefghij中"))
    }

    // --- file_edit size cap ---------------------------------------------------

    @Test
    fun `file_edit refuses a file over 8 MB with the iOS message and leaves it untouched`() {
        val f = File(tmp, "huge.txt")
        f.outputStream().buffered().use { out ->
            val line = "needle and hay\n".toByteArray()
            repeat((FileToolIO.MAX_EDIT_BYTES / line.size + 1).toInt()) { out.write(line) }
        }
        val before = f.length()
        assertTrue(before > FileToolIO.MAX_EDIT_BYTES)
        val r = FileEditTool.edit(f, "/var/minis/workspace/huge.txt", "needle", "pin", true, "t")
        assertFalse(r.success)
        assertEquals(
            "Error: /var/minis/workspace/huge.txt is $before bytes; file_edit handles files up to 8388608 bytes. " +
                "Use the shell (e.g. sed or a script) for larger files.",
            r.output,
        )
        assertEquals(before, f.length())
    }

    @Test
    fun `file_edit at exactly 8 MB still edits`() {
        val f = File(tmp, "edge.txt")
        val body = ByteArray(FileToolIO.MAX_EDIT_BYTES.toInt() - 6) { 'a'.code.toByte() }
        f.writeBytes(body + "NEEDLE".toByteArray())
        assertEquals(FileToolIO.MAX_EDIT_BYTES, f.length())
        val r = FileEditTool.edit(f, "/p", "NEEDLE", "pin", false, "t")
        assertTrue(r.output, r.success)
        assertTrue(f.readText().endsWith("apin"))
    }

    @Test
    fun `file_edit keeps its matching behaviour`() {
        val f = File(tmp, "s.txt")
        f.writeText("a b a")
        assertEquals("Error: old_string not found in /p", FileEditTool.edit(f, "/p", "zzz", "y", false, "t").output)
        val twice = FileEditTool.edit(f, "/p", "a", "y", false, "t")
        assertFalse(twice.success)
        assertTrue(twice.output.startsWith("Error: old_string found 2 times in /p."))
        val all = FileEditTool.edit(f, "/p", "a", "y", true, "t")
        assertEquals("Edited /p (2 replacement(s), 5 bytes)", all.output)
        assertEquals("y b y", f.readText())
    }

    // --- Runner: timeout and OOM ----------------------------------------------

    @Test
    fun `run gives control back after the timeout with an agent-facing message`() {
        val started = System.nanoTime()
        try {
            FileToolIO.run("Reading /var/minis/mounts/x/a.txt", timeoutMs = 1_000) { Thread.sleep(10_000) }
            fail("expected TimedOut")
        } catch (e: FileToolIO.TimedOut) {
            assertEquals(
                "Reading /var/minis/mounts/x/a.txt did not finish within 1s (the file may be on a mounted folder " +
                    "or storage provider that is not responding).",
                e.message,
            )
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertEquals(
            "Writing /a did not finish within 45s (the file may be on a mounted folder or storage provider " +
                "that is not responding). The write may still complete; re-read the file before retrying.",
            FileToolIO.timedOutMessage("Writing /a", FileToolIO.IO_TIMEOUT_MS, writes = true),
        )
    }

    @Test
    fun `a read blocked in open() of a FIFO with no writer times out`() {
        val fifo = File(tmp, "fifo")
        val made = runCatching { ProcessBuilder("mkfifo", fifo.absolutePath).start().waitFor() == 0 }.getOrDefault(false)
        if (!made) return // no mkfifo on this host; the sleep test above covers the runner
        try {
            FileToolIO.run("Reading /fifo", timeoutMs = 500) { FileReadTool.read(fifo, "/fifo", 1, null, 100, "head", "t") }
            fail("expected TimedOut")
        } catch (_: FileToolIO.TimedOut) {
        } finally {
            // Unblock the stuck open() so the pool thread can finish.
            runCatching { java.io.FileOutputStream(fifo).close() }
        }
    }

    @Test
    fun `an OutOfMemoryError becomes a tool error instead of killing the app`() {
        try {
            FileToolIO.run("Reading /big") { throw OutOfMemoryError("Java heap space") }
            fail("expected OutOfMemory")
        } catch (e: FileToolIO.OutOfMemory) {
            assertEquals("Reading /big ran out of memory.", e.message)
        }
        // Ordinary exceptions pass through unchanged.
        try {
            FileToolIO.run("Reading /x") { throw java.io.IOException("Permission denied") }
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertEquals("Permission denied", e.message)
        }
    }

    // --- Source guards ----------------------------------------------------------

    @Test
    fun `file tools never load a whole file outside the guard`() {
        val read = ProductionSources.read("tools/FileReadTool.kt")
        assertFalse("file_read must stream (FileToolIO.scanLines)", read.contains(".readLines(") || read.contains(".readText("))
        assertTrue(read.contains("FileToolIO.scanLines("))
        assertTrue(read.contains("FileToolIO.run(\"Reading \$path\")"))

        val edit = ProductionSources.read("tools/FileEditTool.kt")
        assertTrue(edit.contains("FileToolIO.run(\"Editing \$path\", writes = true)"))
        assertTrue(
            "the size check must come before readText()",
            edit.indexOf("FileToolIO.MAX_EDIT_BYTES)") in 0 until edit.indexOf("file.readText()"),
        )

        val write = ProductionSources.read("tools/FileWriteTool.kt")
        assertTrue(write.contains("FileToolIO.run(\"Writing \$path\", writes = true)"))
    }
}
