package com.openminis.app.service

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sched-overlay-negative-clock] After the waiting countdown ends without a
 * new run, the completed capsule must not count up from the next run's time.
 */
class OverlayClockTest {

    @Test fun `a completed clock shows only from an anchor in the past`() {
        assertTrue(OverlayClock.completedShowsClock(base = 1_000, nowElapsedMs = 36_000))
        assertTrue(OverlayClock.completedShowsClock(base = 36_000, nowElapsedMs = 36_000))
        assertFalse("still the countdown's next-run anchor", OverlayClock.completedShowsClock(base = 78_000, nowElapsedMs = 36_000))
    }

    @Test fun `the completed branch applies the rule after stopping the clock`() {
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        val branch = src.substringAfter("[T-android-overlay-task-total-timer] Completed").substringBefore("} else {\n                elapsed.stop()")
        assertTrue(branch.indexOf("elapsed.stop()") in 0 until branch.indexOf("OverlayClock.completedShowsClock(elapsed.base, SystemClock.elapsedRealtime())"))
        assertFalse("never forced visible", branch.contains("elapsed.visibility = View.VISIBLE\n"))
    }
}
