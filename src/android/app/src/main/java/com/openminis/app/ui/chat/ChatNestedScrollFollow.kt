package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * [T-android-userask-edge-bounce] A scroller nested in the chat list (the
 * pending user_ask card's body) hands its leftover drag to the list once it
 * reaches its own edge. Compose moves the list for that with a raw delta: the
 * list's `isScrollInProgress` stays false and it emits no DragInteraction, so
 * every "is the user scrolling?" check the follow-bottom code has said no.
 * While a question waits the run still counts as streaming, so the
 * layout-drift safety net snapped the list back to the bottom a frame after
 * each push - finger pushes, net pulls back: the card "bounces back and forth
 * at its edge" (field report 2026-10-09; reproduced on a Pixel 4a, log
 * `scroll src=LAYOUT-DRIFT-SNAP` + `arm SUPPRESSED (streaming content
 * growth)` during the drag).
 *
 * The decisions, kept pure so they can be tested without a list.
 */
object ChatNestedScrollFollow {

    /**
     * How long after the last child-driven delta the list still counts as
     * being moved by the user. Covers the frames between two drag deltas and
     * the gap before the gesture's end is reported; the end itself clears the
     * state at once.
     */
    const val HOLD_MS = 300L

    /**
     * Whether a nested pre-scroll is a CHILD's user drag reaching the list.
     * The list's own drag also passes through the connection, but it already
     * reports itself ([listIsDragged]) and has its own handling.
     */
    fun isChildUserScroll(sourceIsUserInput: Boolean, deltaY: Float, listIsDragged: Boolean): Boolean =
        sourceIsUserInput && deltaY != 0f && !listIsDragged

    /** Whether the user is moving the list through a child right now. */
    fun childScrollActive(gestureOpen: Boolean, msSinceLastDelta: Long): Boolean =
        gestureOpen && msSinceLastDelta in 0 until HOLD_MS

    /**
     * Whether follow-bottom may snap the list back. Never while the user moves
     * it - whether by dragging the list or through a child.
     */
    fun followMaySnap(userScrolledAway: Boolean, listScrollInProgress: Boolean, childScrollActive: Boolean): Boolean =
        !userScrolledAway && !listScrollInProgress && !childScrollActive

    /**
     * What a finished child gesture leaves behind: the same rule as a finger
     * lifted from the list itself - away from the bottom means the user is
     * reading, near it means keep following.
     */
    fun scrolledAwayAfterGesture(nearBottom: Boolean): Boolean = !nearBottom
}

/**
 * [T-android-userask-edge-bounce] A fling inside a nested scroller stops at
 * that scroller's edge: its leftover velocity is consumed instead of flinging
 * the chat list behind it. A drag past the edge still scrolls the chat.
 */
object StopFlingAtEdgeConnection : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        // SideEffect = the fling's own deltas; UserInput (a drag) passes on.
        if (source == NestedScrollSource.UserInput) Offset.Zero else available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}
