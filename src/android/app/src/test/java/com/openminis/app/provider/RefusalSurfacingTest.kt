package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [FIX-261018-12] An Anthropic refusal must end the run WITH an error that
 * says why — also when the refused response carried tool calls. Port of the
 * iOS fix (field log 2026-10-18: a shell + a browser call, then
 * stop_reason=refusal with stop_details category "cyber"; the tools ran and
 * the run ended with nothing on screen).
 */
class RefusalSurfacingTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = AnthropicProvider(
            apiKey = "test-key",
            model = LLMModel.claudeHaiku45,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun stream(vararg dataLines: String): List<LLMStreamChunk> = runBlocking {
        val body = buildString { dataLines.forEach { appendLine("data: $it"); appendLine() } }
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream"))
        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()
    }

    private val explanation =
        "This request triggered restrictions on violative cyber content and was blocked under Anthropic's Usage Policy."

    @Test
    fun `refusal after a tool call carries stop_details`() {
        val chunks = stream(
            """{"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":5}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"shell_execute","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"command\":\"ls\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"refusal","stop_sequence":null,"stop_details":{"type":"refusal","category":"cyber","explanation":"$explanation"}},"usage":{"output_tokens":376}}""",
        )
        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>().single()
        assertEquals("refusal", finished.stopReason)
        assertEquals("cyber", finished.refusalCategory)
        assertEquals(explanation, finished.refusalExplanation)
        // The tool call still streams; the agent loop is what drops it.
        assertTrue(chunks.any { it is LLMStreamChunk.ToolCallComplete })
    }

    @Test
    fun `refusal without stop_details has no category`() {
        val chunks = stream("""{"type":"message_delta","delta":{"stop_reason":"refusal","stop_sequence":null}}""")
        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>().single()
        assertEquals("refusal", finished.stopReason)
        assertNull(finished.refusalCategory)
        assertNull(finished.refusalExplanation)
    }

    @Test
    fun `other stop reasons never carry refusal details`() {
        val chunks = stream("""{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_details":{"category":"x"}}}""")
        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>().single()
        assertEquals("tool_use", finished.stopReason)
        assertNull(finished.refusalCategory)
    }

    @Test
    fun `refusalErrorText prefers the category message and appends the explanation`() {
        val generic = "The model declined to respond."
        assertEquals("Blocked (cyber).\n\nWhy.", StopReasons.refusalErrorText("Blocked (cyber).", generic, "  Why. "))
        assertEquals("Blocked (cyber).", StopReasons.refusalErrorText("Blocked (cyber).", generic, null))
        assertEquals(generic, StopReasons.refusalErrorText(null, generic, null))
        assertEquals("$generic\n\nWhy.", StopReasons.refusalErrorText(" ", generic, "Why."))
        assertEquals(generic, StopReasons.refusalErrorText(null, generic, "   "))
    }

    /** Source pins for the agent-loop half (ChatViewModel cannot run headless). */
    @Test
    fun `agent loop drops refused tool calls before history and offers no Resume`() {
        val src = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
        val drop = src.indexOf("if (com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason) && toolCalls.isNotEmpty()) {")
        val history = src.indexOf("// Build assistant contentParts for history")
        val noTool = src.indexOf("if (toolCalls.isEmpty()) {\n                AppLogger.info(TAG_STREAM, \"runAgentLoop turn=\$turn no tool calls")
        assertTrue("drop found", drop >= 0)
        assertTrue("drop runs before history is built", drop in 0 until history)
        assertTrue("drop runs before the no-tool / tool split", drop < noTool)
        val dropBody = src.substring(drop, history)
        assertTrue(dropBody.contains("allToolBlocks.removeAll { it.kind == \"tool_use\" && it.id in refusedIds }"))
        assertTrue(dropBody.contains("toolCalls.clear()"))

        val branch = src.indexOf("stopDiagExit = \"refusal\"")
        val branchStart = src.lastIndexOf("if (com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason)) {", branch)
        val body = src.substring(branchStart, branch)
        assertTrue(body.contains("StopReasons.refusalErrorText("))
        assertTrue(body.contains("R.string.chat_error_model_refused_category"))
        assertTrue(body.contains("_canResume.value = false"))
        assertFalse(body.contains("_canResume.value = true"))
        assertTrue(src.contains("turnRefusalCategory = chunk.refusalCategory"))
    }

    @Test
    fun `category string exists in en, zh and zh-rTW with one placeholder`() {
        for (dir in listOf("values", "values-zh", "values-zh-rTW")) {
            val xml = File("src/main/res/$dir/strings.xml").readText()
            val line = xml.lines().firstOrNull { it.contains("name=\"chat_error_model_refused_category\"") }
            assertTrue("$dir has the string", line != null)
            assertTrue("$dir uses %1\$s", line!!.contains("%1\$s"))
        }
    }
}
