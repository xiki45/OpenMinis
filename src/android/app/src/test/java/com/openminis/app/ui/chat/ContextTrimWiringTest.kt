package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-trim-ladder] [T-ctx-count-reasoning] Everything around the planner:
 * which providers replay reasoning, what counts as the user speaking, the
 * pinned request on a long single turn, and where the view model calls in.
 */
class ContextTrimWiringTest {

    // ── which providers replay reasoning ────────────────────────────────

    private val deepseek = LLMModel(id = "deepseek-flash", displayName = "DeepSeek", provider = "DeepSeek", supportsReasoning = true)

    @Test
    fun `chat completions with thinking replays reasoning`() {
        val p = OpenAIProvider(apiKey = "k", model = deepseek, basePath = "https://api.deepseek.com/v1")
        assertTrue(p.echoesReasoningContent(ThinkingLevel.MAX))
        assertTrue("a model that always reasons replays it even with thinking off", p.echoesReasoningContent(ThinkingLevel.OFF))
    }

    @Test
    fun `no replay on the Responses API, on Mistral or Cerebras, or for a non-reasoning model`() {
        assertFalse(OpenAIProvider(apiKey = "k", model = deepseek, useResponsesAPI = true).echoesReasoningContent(ThinkingLevel.MAX))
        assertFalse(OpenAIProvider(apiKey = "k", model = deepseek, basePath = "https://api.mistral.ai/v1").echoesReasoningContent(ThinkingLevel.MAX))
        assertFalse(OpenAIProvider(apiKey = "k", model = deepseek, basePath = "https://api.cerebras.ai/v1").echoesReasoningContent(ThinkingLevel.MAX))
        val plain = deepseek.copy(supportsReasoning = false)
        assertFalse(OpenAIProvider(apiKey = "k", model = plain, basePath = "https://api.deepseek.com/v1").echoesReasoningContent(ThinkingLevel.MAX))
    }

    @Test
    fun `Anthropic replays unsigned thinking only on a custom endpoint that asks for it`() {
        val interleaved = deepseek.copy(interleavedReasoningField = "reasoning_content")
        assertTrue(AnthropicProvider(apiKey = "k", model = interleaved, basePath = "https://api.deepseek.com/anthropic").echoesReasoningContent(ThinkingLevel.MAX))
        assertFalse("official endpoint verifies signatures", AnthropicProvider(apiKey = "k", model = interleaved).echoesReasoningContent(ThinkingLevel.MAX))
        assertFalse("no interleave field", AnthropicProvider(apiKey = "k", model = deepseek, basePath = "https://relay.example/anthropic").echoesReasoningContent(ThinkingLevel.MAX))
    }

    @Test
    fun `the default for every other provider is no replay`() {
        val llm = ProductionSources.read("provider/LLMProvider.kt")
        assertTrue(llm.contains("fun echoesReasoningContent(thinkingLevel: ThinkingLevel): Boolean = false"))
        assertFalse(ProductionSources.read("provider/gemini/GeminiProvider.kt").contains("echoesReasoningContent"))
    }

    // ── what counts as the user speaking ────────────────────────────────

    private fun userText(t: String) = LLMMessage(LLMMessage.Role.USER, t, contentParts = listOf(AgentContentPart.Text(t)))

