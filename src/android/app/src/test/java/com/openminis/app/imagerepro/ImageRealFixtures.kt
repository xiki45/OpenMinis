package com.openminis.app.imagerepro

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fixtures for the ImageReal* suite (real production functions). A
 * self-contained copy of the ImageRepro* generators and parsers, so this suite
 * does not depend on the previous round's files, plus helpers for the real
 * functions: a test Context with a real filesDir, a canonical dump of
 * LLMMessage for byte-level comparison, and reflection to reach ChatViewModel's
 * private entry points without running its constructor.
 *
 * Images are generated with the JDK (java.util.zip, and javax.imageio through
 * reflection: the Android unit-test compile classpath has no java.desktop).
 */
object ImageRealFixtures {

    // ── generators ──────────────────────────────────────────────────────────

    /**
     * A real, valid PNG of [w]×[h] (8-bit RGB, or RGBA when [alpha]),
     * streamed row by row so a 2760×21891 image never exists as a bitmap.
     * Solid colour; alpha rows are fully transparent.
     */
    fun png(w: Int, h: Int, alpha: Boolean = false, rgb: Int = 0x3366CC): ByteArray {
        val bpp = if (alpha) 4 else 3
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeInt(w); writeInt(h); writeByte(8); writeByte(if (alpha) 6 else 2)
                writeByte(0); writeByte(0); writeByte(0)
            }
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        val row = ByteArray(1 + w * bpp)
        for (x in 0 until w) {
            val o = 1 + x * bpp
            row[o] = (rgb shr 16).toByte(); row[o + 1] = (rgb shr 8).toByte(); row[o + 2] = rgb.toByte()
            if (alpha) row[o + 3] = 0
        }
        val idat = ByteArrayOutputStream()
        DeflaterOutputStream(idat, Deflater(Deflater.BEST_SPEED), 1 shl 16).use { z ->
            repeat(h) { z.write(row) }
        }
        chunk(out, "IDAT", idat.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        d.write(t); d.write(data)
        val crc = CRC32().apply { update(t); update(data) }
        d.writeInt(crc.value.toInt())
    }

    /**
     * A real baseline JPEG of [w]×[h] encoded by the JDK's ImageIO (default
     * quality 0.75). Reached through reflection: the Android unit-test compile
     * classpath has no java.desktop module, the test JVM does. Grayscale keeps
     * the 1080×32768 case at a 35 MB raster.
     */
    fun jpeg(w: Int, h: Int, gray: Boolean = false): ByteArray = awtEncode(w, h, if (gray) 10 else 5, "jpg")

    private fun awtEncode(w: Int, h: Int, type: Int, format: String): ByteArray {
        System.setProperty("java.awt.headless", "true")
        val biClass = Class.forName("java.awt.image.BufferedImage")
        val img = biClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(w, h, type)
        // Fill with a mid colour so the image is not all-zero.
        val g = biClass.getMethod("createGraphics").invoke(img)
        val gClass = Class.forName("java.awt.Graphics")
        val colorClass = Class.forName("java.awt.Color")
        val color = colorClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(60, 120, 200)
        gClass.getMethod("setColor", colorClass).invoke(g, color)
        gClass.getMethod("fillRect", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(g, 0, 0, w, h)
        gClass.getMethod("dispose").invoke(g)
        val out = ByteArrayOutputStream()
        val ok = Class.forName("javax.imageio.ImageIO")
            .getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, java.io.OutputStream::class.java)
            .invoke(null, img, format, out) as Boolean
        check(ok) { "no ImageIO writer for $format" }
        return out.toByteArray()
    }

