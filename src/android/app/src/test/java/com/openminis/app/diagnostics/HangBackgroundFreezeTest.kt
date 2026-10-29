package com.openminis.app.diagnostics

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-hang-background-freeze] Field log (Android 12 tablet): while an OAuth
 * sign-in had the browser on top, the cached-apps freezer stopped the whole
 * process for 39 s, watchdog thread included. On the thaw the watchdog saw a
 * 39 089 ms gap, counted it as a main-thread hang and tripped the launch
 * breaker. Time spent in the background is not a hang the user saw.
 */
class HangBackgroundFreezeTest {

    private fun reason(since: Long, bg: Boolean, visible: Boolean, idle: Boolean = false) =
        HangDetector.notAHangReason(since, bg, { visible }) { idle }

    @Test
    fun `a gap while backgrounded and not visible is never counted, even with a busy main thread`() {
        val r = reason(39_089L, bg = true, visible = false)
        assertNotNull(r)
        assertTrue(r!!.contains("background"))
    }

    @Test
    fun `a real foreground hang is still counted`() {
        assertNull(reason(4_000L, bg = false, visible = true))
        assertNull(reason(39_089L, bg = false, visible = true))
    }

    @Test
    fun `a hang that began in the background and is on screen now is counted`() {
        // ON_START is dispatched on the hung main thread, so the lifecycle
        // flag still says background while the user is looking at the app.
        assertNull(reason(8_000L, bg = true, visible = true))
    }

    @Test
    fun `the background check does not sample the main stack`() {
        var sampled = 0
        HangDetector.notAHangReason(10_000L, backgrounded = true, visibleNow = { false }) { sampled++; false }
        assertEquals(0, sampled)
    }

    @Test
    fun `time the watchdog itself was frozen is not judged, unless an episode is running`() {
        // Field case: frozen 39 s, thawed as the user came back (visible).
        // Measured from the resume, the gap is ~0 - nothing to count.
        val heartbeat = 1_000L
        val resumed = 40_100L
        assertEquals(400L, HangDetector.judgedGapMs(now = 40_500L, lastHeartbeat = heartbeat, resumedAt = resumed, hangActive = false))
        // Still stuck 4 s after the thaw: that stall is real and gets counted.
        assertEquals(4_000L, HangDetector.judgedGapMs(now = 44_100L, lastHeartbeat = heartbeat, resumedAt = resumed, hangActive = false))
        assertNull(reason(4_000L, bg = true, visible = true))
        // A freeze inside a running episode neither ends it nor splits it.
        assertEquals(39_500L, HangDetector.judgedGapMs(now = 40_500L, lastHeartbeat = heartbeat, resumedAt = resumed, hangActive = true))
        // No suspension: the plain heartbeat gap.
        assertEquals(3_500L, HangDetector.judgedGapMs(now = 4_500L, lastHeartbeat = heartbeat, resumedAt = 0L, hangActive = false))
        assertEquals(5_000L, HangDetector.WATCHDOG_SUSPENDED_MS)
    }

    private val src = ProductionSources.read("diagnostics/HangDetector.kt")

    @Test
    fun `the watchdog follows the process lifecycle and reads the flag before the heartbeat`() {
        assertTrue(src.contains("androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle"))
        assertTrue(src.substringAfter("fun start(context: Context) {").substringBefore("thread(name = ").contains("observeProcessLifecycle()"))
        val watch = src.substringAfter("private fun watchLoop()").substringBefore("private fun writeStallSample(")
        val flag = watch.indexOf("val bg = backgrounded")
        val gap = watch.indexOf("val since = judgedGapMs(now, lastHeartbeatAt.get(), resumedAt, hangActive)")
        assertTrue(flag in 0 until gap)
        val suspended = watch.indexOf("if (tickGap > WATCHDOG_SUSPENDED_MS) {")
        assertTrue("the resume point is taken before the gap is judged", suspended in 0 until gap)
        assertTrue(watch.contains("notAHangReason(since, bg, ::visibleNow)"))
    }

    @Test
    fun `visibility is asked of the system, and a foreground service does not count as visible`() {
        val vis = src.substringAfter("private fun visibleNow(): Boolean").substringBefore("/** Start the watchdog.")
        assertTrue(vis.contains("ActivityManager.getMyMemoryState(info)"))
        assertTrue(vis.contains("IMPORTANCE_FOREGROUND ||") && vis.contains("IMPORTANCE_VISIBLE"))
        assertTrue(!vis.contains("IMPORTANCE_FOREGROUND_SERVICE"))
    }

    @Test
    fun `coming back to the foreground resets the heartbeat before clearing the flag`() {
        val obs = src.substringAfter("private fun observeProcessLifecycle()").substringBefore("/** Start the watchdog.")
        val reset = obs.indexOf("if (fg && backgrounded) lastHeartbeatAt.set(SystemClock.uptimeMillis())")
        val flip = obs.indexOf("backgrounded = !fg")
        assertTrue(reset in 0 until flip)
        assertTrue("lifecycle observers run on the main thread", obs.contains("mainHandler.post {"))
    }
}
