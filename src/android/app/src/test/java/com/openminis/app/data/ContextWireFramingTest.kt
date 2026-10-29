package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [T-ctx-count-wire-framing] The estimate counts what frames a message on the
 * wire, not only its text (port of iOS e48d29ee9, ContextWireFramingTests).
 *
 * Measured with the estimator over Android's own Chat Completions body
 * (OpenAIProvider.buildRequestBody, DeepSeek echoing reasoning_content) before
 * this change: 120 small tool rounds read 2.02x the structured estimate, a
 * trimmed history 1.91x, large messages 1.08x - the calibration ratio jumped
 * at every trim. It left out call ids, the second escaping of arguments and
 * the keys around every message and call.
 */
class ContextWireFramingTest {

    private val M = ContextSizeMeter

    private val deepseek = LLMModel(
        id = "deepseek-flash", displayName = "DeepSeek", provider = "OpenAI",
        supportsReasoning = true, interleavedReasoningField = "reasoning_content",
    )

    private fun provider() = OpenAIProvider(apiKey = "k", model = deepseek, basePath = "https://api.deepseek.com/v1")

    /** The estimator over the Chat Completions `messages` this history becomes. */
    private fun wireTokens(history: List<LLMMessage>): Int {
        val body = provider().buildRequestBody(
            messages = history, systemPrompt = null, maxTokens = 1024, stream = false,
            temperature = null, imageParts = emptyList(), thinkingLevel = ThinkingLevel.HIGH,
        )
        return M.estimateTokens(body.getJSONArray("messages").toString())
    }

    private fun round(i: Int, command: String, output: String, reasoning: String? = ""): List<LLMMessage> {
        val id = "call_00_" + i.toString().padStart(24, '0')
        val call = LLMMessage(
            role = LLMMessage.Role.ASSISTANT, content = "",
            contentParts = listOf(
                AgentContentPart.ToolUse(id, "shell_execute", JSONObject().put("command", command).put("tool_title", "step $i")),
            ),
            reasoningContent = reasoning,
        )
        val result = LLMMessage(
            role = LLMMessage.Role.USER, content = "",
            contentParts = listOf(AgentContentPart.ToolResult(id, "shell_execute", output)),
        )
        return listOf(call, result)
    }

    private fun history(rounds: Int, command: (Int) -> String, output: String, reasoning: String? = ""): List<LLMMessage> {
        val h = mutableListOf(LLMMessage(LLMMessage.Role.USER, "please finish the plan"))
        for (i in 0 until rounds) h += round(i, command(i), output, reasoning)
        return h
    }

    private fun ratio(h: List<LLMMessage>): Double =
        wireTokens(h).toDouble() / M.estimateTokens(h, includeReasoning = true)

    // ── Pieces ─────────────────────────────────────────────────────────────

    @Test fun `a call id is counted on the call and on the result`() {
        val longId = "call_00_6JtVL9iU05cirHCa5lT25476"
        val input = JSONObject().put("command", "ls")
        assertEquals(
            M.estimateTokens(longId) - M.estimateTokens("c"),
            M.estimateTokens(AgentContentPart.ToolUse(longId, "shell_execute", input)) -
                M.estimateTokens(AgentContentPart.ToolUse("c", "shell_execute", input)),
        )
        val r1 = M.estimateTokens(AgentContentPart.ToolResult("c", "shell_execute", "ok"))
        val r2 = M.estimateTokens(AgentContentPart.ToolResult(longId, "shell_execute", "ok"))
        assertTrue("$r2 vs $r1", r2 > r1 + 5)
    }

    @Test fun `escaping is counted for quotes, backslashes and newlines`() {
        assertEquals(0, M.wireEscapeTokens("plain text 123"))
        assertEquals(7, M.wireEscapeTokens("\"".repeat(20)))
        assertEquals(2, M.wireEscapeTokens("a\nb\nc\\d\te")) // 4 escaped characters x 0.35, rounded up
        assertEquals(0, M.estimateWireTokens(""))
        val json = """{"a":"b","c":"d"}"""
        assertEquals(M.estimateTokens(json) + M.wireEscapeTokens(json), M.estimateWireTokens(json))
    }

