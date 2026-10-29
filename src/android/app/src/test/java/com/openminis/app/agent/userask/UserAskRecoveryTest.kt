package com.openminis.app.agent.userask

import com.openminis.app.ProductionSources
import com.openminis.app.agent.InterruptedTailDetector
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-port-user-ask-recovery] A question survives a process kill: the record is
 * written before the card, comes back answerable on the next load, and its
 * answer resumes the run - delivered exactly once, with no Resume banner while
 * it waits.
 */
class UserAskRecoveryTest {

    private val t0 = 1_000_000_000L
    private val opt = UserAskQuestion(UserAskQuestionType.OPTIONS, "Which DB?", header = "DB",
        options = listOf(UserAskOption("SQLite"), UserAskOption("Postgres")), defaultIndex = 1)
    private val conf = UserAskQuestion(UserAskQuestionType.CONFIRM, "rm -rf build/", impact = "deletes output", risk = UserAskRisk.HIGH)

    /** The assistant row of a batch with user_ask + a shell call, as persistAssistantTurn writes it. */
    private val snapshot = JSONArray()
        .put(JSONObject().put("type", "text").put("value", "Let me ask."))
        .put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("toolUseId", "tc1").put("name", "user_ask").put("input", "{}")))
        .put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("toolUseId", "tc2").put("name", "shell_execute").put("input", "{}")))
        .toString()

    private fun ask(minutes: Int = 5) = PendingAsk.create("req1", "s1", "tc1", listOf(opt, conf), t0, minutes)

    // ── Fakes ──────────────────────────────────────────────────────────────

    private class MemRecords : UserAskRecords {
        val map = LinkedHashMap<String, UserAskRecord>()
        override fun all(): Map<String, UserAskRecord> = synchronized(map) { map.toMap() }
        override fun save(r: UserAskRecord) { synchronized(map) { map[r.ask.requestId] = r } }
        override fun remove(requestId: String) { synchronized(map) { map.remove(requestId) } }
    }

    private class MemTranscript : UserAskTranscript {
        val rows = mutableListOf<Pair<String, String>>()   // role, partsJson
        override suspend fun sessionExists(sessionId: String) = true
        override suspend fun partsJsonRows(sessionId: String) = synchronized(rows) { rows.map { it.second } }
        override suspend fun append(sessionId: String, role: String, partsJson: String) { synchronized(rows) { rows += role to partsJson } }
    }

    private lateinit var records: MemRecords
    private lateinit var transcript: MemTranscript
    private val delivered = mutableListOf<Pair<String, Boolean>>()

    @Before fun setUp() {
        UserAskCenter.resetForTest()
        UserAskRecoveryCoordinator.resetForTest()
        records = MemRecords()
        transcript = MemTranscript()
        delivered.clear()
        UserAskRecoveryCoordinator.install(records, transcript, { sid, resume -> synchronized(delivered) { delivered += sid to resume } }, scanOthers = false)
        UserAskCenter.onChanged = { records.onAskChanged(it) }
        // Deliveries are driven explicitly below (in the app this hook launches one).
        UserAskCenter.onResolvedWithoutWaiter = null
    }

    @After fun tearDown() {
        UserAskCenter.resetForTest()
        UserAskRecoveryCoordinator.resetForTest()
    }

    // ── Pure rules (iOS vectors) ───────────────────────────────────────────

    @Test fun `the recovery decision`() {
        val rec = UserAskRecord(ask(), snapshot, listOf("tc2"))
        assertEquals(UserAskRecoveryAction.AWAIT_USER, UserAskRecovery.action(rec, t0 + 60_000, false))
        assertEquals(UserAskRecoveryAction.TIME_OUT_THEN_DELIVER, UserAskRecovery.action(rec, t0 + 301_000, false))
        val answered = rec.copy(ask = rec.ask.copy(submitted = setOf(0, 1)).resolveAnswered(UserAskAnswerSource.APP)!!)
        assertEquals(UserAskRecoveryAction.DELIVER, UserAskRecovery.action(answered, t0 + 9_999_000, false))
        assertEquals(UserAskRecoveryAction.ALREADY_DELIVERED, UserAskRecovery.action(answered, t0, true))
        assertEquals(UserAskRecoveryAction.ALREADY_DELIVERED, UserAskRecovery.action(answered.copy(ask = answered.ask.copy(consumed = true)), t0, false))
        assertTrue(UserAskRecovery.resumesRun(answered.ask))
        assertTrue(UserAskRecovery.resumesRun(ask().resolveTimedOut()!!))
        assertFalse("a stopped run stays stopped", UserAskRecovery.resumesRun(ask().resolveDismissed(UserAskDismissReason.STOPPED)!!))
        assertFalse(UserAskRecovery.resumesRun(ask().resolveDismissed(UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE)!!))
    }

    @Test fun `a record survives the round trip, drafts included`() {
        var a = ask().setAnswer(UserAskAnswer(selected = listOf(1), note = "draft note"), 0).copy(submitted = setOf(0), currentIndex = 1)
        a = a.setAnswer(UserAskAnswer(confirmChoice = UserAskConfirmChoice.DENY, note = "why"), 1).copy(sessionTitle = "T")
        val rec = UserAskRecord(a, snapshot, listOf("tc2"), assistantPersisted = true)
        val back = UserAskRecord.fromJson(JSONObject(rec.toJson().toString()))
        assertEquals(rec, back)
        val resolved = rec.copy(ask = a.resolveAnswered(UserAskAnswerSource.APP)!!)
        assertEquals(resolved, UserAskRecord.fromJson(JSONObject(resolved.toJson().toString())))
        val forever = UserAskRecord(ask(0))
        assertNull(UserAskRecord.fromJson(JSONObject(forever.toJson().toString()))!!.ask.expiresAtMs)
        assertNull("garbage is dropped, not a crash", UserAskRecord.fromJson(JSONObject("{\"ask\":{\"questions\":[]}}")))
    }

    @Test fun `purge drops consumed and week-old records`() {
        val fresh = UserAskRecord(ask())
        val consumed = UserAskRecord(ask().copy(requestId = "c", consumed = true))
        val old = UserAskRecord(ask().copy(requestId = "o", createdAtMs = t0 - 8L * 24 * 3600_000))
        val kept = UserAskRecovery.purge(mapOf("req1" to fresh, "c" to consumed, "o" to old), t0)
        assertEquals(setOf("req1"), kept.keys)
    }

    @Test fun `fallback text and the result row`() {
        val a = ask().setAnswer(UserAskAnswer(selected = listOf(1), note = "draft note"), 0).copy(submitted = setOf(0))
            .setAnswer(UserAskAnswer(confirmChoice = UserAskConfirmChoice.APPROVE), 1).resolveAnswered(UserAskAnswerSource.APP)!!
        val fb = UserAskRecovery.plainTextFallback(a)
        assertTrue(fb, fb.contains("1. Which DB? → answered: Postgres (note: draft note)"))
        assertTrue(fb, fb.contains("2. rm -rf build/ → approved: approve"))
        val row = JSONArray(UserAskRecovery.resultRowPartsJson(a, a.resultJson!!, UserAskRecovery.toolUseOrder(snapshot)))
        assertEquals("tool_use order, the sibling marked interrupted", 2, row.length())
        val mine = row.getJSONObject(0).getJSONObject("value")
        assertEquals("tc1", mine.getString("toolUseId"))
        assertTrue(mine.getBoolean("success"))
        assertEquals(a.resultJson, mine.getString("output"))
        val sib = row.getJSONObject(1).getJSONObject("value")
        assertEquals("tc2", sib.getString("toolUseId"))
        assertEquals("shell_execute", sib.getString("name"))
        assertFalse(sib.getBoolean("success"))
        assertEquals(UserAskRecovery.INTERRUPTED_RESULT, sib.getString("output"))
    }

    @Test fun `a pending question suppresses the Resume banner`() {
        val tail = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse("tc1", "user_ask", JSONObject())))
        assertTrue(InterruptedTailDetector.awaitsUserAnswer(tail, setOf("tc1")))
        assertFalse("another call is still interrupted", InterruptedTailDetector.awaitsUserAnswer(tail, setOf("tc9")))
        assertFalse(InterruptedTailDetector.awaitsUserAnswer(tail, emptySet()))
        assertFalse(InterruptedTailDetector.awaitsUserAnswer(LLMMessage(LLMMessage.Role.USER, "x"), setOf("tc1")))
    }

    // ── The kill / restore / answer cycle ──────────────────────────────────

    /** Simulate a kill mid-wait: the record is on disk, nothing in memory, the transcript has the user row only. */
    private fun killedWhileWaiting(minutes: Int = 5) {
        records.save(UserAskRecord(ask(minutes), snapshot, listOf("tc2")))
        transcript.rows += "user" to """[{"type":"text","value":"set up the db"}]"""
    }

    @Test fun `in time - the card comes back answerable, then the answer resumes the run`() = runBlocking {
        killedWhileWaiting()
        val resumeNow = UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 60_000)
        assertFalse(resumeNow)
        assertEquals("the assistant row is written so the card shows", "assistant", transcript.rows.last().first)
        val live = UserAskCenter.ask("tc1")!!
        assertEquals(UserAskState.PENDING, live.state)
        assertFalse("restored asks belong to no tool call", UserAskCenter.isInProcess(live.requestId))
        assertEquals(setOf("tc1"), UserAskRecoveryCoordinator.pendingToolCallIds("s1"))
        // Restoring twice adds nothing.
        UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 61_000)
        assertEquals(2, transcript.rows.size)

        // The user answers both questions on the restored card.
        UserAskCenter.submitQuestion(live.requestId, 0)
        UserAskCenter.confirm(live.requestId, 1, UserAskConfirmChoice.APPROVE)
        UserAskRecoveryCoordinator.deliverRecovered(live.requestId)   // what onResolvedWithoutWaiter launches
        val resultRow = transcript.rows.last()
        assertEquals("user", resultRow.first)
        assertTrue(resultRow.second.contains("\\\"status\\\":\\\"approved\\\"") || resultRow.second.contains("approved"))
        assertTrue(resultRow.second.contains("\"toolUseId\":\"tc2\""))
        assertEquals(listOf("s1" to true), delivered)
        assertTrue("record dropped once delivered", records.all().isEmpty())
        // Exactly once: a second delivery finds the result in the transcript.
        UserAskRecoveryCoordinator.deliverRecovered(live.requestId)
        assertEquals(3, transcript.rows.size)
    }

    @Test fun `expired while the app was dead - timed out and delivered at load, then the run resumes`() = runBlocking {
        killedWhileWaiting()
        val resume = UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 6 * 60_000)
        assertTrue(resume)
        assertEquals(listOf("user", "assistant", "user"), transcript.rows.map { it.first })
        assertTrue(transcript.rows.last().second.contains("timeout"))
        assertNull("nothing left waiting", UserAskCenter.ask("tc1"))
        assertTrue(records.all().isEmpty())
    }

    @Test fun `answered just before the kill - delivered at load`() = runBlocking {
        val answered = ask().copy(submitted = setOf(0, 1))
            .setAnswer(UserAskAnswer(confirmChoice = UserAskConfirmChoice.DENY), 1).resolveAnswered(UserAskAnswerSource.APP)!!
        records.save(UserAskRecord(answered, snapshot, listOf("tc2")))
        assertTrue(UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 10_000))
        assertTrue(transcript.rows.last().second.contains("denied"))
    }

    @Test fun `already in the transcript - dropped, never delivered twice`() = runBlocking {
        killedWhileWaiting()
        transcript.rows += "user" to """[{"type":"toolResult","value":{"toolUseId":"tc1","output":"x","success":true}}]"""
        assertFalse(UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 10_000))
        assertTrue(records.all().isEmpty())
        assertNull(UserAskCenter.ask("tc1"))
    }

    @Test fun `stopped while restored - result written, the run stays stopped`() = runBlocking {
        killedWhileWaiting()
        UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 1_000)
        val rid = UserAskCenter.ask("tc1")!!.requestId
        UserAskCenter.dismissPending("s1", UserAskDismissReason.STOPPED)
        UserAskRecoveryCoordinator.deliverRecovered(rid)
        assertEquals(listOf("s1" to false), delivered)
        assertTrue(transcript.rows.last().second.contains("dismissed"))
    }

    @Test fun `a new message supersedes a restored question, its result lands first`() = runBlocking {
        killedWhileWaiting()
        UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 1_000)
        val parts = UserAskRecoveryCoordinator.supersedeRecovered("s1")
        assertNotNull(parts)
        assertEquals(listOf("tc1", "tc2"), parts!!.map { it.id })
        assertTrue(parts[0].content.contains("dismissed"))
        assertEquals("user", transcript.rows.last().first)
        assertTrue(transcript.rows.last().second.contains("superseded").not())
        assertTrue(transcript.rows.last().second.contains("tc1"))
        assertTrue("the send's own path does not reload the session", delivered.isEmpty())
        assertNull("nothing to supersede the second time", UserAskRecoveryCoordinator.supersedeRecovered("s1"))
    }

    @Test fun `no snapshot - the answers go to the model as a plain message`() = runBlocking {
        records.save(UserAskRecord(ask().resolveTimedOut()!!, assistantPartsJson = null))
        UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 10_000)
        assertTrue(transcript.rows.last().second.contains("Recovered after the app restarted"))
    }

    // ── Source guards ──────────────────────────────────────────────────────

    @Test fun `the view model persists before showing, delivers once, restores before reading`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("beforeShow = { ask ->"))
        assertTrue(vm.contains("UserAskRecoveryCoordinator.saveNew("))
        assertTrue(vm.contains("userAskBatchSnapshot = livePartsJson to toolCalls.map { it.first }"))
        val afterPersist = vm.substringAfter("val toolResultDbId = persistToolResultMessage(resultParts, ").take(400)
        assertTrue(afterPersist.contains("UserAskCenter.markDelivered("))
        val load = vm.substringAfter("    private fun loadSession(): kotlinx.coroutines.Job? {")
        assertTrue(load.indexOf("UserAskRecoveryCoordinator.restoreForSession(sessionId)") in 0 until load.indexOf("val rows = chatRepository.loadMessages(sessionId)"))
        assertTrue(load.contains("&& !awaitsUserAnswer) {"))
        assertTrue(load.contains("if (resumeAfterUserAskRecovery && _canResume.value && !_isStreaming.value)"))
        val send = vm.substringAfter("    private fun sendMessage(").substringBefore("val persistedUser = chatRepository.appendMessage(")
        assertTrue("supersede lands before the user row", send.contains("UserAskRecoveryCoordinator.supersedeRecovered(activeSessionId)"))
        assertTrue("title through the overlay helper", vm.contains("sessionTitle = overlaySessionTitle(),"))
    }
}
