package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-stop-choice] Stop asks "Stop Current Tool Only / Stop All" when the chat
 * has sub agents or scheduled tasks; the tool-only path ends the foreground
 * work and leaves the background alone.
 */
class StopChoiceTest {

    // ── decisions ─────────────────────────────────────────────────────

    @Test fun `only background work makes Stop ask`() {
        assertFalse("a bare turn stops as before", StopSummary(runningTools = listOf("shell_execute")).hasBackground)
        assertTrue(StopSummary(subAgentsRunning = 1).hasBackground)
        assertTrue("a delegation waiting for a slot counts", StopSummary(subAgentsQueued = 1).hasBackground)
        assertTrue(StopSummary(scheduledTasks = 2).hasBackground)
    }

    @Test fun `tool-only stop interrupts executing tools, else ends the turn`() {
        assertEquals(ToolOnlyStop.INTERRUPT_TOOLS, StopScopePolicy.toolOnlyStop(toolsRunning = true))
        assertEquals(ToolOnlyStop.END_TURN, StopScopePolicy.toolOnlyStop(toolsRunning = false))
    }

    @Test fun `after a tool-only stop the turn ends unless a message is waiting to go in`() {
        assertTrue("empty queue: wait for the user", StopScopePolicy.endsTurnAtBoundary(stopRequestedThisTurn = true, insertPending = false))
        assertFalse("a queued user message goes in as the next round", StopScopePolicy.endsTurnAtBoundary(true, insertPending = true))
        assertFalse("no stop: the loop is untouched", StopScopePolicy.endsTurnAtBoundary(false, insertPending = false))
    }

    @Test fun `the scheduled-cancel note names the tasks and is a hidden reminder`() {
        assertNull(StopScopePolicy.scheduledCancelledNotice(emptyList()))
        val note = StopScopePolicy.scheduledCancelledNotice(listOf("Daily digest", "Watch build"))!!
        assertTrue(note.startsWith("<system-reminder>"))
        assertTrue(note.endsWith("</system-reminder>"))
        assertTrue(note.contains("2 scheduled task(s)"))
        assertTrue(note.contains("\"Daily digest\", \"Watch build\""))
        assertTrue(note.contains("will not fire again"))
    }

    // ── ForegroundToolInterrupts ──────────────────────────────────────

    @Test fun `a call that finishes on its own is not marked`() = runBlocking {
        val tools = ForegroundToolInterrupts()
        val out = tools.run("a", "file_read", cancellable = true, onInterrupted = { "stopped" }) { "ok" }
        assertEquals(ForegroundToolInterrupts.Outcome("ok", interrupted = false), out)
        assertTrue(tools.isEmpty())
    }

