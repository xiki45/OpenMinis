package com.openminis.app.agent.userask

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * [T-port-user-ask] Runtime of the user_ask tool (iOS `UserAskCenter`, final
 * state): live asks, the wait the agent loop suspends on, the wall-clock
 * timeout, and the card's actions.
 *
 * The same pattern as ConfigConfirmationGate: a tool call suspends on a
 * continuation that the UI (or the timeout) resumes. Every resolution goes
 * through PendingAsk's resolve*, which accepts only the first terminal state,
 * so the card, the timeout, Stop and a new user message can race safely.
 *
 * Privacy: the user's answer text (Other / note) is never logged - only
 * statuses and lengths (iOS 4f7a72d76).
 *
 * [T-android-user-ask-notify] A question asked while the app is away is also
 * posted as a notification (UserAskNotifier) and can be answered there
 * ([answerFromNotification]) through the same first-wins resolution.
 *
 * [T-user-ask-deferred] A deferred ask (iOS 7a818b8c0) has no tool call
 * waiting on it: it is never in-process, Stop leaves it open, and its
 * resolution goes to [onDeferredResolved], which hands the answer on.
 */
object UserAskCenter {
    private const val TAG = "UserAsk"

    private val lock = Any()
    private val _asks = MutableStateFlow<Map<String, PendingAsk>>(emptyMap())

    /** Live asks by requestId (pending and recently resolved, for the card). */
    val asks: StateFlow<Map<String, PendingAsk>> = _asks.asStateFlow()

    private val waiters = HashMap<String, CancellableContinuation<String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    /** Wall clock; replaceable in tests. */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Called after every change to an ask (and once when one begins), so the
     * persistence layer can save it. Set by the app; null in tests.
     */
    @Volatile var onChanged: ((PendingAsk) -> Unit)? = null

    /**
     * Called when an ask resolves while no tool call waits on it (the app was
     * killed meanwhile and recovery re-registered it): hand the result over.
     */
    @Volatile var onResolvedWithoutWaiter: ((PendingAsk) -> Unit)? = null

    /**
     * [T-user-ask-deferred] Called when a DEFERRED ask resolves (answered,
     * dismissed, replaced, timed out): fill in its stored result and, for a
     * real answer, send it as the user's next message.
     */
    @Volatile var onDeferredResolved: ((PendingAsk) -> Unit)? = null

    /** Asks whose tool call runs in this process; recovery never delivers these. */
    private val inProcess = HashSet<String>()

    /**
     * [T-user-ask-deferred-live-batch] Deferred asks whose own tool batch is
     * still running in this process, so their `deferred` result is not stored
     * yet. One resolved meanwhile (answered from the notification while a
     * sibling tool runs, or replaced by a second user_ask in the same batch)
     * is held in [deferredHeld] until the loop stores the batch
     * ([markDelivered]) or the run ends ([releaseUnstoredDeferred]). Handed on
     * at once, the delivery found no stored result, took the "killed before
     * the batch was written" fallback and appended the assistant row and a
     * result row, and then the loop stored the same tool_use ids again.
     */
    private val deferredUnstored = HashSet<String>()
    private val deferredHeld = HashSet<String>()

    // ── Lookup ─────────────────────────────────────────────────────────────

    fun ask(forToolUseId: String?): PendingAsk? =
        forToolUseId?.let { id -> _asks.value.values.firstOrNull { it.toolCallId == id } }

    fun pendingAsks(sessionId: String): List<PendingAsk> =
        _asks.value.values.filter { it.sessionId == sessionId && it.state == UserAskState.PENDING }

    fun hasPendingAsk(sessionId: String?): Boolean = sessionId != null && pendingAsks(sessionId).isNotEmpty()

    /**
     * [T-port-user-ask-fk-crash] Mark [requestId] as owned by a tool call in
     * this process BEFORE its record is written, so a recovery scan running
     * concurrently can never mistake the live ask for one left by a kill.
     */
    fun claim(requestId: String) = synchronized(lock) { inProcess += requestId }

    /** Whether a tool call in this process owns this ask's delivery. */
    fun isInProcess(requestId: String): Boolean = synchronized(lock) { requestId in inProcess }

    // ── Start and wait ─────────────────────────────────────────────────────

