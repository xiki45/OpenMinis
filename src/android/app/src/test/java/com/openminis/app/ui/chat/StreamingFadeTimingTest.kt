package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stream-fade-ios-timing] The streamed word fade uses iOS
 * TextFadeAnimator's timings: 450 ms per word, and a fixed 100 ms stagger
 * window divided across the batch, so a batch of any size settles in about
 * 550 ms.
 */
class StreamingFadeTimingTest {

    @Test
    fun `timings match iOS TextFadeAnimator`() {
        assertEquals(450L, FADE_DURATION_MS)
        assertEquals(100L, STAGGER_WINDOW_MS)
    }

    @Test
    fun `the stagger window is split evenly across the batch`() {
        assertEquals(0L, perWordStaggerMs(0))
        assertEquals(100L, perWordStaggerMs(1))
        assertEquals(50L, perWordStaggerMs(2))
        assertEquals(10L, perWordStaggerMs(10))
        assertEquals(3L, perWordStaggerMs(30))
    }

    @Test
    fun `a batch settles within window plus duration whatever its size`() {
        for (text in listOf("two words", "one two three four five six seven eight nine ten eleven twelve")) {
            val c = FadeController()
            c.seed("")
            c.ingest(text)
            val t0 = System.nanoTime()
            assertTrue(c.tick(t0))
            // Last word starts at most one window late, then fades for the duration.
            val settled = t0 + (STAGGER_WINDOW_MS + FADE_DURATION_MS + 20L) * 1_000_000L
            assertFalse("\"$text\" still fading after window+duration", c.tick(settled))
        }
    }

    @Test
    fun `a word is still fading part way through its duration`() {
        val c = FadeController()
        c.seed("")
        c.ingest("hello")
        val t0 = System.nanoTime()
        assertTrue(c.tick(t0 + 300L * 1_000_000L))
        var alpha = -1f
        c.forEachActive { _, _, a -> alpha = a }
        assertTrue("alpha=$alpha", alpha > 0f && alpha < 1f)
    }
}
