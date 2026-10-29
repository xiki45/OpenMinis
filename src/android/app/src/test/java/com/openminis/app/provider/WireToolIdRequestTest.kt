package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [B02] A session that ran on the Responses API stores combined tool ids
 * (`call_…|fc_…`). Switching it to an Anthropic or a Chat Completions model
 * must send ids without '|', within 40 chars, every call followed by its
 * result under the same id, and two calls that share a call_id (different
 * fc_id) still told apart. Drives the real providers through a MockWebServer
 * and reads the serialized request.
 */
class WireToolIdRequestTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val legal = Regex("^[A-Za-z0-9_-]+$")
    private val long = "call_" + "x".repeat(60) + "|fc_" + "9".repeat(48)

    /** Responses-origin history: two rounds, a collision pair and an over-long id. */
    private fun history(): List<LLMMessage> {
        fun use(id: String) = AgentContentPart.ToolUse(id, "shell_execute", JSONObject().put("command", "ls"))
        // The output text names no id: the body must carry no '|' at all.
        fun result(id: String) = AgentContentPart.ToolResult(id, "shell_execute", "file.txt")
        val first = listOf("call_same|fc_1", "call_same|fc_2", long)
        return listOf(
            LLMMessage(LLMMessage.Role.USER, "q1"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = first.map(::use)),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = first.map(::result)),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(use("call_abc|fc_def"))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(result("call_abc|fc_def"))),
            LLMMessage(LLMMessage.Role.USER, "q2"),
        )
    }

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "Test", supportsReasoning = false)

    private fun enqueue(body: String) = repeat(4) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun captureAnthropic(): JSONObject {
        enqueue("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""")
        val provider = AnthropicProvider(
            apiKey = "test-key",
            model = model("claude-test"),
            basePath = server.url("/").toString().trimEnd('/'),
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(history(), null, 4096, null, emptyList(), emptyList(), ThinkingLevel.OFF)
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun captureChat(): JSONObject {
        enqueue("""{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""")
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model("relay-claude"),
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = false,
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(history(), null, 4096, null, emptyList(), emptyList(), ThinkingLevel.OFF)
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun assertWireIds(ids: List<String>) {
        assertEquals("4 calls", 4, ids.size)
        assertEquals("unique within the request: $ids", ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue("'$id' has a character outside [A-Za-z0-9_-]", legal.matches(id))
            assertTrue("'$id' is ${id.length} chars, over 40", id.length <= 40)
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    @Test
    fun `anthropic request - no pipe, at most 40, each tool_use answered under its id`() {
        val body = captureAnthropic()
        assertFalse(body.toString().contains('|'))
        val messages = body.getJSONArray("messages").objects()
        val uses = mutableListOf<String>()
        messages.forEachIndexed { i, msg ->
            val content = msg.optJSONArray("content")?.objects() ?: return@forEachIndexed
            val here = content.filter { it.optString("type") == "tool_use" }.map { it.getString("id") }
            if (here.isEmpty()) return@forEachIndexed
            uses += here
            val answered = messages[i + 1].getJSONArray("content").objects()
                .filter { it.optString("type") == "tool_result" }.map { it.getString("tool_use_id") }
            assertEquals("results follow their calls under the same ids", here, answered)
        }
        assertWireIds(uses)
        assertEquals("call_abc", uses.last())
    }

    @Test
    fun `chat completions request - no pipe, at most 40, each tool call answered under its id`() {
        val body = captureChat()
        assertFalse(body.getJSONArray("messages").toString().contains('|'))
        val messages = body.getJSONArray("messages").objects()
        val calls = mutableListOf<String>()
        messages.forEachIndexed { i, msg ->
            val here = msg.optJSONArray("tool_calls")?.objects()?.map { it.getString("id") } ?: return@forEachIndexed
            calls += here
            val answered = messages.drop(i + 1).takeWhile { it.optString("role") == "tool" }
                .map { it.getString("tool_call_id") }
            assertEquals("results follow their calls under the same ids", here, answered)
        }
        assertWireIds(calls)
        assertEquals("call_abc", calls.last())
    }

    @Test
    fun `both protocols send the same wire id for a stored id`() {
        val anthropic = captureAnthropic().getJSONArray("messages").getJSONObject(1)
            .getJSONArray("content").objects().map { it.getString("id") }
        val chat = captureChat().getJSONArray("messages").objects()
            .first { it.has("tool_calls") }.getJSONArray("tool_calls").objects().map { it.getString("id") }
        assertEquals(anthropic[0], chat[0])
        assertEquals(anthropic[2], chat[2])
    }

    // ---------------------------------------------------------------- source guards

    private fun source(path: String): String = sequenceOf(
        File("src/main/java/com/openminis/app/$path"),       // CWD = src/android/app
        File("app/src/main/java/com/openminis/app/$path"),   // CWD = src/android
    ).first { it.isFile }.readText()

    @Test
    fun `chat completions builder sends every tool id through the per-request wire map`() {
        val src = source("provider/openai/OpenAIProvider.kt")
        val start = src.indexOf("internal fun buildRequestBody(")
        val end = src.indexOf("private fun globallyDedupeToolCallIds(")
        assertTrue(start in 0 until end)
        val builder = src.substring(start, end)
        val writes = Regex("""put\("(?:id|tool_call_id)",\s*([^)\n]*)""").findAll(builder).map { it.groupValues[1] }.toList()
        assertTrue("the builder writes tool ids: $writes", writes.size >= 2)
        for (w in writes) {
            assertTrue("'$w' does not go through the wire map", w.trim().startsWith("wireIds.idFor("))
        }
        // One map per request; a Kimi target's map keeps Kimi-native ids ([T-kimi-native-tool-id]).
        assertTrue("one map per request", builder.contains("val wireIds = if (keepsKimiToolIds) {\n            WireToolIdMap(keepAsIs = ::isKimiNativeToolId)\n        } else {\n            WireToolIdMap()\n        }"))
        assertFalse("the 64-char cap is gone", src.contains("callHalf.length <= 64"))
    }

    @Test
    fun `anthropic tool_use and tool_result ids go through the per-request wire map`() {
        val src = source("provider/anthropic/AnthropicProvider.kt")
        assertTrue(src.contains("""put("id", wireIds.idFor(part.id))"""))
        assertTrue(src.contains("""put("tool_use_id", wireIds.idFor(part.id))"""))
        assertFalse(src.contains("sanitizeToolId(part.id)"))
    }
}
