package com.openminis.app.ui.chat

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledSessionHold
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTaskMarker
import kotlinx.coroutines.delay

/**
 * [T-port-sched-pill] Pills hidden with "Hide this countdown", per chat: the
 * ids of the tasks live at the time. In memory only, like iOS.
 */
internal object ScheduledPillDismissals {
    val hidden = mutableStateMapOf<String, Set<String>>()

    fun hide(ids: Set<String>, sessionId: String) {
        hidden[sessionId] = (hidden[sessionId] ?: emptySet()) + ids
    }

    /** Forget tasks no longer live, so the entry empties once the hidden batch is over. */
    fun prune(live: Set<String>, sessionId: String) {
        val h = hidden[sessionId] ?: return
        val kept = h intersect live
        if (kept != h) { if (kept.isEmpty()) hidden.remove(sessionId) else hidden[sessionId] = kept }
    }
}

/**
 * [T-port-sched-pill] A small centred pill under the chat's top bar while the
 * chat has a scheduled task still to run - only tasks that keep this chat
 * active, by the same rule as busy / Stop (ScheduledSessionHold):
 * the glyph ringed by the elapsed wait, "+N" for further tasks, a short clock
 * ("00:48", then "1h 5m", then "1d 3h") or "Running" / "Waiting" one size
 * smaller, and the run counter ("2/30", "#7", nothing for a one-shot).
 *
 * - Tap opens the task page (the scheduled card's own sheet).
 * - Long-press (with a haptic) offers "Hide This Countdown" and "Cancel All
 *   Scheduled Tasks" (confirmed first, the Stop scope:
 *   ScheduledTaskManager.disableAllCreatedBy). The same two are TalkBack
 *   actions. iOS's "Skip next run" is not ported.
 * - Ticks once a second only while shown and the screen is resumed.
 * - Mounted in the Scaffold's top bar slot, so the list starts below it.
 * - [currentSessionId] supplies the chat's REAL id (ChatViewModel.currentSessionId),
 *   not ChatScreen's route id, which stays "__new__…" for a chat started fresh.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ScheduledTaskPill(currentSessionId: () -> String) {
    val context = LocalContext.current
    val manager = remember { ScheduledTaskManager(context.applicationContext) }
    val tasksFlow = remember { manager.store().observe() }
    val tasks by tasksFlow.collectAsState(initial = remember { manager.store().all() })
    val running by ScheduledSessionHold.runningTaskIds.collectAsState()
    val agentJobs by AgentJobRegistry.jobs.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // [T-port-sched-pill-visible] Read on every composition: a draft chat
    // gets its real id on the first message, before any task can name it,
    // and the task-list change that follows recomposes this with the real id.
    val sessionId = currentSessionId()

    val jobs = ScheduledPillModel.jobs(sessionId, tasks, running.keys, now) {
        ScheduledSessionHold.upstreamUnfinished(it, tasks, running.keys, agentJobs, now)
    }
    val liveIds = jobs.mapTo(HashSet()) { it.id }
    LaunchedEffect(liveIds) { ScheduledPillDismissals.prune(liveIds, sessionId) }
    val hidden = ScheduledPillDismissals.hidden[sessionId] ?: emptySet()
    val state = if (ScheduledPillModel.isDismissed(liveIds, hidden)) null else ScheduledPillModel.state(jobs)

    val lifecycleOwner = LocalLifecycleOwner.current
    val shown = state != null
    LaunchedEffect(shown, lifecycleOwner) {
        if (!shown) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000L - now % 1_000L)
            }
        }
    }

    val reduceMotion = animationsDisabled(context)
    var openTask by remember { mutableStateOf<ScheduledTask?>(null) }
    var confirmCancelAll by remember { mutableStateOf(false) }
    AnimatedVisibility(
        visible = shown,
        enter = if (reduceMotion) fadeIn(tween(0)) else slideInVertically { -it } + fadeIn(),
        exit = if (reduceMotion) fadeOut(tween(0)) else slideOutVertically { -it } + fadeOut(),
    ) {
        val s = state ?: return@AnimatedVisibility
        PillCard(
            state = s,
            now = now,
            onOpen = { openTask = tasks.firstOrNull { it.id == s.job.id } },
            onHide = {
                AppLogger.info("SchedPill", "hide session=${sessionId.take(8)} tasks=${liveIds.size}")
                ScheduledPillDismissals.hide(liveIds, sessionId)
            },
            onCancelAll = { confirmCancelAll = true },
            // [T-sched-run-now] The same state the pill draws (phase + the
            // running set it collects), plus a start already in progress.
            // Read when the menu composes and again on every change while it
            // is open; the tap re-checks atomically in ScheduledRunNow.start.
            runNowOffered = {
                com.openminis.app.scheduled.ScheduledRunNow.canOffer(
                    countdown = s.phase == ScheduledPillModel.Phase.COUNTDOWN,
                    nextFireAt = s.job.nextFireAt,
                    running = s.job.id in running.keys,
                    starting = com.openminis.app.scheduled.ScheduledRunNow.isStarting(s.job.id),
                )
            },
            onRunNow = {
                // The run this menu showed; the claim fails if it is no longer pending.
                val shown = tasks.firstOrNull { it.id == s.job.id }
                    ?.let { com.openminis.app.scheduled.ScheduledTaskManager.pendingRunKey(it, now) }
                val started = com.openminis.app.scheduled.ScheduledRunNow.start(
                    context, s.job.id, manager, expectedRunKey = shown,
                )
                AppLogger.info("SchedPill", "run now session=${sessionId.take(8)} task=${s.job.id.take(8)} started=$started")
            },
        )
    }

    openTask?.let { t ->
        ScheduledTaskDetailSheet(
            marker = ScheduledTaskMarker(taskId = t.id, label = t.label, nextFireAtMs = t.nextTriggerMs(), prompt = t.prompt),
            hostSessionId = sessionId,
            onDismiss = { openTask = null },
        )
    }
    if (confirmCancelAll) {
        AlertDialog(
            onDismissRequest = { confirmCancelAll = false },
            title = { Text(stringResource(R.string.sched_pill_cancel_all_title)) },
            text = { Text(stringResource(R.string.sched_pill_cancel_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancelAll = false
                    val n = manager.disableAllCreatedBy(sessionId, "user cancelled all from the timer pill")
                    AppLogger.info("SchedPill", "cancel all session=${sessionId.take(8)} cancelled=$n")
                }) { Text(stringResource(R.string.sched_pill_cancel_all_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelAll = false }) { Text(stringResource(R.string.sched_pill_not_now)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PillCard(
    state: ScheduledPillModel.State,
    now: Long,
    onOpen: () -> Unit,
    onHide: () -> Unit,
    onCancelAll: () -> Unit,
    runNowOffered: () -> Boolean,
    onRunNow: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val remainingMs = state.job.nextFireAt?.let { it - now }
    val running = state.phase == ScheduledPillModel.Phase.RUNNING
    val clock = when (state.phase) {
        ScheduledPillModel.Phase.RUNNING -> stringResource(R.string.sched_pill_running)
        ScheduledPillModel.Phase.WAITING -> stringResource(R.string.sched_pill_waiting)
        ScheduledPillModel.Phase.COUNTDOWN -> remainingMs?.let { visibleCountdown(it) } ?: ""
    }
    val counter = ScheduledPillModel.runCounter(state.job, running)
    val ring = when (state.phase) {
        ScheduledPillModel.Phase.COUNTDOWN -> ScheduledPillModel.progress(state.job, now)
        ScheduledPillModel.Phase.RUNNING -> 1f
        ScheduledPillModel.Phase.WAITING -> 0f
    }
    val spoken = spokenText(state, remainingMs)
    val hint = stringResource(R.string.sched_pill_a11y_hint)
    val hideLabel = stringResource(R.string.sched_pill_hide)
    val cancelLabel = stringResource(R.string.sched_pill_cancel_all)
    val runNowLabel = stringResource(R.string.sched_pill_run_now)
    val offerRunNow = runNowOffered()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .shadow(6.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f))
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .background(ScheduledAccent.copy(alpha = 0.06f), CircleShape)
                    .border(0.75.dp, ScheduledAccent.copy(alpha = 0.28f), CircleShape)
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        },
                    )
                    .clearAndSetSemantics {
                        contentDescription = spoken
                        role = Role.Button
                        onClick(label = hint) { onOpen(); true }
                        customActions = listOfNotNull(
                            if (offerRunNow) CustomAccessibilityAction(runNowLabel) { onRunNow(); true } else null,
                            CustomAccessibilityAction(hideLabel) { onHide(); true },
                            CustomAccessibilityAction(cancelLabel) { onCancelAll(); true },
                        )
                    }
                    .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 13.dp),
            ) {
                PillRing(fraction = ring, waiting = state.phase == ScheduledPillModel.Phase.WAITING)
                if (state.others > 0) {
                    Text(
                        "+${state.others}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScheduledAccent,
                        modifier = Modifier.padding(start = 6.dp),
                        maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                    // The clock keeps the width of its format's widest value
                    // (hidden template), so it does not twitch while ticking.
                    Box(contentAlignment = Alignment.CenterStart) {
                        val isStatus = state.phase != ScheduledPillModel.Phase.COUNTDOWN
                        Text(
                            if (isStatus) "0" else ScheduledPillModel.widthTemplate(clock),
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, style = TabularDigits,
                            maxLines = 1, modifier = Modifier.alpha(0f),
                        )
                        Text(
                            clock,
                            fontSize = if (isStatus) 13.sp else 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = TabularDigits,
                            color = ScheduledAccent,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    if (counter != null) {
                        Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.padding(start = 5.dp)) {
                            Text(
                                ScheduledPillModel.widthTemplate(counter),
                                fontSize = 12.sp, fontWeight = FontWeight.Medium, style = TabularDigits,
                                maxLines = 1, modifier = Modifier.alpha(0f),
                            )
                            Text(
                                counter,
                                fontSize = 12.sp, fontWeight = FontWeight.Medium, style = TabularDigits,
                                color = ScheduledAccent.copy(alpha = 0.7f), maxLines = 1, softWrap = false,
                            )
                        }
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // [T-sched-run-now] First item, only while the run has not
                // started. A run that starts while the menu is open removes it.
                if (offerRunNow) {
                    DropdownMenuItem(
                        text = { Text(runNowLabel) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        onClick = { menuOpen = false; onRunNow() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(hideLabel) },
                    leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                    onClick = { menuOpen = false; onHide() },
                )
                DropdownMenuItem(
                    text = { Text(cancelLabel, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onCancelAll() },
                )
            }
        }
    }
}

/** Digits of equal width, so the clock and counter do not jitter while ticking. */
private val TabularDigits = TextStyle(fontFeatureSettings = "tnum")