    /**
     * Register a new ask: the first open question is shown with its
     * recommended option preselected. [ownedByToolCall] = a tool call in this
     * process will [wait] on it (false for an ask restored after a kill).
     */
    fun begin(ask: PendingAsk, ownedByToolCall: Boolean = true) {
        var a = ask
        val first = a.questions.indices.firstOrNull { it !in a.submitted }
        if (first != null) a = PendingAsk.preselectRecommended(a.copy(currentIndex = first), first)
        synchronized(lock) {
            // [T-user-ask-deferred] No tool call waits on a deferred ask.
            if (ownedByToolCall && !a.deferred) inProcess += a.requestId
            _asks.value = _asks.value + (a.requestId to a)
        }
        onChanged?.invoke(a)
        AppLogger.info(
            TAG,
            "[UserAsk] BEGIN req=${a.requestId.take(8)} tool=${a.toolCallId.take(16)} sid=${a.sessionId.take(8)} " +
                "questions=${a.questions.size} types=${a.questions.map { it.type.wire }} timeout=${a.timeoutMinutes}m " +
                "owned=$ownedByToolCall state=${a.state.wire}",
        )
        if (a.state == UserAskState.PENDING) startTicking()
    }

    /**
     * [T-user-ask-deferred] Start a deferred ask: only one stays open per
     * conversation, so an older open deferred card is closed as replaced.
     */
    fun beginDeferred(ask: PendingAsk) {
        val older = _asks.value.values.filter { it.sessionId == ask.sessionId && it.deferred && it.state == UserAskState.PENDING }
        for (o in older) dismiss(o.requestId, UserAskDismissReason.REPLACED_BY_NEW_ASK)
        synchronized(lock) { deferredUnstored += ask.requestId }
        begin(ask.copy(deferred = true), ownedByToolCall = false)
    }

    /**
     * [T-user-ask-deferred-live-batch] The run of [sessionId] ended: any of its
     * deferred asks whose batch was never stored (stopped, failed) is released,
     * and one that resolved meanwhile is handed on now - its delivery then
     * finds no stored result and writes one, which is right when nothing else
     * will.
     */
    fun releaseUnstoredDeferred(sessionId: String) {
        val held: List<String> = synchronized(lock) {
            val mine = deferredUnstored.filter { id -> _asks.value[id]?.sessionId?.let { it == sessionId } ?: true }
            deferredUnstored -= mine.toSet()
            mine.filter { deferredHeld.remove(it) }
        }
        for (id in held) _asks.value[id]?.let { onDeferredResolved?.invoke(it) }
    }

    /** Open deferred asks of a session (a new user message answers them). */
    fun pendingDeferred(sessionId: String): List<PendingAsk> = pendingAsks(sessionId).filter { it.deferred }

    /**
     * Suspend until the ask resolves; returns the tool_result JSON.
     * Cancellation (Stop, the run being torn down) resolves it as stopped.
     */
    suspend fun wait(requestId: String): String {
        _asks.value[requestId]?.let { if (it.state.isTerminal && it.resultJson != null) return it.resultJson }
        return suspendCancellableCoroutine { cont ->
            val done: String? = synchronized(lock) {
                val a = _asks.value[requestId]
                if (a != null && a.state.isTerminal && a.resultJson != null) a.resultJson
                else { waiters[requestId] = cont; null }
            }
            if (done != null) cont.resume(done)
            cont.invokeOnCancellation {
                synchronized(lock) { waiters.remove(requestId) }
                dismiss(requestId, UserAskDismissReason.STOPPED)
            }
        }
    }

    // ── Card actions ───────────────────────────────────────────────────────

    /** `userAction = false` for a programmatic draft that must not restart the countdown. */
    fun updateAnswer(requestId: String, index: Int, answer: UserAskAnswer, userAction: Boolean = true) {
        mutate(requestId) { a ->
            val next = a.setAnswer(answer, index)
            if (userAction) next.noteInteraction(clock()) else next
        }
    }

    fun noteInteraction(requestId: String) = mutate(requestId) { it.noteInteraction(clock()) }

    fun goTo(requestId: String, index: Int) = mutate(requestId) { it.goTo(index, clock()) ?: it }

