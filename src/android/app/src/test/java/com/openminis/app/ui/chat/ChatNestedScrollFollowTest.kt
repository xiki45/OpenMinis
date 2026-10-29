package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.openminis.app.ProductionSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-userask-edge-bounce] The pending user_ask card's body, dragged
 * past its own edge, moved the chat list by a raw nested delta; follow-bottom
 * did not see a user scroll and snapped the list back every frame (Pixel 4a
 * repro 2026-10-09: card top 252 -> 375 -> 334 -> 359 -> 356 -> 359 px, log
 * `scroll src=LAYOUT-DRIFT-SNAP`).
 */
class ChatNestedScrollFollowTest {

    @Test
    fun `a child's drag reaching the list is a user scroll, the list's own drag is not counted twice`() {
        assertTrue(ChatNestedScrollFollow.isChildUserScroll(sourceIsUserInput = true, deltaY = -12f, listIsDragged = false))
        assertFalse("the list reports its own drag itself", ChatNestedScrollFollow.isChildUserScroll(true, -12f, listIsDragged = true))
        assertFalse("a fling's deltas are not the finger", ChatNestedScrollFollow.isChildUserScroll(false, -12f, false))
        assertFalse("a horizontal child (table, code block) does not count", ChatNestedScrollFollow.isChildUserScroll(true, 0f, false))
    }

    @Test
    fun `follow-bottom never snaps while the user moves the list through a child`() {
        val active = ChatNestedScrollFollow.childScrollActive(gestureOpen = true, msSinceLastDelta = 16)
        assertTrue(active)
        assertFalse(ChatNestedScrollFollow.followMaySnap(userScrolledAway = false, listScrollInProgress = false, childScrollActive = active))
    }

    @Test
    fun `a question waiting with nobody touching the list still follows the bottom`() {
        // The card growing (Show more, a note field) must keep the list pinned.
        val idle = ChatNestedScrollFollow.childScrollActive(gestureOpen = false, msSinceLastDelta = Long.MAX_VALUE)
        assertFalse(idle)
        assertTrue(ChatNestedScrollFollow.followMaySnap(false, false, idle))
    }

    @Test
    fun `the existing blockers still block`() {
        assertFalse(ChatNestedScrollFollow.followMaySnap(userScrolledAway = true, listScrollInProgress = false, childScrollActive = false))
        assertFalse(ChatNestedScrollFollow.followMaySnap(false, listScrollInProgress = true, childScrollActive = false))
    }

    @Test
    fun `a gesture whose end was never reported stops counting after the hold`() {
        assertTrue(ChatNestedScrollFollow.childScrollActive(true, ChatNestedScrollFollow.HOLD_MS - 1))
        assertFalse(ChatNestedScrollFollow.childScrollActive(true, ChatNestedScrollFollow.HOLD_MS))
        assertFalse("a clock that went backwards does not hold follow forever", ChatNestedScrollFollow.childScrollActive(true, -5))
    }

    @Test
    fun `a finished child gesture leaves the same state as a finger lifted from the list`() {
        assertTrue("away from the bottom: the user is reading", ChatNestedScrollFollow.scrolledAwayAfterGesture(nearBottom = false))
        assertFalse("still near the bottom: keep following", ChatNestedScrollFollow.scrolledAwayAfterGesture(nearBottom = true))
    }

    @Test
    fun `a fling in the card stops at the card's edge, a drag past it still scrolls the chat`() = runBlocking {
        val left = Offset(0f, -40f)
        assertEquals("drag leftover goes on to the chat list", Offset.Zero,
            StopFlingAtEdgeConnection.onPostScroll(Offset.Zero, left, NestedScrollSource.UserInput))
        assertEquals("fling leftover is consumed", left,
            StopFlingAtEdgeConnection.onPostScroll(Offset.Zero, left, NestedScrollSource.SideEffect))
        val v = Velocity(0f, -3000f)
        assertEquals("fling velocity is not handed to the list", v, StopFlingAtEdgeConnection.onPostFling(Velocity.Zero, v))
    }

    @Test
    fun `wiring - the list watches nested scrolls, the snap asks the policy, the card stops its fling`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains(".nestedScroll(childScrollWatch)"))
        assertTrue(screen.contains("if (!ChatNestedScrollFollow.followMaySnap(userScrolledAway, listState.isScrollInProgress, childScrolling)) {"))
        val snap = screen.indexOf("tracedScrollToItem(\"LAYOUT-DRIFT-SNAP\", 0, 0)")
        assertTrue("the guard sits before the snap", screen.indexOf("ChatNestedScrollFollow.followMaySnap(") in 1 until snap)
        assertTrue(screen.contains("val away = ChatNestedScrollFollow.scrolledAwayAfterGesture(isNearBottom.value)"))
        val card = ProductionSources.read("ui/chat/UserAskCard.kt")
        val stop = card.indexOf(".nestedScroll(StopFlingAtEdgeConnection)")
        assertTrue("above the body's own scroll, so it receives its leftovers", stop in 1 until card.indexOf(".verticalScroll(bodyScroll)"))
        assertTrue("cap height kept", card.contains(".heightIn(max = bodyCap)"))
    }
}
