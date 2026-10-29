package com.openminis.app.ui.chat

import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.ui.chat.ContextTrimPlanner.Mode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-trim-ladder] Watermarks and the trim ladder: every level, the stop
 * rule, freezing, collapse, small windows and the long single-turn agent run.
 *
 * Sizes use CJK characters (1 token each in [ContextSizeMeter]) so the numbers
 * in the assertions can be reasoned about.
 */
class ContextTrimPlannerTest {

    private val fixed = 1_000
    private val measure: (List<LLMMessage>) -> Int = { ContextSizeMeter.estimateTokens(it, includeReasoning = true) + fixed }

    /** Records offloads; returns a guest path like the real one. */
    private class FakeOffloader : ContextTrimPlanner.Offloader {
        val calls = mutableListOf<Pair<String, String>>()
        override fun offload(content: String, toolId: String, toolName: String): String {
            calls += toolId to toolName
            return "/var/minis/offloads/tools/${toolName}_$toolId.txt"
        }
    }

    private fun cjk(n: Int) = "想".repeat(n)

    private fun user(text: String, id: String) =
        LLMMessage(LLMMessage.Role.USER, text, contentParts = listOf(AgentContentPart.Text(text)), dbMessageId = id)

    /** One iteration: assistant (reasoning + one tool call) and its result carrier. */
    private fun iteration(
        i: Int,
        reasoning: Int = 0,
        result: Int = 50,
        arg: String? = null,
        tool: String = "shell_execute",
        image: ByteArray? = null,
    ): List<LLMMessage> {
        val id = "call_$i"
        val input = JSONObject().put("command", "echo $i").put("tool_title", "step $i")
        if (arg != null) input.put("content", arg)
        val asst = LLMMessage(
            LLMMessage.Role.ASSISTANT, "",
            contentParts = listOf(AgentContentPart.ToolUse(id, tool, input)),
            dbMessageId = "a$i",
            reasoningContent = if (reasoning > 0) cjk(reasoning) else null,
        )
        val carrier = LLMMessage(
            LLMMessage.Role.USER, "",
            contentParts = listOf(AgentContentPart.ToolResult(id, tool, "结".repeat(result), imageData = image, imageMimeType = image?.let { "image/png" })),
            dbMessageId = "r$i",
        )
        return listOf(asst, carrier)
    }

    private fun run(n: Int, reasoning: Int = 0, result: Int = 50): List<LLMMessage> =
        listOf(user("请把计划表写完", "u0")) + (0 until n).flatMap { iteration(it, reasoning, result) }

    private fun reasoningOf(list: List<LLMMessage>) =
        list.filter { it.role == LLMMessage.Role.ASSISTANT }.map { it.reasoningContent }

