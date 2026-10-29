package com.openminis.app.crash

import com.openminis.app.diagnostics.ResourceWatermark
import java.io.InputStream

/**
 * [GH #453] A few lines summarising a WHOLE tombstone - thread count, threads
 * per name, memory-mapping count, open fds, abort message - for the top of an
 * exitinfo log.
 *
 * Why: from API 31, `ApplicationExitInfo.getTraceInputStream()` for a native
 * crash is debuggerd's tombstone in protobuf form (system/core/debuggerd/proto/
 * tombstone.proto), and [ExitInfoCollector] keeps only its first
 * [ExitInfoCollector.MAX_TRACE_BYTES]. For a process killed by thread or
 * mapping exhaustion that head is a binary dump of the first few dozen
 * threads: the thread list and the 60k-line memory map that would answer
 * "who created all these" are past the cut. This reads the full stream once,
 * keeping only counters, and copies the head out on the way so the existing
 * raw section is unchanged.
 *
 * Fields used (tombstone.proto): Tombstone.pid = 5, tid = 6, abort_message =
 * 14, threads = 16 (map<uint32, Thread>: entry key = 1, value = 2),
 * memory_mappings = 17, open_fds = 19; Thread.name = 2. A text tombstone
 * (older platforms, some vendors) is recognised by its leading `*** ` and
 * summarised from its `pid: …, tid: …, name: …` headers and memory-map lines.
 * Anything else, or a malformed stream, yields a summary that says so.
 */
internal object TombstoneSummary {

    class Result(val summary: String, val head: ByteArray)

    /** Hard stop on bytes read, so a huge or endless stream cannot pin the collector. */
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** A Thread entry bigger than this is skipped rather than buffered (its name is lost, its count is not). */
    private const val MAX_THREAD_ENTRY_BYTES = 1 shl 20

    fun read(input: InputStream, headBytes: Int): Result {
        val tee = HeadTee(input, headBytes)
        val summary = try {
            val first = tee.read()
            when {
                first < 0 -> "(empty trace)"
                first == '*'.code -> TextParser(tee).parse()
                else -> ProtoParser(tee, first).parse()
            }
        } catch (t: Throwable) {
            "(not summarised: ${t.javaClass.simpleName}: ${t.message})"
        }
        // A parse that stopped early still owes the raw section its full head.
        runCatching { tee.fillHead() }
        return Result(summary, tee.head())
    }

    // ── protobuf ─────────────────────────────────────────────────────────────

    private class Malformed(msg: String) : Exception(msg)

    private class ProtoParser(private val s: HeadTee, private var pending: Int) {
        private val names = ArrayList<String>()
        private var threads = 0
        private var maps = 0
        private var fds = 0
        private var pid = -1L
        private var tid = -1L
        private var abort: String? = null

        fun parse(): String {
            while (true) {
                val tag = if (pending >= 0) varintFrom(pending).also { pending = -1 } else (readVarintOrEof() ?: break)
                val field = (tag ushr 3).toInt()
                when (val wire = (tag and 7).toInt()) {
                    0 -> { val v = readVarint(); if (field == 5) pid = v; if (field == 6) tid = v }
                    1 -> skip(8)
                    5 -> skip(4)
                    2 -> {
                        val len = readLength()
                        when (field) {
                            14 -> abort = String(readBytes(minOf(len, 4096)), Charsets.UTF_8).also { skip(len - minOf(len, 4096)) }
                            16 -> { threads++; if (len <= MAX_THREAD_ENTRY_BYTES) threadName(readBytes(len))?.let(names::add) else skip(len) }
                            17 -> { maps++; skip(len) }
                            19 -> { fds++; skip(len) }
                            else -> skip(len)
                        }
                    }
                    else -> throw Malformed("wire type $wire at byte ${s.count}")
                }
            }
            if (threads == 0 && maps == 0) throw Malformed("no threads or mappings in ${s.count} bytes")
            return buildString {
                append("format=proto bytes=${s.count} pid=$pid crashTid=$tid threads=$threads maps=$maps openFds=$fds\n")
                abort?.let { append("abortMessage=${it.trim()}\n") }
                append("threadsByName=${ResourceWatermark.breakdown(names)}")
            }
        }

        /** Thread.name from one map entry (key = 1, value = 2 -> Thread). */
        private fun threadName(entry: ByteArray): String? {
            val thread = field(entry, 0, entry.size, 2) ?: return null
            val name = field(entry, thread.first, thread.second, 2) ?: return null
            return String(entry, name.first, name.second - name.first, Charsets.UTF_8)
        }

