package com.openminis.app.ui.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [T-user-ask-resolved-expand] Pure geometry for "a card the user opens or
 * shuts keeps its top in place" (iOS e563158e2 ToggleAnchorMath).
 *
 * The chat list is reverseLayout: an item's BOTTOM is pinned (to the item
 * below it, or to the viewport bottom), so a card that grows pushes its top
 * up and one that shrinks pulls it down. Scrolling by the height change
 * undoes exactly that. In LazyListState units under reverseLayout, a positive
 * scroll moves content down and a negative one moves it up, toward the newest
 * message (see ChatScreen's jump-to-bottom glide: "Negative = toward
 * newest/bottom").
 */
internal object ToggleAnchorMath {
    /** The scroll that keeps the card's top fixed when its height goes [oldHeight] -> [newHeight]. */
    fun keepTopScroll(oldHeight: Int, newHeight: Int): Float = (newHeight - oldHeight).toFloat()

    /**
     * After an EXPAND only: how far (px, >= 0) to move the content up so the
     * opened card's bottom is visible, never pushing its top above the
     * visible top. A collapse never scrolls back (iOS revealShift).
     */
    fun revealShift(cardTop: Float, cardBottom: Float, visibleTop: Float, visibleBottom: Float): Float {
        val overflow = cardBottom - visibleBottom
        if (overflow <= 0f) return 0f
        return minOf(overflow, cardTop - visibleTop).coerceAtLeast(0f)
    }

    /** Ease-in-out over [0, 1], the curve animateContentSize's default spring roughly follows. */
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
    }

    /** Height shown at progress [t] of a resize from [from] to [to]. */
    fun heightAt(from: Int, to: Int, t: Float): Int = from + Math.round((to - from) * ease(t))

    /**
     * The LazyColumn index of flat row [flatPos]: the list renders
     * `flatItems.asReversed()` after [leading] conditional items (the same
     * relationship as ChatScreen's previous-turn jump).
     */
    fun lazyIndex(flatPos: Int, flatSize: Int, leading: Int): Int = (flatSize - 1 - flatPos) + leading

    /** [leading] measured from one visible row: its lazy index and flat position. */
    fun leadingCount(lazyIndex: Int, flatPos: Int, flatSize: Int): Int = lazyIndex - (flatSize - 1 - flatPos)

    /**
     * The scroll offset that puts a row back where it was. LazyListItemInfo
     * offsets and scroll offsets share one axis (an item at scroll position
     * (i, s) has offset -s), so a row that sat at [offset] is requested at
     * -offset. Negative is fine: the next measure fills in the newer rows
     * below it.
     */
    fun restoreScrollOffset(offset: Int): Int = -offset
}

/**
 * The chat list's side of a top-anchored toggle. Provided to list items by
 * [LocalChatToggleAnchor]; null outside the chat list (a card then just
 * animates, as before).
 */
internal class ChatToggleAnchor(private val listState: LazyListState) {
    /** The LazyColumn's coordinates and the px hidden under its bottom reserve (composer / toolbar). */
    var listCoords: () -> LayoutCoordinates? = { null }
    var bottomInsetPx: Float = 0f
    var scope: CoroutineScope? = null

    /**
     * [T-toggle-anchor-snap-yield] True while a user toggle is moving the list
     * (and briefly after): ChatScreen's bottom drift snap stands down, or it
     * would undo each compensation step a frame later when pinned to the bottom.
     */
    @Volatile private var busyUntilMs = 0L
    val isBusy: Boolean get() = android.os.SystemClock.uptimeMillis() < busyUntilMs
    fun markBusy(forMs: Long = BUSY_TAIL_MS) {
        busyUntilMs = maxOf(busyUntilMs, android.os.SystemClock.uptimeMillis() + forMs)
    }

    /** A tapped fold row waiting for the list that opens / shuts it. */
    private class PendingRow(val key: String, val offset: Int, val leading: Int, val size: Int)
    private var pendingRow: PendingRow? = null

    /**
     * A folded tool row was tapped: remember where it is. Expanding inserts its
     * calls as separate rows BELOW it, and the reverseLayout list keeps the
     * row under them in place, so without this the tapped row is pushed up.
     */
    fun captureRow(key: String, rows: List<com.openminis.app.ui.chat.FlatChatItem>) {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val pos = rows.indexOfFirst { it.key == key }
        if (pos < 0) return
        pendingRow = PendingRow(key, info.offset, ToggleAnchorMath.leadingCount(info.index, pos, rows.size), rows.size)
    }

