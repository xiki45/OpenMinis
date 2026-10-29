package com.openminis.app.service

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fgs-presence-no-chip] A chat the user is merely in keeps the service
 * (T166 keep-alive) but must not look like an agent at work: no status-bar
 * chip, no clock, no per-second refresh. Running, waiting for a scheduled run
 * and completed keep the chip.
 */
class FgsPresenceOnlyNotificationTest {

    @Test
    fun `idle with nothing holding a chat is presence only`() {
        assertTrue(isPresenceOnly(AgentPhase.IDLE, hasScheduledHold = false))
    }

    @Test
    fun `a chat held by its scheduled task keeps the chip even without a countdown`() {
        assertFalse(isPresenceOnly(AgentPhase.IDLE, hasScheduledHold = true))
    }

    @Test
    fun `every working, waiting or finished phase keeps the chip`() {
        for (phase in AgentPhase.values().filter { it != AgentPhase.IDLE }) {
            assertFalse("$phase", isPresenceOnly(phase, hasScheduledHold = false))
        }
    }

    @Test
    fun `phase transitions flip the decision both ways`() {
        // Present, idle -> a run starts -> it completes. Same inputs the
        // service resolves on each rebuild.
        val idle = resolveAgentPhase(isCompleted = false, toolName = null, isThinking = false, hasActiveSessions = false)
        val running = resolveAgentPhase(isCompleted = false, toolName = null, isThinking = false, hasActiveSessions = true)
        val tool = resolveAgentPhase(isCompleted = false, toolName = "shell_execute", isThinking = false, hasActiveSessions = true)
        val done = resolveAgentPhase(isCompleted = true, toolName = null, isThinking = false, hasActiveSessions = false)
        val waiting = resolveAgentPhase(
            isCompleted = false, toolName = null, isThinking = false, hasActiveSessions = false, isScheduledWaiting = true,
        )
        assertTrue(isPresenceOnly(idle, false))
        assertFalse(isPresenceOnly(running, false))
        assertFalse(isPresenceOnly(tool, false))
        assertFalse(isPresenceOnly(done, false))
        assertFalse(isPresenceOnly(waiting, false))
    }

    @Test
    fun `presence only shows no clock`() {
        val clock = notificationClockFor(
            phase = AgentPhase.IDLE,
            runStartWallMs = 1_000L,
            finishedWallMs = 2_000L,
            nextRunWallMs = null,
            nowWallMs = 5_000L,
            presenceOnly = true,
        )
        assertFalse(clock.showWhen)
        assertFalse(clock.usesChronometer)
    }

    @Test
    fun `the other phases keep their clock`() {
        val running = notificationClockFor(AgentPhase.GENERATING, 1_000L, 2_000L, null, 5_000L)
        assertTrue(running.usesChronometer)
        assertEquals(1_000L, running.whenMs)
        val idleHeld = notificationClockFor(AgentPhase.IDLE, 1_000L, 2_000L, null, 5_000L, presenceOnly = false)
        assertTrue(idleHeld.usesChronometer)
        val done = notificationClockFor(AgentPhase.COMPLETED, 1_000L, 2_000L, null, 5_000L)
        assertTrue(done.showWhen)
        assertEquals(2_000L, done.whenMs)
    }

    @Test
    fun `the service never promotes or ticks a presence-only notification`() {
        val svc = ProductionSources.read("service/AgentForegroundService.kt")
        assertTrue(
            "the promoted branch is skipped when presence only",
            svc.contains("if (dynamicIslandOn && !presenceOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {"),
        )
        assertTrue("the clock follows presence only", svc.contains("presenceOnly = presenceOnly,"))
        val chip = svc.substringAfter("private fun isPromotedChipActive(): Boolean {").substringBefore("\n    }\n")
        assertTrue("no ticker while presence only", chip.contains("if (presenceOnlyNow()) return false"))
        val ticker = svc.substringAfter("private fun ensureChipTicker() {").substringBefore("\n    }\n")
        assertTrue(
            "a drop to presence only mid-tick re-posts the demoted row before stopping",
            ticker.contains("if (presenceOnlyNow()) {\n                        refreshOngoingNotification()\n                        break"),
        )
    }
}
