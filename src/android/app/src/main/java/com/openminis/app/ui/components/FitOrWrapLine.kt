package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * [T-model-row-premature-wrap] One line of a model row: a text [lead] (model
 * name, or quota hint + model id) followed by fixed-size [tags] (quota pill,
 * Active badge, modality chips). Port of the iOS `ViewThatFits(in: .horizontal)`
 * pair in UnifiedModelPicker (b86aabda4, 74025d909):
 *  - when lead + tags fit the width, they share ONE line, the lead unwrapped;
 *  - otherwise the lead keeps the FULL width (and wraps only if it is longer
 *    than the whole line) and the tags move, together, to their own line below.
 *
 * A plain Row with `weight(1f, fill = false)` on the name (or a FlowRow) did
 * the opposite: the tags kept their width and the name wrapped into what was
 * left ("Claude Sonnet / 5 [14% used] [Active]"). A wrapped Text is only as
 * wide as its longest line, so the leftover showed up as empty space before
 * the quick-test button.
 *
 * [separator] (the "·" before modality chips) is drawn only in the one-line
 * form and only when there are tags, like iOS. No tags → just the lead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FitOrWrapLine(
    modifier: Modifier = Modifier,
    spacing: Dp = 4.dp,
    lineSpacing: Dp = 2.dp,
    separator: (@Composable () -> Unit)? = null,
    lead: @Composable RowScope.() -> Unit,
    tags: @Composable () -> Unit,
) {
    val leadContent: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
            content = lead,
        )
    }
    val separatorContent: @Composable () -> Unit = { separator?.invoke() }
    // A FlowRow, so that in the wrapped form an unusually long set of tags
    // (a model with all seven modality chips on a narrow screen) continues on
    // a further line instead of clipping the last chip. Its max intrinsic
    // width is the single-line width, which is what the fit check uses.
    val tagsContent: @Composable () -> Unit = {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(lineSpacing),
            itemVerticalAlignment = Alignment.CenterVertically,
            content = { tags() },
        )
    }
    Layout(
        contents = listOf(leadContent, separatorContent, tagsContent),
        modifier = modifier,
    ) { (leadSlot, sepSlot, tagsSlot), constraints ->
        val lead = leadSlot.single()
        val sep = sepSlot.firstOrNull()
        val tagRow = tagsSlot.single()
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)

        val tagsW = tagRow.maxIntrinsicWidth(Constraints.Infinity)
        val leadW = lead.maxIntrinsicWidth(Constraints.Infinity)
        val sepW = if (tagsW > 0) sep?.maxIntrinsicWidth(Constraints.Infinity) ?: 0 else 0
        val oneLine = FitOrWrapRules.fitsOnOneLine(
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else null,
            leadWidth = leadW,
            separatorWidth = if (sep != null && tagsW > 0) sepW else null,
            tagsWidth = tagsW,
            gap = gap,
        )

        if (tagsW == 0) {
            val lp = lead.measure(loose)
            return@Layout layout(constraints.constrainWidth(lp.width), constraints.constrainHeight(lp.height)) {
                lp.placeRelative(0, 0)
            }
        }

        if (oneLine) {
            val lp = lead.measure(loose.copy(maxWidth = leadW))
            val sp = if (sepW > 0) sep?.measure(Constraints()) else null
            val tp = tagRow.measure(Constraints(maxWidth = tagsW))
            val h = maxOf(lp.height, sp?.height ?: 0, tp.height)
            val w = lp.width + gap + (if (sp != null) sp.width + gap else 0) + tp.width
            layout(constraints.constrainWidth(w), constraints.constrainHeight(h)) {
                var x = 0
                lp.placeRelative(x, (h - lp.height) / 2)
                x += lp.width + gap
                if (sp != null) {
                    sp.placeRelative(x, (h - sp.height) / 2)
                    x += sp.width + gap
                }
                tp.placeRelative(x, (h - tp.height) / 2)
            }
        } else {
            val lp = lead.measure(loose)
            val tp = tagRow.measure(loose)
            val h = lp.height + lineSpacing.roundToPx() + tp.height
            layout(constraints.constrainWidth(maxOf(lp.width, tp.width)), constraints.constrainHeight(h)) {
                lp.placeRelative(0, 0)
                tp.placeRelative(0, lp.height + lineSpacing.roundToPx())
            }
        }
    }
}

/** The fit decision behind [FitOrWrapLine], kept pure for unit tests. */
internal object FitOrWrapRules {
    /**
     * True when lead, optional separator and tags fit side by side (with [gap]
     * between each) in [maxWidth]; an unbounded width always fits. No tags → one
     * line trivially (the lead alone wraps, if it must, across the full width).
     */
    fun fitsOnOneLine(maxWidth: Int?, leadWidth: Int, separatorWidth: Int?, tagsWidth: Int, gap: Int): Boolean {
        if (tagsWidth <= 0 || maxWidth == null) return true
        val needed = leadWidth + gap + (separatorWidth?.let { it + gap } ?: 0) + tagsWidth
        return needed <= maxWidth
    }
}
