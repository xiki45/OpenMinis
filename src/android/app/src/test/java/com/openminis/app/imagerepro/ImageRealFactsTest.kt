package com.openminis.app.imagerepro

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.ui.chat.ChatAttachmentIngest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Image-pipeline repro on real functions, part 2: the gate at every serializer
 * site (P-a4), what bypasses it, the wire facts N2 / N5, S6, and the facts
 * that remain source-level (P-a6 static half, P-a8, P-a9, N3) — those keep a
 * source anchor because there is no runtime behaviour to call on the JVM;
 * each says so.
 */
class ImageRealFactsTest {

    private val F = ImageRealFixtures
    private val bmp by lazy { F.bmp() }
    private val noVision: (String?) -> String? = { null }

    /** A real live user turn holding [img] (the ImageData + imageParts twin). */
    private fun liveTurn(img: ByteArray, mime: String) =
        ChatAttachmentIngest.liveUserTurn("x", listOf(LLMMessage.ImagePart(img, mime)), emptyList(), listOf(null), null, "u1", noVision)

    private fun toolTurn(img: ByteArray, mime: String) = listOf(
        ImageRealWire.leadingUser(),
        LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse("t1", "read_image", org.json.JSONObject()))),
        LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("t1", "read_image", "meta", imageData = img, imageMimeType = mime))),
    )

    // ── P-a4: the gate at every site of every serializer ───────────────────

    /** (A) P-a4: a real BMP becomes the gate's placeholder at all 3 sites × 4 serializers (12/12). */
    @Test fun `P-a4 gate runs at every image site of all four real serializers`() {
        val sites = listOf(
            "contentParts.ImageData" to (listOf(liveTurn(bmp, "image/bmp")) to emptyList<LLMMessage.ImagePart>()),
            "ToolResult.imageData" to (toolTurn(bmp, "image/bmp") to emptyList()),
            "top-level imageParts" to (listOf(ImageRealWire.leadingUser("x")) to listOf(LLMMessage.ImagePart(bmp, "image/bmp"))),
        )
        var probes = 0
        for ((site, input) in sites) for ((p, body) in ImageRealWire.all(input.first, input.second)) {
            val n = F.wireImages(body).size
            val ph = F.strings(body).count { it == ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER }
            println("[REAL] P-a4 gate probe $site | $p | images=$n placeholders=$ph")
            assertEquals(0, n); assertTrue(ph >= 1); probes++
        }
        assertEquals(12, probes)
    }

    /** (A) P-a4: JPEG bytes labelled image/heic are relabelled image/jpeg by all four. */
    @Test fun `P-a4 gate relabels jpeg declared image-heic on all four`() {
        val jpg = F.jpeg(64, 32)
        for ((p, body) in ImageRealWire.all(listOf(liveTurn(jpg, "image/heic")))) {
            val w = F.wireImages(body).single()
            assertEquals(p, "image/jpeg", w.mime); assertTrue(w.bytes.contentEquals(jpg))
        }
    }

    /** (A) P-a4: OpenAIProvider.editImage posts a BMP raw — no gate. */
    @Test fun `P-a4 editImage bypasses the gate`() {
        val body = editImageBody(bmp)
        println("[REAL] P-a4 editImage | body ${body.size}B contains BMP=${contains(body, bmp)}")
        assertTrue(contains(body, bmp))
    }

    @Ignore("known gap P-a4: editImage posts img.data straight into the multipart body")
    @Test fun `desired - editImage never posts an unaccepted format`() {
        assertFalse(contains(editImageBody(bmp), bmp))
    }

    private fun editImageBody(img: ByteArray): ByteArray {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val provider = OpenAIProvider(apiKey = "k", model = ImageRealWire.visionModel, basePath = server.url("/").toString().trimEnd('/'))
            runBlocking { runCatching { provider.editImage("edit", listOf(LLMMessage.ImagePart(img, "image/png"))) } }
            return server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readByteArray()
        } finally { server.shutdown() }
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    // ── N2 / N5: what the serializers do with imageParts ───────────────────

    /** (A) N2: the real live turn's own parts passed again top-level duplicate on OpenAI Responses only. */
    @Test fun `N2 top-level imageParts equal to the turn's own duplicate on openai responses only`() {
        val m = liveTurn(F.jpeg(64, 32), "image/jpeg")
        val counts = ImageRealWire.all(listOf(m), m.imageParts).mapValues { F.wireImages(it.value).size }
        println("[REAL] N2 counts=$counts")
        assertEquals(mapOf("anthropic" to 1, "openai.chat" to 1, "openai.responses" to 2, "gemini" to 1), counts)
    }

    /** (A) N5: a message with only imageParts (no contentParts) loses the image on all four, with no placeholder. */
    @Test fun `N5 message-level imageParts without contentParts are dropped by all four`() {
        val m = LLMMessage(LLMMessage.Role.USER, "x", imageParts = listOf(LLMMessage.ImagePart(F.jpeg(64, 32), "image/jpeg")))
        for ((p, body) in ImageRealWire.all(listOf(m))) {
            println("[REAL] N5 | $p | images=${F.wireImages(body).size}")
            assertEquals(p, 0, F.wireImages(body).size)
            assertFalse(F.strings(body).contains(ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER))
        }
    }

    // ── P-a5 identity ───────────────────────────────────────────────────────

    /** (A) P-a5: the planner id is the array identity hash. */
    @Test fun `P-a5 planner id is System identityHashCode`() {
        val a = ByteArray(16) { 7 }; val b = a.copyOf()
        assertEquals(ImageBudget.ImagePartId(System.identityHashCode(a)), ImageBudget.ImagePartId.of(a))
        assertNotEquals(ImageBudget.ImagePartId.of(a), ImageBudget.ImagePartId.of(b))
    }

    // ── S6: one image builder for every send path ──────────────────────────

    /**
     * (A) S6: the live turn (sendMessage → liveUserTurn) and the queue paths
     * (drainQueuedPrompts / injectQueuedPromptsAsNewTurn → addModelImage) build
     * the image parts with the same real function — byte-identical parts.
     * The queue paths' surrounding code is not extracted, so that they call it
     * is a source anchor; what it builds is the real call below.
     */
    @Test fun `S6 queue paths and the live path build image parts with the same function`() {
        val part = LLMMessage.ImagePart(F.jpeg(64, 32), "image/jpeg", linuxPath = "/var/minis/attachments/uploads/a.jpg")
        val live = ChatAttachmentIngest.liveUserTurn("hi", listOf(part), listOf(part.linuxPath!!), listOf("note"), null, "u1", noVision)
        val queued = mutableListOf<AgentContentPart>(AgentContentPart.Text("hi"))
        ChatAttachmentIngest.addModelImage(queued, part, part.linuxPath, "note", noVision)
        assertEquals(live.contentParts, queued)
        // [T-ctx-builder] Both queue paths now build the batch with the real
        // ChatAttachmentIngest.queuedTurn, which runs addModelImage per image.
        val prepared = com.openminis.app.ui.chat.PreparedAttachments(listOf(part), emptyList(), emptyList(), emptyList(), listOf(part.linuxPath!!), null, emptyList(), listOf("note"))
        assertEquals(live.contentParts, ChatAttachmentIngest.queuedTurn(listOf("hi"), prepared, noVision).parts)
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertEquals(2, Regex(Regex.escape("ChatAttachmentIngest.queuedTurn(")).findAll(vm).count())
        assertTrue(vm.contains(") = ChatAttachmentIngest.addModelImage(this, part, path, note, ::visionPlaceholderFor)"))
        assertTrue(vm.contains("agentHistory.add(ChatAttachmentIngest.liveUserTurn("))
        assertEquals(3, Regex(Regex.escape("val prepared = prepareUserAttachments(")).findAll(vm).count())
    }

    // ── source-level facts (no runtime behaviour to call on the JVM) ───────

    /**
     * (A, source) P-a6 static half: no decode path reads EXIF or uses
     * ImageDecoder; the production codec is BitmapFactory. Runtime half
     * (orientation ignored, transparent→JPEG black, HEIC) is device-only:
     * androidTest ImageRealDeviceTest.
     */
    @Test fun `P-a6 production codec is BitmapFactory with no EXIF handling`() {
        val budget = ProductionSources.read("provider/ImageBudget.kt")
        val codec = budget.substringAfter("internal object AndroidImageCodec : ImageCodec {")
        assertTrue(codec.contains("BitmapFactory.decodeByteArray(input, 0, input.size, BitmapFactory.Options().apply { this.inSampleSize = inSampleSize })"))
        assertTrue(codec.contains("override fun decodeFile(path: String): Any? = BitmapFactory.decodeFile(path)"))
        for (f in listOf("provider/ImageBudget.kt", "tools/ReadImageTool.kt", "browser/BrowserUseManager.kt", "ui/chat/ChatViewModel.kt")) {
            val src = ProductionSources.read(f)
            for (t in listOf("ExifInterface", "ImageDecoder", "TAG_ORIENTATION")) assertFalse("$f uses $t", src.contains(t))
        }
        assertTrue(ImageBudget.codec === com.openminis.app.provider.AndroidImageCodec)
    }

    /** (A, source) P-a8: the old ImageBudgetCallSiteTest assertion is false only because the history is bound first. */
    @Test fun `P-a8 the old call-site assertion fails only because of the val binding`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val loop = vm.substring(vm.indexOf("suspend fun runAgentLoop"))
        val i = loop.indexOf("currentProvider.streamMessage(")
        val window = loop.substring(i, i + 700)
        // [T-ctx-builder] The budgeted messages now come from RequestContextBuilder.build,
        // which binds the outbound history first and budgets that list.
        // [T-callback-queued-reminder] Sent as `requestMessages`, which starts as the builder's
        // output (a reminder may be appended to its last message; nothing is re-read from history).
        assertTrue(window.contains("requestMessages,")); assertFalse(window.contains("effectiveAgentHistory()"))
        assertTrue(loop.substring(0, i).contains("var requestMessages = requestContext.messages"))
        assertTrue(loop.substring(0, i).contains("val requestContext = RequestContextBuilder.build(requestContextInput())"))
        val build = vm.substringAfter("    fun build(input: Input, onOutbound").substringBefore("\n    }\n")
        assertTrue(build.contains("val outbound = outbound(input, compactionTrace, orphanTrace)"))
        assertTrue(build.contains("applyRequestBudget(outbound, input.resolveAttachmentsRoot)"))
    }

    /** (A, source) P-a9: Takeover builds no image; no takeover file builds image parts. */
    @Test fun `P-a9 no takeover image source`() {
        val pool = ProductionSources.read("browser/BrowserTabPool.kt")
        val takeover = pool.substringAfter("This is the \"Takeover\" action").substringBefore("\n    }\n")
        for (t in listOf("base64", "Bitmap", "screenshot", "ImageData", "imageData")) assertFalse(takeover.contains(t))
        val producers = ProductionSources.allKotlinFiles().filter { it.readText().contains("ImageData(") }.map { it.name }
        assertFalse(producers.any { it.contains("Takeover", ignoreCase = true) })
    }

    /**
     * (A, source) N3: the persisted screenshot name has 1-second resolution and no collision guard.
     * [T-ctx-builder] The block moved to BrowserArtifacts (production passes System::currentTimeMillis);
     * the runtime repro with the real function is scenario C19 (ctx.CtxScenarioTest).
     */
    @Test fun `N3 persisted screenshot name is second-resolution`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("val filename = \"screenshot_\${clockMs() / 1000}.jpg\""))
        assertTrue(vm.contains("clockMs = System::currentTimeMillis,\n                persist = ::persistBrowserArtifact,"))
        val artifact = vm.substringAfter("    fun persist(sessionId: String, filesDir: () -> java.io.File, filename: String, data: ByteArray): String? {").substringBefore("\n    }\n")
        assertTrue(artifact.contains("file.writeBytes(data)")); assertFalse(artifact.contains("exists()"))
    }

    /** (A, source) the agent loop passes no top-level imageParts (so N2 does not fire there). */
    @Test fun `agent loop passes no top-level imageParts`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val loop = vm.substring(vm.indexOf("suspend fun runAgentLoop"))
        val call = loop.substring(loop.indexOf("currentProvider.streamMessage(")).substringBefore("\n                        )\n")
        assertTrue(call.contains("requestMessages,")); assertFalse(call.contains("imageParts"))
        assertTrue(loop.contains("var requestMessages = requestContext.messages"))
    }
}
