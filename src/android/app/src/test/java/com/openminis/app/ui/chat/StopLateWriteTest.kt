package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.ui.chat.InFlightToolHeal.StopAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stop-late-write] After Stop, a shell line that arrives late must
 * not bring a RUNNING pill back, a stuck pill must heal, and a tool's own Stop
 * must never stop a different, live reply (field report 2026-10-02).
 */
class StopLateWriteTest {

    private fun tool(id: String, s: ToolBlockStatus?, name: String = "shell_execute") =
        AssistantBlock(id = id, kind = "tool_use", toolStatus = s, toolName = name)

    private fun reply(id: String, vararg blocks: AssistantBlock) =
        ChatMessage(id = id, role = "assistant", content = "", toolBlocks = blocks.toList())

    // ── The turn token ───────────────────────────────────────────────────

    @Test fun `a line after cancel is not the live turn's, and creates no overlay entry`() {
        val g = LiveTurnGuard()
        val gen = g.begin()
        g.markAssistant(gen, "a1")
        assertTrue(g.isLive(gen))
        assertTrue(g.acceptsStreamingWrite("a1"))

        assertEquals("a1", g.cancel())
        // What the lineCallback and updateAssistantMessage ask, in that order.
        assertFalse("the reader drops the line", g.isLive(gen))
        assertFalse("the overlay refuses the write", g.acceptsStreamingWrite("a1"))
        assertNull(g.liveAssistantId())

        // Simulate the overlay exactly as updateAssistantMessage gates it.
        val overlay = mutableMapOf<String, StreamingDelta>()
        fun write(id: String, blocks: List<AssistantBlock>) {
            if (!g.acceptsStreamingWrite(id)) return
            overlay[id] = StreamingDelta("", blocks, false)
        }
        write("a1", listOf(tool("t1", ToolBlockStatus.RUNNING)))
        assertTrue("no entry for a stopped turn", overlay.isEmpty())
    }

    @Test fun `a resumed turn is live and the stopped one's end does not clear it`() {
        val g = LiveTurnGuard()
        val old = g.begin(); g.markAssistant(old, "a1")
        g.cancel()
        val next = g.begin(); g.markAssistant(next, "a2")
        g.end(old)   // the cancelled loop unwinds after the resume started
        assertTrue(g.isLive(next))
        assertTrue(g.acceptsStreamingWrite("a2"))
        assertFalse("the old bubble stays closed", g.acceptsStreamingWrite("a1"))
        g.end(next)
        assertFalse(g.acceptsStreamingWrite("a2"))
    }

    @Test fun `a sealed bubble stops taking overlay writes once the turn moves on`() {
        val g = LiveTurnGuard()
        val gen = g.begin(); g.markAssistant(gen, "a1")
        g.markAssistant(gen, "a2")
        assertFalse(g.acceptsStreamingWrite("a1"))
        assertTrue(g.acceptsStreamingWrite("a2"))
        g.markAssistant(gen + 1, "zz")   // a stale loop cannot claim the bubble
        assertTrue(g.acceptsStreamingWrite("a2"))
    }

    // ── Overlay folding never undoes a finished status ──────────────────

    @Test fun `a flush keeps a finished canonical status over a stale RUNNING`() {
        val canonical = listOf(tool("t1", ToolBlockStatus.CANCELLED), tool("t2", ToolBlockStatus.SUCCESS))
        val overlay = listOf(tool("t1", ToolBlockStatus.RUNNING), tool("t2", ToolBlockStatus.SUCCESS), tool("t3", ToolBlockStatus.RUNNING))
        val merged = InFlightToolHeal.mergeOverlayBlocks(canonical, overlay)
        assertEquals(ToolBlockStatus.CANCELLED, merged[0].toolStatus)
        assertEquals(ToolBlockStatus.SUCCESS, merged[1].toolStatus)
        assertEquals("a block only the overlay knows is passed through", ToolBlockStatus.RUNNING, merged[2].toolStatus)
        // A live turn's canonical bubble holds no finished copy: unchanged.
        val live = listOf(tool("t9", ToolBlockStatus.RUNNING))
        assertSame(live, InFlightToolHeal.mergeOverlayBlocks(emptyList(), live))
    }

