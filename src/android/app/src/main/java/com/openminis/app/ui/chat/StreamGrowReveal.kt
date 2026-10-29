package com.openminis.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntSize

/**
 * [T-android-stream-grow-reveal] A streaming reply's new lines glide in over
 * 0.2 s instead of appearing in one frame.
 *
 * The chat list is `reverseLayout`: while it is pinned to the bottom, the
 * newest row's bottom edge is the anchor. When a flush makes a row taller,
 * every row above it moves up by the whole growth in a single frame and the
 * new lines are simply there — the "stiff" stream next to iOS.
 *
 * iOS (`NoAnimationCollectionView.layoutSubviews`, T-ios-stream-grow-anim)
 * lets the cell frames land at once and animates only the contentOffset over
 * 0.20 s ease-out, so the whole conversation slides up in lock-step and the
 * new lines rise out from under the composer. A reversed LazyColumn has no
 * offset left to animate at the pinned bottom, so the live reply's rows
 * animate their own height instead: content top-aligned and clipped, the
 * reported height easing from the old value to the new one. With the bottom
 * edge anchored, the rows above glide up together and the new lines are
 * uncovered from the bottom — the same picture. A row that appears empty and
 * then fills (a new paragraph's first measure is 0 px high) opens from 0 the
 * same way.
 *
 * Compose's own `animateContentSize` does this. A hand-written layout node
 * that eased a reported height (the first version of this change) measured
 * fine frame by frame on a Pixel 6, but the list did not always re-place its
 * rows between flushes: a paragraph drew over the one below it and the whole
 * growth landed in one frame at the next flush. With animateContentSize plus
 * the re-pin below, one-line jumps on the same device and prompt shape went
 * from ~16 per reply to none, with no overlap.
 *
 * Only the live reply's rows carry it (the caller applies it only while that
 * reply streams), so history, cold-loaded sessions and finished replies keep
 * their plain layout and cost nothing. The duration matches the VM flush
 * cadence (200 ms below 500 chars), as iOS pairs its 0.2 s flush tier with
 * its glide, so back-to-back flushes read as one continuous motion.
 */
internal fun Modifier.streamGrowReveal(): Modifier = this
    .clipToBounds()
    .animateContentSize(
        animationSpec = tween<IntSize>(STREAM_GROW_DURATION_MS, easing = STREAM_GROW_EASE_OUT),
        alignment = Alignment.TopStart,
    )

/** iOS: `UIView.animate(withDuration: 0.20 …)`. */
internal const val STREAM_GROW_DURATION_MS = 200

/** iOS `.curveEaseOut` (Core Animation's easeOut control points). */
internal val STREAM_GROW_EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * [T-android-stream-grow-reveal] Whether the list should re-pin to the bottom
 * because rows of the live reply just appeared there.
 *
 * A reversed LazyColumn keeps its scroll position by KEY: when a new row is
 * inserted at the bottom while the list sits at (0, 0), the previous bottom
 * row keeps its place and the new one lands BELOW the viewport (the
 * "fIdx=1..5" the follow loop then has to glide back from). For a row of the
 * streaming reply that arrives while following, the screen instead re-pins
 * with `requestScrollToItem(0, 0)` in the same pass, and the row opens from 0
 * through [streamGrowReveal] — the same 0.2 s glide as text growth, iOS's one
 * motion for both.
 *
 * Deliberately narrow; anything else keeps today's behaviour:
 *  - every new key must belong to the live reply (a send also inserts the
 *    user bubble, and the send path owns that scroll);
 *  - the previous bottom row must still be there (the typing row being
 *    replaced by the first block is a swap, not growth);
 *  - at most [MAX_NEW_ROWS] rows at once (a reconnect / resync dump is not a
 *    stream);
 *  - the first list a session shows is never "new".
 */
internal class StreamGrowRevealTracker {
    data class Row(val key: String, val ownerMessageId: String?)

    private var previousKeys: Set<String>? = null
    private var previousNewestKey: String? = null

    /** Rows in display order, oldest first; [pinned] = list at (0, 0) and following. */
    fun onRows(rows: List<Row>, liveMessageId: String?, pinned: Boolean): Boolean {
        val before = previousKeys
        val newestBefore = previousNewestKey
        val keys = rows.mapTo(HashSet(rows.size)) { it.key }
        previousKeys = keys
        previousNewestKey = rows.lastOrNull()?.key
        if (liveMessageId == null || before == null || !pinned) return false

        val added = rows.filter { it.key !in before }
        if (added.isEmpty() || added.size > MAX_NEW_ROWS) return false
        return added.all { it.ownerMessageId == liveMessageId } &&
            newestBefore != null && newestBefore in keys &&
            // The new rows are the bottom of the list, not something inserted above it.
            rows.takeLast(added.size).map { it.key } == added.map { it.key }
    }

    companion object {
        const val MAX_NEW_ROWS = 3
    }
}

/** The reply a row belongs to (the id before any `#` split suffix); null for user-side rows. */
internal fun FlatChatItem.growOwnerMessageId(): String? = when (this) {
    is FlatChatItem.UserBubble,
    is FlatChatItem.AgentCallbackCard,
    is FlatChatItem.ScheduledTaskCardItem -> null
    is FlatChatItem.AssistantHeader -> messageId
    is FlatChatItem.AssistantText -> messageId
    is FlatChatItem.AssistantMarkdownBlock -> messageId
    is FlatChatItem.AssistantThinking -> messageId
    is FlatChatItem.AssistantToolUse -> messageId
    is FlatChatItem.AssistantToolGroup -> messageId
    is FlatChatItem.AssistantInfo -> messageId
    is FlatChatItem.AssistantTyping -> messageId
    is FlatChatItem.AssistantError -> messageId
    is FlatChatItem.AssistantUsage -> messageId
    is FlatChatItem.AssistantLegacyContent -> messageId
}?.substringBefore('#')
