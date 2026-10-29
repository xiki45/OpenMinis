package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.util.expandToggleClickable

/**
 * [T-sched-card-metrics] The one set of measurements every task card in the
 * chat flow uses — the sub-agent card ([HelperToolBlock]) and the scheduled
 * task card ([ScheduledTaskCard]).
 *
 * The two cards used to carry their own copies, and they drifted: the
 * scheduled card set no line heights (so its three texts inherited the
 * theme's 24sp body line height), padded 10 instead of 12, and ended in a
 * Material TextButton whose 40dp minimum height set the card's height by
 * itself. Next to a sub-agent card it read as a different, much taller
 * component. Both now render through [TaskCardFrame], so they cannot drift
 * apart again.
 */
internal object TaskCardMetrics {
    val corner = 12.dp
    val outerVertical = 4.dp
    val paddingHorizontal = 14.dp
    val paddingVertical = 12.dp
    val iconDisc = 34.dp
    val iconGlyph = 13.dp
    val iconGap = 12.dp
    /** Head row to an expanded card's extra rows (iOS VStack spacing 10). */
    val belowGap = 10.dp
    val rowSpacing = 5.dp
    val chipGap = 6.dp
    val titleSize = 13.sp
    val titleLineHeight = 14.sp
    val subtitleSize = 11.sp
    val subtitleLineHeight = 12.sp
    val chipSize = 10.sp
    val chipLineHeight = 11.sp

    /** The trailing action disc (Stop / Start / Resume / Cancel task). */
    val actionDisc = 24.dp
    val actionGlyph = 14.dp
    val chevron = 16.dp
}

/**
 * The card skeleton: tinted rounded box, 34dp icon disc, a text column
 * ([content]: title row + one status line), an optional 24dp action disc and
 * the chevron. Height is set by the fixed metrics, never by a control.
 *
 * [below] is an expanded card's extra rows. They go UNDER the head row,
 * indented to the text column (iOS: `.padding(.leading, 46)`), so the icon and
 * chevron stay centred on the title and status lines, not on the whole card
 * (T-user-ask-resolved-expand).
 */
@Composable
internal fun TaskCardFrame(
    accent: Color,
    onClick: () -> Unit,
    onClickLabel: String?,
    modifier: Modifier = Modifier,
    /**
     * [ISS-017] True when a tap opens / shuts the card in place (the resolved
     * question card) rather than navigating: that tap gets no ripple, see
     * expandToggleClickable. Cards that open a detail sheet keep it.
     */
    expandsInPlace: Boolean = false,
    icon: @Composable () -> Unit,
    action: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(TaskCardMetrics.corner)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = TaskCardMetrics.outerVertical)
            .clip(shape)
            .background(accent.copy(alpha = 0.07f))
            .border(0.8.dp, accent.copy(alpha = 0.35f), shape)
            .then(
                if (expandsInPlace) {
                    Modifier.expandToggleClickable(onClickLabel = onClickLabel, pressedShape = RectangleShape, onClick = onClick)
                } else {
                    Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
                },
            )
            .padding(horizontal = TaskCardMetrics.paddingHorizontal, vertical = TaskCardMetrics.paddingVertical),
    ) {
        TaskCardHeadRow(accent, icon, action, content)
        if (below != null) {
            Column(
                modifier = Modifier.padding(start = TaskCardMetrics.iconDisc + TaskCardMetrics.iconGap, top = TaskCardMetrics.belowGap),
                content = below,
            )
        }
    }
}

/** Icon, text column, action and chevron, centred on each other. */
@Composable
private fun TaskCardHeadRow(
    accent: Color,
    icon: @Composable () -> Unit,
    action: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(TaskCardMetrics.iconDisc).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(TaskCardMetrics.iconGap))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TaskCardMetrics.rowSpacing),
            content = content,
        )
        Spacer(Modifier.width(4.dp))
        if (action != null) {
            action()
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = ChatColors.tertiaryText,
            modifier = Modifier.size(TaskCardMetrics.chevron),
        )
    }
}

/**
 * The height a line of this style really takes with CJK in it, on this device
 * and font. Measured on a Pixel 4a: with the same fontSize and lineHeight, a
 * line holding CJK (a fallback font) came out taller than a Latin one, so the
 * sub-agent card ("完成 …") was 6 px taller than a scheduled card titled
 * "t2-sub". Using this as each row's MINIMUM height makes the card height the
 * same whatever the text, without clipping anything (a fixed height clipped
 * the top of CJK glyphs).
 */
@Composable
private fun cjkLineHeight(fontSize: TextUnit, lineHeight: TextUnit, weight: FontWeight): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    return remember(fontSize, lineHeight, weight, density, base) {
        val style = base.merge(TextStyle(fontSize = fontSize, lineHeight = lineHeight, fontWeight = weight))
        with(density) { measurer.measure("\u56FDAg", style, maxLines = 1).size.height.toDp() }
    }
}

/** The title row (chip + title), never shorter than a CJK title line. */
@Composable
internal fun TaskCardTitleRow(content: @Composable RowScope.() -> Unit) {
    val title = cjkLineHeight(TaskCardMetrics.titleSize, TaskCardMetrics.titleLineHeight, FontWeight.Medium)
    val chip = cjkLineHeight(TaskCardMetrics.chipSize, TaskCardMetrics.chipLineHeight, FontWeight.SemiBold) + 2.dp
    Row(
        modifier = Modifier.heightIn(min = maxOf(title, chip)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TaskCardMetrics.chipGap),
        content = content,
    )
}

/** The status line, never shorter than a CJK status line (see [TaskCardTitleRow]). */
@Composable
internal fun TaskCardStatusRow(content: @Composable RowScope.() -> Unit) {
    val h = taskCardStatusLineHeight()
    Row(
        modifier = Modifier.heightIn(min = h),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * [T-scheduled-card-subline] The status row's height. A status text whose
 * content is arbitrary (the scheduled card's prompt fallback) pins itself to
 * it, so a glyph taller than CJK - a colour emoji - cannot make that card
 * taller than the sub-agent card beside it.
 */
@Composable
internal fun taskCardStatusLineHeight(): Dp =
    cjkLineHeight(TaskCardMetrics.subtitleSize, TaskCardMetrics.subtitleLineHeight, FontWeight.SemiBold)

/** The label pill before a task card's title ("Agent" / "Scheduled task"). */
@Composable
internal fun TaskCardChip(text: String, accent: Color) {
    Text(
        text,
        fontSize = TaskCardMetrics.chipSize,
        lineHeight = TaskCardMetrics.chipLineHeight,
        fontWeight = FontWeight.SemiBold,
        color = accent,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** A task card's title: one line, ellipsized. */
@Composable
internal fun TaskCardTitle(text: String) {
    Text(
        text,
        fontSize = TaskCardMetrics.titleSize,
        lineHeight = TaskCardMetrics.titleLineHeight,
        fontWeight = FontWeight.Medium,
        color = ChatColors.primaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A 24dp action disc in the card's action slot, labelled for TalkBack.
 * [enabled] false dims it and ignores taps.
 */
@Composable
internal fun TaskCardActionDisc(
    label: String,
    background: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.size(TaskCardMetrics.actionDisc).clip(CircleShape).background(background)
            .clickable(enabled = enabled, onClickLabel = label) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

/** Helper for a white glyph inside [TaskCardActionDisc]. */
@Composable
internal fun TaskCardActionGlyph(icon: ImageVector, tint: Color = Color.White) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(TaskCardMetrics.actionGlyph))
}