    /** Next / Submit on one question: advance like a wizard, or resolve when all are in. */
    fun submitQuestion(requestId: String, index: Int) {
        var complete = false
        mutate(requestId) { a ->
            val (next, outcome) = a.submit(index, clock())
            when (outcome) {
                is UserAskSubmitOutcome.Advanced -> PendingAsk.preselectRecommended(next, outcome.to)
                UserAskSubmitOutcome.Complete -> { complete = true; next }
                UserAskSubmitOutcome.Rejected -> a
            }
        }
        if (complete) resolve(requestId, "ANSWERED") { it.resolveAnswered(UserAskAnswerSource.APP) }
    }

    /** A confirm button: records the choice (and a cancel reason) and submits that question. */
    fun confirm(requestId: String, index: Int, choice: UserAskConfirmChoice, note: String? = null) {
        val current = _asks.value[requestId]?.answers?.getOrNull(index) ?: return
        var a = current.copy(confirmChoice = choice)
        if (choice == UserAskConfirmChoice.DENY && note != null) a = a.copy(note = note)
        updateAnswer(requestId, index, a)
        submitQuestion(requestId, index)
    }

    // ── Notification ───────────────────────────────────────────────────────

    /** Record that a notification was posted for question [index] (once per question). */
    fun markNotified(requestId: String, index: Int) = mutate(requestId) { it.copy(notifiedIndex = index) }

    /**
     * [T-android-user-ask-notify] An answer given in the notification for
     * question [index]: an option button, the "Other…" text reply, or Allow /
     * Cancel. Checked and applied under the same lock as every card action, and
     * resolved through the same first-wins path, so it races safely with the
     * card, the timeout and Stop. [text] is never logged.
     */
    fun answerFromNotification(requestId: String, index: Int, actionId: String, text: String?): UserAskNotificationPlan.Outcome {
        var outcome = UserAskNotificationPlan.Outcome.NOT_FOUND
        val changed: PendingAsk? = synchronized(lock) {
            val cur = _asks.value[requestId] ?: return@synchronized null
            val (next, o) = UserAskNotificationPlan.apply(cur, index, actionId, text, clock())
            outcome = o
            if (next == cur) return@synchronized null
            _asks.value = _asks.value + (requestId to next)
            next
        }
        changed?.let { onChanged?.invoke(it) }
        AppLogger.info(
            TAG,
            "[UserAsk] NOTIFICATION action=${actionId.substringBefore(':')} q=${index + 1} req=${requestId.take(8)} " +
                "outcome=$outcome textLen=${text?.length ?: 0}",
        )
        if (outcome == UserAskNotificationPlan.Outcome.COMPLETE) {
            resolve(requestId, "ANSWERED") { it.resolveAnswered(UserAskAnswerSource.NOTIFICATION) }
        }
        return outcome
    }

    // ── Dismissal ──────────────────────────────────────────────────────────

    /** Stop, a new user message in the same session, or an explicit close. */
    fun dismiss(requestId: String, reason: UserAskDismissReason) {
        resolve(requestId, "DISMISSED reason=${reason.wire}") { it.resolveDismissed(reason) }
    }

    fun dismissPending(sessionId: String, reason: UserAskDismissReason) {
        for (a in pendingAsks(sessionId)) {
            // [T-user-ask-deferred] Stop ends the running wait; a deferred card
            // belongs to no running call and stays open. A new user message
            // still replaces it (the user answered in their own words).
            if (reason == UserAskDismissReason.STOPPED && a.deferred) continue
            dismiss(a.requestId, reason)
        }
    }

    // ── Timeout ────────────────────────────────────────────────────────────

    /** Wall clock: an ask that expired while the process slept resolves on the next tick. */
    internal fun tick(nowMs: Long = clock()) {
        for (a in _asks.value.values) {
            if (a.isExpired(nowMs)) resolve(a.requestId, "TIMED OUT - nothing applied") { it.resolveTimedOut() }
        }
    }

    private fun startTicking() {
        synchronized(lock) {
            if (tickJob?.isActive == true) return
            tickJob = scope.launch {
                while (isActive) {
                    delay(1_000)
                    tick()
                    if (_asks.value.values.none { it.state == UserAskState.PENDING }) break
                }
            }
        }
    }

    // ── Delivery bookkeeping ───────────────────────────────────────────────

