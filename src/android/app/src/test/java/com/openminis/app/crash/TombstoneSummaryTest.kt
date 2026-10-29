package com.openminis.app.crash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * [GH #453] The exitinfo tombstone summary. The protobuf here is built by hand
 * with tombstone.proto's field numbers (Tombstone.pid 5, tid 6, abort_message
 * 14, threads 16 as map entries {key 1, value 2 -> Thread{id 1, name 2}},
 * memory_mappings 17, open_fds 19), so the test pins the numbers the parser
 * relies on.
 */
class TombstoneSummaryTest {

    private class Proto {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (true) { if (x and 0x7F.inv().toLong() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun u(field: Int, v: Long) { tag(field, 0); varint(v) }
        fun bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray())
        fun msg(field: Int, build: Proto.() -> Unit) = bytes(field, Proto().apply(build).out.toByteArray())
        fun fixed64(field: Int) { tag(field, 1); out.write(ByteArray(8)) }
        fun toBytes() = out.toByteArray()
    }

    private fun tombstone(threadNames: List<String>, maps: Int, fds: Int, abort: String? = "Scudo ERROR: internal map failure"): ByteArray =
        Proto().apply {
            u(1, 2) // arch
            str(2, "Xiaomi/popsicle/popsicle:16/BP2A/...")
            u(5, 488); u(6, 9001); u(7, 10428)
            str(9, "com.openminis.app")
            abort?.let { str(14, it) }
            threadNames.forEachIndexed { i, n ->
                msg(16) {
                    u(1, 9000L + i)
                    msg(2) {
                        u(1, 9000L + i)
                        str(2, n)
                        // A register and a backtrace frame, so the name is not the last field.
                        msg(3) { str(1, "x0"); u(2, 0x1234) }
                        msg(4) { u(1, 0x70000000); str(4, "libc.so") }
                        fixed64(6)
                    }
                }
            }
            repeat(maps) { msg(17) { u(1, 0x7000000000L + it * 4096L); u(2, 0x7000001000L + it * 4096L); str(7, "[anon:stack_and_tls:$it]") } }
            repeat(fds) { msg(19) { u(1, it.toLong()); str(2, "/dev/null") } }
            u(22, 4096)
        }.toBytes()

    @Test fun `the whole proto tombstone is counted, past the raw head`() {
        val names = List(700) { "mn-offload-wk" } + List(64) { "DefaultDispatch" } + "main" + "RenderThread"
        val bytes = tombstone(names, maps = 65_000, fds = 300)
        assertTrue("well past the 256 kB head", bytes.size > 4 * 256 * 1024)
        val r = TombstoneSummary.read(ByteArrayInputStream(bytes), 256 * 1024)
        assertTrue(r.summary, r.summary.startsWith("format=proto bytes=${bytes.size} pid=488 crashTid=9001 threads=766 maps=65000 openFds=300\n"))
        assertTrue(r.summary, r.summary.contains("abortMessage=Scudo ERROR: internal map failure\n"))
        assertTrue(r.summary, r.summary.endsWith("threadsByName=mn-offload-wk:700,DefaultDispatch:64,RenderThread:1,main:1"))
        assertArrayEquals("the raw section keeps exactly the first 256 kB", bytes.copyOf(256 * 1024), r.head)
    }

    @Test fun `a short trace keeps all of it as the head`() {
        val bytes = tombstone(listOf("main"), maps = 3, fds = 1, abort = null)
        val r = TombstoneSummary.read(ByteArrayInputStream(bytes), 256 * 1024)
        assertTrue(r.summary, r.summary.contains("threads=1 maps=3 openFds=1"))
        assertTrue(!r.summary.contains("abortMessage"))
        assertArrayEquals(bytes, r.head)
    }

    @Test fun `a text tombstone is summarised from its headers and memory map`() {
        val text = buildString {
            append("*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n")
            append("Build fingerprint: 'x'\n")
            append("pid: 488, tid: 9001, name: DefaultDispatch  >>> com.openminis.app <<<\n")
            append("Abort message: 'Scudo ERROR: internal map failure (error desc=Out of memory)'\n")
            append("    x0  0000000000000000\n\n")
            append("memory map (3 entries):\n")
            append("    00000070'00000000-00000070'00001000 r--     0   1000 /system/lib64/libc.so\n")
            append("--->00000070'00001000-00000070'00002000 r-x  1000   1000 /system/lib64/libc.so\n")
            append("    00000070'00002000-00000070'00003000 rw-     0   1000 [anon:stack]\n")
            append("\n")
            append("--- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---\n")
            append("pid: 488, tid: 9002, name: mn-offload-wk  >>> com.openminis.app <<<\n")
            append("pid: 488, tid: 9003, name: mn-offload-wk  >>> com.openminis.app <<<\n")
        }
        val r = TombstoneSummary.read(ByteArrayInputStream(text.toByteArray()), 256 * 1024)
        assertTrue(r.summary, r.summary.startsWith("format=text"))
        assertTrue(r.summary, r.summary.contains("threads=3 maps=3"))
        assertTrue(r.summary, r.summary.contains("abortMessage='Scudo ERROR: internal map failure (error desc=Out of memory)'"))
        assertTrue(r.summary, r.summary.endsWith("threadsByName=mn-offload-wk:2,DefaultDispatch:1"))
        assertEquals(text, String(r.head))
    }

    @Test fun `garbage is reported as not summarised and the raw head is still complete`() {
        val junk = ByteArray(300_000) { (it * 31 + 7).toByte() }.also { it[0] = 0x0F } // wire type 7 straight away
        val r = TombstoneSummary.read(ByteArrayInputStream(junk), 256 * 1024)
        assertTrue(r.summary, r.summary.startsWith("(not summarised"))
        assertArrayEquals(junk.copyOf(256 * 1024), r.head)
    }

    @Test fun `an empty trace says so`() {
        val r = TombstoneSummary.read(ByteArrayInputStream(ByteArray(0)), 1024)
        assertEquals("(empty trace)", r.summary)
        assertEquals(0, r.head.size)
    }

    @Test fun `the exit report formats the last sample with its age`() {
        assertEquals(
            "Last resource sample (17s before exit):\nsampled=x threads=1900\nthreadsByName=mn-offload-wk:1700",
            ExitInfoCollector.formatResourceSample("sampled=x threads=1900\nthreadsByName=mn-offload-wk:1700\n", 17),
        )
    }
}
