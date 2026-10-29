package com.openminis.app.imagerepro

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Image-pipeline repro, Android: the ingest numbers of each scenario and the
 * single gate (ImageBudget.prepareForUpload) — where it runs, what bypasses
 * it, and what the request-level planner measures.
 *
 * P-a4 probe: a real BMP is a KNOWN-unaccepted format, and on the JVM the
 * BitmapFactory-backed transcode fails, so the gate returns null and the
 * caller must emit [ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER]. Seeing that
 * placeholder (and no image) in a body proves the gate ran at that call site;
 * seeing the BMP bytes proves it did not.
 */
class ImageReproGateTest {

    private val mb = 1024 * 1024

    // ── ingest: raw originals and what each production step makes of them ──

    /**
     * (A) S1/S3/S5 ingest facts. Raw originals are real images generated
     * here; the device-side outputs come from the production arithmetic. On
     * the JVM every BitmapFactory-backed step is a no-op — pinned below so no
     * reader mistakes the JVM pass-through for device behaviour.
     */
    @Test fun `ingest - raw originals, device target sizes per producer, JVM decode stub`() {
        val t0 = System.nanoTime()
        val longPng = ImageReproFixtures.png(2760, 21891)
        val fullPageJpeg = ImageReproFixtures.jpeg(1080, 32768, gray = true)
        val camera = ImageReproFixtures.jpeg(4032, 3024)
        val genMs = (System.nanoTime() - t0) / 1_000_000
        for ((name, raw) in listOf("2760x21891 png" to longPng, "1080x32768 jpeg" to fullPageJpeg, "4032x3024 jpeg" to camera)) {
            val d = ImageReproFixtures.dims(raw)!!
            val round = ImageBudget.fitWithin(d.width, d.height, 2000)
            val scale = 2000f / d.longEdge
            val floor = if (d.longEdge > 2000) (d.width * scale).toInt() to (d.height * scale).toInt() else null
            val composerFormat = if (ImageBudget.sniffFormat(raw) == ImageBudget.ImageFormat.PNG) "png" else "jpeg"
            println(
                "[REPRO] ingest $name | raw ${raw.size}B sniff=${ImageBudget.sniffFormat(raw)} " +
                    "| composer/reload/gate (downscaleForModel, round) -> ${round?.let { "${it.first}x${it.second}" }} $composerFormat q85 " +
                    "| browser/read_image (floor) -> ${floor?.let { "${it.first}x${it.second}" }} jpeg q85 " +
                    "| JVM downscaleForModel=${ImageBudget.downscaleForModel(raw)} (stub)",
            )
            // On the JVM the decode stub makes every resize a no-op.
            assertNull(ImageBudget.downscaleForModel(raw))
            assertTrue(ImageBudget.compressBytes(raw) === raw)
        }
        assertEquals(252 to 2000, ImageBudget.fitWithin(2760, 21891, 2000))
        assertEquals(66 to 2000, ImageBudget.fitWithin(1080, 32768, 2000))
        assertEquals(2000 to 1500, ImageBudget.fitWithin(4032, 3024, 2000))
        println("[REPRO] ingest generation time ${genMs}ms")
    }

