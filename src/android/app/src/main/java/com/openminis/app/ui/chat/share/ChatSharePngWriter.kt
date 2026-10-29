package com.openminis.app.ui.chat.share

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs

/**
 * [T-multi-select-share] Writes an opaque RGB PNG row by row, so a long image of tens
 * of thousands of rows is encoded strip by strip without ever holding the whole
 * picture in memory (iOS composes one bitmap of up to 60 MP; an Android heap cannot
 * be relied on for that, and HWUI cannot draw a bitmap that tall anyway).
 *
 * The image data (one zlib stream across every row, cut into IDAT chunks) goes to
 * [idatFile] as it arrives; [finish] then writes the real file — signature, IHDR with
 * the final height, the IDAT chunks, IEND — once the height is known. Each row picks
 * the None / Sub / Up filter with the smallest sum of absolute values, the usual
 * libpng heuristic. Pure JVM: ChatSharePngWriterTest decodes the output with ImageIO.
 */
internal class ChatSharePngWriter(private val width: Int, private val idatFile: File) : AutoCloseable {
    private val deflater = Deflater(6)
    private val idat = DataOutputStream(BufferedOutputStream(FileOutputStream(idatFile), 1 shl 16))
    private val rowBytes = width * 3
    private var prev = ByteArray(rowBytes)
    private var cur = ByteArray(rowBytes)
    private val filtered = Array(3) { ByteArray(rowBytes + 1) }
    private val deflateOut = ByteArray(1 shl 16)
    private val pending = java.io.ByteArrayOutputStream(1 shl 16)
    var rows = 0
        private set

    /** Appends [count] rows of ARGB pixels (alpha ignored) starting at [offset], [width] per row. */
    fun writeRows(argb: IntArray, offset: Int, count: Int) {
        for (r in 0 until count) {
            val base = offset + r * width
            var j = 0
            for (x in 0 until width) {
                val p = argb[base + x]
                cur[j] = (p shr 16).toByte()
                cur[j + 1] = (p shr 8).toByte()
                cur[j + 2] = p.toByte()
                j += 3
            }
            deflateRow(pickFilter())
            val t = prev; prev = cur; cur = t
            rows++
        }
    }

    private fun pickFilter(): ByteArray {
        val none = filtered[0]; val sub = filtered[1]; val up = filtered[2]
        none[0] = 0; sub[0] = 1; up[0] = 2
        var sNone = 0L; var sSub = 0L; var sUp = 0L
        for (i in 0 until rowBytes) {
            val c = cur[i].toInt() and 0xFF
            val a = if (i >= 3) cur[i - 3].toInt() and 0xFF else 0
            val b = prev[i].toInt() and 0xFF
            val vNone = c.toByte(); val vSub = (c - a).toByte(); val vUp = (c - b).toByte()
            none[i + 1] = vNone; sub[i + 1] = vSub; up[i + 1] = vUp
            sNone += abs(vNone.toInt()); sSub += abs(vSub.toInt()); sUp += abs(vUp.toInt())
        }
        return when {
            sUp <= sSub && sUp <= sNone -> up
            sSub <= sNone -> sub
            else -> none
        }
    }

    private fun deflateRow(row: ByteArray) {
        deflater.setInput(row, 0, row.size)
        while (!deflater.needsInput()) drain(Deflater.NO_FLUSH)
    }

    private fun drain(flush: Int) {
        val n = deflater.deflate(deflateOut, 0, deflateOut.size, flush)
        if (n > 0) {
            pending.write(deflateOut, 0, n)
            if (pending.size() >= CHUNK) flushChunk()
        }
    }

    private fun flushChunk() {
        if (pending.size() == 0) return
        writeChunk(idat, "IDAT", pending.toByteArray())
        pending.reset()
    }

    /** Ends the image data and writes the complete PNG to [out]; [idatFile] is removed. */
    fun finish(out: File) {
        deflater.finish()
        while (!deflater.finished()) drain(Deflater.NO_FLUSH)
        flushChunk()
        idat.close()
        deflater.end()
        DataOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 16)).use { png ->
            png.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            val ihdr = java.io.ByteArrayOutputStream().also { b ->
                DataOutputStream(b).apply {
                    writeInt(width); writeInt(rows)
                    writeByte(8) // bit depth
                    writeByte(2) // color type: RGB
                    writeByte(0); writeByte(0); writeByte(0) // compression, filter, interlace
                }
            }.toByteArray()
            writeChunk(png, "IHDR", ihdr)
            idatFile.inputStream().use { it.copyTo(png, 1 shl 16) }
            writeChunk(png, "IEND", ByteArray(0))
        }
        idatFile.delete()
    }

    override fun close() {
        runCatching { idat.close() }
        deflater.end()
        idatFile.delete()
    }

    private companion object {
        const val CHUNK = 1 shl 16

        fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.writeInt(data.size)
            out.write(typeBytes)
            out.write(data)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            out.writeInt(crc.value.toInt())
        }
    }
}
