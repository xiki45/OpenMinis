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

        /** [T-fgs-stop-settle] Mirrors STOP_SETTLE_MS / STOP_RETRY_MS. */
        private val settleMs = 300L
        private val retryMs = settleMs + 50L

        /** Wall time at which the deferred stop is retried, null = none pending. */
        private var retryAt: Long? = null

        fun unpromoted(): Int = (liveInstances - promotedInstances).coerceAtLeast(0)

        private fun wouldStrandAnInFlightStart(): Boolean {
            if (unpromoted() > 0) return true
            if (lastStartAcceptedAt < 0L) return false
            val sinceStart = now - lastStartAcceptedAt
            // Nothing alive yet: the record may still be materialising, so the
            // framework's own promote window is the deadline.
            if (!alive) return sinceStart < promoteDeadlineMs
            // A promoted instance is not enough on its own: see STOP_SETTLE_MS.
            return sinceStart < settleMs
        }

        fun startAccepted() {
            lastStartAcceptedAt = now
            deferred = false
            retryAt = null
        }

        /** The retry that scheduleStopRetry() posts, fired at its scheduled time. */
        fun retryFires() {
            val at = retryAt ?: return
            now = maxOf(now, at)
            if (!deferred) {
                retryAt = null
                return
            }
            // Mirrors the retry body: an instance that appeared during the
            // settle window and has not promoted yet re-arms the retry instead
            // of being stopped.
            if (wouldStrandAnInFlightStart()) {
                retryAt = now + retryMs
                return
            }
            deferred = false
            retryAt = null
            stops++
        }

        fun hasPendingRetry(): Boolean = retryAt != null

        fun instanceCreated() {
            alive = true
            liveInstances++
        }

        fun promoted() = promotedInstances++

        fun stopRequested() {
            if (wouldStrandAnInFlightStart()) {
                deferred = true
                retryAt = now + retryMs
                return
            }
            deferred = false
            retryAt = null
            stops++
        }

        fun consumeDeferredStop(): Boolean {
            if (!deferred) return false
            deferred = false
            retryAt = null
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
        // Promoting is what makes unwinding legal; once the start/stop pair has
        // settled the second request goes out.
        t.now += 400L
        t.stopRequested()
        assertEquals(1, t.stops)
        assertFalse(t.consumeDeferredStop())
    }

    @Test
    fun `a stop on a long-lived promoted instance is dispatched immediately`() {
        val t = Guarded()
        t.startAccepted()
        t.instanceCreated()
        t.promoted()
        t.now += 600L
        t.stopRequested()
        assertEquals("a settled service stops at once", 1, t.stops)
        assertFalse(t.deferred)
    }

    @Test
    fun `a stop 1 ms after a start is deferred even though an instance is alive`() {
        // crash-2026-11-15_12-50-47.log: presence set -> clear -> set inside the
        // same millisecond while the previous instance was alive and promoted.
        // The old guard short-circuited on `isAlive` and dispatched the stop,
        // which then landed on the record the *next* start created.
        val t = Guarded()
        t.startAccepted()
        t.instanceCreated()
        t.promoted()
        t.now += 1L
        t.stopRequested()
        assertEquals("the live instance must not wave the stop through", 0, t.stops)
        assertTrue(t.deferred)
    }

    @Test
    fun `the deferred stop is retried once the settle window has passed`() {
        val t = Guarded()
        t.startAccepted()
        t.instanceCreated()
        t.promoted()
        t.now += 1L
        t.stopRequested()
        assertTrue(t.hasPendingRetry())
        t.retryFires()
        assertEquals("nothing consumes a deferral that no start follows", 1, t.stops)
    }

    @Test
    fun `a new start cancels the pending retry`() {
        val t = Guarded()
        t.startAccepted()
        t.instanceCreated()
        t.promoted()
        t.now += 1L
        t.stopRequested()
        // Presence came back before the retry fired.
        t.startAccepted()
        assertFalse("the start path must invalidate the retry token", t.hasPendingRetry())
        assertEquals(0, t.stops)
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
    fun `a retry that lands on a fresh unpromoted instance re-arms instead of stopping`() {
        val t = Guarded()
        t.startAccepted()
        t.instanceCreated()
        t.promoted()
        t.now += 1L
        t.stopRequested()
        assertTrue(t.hasPendingRetry())
        // A second start was accepted during the settle window and its instance
        // is between onCreate and startForeground() when the retry fires.
        t.instanceCreated()
        t.retryFires()
        assertEquals("the retry may not stop an unpromoted record", 0, t.stops)
        assertTrue("it must wait for the next settle window", t.hasPendingRetry())
        // The instance promotes; the next retry is legal.
        t.promoted()
        t.now += 400L
        t.retryFires()
        assertEquals(1, t.stops)
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
        // [T-fgs-promote-early] onCreate must clear fgRequired before AMS can
        // deliver a queued teardown, i.e. before the safe-mode branch and
        // before anything that could take milliseconds.
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("override fun onStartCommand")
        assertTrue(onCreate.contains("promoteContractStub()"))
        assertTrue(
            "the contract stub must run before the safe-mode bail-out",
            onCreate.indexOf("promoteContractStub()") < onCreate.indexOf("isSafeMode()"),
        )
        assertTrue(service.contains("private fun promoteContractStub()"))
        // [T-fgs-stop-settle] The live-instance shortcut is gone and a deferred
        // stop is retried instead of dropped.
        assertTrue(tracker.contains("STOP_SETTLE_MS"))
        assertTrue(tracker.contains("scheduleStopRetry(context)"))
        assertTrue(
            "a live instance must no longer wave the stop through by itself",
            !tracker.contains("if (AgentForegroundService.isAlive) return false"),
        )
        // Promote before teardown in the deferred branch, and only when the
        // service is genuinely no longer wanted (otherwise the deferral would
        // be consumed and dropped while the service is needed).
        val deferredBranch = service.substringAfter("if (!SessionActivityTracker.shouldRunServiceNow()")
        assertTrue(deferredBranch.indexOf("stopSelf()") >= 0)
        assertTrue(
            "the deferred branch must evaluate shouldRunServiceNow() before consuming",
            service.indexOf("!SessionActivityTracker.shouldRunServiceNow()") <
                service.indexOf("SessionActivityTracker.consumeDeferredStop()"),
        )
        // The retry must apply the same guard before dispatching its stop.
        val retryBody = tracker.substringAfter("private fun scheduleStopRetry(context: Context) {")
            .substringBefore("\n    }\n")
        assertTrue(
            "the retry may not stop an unpromoted record either",
            retryBody.indexOf("stopWouldStrandAnInFlightStart()") in
                0 until retryBody.indexOf("AgentForegroundService.stopService(context)"),
        )

        val activity = ProductionSources.read("MainActivity.kt")
        assertEquals(
            "the cold-start entry must dedupe like onNewIntent does",
            2,
            Regex("isDuplicateAssistEntryDelivery\\(intent\\)").findAll(activity).count(),
        )
    }
}
