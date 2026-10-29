package com.openminis.app.ui.chat.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.LocalChatPalette
import kotlinx.coroutines.delay

/**
 * Room the previews leave above their content so the format switch (10dp from the
 * top, ~38dp tall) covers nothing until the user scrolls.
 */
internal const val FORMAT_SWITCH_CLEARANCE = 56f

/**
 * [T-multi-select-share] The preview's floating "Long Image | PDF" switch: a floating
 * capsule with a sliding selection pill, switched by tapping a segment or swiping
 * across it (left → PDF, right → Long Image). When the selection is too long for one
 * image, "Long Image" is dimmed and a tap on it only explains why (iOS
 * ChatShareFormatSwitch).
 */
@Composable
internal fun ChatShareFormatSwitch(
    format: ChatExportOptions.Format,
    imageUnavailable: Boolean,
    onSelect: (ChatExportOptions.Format) -> Unit,
    onBlockedImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalChatPalette.current
    val density = LocalDensity.current
    fun select(value: ChatExportOptions.Format) {
        if (value == ChatExportOptions.Format.IMAGE && imageUnavailable) {
            onBlockedImage()
            return
        }
        if (value != format) onSelect(value)
    }
    // Segment frames, for the pill that slides between them.
    val frames = remember { mutableStateMapOf<ChatExportOptions.Format, SegmentFrame>() }
    val frame = frames[format]
    val pillX by animateDpAsState(with(density) { (frame?.x ?: 0f).toDp() }, spring(dampingRatio = 0.8f, stiffness = 500f), label = "pillX")
    val pillW by animateDpAsState(with(density) { (frame?.width ?: 0f).toDp() }, spring(dampingRatio = 0.8f, stiffness = 500f), label = "pillW")
    Box(
        modifier = modifier
            .chatShareFloatingSurface(palette.isDark, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
            .pointerInput(format, imageUnavailable) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        val threshold = 20.dp.toPx()
                        if (total < -threshold) select(ChatExportOptions.Format.PDF)
                        if (total > threshold) select(ChatExportOptions.Format.IMAGE)
                    },
                ) { change, amount ->
                    total += amount
                    change.consume()
                }
            }
            .padding(3.dp),
    ) {
        if (frame != null) {
            Box(
                modifier = Modifier
                    .offset(x = pillX)
                    .width(pillW)
                    .height(with(density) { frame.height.toDp() })
                    .shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
                    .clip(CircleShape)
                    .background(palette.background.copy(alpha = 0.9f)),
            )
        }
        Row {
            FormatSegment(
                ChatExportOptions.Format.IMAGE, stringResource(R.string.chat_share_long_image), Icons.Outlined.Image,
                selected = format == ChatExportOptions.Format.IMAGE,
                blocked = imageUnavailable,
                onClick = { select(ChatExportOptions.Format.IMAGE) },
                onFrame = { frames[ChatExportOptions.Format.IMAGE] = it },
            )
            FormatSegment(
                ChatExportOptions.Format.PDF, stringResource(R.string.chat_share_pdf), Icons.Outlined.Description,
                selected = format == ChatExportOptions.Format.PDF,
                blocked = false,
                onClick = { select(ChatExportOptions.Format.PDF) },
                onFrame = { frames[ChatExportOptions.Format.PDF] = it },
            )
        }
    }
}

/** A segment's place in the switch, in px, for the pill that slides between them. */
private data class SegmentFrame(val x: Float, val width: Float, val height: Float)

@Composable
private fun FormatSegment(
    value: ChatExportOptions.Format,
    title: String,
    icon: ImageVector,
    selected: Boolean,
    blocked: Boolean,
    onClick: () -> Unit,
    onFrame: (SegmentFrame) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val color = if (selected) accent else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .onGloballyPositioned { onFrame(SegmentFrame(it.positionInParent().x, it.size.width.toFloat(), it.size.height.toFloat())) }
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .alpha(if (value == ChatExportOptions.Format.IMAGE && blocked) 0.35f else 1f)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Text(
            text = title,
            color = color,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** One toast: its text and whether it is a tip (info icon) rather than a confirmation. */
internal class ChatShareToastMessage(val text: String, val info: Boolean = false)

/**
 * A short self-dismissing capsule near the bottom, iOS MinisToast's look: black at
 * 80%, a white icon and white text, shown for 1.6 s.
 */
@Composable
internal fun ChatShareToast(message: ChatShareToastMessage?, onDone: (ChatShareToastMessage) -> Unit, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<ChatShareToastMessage?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        shown = message
        visible = true
        delay(1_600)
        visible = false
        delay(300)
        onDone(message)
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(250)),
        exit = fadeOut(tween(300)),
        // The preview's content area already ends at the control strip, above
        // the navigation bar; the toast sits just above the strip.
        modifier = modifier.padding(bottom = 16.dp, start = 24.dp, end = 24.dp),
    ) {
        val m = shown ?: return@AnimatedVisibility
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                if (m.info) Icons.Filled.Info else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = m.text,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** One toggle of the preview's control strip. */
internal class ChatShareControl(
    val icon: ImageVector,
    val label: String,
    /** On its non-default side: drawn in the accent colour. */
    val active: Boolean,
    val onClick: () -> Unit,
    val iconRotation: Float = 0f,
)

/**
 * [T-multi-select-share] A photo-editor style control strip at the bottom — width,
 * tables, appearance — one icon toggle per option, showing its current state (icon
 * + "dimension: value" label), accent-coloured on its non-default side. A tap flips
 * it and re-renders; the caller shows a "Switched to …" toast (iOS controlBar).
 */
@Composable
internal fun ChatShareControlStrip(items: List<ChatShareControl>) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 4.dp, start = 8.dp, end = 8.dp),
    ) {
        for (item in items) {
            val color = if (item.active) accent else MaterialTheme.colorScheme.onSurface
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = item.onClick)
                    .padding(vertical = 6.dp),
            ) {
                Box(modifier = Modifier.height(26.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = item.iconRotation },
                    )
                }
                androidx.compose.foundation.text.BasicText(
                    text = item.label,
                    style = androidx.compose.ui.text.TextStyle(color = color, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    maxLines = 1,
                    softWrap = false,
                    // One line, shrinking to 80% before it would be cut (iOS minimumScaleFactor 0.8).
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.8.sp, maxFontSize = 11.sp),
                )
            }
        }
    }
}
