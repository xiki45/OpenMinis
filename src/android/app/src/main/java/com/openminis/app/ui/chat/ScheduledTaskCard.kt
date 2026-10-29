package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledTaskDetailModel
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTaskMarker
import com.openminis.app.scheduled.ScheduledTaskStore
import com.openminis.app.ui.theme.ChatColors
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

/** [T-android-scheduled-task-card] Amber — a timer, not an agent. */
internal val ScheduledAccent = Color(0xFFF59E0B)

/**
 * [T-android-scheduled-task-card / -next-fire / -cancel-from-card]
 * A fired scheduled task, rendered as a card instead of a user bubble.
 *
 * Three things the plain bubble could not say:
 *  - that a TASK wrote this, not the user;
 *  - WHEN it will run next, which until now was invisible after creation —
 *    the user had to go to Settings and read the list to find out;
 *  - that it can be stopped from right here, without going to find it.
 *
 * Cancelling asks first. A scheduled task is something the user deliberately
 * set up, so the cost of an accidental tap is not symmetric with the cost of
 * one extra confirmation.
 */
@Composable
internal fun ScheduledTaskCard(
    marker: ScheduledTaskMarker,
    modifier: Modifier = Modifier,
    // [T-scheduled-task-detail] The chat this card is shown in; the detail
    // page uses it for "this chat" wording and links to other chats.
    hostSessionId: String? = null,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    var confirming by remember { mutableStateOf(false) }
    // Local echo: the card must stop claiming a next run the moment the user
    // cancels from it; it then says "Schedule cancelled" in that slot.
    var cancelled by remember(marker.taskId) { mutableStateOf(false) }
    // [T-scheduled-task-detail] Tapping the card opens the task's detail page.
    var showingDetail by remember { mutableStateOf(false) }

    // [T-scheduled-task-detail] `now` decides whether the envelope's next-fire
    // time is still ahead. Read only at composition, a card on screen kept
    // "Next: <that time>" after the fire had happened, until something else
    // recomposed it. [T-scheduled-card-subline] The line now says "in 4 min.",
    // so it is also re-read at each minute boundary while that time is ahead.
    val now by produceState(System.currentTimeMillis(), marker.nextFireAtMs) {
        val next = marker.nextFireAtMs ?: return@produceState
        while (true) {
            val wait = next - System.currentTimeMillis()
            if (wait <= 0) break
            delay(ScheduledTaskDetailModel.nextTickMs(wait))
            value = System.currentTimeMillis()
        }
    }
    // [T-scheduled-card-subline] Offer Cancel only while the task is still a
    // live timer, as iOS does (`isCancellable`). The envelope is a snapshot, so
    // a card scrolled back to from history offered to cancel a task that had
    // long stopped. The store is the live truth here: it persists, so unlike
    // iOS's in-memory registry it stays right across a relaunch.
    val store = remember { ScheduledTaskStore(context.applicationContext) }
    val live by remember(marker.taskId) {
        store.observe().map { tasks -> tasks.any { it.id == marker.taskId && it.enabled } }
    }.collectAsState(initial = remember(marker.taskId) { store.get(marker.taskId)?.enabled == true })

    // [T-scheduled-card-subline] Port of the iOS card's subline.
    val phrases = ScheduledTaskDetailModel.CardPhrases(
        run = stringResource(R.string.scheduled_card_run),
        left = stringResource(R.string.scheduled_card_left),
        next = stringResource(R.string.scheduled_card_next),
        complete = stringResource(R.string.scheduled_card_task_complete),
        cancelled = stringResource(R.string.scheduled_card_cancelled),
    )
    // A task that is no longer live withdraws a next fire still ahead.
    val subline = ScheduledTaskDetailModel.cardSubline(marker, cancelled, now, phrases, live = live) {
        relativeFireTime(context, it, now)
    }

    // [T-sched-card-metrics] The sub-agent card's skeleton and metrics
    // (TaskCardFrame). This card used to be much taller than the sub-agent
    // card beside it: its texts set no line heights (inheriting the theme's
    // 24sp body line height) and its "Cancel this scheduled task" TextButton,
    // with Material's 40dp minimum height, set the card's height on its own.
    // Cancel is now a 24dp disc in the same slot as the sub-agent card's Stop,
    // before the chevron, so it cannot push the card taller.
    val cancelLabel = stringResource(R.string.scheduled_card_cancel)
    TaskCardFrame(
        accent = ScheduledAccent,
        onClick = { showingDetail = true },
        onClickLabel = null,
        modifier = modifier,
        icon = {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                tint = ScheduledAccent,
                modifier = Modifier.size(TaskCardMetrics.iconGlyph),
            )
        },
        action = if (live && !cancelled) {
            {
                TaskCardActionDisc(
                    label = cancelLabel,
                    background = ScheduledAccent,
                    onClick = { confirming = true },
                ) { TaskCardActionGlyph(Icons.Default.AlarmOff) }
            }
        } else {
            null
        },
    ) {
        TaskCardTitleRow {
            TaskCardChip(stringResource(R.string.scheduled_card_label), ScheduledAccent)
            TaskCardTitle(marker.label.ifEmpty { marker.prompt.take(40) })
        }
        // Always one status line, like the sub-agent card, so every card in the
        // flow keeps the same height. [T-scheduled-card-subline] It is never
        // empty for a fire: the prompt's first line is the last fallback.
        TaskCardStatusRow {
            Text(
                subline,
                fontSize = TaskCardMetrics.subtitleSize,
                lineHeight = TaskCardMetrics.subtitleLineHeight,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // [T-scheduled-card-subline] Exactly the row's height, the
                // glyphs laid out unclipped and centred: the fallback is a
                // prompt's first line, which may open with an emoji taller
                // than the CJK line the row is sized for.
                modifier = Modifier
                    .height(taskCardStatusLineHeight())
                    .wrapContentHeight(Alignment.CenterVertically, unbounded = true),
            )
        }
    }

    if (showingDetail) {
        ScheduledTaskDetailSheet(
            marker = marker,
            hostSessionId = hostSessionId,
            onDismiss = { showingDetail = false },
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.scheduled_card_cancel_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.scheduled_card_cancel_confirm_body,
                        marker.label.ifEmpty { marker.prompt.take(40) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Disable rather than delete: the run history stays
                    // readable, and the task is recoverable from Settings.
                    ScheduledTaskManager(context.applicationContext as android.app.Application)
                        .setEnabled(marker.taskId, false)
                    cancelled = true
                    confirming = false
                }) { Text(stringResource(R.string.scheduled_card_cancel_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.scheduled_card_cancel_confirm_keep))
                }
            },
        )
    }
}

