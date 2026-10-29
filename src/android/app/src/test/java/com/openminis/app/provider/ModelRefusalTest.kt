package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.ui.chat.EmptyTurnRecovery
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-model-refusal] A model DECLINING a request (Anthropic
 * stop_reason=refusal, OpenAI content_filter) is surfaced on the reply instead
 * of ending the run silently. Port of iOS 54c8ea2a4.
 *
 * Field report: an agent task on claude-sonnet-5-5 "stopped streaming silently
 * and could not recover" — every request was HTTP 200 in ~3 s with
 * stop_reason="refusal" and empty content; the loop logged "empty turn
 * detected … finishReason=refusal" and showed nothing.
 */
class ModelRefusalTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun anthropic(model: LLMModel = LLMModel.claudeSonnet55) =
        AnthropicProvider(apiKey = "k", model = model, basePath = server.url("/").toString().trimEnd('/'))

    // ---- provider: the refusal arrives as a distinct stop reason ------------

    @Test
    fun `the reported stream - refusal with empty content - finishes as refusal, not a dropped stream`() = runBlocking {
        val sse = buildString {
            appendLine("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":24989}}}")
            appendLine()
            appendLine("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"},\"usage\":{\"output_tokens\":0}}")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val chunks = anthropic().streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024).toList()
        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals(listOf("refusal"), finished.map { it.stopReason })
        assertTrue("no content", chunks.none { it is LLMStreamChunk.Text })
    }

    @Test
    fun `a refusal is not a silently truncated stream - no transient retry`() = runBlocking {
        // failOnSilentEmptyCompletion throws TransientError (auto-retry) only when
        // NO finish reason arrived. A refusal carries one, so it must pass through:
        // retrying the identical request is just declined again.
        val out = flowOf<LLMStreamChunk>(LLMStreamChunk.Finished("refusal"))
            .failOnSilentEmptyCompletion("test").toList()
        assertEquals(1, out.size)
    }

    // ---- the mapping --------------------------------------------------------

    @Test
    fun `refusal spellings`() {
        assertTrue(StopReasons.isRefusal("refusal"))        // Anthropic
        assertTrue(StopReasons.isRefusal("content_filter")) // OpenAI chat + Responses incomplete
        for (r in listOf(null, "", "stop", "end_turn", "length", "max_tokens", "tool_use", "tool_calls", "SAFETY")) {
            assertFalse("$r is not a refusal", StopReasons.isRefusal(r))
        }
    }

    @Test
    fun `a refusal is never classified as an empty turn - no nudge, no retry round`() {
        for (r in listOf("refusal", "content_filter")) {
            assertFalse(r, EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = r))
        }
    }

    // ---- the loop-end branch --------------------------------------------------

    @Test
    fun `the loop surfaces the refusal on the reply without resume, before any empty-turn handling`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val branch = vm.substringAfter("if (com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason)) {")
            .substringBefore("\n                }\n")
        assertTrue("logged", branch.contains("model declined the request"))
        // [FIX-261018-12] The text names the provider's category when there is
        // one (falling back to the generic message), and there is no Resume:
        // resuming re-sends the same context and is declined again.
        assertTrue("shown on the reply", branch.contains("setInlineError(refusalText)")
            && branch.contains("R.string.chat_error_model_refused_category")
            && branch.contains("genericMessage = context.getString(R.string.chat_error_model_refused)"))
        assertTrue("no resume affordance", branch.contains("_canResume.value = false") && !branch.contains("_canResume.value = true"))
        assertTrue("ends the run deliberately", branch.contains("loopExitedNormally = true") && branch.contains("break"))
        val refusalAt = vm.indexOf("if (com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason)) {")
        val emptyAt = vm.indexOf("val isEmptyTurn = EmptyTurnRecovery.shouldClassifyAsEmptyTurn(")
        assertTrue("the refusal branch runs before the empty-turn path", refusalAt in 0 until emptyAt)
    }

    @Test
    fun `the message exists in every locale iOS translated`() {
        val res = listOf("src/main/res", "app/src/main/res", "src/android/app/src/main/res")
            .map { java.io.File(it) }.first { it.exists() }
        val dirs = listOf(
            "values", "values-de", "values-es", "values-fil", "values-fr", "values-hr", "values-in", "values-ja",
            "values-ko", "values-ms", "values-pl", "values-pt-rBR", "values-ro", "values-ru", "values-th",
            "values-tr", "values-zh", "values-zh-rTW",
        )
        for (d in dirs) {
            assertTrue(d, java.io.File(res, "$d/strings.xml").readText().contains("name=\"chat_error_model_refused\""))
        }
        assertTrue(
            java.io.File(res, "values/strings.xml").readText().contains(
                ">The model declined to respond to this request. Try rephrasing, or switch to a different model.<",
            ),
        )
    }
}