    private fun results(list: List<LLMMessage>) =
        list.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolResult>() }

    private fun uses(list: List<LLMMessage>) =
        list.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolUse>() }

    /** Byte-level view of a history (JSONObject has identity equality). */
    private fun dump(list: List<LLMMessage>) = list.joinToString("\n") { m ->
        "${m.role}|${m.dbMessageId}|${m.content}|${m.reasoningContent}|" + m.contentParts.joinToString(";") { p ->
            when (p) {
                is AgentContentPart.ToolUse -> "U:${p.id}:${p.name}:${p.input}"
                is AgentContentPart.ToolResult -> "R:${p.id}:${p.content}:${p.imageData?.size}"
                is AgentContentPart.Text -> "T:${p.text}"
                else -> p.toString()
            }
        }
    }

    /** Every tool result has its call and vice versa; history starts with a user turn. */
    private fun assertWellFormed(list: List<LLMMessage>) {
        assertEquals(LLMMessage.Role.USER, list.first().role)
        assertEquals(uses(list).map { it.id }.toSet(), results(list).map { it.id }.toSet())
    }

    private fun opts(strip: Boolean = true, floor: Int = 1, recent: Int = 3, collapse: Boolean = false) =
        ContextTrimPlanner.Options(strip, floor, recent, collapse)

    private fun assistantReasoningEmptied(list: List<LLMMessage>) = reasoningOf(list).map { it == "" }

    // ── watermarks ───────────────────────────────────────────────────────

    @Test
    fun `watermarks are based on the usable space, for every window size`() {
        data class Row(val window: Int, val fixed: Int, val hard: Int, val usable: Int, val trigger: Int, val target: Int, val mode: Mode)
        val rows = listOf(
            Row(16_000, 21_000, 11_904, -9_096, 12_814, 16_452, Mode.UNUSABLE),
            Row(16_000, 8_000, 11_904, 3_904, 11_513, 9_952, Mode.TIGHT),
            Row(32_000, 21_000, 27_200, 6_200, 26_580, 24_100, Mode.TIGHT),
            Row(32_000, 8_000, 27_200, 19_200, 25_280, 17_600, Mode.NORMAL),
            Row(64_000, 21_000, 54_400, 33_400, 51_060, 37_700, Mode.NORMAL),
            Row(128_000, 21_000, 111_616, 90_616, 102_554, 66_308, Mode.NORMAL),
            Row(1_000_000, 21_000, 983_616, 962_616, 887_354, 502_308, Mode.NORMAL),
        )
        for (r in rows) {
            val w = ContextTrimPlanner.watermarks(r.window, r.fixed, 16_384)!!
            val label = "window=${r.window} fixed=${r.fixed}"
            assertEquals(label, r.hard, w.hard)
            assertEquals(label, r.usable, w.usable)
            assertEquals(label, r.trigger, w.trigger)
            assertEquals(label, r.target, w.target)
            assertEquals(label, r.mode, w.mode)
            if (r.mode != Mode.UNUSABLE) assertTrue("$label target < trigger < hard", w.target < w.trigger && w.trigger < w.hard)
        }
    }

    @Test
    fun `the output reserve never exceeds the model's own output cap, and never drops below 4K`() {
        assertEquals("small output cap", 4_096, ContextTrimPlanner.watermarks(128_000, 21_000, 4_096)!!.reserve)
        assertEquals("15% of a 64K window", 9_600, ContextTrimPlanner.watermarks(64_000, 21_000, 16_384)!!.reserve)
        assertEquals("4K floor on a small window", 4_096, ContextTrimPlanner.watermarks(16_000, 8_000, 16_384)!!.reserve)
        assertNull("unknown window", ContextTrimPlanner.watermarks(0, 21_000, 16_384))
    }

    // ── iterations ───────────────────────────────────────────────────────

    @Test
    fun `an iteration is an assistant turn plus the carriers of its own results`() {
        val text = LLMMessage(LLMMessage.Role.ASSISTANT, "说明一下进度", dbMessageId = "t1")
        val history = run(2) + text + user("<system-reminder>note</system-reminder>", "u9") + iteration(5)
        val its = ContextTrimPlanner.iterations(history)
        assertEquals(listOf("call_0", "call_1", "msg:t1", "call_5"), its.map { it.key })
        assertEquals(listOf(2), its[0].carrierIdx)
        assertTrue("a text-only reply is its own iteration", its[2].carrierIdx.isEmpty())
        assertFalse(its.any { it.pinned })
    }

    @Test
    fun `a carrier that also holds text (the compaction summary) pins its iteration`() {
        val history = run(2).toMutableList()
        val carrier = history[2]
        history[2] = carrier.copy(contentParts = carrier.contentParts + AgentContentPart.Text("<context-summary>S</context-summary>"))
        assertTrue(ContextTrimPlanner.iterations(history)[0].pinned)
    }

    // ── plan: nothing to do ──────────────────────────────────────────────

    @Test
    fun `under the target nothing changes and the state stays empty`() {
        val history = run(5, reasoning = 100)
        val r = ContextTrimPlanner.plan(history, measure(history) + 1, opts(), measure, FakeOffloader())
        assertTrue(r.state.isEmpty)
        assertEquals(history, r.messages)
        assertTrue(r.steps.isEmpty())
    }

    // ── L1 reasoning ─────────────────────────────────────────────────────

    @Test
    fun `L1 empties reasoning one iteration at a time, oldest first, and stops as soon as it fits`() {
        val history = run(10, reasoning = 5_000)
        // Room for exactly 4 of the 10 reasonings (plus a little slack).
        val target = measure(history) - 6 * 5_000 + 100
        val r = ContextTrimPlanner.plan(history, target, opts(), measure, FakeOffloader())
        assertTrue(r.reachedTarget)
        assertEquals(listOf("L1(n=6/10)"), r.steps.map { it.substringBefore(':') })
        assertEquals("the six oldest emptied, the four newest kept",
            List(6) { true } + List(4) { false }, assistantReasoningEmptied(r.messages))
        assertEquals("field kept, value \"\"", "", reasoningOf(r.messages).first())
        assertEquals("tool parts untouched by L1", results(history), results(r.messages))
        assertNull(r.state.shrinkBefore)
        // Minimal: one fewer emptied would not fit.
        val its = ContextTrimPlanner.iterations(history)
        val fewer = ContextTrimPlanner.apply(history, ContextTrimPlanner.State(reasoningBefore = its[5].key), FakeOffloader())!!
        assertTrue(measure(fewer) > target)
    }

    @Test
    fun `normal mode never empties the newest iteration's reasoning, tight mode may`() {
        val history = run(4, reasoning = 3_000)
        val normal = ContextTrimPlanner.plan(history, fixed + 1_000, opts(), measure, FakeOffloader())
        assertEquals(listOf(true, true, true, false), assistantReasoningEmptied(normal.messages))
        assertEquals("call_3", normal.state.reasoningBefore)
        val tight = ContextTrimPlanner.plan(history, fixed + 1_000, opts(floor = 0), measure, FakeOffloader())
        assertTrue(reasoningOf(tight.messages).all { it == "" })
        assertEquals("covers every iteration that exists now - and no later one", ContextTrimPlanner.THROUGH + "call_3", tight.state.reasoningBefore)
    }

    @Test
    fun `offload also goes one iteration at a time and stops as soon as it fits`() {
        // No reasoning: L2/L3 alone closes the gap; only as many old results as needed.
        val history = run(10, result = 3_000)
        // Each offload frees ~2.9K: two are not enough, three are.
        val target = measure(history) - 6_600
        val r = ContextTrimPlanner.plan(history, target, opts(), measure, FakeOffloader())
        assertTrue(r.reachedTarget)
        assertEquals(listOf("L2L3(n=3/10)"), r.steps.map { it.substringBefore(':') })
        val stubbed = results(r.messages).map { it.content.startsWith(ContextOffload.OFFLOADED_PREFIX) }
        assertEquals("only the three oldest offloaded", List(3) { true } + List(7) { false }, stubbed)
        assertNull("no skeleton needed", r.state.skeletonBefore)
    }

    @Test
    fun `a later level that frees enough gives back what an earlier level took`() {
        // Reasoning is small, results large: L1 at full extent is not enough,
        // L2/L3 closes the gap, and then most reasoning comes back.
        val history = run(10, reasoning = 300, result = 3_000)
        val target = measure(history) - 5 * 2_800
        val r = ContextTrimPlanner.plan(history, target, opts(), measure, FakeOffloader())
        assertTrue(r.reachedTarget)
        val order = r.steps.map { it.substringBefore('(') }
        assertEquals(listOf("L1", "L2L3", "L1-restore"), order)
        assertTrue("reasoning restored", assistantReasoningEmptied(r.messages).count { it } < 9)
    }

    @Test
    fun `shortening never makes an argument or a result longer`() {
        val history = listOf(user("跑命令", "u0")) + (0 until 6).flatMap { iteration(it, result = 320, arg = "x".repeat(250)) }
        val its = ContextTrimPlanner.iterations(history)
        val applied = ContextTrimPlanner.apply(history, ContextTrimPlanner.State(skeletonBefore = its[3].key), FakeOffloader())!!
        for (i in 0 until 3) {
            assertEquals("a 250-char argument is shorter than the notice: kept", "x".repeat(250), uses(applied)[i].input.getString("content"))
            assertFalse(uses(applied)[i].isOffloadedArgument)
            assertTrue(results(applied)[i].content.length <= 320)
        }
        assertTrue(ContextSizeMeter.estimateTokens(applied) <= ContextSizeMeter.estimateTokens(history))
    }

    @Test
    fun `a provider that does not replay reasoning skips L1`() {
        val history = run(6, reasoning = 3_000, result = 3_000)
        val r = ContextTrimPlanner.plan(history, fixed + 5_000, opts(strip = false), measure, FakeOffloader())
        assertTrue(r.steps.none { it.startsWith("L1") })
        assertEquals(reasoningOf(history), reasoningOf(r.messages))
    }

    // ── L2 / L3 shrink ───────────────────────────────────────────────────

    @Test
    fun `L2 offloads large results and L3 large arguments of older iterations only`() {
        val history = listOf(user("写文档", "u0")) +
            (0 until 6).flatMap { iteration(it, result = 3_000, arg = "正文".repeat(1_500), tool = "file_write") }
        val off = FakeOffloader()
        val its = ContextTrimPlanner.iterations(history)
        val applied = ContextTrimPlanner.apply(history, ContextTrimPlanner.State(shrinkBefore = its[3].key), off)!!
        val res = results(applied)
        for (i in 0 until 3) {
            assertTrue("result $i stubbed", res[i].content.startsWith(ContextOffload.OFFLOADED_PREFIX))
            assertTrue(res[i].content.contains("/var/minis/offloads/tools/file_write_call_$i.txt"))
            val arg = uses(applied)[i]
            assertTrue("argument $i is a placeholder the write tools refuse", ContextOffload.isOffloadPlaceholder(arg.input.getString("content")))
            assertTrue(arg.isOffloadedArgument)
        }
        for (i in 3 until 6) {
            assertEquals("protected tail untouched", "结".repeat(3_000), res[i].content)
            assertEquals("正文".repeat(1_500), uses(applied)[i].input.getString("content"))
        }
        assertTrue(off.calls.contains("call_0" to "args-file_write"))
        assertTrue("short values are left alone", uses(applied)[0].input.getString("command") == "echo 0")
        assertWellFormed(applied)
    }

    @Test
    fun `without a disk path the large result is cut, not lost silently`() {
        val history = run(4, result = 3_000)
        val its = ContextTrimPlanner.iterations(history)
        val applied = ContextTrimPlanner.apply(history, ContextTrimPlanner.State(shrinkBefore = its[1].key)) { _, _, _ -> null }!!
        val first = results(applied).first().content
        assertTrue(first.length < 3_000)
        assertTrue(first.contains("chars omitted from context"))
    }

    // ── skeleton ─────────────────────────────────────────────────────────

    @Test
    fun `skeleton keeps the call and one line of the result, with where to find the rest`() {
        val image = ByteArray(10_000) { 1 }
        val history = listOf(user("看图", "u0")) + iteration(0, result = 900, image = image) + iteration(1, result = 20) + iteration(2) + iteration(3)
        val its = ContextTrimPlanner.iterations(history)
        val applied = ContextTrimPlanner.apply(history, ContextTrimPlanner.State(skeletonBefore = its[2].key), FakeOffloader())!!
        val r0 = results(applied)[0]
        assertTrue(r0.content.contains("[shortened for context (image omitted); full output: /var/minis/offloads/tools/shell_execute_call_0.txt]"))
        assertNull("image dropped", r0.imageData)
        assertTrue(r0.content.lines().first().length <= ContextTrimPlanner.SKELETON_CHARS)
        assertEquals("a small result is already a skeleton", "结".repeat(20), results(applied)[1].content)
        assertEquals("the call itself stays", "shell_execute", uses(applied)[0].name)
        assertWellFormed(applied)
    }

    @Test
    fun `skeleton of an offload stub points at the existing file`() {
        val history = run(4, result = 3_000)
        val its = ContextTrimPlanner.iterations(history)
        val off = FakeOffloader()
        val applied = ContextTrimPlanner.apply(history, ContextTrimPlanner.State(shrinkBefore = its[1].key, skeletonBefore = its[1].key), off)!!
        val r0 = results(applied)[0].content
        assertTrue("the stub is already skeleton-sized and keeps its path", r0.startsWith(ContextOffload.OFFLOADED_PREFIX))
        assertTrue(r0.contains("/var/minis/offloads/tools/shell_execute_call_0.txt"))
        assertEquals("offloaded once, not again for the skeleton", 1, off.calls.count { it.first == "call_0" })
        // Skeleton never grows what shrink produced.
        val shrunkOnly = ContextTrimPlanner.apply(history, ContextTrimPlanner.State(shrinkBefore = its[1].key), off)!!
        assertTrue(ContextSizeMeter.estimateTokens(applied) <= ContextSizeMeter.estimateTokens(shrunkOnly))
    }

    // ── L4 collapse ──────────────────────────────────────────────────────

    @Test
    fun `collapse happens only when allowed, oldest first, and no more than needed`() {
        val history = run(20, result = 1_500)
        // Lowest size the levels without collapse reach, minus a bit: only L4 can close the gap.
        val noCollapse = ContextTrimPlanner.plan(history, 0, opts(collapse = false), measure, FakeOffloader())
        val target = noCollapse.after - 200
        val without = ContextTrimPlanner.plan(history, target, opts(collapse = false), measure, FakeOffloader())
        assertTrue(without.steps.none { it.startsWith("L4") })
        assertFalse(without.reachedTarget)
        assertNull(without.state.collapseBefore)

        val with = ContextTrimPlanner.plan(history, target, opts(collapse = true), measure, FakeOffloader())
        assertTrue(with.reachedTarget)
        assertTrue(with.steps.any { it.startsWith("L4") })
        val its = ContextTrimPlanner.iterations(history)
        val cutAt = its.indexOfFirst { it.key == with.state.collapseBefore }
        assertTrue(cutAt > 0)
        // Minimal: collapsing one iteration fewer would not fit.
        val fewer = ContextTrimPlanner.apply(history, with.state.copy(collapseBefore = its[cutAt - 1].key), FakeOffloader())!!
        assertTrue("one fewer collapsed would exceed the target", measure(fewer) > target)
        // The oldest are gone, the newest three intact, the request kept.
        val kept = uses(with.messages).map { it.id }
        assertFalse(kept.contains("call_0"))
        assertTrue(kept.containsAll(listOf("call_17", "call_18", "call_19")))
        assertEquals("结".repeat(1_500), results(with.messages).last().content)
        assertTrue(with.messages.first().content.startsWith("请把计划表写完"))
        assertWellFormed(with.messages)
    }

    @Test
    fun `collapsed steps leave an index on the user's request`() {
        val history = run(6)
        val its = ContextTrimPlanner.iterations(history)
        val applied = ContextTrimPlanner.apply(history, ContextTrimPlanner.State(collapseBefore = its[3].key), FakeOffloader())!!
        val host = applied.first()
        assertEquals("request kept as the host", "u0", host.dbMessageId)
        val index = host.contentParts.filterIsInstance<AgentContentPart.Text>().last().text
        assertTrue(index.startsWith("<system-reminder>"))
        assertTrue(index.contains("3 earlier step(s) of this task were collapsed"))
        assertTrue(index.contains("#1 shell_execute «step 0» → ok"))
        assertTrue(index.contains("Tool calls collapsed: shell_execute 3"))
        assertTrue(index.contains("Do not redo a step listed as ok"))
        assertEquals(listOf("call_3", "call_4", "call_5"), uses(applied).map { it.id })
        assertWellFormed(applied)
    }

    @Test
    fun `the index lists at most 60 lines and counts the rest`() {
        val collapsed = (0 until 200).map { i ->
            val its = iteration(i)
            its[0] to listOf(its[1])
        }
        val text = ContextTrimPlanner.indexText(collapsed)
        assertEquals(ContextTrimPlanner.INDEX_MAX_LINES, text.lines().count { it.startsWith("#") })
        assertTrue(text.contains("(140 older line(s) not listed)"))
        assertTrue(text.contains("Tool calls collapsed: shell_execute 200"))
        assertTrue("newest kept", text.contains("#200 shell_execute"))
    }

    @Test
    fun `a pinned iteration is never collapsed`() {
        val history = run(6).toMutableList()
        history[2] = history[2].copy(contentParts = history[2].contentParts + AgentContentPart.Text("<context-summary>S</context-summary>"))
        val its = ContextTrimPlanner.iterations(history)
        val applied = ContextTrimPlanner.apply(history, ContextTrimPlanner.State(collapseBefore = its[3].key), FakeOffloader())!!
        assertTrue(uses(applied).any { it.id == "call_0" })
        assertTrue(applied.any { m -> m.contentParts.any { it is AgentContentPart.Text && it.text.contains("<context-summary>") } })
        assertWellFormed(applied)
    }

    @Test
    fun `when nothing precedes the collapsed range the index becomes its own user turn`() {
        val history = (0 until 5).flatMap { iteration(it) }
        val its = ContextTrimPlanner.iterations(history)
        val applied = ContextTrimPlanner.apply(history, ContextTrimPlanner.State(collapseBefore = its[2].key), FakeOffloader())!!
        assertEquals(LLMMessage.Role.USER, applied.first().role)
        assertTrue(applied.first().content.contains("earlier step(s) of this task were collapsed"))
    }

    // ── freezing ─────────────────────────────────────────────────────────

    @Test
    fun `a frozen plan re-applied to a grown history keeps the old part byte-identical`() {
        val history = run(12, reasoning = 2_000, result = 2_500)
        val r = ContextTrimPlanner.plan(history, measure(history) / 3, opts(collapse = true), measure, FakeOffloader())
        val grown = history + iteration(12, reasoning = 2_000) + iteration(13, reasoning = 2_000)
        val again = ContextTrimPlanner.apply(grown, r.state, FakeOffloader())!!
        assertEquals("prefix cache keeps hitting", dump(r.messages), dump(again.take(r.messages.size)))
        assertEquals("new turns arrive untouched", cjk(2_000), again.last { it.role == LLMMessage.Role.ASSISTANT }.reasoningContent)
    }

    @Test
    fun `a plan whose cut-off key is gone asks for a re-plan`() {
        val state = ContextTrimPlanner.State(reasoningBefore = "call_missing")
        assertNull(ContextTrimPlanner.apply(run(3), state, FakeOffloader()))
        val h = run(3)
        assertTrue("an empty state is a no-op", h === ContextTrimPlanner.apply(h, ContextTrimPlanner.State(), FakeOffloader()))
    }

    // ── whole ladder ─────────────────────────────────────────────────────

    @Test
    fun `levels run cheapest first`() {
        // Every level has something to take: reasoning (L1), 3K arguments (L3),
        // 1.5K results only a skeleton shortens (SK), and iterations to collapse (L4).
        val history = listOf(user("请把计划表写完", "u0")) +
            (0 until 10).flatMap { iteration(it, reasoning = 4_000, result = 1_500, arg = "正".repeat(3_000)) }
        val floor = ContextTrimPlanner.plan(history, 0, opts(collapse = false), measure, FakeOffloader()).after
        val r = ContextTrimPlanner.plan(history, floor - 50, opts(collapse = true), measure, FakeOffloader())
        val order = r.steps.map { it.substringBefore('(').substringBefore(':') }
        assertEquals(listOf("L1", "L2L3", "SK", "L4"), order.take(4))
        assertTrue(r.reachedTarget)
        assertWellFormed(r.messages)
    }

    @Test
    fun `tight windows keep no old reasoning and only the newest iteration intact`() {
        val history = run(6, reasoning = 1_500, result = 2_500)
        val r = ContextTrimPlanner.plan(
            history, fixed + 4_000,
            ContextTrimPlanner.Options(stripReasoning = true, reasoningFloor = 0, keepRecentIterations = 1, allowCollapse = true),
            measure, FakeOffloader(),
        )
        assertTrue(reasoningOf(r.messages).all { it == "" })
        assertEquals("newest result intact", "结".repeat(2_500), results(r.messages).last().content)
        assertTrue(r.reachedTarget)
        assertWellFormed(r.messages)
    }

    @Test
    fun `when even the protected tail is over the target the plan says so instead of breaking it`() {
        val history = run(3, result = 9_000)
        val r = ContextTrimPlanner.plan(history, fixed + 1_000, opts(collapse = true, recent = 3), measure, FakeOffloader())
        assertFalse(r.reachedTarget)
        assertEquals("nothing protected was touched", results(history), results(r.messages))
    }

    @Test
    fun `one user turn driving 200 tool iterations comes down to the target`() {
        // Field shape: ~15K reasoning and ~3K output per iteration, one "继续".
        val history = run(200, reasoning = 15_000, result = 3_000)
        val target = 66_000
        assertTrue("starts far over the window", measure(history) > 3_000_000)
        val r = ContextTrimPlanner.plan(history, target, opts(collapse = true), measure, FakeOffloader())
        assertTrue("after=${r.after}", r.reachedTarget)
        assertTrue("the user's request is kept", r.messages.first().content.startsWith("请把计划表写完"))
        val kept = uses(r.messages).map { it.id }
        assertTrue("newest three iterations intact", kept.takeLast(3) == listOf("call_197", "call_198", "call_199"))
        assertEquals("the newest step keeps its reasoning", cjk(15_000), r.messages.last { it.role == LLMMessage.Role.ASSISTANT }.reasoningContent)
        // [T-ctx-count-wire-framing] 189 -> 192 with estimator v3 (a049acc01):
        // every iteration now also counts its call id (twice), argument
        // escaping and per-message / per-call framing - measured 7,784 tokens
        // more over this history (39 per iteration) - so three more ~3K
        // outputs go to reach the same target. The planner still picks the
        // smallest prefix that fits (binary search), so "just enough" holds.
        assertEquals("every older reasoning emptied, then just enough old outputs offloaded - no collapse needed",
            listOf("L1(n=199/200)", "L2L3(n=192/200)"), r.steps.map { it.substringBefore(':') })
        assertNull(r.state.collapseBefore)
        val index = r.messages.first().contentParts.filterIsInstance<AgentContentPart.Text>().last().text
        assertTrue(index.lines().count { it.startsWith("#") } <= ContextTrimPlanner.INDEX_MAX_LINES)
        assertWellFormed(r.messages)
    }

    // ── estimator ────────────────────────────────────────────────────────

    @Test
    fun `replayed reasoning is counted only when asked, and only on assistant turns`() {
        val asst = LLMMessage(LLMMessage.Role.ASSISTANT, "ok", reasoningContent = cjk(1_000))
        val userWithReasoning = LLMMessage(LLMMessage.Role.USER, "ok", reasoningContent = cjk(1_000))
        // [T-ctx-count-wire-framing] (a049acc01, iOS e48d29ee9) Echoed reasoning
        // costs its wire tokens (CJK: no escaping, so 1,000) plus the
        // `"reasoning_content":""` field itself - the same expectation iOS's
        // ContextReasoningMeasureTests moved to.
        assertEquals(
            ContextSizeMeter.estimateTokens(asst) + ContextSizeMeter.estimateWireTokens(cjk(1_000)) +
                ContextSizeMeter.REASONING_FIELD_OVERHEAD,
            ContextSizeMeter.estimateTokens(asst, includeReasoning = true),
        )
        assertEquals(1_000, ContextSizeMeter.estimateWireTokens(cjk(1_000)))
        assertEquals(ContextSizeMeter.estimateTokens(userWithReasoning), ContextSizeMeter.estimateTokens(userWithReasoning, includeReasoning = true))
    }

    @Test
    fun `the estimate cache notices emptied reasoning and shortened arguments`() {
        val asst = iteration(0, reasoning = 2_000, arg = "正文".repeat(1_500))[0]
        val full = ContextSizeMeter.estimateTokens(listOf(asst), includeReasoning = true)
        val emptied = ContextSizeMeter.estimateTokens(listOf(asst.copy(reasoningContent = "")), includeReasoning = true)
        assertTrue(full - emptied >= 2_000)
        val use = asst.contentParts.first() as AgentContentPart.ToolUse
        val shortArg = asst.copy(contentParts = listOf(use.copy(input = JSONObject(use.input.toString()).put("content", "x"))))
        assertTrue("same key count, shorter value", ContextSizeMeter.estimateTokens(listOf(shortArg)) < ContextSizeMeter.estimateTokens(listOf(asst)))
    }

    // ── [T-ctx-trim-trigger-margin] ─────────────────────────────────────

    @Test
    fun `a capped group's compaction line equals the hard watermark, so the trigger keeps its own margin`() {
        // 128K cap, ~21K fixed, an output cap of 32K: the reserve is 15% of the window.
        val wm = ContextTrimPlanner.watermarks(128_000, 21_000, 32_000)!!
        val line = ContextPolicy.forUserCap(128_000).compactThreshold
        assertEquals(108_800, line)
        assertEquals("the line IS the hard watermark", line, wm.hard)
        val trigger = ContextTrimPlanner.trigger(line, wm)
        assertEquals(wm.trigger, trigger)
        assertEquals(21_000 + ((108_800 - 21_000) * 0.9).toInt(), trigger)
        assertTrue(trigger < wm.hard)
    }

    @Test
    fun `on a native 1M window the compaction line is above the hard watermark and no longer decides`() {
        val wm = ContextTrimPlanner.watermarks(1_000_000, 21_000, 128_000)!!
        val line = ContextPolicy.forContextWindow(1_000_000).compactThreshold
        assertEquals(980_000, line)
        assertTrue("the line alone is past hard", line > wm.hard)
        assertEquals(wm.trigger, ContextTrimPlanner.trigger(line, wm))
    }

    @Test
    fun `a lower compaction line still wins, and no line means the watermark`() {
        val wm = ContextTrimPlanner.watermarks(128_000, 21_000, 16_384)!!
        assertEquals(90_000, ContextTrimPlanner.trigger(90_000, wm))
        assertEquals(wm.trigger, ContextTrimPlanner.trigger(null, wm))
        assertEquals("0 = this tier has no auto-compaction", wm.trigger, ContextTrimPlanner.trigger(0, wm))
    }

    @Test
    fun `the trigger is below the hard watermark for every tier, cap and output cap`() {
        for (window in listOf(32_000, 48_000, 64_000, 100_000, 128_000, 200_000, 400_000, 1_000_000)) {
            for (maxOut in listOf(4_096, 16_384, 32_000, 128_000)) {
                for (fixed in listOf(8_000, 21_000)) {
                    val wm = ContextTrimPlanner.watermarks(window, fixed, maxOut)!!
                    if (wm.mode == Mode.UNUSABLE) continue
                    for (policy in listOf(ContextPolicy.forUserCap(window), ContextPolicy.forContextWindow(window))) {
                        val trigger = ContextTrimPlanner.trigger(policy.compactThreshold, wm)
                        assertTrue("window=$window maxOut=$maxOut fixed=$fixed: $trigger < ${wm.hard}", trigger < wm.hard)
                        assertTrue(trigger <= wm.trigger)
                    }
                }
            }
        }
    }

    // ── [T-ctx-trim-escalate] ───────────────────────────────────────────

    @Test
    fun `a plan that ends above the midpoint of target and trigger escalates, a small miss does not`() {
        // iOS on-device: target 63,279, trigger about 99,877 -> midpoint 81,578.
        val target = 63_279
        val trigger = 99_877
        assertTrue(ContextTrimPlanner.shouldEscalate(83_919, target, trigger))
        assertTrue(ContextTrimPlanner.shouldEscalate(89_148, target, trigger))
        assertTrue(ContextTrimPlanner.shouldEscalate(91_946, target, trigger))
        assertFalse("2.9K over the target is a small miss", ContextTrimPlanner.shouldEscalate(target + 2_900, target, trigger))
        assertFalse("reached", ContextTrimPlanner.shouldEscalate(target, target, trigger))
        assertFalse("exactly the midpoint", ContextTrimPlanner.shouldEscalate(target + (trigger - target) / 2, target, trigger))
        assertTrue(ContextTrimPlanner.shouldEscalate(target + (trigger - target) / 2 + 1, target, trigger))
    }

    @Test
    fun `a history that only collapsing can shrink - the no-collapse plan misses by a lot, the collapsing one reaches the target`() {
        // Nothing for L1-SK to take: no reasoning, small results.
        val history = run(400, reasoning = 0, result = 40)
        val before = measure(history)
        val target = before / 2
        val trigger = before - 1
        fun plan(collapse: Boolean) = ContextTrimPlanner.plan(
            history, target,
            ContextTrimPlanner.Options(stripReasoning = true, reasoningFloor = 1, keepRecentIterations = 3, allowCollapse = collapse),
            measure, FakeOffloader(),
        )
        val without = plan(collapse = false)
        assertFalse(without.reachedTarget)
        assertTrue("escalates", ContextTrimPlanner.shouldEscalate(without.after, target, trigger))
        val with = plan(collapse = true)
        assertTrue(with.reachedTarget)
        assertFalse(ContextTrimPlanner.shouldEscalate(with.after, target, trigger))
    }

    // ── [T-ctx-trim-plan-marker] ────────────────────────────────────────

    @Test
    fun `a plan made on another compaction marker does not fit`() {
        assertTrue("no compaction then, none now", ContextTrimPlanner.planFitsMarker(null, null))
        assertTrue(ContextTrimPlanner.planFitsMarker("m1", "m1"))
        assertFalse("a compaction happened since", ContextTrimPlanner.planFitsMarker(null, "m1"))
        assertFalse("a second compaction", ContextTrimPlanner.planFitsMarker("m1", "m2"))
        assertFalse("the compaction was reverted", ContextTrimPlanner.planFitsMarker("m1", null))
    }

    @Test
    fun `the cut-off keys alone do not notice a compaction - the old plan still applies to the kept slice`() {
        val history = run(120, reasoning = 600, result = 300)
        val target = measure(history) / 2
        val plan = ContextTrimPlanner.plan(
            history, target,
            ContextTrimPlanner.Options(stripReasoning = true, reasoningFloor = 1, keepRecentIterations = 3, allowCollapse = false),
            measure, FakeOffloader(),
        )
        assertFalse(plan.state.isEmpty)
        // What a compaction keeps: the request and the newest iterations.
        val kept = listOf(user("请把计划表写完", "u0")) + (100 until 120).flatMap { iteration(it, 600, 300) }
        val reapplied = ContextTrimPlanner.apply(kept, plan.state, FakeOffloader())
        if (reapplied != null) {
            // The keys survived, so apply() raised no flag: the kept slice,
            // already far under the target, is still being trimmed.
            assertTrue(measure(kept) < target)
            assertTrue("old extents still trim the small history", measure(reapplied) < measure(kept))
        }
        // Either way the marker comparison is what asks for the re-plan.
        assertFalse(ContextTrimPlanner.planFitsMarker(null, "m1"))
    }

    /**
     * [T-ctx-trim-plan-window] The iOS on-device case, in small: a plan sized
     * for a 128K window, then the window becomes 1M.
     */
    @Test
    fun `a plan made for another window does not fit, in either direction`() {
        assertTrue(ContextTrimPlanner.planFitsWindow(128_000, 128_000))
        assertFalse("the window grew", ContextTrimPlanner.planFitsWindow(128_000, 1_000_000))
        assertFalse("the window shrank", ContextTrimPlanner.planFitsWindow(1_000_000, 128_000))
        assertFalse("a plan with no recorded window is not trusted", ContextTrimPlanner.planFitsWindow(null, 128_000))
    }

    @Test
    fun `left alone, a 128K plan would stay in force under a 1M window - nothing there re-plans`() {
        val history = run(300, reasoning = 600, result = 300)
        val small = ContextTrimPlanner.watermarks(128_000, fixed, 16_384)!!
        val large = ContextTrimPlanner.watermarks(1_000_000, fixed, 16_384)!!
        val untrimmed = measure(history)
        assertTrue("over the 128K trigger: a plan is made", untrimmed > small.trigger)
        val plan = ContextTrimPlanner.plan(
            history, small.target,
            ContextTrimPlanner.Options(stripReasoning = true, reasoningFloor = 1, keepRecentIterations = 3, allowCollapse = true),
            measure, FakeOffloader(),
        )
        assertTrue(plan.reachedTarget)
        assertFalse(plan.state.isEmpty)
        val applied = measure(ContextTrimPlanner.apply(history, plan.state, FakeOffloader())!!)
        // The re-plan rule is "only above the trigger": under 1M the trimmed
        // request is nowhere near it, so the old plan would never be replaced...
        assertTrue(applied <= small.target && applied < large.trigger)
        // ...although under 1M this history needs no trim at all.
        assertTrue(untrimmed < large.target)
        assertFalse(ContextTrimPlanner.planFitsWindow(small.window, large.window))
    }

    @Test
    fun `calibration ceiling is 8 now that reasoning is counted`() {
        assertEquals(8.0, ContextSizeMeter.CALIBRATION_MAX, 0.0)
        assertNotNull(ContextSizeMeter.calibrationRatio(341_873, 48_958))
        assertEquals(341_873.0 / 48_958, ContextSizeMeter.calibrationRatio(341_873, 48_958)!!, 1e-9)
    }
}
