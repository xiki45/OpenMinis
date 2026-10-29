package com.openminis.app.scheduled

import java.util.Calendar

/**
 * [T-scheduled-task-detail] Everything the scheduled-task detail page shows,
 * derived without Android UI so it can be unit-tested. Shared design with iOS
 * (T-scheduled-task-detail), mapped onto
 * Android's model:
 *
 *  - The task is persisted ([ScheduledTaskStore]) and survives a restart, as
 *    does its run history, so the iOS "no live job → Stopped after relaunch"
 *    row does not exist here. Its Android counterpart is a task that has been
 *    DELETED while its cards stay in the chat.
 *  - Triggers are calendar-based (once / daily / weekdays / custom days at
 *    HH:mm, optionally inside a start/end window), or, since
 *    [T-android-scheduled-triggers], a relative one-shot (--after), an interval
 *    loop (--interval, --count) or on-completion. A total count is known for a
 *    one-shot, a window with an end date, or a loop with a --count.
 *  - History comes from [ScheduledTask.runHistory] (persisted, all sessions,
 *    with the session and ok flag) plus the fire whose card was tapped, which
 *    is not recorded until it completes. A newer undelivered fire replaces an
 *    older one instead of being counted, so there is no "+K merged" badge.
 *
 * The status is never "Interrupted": nothing here maps "not live" to it.
 */
object ScheduledTaskDetailModel {

    enum class Status { RUNNING, WAITING, COMPLETED, FAILED, DISABLED, DELETED }

    enum class FireState { OK, FAILED, RUNNING }

    data class FireRow(
        /** 1-based position among all fires of the task. */
        val index: Int,
        val firedAt: Long,
        val sessionId: String?,
        val state: FireState,
        val preview: String?,
        /** The fire whose card was tapped. */
        val isCurrent: Boolean,
        /** Ran in a different chat than the one the page was opened from. */
        val inOtherSession: Boolean,
    )

    data class Progress(
        /** Index of the tapped fire, when it can be identified. */
        val currentIndex: Int?,
        /** Total fires, when the task has a finite number of them. */
        val total: Int?,
        /** Fires still to come; null = unbounded. */
        val remaining: Int?,
        /** Next scheduled fire, or null when there is none. */
        val nextFireAt: Long?,
        val firesSoFar: Int,
    ) {
        /** The progress card appears only when there is something to count. */
        val visible: Boolean get() = currentIndex != null || total != null
    }

    /** Cap on the forward walk that counts remaining fires in a window. */
    private const val REMAINING_WALK_CAP = 1000

    /**
     * Is the tapped fire still being processed? True when it has not been
     * recorded yet and the chat it runs in is streaming — the recording
     * happens when the run completes.
     */
    fun isFireRunning(task: ScheduledTask?, marker: ScheduledTaskMarker, hostStreaming: Boolean): Boolean {
        val firedAt = marker.firedAtMs ?: return false
        val recorded = task?.runHistory?.any { it.firedAt == firedAt } == true
        return !recorded && hostStreaming
    }

    /** Priority order per the shared design §4, adapted to Android. */
    fun status(task: ScheduledTask?, fireRunning: Boolean, now: Long): Status {
        if (task == null) return Status.DELETED
        if (fireRunning) return Status.RUNNING
        val next = task.nextTriggerMs(now)
        if (task.isOneShot) {
            // A one-shot task disables itself after firing; its single run
            // decides how it ended. An on-completion task has no clock time:
            // enabled means it is still waiting for its upstream.
            val waiting = next != null || task.triggerKind == ScheduledTriggerKind.ON_COMPLETION
            if (task.enabled && waiting) return Status.WAITING
            val last = task.runHistory.firstOrNull() ?: return Status.DISABLED
            return if (last.ok) Status.COMPLETED else Status.FAILED
        }
        if (task.enabled) return if (next != null) Status.WAITING else Status.COMPLETED
        // Disabled: finished if its window has run out, otherwise stopped by
        // the user.
        return if (windowEnded(task, now)) Status.COMPLETED else Status.DISABLED
    }