        /** [start, end) of the first length-delimited [want] field inside entry[from, to). */
        private fun field(b: ByteArray, from: Int, to: Int, want: Int): Pair<Int, Int>? {
            var i = from
            fun varint(): Long {
                var r = 0L; var shift = 0
                while (i < to) {
                    val x = b[i++].toInt() and 0xFF
                    r = r or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return r
                    shift += 7
                    if (shift > 63) break
                }
                throw Malformed("varint in thread entry")
            }
            while (i < to) {
                val tag = varint()
                when ((tag and 7).toInt()) {
                    0 -> varint()
                    1 -> i += 8
                    5 -> i += 4
                    2 -> {
                        val len = varint()
                        if (len < 0 || i + len > to) throw Malformed("length in thread entry")
                        if ((tag ushr 3).toInt() == want) return i to (i + len.toInt())
                        i += len.toInt()
                    }
                    else -> throw Malformed("wire type in thread entry")
                }
            }
            return null
        }

        private fun readVarintOrEof(): Long? {
            val b = s.read()
            return if (b < 0) null else varintFrom(b)
        }

        private fun readVarint(): Long = varintFrom(s.read().also { if (it < 0) throw Malformed("eof in varint") })

        private fun varintFrom(firstByte: Int): Long {
            var b = firstByte
            var r = 0L
            var shift = 0
            while (true) {
                r = r or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return r
                shift += 7
                if (shift > 63) throw Malformed("varint too long")
                b = s.read()
                if (b < 0) throw Malformed("eof in varint")
            }
        }

        private fun readLength(): Int {
            val len = readVarint()
            if (len < 0 || len > MAX_TOTAL_BYTES) throw Malformed("length $len")
            return len.toInt()
        }

        private fun readBytes(n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = s.read(out, got, n - got)
                if (r < 0) throw Malformed("eof in field")
                got += r
            }
            return out
        }

        private fun skip(n: Int) {
            var left = n
            val scratch = ByteArray(minOf(n, 8192).coerceAtLeast(1))
            while (left > 0) {
                val r = s.read(scratch, 0, minOf(left, scratch.size))
                if (r < 0) throw Malformed("eof while skipping")
                left -= r
            }
        }
    }

    // ── text ─────────────────────────────────────────────────────────────────

    private class TextParser(private val s: HeadTee) {
        private val header = Regex("""^pid: \d+, tid: \d+, name: (.*?)\s+>>>""")
        private val mapLine = Regex("""^(\s+|--->)[0-9a-f']+-[0-9a-f']+\s""")

        fun parse(): String {
            val names = ArrayList<String>()
            var maps = 0
            var inMaps = false
            var abort: String? = null
            val reader = s.bufferedReader(Charsets.UTF_8)
            reader.lineSequence().forEach { line ->
                header.find(line)?.let { names.add(it.groupValues[1]); inMaps = false; return@forEach }
                when {
                    line.startsWith("memory map") -> inMaps = true
                    inMaps && mapLine.containsMatchIn(line) -> maps++
                    inMaps && line.isBlank() -> inMaps = false
                    abort == null && line.startsWith("Abort message:") -> abort = line.substringAfter(':').trim()
                }
            }
            return buildString {
                append("format=text bytes=${s.count} threads=${names.size} maps=$maps\n")
                abort?.let { append("abortMessage=$it\n") }
                append("threadsByName=${ResourceWatermark.breakdown(names)}")
            }
        }
    }

    // ── stream that keeps its first bytes ───────────────────────────────────

    /** Passes reads through, copying the first [cap] bytes aside, and stops at [MAX_TOTAL_BYTES]. */
    private class HeadTee(private val input: InputStream, cap: Int) : InputStream() {
        private val buf = ByteArray(cap)
        private var held = 0
        var count = 0L
            private set

        fun head(): ByteArray = buf.copyOf(held)

        fun fillHead() {
            while (held < buf.size) {
                val n = input.read(buf, held, buf.size - held)
                if (n <= 0) return
                held += n
                count += n
            }
        }

        override fun read(): Int {
            if (count >= MAX_TOTAL_BYTES) return -1
            val b = input.read()
            if (b >= 0) { if (held < buf.size) buf[held++] = b.toByte(); count++ }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (count >= MAX_TOTAL_BYTES) return -1
            val n = input.read(b, off, minOf(len.toLong(), MAX_TOTAL_BYTES - count).toInt())
            if (n > 0) {
                val copy = minOf(n, buf.size - held)
                if (copy > 0) { System.arraycopy(b, off, buf, held, copy); held += copy }
                count += n
            }
            return n
        }
    }
}
