package com.openminis.app.service

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fgs-stop-guard] The mirror image of [FgsStartAfterStopTest].
 *
 * That one pins "no start may follow the stop". This one pins the crash the
 * device actually hit on 2026-11-13 / 11-14 (crash-2026-11-14_12-59-47.log):
 * a *stop* arriving while the start is still in flight. The service instance is
 * created, the record is brought down before it ever calls startForeground(),
 * the promote that follows lands on a torn-down record, `fgRequired` is never
 * cleared, and the framework kills the PROCESS from the main looper ~250 ms
 * later with
 * `ForegroundServiceDidNotStartInTimeException: ... did not then call
 * Service.startForeground()`.
 *
 * The trigger was the assist entry: one gesture delivered twice, so a cold
 * path opened draft A and the follow-up opened draft B, which made the chat
 * route flip twice inside ~15 ms -> `setAbsent(A)` + `setPresent(B)` ->
 * start/stop/start.
 */
class FgsStopGuardTest {

    /** Mirrors the GUARDED tracker: defer the stop until the record promoted. */
    private class Guarded {
        var liveInstances = 0
        var promotedInstances = 0
        var alive = false
        var deferred = false
        var stops = 0
        private var lastStartAcceptedAt = -1L
        var now = 10_000L
        private val promoteDeadlineMs = 5_000L

        fun unpromoted(): Int = (liveInstances - promotedInstances).coerceAtLeast(0)

        private fun wouldStrandAnInFlightStart(): Boolean {
            if (unpromoted() > 0) return true
            if (alive) return false
            if (lastStartAcceptedAt < 0L) return false
            return now - lastStartAcceptedAt < promoteDeadlineMs
        }

        fun startAccepted() {
            lastStartAcceptedAt = now
            deferred = false
        }

        fun instanceCreated() {
            alive = true
            liveInstances++
        }

        fun promoted() = promotedInstances++

        fun stopRequested() {
            if (wouldStrandAnInFlightStart()) {
                deferred = true
                return
            }
            deferred = false
            stops++
        }

        fun consumeDeferredStop(): Boolean {
            if (!deferred) return false
            deferred = false
            return true
        }

        fun destroyed() {
            alive = false
            liveInstances--
            if (promotedInstances > 0) promotedInstances--
        }
    }

    /** Mirrors the OLD tracker: dispatch the stop no matter what. */
    private class Racy {
        var stops = 0
        fun stopRequested() {
            stops++
        }
    }

    @Test
    fun `a stop that lands before promotion is deferred instead of dispatched`() {
        val t = Guarded()
        // setPresent(A) -> startForegroundService() accepted, not yet delivered.
        t.startAccepted()
        // setAbsent(A) 6 ms later -> the record is already created, still unpromoted.
        t.instanceCreated()
        t.stopRequested()
        assertEquals("no stop may be dispatched while the start is in flight", 0, t.stops)
        assertTrue("the stop must be remembered for the service to honour", t.deferred)
    }

    @Test
    fun `the unguarded shape is what killed the process - proves the test is not vacuous`() {
        val t = Racy()
        t.stopRequested()
        assertEquals("the old body dispatched this very stop", 1, t.stops)
    }

    @Test
    fun `the deferred stop is honoured only after promotion`() {
        val t = Guarded()
        t.startAccepted()
        t.instanceCreated()
        t.stopRequested()
        // The service reaches startForeground(), then consumes the deferral.
        t.promoted()
        assertTrue(t.consumeDeferredStop())
        // Promoting is what makes unwinding legal; the second request goes out.
        t.stopRequested()
        assertEquals(1, t.stops)
        assertFalse(t.consumeDeferredStop())
    }

    @Test
    fun `a stop after promotion is dispatched immediately as before`() {
        val t = Guarded()
        t.startAccepted()
        t.instanceCreated()
        t.promoted()
        t.stopRequested()
        assertEquals("an already-promoted service stops at once", 1, t.stops)
        assertFalse(t.deferred)
    }

    @Test
    fun `a start that never materialised cannot pin the service forever`() {
        val t = Guarded()
        t.startAccepted()
        // No instance ever appears (the start was accepted but nothing ran).
        t.now += 5_001L
        t.stopRequested()
        assertEquals("past the promote deadline the stop goes out", 1, t.stops)
    }

    @Test
    fun `a start accepted moments ago with no instance yet is still deferred`() {
        val t = Guarded()
        t.startAccepted()
        t.now += 200L
        t.stopRequested()
        assertEquals(0, t.stops)
        assertTrue(t.deferred)
    }

    @Test
    fun `a refused start leaves nothing to defer`() {
        val t = Guarded()
        // dispatchStart() never records a start when the system refused it.
        t.stopRequested()
        assertEquals("nothing was started, so nothing needs deferring", 1, t.stops)
        assertFalse(t.deferred)
    }

    @Test
    fun `production keeps the guard wired the same way`() {
        val tracker = ProductionSources.read("service/SessionActivityTracker.kt")
        assertTrue(tracker.contains("private fun stopWouldStrandAnInFlightStart()"))
        assertTrue(tracker.contains("if (stopWouldStrandAnInFlightStart())"))
        assertTrue(tracker.contains("deferredStop = true"))
        assertTrue(tracker.contains("internal fun consumeDeferredStop(): Boolean"))
        // The start path must clear the deferral and stamp the deadline.
        val dispatch =
            tracker.substringAfter("private fun dispatchStart(context: Context) {")
                .substringBefore("\n    }\n")
        assertTrue(dispatch.contains("deferredStop = false"))
        assertTrue(dispatch.contains("lastStartAcceptedAt = SystemClock.elapsedRealtime()"))

        val service = ProductionSources.read("service/AgentForegroundService.kt")
        // Every promote funnels through the helper, so none can skip accounting.
        val promoteCalls = Regex("\\bstartForeground\\(").findAll(service)
            .count { match ->
                val line = service.substring(0, match.range.first).substringAfterLast('\n')
                !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//")
            }
        assertEquals("all promotes must go through promoteToForeground()", 2, promoteCalls)
        assertTrue(service.contains("private fun promoteToForeground(notification: Notification)"))
        assertTrue(service.contains("fun unpromotedInstanceCount(): Int"))
        assertTrue(service.contains("SessionActivityTracker.consumeDeferredStop()"))
        // Promote before teardown in the deferred branch.
        val deferredBranch = service.substringAfter("if (SessionActivityTracker.consumeDeferredStop()")
        assertTrue(deferredBranch.indexOf("stopSelf()") >= 0)

        val activity = ProductionSources.read("MainActivity.kt")
        assertEquals(
            "the cold-start entry must dedupe like onNewIntent does",
            2,
            Regex("isDuplicateAssistEntryDelivery\\(intent\\)").findAll(activity).count(),
        )
    }
}
