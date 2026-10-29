package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.isSpecified

/**
 * [T-414-usage-tap-band] openminis/openminis#414: a blank tap anywhere in a
 * reply revealed its usage capsule, so reading a long reply set it off by
 * accident. Product rule (same on iOS): the capsule sits right after the
 * reply's final text; only a blank tap inside the band of FIVE LINES OF TEXT
 * directly above the capsule reveals it. Blank taps anywhere else do nothing.
 *
 * - Only the text block that is the message's LAST block has a band. A reply
 *   that ends with a tool or thinking block has no response zone at all.
 * - The band's bottom is the bottom of that text block's last row (where the
 *   capsule is); its top is 5 x the height of the block's last visual line
 *   above it. A block shorter than that responds over its whole height (taps
 *   only arrive from the block's own text).
 * - Every reply has its own band, history included.
 *
 * The pure rules are here; [UsageTapBandRegistry] does the coordinate work at
 * tap time, because one text block is several list rows and several Text nodes.
 */
internal object UsageTapBand {
    /** Height of the band, in lines of the block's last line. */
    const val LINES = 5

    /**
     * Whether a message whose last block has [lastKind] ends with reply text,
     * i.e. the capsule directly follows a text block. An empty text block
     * renders nothing, so it does not count.
     */
    fun endsWithText(lastKind: String?, lastHasContent: Boolean): Boolean =
        lastKind == "text" && lastHasContent

    /**
     * The height one line really takes at the end of a text node, in px.
     *
     * It is the line height the node was laid out with ([styleLineHeightPx]:
     * the text style's own value converted by the layout's density and font
     * scale, so it follows the user's font size), and never less than the
     * last line's box. The box alone is not enough: MdText trims the outer
     * half-leadings (LineHeightStyle.Trim.Both), so the last line's box - and
     * the first line's, hence the top-to-top advance of a two-line text - is
     * shorter than a line really is.
     *
     * Without a line height in the style, the last line's box is the unit.
     * When line heights differ inside a block, it is the LAST line's (rule 2).
     */
    fun lastLineHeightPx(lastLineTop: Float?, lastLineBottom: Float?, styleLineHeightPx: Float?): Float {
        val box = if (lastLineTop != null && lastLineBottom != null) lastLineBottom - lastLineTop else 0f
        return maxOf(box, styleLineHeightPx?.takeIf { it > 0f } ?: 0f)
    }

    /**
     * The band in the tapped text's own px coordinates. [anchorBottomPx] is
     * the capsule's position (the bottom of the final text block's last row)
     * in those coordinates; null or a non-positive line height means the
     * anchor is unknown, and then nothing responds.
     */
    fun band(anchorBottomPx: Float?, lastLineHeightPx: Float): BlankTapGeometry.Band {
        if (anchorBottomPx == null || anchorBottomPx.isNaN() || !(lastLineHeightPx > 0f)) return BlankTapGeometry.Band.None
        return BlankTapGeometry.Band.Range(top = anchorBottomPx - LINES * lastLineHeightPx, bottom = anchorBottomPx)
    }
}

/** Which message's final text block a list row belongs to, and whether it is that block's last row. */
internal data class UsageTapBandRow(val messageId: String, val isLastRow: Boolean)

/** Set around the rows of a reply's final text block; null everywhere else (no response zone). */
internal val LocalUsageTapBandRow = compositionLocalOf<UsageTapBandRow?> { null }

/** One per chat list. Absent on screens that do not use the band (taps then follow the pre-#414 rules). */
internal val LocalUsageTapBandRegistry = staticCompositionLocalOf<UsageTapBandRegistry?> { null }

/**
 * Where each reply's capsule anchor is right now: the last row of its final
 * text block, and the text nodes in that row. Read only at tap time; written
 * from composition / layout callbacks, all on the main thread.
 */
internal class UsageTapBandRegistry {
    /** A text node of a last row. Providers read plain holders, so nothing here invalidates anything. */
    class TextNode(val coords: () -> LayoutCoordinates?, val layout: () -> TextLayoutResult?)

    private val lastRows = HashMap<String, LayoutCoordinates>()
    private val lastRowTexts = HashMap<String, ArrayList<TextNode>>()

    fun setLastRow(messageId: String, coords: LayoutCoordinates) { lastRows[messageId] = coords }

    fun clearLastRow(messageId: String, coords: LayoutCoordinates?) {
        // A newer row for the same message may already have replaced it.
        if (coords == null || lastRows[messageId] === coords) lastRows.remove(messageId)
    }

    fun addText(messageId: String, node: TextNode) { lastRowTexts.getOrPut(messageId) { ArrayList() }.add(node) }

