package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-stop-choice] "What should stop?" - shown instead of an immediate Stop
 * when the conversation has background work a Stop would also end (sub
 * agents, scheduled tasks; [StopSummary.hasBackground]).
 *
 * Lists what is running and how many of each, then offers:
 *  - Stop Current Tool Only ([onToolOnly], the safe primary choice, nearest the
 *    thumb) - only when this chat's own turn is running ([canStopToolOnly]);
 *    with only background work there is no "current tool" to stop;
 *  - Stop All ([onStopAll], destructive);
 *  - Cancel.
 *
 * Also used by New Chat's "a task is running" guard, with its own title and
 * button labels and the [body] override.
 *
 * Drawn as the iOS action sheet the same choice uses (ChatSendButton
 * `.confirmationDialog`): a card at the bottom with the centred title and
 * message over full-width, centred buttons divided by hairlines - the safe
 * choice in the accent colour, Stop All in red - and Cancel, bold, on a card
 * of its own below. The Material alert it replaces stacked three text
 * buttons at the right edge under a left-aligned body.
 */
@Composable
internal fun StopChoiceDialog(
    summary: StopSummary,
    canStopToolOnly: Boolean,
    onDismiss: () -> Unit,
    onToolOnly: () -> Unit,
    onStopAll: () -> Unit,
    title: String = stringResource(R.string.chat_stop_choice_title),
    toolOnlyText: String = stringResource(R.string.chat_stop_choice_tool_only),
    stopAllText: String = stringResource(R.string.chat_stop_choice_all),
    body: String? = null,
) {
    val lines = buildList {
        if (canStopToolOnly) {
            add(
                if (summary.runningTools.isEmpty()) {
                    stringResource(R.string.chat_stop_choice_current_reply)
                } else {
                    stringResource(R.string.chat_stop_choice_current_tools, summary.runningTools.distinct().joinToString(", "))
                },
            )
        }
        if (summary.subAgentsRunning > 0) {
            add(pluralStringResource(R.plurals.chat_stop_choice_subagents, summary.subAgentsRunning, summary.subAgentsRunning))
        }
        if (summary.subAgentsQueued > 0) {
            add(pluralStringResource(R.plurals.chat_stop_choice_subagents_queued, summary.subAgentsQueued, summary.subAgentsQueued))
        }
        if (summary.scheduledTasks > 0) {
            add(pluralStringResource(R.plurals.chat_stop_choice_scheduled, summary.scheduledTasks, summary.scheduledTasks))
        }
    }
    val footer = buildList {
        if (canStopToolOnly) add(stringResource(R.string.chat_stop_choice_tool_only_footer))
        add(stringResource(R.string.chat_stop_choice_all_footer))
    }.joinToString(" ")
    val message = buildString {
        body?.let { append(it).append("\n\n") }
        append(lines.joinToString("\n"))
        if (lines.isNotEmpty()) append("\n\n")
        append(footer)
    }
    StopActionSheet(
        title = title,
        message = message,
        onDismiss = onDismiss,
        actions = buildList {
            if (canStopToolOnly) add(SheetAction(toolOnlyText, destructive = false, onToolOnly))
            add(SheetAction(stopAllText, destructive = true, onStopAll))
        },
    )
}

private class SheetAction(val label: String, val destructive: Boolean, val onClick: () -> Unit)

@Composable
private fun StopActionSheet(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    actions: List<SheetAction>,
) {
    val dark = ChatColors.isDark
    // iOS system colours for an action sheet: the grouped card, the tint and
    // the destructive red, in each appearance.
    val card = if (dark) Color(0xFF2C2C2E) else Color(0xFFF9F9F9)
    val accent = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val red = if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
    val hairline = ChatColors.separator
    val shape = RoundedCornerShape(14.dp)
    val noRipple = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Full-window so the sheet can sit at the bottom; a tap above it
        // dismisses, as outside an iOS action sheet.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    // Taps on the sheet itself do not dismiss it.
                    .clickable(interactionSource = noRipple, indication = null, onClick = {}),
            ) {
                // [T-stop-sheet-fits] In landscape or at a large font scale the
                // sheet can be taller than the window. Cancel is measured first
                // (this card is weighted, not filling), and inside the card the
                // buttons come before the title and message, which scroll in
                // what is left - as the platform alert dialog does. Before,
                // the bottom buttons laid out past the window edge.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .clip(shape)
                        .background(card),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ChatColors.secondaryText,
                            textAlign = TextAlign.Center,
                        )
                        if (message.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = message,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                color = ChatColors.secondaryText,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    for (action in actions) {
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(hairline))
                        SheetButton(action.label, if (action.destructive) red else accent, FontWeight.Normal) {
                            action.onClick()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().clip(shape).background(card)) {
                    SheetButton(stringResource(R.string.cancel), accent, FontWeight.SemiBold, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, color: Color, weight: FontWeight, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 19.sp,
            fontWeight = weight,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}
