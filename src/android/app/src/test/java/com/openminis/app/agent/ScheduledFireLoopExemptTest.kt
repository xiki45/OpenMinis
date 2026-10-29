package com.openminis.app.agent

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-loop-reminder-scheduled-exempt] A scheduled task that polls (the same
 * shell command every 2 minutes) repeats one identical tool call per fire by
 * design. Each fire exempts its next recorded call, so the poll never adds up
 * to a loop warning; everything else keeps the old behaviour.
 */
class ScheduledFireLoopExemptTest {

    private val poll = mapOf("command" to "osascript read-iterm2-screen")

    /** One scheduled fire: exempt, then the poll call (same result each time). */
    private fun fire(d: ToolLoopDetector): Pair<LoopCheckResult, LoopCheckResult> {
        d.exemptNextCallForScheduledFire()
        val pre = d.check("shell", poll)
        val post = d.record("shell", poll, "same screen", null)
        return pre to post
    }

    @Test
    fun `40 fires of the same poll never warn or block`() {
        val d = ToolLoopDetector()
        repeat(40) { i ->
            val (pre, post) = fire(d)
            assertEquals("fire $i check", Level.NONE, pre.level)
            assertEquals("fire $i record", Level.NONE, post.level)
        }
        assertEquals("exempted calls are not counted", 0, d.historySnapshot().size)
    }

    @Test
    fun `the same calls without scheduled fires still warn at 10 and block at 30`() {
        val d = ToolLoopDetector()
        var firstWarning = -1
        for (i in 1..10) {
            d.check("shell", poll)
            val post = d.record("shell", poll, "same screen", null)
            if (post.level == Level.WARNING && firstWarning < 0) firstWarning = i
        }
        assertEquals(10, firstWarning)
        repeat(20) { d.record("shell", poll, "same screen", null) }
        assertEquals(Level.CRITICAL, d.check("shell", poll).level)
    }

    @Test
    fun `the exemption does not stack and covers only one call`() {
        val d = ToolLoopDetector()
        // Several fires that made no tool call bank nothing beyond one.
        repeat(5) { d.exemptNextCallForScheduledFire() }
        repeat(11) { d.record("shell", poll, "same screen", null) }
        // 1 exempted, 10 counted.
        assertEquals(10, d.historySnapshot().size)
    }

    @Test
    fun `a runaway loop inside one fire is still caught`() {
        val d = ToolLoopDetector()
        d.exemptNextCallForScheduledFire()
        var warned = false
        repeat(11) {
            d.check("shell", poll)
            if (d.record("shell", poll, "same screen", null).level == Level.WARNING) warned = true
        }
        assertTrue("10 counted identical calls within one fire must warn", warned)
    }

    @Test
    fun `reset clears a pending exemption`() {
        val d = ToolLoopDetector()
        d.exemptNextCallForScheduledFire()
        d.reset()
        d.record("shell", poll, "x", null)
        assertEquals(1, d.historySnapshot().size)
    }

    @Test
    fun `the chat exempts a fire where it starts running, never at enqueue`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        // Idle fire: in the send funnel, right before the run claims the stream.
        val send = vm.substring(vm.indexOf("    private fun sendMessage(\n"))
        val claim = send.indexOf("AppLogger.info(TAG_STREAM, \"send _isStreaming=true")
        val exempt = send.indexOf("toolLoopDetector.exemptNextCallForScheduledFire()")
        assertTrue(exempt in 0 until claim)
        assertTrue(send.substring(0, exempt).contains("ScheduledTaskMarker.parse(text) != null"))
        // Queued fire: at the tool-boundary insert and at the drain.
        assertTrue(vm.contains("if (scheduledInsert) toolLoopDetector.exemptNextCallForScheduledFire()"))
        assertTrue(vm.contains("if (queued.any { it.isScheduledFire }) toolLoopDetector.exemptNextCallForScheduledFire()"))
        // Not at enqueue: the running turn's next call would consume it.
        val enqueue = vm.substring(vm.indexOf("    fun enqueuePrompt(")).substringBefore("\n    fun removeQueuedPrompt(")
        assertTrue(!enqueue.contains("exemptNextCallForScheduledFire"))
    }
}