    /**
     * (A) N4: the two resize rules disagree by a pixel. The composer, reload
     * and gate round (fitWithin); browser and read_image floor. Same original
     * → 66×2000 via an attachment, 65×2000 via a screenshot/read_image.
     */
    @Test fun `N4 floor vs round - 1080x32768 is 66x2000 as an attachment, 65x2000 as a tool image`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val tool = ProductionSources.read("tools/ReadImageTool.kt")
        val resize = vm.substringAfter("private fun resizeJpegToMaxEdge(").substringBefore("\n    }\n")
        for (src in listOf(resize, tool)) {
            assertTrue(src.contains("val w = (") && src.contains(" * scale).toInt()"))
        }
        assertEquals(66 to 2000, ImageBudget.fitWithin(1080, 32768, 2000))
        val s = 2000f / 32768
        assertEquals(65, (1080 * s).toInt())
    }

    // ── P-a4: where the gate runs ───────────────────────────────────────────

    private val bmp by lazy { ImageReproFixtures.bmp() }

    private fun probe(label: String, msgs: List<LLMMessage>, top: List<LLMMessage.ImagePart> = emptyList()) {
        for ((p, body) in ImageReproWire.all(msgs, top)) {
            val imgs = ImageReproFixtures.wireImages(body)
            val placeholder = ImageReproFixtures.strings(body).count { it == ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER }
            println("[REPRO] P-a4 gate probe $label | $p | images=${imgs.size} placeholders=$placeholder")
            assertEquals("$label/$p: no BMP reaches the wire", 0, imgs.size)
            assertTrue("$label/$p: the gate's placeholder is there", placeholder >= 1)
        }
    }

    /** (A) P-a4: the gate runs for ImageData, ToolResult images, legacy imageParts and top-level imageParts, on all four. */
    @Test fun `P-a4 gate runs at every image site of all four serializers`() {
        probe("contentParts.ImageData", listOf(ImageReproWire.liveUserTurn("x", bmp, "image/bmp", null)))
        probe("ToolResult.imageData", listOf(ImageReproWire.leadingUser()) +
            ImageReproWire.liveToolRound("t1", "read_image", "meta", bmp, "image/bmp", null))
        probe("top-level imageParts argument", listOf(ImageReproWire.leadingUser("x")), top = listOf(LLMMessage.ImagePart(bmp, "image/bmp")))
    }

    /**
     * (A) N5: a message holding images ONLY in its own `imageParts` (no
     * contentParts). Measured with a real JPEG: NONE of the four serializers
     * sends them — each reads contentParts, or the TOP-LEVEL imageParts
     * argument, never `msg.imageParts`. Every ChatViewModel user turn also has
     * contentParts, so in the agent loop `LLMMessage.imageParts` only ever
     * feeds the token estimate (P-a1); a direct caller that builds
     * `LLMMessage(imageParts=…)` without contentParts loses the image silently,
     * with no placeholder.
     */
    @Test fun `N5 message-level imageParts without contentParts are dropped by all four serializers`() {
        val jpg = ImageReproFixtures.jpeg(64, 32)
        val msg = LLMMessage(LLMMessage.Role.USER, "x", imageParts = listOf(LLMMessage.ImagePart(jpg, "image/jpeg")))
        for ((p, body) in ImageReproWire.all(listOf(msg))) {
            val n = ImageReproFixtures.wireImages(body).size
            val placeholders = ImageReproFixtures.strings(body).count { it == ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER }
            println("[REPRO] N5 legacy message imageParts only | $p | images=$n placeholders=$placeholders")
            assertEquals("$p", 0, n)
            assertEquals("$p: and nothing says an image was there", 0, placeholders)
        }
        // The same parts passed top-level DO go out (that is the only arm that reads them).
        val top = ImageReproWire.all(listOf(ImageReproWire.leadingUser("x")), msg.imageParts)
            .mapValues { ImageReproFixtures.wireImages(it.value).size }
        assertEquals(mapOf("anthropic" to 1, "openai.chat" to 1, "openai.responses" to 1, "gemini" to 1), top)
    }

    /** (A) P-a4: the gate also fixes the LABEL — JPEG bytes declared image/heic go out as image/jpeg everywhere. */
    @Test fun `P-a4 gate relabels jpeg bytes declared image-heic on all four`() {
        val jpg = ImageReproFixtures.jpeg(64, 32)
        for ((p, body) in ImageReproWire.all(listOf(ImageReproWire.liveUserTurn("x", jpg, "image/heic", null)))) {
            val img = ImageReproFixtures.wireImages(body).single()
            assertEquals("$p", "image/jpeg", img.mime)
            assertTrue(img.bytes.contentEquals(jpg))
        }
    }

    /** (A) P-a4: editImage posts the input bytes as they are — no format gate, no fit, no budget. */
    @Test fun `P-a4 editImage sends a BMP raw - the gate is bypassed`() {
        val raw = sendEditImage(bmp, "image/png")
        println("[REPRO] P-a4 editImage | body ${raw.length}B contains BMP header=${raw.indexOf("BM", 0) >= 0}")
        assertTrue("the BMP bytes are in the multipart body", containsBytes(raw.toByteArray(Charsets.ISO_8859_1), bmp))
        assertFalse(raw.contains(ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER))
    }

    /** (B) P-a4: editImage should only ever send an accepted, gated image. */
    @Ignore("known gap P-a4: OpenAIProvider.editImage posts img.data straight into the multipart body")
    @Test fun `desired - editImage never posts an unaccepted format`() {
        val raw = sendEditImage(bmp, "image/png").toByteArray(Charsets.ISO_8859_1)
        assertFalse(containsBytes(raw, bmp))
    }

    private fun sendEditImage(bytes: ByteArray, mime: String): String {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val provider = OpenAIProvider(apiKey = "k", model = ImageReproWire.visionModel, basePath = server.url("/").toString().trimEnd('/'))
            runBlocking { runCatching { provider.editImage("edit", listOf(LLMMessage.ImagePart(bytes, mime))) } }
            return server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readString(Charsets.ISO_8859_1)
        } finally {
            server.shutdown()
        }
    }

    private fun containsBytes(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    // ── P-a5: what the request-level planner measures ───────────────────────

    /**
     * (A) P-a5: the planner charges the PRE-gate size. Six 6 MB-raw long PNGs
     * are charged 5 MB each (the per-image clamp) and one is elided, although
     * on a device the gate would send each as a 252×2000 image of a few KB.
     */
    @Test fun `P-a5 planner charges pre-gate bytes - oversize originals are elided though they would gate small`() {
        val header = ImageReproFixtures.png(2760, 21891).copyOf(64)
        val big = (0 until 6).map { i -> ImageBudget.BudgetImage(header + ByteArray(6 * mb + i), "/var/minis/attachments/uploads/l$i.png", "image/png") }
        val plan = ImageBudget.planRequestBudget(big)
        println("[REPRO] P-a5 planner | 6 x 6MB-raw long PNGs | dropped=${plan.droppedCount} keptBytes=${plan.keptBytes} (charged per image ${ImageBudget.MAX_PER_IMAGE_BYTES}) | device gate output each ≈ 252x2000")
        assertEquals(1, plan.droppedCount)
        assertEquals(5 * ImageBudget.MAX_PER_IMAGE_BYTES, plan.keptBytes)
    }

    /** (A) P-a5: the planner's id is the array's identity hash — equal bytes in two arrays are two images, one array listed twice is one. */
    @Test fun `P-a5 planner id is System identityHashCode of the array`() {
        val a = ByteArray(16) { 7 }
        val b = a.copyOf()
        assertEquals(ImageBudget.ImagePartId(System.identityHashCode(a)), ImageBudget.ImagePartId.of(a))
        assertNotEquals("equal content, distinct arrays → distinct ids (barring a hash collision)", ImageBudget.ImagePartId.of(a), ImageBudget.ImagePartId.of(b))
        assertEquals(ImageBudget.ImagePartId.of(a), ImageBudget.ImagePartId.of(a))
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val budget = vm.substringAfter("private fun applyRequestImageBudget(").substringBefore("\n    }\n")
        assertTrue("planner input is the history's raw part bytes", budget.contains("ImageBudget.BudgetImage(part.data, part.linuxPath, part.mimeType)"))
        assertFalse("no gate before the planner", budget.contains("prepareForUpload"))
        assertTrue("only contentParts are scanned", budget.contains("msg.contentParts.forEachIndexed") && !budget.contains("imageParts"))
    }

    /** (B) P-a5: images the gate would send small are not elided by the request budget. */
    @Ignore("known gap P-a5: planRequestBudget measures the pre-gate bytes (and keys by identityHashCode)")
    @Test fun `desired - planner measures what the gate sends`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val budget = vm.substringAfter("private fun applyRequestImageBudget(").substringBefore("\n    }\n")
        assertTrue(budget.contains("prepareForUpload"))
    }

    // ── P-a6 / P-a7: decode path ────────────────────────────────────────────

    /** Files that decode image bytes (BrowserUseManager only captures and encodes a WebView bitmap). */
    private val decodeFiles = listOf("provider/ImageBudget.kt", "tools/ReadImageTool.kt")

    /**
     * (A) P-a6, static half: no image path reads EXIF or uses ImageDecoder.
     * The runtime half (BitmapFactory ignores orientation 6; a transparent PNG
     * encoded as JPEG turns black) needs real Android decoders — NOT verifiable
     * on the JVM; it was measured on a Pixel 4a.
     */
    @Test fun `P-a6 no decode path reads EXIF or uses ImageDecoder`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val resize = vm.substringAfter("private fun resizeJpegToMaxEdge(").substringBefore("\n    }\n")
        for (f in decodeFiles) {
            val src = ProductionSources.read(f)
            assertTrue("$f decodes with BitmapFactory", src.contains("BitmapFactory.decode"))
            for (token in listOf("ExifInterface", "ImageDecoder", "TAG_ORIENTATION")) {
                assertFalse("$f uses $token", src.contains(token))
            }
        }
        assertFalse(resize.contains("Exif") || resize.contains("ImageDecoder"))
        val browser = ProductionSources.read("browser/BrowserUseManager.kt")
        assertFalse(browser.contains("ExifInterface") || browser.contains("ImageDecoder"))
        // Transparent PNG → JPEG happens only where the output is forced to JPEG.
        val budget = ProductionSources.read("provider/ImageBudget.kt")
        assertTrue("compressBytes always writes JPEG", budget.contains("out.compress(Bitmap.CompressFormat.JPEG, q.coerceIn(1, 100), baos)"))
        assertTrue("downscaleForModel keeps PNG as PNG", budget.contains("if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG"))
    }

    /** (A) P-a7: ReadImageTool and resizeJpegToMaxEdge decode at full size; downscaleForModel samples. */
    @Test fun `P-a7 read_image and browser resize decode the full image, no inSampleSize`() {
        val tool = ProductionSources.read("tools/ReadImageTool.kt")
        assertTrue(tool.contains("BitmapFactory.decodeFile(file.absolutePath)\n"))
        assertFalse(tool.contains("inSampleSize"))
        assertTrue("catches Exception, not OutOfMemoryError", tool.contains("} catch (e: Exception) {") && !tool.contains("OutOfMemoryError") && !tool.contains("Throwable"))
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val resize = vm.substringAfter("private fun resizeJpegToMaxEdge(").substringBefore("\n    }\n")
        assertTrue(resize.contains("BitmapFactory.decodeByteArray(data, 0, data.size)"))
        assertFalse(resize.contains("inSampleSize"))
        assertTrue(ProductionSources.read("provider/ImageBudget.kt").contains("inSampleSize = sample"))
    }

    /** (B) P-a6: the gate applies EXIF orientation before scaling. */
    @Ignore("known gap P-a6: BitmapFactory path ignores EXIF orientation; no ExifInterface/ImageDecoder in ImageBudget")
    @Test fun `desired - gate applies EXIF orientation`() {
        val src = ProductionSources.read("provider/ImageBudget.kt")
        assertTrue(src.contains("ImageDecoder") || src.contains("ExifInterface"))
    }

    /** (B) P-a7: tool-side decodes are sampled to the target size. */
    @Ignore("known gap P-a7: ReadImageTool.decodeFile and resizeJpegToMaxEdge decode at full size")
    @Test fun `desired - read_image and browser resize decode sampled`() {
        assertTrue(ProductionSources.read("tools/ReadImageTool.kt").contains("inSampleSize"))
    }

    @Test fun `fixture sanity - generated images parse as what they claim`() {
        assertEquals(ImageReproFixtures.Dim("png", 252, 2000), ImageReproFixtures.dims(ImageReproFixtures.png(252, 2000)))
        assertEquals(ImageReproFixtures.Dim("jpeg", 65, 2000), ImageReproFixtures.dims(ImageReproFixtures.jpeg(65, 2000)))
        assertEquals("bmp", ImageReproFixtures.dims(bmp)!!.format)
        assertEquals(ImageBudget.ImageFormat.BMP, ImageBudget.sniffFormat(bmp))
        val alpha = ImageReproFixtures.png(4, 4, alpha = true)
        assertEquals(6, alpha[25].toInt()) // IHDR colour type RGBA
        assertTrue(AgentContentPart.ImageData(alpha, "image/png") == AgentContentPart.ImageData(alpha.copyOf(), "image/png"))
    }
}