    // ── Healing ──────────────────────────────────────────────────────────

    @Test fun `the cancel cleanup heals a RUNNING block in a message that is not the last`() {
        val stuck = reply("a1", tool("t1", ToolBlockStatus.RUNNING), tool("t0", ToolBlockStatus.SUCCESS))
        val last = reply("a2", tool("t2", ToolBlockStatus.RUNNING))
        val user = ChatMessage(id = "u1", role = "user", content = "hi")
        val healed = InFlightToolHeal.sweep(listOf(stuck, user, last), keep = setOf("a2"))
        assertEquals(1, healed.blockCount)
        assertEquals(listOf(Triple("a1", "t1", "shell_execute")), healed.toolUses)
        assertEquals(ToolBlockStatus.CANCELLED, healed.messages[0].toolBlocks[0].toolStatus)
        assertEquals(ToolBlockStatus.SUCCESS, healed.messages[0].toolBlocks[1].toolStatus)
        assertEquals("the last bubble is Case 1's", ToolBlockStatus.RUNNING, healed.messages[2].toolBlocks[0].toolStatus)
        assertSame(user, healed.messages[1])
    }

    @Test fun `load heals every bubble, but never the live one or a sub agent`() {
        val stuck = reply("a1", tool("t1", ToolBlockStatus.PENDING))
        val agent = reply("a2", tool("d1", ToolBlockStatus.RUNNING, name = "subagent_task"))
        val live = reply("a3", tool("t3", ToolBlockStatus.STREAMING))
        val healed = InFlightToolHeal.sweep(listOf(stuck, agent, live), keep = setOf("a3")) { it.toolName == "subagent_task" }
        assertEquals(ToolBlockStatus.CANCELLED, healed.messages[0].toolBlocks[0].toolStatus)
        assertEquals(ToolBlockStatus.RUNNING, healed.messages[1].toolBlocks[0].toolStatus)
        assertEquals(ToolBlockStatus.STREAMING, healed.messages[2].toolBlocks[0].toolStatus)
        val clean = listOf(reply("a1", tool("t1", ToolBlockStatus.SUCCESS)))
        assertSame("nothing to heal, nothing re-emitted", clean, InFlightToolHeal.sweep(clean, emptySet()).messages)
    }

    @Test fun `a cancelled result is stored only where it stays next to its call`() {
        val use = """[{"type":"toolUse","value":{"toolUseId":"t1","name":"shell_execute","input":"{}"}}]"""
        val res = """[{"type":"toolResult","value":{"toolUseId":"t1","output":"x","success":true}}]"""
        val later = """[{"type":"text","value":"next"}]"""
        assertEquals(setOf("t1"), InFlightToolHeal.appendableCancelResults(listOf(later, use), listOf("t1")))
        assertTrue("already answered", InFlightToolHeal.appendableCancelResults(listOf(use, res), listOf("t1")).isEmpty())
        assertTrue("later rows follow the call", InFlightToolHeal.appendableCancelResults(listOf(use, later), listOf("t1")).isEmpty())
        // Responses-style combined ids pair on the call half.
        val combinedUse = """[{"type":"toolUse","value":{"toolUseId":"c1|fc1","name":"x"}}]"""
        val combinedRes = """[{"type":"toolResult","value":{"toolUseId":"c1","output":"x","success":true}}]"""
        assertTrue(InFlightToolHeal.appendableCancelResults(listOf(combinedUse, combinedRes), listOf("c1|fc1")).isEmpty())
    }

    // ── Per-tool Stop ────────────────────────────────────────────────────

    @Test fun `stop on a stale pill while another turn streams cancels only that pill`() {
        val live = listOf(tool("new", ToolBlockStatus.RUNNING))
        assertEquals(StopAction.CANCEL_STALE_BLOCK,
            InFlightToolHeal.stopAction("old", isStreaming = true, liveBlocks = live, blockStatus = ToolBlockStatus.RUNNING))
        val blocks = listOf(tool("old", ToolBlockStatus.RUNNING), tool("x", ToolBlockStatus.SUCCESS))
        val fixed = InFlightToolHeal.cancelBlock(blocks, "old")
        assertEquals(ToolBlockStatus.CANCELLED, fixed[0].toolStatus)
        assertSame(blocks[1], fixed[1])
        assertSame("absent: same list", blocks, InFlightToolHeal.cancelBlock(blocks, "nope"))
    }

