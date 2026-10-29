package com.openminis.app.ui.chat

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [FIX-261022-07 / OpenMinis#443, #472] Keep the text a scrolled-away reader
 * is looking at where it is.
 *
 * The chat list is reverseLayout: the bottom-most visible row (the anchor,
 * `firstVisibleItemIndex`) keeps its BOTTOM edge in place, and everything
 * above it is laid out upward from there. That is right while the list
 * follows the bottom, and wrong once the user has scrolled up to read:
 *  - the anchor row growing (the streaming paragraph, a tool row filling in)
 *    pushes its own top and every row above it up - measured on an emulator
 *    with one long streamed paragraph: the text moved up 126-264 px every
 *    0.6 s with userScrolledAway=true and no programmatic scroll at all;
 *  - the list's viewport getting shorter (the keyboard opening, the composer
 *    growing a line) moves the anchor's bottom edge up, and the whole
 *    conversation with it - the keyboard pushed the message being read off
 *    the screen (#472).
 * Both are undone by scrolling by the change, so the content keeps its
 * distance from the list's TOP edge instead (iOS keeps contentOffset).
 *
 * Axis: in LazyListState units under reverseLayout a positive scroll moves the
 * content down (toward older messages), the same convention as
 * [ToggleAnchorMath].
 */
internal object ReadingAnchorMath {
    /**
     * The anchor's scroll offset that keeps the content in place when the
     * viewport height goes [oldHeight] -> [newHeight]: a viewport shorter by d
     * moved the anchor's bottom up by d, so d more of the anchor goes below it.
     * Negative is fine (LazyList moves on to the newer rows).
     */
    fun offsetAfterViewportResize(offset: Int, oldHeight: Int, newHeight: Int): Int =
        offset + (oldHeight - newHeight)

    /** Whether a viewport height change should be compensated. */
    fun shouldKeepOnResize(oldHeight: Int, newHeight: Int, readerAway: Boolean): Boolean =
        readerAway && oldHeight > 0 && newHeight > 0 && oldHeight != newHeight

    /**
     * Whether a row's new measured height must be held back for one frame (and
     * then shown together with the compensating scroll): only the anchor row,
     * only while the reader is away, and only once its previous height is known.
     */
    fun shouldHoldGrowth(reported: Int, natural: Int, isAnchor: Boolean, readerAway: Boolean): Boolean =
        readerAway && isAnchor && reported >= 0 && natural != reported
}

/**
 * The list-side controller. [readerAway] is read without observation in
 * measure: userScrolledAway, and no drag / fling / programmatic scroll in
 * progress (a moving list is the user's or another mover's, never ours).
 */
internal class ReadingAnchor(
    private val listState: LazyListState,
    private val toggleAnchor: ChatToggleAnchor,
) {
    var readerAway: () -> Boolean = { false }
    /**
     * For a viewport resize: the reader is away, or a finger is on the list -
     * scroll-to-dismiss closes the keyboard at the start of a drag, and the
     * text under the finger must not jump by the keyboard's height. A drag is
     * not interrupted by the request (its UserInput priority outranks the
     * stopScroll requestScrollToItem issues).
     */
    var keepOnResize: () -> Boolean = { false }
    var scope: CoroutineScope? = null

    // A card or fold row the user is opening / shutting keeps its own top
    // (ToggleAnchor); compensating its frames again would move it twice.
    private fun away(): Boolean = Snapshot.withoutReadObservation {
        readerAway() && !listState.isScrollInProgress && !toggleAnchor.isBusy
    }

    private fun anchorKey(): Any? = Snapshot.withoutReadObservation {
        listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
    }

    // ── Viewport resize (#472: keyboard / composer height) ──────────────

    private var lastViewportHeight = -1

    /**
     * On the LazyColumn: before the list measures with a new height, request
     * the anchor offset that keeps the content in place. requestScrollToItem
     * only invalidates the list's measure scope, so asked here, in the parent
     * measure, it applies to the list's measure of this same pass - no frame
     * shows the conversation moved.
     */
    val viewportModifier: Modifier = Modifier.layout { measurable, constraints ->
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else -1
        val old = lastViewportHeight
        lastViewportHeight = height
        val keep = Snapshot.withoutReadObservation { keepOnResize() && !toggleAnchor.isBusy }
        if (ReadingAnchorMath.shouldKeepOnResize(old, height, keep)) {
            Snapshot.withoutReadObservation {
                listState.requestScrollToItem(
                    listState.firstVisibleItemIndex,
                    ReadingAnchorMath.offsetAfterViewportResize(listState.firstVisibleItemScrollOffset, old, height),
                )
            }
        }
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    // ── Anchor row growth (#443: streaming text, tool rows) ─────────────

    /**
     * One row's side. The list reads a row's height in its own measure, after
     * it has applied the scroll position, so a compensating scroll can only
     * land a frame later. The row therefore reports its previous height for
     * that one frame (content top-aligned, new lines below the old bottom,
     * which is at or under the viewport's bottom edge), and a frame callback
     * then applies the new height and the scroll together, before the next
     * measure - the same pairing as [TopAnchoredResize].
     */
    inner class RowHold(private val key: Any) {
        private var reported = -1
        private var natural = -1
        private var pending = false
        private val release = mutableIntStateOf(0)

        val modifier: Modifier = Modifier.layout { measurable, constraints ->
            release.intValue // a released hold re-measures this row
            val placeable = measurable.measure(constraints)
            natural = placeable.height
            val height = when {
                pending -> reported
                ReadingAnchorMath.shouldHoldGrowth(reported, natural, anchorKey() == key, away()) -> {
                    hold()
                    reported
                }
                else -> natural.also { reported = it }
            }
            layout(placeable.width, height) { placeable.place(0, 0) }
        }

        private fun hold() {
            val s = scope ?: return
            pending = true
            s.launch {
                withFrameNanos {
                    val from = reported
                    val to = natural
                    // Still the anchor of a reader who is away: scroll by the
                    // change. Otherwise (they started a drag, or tapped to the
                    // bottom) just let the height through. Not
                    // ChatToggleAnchor.keepTop: that marks the list busy for
                    // a toggle, which would stand this down for the next flush.
                    if (anchorKey() == key && away()) {
                        val d = ToggleAnchorMath.keepTopScroll(from, to)
                        if (d != 0f) listState.dispatchRawDelta(d)
                    }
                    reported = to
                    pending = false
                    release.intValue++
                }
            }
        }
    }
}

/**
 * [#472] Whether something is recording audio right now - while the keyboard
 * is up, that is the IME's voice typing (Gboard, WeChat, Doubao all record
 * through the platform). Android offers no "the IME is dictating" signal,
 * but since API 29 every app may see the active recording configurations
 * (other apps' client details anonymised). Hiding the keyboard then would end
 * the dictation, so a scroll of the list leaves it up.
 */
internal object ImeDictation {
    fun recordingActive(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.activeRecordingConfigurations?.isNotEmpty() == true
    }.getOrDefault(false)
}