    private fun windowEnded(task: ScheduledTask, now: Long): Boolean =
        // An interval that used up its --count ended the same way a window does.
        task.remainingFires == 0 ||
            (task.endDateMs != null && task.copy(enabled = true).nextTriggerMs(now) == null)

    fun progress(task: ScheduledTask?, marker: ScheduledTaskMarker, fireRunning: Boolean, now: Long): Progress {
        if (task == null) return Progress(null, null, null, null, 0)
        val fired = task.firesSoFar
        val next = if (task.enabled) task.nextTriggerMs(now) else null
        val remaining: Int? = when {
            task.triggerKind == ScheduledTriggerKind.ON_COMPLETION -> if (task.enabled) 1 else 0
            next == null -> 0
            task.isOneShot -> 1
            task.triggerKind == ScheduledTriggerKind.INTERVAL -> task.remainingFires
            task.endDateMs == null -> null
            else -> countFires(task, now)
        }
        val inFlight = if (fireRunning) 1 else 0
        val total = remaining?.let { fired + inFlight + it }
            ?.takeIf {
                task.isOneShot || task.endDateMs != null ||
                    (task.triggerKind == ScheduledTriggerKind.INTERVAL && task.maxFires != null)
            }
        return Progress(
            currentIndex = currentIndex(task, marker, fireRunning),
            total = total,
            remaining = remaining,
            nextFireAt = next,
            firesSoFar = fired,
        )
    }

    private fun countFires(task: ScheduledTask, now: Long): Int {
        var n = 0
        var t = task.nextTriggerMs(now) ?: return 0
        while (n < REMAINING_WALK_CAP) {
            n++
            t = task.nextTriggerMs(t + 60_000L) ?: break
        }
        return n
    }

    private fun currentIndex(task: ScheduledTask, marker: ScheduledTaskMarker, fireRunning: Boolean): Int? {
        val firedAt = marker.firedAtMs ?: return null
        val pos = task.runHistory.indexOfFirst { it.firedAt == firedAt }
        if (pos >= 0) return task.firesSoFar - pos
        return if (fireRunning) task.firesSoFar + 1 else null
    }

    /**
     * Newest first. The recorded runs, plus the tapped fire when it is still
     * running (it is recorded only when it completes). Rows are unique by
     * fire time.
     */
    fun history(
        task: ScheduledTask?,
        marker: ScheduledTaskMarker,
        hostSessionId: String?,
        fireRunning: Boolean,
    ): List<FireRow> {
        val runs = task?.runHistory ?: emptyList()
        val fired = task?.firesSoFar ?: runs.size
        val rows = runs.mapIndexed { pos, run ->
            FireRow(
                index = fired - pos,
                firedAt = run.firedAt,
                sessionId = run.sessionId,
                state = if (run.ok) FireState.OK else FireState.FAILED,
                preview = run.preview,
                isCurrent = marker.firedAtMs != null && run.firedAt == marker.firedAtMs,
                inOtherSession = run.sessionId != null && hostSessionId != null && run.sessionId != hostSessionId,
            )
        }
        val runningAt = marker.firedAtMs
        if (!fireRunning || runningAt == null || rows.any { it.firedAt == runningAt }) return rows
        val live = FireRow(
            index = fired + 1,
            firedAt = runningAt,
            sessionId = hostSessionId,
            state = FireState.RUNNING,
            preview = null,
            isCurrent = true,
            inOtherSession = false,
        )
        return listOf(live) + rows
    }

    // ── trigger text ────────────────────────────────────────────────────────

