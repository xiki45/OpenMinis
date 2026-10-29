package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.repository.BackgroundSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [T-port-sched-wait-notification] What the ongoing notification shows while a
 * chat waits for its next scheduled run - the Android counterpart of the iOS
 * Live Activity's waiting card (fa7adaf9e, `liveActivityWaitCards`).
 */
data class ScheduledWait(
    /** The chat the task keeps active (its creator, see [ScheduledSessionHold]). */
    val sessionId: String,
    val taskId: String,
    val label: String,
    /** Wall-clock ms of the next run; null for an on-completion wait (no clock). */
    val nextRunMs: Long?,
    /** "2/30" (this run of a counted loop), "#7" (unbounded), null for a one-shot. */
    val runCounter: String?,
    /** The chat's title, filled in by the app from the session row. */
    val chatTitle: String? = null,
)

/**
 * [T-port-sched-running-counter] A scheduled fire whose run is in progress in
 * a chat it keeps active, with that run's counter - iOS's "Working… · 2/30"
 * on the waiting card while the fire runs (ScheduledBannerModel.runCounter
 * with running = true).
 */
data class ScheduledFiring(
    val sessionId: String,
    val taskId: String,
    /** "2/30" (this run of a counted loop) or "#7" (unbounded / repeating calendar). */
    val runCounter: String,
)

object ScheduledWaitModel {

    /**
     * Run number of the NEXT run of [task] (iOS `ScheduledBannerModel.runNumber`
     * with running = false): only a repeating schedule numbers its runs, and a
     * counted loop never shows more than its total.
     *
     * Counts match the chat card's ([ScheduledTaskMarker.forFire]): an
     * interval counts alarm fires since its last (re)start
     * ([ScheduledTask.triggeredCount], against [ScheduledTask.maxFires]); a
     * repeating calendar task counts recorded fires.
     */
    fun nextRunNumber(task: ScheduledTask): Int? = when {
        task.triggerKind == ScheduledTriggerKind.INTERVAL -> {
            val n = (task.triggeredCount ?: 0) + 1
            task.maxFires?.let { minOf(n, it) } ?: n
        }
        task.isCalendar && task.repeatMode != ScheduledRepeatMode.ONCE -> task.firesSoFar + 1
        else -> null
    }

    /**
     * [T-port-sched-running-counter] Number of the run IN PROGRESS (iOS
     * runNumber with running = true). An interval's alarm counts its fire
     * before the run starts, so the running fire is triggeredCount itself; a
     * calendar fire is recorded when its run completes, so the running one is
     * firesSoFar + 1. One-shots have no number.
     */
    fun runningRunNumber(task: ScheduledTask): Int? = when {
        task.triggerKind == ScheduledTriggerKind.INTERVAL -> {
            val n = maxOf(1, task.triggeredCount ?: 0)
            task.maxFires?.let { minOf(n, it) } ?: n
        }
        task.isCalendar && task.repeatMode != ScheduledRepeatMode.ONCE -> task.firesSoFar + 1
        else -> null
    }

    /** [T-port-sched-running-counter] The running run's counter, "2/30" / "#7", or null. */
    fun runningCounter(task: ScheduledTask): String? {
        val n = runningRunNumber(task) ?: return null
        val total = task.maxFires.takeIf { task.triggerKind == ScheduledTriggerKind.INTERVAL }
        return if (total != null) "$n/$total" else "#$n"
    }

    /**
     * [T-port-sched-running-counter] The fire being run right now in a chat
     * it keeps active (ScheduledSessionHold.runHold - the follow-up-to-creator
     * rule), with a counter; null when none. Several at once (rare): the
     * lowest task id, for a stable choice.
     */
    fun firing(tasks: List<ScheduledTask>, runningIds: Set<String>): ScheduledFiring? =
        tasks.asSequence()
            .filter { it.id in runningIds }
            .mapNotNull { t ->
                val sid = ScheduledSessionHold.runHold(t) ?: return@mapNotNull null
                val counter = runningCounter(t) ?: return@mapNotNull null
                ScheduledFiring(sid, t.id, counter)
            }
            .minByOrNull { it.taskId }

    /** [T-port-sched-running-counter] [firing], re-derived when tasks or runs change. */
    fun observeFiring(context: Context): Flow<ScheduledFiring?> =
        combine(
            ScheduledTaskStore(context.applicationContext).observe(),
            ScheduledSessionHold.runningTaskIds,
        ) { tasks, running -> firing(tasks, running.keys) }.distinctUntilChanged()

    /** iOS `ScheduledBannerModel.runCounter`: "2/30", "#7", or null. */
    fun runCounter(task: ScheduledTask): String? {
        val n = nextRunNumber(task) ?: return null
        val total = task.maxFires.takeIf { task.triggerKind == ScheduledTriggerKind.INTERVAL }
        return if (total != null) "$n/$total" else "#$n"
    }

    /**
     * The wait to show, among the tasks that keep a chat active (the same test
     * as [ScheduledSessionHold.heldSession]): the soonest next run; an
     * on-completion task only when nothing has a time (iOS
     * `ScheduledBannerModel.state`). Ties go to the lower task id so the choice
     * is stable. null when no task holds a chat.
     */
    fun pick(
        tasks: List<ScheduledTask>,
        now: Long,
        leadMs: Long = Long.MAX_VALUE,
        upstreamUnfinished: (String) -> Boolean,
    ): ScheduledWait? {
        // [FIX-261025-03] Same countdown window as the hold, so the waiting
        // card appears exactly when the chat becomes active again.
        val held = tasks.mapNotNull { t ->
            ScheduledSessionHold.heldSession(t, now, upstreamUnfinished, leadMs)?.let { sid -> t to sid }
        }
        if (held.isEmpty()) return null
        val timed = held.mapNotNull { (t, sid) -> t.nextTriggerMs(now)?.let { Triple(t, sid, it) } }
            .minWithOrNull(compareBy<Triple<ScheduledTask, String, Long>> { it.third }.thenBy { it.first.id })
        if (timed != null) {
            val (t, sid, next) = timed
            return ScheduledWait(sid, t.id, t.label, next, runCounter(t))
        }
        val (t, sid) = held.minWith(compareBy<Pair<ScheduledTask, String>> { it.first.label }.thenBy { it.first.id })
        return ScheduledWait(sid, t.id, t.label, null, runCounter(t))
    }

    /**
     * [pick], re-derived from the same inputs as [ScheduledSessionHold.observe]
     * (persisted tasks, runs in flight, agent jobs, the countdown setting), so
     * it changes exactly when a run starts or ends, a task is edited or enters
     * its countdown window - never per second.
     */
    fun observe(context: Context): Flow<ScheduledWait?> =
        ScheduledSessionHold.windowed(
            combine(
                ScheduledTaskStore(context.applicationContext).observe(),
                ScheduledSessionHold.runningTaskIds,
                AgentJobRegistry.jobs,
                BackgroundSettingsRepository(context.applicationContext).scheduledCountdownLead,
            ) { tasks, running, jobs, lead ->
                ScheduledSessionHold.Inputs(tasks, running.keys, jobs, BackgroundSettingsRepository.scheduledCountdownLeadMs(lead))
            },
            tasksOf = { it.tasks },
            leadOf = { it.leadMs },
        ) { i, now ->
            pick(i.tasks, now, i.leadMs) { ScheduledSessionHold.upstreamUnfinished(it, i.tasks, i.runningIds, i.jobs, now) }
        }.distinctUntilChanged()
}
