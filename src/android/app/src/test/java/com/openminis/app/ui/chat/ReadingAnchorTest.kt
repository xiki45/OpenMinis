package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-261022-07 / OpenMinis#443, #472] A scrolled-away reader's text stays
 * put when the bottom-most visible row grows or the list's viewport resizes.
 */
class ReadingAnchorTest {

    /**
     * The reverseLayout list in one dimension, screen y growing downward from
     * the list's top edge: the anchor row's BOTTOM sits [offset] px below the
     * viewport's bottom edge ([viewport] px down), and a line [k] px above that
     * bottom is drawn at `viewport + offset - k`.
     */
    private class ReverseList(var viewport: Int, var offset: Int) {
        fun y(k: Int) = viewport + offset - k
    }

    @Test fun `the keyboard opening and closing leaves the text where it was`() {
        val list = ReverseList(viewport = 1700, offset = 120)
        val k = 900 // a line the user is reading, 900 px above the anchor's bottom
        val y0 = list.y(k)
        for (newHeight in listOf(1000, 1062, 1700, 1650)) { // IME up, composer +1 line, IME down, ...
            val old = list.viewport
            assertTrue(ReadingAnchorMath.shouldKeepOnResize(old, newHeight, readerAway = true))
            list.offset = ReadingAnchorMath.offsetAfterViewportResize(list.offset, old, newHeight)
            list.viewport = newHeight
            assertEquals("viewport $old -> $newHeight", y0, list.y(k))
        }
        // Without it the bottom-anchored list carries the text up with the keyboard: the report.
        val plain = ReverseList(1700, 120)
        plain.viewport = 1000
        assertEquals(y0 - 700, plain.y(k))
    }

    @Test fun `a follower and an unmeasured list are left alone on resize`() {
        assertFalse("following the bottom: the newest line rides above the keyboard", ReadingAnchorMath.shouldKeepOnResize(1700, 1000, readerAway = false))
        assertFalse(ReadingAnchorMath.shouldKeepOnResize(-1, 1000, readerAway = true))
        assertFalse(ReadingAnchorMath.shouldKeepOnResize(1700, 1700, readerAway = true))
    }

    @Test fun `a growing anchor row keeps its top when the held height and the scroll land together`() {
        // Streamed text appended to the anchor row, one flush per frame.
        val heights = listOf(400, 452, 452, 504, 610, 610, 662)
        var bottom = 1800 // the anchor's bottom edge on screen
        var reported = heights.first()
        val top0 = bottom - reported
        var natural: Int
        for (h in heights.drop(1)) {
            natural = h
            // Measure: the new height is held back for this frame.
            if (ReadingAnchorMath.shouldHoldGrowth(reported, natural, isAnchor = true, readerAway = true)) {
                assertEquals("held frame", top0, bottom - reported)
                // Next frame callback: scroll by the change and show the new height.
                bottom += ToggleAnchorMath.keepTopScroll(reported, natural).toInt()
                reported = natural
            }
            assertEquals("after $h", top0, bottom - reported)
        }
    }

    @Test fun `only the anchor row of a reader who is away is held`() {
        assertFalse("following: the stream grows as before", ReadingAnchorMath.shouldHoldGrowth(400, 452, isAnchor = true, readerAway = false))
        assertFalse("a row above the anchor does not move the text below it", ReadingAnchorMath.shouldHoldGrowth(400, 452, isAnchor = false, readerAway = true))
        assertFalse("first measure", ReadingAnchorMath.shouldHoldGrowth(-1, 452, isAnchor = true, readerAway = true))
        assertFalse(ReadingAnchorMath.shouldHoldGrowth(452, 452, isAnchor = true, readerAway = true))
        assertTrue("a shrink (a block folding) keeps the top too", ReadingAnchorMath.shouldHoldGrowth(452, 200, isAnchor = true, readerAway = true))
    }

    private val screen = ProductionSources.read("ui/chat/ChatScreen.kt")

    @Test fun `ChatScreen wires the viewport keeper, the row hold and the dictation guard`() {
        assertTrue(screen.contains("readingAnchor.readerAway = { userScrolledAway }"))
        // A drag that dismisses the keyboard keeps the text under the finger too.
        assertTrue(screen.contains("readingAnchor.keepOnResize = { userScrolledAway || isUserDragging }"))
        assertTrue(screen.contains(".then(readingAnchor.viewportModifier)"))
        // The hold is the row's outermost modifier: it must see the height the list places.
        val row = screen.substringAfter("val rowHold = remember(item.key) { readingAnchor.RowHold(item.key) }")
        assertTrue(row.substringBefore(".then(").contains("modifier = Modifier"))
        assertTrue(row.substringAfter("modifier = Modifier").trimStart().startsWith(".then(rowHold.modifier)"))
        // Scroll-to-dismiss still hides the keyboard, except over a dictation.
        val dismiss = screen.substringAfter("LaunchedEffect(isUserDragging) {").substringBefore("focusManager.clearFocus()")
        assertTrue(dismiss.contains("if (ImeDictation.recordingActive(context))"))
        assertTrue(dismiss.indexOf("ImeDictation.recordingActive") < dismiss.indexOf("keyboardController?.hide()"))
    }
}