/**
 * [T-scheduled-card-subline] "in 30 sec." / "in 4 min." / "in 2 hr." — iOS
 * `RelativeDateTimeFormatter` with `.short` units, which the iOS card uses for
 * "Next %@". ICU's short style gives the same forms; DateUtils does not (its
 * future form is sentence-cased, "In 4 min.", wrong mid-line). Units are
 * picked by [ScheduledTaskDetailModel.relativeUnit].
 */
internal fun relativeFireTime(context: android.content.Context, atMs: Long, nowMs: Long): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val f = android.icu.text.RelativeDateTimeFormatter.getInstance(
        android.icu.util.ULocale.forLocale(locale), null,
        android.icu.text.RelativeDateTimeFormatter.Style.SHORT,
        android.icu.text.DisplayContext.CAPITALIZATION_NONE,
    )
    val sec = ((atMs - nowMs) / 1000L).coerceAtLeast(0L)
    val (value, unit) = ScheduledTaskDetailModel.relativeUnit(sec)
    val icuUnit = when (unit) {
        ScheduledTaskDetailModel.RelativeUnit.SECOND -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.SECONDS
        ScheduledTaskDetailModel.RelativeUnit.MINUTE -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES
        ScheduledTaskDetailModel.RelativeUnit.HOUR -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS
        ScheduledTaskDetailModel.RelativeUnit.DAY -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS
        ScheduledTaskDetailModel.RelativeUnit.WEEK -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.WEEKS
        ScheduledTaskDetailModel.RelativeUnit.MONTH -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MONTHS
        ScheduledTaskDetailModel.RelativeUnit.YEAR -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.YEARS
    }
    return f.format(value.toDouble(), android.icu.text.RelativeDateTimeFormatter.Direction.NEXT, icuUnit)
}
