package com.openminis.app.ui.chat.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.math.abs

/** [T-multi-select-share] The streamed long-image PNG decodes to exactly the rows written, across strips. */
class ChatSharePngWriterTest {

    private fun pixel(x: Int, y: Int): Int =
        if ((x + y) % 7 == 0) 0xFF000000.toInt() or ((x * 5 and 0xFF) shl 16) or ((y * 3 and 0xFF) shl 8) or 0x40
        else 0xFFFFFFFF.toInt()

    @Test fun `rows written in strips decode back pixel for pixel`() {
        val dir = kotlin.io.path.createTempDirectory("pngw").toFile()
        try {
            val w = 37
            val h = 150
            val writer = ChatSharePngWriter(w, File(dir, "idat.tmp"))
            var y = 0
            // Uneven strips, as the renderer produces at a part's end.
            for (strip in listOf(64, 50, 36)) {
                val px = IntArray(w * strip) { i -> pixel(i % w, y + i / w) }
                writer.writeRows(px, 0, strip)
                y += strip
            }
            val out = File(dir, "out.png")
            writer.finish(out)
            assertFalse("temp image data is removed", File(dir, "idat.tmp").exists())
            val (size, rgb) = decode(out.readBytes())
            assertEquals(w to h, size)
            for (yy in 0 until h) for (x in 0 until w) {
                val p = pixel(x, yy)
                val o = (yy * w + x) * 3
                assertArrayEquals(
                    "pixel $x,$yy",
                    byteArrayOf((p shr 16).toByte(), (p shr 8).toByte(), p.toByte()),
                    rgb.copyOfRange(o, o + 3),
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A minimal PNG reader for 8-bit RGB: checks every chunk CRC, inflates IDAT, undoes the filters. */
    private fun decode(bytes: ByteArray): Pair<Pair<Int, Int>, ByteArray> {
        val input = DataInputStream(bytes.inputStream())
        val sig = ByteArray(8).also { input.readFully(it) }
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10), sig)
        var w = 0; var h = 0
        val idat = ByteArrayOutputStream()
        while (true) {
            val len = input.readInt()
            val type = ByteArray(4).also { input.readFully(it) }
            val data = ByteArray(len).also { input.readFully(it) }
            val crc = CRC32().apply { update(type); update(data) }
            assertEquals("CRC of ${String(type)}", crc.value.toInt(), input.readInt())
            when (String(type)) {
                "IHDR" -> {
                    val d = DataInputStream(data.inputStream())
                    w = d.readInt(); h = d.readInt()
                    assertEquals(8, d.readUnsignedByte()); assertEquals(2, d.readUnsignedByte())
                }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }
        val inflater = Inflater().apply { setInput(idat.toByteArray()) }
        val raw = ByteArray(h * (w * 3 + 1))
        var n = 0
        while (n < raw.size && !inflater.finished()) n += inflater.inflate(raw, n, raw.size - n)
        assertEquals(raw.size, n)
        val stride = w * 3
        val out = ByteArray(h * stride)
        for (row in 0 until h) {
            val filter = raw[row * (stride + 1)].toInt()
            for (i in 0 until stride) {
                val x = raw[row * (stride + 1) + 1 + i].toInt() and 0xFF
                val a = if (i >= 3) out[row * stride + i - 3].toInt() and 0xFF else 0
                val b = if (row > 0) out[(row - 1) * stride + i].toInt() and 0xFF else 0
                val c = if (row > 0 && i >= 3) out[(row - 1) * stride + i - 3].toInt() and 0xFF else 0
                val v = when (filter) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    4 -> {
                        val p = a + b - c
                        val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> error("bad filter $filter")
                }
                out[row * stride + i] = v.toByte()
            }
        }
        return (w to h) to out
    }
}
