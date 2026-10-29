package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput

/**
 * [T-android-usage-capsule-blank-tap-margin] Decides whether a tap on a reply's
 * text block is "blank" - the gesture that reveals the message's usage capsule.
 *
 * It used to be blank whenever the tap missed the bounding box of the character
 * nearest to it, which made the leading between two lines, the gap between two
 * words and a tap a few dp beside a link all blank: a slightly-off tap on a link,
 * or the start of a selection, popped the capsule instead. Now a tap is blank
 * only when it is clearly away from content. Pure (geometry in px, thresholds in
 * dp scaled by [density]) so it is unit-tested on the JVM.
 */
internal object BlankTapGeometry {
    /**
     * Horizontal distance a tap must keep from a line's text to be blank: a
     * finger's contact patch is ~7-10mm wide, so a tap aimed at the last word of
     * a line routinely lands a dozen dp past it.
     */
    const val TEXT_CLEARANCE_DP = 24f

    /**
     * Vertical reach of a line beyond its own box: a tap in the leading just
     * below a short line but above a long one must not count as blank beside
     * the short one.
     */
    const val LINE_VERTICAL_CLEARANCE_DP = 8f

    /**
     * Keep-out around links and inline code: a near miss there is far more
     * likely an attempt to hit them than a request for the usage capsule, so it
     * does nothing at all.
     */
    const val INTERACTIVE_CLEARANCE_DP = 20f

    enum class Kind {
        /** Clearly away from text and interactive spans: reveal the capsule. */
        BLANK,
        /** On or beside text: not blank; links / code act only when ON their glyphs. */
        TEXT,
        /** Near (not on) a link or inline code: do nothing. */
        NEAR_INTERACTIVE,
        /** A selection is active, or the press was a long press: never blank. */
        SUPPRESSED,
        /**
         * [T-414-usage-tap-band] Blank, but not within the five lines of text
         * above the usage capsule (or this text has no such band): do nothing.
         */
        OUTSIDE_BAND,
    }

    /**
     * [T-414-usage-tap-band] Where a blank tap may reveal the capsule, in the
     * tapped block's own px coordinates (see [UsageTapBand]).
     */
    sealed class Band {
        /** No restriction: the rules as they were before #414. Not used by the chat. */
        object Anywhere : Band()
        /** This text has no response zone: not right before the capsule, or the anchor is unknown. */
        object None : Band()
        /** From [top] to [bottom], both inclusive. [bottom] is the capsule's position. */
        data class Range(val top: Float, val bottom: Float) : Band()

        fun contains(y: Float): Boolean = when (this) {
            Anywhere -> true
            None -> false
            is Range -> y >= top && y <= bottom
        }
    }

    /**
     * @param lines each line's ink box (left/right = the line's text extent,
     *   top/bottom = its line box), in the block's own px coordinates.
     * @param interactive the box of every url / inline-code span, one per line
     *   it covers (a wrapped link is several boxes, not one wide rectangle).
     * @param band [T-414-usage-tap-band] an extra condition on a tap the rules
     *   above already found blank: outside the band it is [Kind.OUTSIDE_BAND].
     *   It never changes [Kind.TEXT] / [Kind.NEAR_INTERACTIVE] /
     *   [Kind.SUPPRESSED], so a link outside the band still opens.
     */
    fun decide(
        tap: Offset,
        lines: List<Rect>,
        interactive: List<Rect>,
        density: Float,
        selectionActive: Boolean,
        longPress: Boolean,
        band: Band = Band.Anywhere,
    ): Kind {
        if (selectionActive || longPress) return Kind.SUPPRESSED
        val keepOut = INTERACTIVE_CLEARANCE_DP * density
        for (r in interactive) {
            if (r.contains(tap)) return Kind.TEXT
        }
        for (r in interactive) {
            if (r.inflate(keepOut).contains(tap)) return Kind.NEAR_INTERACTIVE
        }
        val margin = TEXT_CLEARANCE_DP * density
        val reach = LINE_VERTICAL_CLEARANCE_DP * density
        for (line in lines) {
            // An empty line (left == right) has no text to keep away from.
            if (line.right <= line.left) continue
            val inBand = tap.y >= line.top - reach && tap.y <= line.bottom + reach
            if (!inBand) continue
            if (tap.x > line.left - margin && tap.x < line.right + margin) return Kind.TEXT
        }
        return if (band.contains(tap.y)) Kind.BLANK else Kind.OUTSIDE_BAND
    }
}

/**
 * [T-android-usage-capsule-blank-tap-margin] The usage row's own tap target:
 * it only DISMISSES a capsule that is showing, and only within its own bounds.
 *
 * Device finding: the hidden row (zero height, or a 12dp strip in the sub-agent
 * transcript) still took taps, because Compose stretches a small pointer target
 * to the 48dp minimum touch size. That made the row catch taps up to ~24dp
 * above it - beside the reply's last line - and reveal the capsule with none of
 * BlankTapGeometry's rules (no clearance from text or links, no selection
 * check). Revealing is the text's job now; this row no longer expands its hit
 * area and is not a target at all while the capsule is hidden.
 */
@androidx.compose.runtime.Composable
internal fun UsageCapsuleRow(
    shown: Boolean,
    onToggle: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    val base = androidx.compose.ui.platform.LocalViewConfiguration.current
    val exact = androidx.compose.runtime.remember(base) {
        object : androidx.compose.ui.platform.ViewConfiguration by base {
            override val minimumTouchTargetSize = androidx.compose.ui.unit.DpSize.Zero
        }
    }
    val currentToggle by androidx.compose.runtime.rememberUpdatedState(onToggle)
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalViewConfiguration provides exact,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = modifier.then(
                if (shown) {
                    androidx.compose.ui.Modifier.pointerInput(Unit) {
                        detectTapGestures { currentToggle() }
                    }
                } else {
                    androidx.compose.ui.Modifier
                },
            ),
        ) {
            // The capsule itself must keep the app's normal view configuration.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalViewConfiguration provides base,
                content = content,
            )
        }
    }
}
