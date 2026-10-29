package com.openminis.app.agent.userask

import com.openminis.app.ProductionSources
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-port-user-ask-fk-crash] Pixel 4a, HEAD 98ee344e5: the first user_ask in a
 * new chat crashed the app (3/3) with "FOREIGN KEY constraint failed" from an
 * insertMessage on an IO StandaloneCoroutine, 25 ms after BEGIN. The first ask
 * installs recovery, whose launch scan walked a record left by a chat the user
 * had since deleted and appended its assistant row into that missing session.
 *
 * This transcript enforces the foreign key: appending into a session that does
 * not exist throws, exactly as SQLite does.
 */
class UserAskFkCrashTest {

    private class FkTranscript(val sessions: MutableSet<String>) : UserAskTranscript {
        val rows = mutableListOf<Triple<String, String, String>>()   // sid, role, parts
        override suspend fun partsJsonRows(sessionId: String) = synchronized(rows) { rows.filter { it.first == sessionId }.map { it.third } }
        override suspend fun append(sessionId: String, role: String, partsJson: String) {
            if (sessionId !in sessions) throw IllegalStateException("FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)")
            synchronized(rows) { rows += Triple(sessionId, role, partsJson) }
        }
        override suspend fun sessionExists(sessionId: String) = sessionId in sessions
    }

    private class MemRecords : UserAskRecords {
        val map = LinkedHashMap<String, UserAskRecord>()
        override fun all(): Map<String, UserAskRecord> = synchronized(map) { map.toMap() }
        override fun save(r: UserAskRecord) { synchronized(map) { map[r.ask.requestId] = r } }
        override fun remove(requestId: String) { synchronized(map) { map.remove(requestId) } }
    }

    private val now = System.currentTimeMillis()
    private val live = "502835b1-8817-4cc6-8c29-a1abc7148ab2"
    private val deleted = "deleted-session"
    private lateinit var records: MemRecords
    private lateinit var transcript: FkTranscript