    /**
     * [jpeg] with an EXIF APP1 segment declaring Orientation = [orientation],
     * inserted straight after SOI. Stored pixels stay [w]×[h]; a decoder that
     * honours EXIF shows h×w for orientation 6.
     */
    fun jpegWithExifOrientation(w: Int, h: Int, orientation: Int): ByteArray {
        val base = jpeg(w, h)
        val tiff = ByteArrayOutputStream().also { b ->
            // Little-endian TIFF header, one IFD entry: 0x0112 SHORT 1 = orientation.
            b.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00))
            b.write(byteArrayOf(0x01, 0x00)) // 1 entry
            b.write(byteArrayOf(0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, orientation.toByte(), 0x00, 0x00, 0x00))
            b.write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // next IFD
        }.toByteArray()
        val payload = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
        return base.copyOfRange(0, 2) + app1 + base.copyOfRange(2, base.size)
    }

    /**
     * NOT a decodable HEIC: an ISO-BMFF `ftyp heic` box followed by filler.
     * The JDK cannot encode HEIC; this only exercises sniffing / labelling.
     */
    fun heicHeaderOnly(size: Int = 4096): ByteArray =
        byteArrayOf(0x00, 0x00, 0x00, 0x18) + "ftypheic".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0) + "mif1heic".toByteArray(Charsets.US_ASCII) + ByteArray(size)

    /** A small real BMP (24-bit) — a KNOWN-unaccepted format, used as a gate probe. */
    fun bmp(w: Int = 4, h: Int = 4): ByteArray = awtEncode(w, h, 5, "bmp")

    // ── independent header parser ───────────────────────────────────────────

    data class Dim(val format: String, val width: Int, val height: Int, val exifOrientation: Int? = null) {
        val longEdge: Int get() = maxOf(width, height)
    }

    fun dims(b: ByteArray): Dim? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun be32(i: Int) = (be16(i) shl 16) or be16(i + 2)
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun le32(i: Int) = le16(i) or (le16(i + 2) shl 16)
        if (b.size < 26) return null
        if (u8(0) == 0x89 && b[1] == 'P'.code.toByte()) return Dim("png", be32(16), be32(20))
        if (u8(0) == 0xFF && u8(1) == 0xD8) {
            var i = 2
            var orientation: Int? = null
            while (i + 9 < b.size) {
                if (u8(i) != 0xFF) { i++; continue }
                val m = u8(i + 1)
                if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
                val len = be16(i + 2)
                if (m == 0xE1 && String(b, i + 4, 4, Charsets.US_ASCII) == "Exif") {
                    orientation = exifOrientation(b, i + 10)
                }
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                    return Dim("jpeg", be16(i + 7), be16(i + 5), orientation)
                }
                i += 2 + len
            }
            return null
        }
        if (String(b, 0, 3, Charsets.US_ASCII) == "GIF") return Dim("gif", le16(6), le16(8))
        if (String(b, 0, 2, Charsets.US_ASCII) == "BM") return Dim("bmp", le32(18), kotlin.math.abs(le32(22)))
        return null
    }

    private fun exifOrientation(b: ByteArray, tiff: Int): Int? {
        val little = b[tiff] == 'I'.code.toByte()
        fun u16(i: Int) = if (little) (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        else ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        val ifd = tiff + 8
        val n = u16(ifd)
        for (e in 0 until n) {
            val p = ifd + 2 + e * 12
            if (u16(p) == 0x0112) return u16(p + 8)
        }
        return null
    }

    // ── request-body image extraction (all four wire shapes) ────────────────

    data class WireImage(
        val shape: String,
        val mime: String,
        val base64Length: Int,
        val bytes: ByteArray,
    ) {
        val dim: Dim? get() = dims(bytes)
        override fun toString(): String =
            "$shape $mime ${bytes.size}B b64=$base64Length ${dim?.let { "${it.format} ${it.width}x${it.height}" } ?: "dims=?"}"
    }

    /** Every inline image in [body], in document order, whatever the provider. */
    fun wireImages(body: Any?): List<WireImage> {
        val out = mutableListOf<WireImage>()
        fun dataUrl(shape: String, url: String) {
            if (!url.startsWith("data:")) return
            val mime = url.substringAfter("data:").substringBefore(";")
            val b64 = url.substringAfter("base64,")
            out += WireImage(shape, mime, b64.length, java.util.Base64.getDecoder().decode(b64))
        }
        fun walk(v: Any?) {
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.get(i))
                is JSONObject -> {
                    val type = v.optString("type")
                    when {
                        type == "image" && v.optJSONObject("source")?.optString("type") == "base64" -> {
                            val s = v.getJSONObject("source")
                            val d = s.getString("data")
                            out += WireImage("anthropic.image", s.getString("media_type"), d.length, java.util.Base64.getDecoder().decode(d))
                        }
                        type == "image_url" && v.optJSONObject("image_url") != null ->
                            dataUrl("openai.chat.image_url", v.getJSONObject("image_url").getString("url"))
                        type == "input_image" -> dataUrl("openai.responses.input_image", v.optString("image_url"))
                        v.has("inlineData") || v.has("inline_data") -> {
                            val s = v.optJSONObject("inlineData") ?: v.getJSONObject("inline_data")
                            val d = s.getString("data")
                            val mime = s.optString("mimeType", s.optString("mime_type"))
                            out += WireImage("gemini.inlineData", mime, d.length, java.util.Base64.getDecoder().decode(d))
                        }
                    }
                    for (k in v.keys()) walk(v.get(k))
                }
            }
        }
        walk(body)
        return out
    }

    /** All string leaves in [body] (to look for placeholders). */
    fun strings(body: Any?): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.get(i))
                is JSONObject -> for (k in v.keys()) walk(v.get(k))
                is String -> out += v
            }
        }
        walk(body)
        return out
    }


    // ── helpers for the real functions ──────────────────────────────────────

    fun sha256(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun sha256(s: String): String = sha256(s.toByteArray(Charsets.UTF_8))

    /** A Context whose only working method is getFilesDir() (enough for MediaStore and the spillover dir). */
    fun context(filesDir: java.io.File): android.content.Context = object : android.content.ContextWrapper(null) {
        override fun getFilesDir(): java.io.File = filesDir
    }

    fun tempDir(prefix: String): java.io.File = java.nio.file.Files.createTempDirectory(prefix).toFile()

    /** Canonical, byte-comparable text of a message: every field, image bytes as sha256. */
    fun dump(m: LLMMessage): String = buildString {
        append("role=").append(m.role).append('|').append("content=").append(m.content)
        append("|db=").append(m.dbMessageId).append("|reasoning=").append(m.reasoningContent)
        for (p in m.imageParts) {
            append("|imagePart(").append(sha256(p.data)).append(',').append(p.mimeType).append(',')
                .append(p.linuxPath).append(',').append(p.noVisionPlaceholder).append(')')
        }
        for (p in m.contentParts) {
            append('|')
            when (p) {
                is AgentContentPart.Text -> append("Text(").append(p.text).append(')')
                is AgentContentPart.ToolUse -> append("ToolUse(").append(p.id).append(',').append(p.name).append(',')
                    .append(p.input.toString()).append(',').append(p.thoughtSignature).append(',').append(p.isOffloadedArgument).append(')')
                is AgentContentPart.ToolResult -> append("ToolResult(").append(p.id).append(',').append(p.name).append(',')
                    .append(p.content).append(',').append(p.isError).append(',').append(p.imageData?.let { sha256(it) }).append(',')
                    .append(p.imageMimeType).append(',').append(p.imageLinuxPath).append(')')
                is AgentContentPart.ImageData -> append("ImageData(").append(sha256(p.data)).append(',').append(p.mimeType).append(',')
                    .append(p.linuxPath).append(',').append(p.noVisionPlaceholder).append(')')
            }
        }
        if (m.imageParts.isNotEmpty() && m.contentParts.isNotEmpty()) {
            val img = m.contentParts.filterIsInstance<AgentContentPart.ImageData>().map { it.data }
            append("|sharedArrays=").append(m.imageParts.mapIndexed { i, p -> img.getOrNull(i) === p.data })
        }
    }

    // ── reflection: ChatViewModel's private entry points, no constructor ────

    private val unsafe: Any by lazy {
        val c = Class.forName("sun.misc.Unsafe")
        c.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    }

    fun allocate(cls: Class<*>): Any =
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, cls)

    /** Set any (even final) instance field via Unsafe. */
    fun setField(target: Any, name: String, value: Any?) {
        var c: Class<*>? = target.javaClass
        var f: java.lang.reflect.Field? = null
        while (c != null && f == null) {
            f = c.declaredFields.firstOrNull { it.name == name }
            c = c.superclass
        }
        requireNotNull(f) { "no field $name" }
        val off = unsafe.javaClass.getMethod("objectFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, f) as Long
        unsafe.javaClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(unsafe, target, off, value)
    }

    fun callPrivate(target: Any, name: String, vararg args: Any?): Any? {
        val m = target.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        return try {
            m.invoke(target, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
