package com.openminis.app.agent.userask

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** [T-port-user-ask-recovery] Where records live (SharedPreferences in the app, memory in tests). */
interface UserAskRecords {
    fun all(): Map<String, UserAskRecord>
    fun record(requestId: String): UserAskRecord? = all()[requestId]
    fun save(r: UserAskRecord)
    fun remove(requestId: String)

    /** Keep the record's snapshot and siblings; take the ask's latest state. Consumed = done. */
    fun onAskChanged(ask: PendingAsk) {
        if (ask.consumed) { remove(ask.requestId); return }
        save((record(ask.requestId) ?: UserAskRecord(ask)).copy(ask = ask))
    }

    fun purge(nowMs: Long) {
        val all = all()
        val kept = UserAskRecovery.purge(all, nowMs)
        for (k in all.keys - kept.keys) remove(k)
    }
}

/** [T-port-user-ask-recovery] The transcript rows recovery reads and appends (the chat DB in the app). */
interface UserAskTranscript {
    suspend fun partsJsonRows(sessionId: String): List<String>
    suspend fun append(sessionId: String, role: String, partsJson: String)

    /**
     * [T-port-user-ask-fk-crash] Whether the session row exists. A message
     * may only be written into an existing session: the messages table has a
     * foreign key on it, and a record can outlive its session (deleted).
     */
    suspend fun sessionExists(sessionId: String): Boolean

    /**
     * [T-user-ask-deferred] Rewrite the stored result of [toolUseId] with
     * [output]. False when no stored result carries that id.
     */
    suspend fun replaceToolResult(sessionId: String, toolUseId: String, output: String): Boolean = false
}

/**
 * [T-user-ask-deferred] A deferred ask's result is now in the store; [answerMessage]
 * is the user's next message for a real answer, null when the card just closed.
 */
data class UserAskDeferredDelivery(
    val sessionId: String,
    val toolUseId: String,
    val resultJson: String,
    val answerMessage: String?,
)

/**
 * [T-port-user-ask-recovery] SharedPreferences JSON map requestId -> record.
 * A handful of live asks at most; survives process death; device-local (not
 * in backups, like iOS's UserDefaults). Writes are synchronous (commit) so a
 * record is on disk before the card that refers to it is shown.
 */
class UserAskRecordStore(context: Context) : UserAskRecords {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun all(): Map<String, UserAskRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return obj.keys().asSequence().mapNotNull { k -> obj.optJSONObject(k)?.let(UserAskRecord::fromJson)?.let { k to it } }.toMap()
    }

    @Synchronized
    override fun save(r: UserAskRecord) {
        pendingChanges.remove(r.ask.requestId)   // this record is newer than any queued draft
        write(all() + (r.ask.requestId to r))
    }

    @Synchronized
    override fun remove(requestId: String) {
        pendingChanges.remove(requestId)
        val map = all()
        if (requestId in map) write(map - requestId)
    }

    /**
     * [T-user-ask-async-draft-save] The card calls this on every keystroke,
     * tap and Next, on the main thread. Rewriting the whole record (with the
     * turn's assistant snapshot) synchronously each time lagged typing. Draft
     * changes are now coalesced per question and written on a background
     * thread; a terminal or consumed change is still written synchronously,
     * so an answer is on disk before the run moves on.
     */
    override fun onAskChanged(ask: PendingAsk) {
        val sync = ask.consumed || ask.state.isTerminal
        synchronized(this) { pendingChanges[ask.requestId] = ask }
        if (sync) flushPending() else writer.execute { runCatching { flushPending() } }
    }

    /** Apply the queued changes: update records that still exist, drop consumed ones. */
    @Synchronized
    internal fun flushPending() {
        if (pendingChanges.isEmpty()) return
        val batch = pendingChanges.values.toList()
        pendingChanges.clear()
        val map = all().toMutableMap()
        var changed = false
        for (ask in batch) {
            val cur = map[ask.requestId] ?: continue   // removed meanwhile: never resurrect it
            if (ask.consumed) map.remove(ask.requestId) else map[ask.requestId] = cur.copy(ask = ask)
            changed = true
        }
        if (changed) write(map)
    }

    private val pendingChanges = LinkedHashMap<String, PendingAsk>()

    @Synchronized
    override fun purge(nowMs: Long) = write(UserAskRecovery.purge(all(), nowMs))

    private fun write(map: Map<String, UserAskRecord>) {
        if (map.isEmpty()) { prefs.edit().remove(KEY).commit(); return }
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v.toJson())
        prefs.edit().putString(KEY, obj.toString()).commit()
    }

    companion object {
        /** One background writer for every store instance: draft saves stay ordered. */
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "UserAskRecordWriter").apply { isDaemon = true }
        }
        const val PREFS = "user_ask_records"
        /** Same key name as iOS UserAskRecordStore. */
        const val KEY = "userAsk.records.v1"
    }
}

