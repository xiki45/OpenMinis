package com.openminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-android-jankdiag] The frame / sample aggregation behind the [JankDiag] summaries. */
class JankDiagStatsTest {
    private fun frame(totalMs: Double, layoutMs: Double = 0.0): LongArray {
        val a = LongArray(JankFrameWindow.Stage.entries.size)
        a[JankFrameWindow.Stage.TOTAL.ordinal] = (totalMs * 1e6).toLong()
        a[JankFrameWindow.Stage.LAYOUT_MEASURE.ordinal] = (layoutMs * 1e6).toLong()
        return a
    }

    @Test fun `window counts jank against the frame deadline and reports percentiles`() {
        val w = JankFrameWindow(initialCapacity = 4) // grows past its initial size
        val deadline = 8_333_333L // 120 Hz
        var vsync = 1_000_000_000L
        repeat(90) { w.add(frame(6.0, 1.0), deadline, vsync); vsync += deadline }
        repeat(10) { w.add(frame(20.0, 9.0), deadline, vsync); vsync += deadline }
        assertEquals(100, w.count)
        assertEquals(10, w.janky)
        assertEquals(0, w.over100ms)
        assertEquals(6.0, w.percentileMs(JankFrameWindow.Stage.TOTAL, .5), 1e-6)
        assertEquals(20.0, w.percentileMs(JankFrameWindow.Stage.TOTAL, .99), 1e-6)
        assertEquals(9.0, w.maxMs(JankFrameWindow.Stage.LAYOUT_MEASURE), 1e-6)
        assertEquals(120.0, w.fps(), 0.5)
        assertTrue(w.worthReporting())
        assertTrue(w.summary().startsWith("frames=100 fps=120.0 janky=10 over100ms=0 total p50=6.0"))
        w.reset()
        assertEquals(0, w.count)
        assertFalse("an empty window prints nothing", w.worthReporting())
    }

    @Test fun `a quiet window is not reported, one long hitch is`() {
        val w = JankFrameWindow()
        repeat(50) { w.add(frame(5.0), 16_666_666L, 0) }
        w.add(frame(25.0), 16_666_666L, 0)
        assertFalse("one janky frame alone is not worth a line", w.worthReporting())
        w.add(frame(150.0), 16_666_666L, 0)
        assertTrue("a frame over 100 ms is", w.worthReporting())
    }

    @Test fun `ms samples keep percentiles over a bounded reservoir`() {
        val s = JankMsSamples(capacity = 8)
        (1..20).forEach { s.add(it.toDouble()) }
        assertEquals(20, s.total)
        assertEquals(20.0, s.maxMs, 0.0)
        assertTrue(s.percentile(.9) <= 20.0)
        s.reset()
        assertEquals(0.0, s.percentile(.5), 0.0)
    }

    @Test fun `screen labels drop route arguments`() {
        assertEquals("chat", jankScreenLabel("chat/{sessionId}"))
        assertEquals("settings_memory", jankScreenLabel("settings_memory"))
        assertEquals("none", jankScreenLabel(null))
    }
}
