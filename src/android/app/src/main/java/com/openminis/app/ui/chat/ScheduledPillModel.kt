package com.openminis.app.ui.chat

import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledSessionHold
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTriggerKind

/**
 * [T-port-sched-pill] The scheduled-task pill under the chat's top bar, as
 * plain data: which task it shows, its phase, the ring, the clock and the run
 * counter. Port of iOS `ScheduledBannerModel` (5d471e56d, e07004f03,
 * 33b4c3cc0, 9b60d1d89, 8e4c92542, 49540e6c5, 1a71c8333 - final state). Pure,
 * so the rules are JVM-tested.
 */
internal data class PillJob(
    val id: String,
    val title: String,
    /** Next planned run (wall ms); null for an on-completion task. */
    val nextFireAt: Long?,
    /** Where the wait the ring measures began. */
    val windowStart: Long,
    /** Runs left including the next one; null when unbounded / not counted. */
    val remaining: Int?,
    /** A run of this task is being processed right now. */
    val isFiringNow: Boolean,
    /** Runs already started. */
    val runsDone: Int,
    /** Planned runs in all (a counted loop); null when unbounded. */
    val runTotal: Int?,
    /** Runs more than once (loop / repeating calendar): only those show a counter. */
    val repeats: Boolean,
)

internal object ScheduledPillModel {
    enum class Phase {
        /** Waiting for the next run: countdown. */
        COUNTDOWN,
        /** A run of this task is in progress in the chat. */
        RUNNING,
        /** An on-completion task: no time to count down to. */
        WAITING,
    }

    data class State(val job: PillJob, val phase: Phase, /** "+N" */ val others: Int)

    /**
     * Which task the pill shows: one running now wins; else the soonest next
     * run; an on-completion task only when nothing has a time. null hides it.
     */
    fun state(jobs: List<PillJob>): State? {
        if (jobs.isEmpty()) return null
        val others = jobs.size - 1
        val soonest = compareBy<PillJob>({ it.nextFireAt ?: Long.MAX_VALUE }, { it.id })
        jobs.filter { it.isFiringNow }.minWithOrNull(soonest)?.let { return State(it, Phase.RUNNING, others) }
        jobs.filter { it.nextFireAt != null }.minWithOrNull(soonest)?.let { return State(it, Phase.COUNTDOWN, others) }
        val waiting = jobs.minWith(compareBy({ it.title }, { it.id }))
        return State(waiting, Phase.WAITING, others)
    }

    /** "00:48" / "59:59", seconds rounded up; null from an hour up (the caller writes "1h 5m"). */
    fun countdown(remainingMs: Long): String? {
        val s = ((remainingMs.coerceAtLeast(0) + 999) / 1000)
        if (s >= 3600) return null
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    /**
     * The coarse clock from an hour up, as (first unit, second unit) amounts:
     * hours + minutes (minutes rounded up) below a day, else days + hours.
     * The caller localizes it.
     */
    data class Coarse(val days: Long, val hours: Long, val minutes: Long)

    fun coarse(remainingMs: Long): Coarse {
        val s = (remainingMs.coerceAtLeast(0) + 999) / 1000
        if (s < 86_400) {
            val mins = (s + 59) / 60
            return Coarse(0, mins / 60, mins % 60)
        }
        return Coarse(s / 86_400, (s % 86_400) / 3600, 0)
    }

    /** Share of the wait already elapsed, 0..1; 0 without a next run. */
    fun progress(job: PillJob, now: Long): Float {
        val end = job.nextFireAt ?: return 0f
        val total = end - job.windowStart
        if (total <= 0) return 1f
        return ((now - job.windowStart).toFloat() / total).coerceIn(0f, 1f)
    }

    /** "This run": the one counting down, or the one in progress. */
    fun runNumber(job: PillJob, running: Boolean): Int? {
        if (!job.repeats) return null
        val n = if (running) maxOf(1, job.runsDone) else job.runsDone + 1
        return job.runTotal?.let { minOf(n, it) } ?: n
    }

    /** "2/30", "#7", or null for a one-shot. */
    fun runCounter(job: PillJob, running: Boolean): String? {
        val n = runNumber(job, running) ?: return null
        return job.runTotal?.let { "$n/$it" } ?: "#$n"
    }

    /**
     * The clock's width floor: every run of digits as "00" (at least as many
     * zeros as it has digits), so a ticking monospaced clock never changes
     * the pill's width until its format does.
     */
    fun widthTemplate(clock: String): String {
        val out = StringBuilder()
        var run = 0
        fun flush() {
            if (run > 0) repeat(maxOf(2, run)) { out.append('0') }
            run = 0
        }
        for (ch in clock) {
            if (ch in '0'..'9') run++ else { flush(); out.append(ch) }
        }
        flush()
        return out.toString()
    }

    /** "Hide this countdown" covers the tasks live when chosen; a new one brings the pill back. */
    fun isDismissed(live: Set<String>, hidden: Set<String>): Boolean =
        live.isNotEmpty() && hidden.containsAll(live)

    // --- From scheduled tasks ---------------------------------------------------

    /**
     * The tasks keeping [sessionId] active, as the pill sees them: the same
     * rule as busy / Stop / the ongoing notification
     * ([ScheduledSessionHold.heldSession]), plus a task whose run is in
     * progress here ([ScheduledSessionHold.runHold]) - the pill stays up,
     * with "Running", through a fire, even the last one the alarm already
     * disabled.
     *
     * [FIX-261025-03] Deliberately WITHOUT Settings > Background's countdown
     * lead (the default: the whole wait): the pill is the in-chat place to see
     * and cancel a schedule, while the lead only decides when the chat counts
     * as busy and the notification / status chip show the wait.
     */
    fun jobs(
        sessionId: String,
        tasks: List<ScheduledTask>,
        runningTaskIds: Set<String>,
        now: Long,
        upstreamUnfinished: (String) -> Boolean,
    ): List<PillJob> = tasks.mapNotNull { t ->
        val firing = t.id in runningTaskIds
        val held = ScheduledSessionHold.heldSession(t, now, upstreamUnfinished) == sessionId
        val runningHere = firing && ScheduledSessionHold.runHold(t) == sessionId
        if (!held && !runningHere) return@mapNotNull null
        pillJob(t, now, firing)
    }

    fun pillJob(t: ScheduledTask, now: Long, firing: Boolean): PillJob {
        val next = t.nextTriggerMs(now)
        val interval = t.triggerKind == ScheduledTriggerKind.INTERVAL
        val repeats = interval || (t.isCalendar && t.repeatMode != ScheduledRepeatMode.ONCE)
        val windowStart = when {
            interval && next != null && t.intervalSec != null -> next - t.intervalSec * 1000
            t.usesAnchor -> t.anchorMs ?: t.createdAt
            else -> t.lastFiredAt ?: t.createdAt
        }
        // An interval counts alarm fires when they happen (the running one
        // included); a calendar fire is recorded when its run completes.
        val runsDone = if (interval) t.triggeredCount ?: 0 else t.firesSoFar + if (firing) 1 else 0
        return PillJob(
            id = t.id,
            title = t.label.ifBlank { t.prompt.take(40) },
            nextFireAt = next,
            windowStart = windowStart,
            remaining = t.remainingFires,
            isFiringNow = firing,
            runsDone = runsDone,
            runTotal = if (interval) t.maxFires else null,
            repeats = repeats,
        )
    }
}
