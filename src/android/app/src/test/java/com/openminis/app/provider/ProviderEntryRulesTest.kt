package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-provider-entry-rules] Every request a provider sends carries the
 * provider's custom rules - not only chat. Audit 2026-10-07: image generation,
 * image edit and the model-use passthrough built their requests by hand and
 * skipped the OpenCode session header, Copilot's per-request headers and the
 * user's per-model headers (the passthrough also the User-Agent); folder
 * suggestions, vision and voice correction built providers with no session id.
 *
 * The OpenCode header itself cannot reach MockWebServer (127.0.0.1 fails the
 * host gate by design); it shares [OpenAIProvider.applyProviderHeaders] with
 * the headers asserted here, and the source scans below pin that every
 * endpoint goes through it.
 */
class ProviderEntryRulesTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() { server = MockWebServer(); server.start() }

    @After
    fun tearDown() = server.shutdown()

    private val pngB64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

    /** A provider carrying every rule that applies on a local server. */
    private fun provider(extra: Map<String, String> = emptyMap()) = OpenAIProvider(
        apiKey = "test-key",
        model = LLMModel.gpt4oMini,
        basePath = server.url("/v1").toString().trimEnd('/'),
        extraHeaders = extra,
        customUserAgent = "RelayUA/1.0",
    ).apply {
        modelOverrides = ModelOverrides(customHeaders = mapOf("X-User-Header" to "from-overrides"))
        perRequestHeaders = { body -> mapOf("X-Derived" to (if (body.has("prompt")) "image" else "chat")) }
    }

    private fun assertRules(r: RecordedRequest, derived: String?) {
        assertEquals("per-model custom header", "from-overrides", r.getHeader("X-User-Header"))
        assertEquals("instance User-Agent", "RelayUA/1.0", r.getHeader("User-Agent"))
        assertEquals("body-derived (Copilot-style) header", derived, r.getHeader("X-Derived"))
    }

    @Test
    fun `image generation carries the provider rules`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"$pngB64"}]}"""))
        // Parsing the image needs Android APIs; the request has gone out by then.
        runCatching { provider().generateImage("a cat") }
        assertRules(server.takeRequest(), derived = "image")
    }

    @Test
    fun `image edit carries the provider rules (multipart, so no body-derived header)`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"$pngB64"}]}"""))
        val png = java.util.Base64.getDecoder().decode(pngB64)
        runCatching { provider().editImage("make it blue", listOf(LLMMessage.ImagePart(png, "image/png"))) }
        assertRules(server.takeRequest(), derived = null)
    }

    @Test
    fun `model-use passthrough carries the provider rules and the caller's headers still win`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        provider().rawPassthroughRequest(
            endpoint = null, method = "POST",
            headers = mapOf("X-User-Header" to "from-caller"),
            bodyObject = JSONObject().put("model", "m").put("messages", org.json.JSONArray()),
        )
        val r = server.takeRequest()
        assertEquals("caller headers are applied last", "from-caller", r.getHeader("X-User-Header"))
        assertEquals("RelayUA/1.0", r.getHeader("User-Agent"))
        assertEquals("chat", r.getHeader("X-Derived"))
    }

    @Test
    fun `chat still carries the same rules`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
            ),
        )
        provider().sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 16)
        assertRules(server.takeRequest(), derived = "chat")
    }

    @Test
    fun `a provider-identity User-Agent in extraHeaders is not replaced by the branded default`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"$pngB64"}]}"""))
        OpenAIProvider(
            apiKey = "k", model = LLMModel.gpt4oMini, basePath = server.url("/v1").toString().trimEnd('/'),
            extraHeaders = mapOf("User-Agent" to "GitHubCopilotChat/0.30"),
        ).let { p -> runCatching { p.generateImage("x") } }
        assertEquals("GitHubCopilotChat/0.30", server.takeRequest().getHeader("User-Agent"))
    }

    // ── source scans: no endpoint and no entry can skip the rules ──────────

    private val openAI = ProductionSources.read("provider/openai/OpenAIProvider.kt")

    @Test
    fun `every request OpenAIProvider dials goes through applyProviderHeaders`() {
        val lines = openAI.lines()
        val exempt = mapOf(
            "dlReq" to "downloading a generated image from the vendor's storage URL - not the provider",
            "CODEX" to "Codex OAuth branch - must keep the official client fingerprint",
        )
        var checked = 0
        for ((i, line) in lines.withIndex()) {
            if (!line.contains("Request.Builder()") || line.trimStart().startsWith("*") || line.trimStart().startsWith("//")) continue
            val window = lines.subList(i, minOf(lines.size, i + 25)).joinToString("\n")
            val before = lines.subList(maxOf(0, i - 3), i).joinToString("\n")
            val isDownload = line.contains("dlReq")
            val isCodex = before.contains("isOAuth && !forceChatCompletions") || window.contains("Chatgpt-Account-Id") ||
                lines.subList(i, minOf(lines.size, i + 12)).any { it.contains("codex") || it.contains("Codex") }
            if (isDownload || isCodex) continue
            checked++
            assertTrue("line ${i + 1} builds a request without applyProviderHeaders (exempt: $exempt)", window.contains("applyProviderHeaders("))
        }
        assertEquals("chat, image generation, image edit, passthrough", 4, checked)
    }

    @Test
    fun `every provider entry says which conversation it serves`() {
        val factory = ProductionSources.read("provider/ProviderFactory.kt")
        assertTrue("sessionId has no default", factory.contains("        sessionId: String?,\n"))
        // Entry -> the session id it passes. A new entry must be added here
        // with its reason, so none can default to "no header" unnoticed.
        val expected = mapOf(
            "ui/chat/ChatViewModel.kt" to "sessionId = activeSessionId",
            "ui/chat/TitleCandidates.kt" to "sessionId = sessionId",
            "sandbox/offload/ModelUseOffloadHandler.kt" to "sessionId = callerSid",
            "tools/VisionGroupResolver.kt" to "sessionId = sessionId",
            "ui/sessions/SessionListViewModel.kt" to "sessionId = ProviderFactory.oneOffSessionId()",
            "speech/correction/CorrectionStrategy.kt" to "sessionId = ProviderFactory.oneOffSessionId()",
            "ui/components/QuickTestSheet.kt" to "sessionId = ProviderFactory.oneOffSessionId()",
        )
        for ((file, arg) in expected) {
            val src = ProductionSources.read(file)
            val calls = src.split("ProviderFactory.create(").drop(1).map { it.substringBefore("\n        )").take(400) }
            assertTrue("$file still calls the factory", calls.isNotEmpty())
            for (c in calls) assertTrue("$file: every create passes `$arg`", c.contains(arg))
        }
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue("vision gets the chat's id", vm.contains("customPrompt = customPrompt,\n            sessionId = activeSessionId,"))
    }

    @Test
    fun `side entries take their credential from requestCredential (refreshed OAuth)`() {
        for (file in listOf(
            "ui/chat/TitleCandidates.kt", "ui/sessions/SessionListViewModel.kt", "tools/VisionGroupResolver.kt",
            "speech/correction/CorrectionStrategy.kt", "sandbox/offload/ModelUseOffloadHandler.kt", "ui/components/QuickTestSheet.kt",
        )) {
            assertTrue(file, ProductionSources.read(file).contains("requestCredential(instance)"))
        }
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertTrue(repo.contains("OAuthManager.forInstance(context, instance)?.validAccessToken()"))
    }

    @Test
    fun `voice providers carry the instance User-Agent`() {
        val factory = ProductionSources.read("provider/voice/VoiceProviderFactory.kt")
        assertTrue(factory.contains("makeVendor(instance, apiKey)?.also { it.customUserAgent = instance.customUserAgent }"))
        val voice = ProductionSources.read("provider/voice/VoiceProvider.kt")
        assertTrue(voice.contains("Companion.httpClient.newBuilder().addInterceptor"))
    }
}