    @Test fun `interrupting a cancellable call answers with the stopped result and leaves the caller running`() = runBlocking {
        val tools = ForegroundToolInterrupts()
        val started = CompletableDeferred<Unit>()
        val call = async {
            tools.run("a", "browser_use", cancellable = true, onInterrupted = { "stopped" }) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        assertEquals(listOf("browser_use"), tools.runningNames())
        assertEquals("browser_use", tools.nameOf("a"))
        assertEquals(listOf("a"), tools.interrupt())
        assertEquals(ForegroundToolInterrupts.Outcome("stopped", interrupted = true), withTimeout(2_000) { call.await() })
        assertTrue("unregistered once done", tools.isEmpty())
    }

    @Test fun `a non-cancellable call keeps running and comes back marked`() = runBlocking {
        // The shell: its process group is killed, it returns by itself.
        val tools = ForegroundToolInterrupts()
        val release = CompletableDeferred<Unit>()
        val call = async {
            tools.run("s", "shell_execute", cancellable = false, onInterrupted = { "never" }) {
                release.await()
                "partial output"
            }
        }
        while (tools.isEmpty()) yield()
        tools.interrupt()
        delay(50)
        assertFalse("not cancelled", call.isCompleted)
        release.complete(Unit)
        assertEquals(ForegroundToolInterrupts.Outcome("partial output", interrupted = true), call.await())
    }

    @Test fun `only the named calls are interrupted`() = runBlocking {
        val tools = ForegroundToolInterrupts()
        val gate = CompletableDeferred<Unit>()
        val a = async { tools.run("a", "shell_execute", false, { "x" }) { gate.await(); "a" } }
        val b = async { tools.run("b", "shell_execute", false, { "x" }) { gate.await(); "b" } }
        val c = async { tools.run("c", "web_fetch", true, { "x" }) { gate.await(); "c" } }
        while (tools.runningNames().size < 3) yield()
        assertEquals(setOf("a", "b"), tools.idsNamed("shell_execute").toSet())
        assertEquals(listOf("a"), tools.interrupt(listOf("a", "gone")))
        gate.complete(Unit)
        assertTrue(a.await().interrupted)
        assertFalse(b.await().interrupted)
        assertEquals(ForegroundToolInterrupts.Outcome("c", false), c.await())
    }

    @Test fun `a stop of the whole turn still cancels the call - it is not swallowed`() = runBlocking {
        val tools = ForegroundToolInterrupts()
        val started = CompletableDeferred<Unit>()
        val turn = async {
            tools.run("a", "web_fetch", cancellable = true, onInterrupted = { "stopped" }) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        tools.interrupt() // tool-only stop first ...
        turn.cancel() // ... and then the turn itself
        try {
            turn.await()
            fail("the turn's cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertTrue(tools.isEmpty())
    }

    @Test fun `the interrupted note keeps partial output and tells the model to wait`() {
        assertEquals(ForegroundToolInterrupts.INTERRUPTED_RESULT, ForegroundToolInterrupts.withInterruptNote(" "))
        val noted = ForegroundToolInterrupts.withInterruptNote("line 1\nline 2")
        assertTrue(noted.startsWith("line 1\nline 2\n\n[Stopped by the user]"))
        assertTrue(noted.contains("wait for the user's next message"))
    }

    // ── wiring ────────────────────────────────────────────────────────

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
    private val screen = ProductionSources.read("ui/chat/ChatScreen.kt")

    private fun body(src: String, header: String) = src.substringAfter(header).substringBefore("\n    }\n")

    @Test fun `tool-only stop never touches sub agents, their results or timers`() {
        val toolOnly = body(vm, "    fun stopCurrentToolOnly() {")
        val interrupt = body(vm, "    private fun interruptForegroundTools(ids: Collection<String>?) {")
        for (src in listOf(toolOnly, interrupt)) {
            for (forbidden in listOf("muteDelegationResults", "cancelAll", "dropQueuedDelegations", "disableAllCreatedBy", "stopCurrentCommand")) {
                assertFalse(forbidden, src.contains(forbidden))
            }
        }
        assertTrue(toolOnly.contains("ToolOnlyStop.INTERRUPT_TOOLS -> interruptForegroundTools(ids = null)"))
        assertTrue(toolOnly.contains("ToolOnlyStop.END_TURN -> cancelStream(keepBackground = true)"))
        // The commands die before the calls are cancelled (a cancelled call drops its shell callback).
        assertTrue(interrupt.indexOf("ExecutionCoordinator.interruptForegroundCommands(activeSessionId)") in 0 until interrupt.indexOf("foregroundTools.interrupt(ids)"))
        assertTrue("a waiting delegation moves to the background", interrupt.contains("delegateDetach.value = delegateDetach.value + 1"))
    }

    @Test fun `cancelStream keeps the background only when asked`() {
        val cancel = body(vm, "    fun cancelStream(keepBackground: Boolean = false) {")
        val gate = cancel.substringAfter("if (!keepBackground) {").substringBefore("\n        }")
        assertTrue(gate.contains("muteDelegationResults") && gate.contains("dropQueuedDelegations") && gate.contains("cancelAll"))
        assertTrue(cancel.contains("ExecutionCoordinator.interruptForegroundCommands(activeSessionId)"))
        assertTrue("Stop All still clears the whole pool", cancel.contains("ExecutionCoordinator.stopCurrentCommand(activeSessionId)"))
    }

    @Test fun `the loop routes every tool call through the interrupts and ends at the boundary`() {
        assertEquals(1, vm.split("val toolRun = foregroundTools.run(").size - 1)
        assertTrue(vm.contains("cancellable = name != \"shell_execute\" && !isDelegation,"))
        assertTrue(vm.contains("toolInterrupted -> ToolBlockStatus.CANCELLED"))
        val loop = vm.substringAfter("    private suspend fun runAgentLoopBody(")
        val boundary = loop.indexOf("StopScopePolicy.endsTurnAtBoundary(toolStopHere, insertPending = insertBatch.isNotEmpty())")
        assertTrue("after the batch is known, before it is inserted",
            boundary > loop.indexOf("val insertBatch = QueuedPromptBatching.nextInsertBatch(_promptQueue.value)") &&
                boundary < loop.indexOf("injectQueuedPromptsAsNewTurn("))
    }

    @Test fun `a waiting delegation converts to background on a tool-only stop`() {
        val delegate = vm.substringAfter("    private suspend fun executeDelegateTask(")
        assertTrue(delegate.indexOf("val detachSeen = delegateDetach.value") in 0 until delegate.indexOf("// ── Wait mode"))
        assertTrue(delegate.contains("if (userFollowUp || delegateDetach.value != detachSeen) {"))
        assertTrue(delegate.contains("withTimeoutOrNull(1_000L) { delegateDetach.first { it != detachSeen } }"))
    }

    @Test fun `stop all tells the model its timers are gone, after the cleanup's writes`() {
        val stopAll = body(vm, "    fun stopByUser() {")
        assertTrue(stopAll.indexOf("StopScopePolicy.scheduledCancelledNotice(cancelledLabels)") > stopAll.indexOf("cancelStream()"))
        val note = body(vm, "    private fun noteForNextTurn(note: String, reason: String) {")
        assertTrue(note.contains("after?.join()"))
        assertEquals("both cleanup writes are tracked", 2, vm.split("cleanupPersistJob = viewModelScope.launch(Dispatchers.IO) {").size - 1)
    }

    @Test fun `the shell interrupt keeps the pool`() {
        val coord = ProductionSources.read("sandbox/ExecutionCoordinator.kt")
        val interrupt = coord.substringAfter("fun interruptForegroundCommands(sessionId: String): Int {").substringBefore("\n    }\n")
        assertFalse(interrupt.contains("removeSessionShells"))
        assertTrue(interrupt.contains("shell.interruptForeground()"))
        val shell = ProductionSources.read("sandbox/PersistentShell.kt")
        val fg = shell.substringAfter("fun interruptForeground(): Boolean {").substringBefore("\n    }\n")
        assertTrue(fg.contains("android.system.Os.kill(-pgid, android.system.OsConstants.SIGKILL)"))
        assertTrue("no group known: this one shell only", fg.contains("stop()"))
    }

    @Test fun `the composer and New Chat offer the choice`() {
        val stopBlock = screen.substringAfter("if (showStop) {").substringBefore("} else {\n                            // Streaming with content")
        assertTrue(stopBlock.contains("StopConfirmPolicy.TapAction.CHOOSE_SCOPE -> {"))
        assertTrue(stopBlock.contains("canStopToolOnly = stopChoiceTurn != null,"))
        assertTrue(stopBlock.contains("viewModel.stopCurrentToolOnly()"))
        assertTrue("never the next turn's tools",
            stopBlock.contains("StopConfirmPolicy.confirmStillApplies(stopChoiceTurn, viewModel.liveTurnToken(), sessionBusy)"))
        assertFalse("the scheduled-only warning is gone", stopBlock.contains("chat_scheduled_stop_confirm_title"))
        val newChat = screen.substringAfter("val newChatStopChoice = remember(showNewChatStopDialog) {").substringBefore("} else if (showNewChatStopDialog) {")
        assertTrue(newChat.contains("viewModel.stopCurrentToolOnly()\n                        onNewChat()"))
        assertTrue(newChat.contains("viewModel.stopByUser()\n                        onNewChat()"))
    }

    @Test fun `strings exist in en, zh-CN and zh-TW`() {
        val base = listOf("src/main/res", "app/src/main/res").map { java.io.File(it) }.first { it.exists() }
        for (dir in listOf("values", "values-zh", "values-zh-rTW")) {
            val xml = java.io.File(base, "$dir/strings.xml").readText()
            for (key in listOf(
                "string name=\"chat_stop_choice_title\"", "string name=\"chat_stop_choice_current_tools\"",
                "string name=\"chat_stop_choice_current_reply\"", "plurals name=\"chat_stop_choice_subagents\"",
                "plurals name=\"chat_stop_choice_subagents_queued\"", "plurals name=\"chat_stop_choice_scheduled\"",
                "string name=\"chat_stop_choice_tool_only\"", "string name=\"chat_stop_choice_all\"",
                "string name=\"chat_stop_choice_tool_only_footer\"", "string name=\"chat_stop_choice_all_footer\"",
                "string name=\"chat_new_chat_stop_turn_only\"", "string name=\"chat_new_chat_stop_all\"",
            )) {
                assertTrue("$dir: $key", xml.contains("<$key"))
            }
        }
    }
}
