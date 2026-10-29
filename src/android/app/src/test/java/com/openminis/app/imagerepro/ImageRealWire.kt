package com.openminis.app.imagerepro

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
 * their bodies are captured off a local MockWebServer (nothing leaves the
 * machine); OpenAI Chat Completions and Responses expose internal builders.
 *
 * Unlike ImageReproWire there are no hand-built message shapes here: the
 * ImageReal* tests get their messages from the real extracted functions.
 */
object ImageRealWire {

    val visionModel = LLMModel("vision-test", "Vision Test", "test", inputModalities = listOf("text", "image"))

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

    fun leadingUser(text: String = "go") = LLMMessage(LLMMessage.Role.USER, text)
}