/** The glyph in its tinted circle, ringed by how much of the wait has passed. */
@Composable
private fun PillRing(fraction: Float, waiting: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp)) {
        Canvas(modifier = Modifier.size(26.dp)) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2
            drawCircle(ScheduledAccent.copy(alpha = 0.15f))
            drawCircle(ScheduledAccent.copy(alpha = 0.18f), radius = size.minDimension / 2 - inset, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(
                    color = ScheduledAccent,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Icon(
            if (waiting) Icons.Outlined.HourglassEmpty else Icons.Default.Schedule,
            contentDescription = null,
            tint = ScheduledAccent,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** "00:48" up to an hour, then "1h 5m" (minutes rounded up), then "1d 3h", system-localized. */
@Composable
private fun visibleCountdown(remainingMs: Long): String {
    ScheduledPillModel.countdown(remainingMs)?.let { return it }
    val c = ScheduledPillModel.coarse(remainingMs)
    val locale = LocalContext.current.resources.configuration.locales[0]
    return formatMeasures(locale, MeasureFormat.FormatWidth.NARROW, c)
}

private fun formatMeasures(locale: java.util.Locale, width: MeasureFormat.FormatWidth, c: ScheduledPillModel.Coarse): String {
    val measures = buildList {
        if (c.days > 0) {
            add(Measure(c.days, MeasureUnit.DAY))
            if (c.hours > 0) add(Measure(c.hours, MeasureUnit.HOUR))
        } else {
            if (c.hours > 0) add(Measure(c.hours, MeasureUnit.HOUR))
            if (c.minutes > 0 || c.hours == 0L) add(Measure(c.minutes, MeasureUnit.MINUTE))
        }
    }
    return runCatching { MeasureFormat.getInstance(locale, width).formatMeasures(*measures.toTypedArray()) }
        .getOrDefault("")
}

/** The pill for TalkBack, one sentence-like line (iOS accessibilityText). */
@Composable
private fun spokenText(state: ScheduledPillModel.State, remainingMs: Long?): String {
    val parts = mutableListOf(stringResource(R.string.sched_pill_a11y_title), state.job.title)
    parts += when (state.phase) {
        ScheduledPillModel.Phase.RUNNING -> stringResource(R.string.sched_pill_a11y_running)
        ScheduledPillModel.Phase.WAITING -> stringResource(R.string.sched_pill_a11y_waiting)
        ScheduledPillModel.Phase.COUNTDOWN -> state.job.remaining
            ?.let { stringResource(R.string.sched_pill_a11y_upcoming_left, it) }
            ?: stringResource(R.string.sched_pill_a11y_upcoming)
    }
    ScheduledPillModel.runNumber(state.job, state.phase == ScheduledPillModel.Phase.RUNNING)?.let { n ->
        parts += state.job.runTotal?.let { stringResource(R.string.sched_pill_a11y_run_of, n, it) }
            ?: stringResource(R.string.sched_pill_a11y_run, n)
    }
    if (remainingMs != null && state.phase != ScheduledPillModel.Phase.WAITING) {
        val locale = LocalContext.current.resources.configuration.locales[0]
        val s = (remainingMs.coerceAtLeast(0) + 999) / 1000
        val spoken = when {
            s >= 86_400 -> formatMeasures(locale, MeasureFormat.FormatWidth.WIDE, ScheduledPillModel.Coarse(s / 86_400, (s % 86_400) / 3600, 0))
            s >= 3600 -> formatMeasures(locale, MeasureFormat.FormatWidth.WIDE, ScheduledPillModel.Coarse(0, s / 3600, (s % 3600) / 60))
            else -> runCatching {
                val m = buildList {
                    if (s / 60 > 0) add(Measure(s / 60, MeasureUnit.MINUTE))
                    add(Measure(s % 60, MeasureUnit.SECOND))
                }
                MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).formatMeasures(*m.toTypedArray())
            }.getOrDefault("")
        }
        if (spoken.isNotEmpty()) parts += stringResource(R.string.sched_pill_a11y_next_in, spoken)
    }
    if (state.others > 0) parts += stringResource(R.string.sched_pill_a11y_more, state.others)
    return parts.joinToString(", ")
}
