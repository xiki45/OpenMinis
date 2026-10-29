package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.repository.BackgroundSettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * [T-scheduled-active-session-android] Which chats a scheduled task keeps
 * ACTIVE between its fires: they are not finished, they are waiting to be
 * woken. Port of iOS `AgentJobRegistry.sessionsKeptActiveBySchedule`
 * (f2891c01b).
 *
 * Only a task that comes back into the chat it was created in counts - a
 * follow-up ([ScheduledTargetMode.AppendToSession]) whose target is its own
 * [ScheduledTask.creatorSessionId] - because only then is "this chat is still
 * working" true from the user's side. A task made outside any chat, or one
 * that lands in a new chat, a different chat, a hidden child run or a rerun,
 * makes no chat active (iOS: new-chat, child, rerun, other-session and
 * creator-less jobs do not count). An on-completion task counts only while its
 * upstream still exists and is unfinished; otherwise it waits for something
 * that will never happen.
 *
 * The result feeds [com.openminis.app.service.SessionActivityTracker], which
 * unions it with the running loops into the one "processing" set that
 * keep-alive, Stop, the list spinner and the notification read.
 */
object ScheduledSessionHold {

    // BEGIN heldSession
    /**
     * The chat [task] keeps active at [now], or null. [upstreamUnfinished]
     * answers for an on-completion task's upstream id.
     *
     * [FIX-261025-03] [leadMs] is Settings > Background's "scheduled task
     * countdown": a timed task holds its chat only once its next run is at
     * most [leadMs] away (0 = never). Before, it held it for the whole wait,
     * so a daily task kept the foreground service, its notification and the
     * Live Updates chip up all day. The alarm starts the service itself when
     * the run is due, so nothing needs the hold before that. An on-completion
     * task has no clock: it holds unless the countdown is off.
     */
    fun heldSession(
        task: ScheduledTask,
        now: Long,
        upstreamUnfinished: (String) -> Boolean,
        leadMs: Long = Long.MAX_VALUE,
    ): String? {
        if (!task.enabled) return null
        if (leadMs <= 0L) return null
        val target = task.targetMode as? ScheduledTargetMode.AppendToSession ?: return null
        val creator = task.creatorSessionId ?: return null
        if (target.sessionId != creator) return null
        when (task.triggerKind) {
            ScheduledTriggerKind.ON_COMPLETION -> {
                val upstream = task.onCompletionOf ?: return null
                if (!upstreamUnfinished(upstream)) return null
            }
            // Disabled at its last fire (ONCE, AFTER, an INTERVAL's last of
            // --count), or no slot left (past the end date, no custom days).
            else -> {
                val next = task.nextTriggerMs(now) ?: return null
                if (next - now > leadMs) return null
            }
        }
        return creator
    }

    /**
     * [FIX-261025-03] The next instant at which a timed task enters its
     * [leadMs] window, so the held set can be re-derived then (nothing else
     * changes at that moment), or null when no task is waiting outside it.
     */
    fun nextWindowStart(tasks: List<ScheduledTask>, now: Long, leadMs: Long): Long? {
        if (leadMs <= 0L || leadMs == Long.MAX_VALUE) return null
        return tasks.asSequence()
            .filter { it.enabled && it.triggerKind != ScheduledTriggerKind.ON_COMPLETION && runHold(it) != null }
            .mapNotNull { it.nextTriggerMs(now) }
            .map { it - leadMs }
            .filter { it > now }
            .minOrNull()
    }

    /**
     * [FIX-261025-03] [derive] for every change of the inputs, and again each
     * time a task enters its countdown window.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun <I, T> windowed(
        inputs: Flow<I>,
        tasksOf: (I) -> List<ScheduledTask>,
        leadOf: (I) -> Long,
        derive: (I, Long) -> T,
    ): Flow<T> = inputs.flatMapLatest { i ->
        flow {
            while (true) {
                val now = System.currentTimeMillis()
                emit(derive(i, now))
                val next = nextWindowStart(tasksOf(i), now, leadOf(i)) ?: break
                // A little past the boundary so `next - now <= lead` holds.
                delay((next - System.currentTimeMillis()).coerceAtLeast(0L) + 50L)
            }
        }
    }
    // END heldSession

    /**
     * Whether [task] would still fire (iOS `liveScheduledJobs`: enabled and
     * pending): enabled with a next slot, or an enabled on-completion task
     * still waiting. What Stop turns off, and what its confirmation counts.
     */
    fun isLive(task: ScheduledTask, now: Long): Boolean =
        task.enabled && (task.triggerKind == ScheduledTriggerKind.ON_COMPLETION || task.nextTriggerMs(now) != null)