    @Test fun `stop on a pill of the running turn stops that turn`() {
        val live = listOf(tool("t1", ToolBlockStatus.RUNNING))
        assertEquals(StopAction.STOP_TURN,
            InFlightToolHeal.stopAction("t1", isStreaming = true, liveBlocks = live, blockStatus = ToolBlockStatus.RUNNING))
        assertEquals("nothing streams: a stuck pill is a leftover", StopAction.CANCEL_STALE_BLOCK,
            InFlightToolHeal.stopAction("t1", isStreaming = false, liveBlocks = emptyList(), blockStatus = ToolBlockStatus.RUNNING))
        assertEquals(StopAction.NONE,
            InFlightToolHeal.stopAction("t1", isStreaming = false, liveBlocks = emptyList(), blockStatus = ToolBlockStatus.CANCELLED))
    }

    // ── Source guards ────────────────────────────────────────────────────

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    @Test fun `the shell lineCallback checks the turn token before touching the block`() {
        val cb = vm.substringAfter("lineCallback = lc@{ rawLine ->").substringBefore("\n            )\n")
        val check = cb.indexOf("if (turnGen != null && !liveTurn.isLive(turnGen)) {")
        assertTrue(check >= 0)
        assertTrue("before the snapshot is rewritten", check < cb.indexOf("toolBlocks[idx] = toolBlocks[idx].copy(content = trimmed)"))
        assertTrue(cb.contains("noteStopLateLine(toolId, cleanedLine, \"reader\")"))
        // ...and again on Main, where the write happens.
        val main = cb.substringAfter("viewModelScope.launch(Dispatchers.Main) {")
        assertTrue(main.indexOf("liveTurn.isLive(turnGen)") in 0 until main.indexOf("updateAssistantMessage("))
        assertTrue(vm.contains("val turnGen = liveTurn.liveGeneration()"))
    }

    @Test fun `only the live bubble writes the overlay`() {
        val upd = vm.substringAfter("    private fun updateAssistantMessage(").substringBefore("\n    internal fun effectiveContent(")
        val streaming = upd.substringAfter("if (isStreaming) {")
        val gate = streaming.indexOf("if (!liveTurn.acceptsStreamingWrite(id)) {")
        assertTrue(gate >= 0)
        assertTrue("before any overlay write", gate < streaming.indexOf("_streamingById.value = "))
        val publish = streaming.substringAfter("fun publish(").substringBefore("_streamingById.value = ")
        assertTrue("the delayed trailing publish re-checks", publish.contains("if (!liveTurn.acceptsStreamingWrite(id)) return"))
    }

    @Test fun `the loop owns its token, and every bubble swap is announced`() {
        val wrapper = vm.substringAfter("    private suspend fun runAgentLoop(").substringBefore("    private suspend fun runAgentLoopBody(")
        assertTrue(wrapper.contains("val turnGen = liveTurn.begin()"))
        assertTrue(wrapper.contains("} finally {\n            liveTurn.end(turnGen)"))
        val body = vm.substringAfter("    private suspend fun runAgentLoopBody(")
        assertEquals(3, body.split("liveTurn.markAssistant(turnGen, assistantId)").size - 1)
        assertEquals("assistantId is reassigned in exactly the three places that announce it",
            2, Regex("""\n\s+assistantId = (freshAssistantId|handled\.newAssistantId)\n""").findAll(body).count())
    }