    @Test fun `arguments are counted as a string inside the body`() {
        val quoted = "say \"hi\" ".repeat(200)
        val part = AgentContentPart.ToolUse("c", "shell_execute", JSONObject().put("command", quoted))
        // Escaped once by input.toString() (\"), then again as a string value (\\\").
        assertTrue(M.estimateTokens(part) > M.estimateTokens(quoted) + 300)
    }

    @Test fun `the tool name is not counted on the result`() {
        assertEquals(
            M.estimateTokens(AgentContentPart.ToolResult("c", "x", "ok")),
            M.estimateTokens(AgentContentPart.ToolResult("c", "a_very_long_tool_name_that_is_not_sent_again", "ok")),
        )
    }

    @Test fun `framing constants match iOS`() {
        assertEquals(8, M.PER_MESSAGE_OVERHEAD)
        assertEquals(22, M.PER_TOOL_USE_OVERHEAD)
        assertEquals(6, M.PER_TOOL_RESULT_OVERHEAD)
        assertEquals(6, M.REASONING_FIELD_OVERHEAD)
    }

    @Test fun `the reasoning field costs even when empty, only when echoed`() {
        val withField = LLMMessage(LLMMessage.Role.ASSISTANT, "ok", reasoningContent = "")
        val noField = LLMMessage(LLMMessage.Role.ASSISTANT, "ok")
        assertEquals(M.REASONING_FIELD_OVERHEAD,
            M.estimateTokens(listOf(withField), includeReasoning = true) - M.estimateTokens(listOf(noField), includeReasoning = true))
        assertEquals("not echoed: not counted", M.estimateTokens(listOf(noField)), M.estimateTokens(listOf(withField)))
    }

    @Test fun `the cache separates an empty field from no field`() {
        val id = "wire-" + java.util.UUID.randomUUID()
        val noField = LLMMessage(LLMMessage.Role.ASSISTANT, "ok", dbMessageId = id)
        val withField = noField.copy(reasoningContent = "")
        val a = M.estimateTokens(listOf(noField), includeReasoning = true)
        val b = M.estimateTokens(listOf(withField), includeReasoning = true)
        assertEquals(M.REASONING_FIELD_OVERHEAD, b - a)
    }

    @Test fun `plain text messages are still close to their text`() {
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(100)
        assertEquals(M.estimateTokens(text) + M.PER_MESSAGE_OVERHEAD,
            M.estimateTokens(listOf(LLMMessage(LLMMessage.Role.USER, text))))
    }

    // ── Against the real request body ──────────────────────────────────────

    /**
     * Many small tool rounds - the shape a trimmed history has, where framing
     * is the largest share. The structured estimate must stay close to the
     * estimator run over the body the provider is actually sent (2.02x before).
     */
    @Test fun `many small rounds measure like the body that is sent`() {
        val h = history(120, { "grep -n \"total\" lib/sum_$it.sh | head -3" }, "12: total=\$((total + 1))\n[exit 0]")
        val r = ratio(h)
        assertEquals("wire ${wireTokens(h)} vs estimate ${M.estimateTokens(h, includeReasoning = true)}", 1.0, r, 0.08)
    }

    @Test fun `the ratio no longer depends on how small the messages are`() {
        val large = ratio(history(60, { "cat lib/file_$it.sh" }, "echo \"line\" # comment\n".repeat(3000 / 22),
            "Considering the next step. ".repeat(6000 / 27)))
        val trimmed = ratio(history(60, { "cat lib/file_$it.sh" }, "echo \"line\" # comment\n".repeat(5)))
        assertEquals(1.0, large, 0.05)
        assertEquals(1.0, trimmed, 0.08)
        assertTrue("large $large vs trimmed $trimmed (before: 1.08 against 1.91)", abs(trimmed - large) < 0.08)
    }
}
