package com.openminis.app.imagerepro

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject

/**
 * The four Android serializers, each returning the request body it really
 * builds for a history. Anthropic and Gemini keep their builders private, so
 * their bodies are captured off a MockWebServer (no network leaves the
 * machine); OpenAI Chat Completions and Responses expose internal builders.
 *
 * Plus the message SHAPES ChatViewModel builds. ChatViewModel cannot be
 * constructed in a JVM test (Room + Context), so these are rebuilt here from
 * the source; ImageReproHistoryFactsTest pins every line they mirror, so a
 * production change that alters the shape turns that test red.
 */
object ImageReproWire {

    val visionModel = LLMModel("vision-test", "Vision Test", "test", inputModalities = listOf("text", "image"))

    const val PROVIDERS_NOTE = "anthropic | openai.chat | openai.responses | gemini"

    fun anthropic(messages: List<LLMMessage>, imageParts: List<LLMMessage.ImagePart> = emptyList()): JSONObject =
        captured("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""") { base ->
            AnthropicProvider(apiKey = "test-key", model = LLMModel.claudeHaiku45, basePath = base)
                .sendMessage(messages, null, 256, imageParts = imageParts)
        }

    fun gemini(messages: List<LLMMessage>, imageParts: List<LLMMessage.ImagePart> = emptyList()): JSONObject =
        captured("""{"candidates":[{"content":{"parts":[{"text":"ok"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1}}""") { base ->
            GeminiProvider(apiKey = "test-key", model = LLMModel.gemini25Flash, basePath = base)
                .sendMessage(messages, null, 256, imageParts = imageParts)
        }

    fun openAIChat(messages: List<LLMMessage>, imageParts: List<LLMMessage.ImagePart> = emptyList()): JSONObject =
        OpenAIProvider(apiKey = "k", model = visionModel, basePath = "https://example.invalid/v1")
            .buildRequestBody(messages, null, 256, false, null, imageParts)

    fun openAIResponses(messages: List<LLMMessage>, imageParts: List<LLMMessage.ImagePart> = emptyList()): JSONObject =
        OpenAIProvider(apiKey = "k", model = visionModel, basePath = "https://example.invalid/v1", useResponsesAPI = true)
            .buildResponsesAPIBody(messages, null, 256, false, imageParts)

    /** All four bodies, keyed by serializer name. */
    fun all(messages: List<LLMMessage>, imageParts: List<LLMMessage.ImagePart> = emptyList()): Map<String, JSONObject> =
        linkedMapOf(
            "anthropic" to anthropic(messages, imageParts),
            "openai.chat" to openAIChat(messages, imageParts),
            "openai.responses" to openAIResponses(messages, imageParts),
            "gemini" to gemini(messages, imageParts),
        )

    private fun captured(response: String, call: suspend (String) -> Unit): JSONObject {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(response).setHeader("Content-Type", "application/json"))
            runBlocking { runCatching { call(server.url("/").toString().trimEnd('/')) } }
            return JSONObject(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    // ── ChatViewModel message shapes (pinned by ImageReproHistoryFactsTest) ──

    /**
     * Live user turn, as the send path builds it (CVM `addModelImage` +
     * `agentHistory.add(LLMMessage(… imageParts = imageParts, contentParts =
     * userContentParts …))`): the SAME array sits in imageParts and in
     * contentParts.ImageData, after an "[attached image: <path>]" caption.
     */
    fun liveUserTurn(body: String, image: ByteArray, mime: String, path: String?, note: String? = null): LLMMessage {
        val part = LLMMessage.ImagePart(image, mime, linuxPath = path)
        val parts = mutableListOf<AgentContentPart>()
        if (body.isNotEmpty()) parts += AgentContentPart.Text(body)
        if (path != null) parts += AgentContentPart.Text("[attached image: $path]")
        parts += AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path)
        if (note != null) parts += AgentContentPart.Text(note)
        return LLMMessage(LLMMessage.Role.USER, body, imageParts = listOf(part), contentParts = parts)
    }

    /**
     * The same turn after persist → reload, as `toLLMMessage` rebuilds it from
     * `buildUserPartsJson` (text part, then mediaRef parts): no caption, a NEW
     * array shared again by imageParts and contentParts.ImageData.
     */
    fun rebuiltUserTurn(body: String, image: ByteArray, mime: String, path: String?, note: String? = null): LLMMessage {
        val parts = mutableListOf<AgentContentPart>()
        if (body.isNotEmpty()) parts += AgentContentPart.Text(body)
        parts += AgentContentPart.ImageData(image, mime, linuxPath = path)
        if (note != null) parts += AgentContentPart.Text(note)
        return LLMMessage(
            LLMMessage.Role.USER, body,
            imageParts = listOf(LLMMessage.ImagePart(image, mime, linuxPath = path)),
            contentParts = parts,
        )
    }

    private val toolInput = JSONObject().put("action", "screenshot")

    /** Assistant tool call + the tool-result turn carrying an image (live). */
    fun liveToolRound(id: String, tool: String, output: String, image: ByteArray?, mime: String?, path: String?): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse(id, tool, toolInput))),
        LLMMessage(
            LLMMessage.Role.USER, "",
            contentParts = listOf(AgentContentPart.ToolResult(id, tool, output, imageData = image, imageMimeType = mime, imageLinuxPath = path)),
        ),
    )

    /** The same round after reload: `toLLMMessage`'s "toolResult" branch keeps text only. */
    fun rebuiltToolRound(id: String, tool: String, output: String): List<LLMMessage> =
        liveToolRound(id, tool, output, image = null, mime = null, path = null)

    fun leadingUser(text: String = "go") = LLMMessage(LLMMessage.Role.USER, text)
}