    /** The folded rows were rebuilt: request the tapped row's old place for the same remeasure. */
    fun restoreRow(rows: List<com.openminis.app.ui.chat.FlatChatItem>) {
        val p = pendingRow ?: return
        pendingRow = null
        val pos = rows.indexOfFirst { it.key == p.key }
        if (pos < 0) return
        val index = ToggleAnchorMath.lazyIndex(pos, rows.size, p.leading)
        if (index < 0) return
        markBusy()
        listState.requestScrollToItem(index, ToggleAnchorMath.restoreScrollOffset(p.offset))
        val added = rows.size - p.size
        if (added > 0) scope?.launch { revealRows(p.key, index, added) }
    }

    /** After a fold row opened: bring its last call into view, keeping the row itself visible. */
    private suspend fun revealRows(key: String, index: Int, added: Int) {
        withFrameNanos { }
        withFrameNanos { }
        val info = listState.layoutInfo
        val head = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val last = info.visibleItemsInfo.firstOrNull { it.index == index - added }
        // Axis: offsets grow from the list's bottom edge upward (reverseLayout),
        // so "below the visible bottom" is a negative offset.
        val blockBottom = last?.offset ?: Int.MIN_VALUE / 2
        val shift = ToggleAnchorMath.revealShift(
            cardTop = -(head.offset + head.size).toFloat(),
            cardBottom = -blockBottom.toFloat(),
            visibleTop = -(info.viewportEndOffset - info.afterContentPadding).toFloat(),
            visibleBottom = 0f,
        )
        if (shift > 0f) { markBusy(); listState.animateScrollBy(-shift) }
    }

    /** Undo the bottom pin for one height change. Called in a frame callback, before that frame's measure. */
    fun keepTop(oldHeight: Int, newHeight: Int) {
        markBusy()
        val d = ToggleAnchorMath.keepTopScroll(oldHeight, newHeight)
        if (d != 0f) listState.dispatchRawDelta(d)
    }

    /** One short scroll bringing an opened card's bottom into view (top stays visible). */
    suspend fun reveal(card: LayoutCoordinates?) {
        val list = listCoords() ?: return
        if (card == null || !card.isAttached || !list.isAttached) return
        val box = list.localBoundingBoxOf(card, clipBounds = false)
        val shift = ToggleAnchorMath.revealShift(box.top, box.bottom, 0f, list.size.height - bottomInsetPx)
        if (shift > 0f) { markBusy(); listState.animateScrollBy(-shift) }
    }
}

internal val LocalChatToggleAnchor = staticCompositionLocalOf<ChatToggleAnchor?> { null }

/** How long after its last step a toggle keeps the drift snap away. */
private const val BUSY_TAIL_MS = 450L

/**
 * A card's own open / shut animation with its top held in place.
 *
 * animateContentSize cannot do this: it resizes during measure, and the list's
 * compensating scroll would land a frame later, so the top would wobble. Here
 * one frame callback sets both the shown height and the compensating scroll,
 * and the frame's measure applies them together.
 *
 * Apply [modifier] to the card's outermost layout. Its content is measured at
 * its natural height; while [shown] is pinned, the card reports that height
 * instead and clips.
 */
internal class TopAnchoredResize(private val durationMs: Long = 260) {
    private var natural = -1
    private var shown: Int? by mutableStateOf(null)
    private var job: Job? = null
    /** The card's coordinates, for the reveal. */
    var coords: LayoutCoordinates? = null

    val modifier: Modifier = Modifier
        .clipToBounds()
        .layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            natural = p.height
            val h = shown ?: p.height
            layout(p.width, h) { p.place(0, 0) }
        }

    /** Flip the card's state with [flip] and animate the height change, top fixed. */
    fun toggle(scope: CoroutineScope, anchor: ChatToggleAnchor?, flip: () -> Unit) {
        val from = shown ?: natural
        job?.cancel()
        if (from < 0) { flip(); return }
        shown = from
        anchor?.markBusy(durationMs + 450)
        flip()
        // A newer toggle cancels this one and pins its own starting height, so
        // a cancelled run leaves [shown] alone.
        job = scope.launch {
            withFrameNanos { }   // this frame measures the new content (height still pinned)
            withFrameNanos { }   // its natural height is known from here on
            val to = natural
            var current = from
            val start = withFrameNanos { it }
            while (true) {
                val done = withFrameNanos { now ->
                    val t = ((now - start) / 1_000_000f) / durationMs
                    val h = if (t >= 1f) to else ToggleAnchorMath.heightAt(from, to, t)
                    // Same frame callback: the scroll and the height reach this frame's measure together.
                    anchor?.keepTop(current, h)
                    current = h
                    shown = h
                    t >= 1f
                }
                if (done) break
            }
            shown = null
            if (to > from) {
                withFrameNanos { }
                anchor?.reveal(coords)
            }
        }
    }
}
