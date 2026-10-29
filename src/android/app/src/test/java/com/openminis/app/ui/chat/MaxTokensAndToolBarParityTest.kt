package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.StopReasons
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS parity for three things a field log showed (realme, 1.15 build 29):
 *  - [T-android-max-tokens-notice] a reply that stopped at the output-token
 *    limit ended silently; iOS shows "Response truncated (max tokens reached)"
 *    and offers Resume (AIChatViewModel+AgentLoop.swift, no-tool exit).
 *  - [T-android-max-tokens-default] the default output budget per provider
 *    (iOS defaultMaxTokens: Chat Completions 16K, Responses 32K, Anthropic
 *    64K, Gemini 16K).
 *  - [T-android-toolbar-after-compaction] the floating tool bar counted tool
 *    calls above the compaction divider ("1941/1941"); iOS leaves them out.
 */
class MaxTokensAndToolBarParityTest {

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    // -- max tokens: stop reason --

    @Test
    fun `every provider spelling of an output-limit stop is recognised, nothing else is`() {
        for (r in listOf("length", "max_tokens", "max_output_tokens")) assertTrue(r, StopReasons.isMaxTokens(r))
        for (r in listOf(null, "", "stop", "end_turn", "tool_calls", "tool_use", "refusal", "content_filter")) {
            assertFalse("$r", StopReasons.isMaxTokens(r))
        }
    }

