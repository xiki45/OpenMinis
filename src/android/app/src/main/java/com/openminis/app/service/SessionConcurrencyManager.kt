package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.LinkedList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Limits concurrent agent loop sessions to [maxConcurrent].
 * Excess sessions are suspended in a FIFO queue until a slot frees up.
 *
 * Sub agent (helper) child sessions never take a slot here: their loop skips
 * acquireSlot (`helperConfig != null`), and they are capped separately, per
 * parent, by [com.openminis.app.agent.jobs.AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT].
 */
object SessionConcurrencyManager {
    /** [T-new-limits] Agent loop sessions running at once, global (was 5). */
    const val MAX_CONCURRENT = 6

    /**
     * [T-loadtest-limits] The cap admission actually uses: [MAX_CONCURRENT],
     * unless a DEV_TOOLS build overrides it for a load test
     * ([debugSetMaxConcurrent], RPC `debug.limits.set`). In memory only, so
     * every process start is back on the default; release builds can never
     * change it.
     */
    @Volatile
    var maxConcurrent: Int = MAX_CONCURRENT
        private set

    /**
     * [T-loadtest-limits] DEV_TOOLS only: set the cap (null = default),
     * clamped to 1..32. Raising it admits queued turns at once. Returns the
     * cap in effect.
     */
    fun debugSetMaxConcurrent(n: Int?): Int {
        if (!com.openminis.app.BuildConfig.DEV_TOOLS) return maxConcurrent
        maxConcurrent = (n ?: MAX_CONCURRENT).coerceIn(1, 32)
        while (true) {
            val next = synchronized(this) {
                if (_runningSessions.value.size < maxConcurrent) waitQueue.pollFirst() else null
            } ?: break
            _suspendedSessions.value = _suspendedSessions.value - next.sessionId
            _runningSessions.value = _runningSessions.value + next.sessionId
            next.continuation.resume(Unit)
        }
        println("[T-loadtest-limits] maxConcurrent=$maxConcurrent")
        return maxConcurrent
    }

    /** [T-STALL-DIAG] Heartbeat cadence while a turn is blocked on a slot. */
    private const val SLOT_WAIT_WARN_MS = 10_000L

    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()

    private val _suspendedSessions = MutableStateFlow<List<String>>(emptyList())
    val suspendedSessions: StateFlow<List<String>> = _suspendedSessions.asStateFlow()

    private data class Waiter(val sessionId: String, val continuation: Continuation<Unit>)
    private val waitQueue = LinkedList<Waiter>()

    suspend fun acquireSlot(sessionId: String) {
        // [T-new-limits] Check-and-take under the lock: two turns starting at
        // once both saw "a slot is free" and both took it, one over the cap.
        val fast = synchronized(this) {
            if (_runningSessions.value.size < maxConcurrent) {
                _runningSessions.value = _runningSessions.value + sessionId
                true
            } else false
        }
        if (fast) {
            // [T-STALL-DIAG] Fast path taken — record who now holds slots so a
            // later leak can be traced back to the turn that opened it.
            println(
                "[T-STALL-DIAG] slot ACQUIRED-fast sid=$sessionId " +
                    "running=${_runningSessions.value.size}/$maxConcurrent " +
                    "holders=${_runningSessions.value.joinToString(",")}",
            )
            return
        }

        // [T-STALL-DIAG] SLOW PATH — every slot is taken, so this turn is about
        // to suspend with NO timeout. This is the prime suspect for the reported
        // "new session sits on thinking… forever, nothing in the UI, stop button
        // still armed": if a streamJob is killed before its `finally`
        // (process death, cancellation gap), its id is never removed from
        // _runningSessions, and after MAX_CONCURRENT such leaks EVERY new turn
        // blocks here silently and indefinitely.
        //
        // Log WHO holds the slots at the moment we start waiting, then emit a
        // heartbeat while still blocked, so the log distinguishes:
        //   - "waiting, holders are real live sessions"  → legitimate queueing
        //   - "waiting, holders are stale/unknown ids"   → leaked slots (bug)
        val holdersAtWait = _runningSessions.value.toList()
        val waitStartMs = android.os.SystemClock.elapsedRealtime()
        println(
            "[T-STALL-DIAG] slot WAIT-BEGIN sid=$sessionId " +
                "running=${holdersAtWait.size}/$maxConcurrent " +
                "holders=${holdersAtWait.joinToString(",")} " +
                "queueDepth=${_suspendedSessions.value.size}",
        )
        val watchdog = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var waited = 0L
            while (true) {
                kotlinx.coroutines.delay(SLOT_WAIT_WARN_MS)
                waited += SLOT_WAIT_WARN_MS
                println(
                    "[T-STALL-DIAG] slot STILL-WAITING sid=$sessionId waitedMs=$waited " +
                        "holders=${_runningSessions.value.joinToString(",")} " +
                        "queueDepth=${_suspendedSessions.value.size} " +
                        "— if these holders are not live turns, slots have LEAKED",
                )
            }
        }

