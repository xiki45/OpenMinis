package com.openminis.app.service

import com.openminis.app.ProductionSources
import com.openminis.app.scheduled.ScheduledWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-port-sched-overlay-waiting] Between two fires of a scheduled task, the
 * floating capsule shows the wait - "Next run · 2/3" counting down - instead
 * of lingering five minutes as "completed" with the last run's reply (iOS
 * fa7adaf9e turns the Live Activity card into a countdown card).
 */
class ScheduledOverlayWaitingTest {

    private val wait = ScheduledWait("chat-1", "task", "tick", nextRunMs = 2_000_000L, runCounter = "2/3")

    @Test
    fun `the wait shows only on the capsule of its own chat`() {
        assertEquals(wait, overlayWaitFor(wait, "chat-1"))
        assertEquals("an unbound capsule shows it too", wait, overlayWaitFor(wait, null))
        assertNull(overlayWaitFor(wait, "chat-2"))
        assertNull(overlayWaitFor(null, "chat-1"))
    }

    @Test
    fun `next run instant is moved onto the elapsedRealtime clock`() {
        assertEquals(5_060_000L, wallClockToElapsedRealtime(wallMs = 1_060_000L, nowWallMs = 1_000_000L, nowElapsedMs = 5_000_000L))
        assertEquals(4_990_000L, wallClockToElapsedRealtime(990_000L, 1_000_000L, 5_000_000L))
    }

    @Test
    fun `the service renders waiting before the completion linger, without the 5 minute cap`() {
        val svc = ProductionSources.read("service/AgentForegroundService.kt")
        assertTrue("observed", svc.contains("                SessionActivityTracker.scheduledWait,\n            ) { values: Array<Any?> ->"))
        assertTrue(svc.contains("scheduledWait = values[17] as com.openminis.app.scheduled.ScheduledWait?,"))
        val waitingBranch = svc.indexOf("} else if (overlayWaitFor(state.scheduledWait, state.currentSessionId) != null) {")
        val lingerBranch = svc.indexOf("// [T-android-overlay-completion-pending] Completion linger:")
        assertTrue(waitingBranch in 0 until lingerBranch)
        val branch = svc.substring(waitingBranch, lingerBranch)
        assertTrue("cancels any linger timer", branch.contains("lingerJob?.cancel()"))
        assertFalse("arms none", branch.contains("delay(COMPLETION_LINGER_TIMEOUT_MS)"))
        assertTrue(branch.contains("replyExcerpt = null,"))
        assertTrue(branch.contains("waitStatusText(getString(R.string.scheduled_detail_next_run), wait.runCounter)"))
    }

    @Test
    fun `the capsule drops the outcome and counts down, then counts up again when running`() {
        val c = ProductionSources.read("service/ToolOverlayController.kt")
        assertTrue(c.contains("data class Waiting(val statusText: String, val nextRunElapsedMs: Long?)"))
        assertTrue("no 'completed' word while waiting", c.contains("val completionWord: String? = if (!isRunning && !isWaiting) when (outcome) {"))
        assertTrue("no outcome tick while waiting", c.contains("if (!showReplyVariant || isWaiting) null else when (outcome) {"))
        assertTrue("row 2 is the wait", c.contains("isWaiting -> waiting!!.statusText"))
        assertTrue("counts down", c.contains("elapsed.isCountDown = true"))
        assertTrue("stops at zero", c.contains("if (c.isCountDown && c.base <= SystemClock.elapsedRealtime()) c.stop()"))
        assertTrue("back to counting up", c.contains("elapsed.isCountDown = false"))
    }
}