    private val confirm = UserAskQuestion(UserAskQuestionType.CONFIRM, "rm -rf buld/", impact = "gone for good", risk = UserAskRisk.HIGH)
    private val pick = UserAskQuestion(UserAskQuestionType.OPTIONS, "Which?", options = listOf(UserAskOption("A"), UserAskOption("B")))
    private val snapshot = JSONArray().put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("toolUseId", "old-tc").put("name", "user_ask").put("input", "{}"))).toString()

    @Before fun setUp() {
        UserAskCenter.resetForTest()
        UserAskRecoveryCoordinator.resetForTest()
        records = MemRecords()
        transcript = FkTranscript(mutableSetOf(live))
    }

    @After fun tearDown() {
        UserAskCenter.resetForTest()
        UserAskRecoveryCoordinator.resetForTest()
    }

    private fun install(scanOthers: Boolean) {
        UserAskRecoveryCoordinator.install(records, transcript, { _, _ -> }, scanOthers = scanOthers)
        UserAskCenter.onChanged = { records.onAskChanged(it) }
        UserAskCenter.onResolvedWithoutWaiter = null
    }

    /** Records a deleted chat can leave behind: stopped (never consumed), still waiting, answered. */
    private fun staleRecords() {
        val base = PendingAsk.create("stale-stop", deleted, "old-tc", listOf(confirm), now - 60_000, 5)
        records.save(UserAskRecord(base.resolveDismissed(UserAskDismissReason.STOPPED)!!, snapshot))
        records.save(UserAskRecord(base.copy(requestId = "stale-wait", toolCallId = "old-tc2"), snapshot))
        records.save(UserAskRecord(base.copy(requestId = "stale-done", toolCallId = "old-tc3", submitted = setOf(0))
            .setAnswer(UserAskAnswer(confirmChoice = UserAskConfirmChoice.DENY), 0).resolveAnswered(UserAskAnswerSource.APP)!!, snapshot))
    }

    private fun args(q: UserAskQuestion) = JSONObject().put("questions", JSONArray().put(
        JSONObject().put("type", q.type.wire).put("question", q.question).apply {
            q.options?.let { put("options", JSONArray(it.map { o -> JSONObject().put("label", o.label) })) }
            if (q.type == UserAskQuestionType.CONFIRM) put("risk", q.risk.wire)
        },
    )).toString()

    /** The view model's BeforeShow, as wired in ChatViewModel. */
    private val beforeShow = UserAskToolRunner.BeforeShow { ask ->
        UserAskRecoveryCoordinator.saveNew(UserAskRecord(ask, assistantPartsJson = "[]"))
    }

    // ── The device repro ───────────────────────────────────────────────────

    @Test fun `first ask in a new chat with stale records of a deleted chat - no crash, stale records dropped`() = runBlocking {
        staleRecords()
        install(scanOthers = true)   // what the first ask does: the launch scan runs on IO
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            UserAskToolRunner.execute(args(confirm), "call_9984", live, null, 5, requestId = "8afda200", beforeShow = beforeShow)
        }
        withTimeout(3_000) { while (records.all().keys.any { it.startsWith("stale") }) delay(10) }
        assertTrue("nothing was written into the deleted chat", transcript.rows.none { it.first == deleted })
        val ask = UserAskCenter.ask("call_9984")!!
        assertEquals(UserAskState.PENDING, ask.state)
        assertTrue("still owned by its tool call", UserAskCenter.isInProcess(ask.requestId))
        assertTrue("the scan did not restore the live ask as a killed one", transcript.rows.none { it.first == live })
        UserAskCenter.confirm("8afda200", 0, UserAskConfirmChoice.DENY)
        assertEquals(listOf(UserAskResultStatus.DENIED), UserAskResolver.statuses(withTimeout(2_000) { call.await() }.output))
    }

    @Test fun `restoring a deleted chat never throws and drops its records`() = runBlocking {
        staleRecords()
        install(scanOthers = false)
        assertFalse(UserAskRecoveryCoordinator.restoreForSession(deleted, nowMs = now))
        assertTrue(records.all().isEmpty())
        assertTrue(transcript.rows.isEmpty())
    }

    @Test fun `a write that hits a constraint anyway is contained`() = runBlocking {
        // The session row is there when checked, gone when written (deleted mid-recovery).
        val racy = object : UserAskTranscript by transcript {
            override suspend fun sessionExists(sessionId: String) = true
        }
        UserAskRecoveryCoordinator.install(records, racy, { _, _ -> }, scanOthers = false)
        staleRecords()
        assertFalse(UserAskRecoveryCoordinator.restoreForSession(deleted, nowMs = now))
        assertTrue("dropped, not retried forever", records.all().isEmpty())
    }

    @Test fun `delivering or superseding into a deleted chat is a logged no-op`() = runBlocking {
        install(scanOthers = false)
        val a = PendingAsk.create("r9", deleted, "tc9", listOf(pick), now, 5)
        records.save(UserAskRecord(a, snapshot))
        UserAskCenter.begin(a, ownedByToolCall = false)
        UserAskCenter.submitQuestion("r9", 0)
        UserAskRecoveryCoordinator.deliverRecovered("r9")
        assertTrue(transcript.rows.isEmpty())
        assertTrue(records.all().isEmpty())

        val b = PendingAsk.create("r10", deleted, "tc10", listOf(pick), now, 5)
        records.save(UserAskRecord(b, snapshot))
        UserAskCenter.begin(b, ownedByToolCall = false)
        assertNull("nothing written, nothing mirrored", UserAskRecoveryCoordinator.supersedeRecovered(deleted))
    }

    // ── The race and drafts ────────────────────────────────────────────────

    @Test fun `a scan between saving and showing never takes over the live ask`() = runBlocking {
        install(scanOthers = false)
        val a = PendingAsk.create("rLive", live, "tcLive", listOf(confirm), now, 5)
        UserAskCenter.claim(a.requestId)          // what the runner does first
        UserAskRecoveryCoordinator.saveNew(UserAskRecord(a, snapshot))
        UserAskRecoveryCoordinator.restoreForSession(live, nowMs = now)   // the scan lands here
        assertTrue("no assistant row appended twice", transcript.rows.isEmpty())
        assertNull("not registered as a restored card", UserAskCenter.ask("tcLive"))
        assertTrue(records.all().containsKey("rLive"))
    }

    @Test fun `a draft chat is never persisted`() {
        install(scanOthers = false)
        val draft = PendingAsk.create("rd", "__new__30a8f54f", "tcd", listOf(pick), now, 5)
        UserAskRecoveryCoordinator.saveNew(UserAskRecord(draft, snapshot))
        assertTrue(records.all().isEmpty())
    }

    // ── New chat, first question: confirm and options ──────────────────────

    @Test fun `new chat - first confirm and first options question show and resolve`() = runBlocking {
        install(scanOthers = true)
        for ((q, tc) in listOf(confirm to "tcC", pick to "tcP")) {
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                UserAskToolRunner.execute(args(q), tc, live, null, 5, requestId = "req-$tc", beforeShow = beforeShow)
            }
            assertTrue("record saved under the real session id", records.all()["req-$tc"]?.ask?.sessionId == live)
            if (q.type == UserAskQuestionType.CONFIRM) UserAskCenter.confirm("req-$tc", 0, UserAskConfirmChoice.APPROVE)
            else UserAskCenter.submitQuestion("req-$tc", 0)
            val out = withTimeout(2_000) { call.await() }.output
            assertEquals(if (q.type == UserAskQuestionType.CONFIRM) UserAskResultStatus.APPROVED else UserAskResultStatus.ANSWERED,
                UserAskResolver.statuses(out).single())
        }
        assertTrue("nothing was appended by recovery for the live chat", transcript.rows.isEmpty())
    }

    // ── Source guards ──────────────────────────────────────────────────────

    @Test fun `ownership comes first, recovery is contained, existence is checked against the DB`() {
        // [T-user-ask-deferred] The waiting call; a deferred one is never in-process.
        val runner = ProductionSources.read("agent/userask/UserAskToolRunner.kt").substringAfter("suspend fun execute(")
        assertTrue(runner.indexOf("UserAskCenter.claim(ask.requestId)") in 0 until runner.indexOf("beforeShow?.onAsk(ask)"))
        assertTrue(runner.contains("runCatching { beforeShow?.onAsk(ask) }"))
        val co = ProductionSources.read("agent/userask/UserAskRecoveryCoordinator.kt")
        assertTrue(co.contains("kotlinx.coroutines.CoroutineExceptionHandler"))
        assertTrue(co.contains("override suspend fun sessionExists(sessionId: String) = r.getSession(sessionId) != null"))
        val deliver = co.substringAfter("private suspend fun deliverLocked(").substringBefore("\n    }\n")
        assertTrue("the FK precondition guards every write", deliver.indexOf("r.sessionExists(sid)") in 0 until deliver.indexOf("r.append("))
    }
}