    /**
     * Localized phrases for [triggerText], filled from string resources by the
     * page and from literals by tests. `%1$s` is the time (or date + time for
     * [once]), `%2$s` the day list for [weekly].
     */
    data class TriggerPhrases(
        val once: String,
        val daily: String,
        val weekdays: String,
        val weekly: String,
        val daySeparator: String,
        /** Short names indexed by Calendar.DAY_OF_WEEK - 1 (Sunday first). */
        val dayNames: List<String>,
        val from: String,
        val until: String,
        val window: String,
        // [T-android-scheduled-triggers] `%1$s` is a duration ("10m", "2h").
        // Defaults keep literal-built phrases (tests) compiling.
        val interval: String = "Every %1${'$'}s",
        /** `%1$s` duration, `%2$d` the --count. */
        val intervalCount: String = "Every %1${'$'}s · %2${'$'}d times",
        /** `%1$s` the upstream task's label or id. */
        val onCompletion: String = "When \u201c%1${'$'}s\u201d finishes",
    )

    /** Monday-first display order of Calendar.DAY_OF_WEEK values. */
    private val WEEK_ORDER = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
    )

    /**
     * One-line human form of the task's trigger, e.g. "Every day 08:00",
     * "Every Mon, Wed 09:30 · until Oct 3". [formatTime] renders HH:mm,
     * [formatDate] renders a day (both locale-aware in the page).
     */
    fun triggerText(
        task: ScheduledTask,
        p: TriggerPhrases,
        formatTime: (hour: Int, minute: Int) -> String,
        /**
         * [T-android-scheduled-triggers] Name of an on-completion upstream.
         * Before [formatDate] so existing trailing-lambda callers still bind it.
         */
        upstreamName: (String) -> String? = { null },
        formatDate: (Long) -> String,
    ): String {
        // [T-android-scheduled-triggers] The relative triggers.
        when (task.triggerKind) {
            ScheduledTriggerKind.AFTER -> {
                // Shown as the moment it fires (or fired), in the same words as
                // a calendar one-shot: "Once at Oct 3 09:30".
                val at = (task.anchorMs ?: task.createdAt) + (task.delaySec ?: 0L) * 1000
                val cal = Calendar.getInstance().apply { timeInMillis = at }
                return p.once.format(
                    "${formatDate(at)} ${formatTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}",
                )
            }
            ScheduledTriggerKind.INTERVAL -> {
                val every = formatDuration(task.intervalSec ?: 0L)
                return task.maxFires?.let { p.intervalCount.format(every, it) } ?: p.interval.format(every)
            }
            ScheduledTriggerKind.ON_COMPLETION -> {
                val of = task.onCompletionOf.orEmpty()
                return p.onCompletion.format(upstreamName(of) ?: of.take(8))
            }
            ScheduledTriggerKind.CALENDAR -> Unit
        }
        val time = formatTime(task.timeOfDayHour, task.timeOfDayMinute)
        val base = when (task.repeatMode) {
            ScheduledRepeatMode.ONCE -> {
                val at = task.startDateMs?.let { "${formatDate(it)} $time" } ?: time
                p.once.format(at)
            }
            ScheduledRepeatMode.DAILY -> p.daily.format(time)
            ScheduledRepeatMode.WEEKDAYS -> p.weekdays.format(time)
            ScheduledRepeatMode.CUSTOM -> {
                val days = WEEK_ORDER.filter { it in task.customDays }
                    .joinToString(p.daySeparator) { p.dayNames[it - 1] }
                p.weekly.format(days, time)
            }
        }
        if (task.repeatMode == ScheduledRepeatMode.ONCE) return base
        val start = task.startDateMs
        val end = task.endDateMs
        val window = when {
            start != null && end != null -> p.window.format(formatDate(start), formatDate(end))
            end != null -> p.until.format(formatDate(end))
            start != null -> p.from.format(formatDate(start))
            else -> null
        }
        return if (window == null) base else "$base · $window"
    }

    /**
     * [T-android-scheduled-triggers] A duration the way the CLI takes it:
     * "90s", "10m", "2h", "1h30m". Compact and language-neutral.
     */
    fun formatDuration(sec: Long): String {
        if (sec <= 0) return "0s"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return buildString {
            if (h > 0) append("${h}h")
            if (m > 0) append("${m}m")
            if (s > 0 || isEmpty()) append("${s}s")
        }
    }

    // ── chat card subtitle ──────────────────────────────────────────────────

    /** Localized pieces of a card's status line (iOS keys in comments). */
    data class CardPhrases(
        /** "Run %d" */
        val run: String,
        /** "%d left" */
        val left: String,
        /** "Next %@" — takes the relative time, e.g. "in 4 min." */
        val next: String,
        /** "Task complete" */
        val complete: String,
        /** Android-only local echo after cancelling from this card. */
        val cancelled: String,
    )

    /** Joins the parts of a card's status line, as iOS does. */
    const val CARD_SEPARATOR = " · "

    /**
     * [T-scheduled-card-subline] The second line of a fire's chat card, port of
     * iOS `AgentCallbackCellView.subline` for a scheduled callback:
     * "Run N" · "N left" · "Next <relative>" or "Task complete" · trigger, and
     * the prompt's first line when all of those are empty, so the card is
     * never just a title.
     *
     * Field report: once a task's next fire had passed or it was stopped, every
     * card of it showed its title over an empty line — the old rule said only
     * "next fire", and a passed next-fire time says nothing.
     *
     * The envelope is a snapshot taken when the fire started, so its next-fire
     * time goes stale: every card scrolled back to is past it. Only "this fire
     * had no next one" (no next time, or nothing left) means the task is
     * complete; a passed time says nothing about it (iOS cbf9c6961 fixed
     * "Run 1 · 4 left · Task complete" on a task still firing), and the detail
     * page shows the live state.
     *
     * [cancelled] is Android's local echo of a cancel made from this card; it
     * takes the next-fire slot. [live] is whether the task is still an enabled
     * timer: when it is not, a next fire still ahead is a promise that will not
     * be kept, so that slot says cancelled too (iOS keeps saying "Next" until
     * the time passes). A passed or absent next fire is unaffected, so a task
     * that ran out its count still reads "Task complete" on its last card.
     */
    fun cardSubline(
        marker: ScheduledTaskMarker,
        cancelled: Boolean,
        now: Long,
        p: CardPhrases,
        live: Boolean = true,
        relative: (atMs: Long) -> String,
    ): String {
        val parts = mutableListOf<String>()
        marker.fireIndex?.takeIf { it > 0 }?.let { parts += p.run.format(it) }
        marker.remaining?.let { parts += p.left.format(it) }
        val next = marker.nextFireAtMs?.takeIf { it > now }
        when {
            cancelled -> parts += p.cancelled
            next != null -> parts += if (live) p.next.format(relative(next)) else p.cancelled
            isLastFire(marker) -> parts += p.complete
        }
        marker.triggerLabel?.takeIf { it.isNotEmpty() }?.let { parts += it }
        if (parts.isEmpty()) {
            marker.prompt.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { parts += it }
        }
        return parts.joinToString(CARD_SEPARATOR)
    }

    enum class RelativeUnit { SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, YEAR }

    /**
     * [T-scheduled-card-subline] The unit and value a relative "in …" label
     * uses for [seconds] ahead: the largest non-zero unit, truncated, as
     * Foundation's `RelativeDateTimeFormatter` picks it (90 s → 1 min, 59 min →
     * 59 min, 25 h → 1 day). Months and years are 30 / 365 days.
     */
    fun relativeUnit(seconds: Long): Pair<Long, RelativeUnit> {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> s to RelativeUnit.SECOND
            s < 3_600 -> s / 60 to RelativeUnit.MINUTE
            s < 86_400 -> s / 3_600 to RelativeUnit.HOUR
            s < 7 * 86_400 -> s / 86_400 to RelativeUnit.DAY
            s < 30 * 86_400 -> s / (7 * 86_400) to RelativeUnit.WEEK
            s < 365 * 86_400 -> s / (30 * 86_400) to RelativeUnit.MONTH
            else -> s / (365 * 86_400) to RelativeUnit.YEAR
        }
    }

    /**
     * [T-scheduled-card-subline] How long to wait before re-reading the clock
     * for a relative "in N min." label [waitMs] before the fire: to the next
     * whole-minute boundary, so the truncated minute count is never a minute
     * behind (iOS computes it at each render), and to the fire itself inside
     * the last minute, when the "Next" part goes away.
     */
    fun nextTickMs(waitMs: Long): Long =
        if (waitMs <= 60_000L) waitMs + 1 else (waitMs % 60_000L).let { if (it == 0L) 60_000L else it } + 1

    /** iOS `isLastFire`: the envelope promises no next fire, or nothing is left. */
    fun isLastFire(marker: ScheduledTaskMarker): Boolean =
        marker.nextFireAtMs == null || marker.remaining == 0

    // ── durations ───────────────────────────────────────────────────────────

    enum class DurationUnit { DAY, HOUR, MINUTE, SECOND }

    /**
     * A duration as its two largest units, e.g. 3725 s → [1 h, 2 min],
     * 90 s → [1 min, 30 s], 45 s → [45 s]. The second unit is dropped when it
     * is zero, so "2 h" rather than "2 h 0 min".
     */
    fun durationParts(totalSeconds: Long): List<Pair<DurationUnit, Int>> {
        val s = totalSeconds.coerceAtLeast(0)
        val units = listOf(
            DurationUnit.DAY to s / 86_400,
            DurationUnit.HOUR to (s % 86_400) / 3_600,
            DurationUnit.MINUTE to (s % 3_600) / 60,
            DurationUnit.SECOND to s % 60,
        )
        val first = units.indexOfFirst { it.second > 0 }
        if (first < 0) return listOf(DurationUnit.SECOND to 0)
        return units.subList(first, minOf(first + 2, units.size))
            .filterIndexed { i, (_, n) -> i == 0 || n > 0 }
            .map { (u, n) -> u to n.toInt() }
    }

    // ── delivery ────────────────────────────────────────────────────────────

    sealed class Delivery {
        object NewSession : Delivery()
        object ThisChat : Delivery()
        data class OtherChat(val sessionId: String) : Delivery()
        data class Rerun(val sessionId: String, val inThisChat: Boolean) : Delivery()
        object ChildOfThisChat : Delivery()
        data class ChildOfOtherChat(val sessionId: String) : Delivery()
    }

    fun delivery(mode: ScheduledTargetMode, hostSessionId: String?): Delivery = when (mode) {
        ScheduledTargetMode.NewSession -> Delivery.NewSession
        is ScheduledTargetMode.AppendToSession ->
            if (mode.sessionId == hostSessionId) Delivery.ThisChat else Delivery.OtherChat(mode.sessionId)
        is ScheduledTargetMode.RerunMessage -> Delivery.Rerun(mode.sessionId, mode.sessionId == hostSessionId)
        is ScheduledTargetMode.ChildOfCurrent ->
            if (mode.sessionId == hostSessionId) Delivery.ChildOfThisChat else Delivery.ChildOfOtherChat(mode.sessionId)
    }

    // ── prompt ──────────────────────────────────────────────────────────────

    /**
     * The clean prompt: the stored task's, else the envelope body (which
     * [ScheduledTaskMarker.parse] has already stripped of the prefill note
     * and the insertion reminder). Any leading "[…]" notes that still survive
     * — an envelope from another build — are removed as well.
     */
    fun cleanPrompt(task: ScheduledTask?, marker: ScheduledTaskMarker): String {
        val raw = task?.prompt?.takeIf { it.isNotBlank() } ?: marker.prompt
        var s = raw.trimStart()
        while (true) {
            s = when {
                s.startsWith("<system-reminder>") && s.contains("</system-reminder>") ->
                    s.substringAfter("</system-reminder>").trimStart()
                LEADING_NOTE.containsMatchIn(s) -> s.replaceFirst(LEADING_NOTE, "").trimStart()
                else -> return s.trimEnd()
            }
        }
    }

    /** "[Scheduled task …]" / "[This task's first step already ran …]". */
    private val LEADING_NOTE = Regex("""^\[(?:Scheduled task\b|This task's first step already ran\b)[^\]]*]""")
}
