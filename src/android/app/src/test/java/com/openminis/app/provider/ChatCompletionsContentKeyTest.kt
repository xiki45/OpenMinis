package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-261024-04] TG Open Minis 95907: replaying tool history over Chat
 * Completions sent an assistant `tool_calls` message with no `content` key,
 * which some OpenAI-compatible servers reject. Every message must carry the
 * key, "" (not null) for a tool-only assistant turn.
 */
class ChatCompletionsContentKeyTest {

    private val model = LLMModel(id = "gpt-4o", displayName = "gpt-4o", provider = "test")

    private fun messagesOf(history: List<LLMMessage>): JSONArray =
        OpenAIProvider(apiKey = "test-key", model = model, basePath = "https://example.invalid/v1")
            .buildRequestBody(
                messages = history,
                systemPrompt = null,
                maxTokens = 1024,
                stream = false,
                temperature = null,
                imageParts = emptyList(),
            ).getJSONArray("messages")

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)

    private fun toolOnly(id: String) = LLMMessage(
        LLMMessage.Role.ASSISTANT, "",
        contentParts = listOf(AgentContentPart.ToolUse(id, "shell_execute", JSONObject().put("command", "ls"))),
    )

    private fun textAndTool(id: String) = LLMMessage(
        LLMMessage.Role.ASSISTANT, "let me look",
        contentParts = listOf(
            AgentContentPart.Text("let me look"),
            AgentContentPart.ToolUse(id, "shell_execute", JSONObject().put("command", "ls")),
        ),
    )

    private fun result(id: String) = LLMMessage(
        LLMMessage.Role.USER, "",
        contentParts = listOf(AgentContentPart.ToolResult(id, "shell_execute", "a\nb")),
    )

    @Test
    fun toolOnlyAssistantCarriesEmptyContent() {
        val msgs = messagesOf(listOf(user("hi"), toolOnly("c1"), result("c1")))
        val a = msgs.getJSONObject(1)
        assertEquals("assistant", a.getString("role"))
        assertTrue("content key present", a.has("content"))
        assertFalse("content is not null", a.isNull("content"))
        assertEquals("", a.getString("content"))
        assertEquals(1, a.getJSONArray("tool_calls").length())
    }

    @Test
    fun textAndToolCallsAssistantKeepsText() {
        val a = messagesOf(listOf(user("hi"), textAndTool("c1"), result("c1"))).getJSONObject(1)
        assertEquals("let me look", a.getString("content"))
        assertEquals(1, a.getJSONArray("tool_calls").length())
    }

    @Test
    fun toolResultMessageCarriesContent() {
        val t = messagesOf(listOf(user("hi"), toolOnly("c1"), result("c1"))).getJSONObject(2)
        assertEquals("tool", t.getString("role"))
        assertEquals("c1", t.getString("tool_call_id"))
        assertEquals("a\nb", t.getString("content"))
    }

    @Test
    fun multiTurnReplayEveryMessageHasContent() {
        val msgs = messagesOf(
            listOf(
                user("hi"),
                toolOnly("c1"), result("c1"),
                textAndTool("c2"), result("c2"),
                toolOnly("c3"), result("c3"),
                LLMMessage(LLMMessage.Role.ASSISTANT, "done", contentParts = listOf(AgentContentPart.Text("done"))),
                user("again"),
            ),
        )
        val roles = (0 until msgs.length()).map { msgs.getJSONObject(it).getString("role") }
        assertEquals(listOf("user", "assistant", "tool", "assistant", "tool", "assistant", "tool", "assistant", "user"), roles)
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            assertTrue("message $i has no content key: $m", m.has("content"))
            assertFalse("message $i content is null: $m", m.isNull("content"))
        }
        // The serialized body a server sees has the key too.
        assertTrue(msgs.getJSONObject(5).toString().contains("\"content\":\"\""))
    }
}
