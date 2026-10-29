package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-port-fold-tools] Port of iOS ToolFoldPlannerTests: which runs of tool
 * calls the chat folds into one "Ran N tools" row, and when.
 *
 * Block notation: T = succeeded tool, F = failed, C = cancelled, R = unfinished
 * (running / streaming), K = thinking, t = non-empty multi-line text, o =
 * non-empty ONE-line text, e = empty text, B = barrier (info / sub agent /
 * user_ask). Output: one "lo-hi:tools/failed/cancelled" per group, or "none".
 * Like the list, [show] runs markingNotes first.
 */
class ToolFoldPlannerTest {

    private fun parse(s: String): List<ToolFoldPlanner.Input> = s.map { c ->
        when (c) {
            'T' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.SUCCEEDED)
            'F' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.FAILED)
            'C' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.CANCELLED)
            'R' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.UNFINISHED)
            'K' -> ToolFoldPlanner.Input.Thinking
            't', 'o' -> ToolFoldPlanner.Input.Text(isEmpty = false)
            'e' -> ToolFoldPlanner.Input.Text(isEmpty = true)
            else -> ToolFoldPlanner.Input.Barrier
        }
    }

    private fun show(s: String, enabled: Boolean = true, horizon: Int = s.length): String {
        val chars = s.take(horizon)
        val inputs = ToolFoldPlanner.markingNotes(parse(chars)) { chars[it] == 'o' }
        val g = ToolFoldPlanner.plan(inputs, enabled)
        if (g.isEmpty()) return "none"
        return g.joinToString(",") { "${it.range.first}-${it.range.last}:${it.toolCount}/${it.failedCount}/${it.cancelledCount}" }
    }

    private val cases = mapOf(
        "three" to ("TTTt" to "0-2:3/0/0"),
        "two" to ("TTt" to "0-1:2/0/0"),
        "one" to ("Tt" to "none"),
        "oneThink" to ("TKKt" to "none"),
        "leadText" to ("tTTTTt" to "1-4:4/0/0"),
        "interleaved" to ("KTKTTt" to "0-4:3/0/0"),
        "thinkOnly" to ("TKKTt" to "0-3:2/0/0"),
        "thinkMid" to ("TKTKTt" to "0-4:3/0/0"),
        "emptyMid" to ("TTeTt" to "0-3:3/0/0"),
        "infoMid" to ("TTBTTt" to "3-4:2/0/0"),
        "delegateSplit" to ("TTTBTTTt" to "4-6:3/0/0"),
        "noReply" to ("tTTTT" to "none"),
        "noReplyEmpty" to ("TTTe" to "none"),
        "running" to ("TTTRt" to "none"),
        "runningFirst" to ("RTTTt" to "1-3:3/0/0"),
        "failCancel" to ("TFCTt" to "0-3:4/1/1"),
        "twoRuns" to ("TTTtTTTTt" to "0-2:3/0/0,4-7:4/0/0"),
        "trailingThink" to ("TTTKt" to "0-2:3/0/0"),
        "allThinking" to ("KKKt" to "none"),
        "noteExample" to ("ToTo" to "0-2:2/0/0"),
        "noteThenReply" to ("ToTt" to "0-2:2/0/0"),
        "notesLong" to ("ToToTTt" to "0-5:4/0/0"),
        "multiLineMid" to ("TTtTTt" to "0-1:2/0/0,3-4:2/0/0"),
        "noteLive" to ("TToR" to "0-2:2/0/0"),
        "noteNoReply" to ("TToTT" to "0-2:2/0/0"),
        "noteOneTool" to ("ToR" to "none"),
        "noteLead" to ("oTTt" to "1-2:2/0/0"),
        "noteBeforeBarrier" to ("TToBt" to "0-1:2/0/0"),
        "noteThink" to ("TKoTt" to "0-3:2/0/0"),
        "noteAfterSoftEnd" to ("TToTTR" to "0-2:2/0/0"),
        "leadThink" to ("KTTt" to "0-2:2/0/0"),
        "leadThinks" to ("KKeTTt" to "0-4:2/0/0"),
        "leadThinkAfterText" to ("tKTTt" to "1-3:2/0/0"),
        "leadThinkSecondRun" to ("TTtKTTt" to "0-1:2/0/0,3-5:2/0/0"),
        "leadThinkOneTool" to ("KTt" to "none"),
        "leadThinkNote" to ("KoTTt" to "2-3:2/0/0"),
        "leadThinkBarrier" to ("KBTTt" to "2-3:2/0/0"),
        "leadThinkRunning" to ("KRTTt" to "2-3:2/0/0"),
    )

    @Test
    fun `every iOS planner case gives the same groups`() {
        for ((name, c) in cases) assertEquals(name, c.second, show(c.first))
    }

    @Test
    fun `threshold, setting off`() {
        assertEquals(2, ToolFoldPlanner.MINIMUM_TOOL_COUNT)
        assertEquals("none", show("TTTTt", enabled = false))
    }

    @Test
    fun `single line, line count and separator match iOS`() {
        assertTrue(ToolFoldPlanner.isSingleLine("  Now checking the logs.  \n"))
        assertFalse(ToolFoldPlanner.isSingleLine("line one\nline two"))
        assertTrue(ToolFoldPlanner.isSingleLine("\n\nOne line after blank lines\n\n"))
        assertTrue(ToolFoldPlanner.isSingleLine(""))
        assertEquals(0, ToolFoldPlanner.nonEmptyLineCount(""))
        assertEquals(1, ToolFoldPlanner.nonEmptyLineCount("Checking the logs now."))
        assertEquals(1, ToolFoldPlanner.nonEmptyLineCount("\n  \nOnly one line\n\n   \n"))
        assertEquals(2, ToolFoldPlanner.nonEmptyLineCount("First line\nSecond line"))
        assertEquals(2, ToolFoldPlanner.nonEmptyLineCount("First\n\n\n   \nSecond\n"))
        assertEquals(2, ToolFoldPlanner.nonEmptyLineCount("First\r\nSecond"))
        assertEquals(3, ToolFoldPlanner.nonEmptyLineCount("- a\n- b\n- c"))
        assertEquals(2, ToolFoldPlanner.SEPARATOR_MINIMUM_LINES)
        assertFalse(ToolFoldPlanner.isFoldSeparator(""))
        assertFalse(ToolFoldPlanner.isFoldSeparator("Now let me check the logs."))
        assertFalse(ToolFoldPlanner.isFoldSeparator("One line\n\n   \n"))
        assertTrue(ToolFoldPlanner.isFoldSeparator("Found it.\nThe config is wrong."))
        assertTrue(ToolFoldPlanner.isFoldSeparator(listOf("First block", "Second block").joinToString("\n")))
    }

    @Test
    fun `fold horizon and the step-by-step timing match iOS`() {
        val ids = listOf("a", "b", "c", "d", "e")
        assertEquals(0, ToolFoldPlanner.foldHorizon(ids, emptySet()))
        assertEquals(3, ToolFoldPlanner.foldHorizon(ids, setOf("c")))
        assertEquals(4, ToolFoldPlanner.foldHorizon(ids, setOf("b", "d")))
        assertEquals(0, ToolFoldPlanner.foldHorizon(ids, setOf("x")))
        assertEquals(2, ToolFoldPlanner.foldHorizon(ids, setOf("b", "x")))
        assertEquals("none", show("TTt", horizon = 0))
        assertEquals("none", show("TToR", horizon = 0))
        assertEquals("0-1:2/0/0", show("TTtR", horizon = 4))
        assertEquals("0-1:2/0/0", show("TTtTT", horizon = 4))
        assertEquals("0-1:2/0/0", show("TTtTTt", horizon = 4))
        assertEquals("0-1:2/0/0,3-4:2/0/0", show("TTtTTt"))
        assertEquals("none", show("TToTTo", horizon = 0))
        assertEquals("0-4:4/0/0", show("TToTTo"))
    }

    @Test
    fun `browse hold matches iOS, row lookup only when needed`() {
        var probed = 0
        fun hb(b: Boolean, f: Boolean, r: Boolean, shown: Boolean) =
            ToolFoldPlanner.holdsBack(b, f, r) { probed++; shown }
        assertFalse(hb(false, false, true, true))
        assertFalse(hb(true, true, true, true))
        assertTrue(hb(true, false, true, false))
        assertTrue(hb(true, false, false, true))
        assertFalse(hb(true, false, false, false))
        probed = 0
        hb(false, false, false, true); hb(true, true, false, true); hb(true, false, true, true)
        assertEquals(0, probed)
    }

    // --- AssistantBlock adapters and the live horizon ---------------------------

    private var n = 0
    private fun tool(status: ToolBlockStatus = ToolBlockStatus.SUCCESS, name: String = "shell_execute", ms: Long = 0) =
        AssistantBlock(id = "tu${n++}", kind = "tool_use", toolStatus = status, toolName = name, durationMs = ms)
    private fun text(s: String) = AssistantBlock(id = "x${n++}", kind = "text", content = s)
    private fun think() = AssistantBlock(id = "k${n++}", kind = "thinking", content = "hmm")

    @Test
    fun `block inputs - barriers, statuses and notes`() {
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(AssistantBlock(id = "i", kind = "info")))
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(tool(name = "subagent_task")))
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(tool(name = "delegate_task")))
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(tool(name = "user_ask")))
        assertEquals(ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.FAILED), ToolFoldPlanner.input(tool(ToolBlockStatus.TIMEOUT)))
        for (s in listOf(ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING, null)) {
            assertEquals(ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.UNFINISHED), ToolFoldPlanner.input(tool(s ?: ToolBlockStatus.RUNNING).copy(toolStatus = s)))
        }
        assertEquals(ToolFoldPlanner.Input.Text(isEmpty = true), ToolFoldPlanner.input(text("  \n ")))
        val blocks = listOf(tool(), text("Now the logs."), tool(), text("Done.\nAll good."))
        assertEquals(ToolFoldPlanner.Input.Note, ToolFoldPlanner.inputs(blocks)[1])
        assertEquals(ToolFoldPlanner.Input.Text(isEmpty = false), ToolFoldPlanner.inputs(blocks)[3])
    }

    @Test
    fun `folded runs carry the first tool's id as key, members and summed time`() {
        val k = think()
        val a = tool(ms = 1200)
        val b = tool(ToolBlockStatus.FAILED, ms = 800)
        val reply = text("Done.\nAll good.")
        val runs = ToolFoldPlanner.foldedRuns(listOf(k, a, b, reply))
        assertEquals(1, runs.size)
        val r = runs[0]
        assertEquals(a.id, r.key)
        assertEquals(k.id, r.firstBlockId)
        assertEquals(listOf(k.id, a.id, b.id), r.memberIds)
        assertEquals(2000L, r.durationMs)
        assertEquals(1, r.group.failedCount)
        // No recorded time at all: no duration.
        assertEquals(null, ToolFoldPlanner.foldedRuns(listOf(tool(), tool(), reply)).single().durationMs)
    }

    @Test
    fun `live horizon - nothing folds while a reply streams or tools run, a long request end folds`() {
        val t1 = tool(); val t2 = tool()
        // Request 2 is a long reply that is still streaming (no tools yet).
        val long = text("Found it.\nThe config is wrong.")
        assertEquals(0, ToolFoldPlanner.liveHorizon(listOf(t1, t2, long)))
        // It called a tool whose arguments are still streaming: not ended.
        val r = tool(ToolBlockStatus.STREAMING)
        assertEquals(0, ToolFoldPlanner.liveHorizon(listOf(t1, t2, long, r)))
        // Its arguments are complete (the tool runs): that request ended.
        val running = r.copy(toolStatus = ToolBlockStatus.RUNNING)
        assertEquals(4, ToolFoldPlanner.liveHorizon(listOf(t1, t2, long, running)))
        // A one-line remark is no separator.
        assertEquals(0, ToolFoldPlanner.liveHorizon(listOf(t1, t2, text("Next."), running)))
        // A later request started: the earlier long request is ended for sure.
        val t3 = running.copy(toolStatus = ToolBlockStatus.SUCCESS)
        val next = text("Checking")
        assertEquals(4, ToolFoldPlanner.liveHorizon(listOf(t1, t2, long, t3, next)))
        // A running message is planned up to its horizon; an ended one whole.
        val msg = ChatMessage(id = "m", role = "assistant", content = "", isStreaming = true, toolBlocks = listOf(t1, t2, long, t3, tool(), text("Done.\nOk.")))
        assertEquals(1, ToolFoldPlanner.foldedRuns(msg).size)
        assertEquals(2, ToolFoldPlanner.foldedRuns(msg.copy(isStreaming = false)).size)
    }

    // --- Rows ------------------------------------------------------------------

    private fun rowsFor(m: ChatMessage) = buildFlatChatItems(listOf(m))

    @Test
    fun `a folded run becomes one group row where its first member was, members back when expanded`() {
        val k = think(); val a = tool(ms = 1000); val b = tool(ms = 2000); val reply = text("Done.\nAll good.")
        val m = ChatMessage(id = "m", role = "assistant", content = "", toolBlocks = listOf(k, a, b, reply))
        val rows = rowsFor(m)
        val runs = mapOf("m" to ToolFoldPlanner.foldedRuns(m.toolBlocks))
        val folded = applyToolFolds(rows, runs, emptySet())
        val keys = folded.map { it.key }
        assertTrue(keys.toString(), "toolgroup:m:${a.id}" in keys)
        assertFalse("thinking:m:${k.id}" in keys || "tool:m:${a.id}" in keys || "tool:m:${b.id}" in keys)
        assertTrue(keys.any { it.startsWith("mdblock:m:${reply.id}") })
        assertEquals(keys.indexOf("toolgroup:m:${a.id}"), rows.indexOfFirst { it.key == "thinking:m:${k.id}" })
        val g = folded.filterIsInstance<FlatChatItem.AssistantToolGroup>().single()
        assertEquals(2, g.toolCount); assertEquals(3000L, g.durationMs); assertFalse(g.expanded)

        val open = applyToolFolds(rows, runs, setOf(a.id)).map { it.key }
        val gi = open.indexOf("toolgroup:m:${a.id}")
        assertEquals(listOf("thinking:m:${k.id}", "tool:m:${a.id}", "tool:m:${b.id}"), open.subList(gi + 1, gi + 4))
        // Nothing to fold: the same list instance.
        assertSame(rows, applyToolFolds(rows, emptyMap(), emptySet()))
    }

    @Test
    fun `controller - off passes through, toggles, and holds a new fold while browsing`() {
        val a = tool(); val b = tool(); val reply = text("Done.\nAll good.")
        val m = ChatMessage(id = "m", role = "assistant", content = "", toolBlocks = listOf(a, b, reply))
        val rows = rowsFor(m)
        val c = ToolFoldController()
        assertSame(rows, c.fold(rows, listOf(m), emptyList()))
        val shownUnfolded = ToolFoldController().apply { enabled = true; browsing = true }
        // A fresh controller has shown nothing: a just-opened chat folds at once.
        assertTrue(shownUnfolded.fold(rows, listOf(m), emptyList()).any { it is FlatChatItem.AssistantToolGroup })

        val d = ToolFoldController().apply { enabled = true }
        // While the task runs its rows are shown unfolded (horizon 0)...
        val live = m.copy(isStreaming = true, toolBlocks = listOf(a, b, text("Done.\nAll")))
        assertFalse(d.fold(rowsFor(live), listOf(live), listOf(live)).any { it is FlatChatItem.AssistantToolGroup })
        // ...the task ends while the user is scrolled up: held, still rows.
        d.browsing = true
        assertFalse(d.fold(rows, listOf(m), emptyList()).any { it is FlatChatItem.AssistantToolGroup })
        // Back at the bottom: it folds, and stays folded when they scroll up again.
        d.browsing = false
        assertTrue(d.fold(rows, listOf(m), emptyList()).any { it is FlatChatItem.AssistantToolGroup })
        d.browsing = true
        assertTrue(d.fold(rows, listOf(m), emptyList()).any { it is FlatChatItem.AssistantToolGroup })
        // A tap opens it in place and ticks `changes`.
        val before = d.changes.value
        d.toggle(a.id)
        assertTrue(d.changes.value > before)
        assertTrue(d.fold(rows, listOf(m), emptyList()).any { it.key == "tool:m:${a.id}" })
    }

    @Test
    fun `a running task folds its finished requests - the live copy is checked, not the empty stored one`() {
        // [T-fold-live-turn] During a turn the stored message has no tool blocks
        // (they stream into the overlay); the fold used to test the stored copy's
        // block count first, so a running task never folded until it ended.
        val a = tool(); val b = tool()
        val sep = text((1..ToolFoldPlanner.SEPARATOR_MINIMUM_LINES + 1).joinToString("\n") { "line $it" })
        val c = tool()
        val stored = ChatMessage(id = "m", role = "assistant", content = "", toolBlocks = emptyList(), isStreaming = true)
        val live = stored.copy(toolBlocks = listOf(a, b, sep, c))
        val d = ToolFoldController().apply { enabled = true }
        val out = d.fold(rowsFor(live), listOf(stored), listOf(live))
        assertTrue(out.map { it.key }.toString(), out.any { it is FlatChatItem.AssistantToolGroup && it.runKey == a.id })
    }

    // --- Wiring ----------------------------------------------------------------

    @Test
    fun `the chat list folds its rows, renders and toggles the group row`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("flatItems = toolFold.fold("))
        assertTrue(screen.contains("toolFold.changes,"))
        // [T-user-ask-resolved-expand] The tap also anchors the row's top first (ToggleAnchorTest).
        val tap = screen.substringAfter("is FlatChatItem.AssistantToolGroup -> ToolGroupRow(item) {").substringBefore("\n                            }")
        assertTrue(tap.contains("toolFold.toggle(item.runKey)"))
        assertTrue(screen.contains("snapshotFlow { userScrolledAway }.collect { toolFold.browsing = it }"))
        assertTrue(screen.contains("com.openminis.app.ui.settings.KEY_FOLD_TOOLS, com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT,"))
        assertTrue(screen.contains("sp.getBoolean(key, com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT)"))
        assertFalse(screen.contains("KEY_FOLD_TOOLS, false"))
    }

    @Test
    fun `the setting is on by default, in Appearance and the config registry`() {
        // [PAR-261022-05] Every read site goes through one default, so an
        // untouched install folds while a stored false is kept.
        assertTrue(com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT)
        val app = ProductionSources.read("ui/settings/AppearanceScreen.kt")
        assertTrue(app.contains("const val KEY_FOLD_TOOLS = \"fold_consecutive_tools\""))
        assertTrue(app.contains("mutableStateOf(prefs.getBoolean(KEY_FOLD_TOOLS, FOLD_TOOLS_DEFAULT))"))
        val reg = ProductionSources.read("config/ConfigBuiltins.kt")
        val entry = reg.substringAfter("path = \"chat.foldTools\"").substringBefore(")")
        assertTrue(entry.contains("key = com.openminis.app.ui.settings.KEY_FOLD_TOOLS"))
        assertTrue(entry.contains("defaultValue = com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT"))
    }

    @Test
    fun `every locale has the fold strings`() {
        val res = File(ProductionSources.mainRoot()!!, "../../../../res")
        val locales = listOf("values", "values-de", "values-es", "values-fil", "values-fr", "values-hr", "values-in", "values-ja",
            "values-ko", "values-ms", "values-pl", "values-pt-rBR", "values-ro", "values-ru", "values-th", "values-tr", "values-zh", "values-zh-rTW")
        val keys = listOf("tool_fold_setting_title", "tool_fold_section", "tool_fold_setting_footer", "tool_fold_ran_n_tools",
            "tool_fold_n_failed", "tool_fold_n_cancelled", "tool_fold_expand_hint", "tool_fold_collapse_hint")
        for (l in locales) {
            val f = File(res, "$l/strings_tool_fold.xml")
            assertTrue("missing ${f.path}", f.isFile)
            val text = f.readText()
            for (k in keys) assertTrue("$l lacks $k", text.contains("name=\"$k\""))
            assertTrue("$l count placeholder", text.contains("%1\$d"))
        }
        assertFalse(File(res, "values/strings_tool_fold.xml").readText().lowercase().contains("default"))
    }
}