/**
 * [T-port-user-ask-recovery] Brings a question back after the app was killed
 * while it waited (iOS UserAskCenter.recoverAll / deliverRecovered).
 *
 * - On a session load ([restoreForSession], before the transcript is read):
 *   already delivered -> drop; still in time -> the card comes back
 *   answerable (and the saved assistant row is written so it shows);
 *   answered / expired -> its result is written now and the run resumes once
 *   the load finishes; stopped / replaced -> result written, no resume.
 * - A restored card answered later ([deliverRecovered], via
 *   UserAskCenter.onResolvedWithoutWaiter) writes the result rows and asks the
 *   session's view model to reload and continue.
 *
 * Delivery is exactly once per toolCallId: a tool_result already in the
 * transcript always wins.
 */
object UserAskRecoveryCoordinator {
    private const val TAG = "UserAsk"
    private val installed = AtomicBoolean(false)
    private val mutex = Mutex()
    /**
     * [T-port-user-ask-fk-crash] Recovery never takes the app down: anything
     * that escapes a step (a DB constraint, a corrupt record) is logged.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            AppLogger.error(TAG, "[UserAsk] RECOVER step failed (contained): ${e.javaClass.simpleName}: ${e.message}")
        },
    )

    @Volatile private var store: UserAskRecords? = null
    @Volatile private var repo: UserAskTranscript? = null

    /** Reload the session's view model and, if [resume], continue its run. */
    @Volatile private var onDelivered: ((sessionId: String, resume: Boolean) -> Unit)? = null

    /** [T-user-ask-deferred] Patch the session's view model and send the answer, if any. */
    @Volatile private var onDeferredDelivered: ((UserAskDeferredDelivery) -> Unit)? = null

    /**
     * Idempotent. Called by every chat view model (the only place asks begin or
     * are restored), so no app-level hook is needed. The first call also scans
     * every stored record once, like iOS's recovery at launch.
     */
    fun ensureInstalled(
        context: Context,
        repository: ChatRepository,
        delivered: (sessionId: String, resume: Boolean) -> Unit,
        deferredDelivered: (UserAskDeferredDelivery) -> Unit = {},
    ) {
        if (!installed.compareAndSet(false, true)) return
        install(UserAskRecordStore(context), ChatRepositoryTranscript(repository), delivered, scanOthers = true, deferredDelivered)
    }

    internal fun install(
        s: UserAskRecords,
        transcript: UserAskTranscript,
        delivered: (String, Boolean) -> Unit,
        scanOthers: Boolean,
        deferredDelivered: (UserAskDeferredDelivery) -> Unit = {},
    ) {
        installed.set(true)
        store = s
        repo = transcript
        onDelivered = delivered
        onDeferredDelivered = deferredDelivered
        UserAskCenter.onChanged = { ask -> runCatching { s.onAskChanged(ask) } }
        UserAskCenter.onResolvedWithoutWaiter = { ask -> scope.launch { deliverRecovered(ask.requestId) } }
        UserAskCenter.onDeferredResolved = { ask -> scope.launch { deliverDeferred(ask.requestId) } }
        if (scanOthers) scope.launch { recoverOthers() }
    }

