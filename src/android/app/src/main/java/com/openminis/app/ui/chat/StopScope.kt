package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-stop-choice] What a Stop can reach in one conversation, counted at tap
 * time. The composer asks "stop the current tool only, or everything?" only
 * when something besides the turn itself would be lost: a sub agent (running
 * or queued for a slot) or a scheduled task this chat created that would
 * still fire. Background shell processes are not counted - nothing registers
 * them - so the dialog only mentions that Stop All ends them too.
 */
data class StopSummary(
    /** Tool calls of the running turn that are executing right now. */
    val runningTools: List<String> = emptyList(),
    val subAgentsRunning: Int = 0,
    val subAgentsQueued: Int = 0,
    val scheduledTasks: Int = 0,
) {
    val hasBackground: Boolean get() = subAgentsRunning > 0 || subAgentsQueued > 0 || scheduledTasks > 0
}

/**
 * [T-stop-choice] How "Stop Current Tool Only" ends the foreground work.
 *
 *  - [INTERRUPT_TOOLS]: tool calls are executing. Each is cut short and
 *    answers with [ForegroundToolInterrupts.INTERRUPTED_RESULT]; the loop
 *    carries on to its post-tool boundary, where a queued user message goes
 *    in as the next round (the existing QueueInterrupt path) and otherwise
 *    the turn ends there ([endsTurnAtBoundary]).
 *  - [END_TURN]: no tool is executing (the model is writing, or the request
 *    is in flight). The turn ends the way Stop always ended it, minus the
 *    background: sub agents, their results and scheduled tasks are kept.
 */
enum class ToolOnlyStop { INTERRUPT_TOOLS, END_TURN }

object StopScopePolicy {

    fun toolOnlyStop(toolsRunning: Boolean): ToolOnlyStop =
        if (toolsRunning) ToolOnlyStop.INTERRUPT_TOOLS else ToolOnlyStop.END_TURN

    /**
     * After a tool-only stop the loop does not ask the model again on its own:
     * with nothing of the user's to answer it would only retry the tool that
     * was just stopped. A user message (or a due scheduled fire) waiting to be
     * inserted keeps it going, as an inserted round.
     */
    fun endsTurnAtBoundary(stopRequestedThisTurn: Boolean, insertPending: Boolean): Boolean =
        stopRequestedThisTurn && !insertPending

    /** The model-facing note for scheduled tasks a Stop All turned off (iOS parity). */
    fun scheduledCancelledNotice(labels: List<String>): String? {
        if (labels.isEmpty()) return null
        val names = labels.joinToString(", ") { "\"${it.take(80)}\"" }
        return "<system-reminder>The user pressed Stop All in this conversation. This also cancelled " +
            "${labels.size} scheduled task(s) created here: $names. They are disabled and will not fire again " +
            "unless the user turns them back on - do not wait for them or reschedule them unless asked." +
            "</system-reminder>"
    }
}

/**
 * [T-stop-choice] The tool calls of this chat's loop that are executing right
 * now, so "Stop Current Tool Only" can cut them short without cancelling the
 * turn they belong to (which would take the whole loop down with them).
 *
 * Each call runs as a child of its own slot; cancelling that child ends only
 * the call, and [run] turns the cancellation into an ordinary tool result so
 * tool_use / tool_result stay paired and the loop continues. A cancellation
 * that comes from above (the turn itself was stopped) is rethrown untouched.
 *
 * A call registered as not [Entry.cancellable] is never cancelled here; its
 * owner ends it another way (a shell's process group is killed so the call
 * returns by itself; a waiting delegation is moved to the background).
 */
class ForegroundToolInterrupts {

    private class Entry(val name: String, val job: Job, val cancellable: Boolean)

    private val running = ConcurrentHashMap<String, Entry>()
    private val interrupted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    data class Outcome<T>(val value: T, val interrupted: Boolean)

    fun runningNames(): List<String> = running.values.map { it.name }

    fun isEmpty(): Boolean = running.isEmpty()

    fun isRunning(id: String): Boolean = running.containsKey(id)

    fun nameOf(id: String): String? = running[id]?.name

    /**
     * [FIX-261025-05] Whether [id] was interrupted and is still running. A
     * non-cancellable call (a shell) checks this while it is still waiting -
     * a `delay` countdown, a bash install - so it can stop before starting
     * anything, instead of only when its process group is killed.
     */
    fun isInterrupted(id: String): Boolean = interrupted.contains(id)

    fun idsNamed(name: String): List<String> = running.entries.filter { it.value.name == name }.map { it.key }

    /**
     * Run one tool call. [onInterrupted] supplies the result when the call was
     * cancelled by [interrupt]; a call that returned by itself after an
     * interrupt (a killed shell) comes back with [Outcome.interrupted] set.
     */
    suspend fun <T> run(
        id: String,
        name: String,
        cancellable: Boolean,
        onInterrupted: () -> T,
        body: suspend () -> T,
    ): Outcome<T> = coroutineScope {
        val call = async { body() }
        running[id] = Entry(name, call, cancellable)
        try {
            val value = call.await()
            Outcome(value, interrupted.contains(id))
        } catch (e: CancellationException) {
            if (interrupted.contains(id) && currentCoroutineContext().isActive) {
                Outcome(onInterrupted(), true)
            } else {
                throw e
            }
        } finally {
            running.remove(id)
            interrupted.remove(id)
        }
    }

    /**
     * Interrupt [ids] (all running calls when null). Returns the ids marked;
     * cancellable ones are cancelled at once.
     */
    fun interrupt(ids: Collection<String>? = null): List<String> {
        val targets = ids?.filter { running.containsKey(it) } ?: running.keys.toList()
        for (id in targets) {
            interrupted.add(id)
            // Finished meanwhile: its finally already ran, so drop the mark here.
            val entry = running[id] ?: run { interrupted.remove(id); null } ?: continue
            if (entry.cancellable) entry.job.cancel(CancellationException("stopped by the user (tool only)"))
        }
        return targets
    }

    companion object {
        /** What a cut-short call answers. Read by the model, so it says what to do. */
        const val INTERRUPTED_RESULT =
            "[Stopped by the user] This tool call was interrupted before it finished; any output above is partial. " +
                "Do not run it again on your own - wait for the user's next message."

        fun withInterruptNote(output: String): String =
            if (output.isBlank()) INTERRUPTED_RESULT else "$output\n\n$INTERRUPTED_RESULT"
    }
}
