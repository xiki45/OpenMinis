package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-261025-05] A tester reported that a shell call waiting on a countdown
 * could not be stopped. "Stop Current Tool Only" and the tool pill's Stop do
 * not cancel a shell call - they kill its process group - so anything the
 * call did before its process existed (a `delay` countdown, the process
 * queue, a bash install) ignored the Stop and the command ran afterwards.
 */
class ShellStopBeforeStartTest {

    @Test fun `a non-cancellable call can see its interrupt while it is still waiting`() = runBlocking {
        val tools = ForegroundToolInterrupts()
        var waitedTicks = 0
        suspend fun countdown(): String {
            // The delay countdown: one check per tick.
            repeat(1_000) {
                if (tools.isInterrupted("s1")) return "stopped during the countdown"
                waitedTicks++
                delay(5)
            }
            return "ran the command"
        }
        val outcome = withTimeout(5_000) {
            val call = async {
                tools.run("s1", "shell_execute", cancellable = false, onInterrupted = { "cancelled" }) { countdown() }
            }
            while (!tools.isRunning("s1")) delay(1)
            delay(30)
            tools.interrupt()
            call.await()
        }
        assertEquals("stopped during the countdown", outcome.value)
        assertTrue("marked interrupted", outcome.interrupted)
        assertTrue("stopped early, not after the whole countdown", waitedTicks < 1_000)
        assertFalse("the mark is dropped once the call ends", tools.isInterrupted("s1"))
    }

    @Test fun `every stop of a session moves its epoch, other sessions keep theirs`() {
        val a = "test-epoch-a-${System.nanoTime()}"
        val b = "test-epoch-b-${System.nanoTime()}"
        val a0 = ExecutionCoordinator.stopEpoch(a)
        val b0 = ExecutionCoordinator.stopEpoch(b)
        assertFalse(ExecutionCoordinator.stoppedSince(a, a0))

        ExecutionCoordinator.interruptForegroundCommands(a)
        assertTrue("tool-only stop", ExecutionCoordinator.stoppedSince(a, a0))
        assertFalse("another chat is untouched", ExecutionCoordinator.stoppedSince(b, b0))

        val a1 = ExecutionCoordinator.stopEpoch(a)
        ExecutionCoordinator.stopCurrentCommand(a)
        assertTrue("stop all", ExecutionCoordinator.stoppedSince(a, a1))

        val a2 = ExecutionCoordinator.stopEpoch(a)
        ExecutionCoordinator.sessionDidTerminate(a)
        assertTrue("chat closed", ExecutionCoordinator.stoppedSince(a, a2))
    }

    @Test fun `the session-less stop (shell restart after a mount change) is not a user stop`() {
        val a = "test-epoch-c-${System.nanoTime()}"
        ExecutionCoordinator.interruptForegroundCommands(a)
        val a0 = ExecutionCoordinator.stopEpoch(a)
        ExecutionCoordinator.stopCurrentCommand()
        assertFalse("a waiting command in any chat still runs", ExecutionCoordinator.stoppedSince(a, a0))
    }

    @Test fun `a command checks the stop before it spawns, and its wait is watched`() {
        val src = ProductionSources.read("sandbox/ExecutionCoordinator.kt")
        assertTrue(src.contains("if (stoppedSince(sessionId, stopEpoch)) {"))
        assertTrue("registered before the check", src.indexOf("(existing ?: newFreshShellSet()).also { it.add(shell) }") <
            src.indexOf("if (stoppedSince(sessionId, stopEpoch)) {"))
        assertTrue(src.contains("work.cancel(StoppedBeforeStart())"))
        assertTrue("watching stops once a process exists", src.contains("while (isActive && !spawned.get())"))
    }

    @Test fun `the shell call checks the interrupt in its countdown and passes one epoch to every execute`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("return stoppedBeforeStart(toolBlocks, toolId, toolTitle, \"during its \${delaySec}s delay\")"))
        assertTrue(vm.contains("val stopEpoch = ExecutionCoordinator.stopEpoch(dispatchSessionId)"))
        assertEquals("bash probes/install and both runs share the tool's epoch", 4, Regex("stopEpoch = stopEpoch").findAll(vm).count())
        assertTrue("a call queued behind a tool-only stop does not start", vm.contains("if (toolOnlyStopTurn == turnGen) {"))
    }

    @Test fun `a stopped bash install is not counted as a failed one`() {
        val src = ProductionSources.read("agent/shell/OnDemandBash.kt")
        assertTrue(src.contains("if (probe == STOPPED_EXIT) return Outcome.Unavailable(STOPPED_REASON)"))
        val stopped = src.indexOf("if (rc == STOPPED_EXIT) {")
        assertTrue(stopped > 0 && stopped < src.indexOf("recordFailure(context); markUnavailable()\n        Log.e"))
    }
}