    private class ChatRepositoryTranscript(private val r: ChatRepository) : UserAskTranscript {
        override suspend fun partsJsonRows(sessionId: String) = r.loadMessages(sessionId).map { it.partsJson }
        override suspend fun append(sessionId: String, role: String, partsJson: String) {
            r.appendMessage(sessionId, role, partsJson)
        }
        override suspend fun sessionExists(sessionId: String) = r.getSession(sessionId) != null
        override suspend fun replaceToolResult(sessionId: String, toolUseId: String, output: String): Boolean {
            for (m in r.loadMessages(sessionId)) {
                val patched = UserAskRecovery.replaceToolResultOutput(m.partsJson, toolUseId, output) ?: continue
                r.updateMessageParts(m.id, patched)
                return true
            }
            return false
        }
    }

    /** BeforeShow hook: the record is on disk before the card can show. */
    fun saveNew(record: UserAskRecord) {
        // [T-port-user-ask-fk-crash] A draft has no session row yet, so nothing
        // written for it could be restored; never persist one.
        if (isDraftId(record.ask.sessionId)) {
            AppLogger.info(TAG, "[UserAsk] not persisting req=${record.ask.requestId.take(8)}: draft session")
            return
        }
        runCatching { store?.save(record) }
    }

    /** Draft chats carry a `__new__` id until their first message creates the row. */
    internal fun isDraftId(sessionId: String): Boolean = sessionId.startsWith("__new__")

    /** tool_use ids of questions still waiting in [sessionId] (live or stored): no Resume banner for them. */
    fun pendingToolCallIds(sessionId: String): Set<String> {
        val live = UserAskCenter.pendingAsks(sessionId).map { it.toolCallId }
        val stored = store?.all()?.values
            ?.filter { it.ask.sessionId == sessionId && it.ask.state == UserAskState.PENDING && !it.ask.consumed }
            ?.map { it.ask.toolCallId } ?: emptyList()
        return (live + stored).toSet()
    }

    /**
     * Before [sessionId]'s transcript is read. Returns true when a recovered
     * answer (or timeout) was written and the run should resume after the load.
     */
    suspend fun restoreForSession(sessionId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = store ?: return false
        val r = repo ?: return false
        var resume = false
        val deferredDone = mutableListOf<UserAskDeferredDelivery>()
        mutex.withLock {
            runCatching { s.purge(nowMs) }
            for ((rid, rec) in s.all()) {
                if (rec.ask.sessionId != sessionId || UserAskCenter.isInProcess(rid)) continue
                // [T-port-user-ask-fk-crash] One bad record (its session was
                // deleted, a write hit a constraint) is logged and dropped; it
                // never fails the load or the process.
                try {
                    if (isDraftId(sessionId) || !r.sessionExists(sessionId)) {
                        AppLogger.info(TAG, "[UserAsk] RECOVER req=${rid.take(8)} dropped: session ${sessionId.take(8)} does not exist")
                        s.remove(rid)
                        continue
                    }
                    resume = restoreOne(s, r, rid, rec, sessionId, nowMs, deferredDone) || resume
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.error(TAG, "[UserAsk] RECOVER req=${rid.take(8)} failed, record dropped: ${e.javaClass.simpleName}: ${e.message}")
                    runCatching { s.remove(rid) }
                }
            }
        }
        deferredDone.forEach { d -> onDeferredDelivered?.invoke(d) }
        return resume
    }