    @Test
    fun `runtime reminders, callbacks and tool results are not the user speaking`() {
        assertTrue(RequestContextBuilder.isRealUserText(userText("继续")))
        assertFalse(RequestContextBuilder.isRealUserText(userText("<system-reminder>[Minis runtime reminder] …</system-reminder>")))
        assertFalse(RequestContextBuilder.isRealUserText(userText("<agent_callback kind=\"finished\">…</agent_callback>")))
        assertFalse(RequestContextBuilder.isRealUserText(userText("   ")))
        val result = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("c1", "shell_execute", "ok")))
        assertFalse(RequestContextBuilder.isRealUserText(result))
        assertFalse(RequestContextBuilder.isRealUserText(LLMMessage(LLMMessage.Role.ASSISTANT, "继续")))
    }

    @Test
    fun `an app note no longer uses up one of the recent user turns`() {
        val history = listOf(
            userText("第一件事").copy(dbMessageId = "u1"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "好", dbMessageId = "a1"),
            userText("第二件事").copy(dbMessageId = "u2"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "好", dbMessageId = "a2"),
            userText("<system-reminder>note</system-reminder>").copy(dbMessageId = "n1"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "好", dbMessageId = "a3"),
            userText("第三件事").copy(dbMessageId = "u3"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "好", dbMessageId = "a4"),
        )
        val walk = RequestContextBuilder.walkBackUserTurnsBounded(history, history.size - 1, maxUserTextTurns = 3, maxMessages = 100)
        assertEquals("three real turns reach back to the first", 0, walk.priorIdx)
        assertEquals(3, walk.userTextTurnsFound)
    }

    // ── pinned request on a long single turn ────────────────────────────

    @Test
    fun `a single turn longer than the cap keeps the user's request verbatim`() {
        val history = mutableListOf(userText("把 MT 管理器完整复刻出来").copy(dbMessageId = "u0"))
        for (i in 0 until 80) {
            history += LLMMessage(
                LLMMessage.Role.ASSISTANT, "",
                contentParts = listOf(AgentContentPart.ToolUse("call_$i", "shell_execute", JSONObject().put("command", "ls"))),
                dbMessageId = "a$i",
            )
            history += LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("call_$i", "shell_execute", "ok")), dbMessageId = "r$i")
        }
        val marker = CompactMarkerEntity(
            id = "mk", sessionId = "s", summary = "S", firstKeptSortOrder = 0, compactedCount = 0, createdAt = 0,
            lastCompactedMessageId = "r79", version = 2,
        )
        val out = RequestContextBuilder.compacted(history, RequestContextBuilder.Compaction(summary = "已经做了很多", marker = marker), 3)
        assertEquals("u0", out.first().dbMessageId)
        assertTrue(out.first().content.contains("把 MT 管理器完整复刻出来"))
        assertTrue("the summary is still injected", out.any { m -> m.content.contains("<context-summary>") || m.contentParts.any { it is AgentContentPart.Text && it.text.contains("<context-summary>") } })
    }

    // ── view model wiring ───────────────────────────────────────────────

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    private fun body(signature: String, end: String): String =
        vm.substringAfter(signature).substringBefore(end)

    @Test
    fun `measurement and dispatch count replayed reasoning and see the frozen trim`() {
        assertTrue(vm.contains("val history = ContextSizeMeter.estimateTokens(effectiveAgentHistoryUncounted(), reasoningEchoed())"))
        assertTrue(vm.contains("lastDispatchEstimate = ContextSizeMeter.estimateTokens(history, reasoningEchoed()) + contextFixedTokens"))
        assertTrue(vm.contains("applyFrozenTrim(RequestContextBuilder.compacted(agentHistory, currentCompaction(), COMPACT_KEEP_RECENT_USER_TURNS))"))
        assertTrue("requests carry the same trim", vm.contains("trim = ::applyFrozenTrim,"))
        assertTrue(vm.contains("dropOrphanedToolParts(input.trim(compacted("))
    }

    @Test
    fun `the in-loop check trims before it measures, and collapses only after a summary had its chance`() {
        val check = body("private suspend fun inLoopContextCheck(", "private fun settleWithoutCompacting(")
        val trim = check.indexOf("replanTrim(\"in-loop\", allowCollapse = !compactAvailable)")
        val measure = check.indexOf("val m = contextMeasurement()")
        assertTrue(trim in 1 until measure)
        assertTrue(check.contains("trimReplanPending = true\n                replanTrim(\"after-compact\", allowCollapse = true)"))
        assertTrue(check.contains("replanTrim(\"compact-failed\", allowCollapse = true)"))
        assertTrue("over the hard watermark stops instead of sending", check.contains("if (!compactAvailable && trimmedOverHard()) {"))
        assertTrue(check.contains("return InLoopContextAction.STOP"))
    }

    @Test
    fun `pre-send trims first so a request that fits is neither compacted nor asked about`() {
        val pre = body("private fun checkContextBeforeSend(", "val verdict = policy.check(tokens, window)")
        assertTrue(pre.indexOf("replanTrim(\"pre-send\"") in 1 until pre.indexOf("val m = contextMeasurement()"))
    }

    @Test
    fun `re-plan rules - trigger, target, tight mode, freeze`() {
        val replan = body("private fun replanTrim(", "private fun summaryWouldFreeLittle(")
        assertTrue("trigger = min(compaction line, watermark trigger)",
            replan.contains("val trigger = ContextTrimPlanner.trigger(currentContextPolicy()?.first?.compactThreshold, wm)"))
        assertTrue("normal mode only re-plans over the trigger or after a compaction",
            replan.contains("if (!tight && !trimReplanPending && size <= trigger) return null"))
        assertTrue("normal mode never empties the newest step's reasoning", replan.contains("reasoningFloor = if (tight) 0 else 1,"))
        assertTrue(replan.contains("keepRecentIterations = if (tight) 1 else 3"))
        assertTrue("tight windows may always collapse", replan.contains("val collapse = allowCollapse || tight ||"))
        assertTrue("the plan is frozen per session", replan.contains("trimStateSid = activeSessionId"))
        assertTrue(replan.contains("[ctx-trim] site=\$site"))
        val frozen = body("private fun applyFrozenTrim(", "private fun replanTrim(")
        assertTrue("a stale plan is dropped and re-planned", frozen.contains("trimReplanPending = true"))
    }

    @Test
    fun `a no-collapse re-plan that recovered too little sends the loop to the compaction branch`() {
        val replan = body("private fun replanTrim(", "private fun summaryWouldFreeLittle(")
        assertTrue("cleared on every call", replan.substringBefore("val wm = currentWatermarks()").contains("lastTrimPlanEscalates = false"))
        assertTrue("only a plan that could not collapse",
            replan.contains("lastTrimPlanEscalates = !collapse && ContextTrimPlanner.shouldEscalate(result.after, wm.target, trigger)"))
        val check = body("private suspend fun inLoopContextCheck(", "private fun settleWithoutCompacting(")
        val read = check.indexOf("val escalate = lastTrimPlanEscalates && compactAvailable")
        assertTrue("read right after the in-loop re-plan, before any other re-plan clears it",
            read > check.indexOf("replanTrim(\"in-loop\", allowCollapse = !compactAvailable)") && read < check.indexOf("trimmedOverHard()"))
        assertTrue("an OK verdict becomes the compaction branch; any other verdict is untouched",
            check.contains("val verdict = if (policyVerdict == ContextPolicy.CheckResult.OK && escalate) {"))
        assertTrue(check.contains("[ctx-trim] the re-plan recovered too little without collapse"))
        // The branch it lands in still tries collapse before a model summary.
        assertTrue(check.indexOf("ContextPolicy.CheckResult.NEEDS_COMPACT -> {") < check.indexOf("if (summaryWouldFreeLittle(tokens)) {"))
    }

    @Test
    fun `a plan made on another compaction marker is dropped before anything is measured, the collapse unlock stays`() {
        val replan = body("private fun replanTrim(", "private fun summaryWouldFreeLittle(")
        val check = replan.indexOf("!ContextTrimPlanner.planFitsMarker(trimStateMarkerId, markerId)")
        val measured = replan.indexOf("val applied = applyFrozenTrim(compacted)")
        assertTrue(check in 1 until measured)
        assertTrue("the marker is the one requests are built from", replan.contains("val markerId = _cachedLatestMarker?.id"))
        assertTrue(vm.contains("marker = _cachedLatestMarker,"))
        val drop = replan.substring(check, measured)
        assertTrue(drop.contains("trimState = null"))
        assertTrue(drop.contains("trimReplanPending = true"))
        assertFalse("the unlock belongs to the session", drop.contains("trimCollapseUnlockedSid"))
        assertTrue(drop.contains("[ctx-trim] the frozen plan was made on compaction marker"))
        assertTrue("every plan records its marker", replan.contains("trimStateWindow = wm.window\n        trimStateMarkerId = markerId"))
        assertEquals(1, Regex("trimStateMarkerId = ").findAll(vm).count())
    }

    @Test
    fun `a plan made for another window is dropped before anything is measured`() {
        val replan = body("private fun replanTrim(", "private fun summaryWouldFreeLittle(")
        val check = replan.indexOf("!ContextTrimPlanner.planFitsWindow(trimStateWindow, wm.window)")
        val measured = replan.indexOf("val applied = applyFrozenTrim(compacted)")
        assertTrue("checked before the frozen plan is applied and measured", check in 1 until measured)
        val drop = replan.substring(check, measured)
        assertTrue(drop.contains("trimState = null"))
        assertTrue("the collapse unlock goes with it", drop.contains("trimCollapseUnlockedSid = null"))
        assertTrue(drop.contains("trimReplanPending = true"))
        assertTrue(drop.contains("[ctx-trim] the frozen plan was made for a \${trimStateWindow ?: 0} window, now \${wm.window}"))
        assertTrue("every plan records its window", replan.contains("trimStateSid = activeSessionId\n        trimStateWindow = wm.window"))
        assertEquals("the window is recorded only where a plan is made", 1, Regex("trimStateWindow = ").findAll(vm).count())
    }

    /**
     * Field 2026-10-07 15:50: a ratio of 8.0 replayed from rows written by the
     * old estimator turned a 22K request into a measured 229K, the fixed share
     * alone (20,295 x 8) exceeded the 128K window, and every turn stopped as
     * "context full" without sending - so the ratio could never correct.
     */
    @Test
    fun `a wrong ratio cannot wedge the session as context full`() {
        val hard = body("private fun trimmedOverHard(", "private fun calibrateContextSize(")
        assertTrue("stop only when the raw estimate is over too", hard.contains("return calibrated > wm.hard && raw > wm.hard"))
        assertFalse("UNUSABLE alone (a ratio artefact) does not stop", hard.contains("Mode.UNUSABLE) return true"))
        val wm = body("private fun currentWatermarks(", "private fun trimMeasure(")
        assertTrue("the text-only fixed share is not inflated by a history ratio",
            wm.contains("val ratio = contextCalibrationRatio().coerceAtMost(FIXED_RATIO_MAX)"))
        assertTrue(vm.contains("private val FIXED_RATIO_MAX = 1.5"))
        assertTrue("rows carry the estimator version",
            vm.contains("\"estimatorVersion\":\${ContextSizeMeter.ESTIMATOR_VERSION}"))
        assertTrue(ProductionSources.read("data/ContextPolicy.kt")
            .contains("if (o.optInt(\"estimatorVersion\", 1) != ESTIMATOR_VERSION) return null"))
    }

    // ── collapse-first and sticky collapse (field 2026-10-07 18:11-18:26) ──

    private fun turn(prefix: String, iterations: Int, result: Int): List<LLMMessage> =
        listOf(userText("$prefix 的任务").copy(dbMessageId = "u_$prefix")) + (0 until iterations).flatMap { i ->
            val id = "${prefix}_$i"
            listOf(
                LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse(id, "shell_execute", JSONObject().put("command", "ls"))), dbMessageId = "a_$id"),
                LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult(id, "shell_execute", "结".repeat(result))), dbMessageId = "r_$id"),
            )
        }

    @Test
    fun `inside one long user turn a summary frees almost nothing`() {
        // One request driving 40 tool rounds (80 messages, under the 100 cap):
        // the whole turn is the kept slice, the summary only adds to it.
        val history = turn("one", 40, 900)
        val gain = RequestContextBuilder.simulatedSummaryGain(history, null, 3, 3_000)!!
        assertTrue("gain=$gain", gain < 3_000)
    }

    @Test
    fun `older user turns are what a summary pays for`() {
        // Three finished turns with big outputs before the current short one.
        val history = turn("a", 12, 3_000) + turn("b", 12, 3_000) + turn("c", 12, 3_000) + turn("d", 12, 3_000) + turn("now", 3, 200)
        val gain = RequestContextBuilder.simulatedSummaryGain(history, null, 3, 3_000)!!
        assertTrue("turn a (~36K) is summarised away: gain=$gain", gain > 30_000)
    }

    @Test
    fun `no anchor id, no simulation`() {
        assertEquals(null, RequestContextBuilder.simulatedSummaryGain(listOf(userText("hi")), null, 3, 3_000))
    }

    @Test
    fun `collapse is tried before a compaction that could not pay for itself, and never undone later`() {
        val check = body("private suspend fun inLoopContextCheck(", "private fun settleWithoutCompacting(")
        val first = check.indexOf("if (summaryWouldFreeLittle(tokens)) {")
        assertTrue(first > 0 && first < check.indexOf("val ok = awaitCompaction()"))
        assertTrue(check.contains("replanTrim(\"collapse-first\", allowCollapse = true)"))
        assertTrue("compaction still follows when collapse is not enough",
            check.contains("if (m2.measured > 0 && policy.check(m2.measured, window) == ContextPolicy.CheckResult.OK) {"))
        val little = body("private fun summaryWouldFreeLittle(", "private fun trimmedOverHard(")
        assertTrue(little.contains("val little = gain < needed / 2"))
        val replan = body("private fun replanTrim(", "private fun summaryWouldFreeLittle(")
        assertTrue("once allowed in a session, collapse stays allowed",
            replan.contains("val collapse = allowCollapse || tight || trimCollapseUnlockedSid == activeSessionId"))
        assertTrue(replan.contains("if (collapse) trimCollapseUnlockedSid = activeSessionId"))
        assertTrue(replan.contains("allowCollapse = collapse,"))
    }

    @Test
    fun `offloads are written once per tool output`() {
        val off = body("private val trimOffloader = ContextTrimPlanner.Offloader", "/** Watermarks for the live model")
        assertTrue(off.contains("trimOffloadPaths[key] ?: com.openminis.app.data.ContextOffload"))
        assertTrue(off.contains(".also { trimOffloadPaths[key] = it }"))
    }
}