    fun heldSessions(
        tasks: List<ScheduledTask>,
        now: Long,
        upstreamUnfinished: (String) -> Boolean,
        leadMs: Long = Long.MAX_VALUE,
    ): Set<String> =
        tasks.mapNotNullTo(LinkedHashSet()) { heldSession(it, now, upstreamUnfinished, leadMs) }

    /**
     * Whether the upstream [id] of an on-completion task is still to finish:
     * a scheduled task being run right now, a scheduled task still due (an
     * enabled on-completion one is itself waiting), or an agent job still
     * pending or running. Agent jobs live in memory, so one from a previous
     * process is gone - and its dependents never fire, so they hold nothing.
     */
    fun upstreamUnfinished(
        id: String,
        tasks: List<ScheduledTask>,
        runningTaskIds: Set<String>,
        jobs: Map<String, AgentJob>,
        now: Long,
    ): Boolean {
        if (id in runningTaskIds) return true
        tasks.firstOrNull { it.id == id }?.let { t ->
            if (!t.enabled) return false
            return t.triggerKind == ScheduledTriggerKind.ON_COMPLETION || t.nextTriggerMs(now) != null
        }
        return jobs[id]?.isActive == true
    }

    /**
     * Tasks whose fire is being run right now (alarm received → agent loop
     * finished), by task id, counted per run. [ScheduledAgentRunner.run] holds
     * it for the whole run, which covers the two moments the store alone
     * cannot: the handoff before the turn starts, and the LAST fire of a task
     * that the alarm already disabled.
     */
    private val _runningRuns = MutableStateFlow<Map<String, Int>>(emptyMap())
    val runningTaskIds: StateFlow<Map<String, Int>> = _runningRuns.asStateFlow()

    fun beginRun(taskId: String) {
        _runningRuns.update { it + (taskId to ((it[taskId] ?: 0) + 1)) }
    }

    fun endRun(taskId: String) {
        _runningRuns.update { m ->
            val n = (m[taskId] ?: 0) - 1
            if (n > 0) m + (taskId to n) else m - taskId
        }
    }

    /**
     * The chats scheduled tasks keep active, re-derived on every change to the
     * persisted task list (create, edit, enable / disable, delete, each fire's
     * bookkeeping), to the runs in flight and to the agent jobs an
     * on-completion task may wait on. Persisted input, so it is right again
     * from the first emission after a process restart.
     */
    fun observe(context: Context): Flow<Set<String>> =
        windowed(
            combine(
                ScheduledTaskStore(context.applicationContext).observe(),
                _runningRuns,
                AgentJobRegistry.jobs,
                BackgroundSettingsRepository(context.applicationContext).scheduledCountdownLead,
            ) { tasks, running, jobs, lead ->
                Inputs(tasks, running.keys, jobs, BackgroundSettingsRepository.scheduledCountdownLeadMs(lead))
            },
            tasksOf = { it.tasks },
            leadOf = { it.leadMs },
        ) { i, now -> heldNow(i.tasks, i.runningIds, i.jobs, now, i.leadMs) }.distinctUntilChanged()

    /** [FIX-261025-03] One emission of the observed inputs. */
    internal data class Inputs(
        val tasks: List<ScheduledTask>,
        val runningIds: Set<String>,
        val jobs: Map<String, AgentJob>,
        val leadMs: Long,
    )

    /**
     * [T-port-sched-wait-hold-gap] What [observe] emits for one set of inputs:
     * the chats held by their schedule, plus the chat of every task whose fire
     * is being run right now. Pure, so a whole fire sequence can be replayed
     * in tests.
     */
    fun heldNow(
        tasks: List<ScheduledTask>,
        runningIds: Set<String>,
        jobs: Map<String, AgentJob>,
        now: Long,
        leadMs: Long = Long.MAX_VALUE,
    ): Set<String> =
        heldSessions(tasks, now, { upstreamUnfinished(it, tasks, runningIds, jobs, now) }, leadMs) +
            tasks.filter { it.id in runningIds }.mapNotNull(::runHold)

    /**
     * The chat a task keeps active while one of its fires is being run, enabled
     * or not (the alarm disables a task at its last fire, before the run): the
     * same follow-up-to-creator test as [heldSession], without the schedule.
     */
    fun runHold(task: ScheduledTask): String? {
        val target = task.targetMode as? ScheduledTargetMode.AppendToSession ?: return null
        return target.sessionId.takeIf { it == task.creatorSessionId }
    }
}
