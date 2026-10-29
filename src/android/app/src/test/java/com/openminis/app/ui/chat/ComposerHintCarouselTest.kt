package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-port-composer-hint-carousel] The composer's context and quota figures are
 * two one-line pages that take turns every 5 s for 3 rounds and then settle on
 * the context page (iOS b76030535, 81488a84d, ffa76773a), instead of one
 * joined line whose quota half a narrow screen cut off.
 */
class ComposerHintCarouselTest {

    private fun hint(gen: Int, quota: String? = "Quota 5h 14% / 7d 32% used") = ContextUsageHint(
        generation = gen, text = "", highlights = listOf("72%", "144k / 200k"),
        tier = ContextUsage.Tier.WARNING, quotaText = quota, quotaHighlight = quota?.let { "14%" },
    )

    @Test
    fun `pages - context first, quota only when there is one`() {
        assertEquals(listOf(UsageHintPage.CONTEXT, UsageHintPage.QUOTA), hint(1).pages())
        assertEquals(listOf(UsageHintPage.CONTEXT), hint(1, quota = null).pages())
        assertEquals(listOf(UsageHintPage.CONTEXT), hint(1, quota = "").pages())
    }

    @Test
    fun `iOS timing - 5 s, 3 rounds`() {
        assertEquals(5_000L, ComposerHintCarousel.INTERVAL_MS)
        assertEquals(3, ComposerHintCarousel.ROUNDS)
    }

    @Test
    fun `two pages turn 6 times, then stop on the context page`() {
        val c = ComposerHintCarousel()
        c.adopt(7)
        val seen = mutableListOf(c.page)
        while (c.canTurn(2, lineShowing = true, composerEmpty = true, screenReaderOn = false)) seen.add(c.turn(2))
        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0), seen)
        assertTrue(c.finished)
        assertEquals(0, c.page)
        // Finished: further turns change nothing.
        assertEquals(0, c.turn(2))
    }

    @Test
    fun `a single page never turns`() {
        val c = ComposerHintCarousel()
        c.adopt(1)
        assertFalse(c.canTurn(1, lineShowing = true, composerEmpty = true, screenReaderOn = false))
        assertEquals(0, c.turn(1))
    }

    @Test
    fun `no turning while typing, without the line, or with a screen reader`() {
        val c = ComposerHintCarousel()
        c.adopt(1)
        assertFalse(c.canTurn(2, lineShowing = true, composerEmpty = false, screenReaderOn = false))
        assertFalse(c.canTurn(2, lineShowing = false, composerEmpty = true, screenReaderOn = false))
        assertFalse(c.canTurn(2, lineShowing = true, composerEmpty = true, screenReaderOn = true))
        assertTrue(c.canTurn(2, lineShowing = true, composerEmpty = true, screenReaderOn = false))
    }

    @Test
    fun `a pause keeps the count, new figures get their own rounds`() {
        val c = ComposerHintCarousel()
        c.adopt(3)
        c.turn(2); c.turn(2); c.turn(2)
        c.adopt(3) // same figures back on screen
        assertEquals(3, c.turns)
        assertEquals(1, c.page)
        c.turn(2); c.turn(2); c.turn(2)
        assertTrue(c.finished)
        c.adopt(3)
        assertTrue("the same figures do not restart", c.finished)
        c.adopt(4)
        assertFalse(c.finished)
        assertEquals(0, c.turns)
        assertEquals(0, c.page)
    }

    // --- Focus-rotation pool --------------------------------------------------

    @Test
    fun `usage pages join the rotation pool after the text entries`() {
        val text = ComposerPlaceholderRotation.POOL_SIZE
        assertEquals(text, ComposerPlaceholderPool.size(null))
        assertEquals(text + 2, ComposerPlaceholderPool.size(hint(1)))
        assertEquals(text + 1, ComposerPlaceholderPool.size(hint(1, quota = null)))
        assertNull(ComposerPlaceholderPool.usagePage(0, hint(1)))
        assertNull(ComposerPlaceholderPool.usagePage(text - 1, hint(1)))
        assertEquals(UsageHintPage.CONTEXT, ComposerPlaceholderPool.usagePage(text, hint(1)))
        assertEquals(UsageHintPage.QUOTA, ComposerPlaceholderPool.usagePage(text + 1, hint(1)))
        // Figures gone, or fewer pages than when picked: back to the default entry.
        assertNull(ComposerPlaceholderPool.usagePage(text + 1, hint(1, quota = null)))
        assertNull(ComposerPlaceholderPool.usagePage(text, null))
        assertEquals(ComposerPlaceholderRotation.DEFAULT_INDEX, ComposerPlaceholderPool.textIndex(text + 1))
        assertEquals(3, ComposerPlaceholderPool.textIndex(3))
    }

    @Test
    fun `a focus picks over the whole pool, never the entry on screen`() {
        val size = ComposerPlaceholderPool.size(hint(1))
        for (current in 0 until size) {
            val reached = (0 until size - 1).map { draw ->
                ComposerPlaceholderRotation.nextIndex(current, true, true, false, { draw }, poolSize = size)
            }.toSet()
            assertEquals((0 until size).toSet() - current, reached)
        }
        // Pinned under TalkBack, as before.
        assertEquals(7, ComposerPlaceholderRotation.nextIndex(7, true, true, true, { 0 }, poolSize = size))
        // A stale usage index (figures gone) excludes nothing.
        assertEquals(2, ComposerPlaceholderRotation.nextIndex(7, true, true, false, { 2 }))
    }

    // --- Wiring ---------------------------------------------------------------

    @Test
    fun `the composer draws pages, not the joined line, and rotates over the whole pool`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertFalse("the composer must not draw the joined, ellipsized line", screen.contains("contextUsageAnnotatedText(shownHint)"))
        assertTrue(screen.contains("val usagePage = rememberHintCarouselPage("))
        assertTrue(screen.contains("targetState = shownHint?.let { -1 - (it.generation * 2 + usagePage) }"))
        assertTrue(screen.contains("poolSize = ComposerPlaceholderPool.size(contextUsageHint),"))
        assertEquals(2, Regex("UsageHintPageText\\(").findAll(screen).count())
        val ui = ProductionSources.read("ui/chat/ComposerHintCarouselUi.kt")
        assertTrue("a hidden composer does not use up its rounds", ui.contains("repeatOnLifecycle(Lifecycle.State.RESUMED)"))
        assertTrue(ui.contains("delay(ComposerHintCarousel.INTERVAL_MS)"))
        assertTrue("each page fits one line by shrinking, floor 0.75", ui.contains("private const val MIN_FIT_SCALE = 0.75f"))
        // The voice panel keeps the joined line, as on iOS.
        assertTrue(ProductionSources.read("ui/chat/voice/InlineVoiceInputPanel.kt").contains("contextUsageAnnotatedText(usageLine)"))
    }
}