    @Test fun `cancel ends the turn first, logs the real flag, and drains after the kill`() {
        val cancel = vm.substringAfter("    fun cancelStream(keepBackground: Boolean = false) {").substringBefore("\n    }\n")
        assertFalse("no hard-coded literal", cancel.contains("cancelStream invoked _isStreaming=false"))
        assertTrue(cancel.contains("cancelStream invoked _isStreaming=\$wasStreaming"))
        val endTurn = cancel.indexOf("liveTurn.cancel()")
        assertTrue(endTurn in 0 until cancel.indexOf("streamJob?.cancel()"))
        assertTrue(endTurn < cancel.indexOf("flushAllStreamingDeltas()"))
        val kill = cancel.indexOf("ExecutionCoordinator.stopCurrentCommand(activeSessionId)")
        val drain = cancel.indexOf("[StopLate] cancelStream: draining")
        assertTrue(drain > kill && drain < cancel.indexOf("handleUserCancelledCleanup()"))
        // Kept from T-scheduled-active-session-android.
        assertFalse(cancel.contains("ScheduledTaskManager") || cancel.contains("disableAllCreatedBy"))
    }

    @Test fun `flushes fold the overlay without regressing finished statuses`() {
        assertEquals(2, vm.split("toolBlocks = InFlightToolHeal.mergeOverlayBlocks(current[idx].toolBlocks, delta.toolBlocks),").size - 1)
        assertFalse(vm.contains("toolBlocks = delta.toolBlocks,"))
    }

    @Test fun `stuck pills heal on cancel, load and reopen`() {
        val cleanup = vm.substringAfter("    private fun handleUserCancelledCleanup() {")
        assertTrue(cleanup.indexOf("healStaleInFlightTools(") in 0 until cleanup.indexOf("val msgs = _messages.value.toMutableList()"))
        assertTrue(vm.contains("healStaleInFlightTools(\"load\")"))
        assertTrue(ProductionSources.read("ui/chat/ChatScreen.kt").contains("viewModel.healStaleInFlightTools(\"screen-open\")"))
        val heal = vm.substringAfter("internal fun healStaleInFlightTools(").substringBefore("\n    }\n")
        assertTrue("sub agents keep their own lifecycle", heal.contains("HelperRunner.isSubAgentToolName(b.toolName)"))
        assertTrue(heal.contains("persistCancelledIfUnanswered("))
    }

    @Test fun `a stale pill's stop never touches the running turn`() {
        val stop = vm.substringAfter("    fun stopTool(blockId: String) {").substringBefore("\n    }\n")
        assertTrue(stop.contains("InFlightToolHeal.StopAction.STOP_TURN -> if (foregroundTools.isRunning(blockId)) {"))
        // [T-stop-choice] A pill stops its own tool, never the sub agents.
        assertFalse(stop.contains("cancelStream()"))
        assertTrue(stop.contains("InFlightToolHeal.StopAction.CANCEL_STALE_BLOCK -> cancelStaleBlock(blockId)"))
        val stale = vm.substringAfter("    private fun cancelStaleBlock(blockId: String) {").substringBefore("\n    }\n")
        for (forbidden in listOf("cancelStream", "_isStreaming", "streamJob", "ExecutionCoordinator", "AgentJobRegistry", "_promptQueue")) {
            assertFalse(forbidden, stale.contains(forbidden))
        }
        assertTrue("the live bubble is skipped", stale.contains("m.id == liveId"))
    }

    @Test fun `the pill and the floating bar use the per-tool stop`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("onStop = { viewModel.stopTool(item.block.id) },"))
        assertTrue(screen.contains("onStop = { blockId -> viewModel.stopTool(blockId) },"))
        val bar = screen.substringAfter("FloatingToolStatusBar(").substringBefore("\n                        )")
        assertFalse(bar.contains("cancelStream"))
        val pill = screen.substringAfter(") else ToolCallPill(").substringBefore("onOpenTerminalWithCommand")
        assertFalse(pill.contains("cancelStream"))
        assertTrue(ProductionSources.read("ui/chat/ChatComposerWidgets.kt").contains("onStop: ((blockId: String) -> Unit)? = null,"))
    }

    @Test fun `the shell stop reaches the field log`() {
        val coord = ProductionSources.read("sandbox/ExecutionCoordinator.kt")
        val stop = coord.substringAfter("fun stopCurrentCommand(sessionId: String? = null) {").substringBefore("\n    }\n")
        assertTrue(stop.contains("com.openminis.app.logging.AppLogger.info("))
        assertTrue(stop.contains("stopped by user, command killed"))
        assertFalse(stop.contains("Log.i(TAG, \"[\$sessionId] Shells stopped by user\")"))
    }
}
