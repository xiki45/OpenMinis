package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * [T-android-pshell-utf8-split] PersistentShell reads the shell's output in
 * chunks of up to 4096 bytes whose boundaries are arbitrary. A multi-byte
 * character cut by a boundary must come out whole, not as two U+FFFD.
 */
class Utf8StreamDecoderTest {

    /** Hands out [data] with a read boundary after every offset in [cuts]. */
    private class ChunkedStream(data: ByteArray, cuts: Set<Int>) : InputStream() {
        private val source = ByteArrayInputStream(data)
        private val boundaries = (cuts + data.size).filter { it in 1..data.size }.sorted()
        private var pos = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val next = boundaries.firstOrNull { it > pos } ?: return -1
            val n = source.read(b, off, minOf(len, next - pos))
            pos += n
            return n
        }
    }

    /** The read loop of PersistentShell, reduced to its decoding. */
    private fun readAll(data: ByteArray, cuts: Set<Int>): String {
        val stream = ChunkedStream(data, cuts)
        val decoder = Utf8StreamDecoder()
        val buffer = ByteArray(4096)
        val out = StringBuilder()
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.append(decoder.decode(buffer, 0, n))
        }
        return out.append(decoder.finish()).toString()
    }

    /** [expected] must come out for one cut at every byte position, and for a cut after every byte. */
    private fun assertEveryCut(expected: String, data: ByteArray) {
        assertEquals("one read", expected, readAll(data, emptySet()))
        for (cut in 1 until data.size) {
            assertEquals("cut after byte $cut of ${data.size}", expected, readAll(data, setOf(cut)))
        }
        assertEquals("one byte per read", expected, readAll(data, (1 until data.size).toSet()))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `Chinese text survives a cut at every byte`() {
        val text = "ls 输出：文件 已保存 ok\n第二行"
        assertEveryCut(text, text.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `four-byte emoji survive a cut at every byte`() {
        val text = "done 🎉 then 👨‍👩‍👧 and 🀄 end"
        assertEveryCut(text, text.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `plain ASCII is unchanged`() {
        val text = "total 8\n-rw-r--r-- 1 root root 12 file.txt\n__MINIS_DONE_ab12cd34_EXIT_0__\n"
        assertEveryCut(text, text.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `each stray continuation byte at the start is one replacement character, and the rest is intact`() {
        // 0x96 0x87 are the tail of a character whose lead byte was never read.
        assertEveryCut("��件 ok", bytes(0x96, 0x87) + "件 ok".toByteArray(Charsets.UTF_8))
        assertEveryCut("�abc", bytes(0x80) + "abc".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `half a character at the end of the stream is one replacement character`() {
        val full = "结尾 中".toByteArray(Charsets.UTF_8)
        // "中" is E4 B8 AD: drop one, then two, of its bytes.
        assertEveryCut("结尾 �", full.copyOf(full.size - 1))
        assertEveryCut("结尾 �", full.copyOf(full.size - 2))
        // A 4-byte emoji missing its last byte.
        val emoji = "x🎉".toByteArray(Charsets.UTF_8)
        assertEveryCut("x�", emoji.copyOf(emoji.size - 1))
    }

    @Test
    fun `an invalid byte in the middle is replaced without swallowing its neighbours`() {
        assertEveryCut("a�b中", bytes('a'.code, 0xFF, 'b'.code) + "中".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `nothing is held back once a character is complete, and finish is empty after clean input`() {
        val decoder = Utf8StreamDecoder()
        val zhong = "中".toByteArray(Charsets.UTF_8)
        assertEquals("", decoder.decode(zhong, 0, 1))
        assertEquals("", decoder.decode(zhong, 1, 1))
        assertEquals("中", decoder.decode(zhong, 2, 1))
        assertEquals("a", decoder.decode(bytes('a'.code), 0, 1))
        assertEquals("", decoder.finish())
        assertEquals("usable again after finish", "中", decoder.decode(zhong, 0, 3))
    }

    @Test
    fun `completion markers are found whatever the cuts - they are matched on the decoded text`() {
        val text = "输出🎉\n__MINIS_DONE_ab12cd34_EXIT_0__\n"
        val data = text.toByteArray(Charsets.UTF_8)
        for (cut in 1 until data.size) {
            assertTrue("cut $cut", readAll(data, setOf(cut)).contains("__MINIS_DONE_ab12cd34_EXIT_0__"))
        }
    }

    @Test
    fun `PersistentShell's read loop decodes through it and flushes at end of stream`() {
        val shell = ProductionSources.read("sandbox/PersistentShell.kt")
        val loop = shell.substringAfter("private fun readLoop(p: Process) {").substringBefore("// Process exited")
        assertFalse("no per-read String(...) left", loop.contains("String(buffer, 0, n"))
        assertTrue(loop.contains("val decoder = Utf8StreamDecoder()"))
        assertTrue(loop.contains("if (n < 0) decoder.finish() else decoder.decode(buffer, 0, n)"))
    }
}