    /** The agent loop stored the tool_result for these calls: they are done. */
    fun markDelivered(toolUseIds: Collection<String>) {
        for (id in toolUseIds) {
            val a = ask(forToolUseId = id) ?: continue
            // [T-user-ask-deferred] The loop stored the `deferred` result; the
            // ask stays until its ANSWER is handed on (markDeferredConsumed).
            if (a.deferred && !a.consumed) {
                // [T-user-ask-deferred-live-batch] Stored now: a resolution
                // held back for it goes on.
                val release = synchronized(lock) {
                    deferredUnstored -= a.requestId
                    deferredHeld.remove(a.requestId)
                }
                if (release) _asks.value[a.requestId]?.let { onDeferredResolved?.invoke(it) }
                continue
            }
            mutate(a.requestId) { it.copy(consumed = true) }
            synchronized(lock) { inProcess -= a.requestId }
            AppLogger.info(TAG, "[UserAsk] DELIVERED req=${a.requestId.take(8)} tool=${id.take(16)}")
        }
    }

    /**
     * [T-user-ask-deferred] The answer of a deferred ask is being handed on:
     * it is done, so it is neither delivered twice nor restored after a restart.
     * Returns false when it was already consumed (first caller wins).
     */
    fun markDeferredConsumed(requestId: String): Boolean {
        var won = false
        mutate(requestId) { a -> if (a.consumed) a else { won = true; a.copy(consumed = true) } }
        return won
    }

    /** Drop finished asks of a session from memory (their stored result is the record). */
    fun forget(requestId: String) {
        synchronized(lock) {
            _asks.value = _asks.value - requestId
            inProcess -= requestId
            deferredUnstored -= requestId
            deferredHeld -= requestId
            waiters.remove(requestId)
        }
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private fun mutate(requestId: String, body: (PendingAsk) -> PendingAsk) {
        val changed: PendingAsk = synchronized(lock) {
            val cur = _asks.value[requestId] ?: return
            val next = body(cur)
            if (next == cur) return
            _asks.value = _asks.value + (requestId to next)
            next
        }
        onChanged?.invoke(changed)
    }

    /** Apply a terminal transition; first one wins. Resumes the waiting tool call. */
    private fun resolve(requestId: String, label: String, transition: (PendingAsk) -> PendingAsk?) {
        var resolved: PendingAsk? = null
        var waiter: CancellableContinuation<String>? = null
        synchronized(lock) {
            val cur = _asks.value[requestId] ?: return
            val next = transition(cur) ?: return
            _asks.value = _asks.value + (requestId to next)
            resolved = next
            waiter = waiters.remove(requestId)
        }
        val r = resolved ?: return
        // Statuses only: the result can carry the user's own words.
        AppLogger.info(
            TAG,
            "[UserAsk] $label req=${requestId.take(8)} source=${r.source?.wire ?: "-"} " +
                "statuses=${r.resultStatuses.map { it.wire }} resultLen=${r.resultJson?.length ?: 0}",
        )
        onChanged?.invoke(r)
        val w = waiter
        if (w != null) {
            if (w.isActive) w.resume(r.resultJson ?: UserAskResolver.encode(UserAskResolver.dismissed(r.questions)))
        } else if (r.deferred) {
            // [T-user-ask-deferred-live-batch] Its batch is not stored yet:
            // hand it on once it is.
            val held = synchronized(lock) {
                if (requestId in deferredUnstored) { deferredHeld += requestId; true } else false
            }
            if (held) {
                AppLogger.info(TAG, "[UserAsk] DEFERRED req=${requestId.take(8)} resolved before its batch was stored - held")
            } else {
                onDeferredResolved?.invoke(r)
            }
        } else if (!isInProcess(requestId)) {
            onResolvedWithoutWaiter?.invoke(r)
        }
    }

    /** Test hook: clear all state. */
    internal fun resetForTest() {
        synchronized(lock) {
            _asks.value = emptyMap()
            waiters.clear()
            inProcess.clear()
            deferredUnstored.clear()
            deferredHeld.clear()
            tickJob?.cancel()
            tickJob = null
        }
        onChanged = null
        onResolvedWithoutWaiter = null
        onDeferredResolved = null
        clock = { System.currentTimeMillis() }
    }
}