    private suspend fun restoreOne(
        s: UserAskRecords,
        r: UserAskTranscript,
        rid: String,
        rec: UserAskRecord,
        sessionId: String,
        nowMs: Long,
        deferredDone: MutableList<UserAskDeferredDelivery>,
    ): Boolean {
        val inStore = toolResultExists(r, sessionId, rec.ask.toolCallId)
        val deferredInStore = rec.ask.deferred && inStore
        return when (UserAskRecovery.action(rec, nowMs, inStore)) {
            UserAskRecoveryAction.ALREADY_DELIVERED -> { s.remove(rid); false }
            UserAskRecoveryAction.AWAIT_USER -> {
                if (UserAskCenter.asks.value[rid]?.state == UserAskState.PENDING) return false
                if (deferredInStore) {
                    // [T-user-ask-deferred] Its tool call and `deferred` result are
                    // in the transcript; the card comes back and its answer is
                    // handed on as a message (UserAskCenter.onDeferredResolved).
                    UserAskCenter.begin(rec.ask, ownedByToolCall = false)
                    AppLogger.info(TAG, "[UserAsk] RECOVER awaiting deferred req=${rid.take(8)} - card restored")
                    return false
                }
                val persisted = ensureAssistantRow(r, rec)
                s.save(persisted)
                UserAskCenter.begin(persisted.ask, ownedByToolCall = false)
                AppLogger.info(TAG, "[UserAsk] RECOVER awaiting req=${rid.take(8)} - card restored, run continues on answer")
                false
            }
            UserAskRecoveryAction.TIME_OUT_THEN_DELIVER -> {
                val timed = rec.ask.resolveTimedOut() ?: rec.ask
                if (deferredInStore) {
                    deliverDeferredLocked(s, r, timed)?.let { deferredDone += it }
                    false
                } else deliverLocked(s, r, rec.copy(ask = timed))
            }
            UserAskRecoveryAction.DELIVER -> deliverLocked(s, r, rec)
            UserAskRecoveryAction.DELIVER_DEFERRED -> {
                deliverDeferredLocked(s, r, UserAskCenter.asks.value[rid] ?: rec.ask)?.let { deferredDone += it }
                false
            }
        }
    }