    @Test
    fun `a max-tokens stop is still not an empty turn, so it never gets the nudge or a retry`() {
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = "length"))
        assertFalse(EmptyTurnRecovery.shouldClassifyAsEmptyTurn(hasText = false, hasToolCall = false, stopReason = "max_tokens"))
    }

    // -- max tokens: the loop branch --

    private val branch: String by lazy {
        val start = vm.indexOf("if (com.openminis.app.provider.StopReasons.isMaxTokens(turnFinishReason) && !closingRequestDue) {")
        assertTrue("the max-tokens branch exists", start > 0)
        vm.substring(start, vm.indexOf("val isEmptyTurn = EmptyTurnRecovery.shouldClassifyAsEmptyTurn(", start))
    }

    @Test
    fun `the branch shows the truncation notice, offers Resume and ends the loop normally`() {
        assertTrue(branch.contains("setInlineError(context.getString(R.string.chat_error_max_tokens_truncated))"))
        assertTrue(branch.contains("_canResume.value = true"))
        assertTrue("no fake turn-limit error afterwards", branch.contains("loopExitedNormally = true"))
        assertTrue(branch.contains("break"))
    }

    @Test
    fun `the branch sits in the no-tool exit, after the refusal branch and before the empty-turn path`() {
        val noToolExit = vm.indexOf("runAgentLoop turn=\$turn no tool calls → break")
        val refusal = vm.indexOf("if (com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason)) {", noToolExit)
        val maxTokens = vm.indexOf("StopReasons.isMaxTokens(turnFinishReason) && !closingRequestDue", noToolExit)
        val emptyTurn = vm.indexOf("val isEmptyTurn = EmptyTurnRecovery.shouldClassifyAsEmptyTurn(", noToolExit)
        assertTrue(noToolExit in 1 until refusal)
        assertTrue(refusal < maxTokens)
        assertTrue(maxTokens < emptyTurn)
    }

    @Test
    fun `a sub agent owed its closing request keeps that path, as on iOS`() {
        // closingRequestDue is computed before the branch and gates it.
        val due = vm.indexOf("val closingRequestDue = com.openminis.app.agent.jobs.HelperRunner.shouldSendClosingRequest(")
        val maxTokens = vm.indexOf("StopReasons.isMaxTokens(turnFinishReason) && !closingRequestDue")
        assertTrue(due in 1 until maxTokens)
    }

    // -- default output budget --

    private val model = LLMModel("gpt-x", "GPT X", "OpenAI")

    @Test
    fun `OpenAI-compatible default is 16K on Chat Completions and 32K on the Responses API`() {
        assertEquals(16_384, OpenAIProvider.defaultMaxOutputTokensFor(chatCompletions = true))
        assertEquals(32_768, OpenAIProvider.defaultMaxOutputTokensFor(chatCompletions = false))
        assertEquals(16_384, OpenAIProvider(apiKey = "k", model = model).defaultMaxOutputTokens)
        assertEquals(32_768, OpenAIProvider(apiKey = "k", model = model, useResponsesAPI = true).defaultMaxOutputTokens)
        assertEquals("OAuth (Codex) is the Responses API", 32_768, OpenAIProvider(oauthTokenProvider = { "t" }, model = model).defaultMaxOutputTokens)
    }

    @Test
    fun `a model's own maxOutputTokens still wins over the provider default`() {
        val declared = model.copy(maxOutputTokens = 8_000)
        val p = OpenAIProvider(apiKey = "k", model = declared, useResponsesAPI = true)
        assertEquals(8_000, p.effectiveMaxOutputTokens(declared))
    }

    @Test
    fun `Anthropic stays at 64K and the shared fallback at 16K, matching iOS`() {
        val anthropic = ProductionSources.read("provider/anthropic/AnthropicProvider.kt")
        assertTrue(anthropic.contains("override val defaultMaxOutputTokens: Int get() = 64_000"))
        val llm = ProductionSources.read("provider/LLMProvider.kt")
        assertTrue(llm.contains("val defaultMaxOutputTokens: Int get() = 16_384"))
        assertFalse("Gemini inherits the 16K fallback", ProductionSources.read("provider/gemini/GeminiProvider.kt").contains("defaultMaxOutputTokens"))
    }

    // -- floating tool bar after a compaction --

    private fun tool(id: String, kind: String = "tool_use", status: ToolBlockStatus? = ToolBlockStatus.SUCCESS) =
        AssistantBlock(id = id, kind = kind, toolStatus = status)

    private fun reply(id: String, compacted: Boolean, vararg blocks: AssistantBlock) =
        ChatMessage(id = id, role = "assistant", content = "", toolBlocks = blocks.toList(), isCompactedHistory = compacted)

    @Test
    fun `tool calls above the compaction divider are not counted`() {
        val messages = listOf(
            reply("old1", compacted = true, tool("a"), tool("b")),
            ChatMessage(id = "u1", role = "user", content = "继续", isCompactedHistory = true),
            reply("old2", compacted = true, tool("c")),
            ChatMessage(id = "div", role = "system", content = "3 messages compacted"),
            ChatMessage(id = "u2", role = "user", content = "继续修"),
            reply("new1", compacted = false, tool("d"), tool("think", kind = "thinking"), tool("note", kind = "info")),
            reply("new2", compacted = false, tool("e"), AssistantBlock(id = "txt", kind = "text", content = "hi")),
        )
        assertEquals(listOf("d", "e"), floatingToolBarBlocks(messages).map { it.id })
    }

    @Test
    fun `without a compaction every tool call still counts`() {
        val messages = listOf(reply("r1", false, tool("a")), reply("r2", false, tool("b"), tool("c", status = ToolBlockStatus.RUNNING)))
        assertEquals(listOf("a", "b", "c"), floatingToolBarBlocks(messages).map { it.id })
    }

    @Test
    fun `the chat screen builds the bar and its detail sheet from this one function`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("floatingToolBarBlocks(merged)"))
        assertFalse("the old unfiltered chain is gone",
            screen.contains(".flatMap { it.toolBlocks }\n                            .filter { it.toolStatus != null"))
    }

    // -- strings --

    private val res = java.io.File(ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile, "res")

    @Test
    fun `the truncation notice exists in every shipped locale`() {
        val dirs = res.listFiles()!!.filter { java.io.File(it, "strings.xml").exists() }.map { it.name }
        assertTrue("found the locales: $dirs", dirs.size >= 18)
        for (dir in dirs) {
            val xml = java.io.File(res, "$dir/strings.xml").readText()
            val value = Regex("""<string name="chat_error_max_tokens_truncated">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue("$dir is missing chat_error_max_tokens_truncated", !value.isNullOrBlank())
            assertFalse("$dir has an unescaped apostrophe", Regex("""(?<!\\)'""").containsMatchIn(value!!))
        }
        assertTrue(java.io.File(res, "values/strings.xml").readText()
            .contains("<string name=\"chat_error_max_tokens_truncated\">Response truncated (max tokens reached)</string>"))
    }
}