        // Queue and suspend
        // [T-new-limits] Under the lock like every other write to it: two turns
        // starting to wait at once each read the old list and one overwrote
        // the other, so isSuspended() missed a session that was waiting.
        synchronized(this) { _suspendedSessions.value = _suspendedSessions.value + sessionId }
        try {
            suspendCancellableCoroutine { cont ->
                // [T-new-limits] Re-check under the lock that guards the queue:
                // a release landing between the fast-path check and this point
                // found no waiter to wake, and the turn then waited with a free
                // slot until some later release.
                val admitted = synchronized(this@SessionConcurrencyManager) {
                    if (_runningSessions.value.size < maxConcurrent) {
                        _runningSessions.value = _runningSessions.value + sessionId
                        _suspendedSessions.value = _suspendedSessions.value - sessionId
                        true
                    } else {
                        waitQueue.add(Waiter(sessionId, cont))
                        false
                    }
                }
                if (admitted) {
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    synchronized(this@SessionConcurrencyManager) {
                        waitQueue.removeAll { it.sessionId == sessionId }
                        _suspendedSessions.value = _suspendedSessions.value - sessionId
                    }
                }
            }
        } finally {
            watchdog.cancel()
            println(
                "[T-STALL-DIAG] slot WAIT-END sid=$sessionId " +
                    "waitedMs=${android.os.SystemClock.elapsedRealtime() - waitStartMs}",
            )
        }
    }

    /**
     * [T-STALL-DIAG] Snapshot for the send path to log BEFORE it tries to
     * acquire — so a turn that never reaches "slot acquired" still leaves a
     * record of what the manager looked like at that moment.
     */
    fun diagSnapshot(): String =
        "running=${_runningSessions.value.size}/$maxConcurrent " +
            "holders=${_runningSessions.value.joinToString(",")} " +
            "suspended=${_suspendedSessions.value.joinToString(",")}"

    @Synchronized
    fun releaseSlot(sessionId: String) {
        val had = sessionId in _runningSessions.value
        _runningSessions.value = _runningSessions.value - sessionId
        println(
            "[T-STALL-DIAG] slot RELEASED sid=$sessionId wasHeld=$had " +
                "running=${_runningSessions.value.size}/$maxConcurrent " +
                "holders=${_runningSessions.value.joinToString(",")}",
        )

        // Resume the next waiter only when a slot is actually free.
        // [T-new-limits] A release that held nothing — a turn cancelled while
        // it was still WAITING runs its `finally` release too — used to wake a
        // waiter anyway, admitting one turn more than the cap (7 of 6).
        val next = synchronized(this) {
            if (_runningSessions.value.size < maxConcurrent) waitQueue.pollFirst() else null
        }
        if (next != null) {
            _suspendedSessions.value = _suspendedSessions.value - next.sessionId
            _runningSessions.value = _runningSessions.value + next.sessionId
            next.continuation.resume(Unit)
        }
    }

    fun isSuspended(sessionId: String): Boolean = sessionId in _suspendedSessions.value
}