    fun removeText(messageId: String, node: TextNode) {
        val list = lastRowTexts[messageId] ?: return
        list.remove(node)
        if (list.isEmpty()) lastRowTexts.remove(messageId)
    }

    /**
     * The band for a tap on a text node at [tapped], laid out as [tappedLayout],
     * in that node's own coordinates.
     *
     * [row] null: the text is not in a final text block -> no response zone.
     * The anchor is the bottom of the block's last row. That row may be off
     * screen (not composed) when the tapped row is an earlier one: the capsule
     * is then out of view too, and nothing responds.
     */
    fun bandFor(row: UsageTapBandRow?, tapped: LayoutCoordinates?, tappedLayout: TextLayoutResult): BlankTapGeometry.Band {
        if (row == null || tapped == null || !tapped.isAttached) return BlankTapGeometry.Band.None
        val last = lastRows[row.messageId]?.takeIf { it.isAttached } ?: return BlankTapGeometry.Band.None
        val anchorBottom = runCatching {
            tapped.localPositionOf(last, Offset(0f, last.size.height.toFloat())).y
        }.getOrNull() ?: return BlankTapGeometry.Band.None
        // The unit is the last line of the block: the lowest text node of the
        // last row. A last row with no text node (a code block, an image)
        // falls back to the tapped text's own last line.
        val unitLayout = lastRowTexts[row.messageId]
            ?.mapNotNull { n -> n.coords()?.takeIf { it.isAttached }?.let { c -> n.layout()?.let { l -> c to l } } }
            ?.maxByOrNull { (c, _) -> runCatching { tapped.localPositionOf(c, Offset(0f, c.size.height.toFloat())).y }.getOrDefault(Float.NEGATIVE_INFINITY) }
            ?.second
            ?: tappedLayout
        return UsageTapBand.band(anchorBottom, lastLineHeightPx(unitLayout))
    }

    companion object {
        /** [UsageTapBand.lastLineHeightPx] fed from a real layout. */
        fun lastLineHeightPx(layout: TextLayoutResult): Float {
            val last = layout.lineCount - 1
            val style = layout.layoutInput.style.lineHeight
            val stylePx = if (style.isSpecified && style.isSp) with(layout.layoutInput.density) { style.toPx() } else null
            return UsageTapBand.lastLineHeightPx(
                lastLineTop = if (last >= 0) layout.getLineTop(last) else null,
                lastLineBottom = if (last >= 0) layout.getLineBottom(last) else null,
                styleLineHeightPx = stylePx,
            )
        }
    }
}

/**
 * Wraps one list row of a reply's text. [inFinalTextBlock] rows get a band
 * scope; the block's last row also reports where it is (the capsule's anchor).
 * Any other row has no response zone.
 *
 * [T-android-freeze-edge-flash-regress] Every state composes the SAME shape -
 * one provider, one Box, one `content()` call site - and only the VALUES
 * (the provided row, the Box's modifier) depend on the flags. The streaming
 * tail row flips [isLastRow] true -> false the moment the next paragraph
 * starts, which is also the moment its fragment freezes; and a text block
 * stops being [inFinalTextBlock] when a tool call follows it. With a
 * branch per state (bare `content()` vs `Box { content() }`) each flip moved
 * the content to a different group, so Compose disposed and rebuilt the whole
 * markdown row: MarkdownBlock lost its live-parse carry-over and painted the
 * raw-markdown preview (a rendered table back to `|---|` pipes) for the length
 * of an off-main parse - the stream's flash. The Box is a pass-through layout
 * for rows that report nothing.
 */
@Composable
internal fun UsageTapBandRowScope(
    messageId: String,
    inFinalTextBlock: Boolean,
    isLastRow: Boolean,
    content: @Composable () -> Unit,
) {
    val registry = LocalUsageTapBandRegistry.current
    val inBand = inFinalTextBlock && registry != null
    // The flattener may suffix "#n" to keep keys unique; the capsule is keyed by message.
    val id = messageId.substringBefore('#')
    val row = remember(inBand, id, isLastRow) { if (inBand) UsageTapBandRow(id, isLastRow) else null }
    val reportsPosition = inBand && isLastRow
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    CompositionLocalProvider(LocalUsageTapBandRow provides row) {
        Box(
            modifier = if (reportsPosition && registry != null) {
                Modifier.onGloballyPositioned {
                    holder[0] = it
                    registry.setLastRow(id, it)
                }
            } else {
                Modifier
            },
        ) { content() }
    }
    // Keyed on reportsPosition: a row that stops being the last one clears the
    // anchor it set, as leaving the old last-row branch did.
    DisposableEffect(registry, id, reportsPosition) {
        onDispose { if (reportsPosition) registry?.clearLastRow(id, holder[0]) }
    }
}