    /** Asks being superseded inline by a send; the async delivery leaves them alone. */
    private val supersedingInline = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * The user sent a message while a RESTORED question waited (no run behind
     * it): the message answers it in their own words. Its result row must land
     * before the new user row, so it is written here, inline, and the tool
     * results are returned for the caller's in-memory history. Null when there
     * was nothing to supersede.
     */
    suspend fun supersedeRecovered(sessionId: String): List<com.openminis.app.data.model.AgentContentPart.ToolResult>? {
        val s = store ?: return null
        val r = repo ?: return null
        // [T-user-ask-deferred] Not a deferred card: its call already has a
        // result; the send dismisses it and the delivery rewrites that result.
        val restored = UserAskCenter.pendingAsks(sessionId).filter { !UserAskCenter.isInProcess(it.requestId) && !it.deferred }
        if (restored.isEmpty()) return null
        val parts = mutableListOf<com.openminis.app.data.model.AgentContentPart.ToolResult>()
        for (a in restored) {
            supersedingInline += a.requestId
            try {
                UserAskCenter.dismiss(a.requestId, UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE)
                // [T-port-user-ask-fk-crash] Runs inside the send: it must never throw.
                try { mutex.withLock {
                    val rec = s.record(a.requestId) ?: return@withLock
                    val live = UserAskCenter.asks.value[a.requestId] ?: return@withLock
                    val json = live.resultJson ?: return@withLock
                    // Mirror into memory only what is really written now.
                    if (!r.sessionExists(live.sessionId) || toolResultExists(r, live.sessionId, live.toolCallId)) {
                        s.remove(a.requestId)
                        return@withLock
                    }
                    deliverLocked(s, r, rec.copy(ask = live))
                    val order = UserAskRecovery.toolUseOrder(rec.assistantPartsJson).ifEmpty { listOf(live.toolCallId to UserAskPromptText.TOOL_NAME) }
                    for ((id, name) in order) {
                        val mine = id == live.toolCallId
                        parts += com.openminis.app.data.model.AgentContentPart.ToolResult(
                            id = id, name = name, content = if (mine) json else UserAskRecovery.INTERRUPTED_RESULT, isError = !mine,
                        )
                    }
                } } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.error(TAG, "[UserAsk] supersede req=${a.requestId.take(8)} failed, record dropped: ${e.javaClass.simpleName}: ${e.message}")
                    runCatching { s.remove(a.requestId) }
                }
            } finally {
                supersedingInline -= a.requestId
            }
        }
        return parts.ifEmpty { null }
    }

    /** A restored card was answered (or timed out) in this process: write it, then reload + resume. */
    suspend fun deliverRecovered(requestId: String) {
        val s = store ?: return
        val r = repo ?: return
        if (requestId in supersedingInline) return
        var sid: String? = null
        var resume = false
        mutex.withLock {
            val rec = s.record(requestId) ?: return
            val live = UserAskCenter.asks.value[requestId] ?: rec.ask
            if (!live.state.isTerminal || live.resultJson == null) return
            sid = live.sessionId
            resume = try {
                deliverLocked(s, r, rec.copy(ask = live))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // [T-port-user-ask-fk-crash] Logged and dropped, never a crash.
                AppLogger.error(TAG, "[UserAsk] RECOVER deliver req=${requestId.take(8)} failed, record dropped: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { s.remove(requestId) }
                return
            }
        }
        sid?.let { onDelivered?.invoke(it, resume) }
    }

    /**
     * [T-user-ask-deferred] A deferred ask resolved (answered, dismissed,
     * replaced, timed out): write its real result over the stored `deferred`
     * one, then let the session's view model patch its memory and, for a real
     * answer, send it as the user's next message (iOS deliverDeferred).
     * Exactly once per ask.
     */
    suspend fun deliverDeferred(requestId: String) {
        val s = store ?: return
        val r = repo ?: return
        var done: UserAskDeferredDelivery? = null
        mutex.withLock {
            val ask = UserAskCenter.asks.value[requestId] ?: s.record(requestId)?.ask ?: return
            done = try {
                deliverDeferredLocked(s, r, ask)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "[UserAsk] DEFERRED deliver req=${requestId.take(8)} failed, record dropped: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { s.remove(requestId) }
                null
            }
        }
        done?.let { onDeferredDelivered?.invoke(it) }
    }

    private suspend fun deliverDeferredLocked(s: UserAskRecords, r: UserAskTranscript, ask: PendingAsk): UserAskDeferredDelivery? {
        if (!ask.deferred || !ask.state.isTerminal || ask.consumed) return null
        val json = ask.resultJson ?: return null
        // Read before consuming: a consumed ask's record is removed at once.
        val rec = s.record(ask.requestId)
        // First caller wins: the live ask is marked consumed, a recovered one
        // loses its record (both under the mutex).
        if (UserAskCenter.asks.value[ask.requestId] != null && !UserAskCenter.markDeferredConsumed(ask.requestId)) return null
        s.remove(ask.requestId)
        val sid = ask.sessionId
        if (isDraftId(sid) || !r.sessionExists(sid)) {
            AppLogger.info(TAG, "[UserAsk] DEFERRED req=${ask.requestId.take(8)} dropped: session ${sid.take(8)} does not exist")
            return null
        }
        if (!r.replaceToolResult(sid, ask.toolCallId, json)) {
            // Its `deferred` result never reached the store (killed before the
            // batch was written): write the answer as the call's result instead.
            if (rec != null) deliverLocked(s, r, rec.copy(ask = ask.copy(consumed = false)))
        }
        val message = if (UserAskDeferred.sendsAnswer(ask)) UserAskDeferred.answerMessage(ask) else null
        AppLogger.info(
            TAG,
            "[UserAsk] DEFERRED req=${ask.requestId.take(8)} state=${ask.state.wire} statuses=${ask.resultStatuses.map { it.wire }} " +
                "- result filled in, message=${message != null}",
        )
        return UserAskDeferredDelivery(sid, ask.toolCallId, json, message)
    }

    /** Sessions that are not open: restore their waiting asks so the ticker can time them out. */
    private suspend fun recoverOthers() {
        val s = store ?: return
        val sessions = s.all().values.map { it.ask.sessionId }.toSet()
        for (sid in sessions) {
            val resume = restoreForSession(sid)
            if (resume) onDelivered?.invoke(sid, true)
        }
    }

    /** Append the saved assistant row (if missing) and the result row; drop the record. Returns whether to resume. */
    private suspend fun deliverLocked(s: UserAskRecords, r: UserAskTranscript, rec: UserAskRecord): Boolean {
        val ask = rec.ask
        val json = ask.resultJson ?: return false
        val sid = ask.sessionId
        // [T-port-user-ask-fk-crash] Messages may only go into an existing
        // session (foreign key). A record whose session was deleted is dropped.
        if (isDraftId(sid) || !r.sessionExists(sid)) {
            AppLogger.info(TAG, "[UserAsk] RECOVER req=${ask.requestId.take(8)} dropped: session ${sid.take(8)} does not exist")
            s.remove(ask.requestId)
            return false
        }
        if (toolResultExists(r, sid, ask.toolCallId)) {
            s.remove(ask.requestId)
            UserAskCenter.markDelivered(listOf(ask.toolCallId))
            return false
        }
        val snapshot = rec.assistantPartsJson
        if (snapshot.isNullOrEmpty() && !rec.assistantPersisted && !toolUseExists(r, sid, ask.toolCallId)) {
            // The request cannot be restored: keep the answers as a plain message.
            AppLogger.warning(TAG, "[UserAsk] RECOVER req=${ask.requestId.take(8)} - assistant snapshot missing, falling back to a plain message")
            r.append(sid, "user", JSONArray().put(JSONObject().put("type", "text").put("value", UserAskRecovery.plainTextFallback(ask))).toString())
        } else {
            if (!rec.assistantPersisted && !toolUseExists(r, sid, ask.toolCallId) && snapshot != null) {
                r.append(sid, "assistant", snapshot)
            }
            val order = UserAskRecovery.toolUseOrder(snapshot).ifEmpty { listOf(ask.toolCallId to UserAskPromptText.TOOL_NAME) }
            r.append(sid, "user", UserAskRecovery.resultRowPartsJson(ask, json, order))
        }
        s.remove(ask.requestId)
        UserAskCenter.markDelivered(listOf(ask.toolCallId))
        val resume = UserAskRecovery.resumesRun(ask)
        AppLogger.info(
            TAG,
            "[UserAsk] RECOVER delivered req=${ask.requestId.take(8)} state=${ask.state.wire} statuses=${ask.resultStatuses.map { it.wire }} resume=$resume",
        )
        return resume
    }

    /** Write the saved assistant row so a reopened session shows the card while it still waits. */
    private suspend fun ensureAssistantRow(r: UserAskTranscript, rec: UserAskRecord): UserAskRecord {
        if (rec.assistantPersisted) return rec
        val snapshot = rec.assistantPartsJson ?: return rec
        if (!toolUseExists(r, rec.ask.sessionId, rec.ask.toolCallId)) r.append(rec.ask.sessionId, "assistant", snapshot)
        return rec.copy(assistantPersisted = true)
    }

    private suspend fun toolResultExists(r: UserAskTranscript, sid: String, toolUseId: String): Boolean =
        r.partsJsonRows(sid).any { partHasId(it, "toolResult", toolUseId) }

    private suspend fun toolUseExists(r: UserAskTranscript, sid: String, toolUseId: String): Boolean =
        r.partsJsonRows(sid).any { partHasId(it, "toolUse", toolUseId) }

    internal fun partHasId(partsJson: String, type: String, toolUseId: String): Boolean {
        if (!partsJson.contains(toolUseId)) return false
        val arr = runCatching { JSONArray(partsJson) }.getOrNull() ?: return false
        return (0 until arr.length()).any { i ->
            val o = arr.optJSONObject(i)
            o?.optString("type") == type && o.optJSONObject("value")?.optString("toolUseId") == toolUseId
        }
    }

    /** Test hook. */
    internal fun resetForTest() {
        installed.set(false)
        store = null
        repo = null
        onDelivered = null
        onDeferredDelivered = null
    }
}
