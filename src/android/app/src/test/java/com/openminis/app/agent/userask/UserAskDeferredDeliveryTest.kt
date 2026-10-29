package com.openminis.app.agent.userask

import com.openminis.app.ProductionSources
import com.openminis.app.ui.chat.ChatViewModel
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
 * [T-user-ask-deferred] The deferred path end to end below the view model
 * (iOS 7a818b8c0, e1c070d9b): a deferred call returns at once with the card
 * left open; its resolution rewrites the stored `deferred` result and hands a
 * real answer on as the user's next message - exactly once, also after a kill.
 */
class UserAskDeferredDeliveryTest {

    private val t0 = 1_000_000_000L
    private val args = JSONObject().put(
        "questions",
        JSONArray().put(
            JSONObject().put("type", "options").put("question", "Which DB?")
                .put("options", JSONArray().put(JSONObject().put("label", "SQLite")).put(JSONObject().put("label", "Postgres"))),
        ),
    ).toString()

    private class MemRecords : UserAskRecords {
        val map = LinkedHashMap<String, UserAskRecord>()
        override fun all(): Map<String, UserAskRecord> = synchronized(map) { map.toMap() }
        override fun save(r: UserAskRecord) { synchronized(map) { map[r.ask.requestId] = r } }
        override fun remove(requestId: String) { synchronized(map) { map.remove(requestId) } }
    }

    /** Rows are (role, partsJson); the deferred tool result is rewritten in place like the DB row. */
    private class MemTranscript : UserAskTranscript {
        val rows = mutableListOf<Pair<String, String>>()
        override suspend fun sessionExists(sessionId: String) = true
        override suspend fun partsJsonRows(sessionId: String) = synchronized(rows) { rows.map { it.second } }
        override suspend fun append(sessionId: String, role: String, partsJson: String) { synchronized(rows) { rows += role to partsJson } }
        override suspend fun replaceToolResult(sessionId: String, toolUseId: String, output: String): Boolean = synchronized(rows) {
            for (i in rows.indices) {
                val p = UserAskRecovery.replaceToolResultOutput(rows[i].second, toolUseId, output) ?: continue
                rows[i] = rows[i].first to p
                return true
            }
            false
        }
    }

    private lateinit var records: MemRecords
    private lateinit var transcript: MemTranscript
    private val delivered = mutableListOf<UserAskDeferredDelivery>()

    private val snapshot = JSONArray()
        .put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("toolUseId", "tc1").put("name", "user_ask").put("input", "{}")))
        .toString()

    /** What the loop writes after a deferred call: the assistant row and the `deferred` result row. */
    private fun storeBatch(result: String) {
        transcript.rows += "assistant" to snapshot
        transcript.rows += "user" to UserAskRecovery.resultRowPartsJson(
            PendingAsk.create("x", "s1", "tc1", emptyList(), t0, 0), result, listOf("tc1" to "user_ask"),
        )
    }

    private fun storedOutput(): String {
        val row = JSONArray(transcript.rows.last { it.second.contains("toolResult") }.second)
        return row.getJSONObject(0).getJSONObject("value").getString("output")
    }

    private fun askDeferred(requestId: String = "r1", toolCallId: String = "tc1"): Pair<PendingAsk, String> {
        var saved: PendingAsk? = null
        val res = UserAskToolRunner.executeDeferred(
            args, toolCallId, "s1", "T", nowMs = t0, requestId = requestId,
            beforeShow = { a -> saved = a; records.save(UserAskRecord(a, snapshot)) },
        )
        assertTrue(res.success)
        return saved!! to res.output
    }

    @Before fun setUp() {
        UserAskCenter.resetForTest()
        UserAskRecoveryCoordinator.resetForTest()
        records = MemRecords()
        transcript = MemTranscript()
        delivered.clear()
        UserAskRecoveryCoordinator.install(records, transcript, { _, _ -> }, scanOthers = false) { d -> synchronized(delivered) { delivered += d } }
        UserAskCenter.onChanged = { records.onAskChanged(it) }
        UserAskCenter.clock = { t0 }
        // Deliveries are driven explicitly below (in the app this hook launches one).
        UserAskCenter.onDeferredResolved = null
    }

    @After fun tearDown() {
        UserAskCenter.resetForTest()
        UserAskRecoveryCoordinator.resetForTest()
    }

    @Test fun `a deferred call returns at once and leaves the card open`() {
        val (ask, out) = askDeferred()
        assertEquals(UserAskDeferred.toolResult(1), out)
        val live = UserAskCenter.ask("tc1")!!
        assertTrue(live.deferred)
        assertEquals(UserAskState.PENDING, live.state)
        assertEquals("no countdown", 0, live.timeoutMinutes)
        assertNull(live.expiresAtMs)
        assertFalse("no tool call waits on it", UserAskCenter.isInProcess(ask.requestId))
        assertNotNull("the record is on disk", records.record(ask.requestId))
    }

    @Test fun `answered - the stored result is rewritten and the answer becomes the next message`() = runBlocking {
        val (ask, out) = askDeferred()
        storeBatch(out)
        UserAskCenter.markDelivered(listOf("tc1"))   // the loop stored the `deferred` result
        assertNotNull("the record stays until the ANSWER is handed on", records.record(ask.requestId))
        var routed: PendingAsk? = null
        UserAskCenter.onDeferredResolved = { routed = it }
        UserAskCenter.onResolvedWithoutWaiter = { error("a deferred ask is not a recovered one") }
        UserAskCenter.updateAnswer(ask.requestId, 0, UserAskAnswer(selected = listOf(1)))
        UserAskCenter.submitQuestion(ask.requestId, 0)
        assertEquals(ask.requestId, routed?.requestId)
        UserAskRecoveryCoordinator.deliverDeferred(ask.requestId)
        assertTrue(storedOutput(), storedOutput().contains("\"answer\":\"Postgres\""))
        assertEquals(1, delivered.size)
        val d = delivered.single()
        assertEquals("tc1", d.toolUseId)
        assertEquals("1. Which DB? → Postgres\n" + UserAskDeferred.ANSWER_REMINDER, d.answerMessage)
        assertTrue("consumed", UserAskCenter.ask("tc1")!!.consumed)
        assertTrue("record gone", records.all().isEmpty())
        // Exactly once.
        UserAskRecoveryCoordinator.deliverDeferred(ask.requestId)
        assertEquals(1, delivered.size)
    }

    @Test fun `a newer deferred card replaces the older one, which sends nothing`() = runBlocking {
        val (first, out) = askDeferred()
        storeBatch(out)
        askDeferred(requestId = "r2", toolCallId = "tc9")
        val old = UserAskCenter.asks.value[first.requestId]!!
        assertEquals(UserAskState.DISMISSED, old.state)
        assertEquals(UserAskDismissReason.REPLACED_BY_NEW_ASK, old.dismissReason)
        assertEquals(UserAskState.PENDING, UserAskCenter.asks.value["r2"]!!.state)
        UserAskRecoveryCoordinator.deliverDeferred(first.requestId)
        assertNull("closed cards send no message", delivered.single().answerMessage)
        assertTrue(storedOutput().contains("\"status\":\"dismissed\""))
    }

    @Test fun `Stop leaves a deferred card open, a new user message closes it`() {
        val (ask, _) = askDeferred()
        UserAskCenter.dismissPending("s1", UserAskDismissReason.STOPPED)
        assertEquals(UserAskState.PENDING, UserAskCenter.asks.value[ask.requestId]!!.state)
        assertEquals(listOf(ask.requestId), UserAskCenter.pendingDeferred("s1").map { it.requestId })
        UserAskCenter.dismissPending("s1", UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE)
        assertEquals(UserAskState.DISMISSED, UserAskCenter.asks.value[ask.requestId]!!.state)
    }

    @Test fun `after a kill - an open deferred card comes back, its answer is delivered`() = runBlocking {
        val (ask, out) = askDeferred()
        storeBatch(out)
        UserAskCenter.resetForTest()   // the process died; the record and the transcript survive
        UserAskCenter.onChanged = { records.onAskChanged(it) }
        UserAskCenter.clock = { t0 }
        assertFalse(UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 60_000))
        val live = UserAskCenter.ask("tc1")!!
        assertTrue(live.deferred && live.state == UserAskState.PENDING)
        assertEquals("no assistant row added: it is in the transcript", 2, transcript.rows.size)
        assertFalse(UserAskCenter.isInProcess(ask.requestId))
        UserAskCenter.onDeferredResolved = null
        UserAskCenter.updateAnswer(ask.requestId, 0, UserAskAnswer(selected = listOf(0)))
        UserAskCenter.submitQuestion(ask.requestId, 0)
        UserAskRecoveryCoordinator.deliverDeferred(ask.requestId)
        assertTrue(storedOutput().contains("\"answer\":\"SQLite\""))
        assertEquals("1. Which DB? → SQLite\n" + UserAskDeferred.ANSWER_REMINDER, delivered.single().answerMessage)
    }

    @Test fun `after a kill - an answer given just before it is delivered on load`() = runBlocking {
        val (ask, out) = askDeferred()
        storeBatch(out)
        val answered = ask.setAnswer(UserAskAnswer(selected = listOf(1)), 0).copy(submitted = setOf(0))
            .resolveAnswered(UserAskAnswerSource.NOTIFICATION)!!
        records.save(UserAskRecord(answered, snapshot))
        UserAskCenter.resetForTest()
        UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 60_000)
        assertTrue(storedOutput().contains("Postgres"))
        assertEquals(1, delivered.size)
        assertNotNull(delivered.single().answerMessage)
        assertTrue(records.all().isEmpty())
        UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 61_000)
        assertEquals("never twice", 1, delivered.size)
    }

    @Test fun `killed before the batch was written - the answer becomes the call's result`() = runBlocking {
        val (ask, _) = askDeferred()
        // Nothing in the transcript: the loop never stored the batch.
        UserAskCenter.onDeferredResolved = null
        UserAskCenter.updateAnswer(ask.requestId, 0, UserAskAnswer(selected = listOf(1)))
        UserAskCenter.submitQuestion(ask.requestId, 0)
        UserAskRecoveryCoordinator.deliverDeferred(ask.requestId)
        assertEquals(listOf("assistant", "user"), transcript.rows.map { it.first })
        assertTrue(storedOutput().contains("Postgres"))
    }

    // [T-user-ask-deferred-live-batch] Resolved while its own batch still runs:
    // the `deferred` result is not stored yet, so the hand-on waits for it
    // instead of taking the killed-before-the-batch fallback (which appended
    // the assistant row and a result row the loop then wrote again).
    @Test fun `answered while its batch still runs - handed on once the batch is stored`() = runBlocking {
        val (ask, out) = askDeferred()
        val routed = mutableListOf<PendingAsk>()
        UserAskCenter.onDeferredResolved = { routed += it }
        UserAskCenter.updateAnswer(ask.requestId, 0, UserAskAnswer(selected = listOf(1)))
        UserAskCenter.submitQuestion(ask.requestId, 0)
        assertTrue("held: the batch is not stored", routed.isEmpty())
        storeBatch(out)
        UserAskCenter.markDelivered(listOf("tc1"))
        assertEquals(listOf(ask.requestId), routed.map { it.requestId })
        UserAskRecoveryCoordinator.deliverDeferred(ask.requestId)
        assertEquals("no second assistant row or result row", listOf("assistant", "user"), transcript.rows.map { it.first })
        assertTrue(storedOutput().contains("Postgres"))
        UserAskCenter.markDelivered(listOf("tc1"))
        assertEquals("never twice", 1, routed.size)
    }

    @Test fun `replaced by a second user_ask in the same batch - the first is held until the batch is stored`() {
        val (first, _) = askDeferred()
        val routed = mutableListOf<PendingAsk>()
        UserAskCenter.onDeferredResolved = { routed += it }
        askDeferred(requestId = "r2", toolCallId = "tc9")
        assertEquals(UserAskState.DISMISSED, UserAskCenter.asks.value[first.requestId]!!.state)
        assertTrue(routed.isEmpty())
        UserAskCenter.markDelivered(listOf("tc1", "tc9"))
        assertEquals(listOf(first.requestId), routed.map { it.requestId })
    }

    @Test fun `a run that never stored its batch releases the held ask when it ends`() {
        val (ask, _) = askDeferred()
        val routed = mutableListOf<PendingAsk>()
        UserAskCenter.onDeferredResolved = { routed += it }
        UserAskCenter.dismiss(ask.requestId, UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE)
        assertTrue(routed.isEmpty())
        UserAskCenter.releaseUnstoredDeferred("other-session")
        assertTrue("another session's run does not release it", routed.isEmpty())
        UserAskCenter.releaseUnstoredDeferred("s1")
        assertEquals(listOf(ask.requestId), routed.map { it.requestId })
    }

    @Test fun `an open card whose batch was stored resolves straight through`() {
        val (ask, out) = askDeferred()
        storeBatch(out)
        UserAskCenter.markDelivered(listOf("tc1"))
        val routed = mutableListOf<PendingAsk>()
        UserAskCenter.onDeferredResolved = { routed += it }
        UserAskCenter.dismiss(ask.requestId, UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE)
        assertEquals(listOf(ask.requestId), routed.map { it.requestId })
    }

    @Test fun `the result rewrite touches only the matching tool result`() {
        val parts = JSONArray()
            .put(JSONObject().put("type", "toolResult").put("value", JSONObject().put("toolUseId", "a").put("output", "A")
                .put("snapshot", JSONObject().put("type", "text").put("text", "A"))))
            .put(JSONObject().put("type", "toolResult").put("value", JSONObject().put("toolUseId", "b").put("output", "B")))
            .toString()
        val out = JSONArray(UserAskRecovery.replaceToolResultOutput(parts, "a", "NEW")!!)
        assertEquals("NEW", out.getJSONObject(0).getJSONObject("value").getString("output"))
        assertEquals("NEW", out.getJSONObject(0).getJSONObject("value").getJSONObject("snapshot").getString("text"))
        assertEquals("B", out.getJSONObject(1).getJSONObject("value").getString("output"))
        assertNull(UserAskRecovery.replaceToolResultOutput(parts, "zzz", "NEW"))
    }

    @Test fun `the user bubble hides the note for the model, a note-only row is kept`() {
        val msg = "1. Which DB? → Postgres\n" + UserAskDeferred.ANSWER_REMINDER
        assertEquals("1. Which DB? → Postgres", ChatViewModel.userBubbleText(msg))
        assertEquals("plain", ChatViewModel.userBubbleText("plain"))
        assertEquals(UserAskDeferred.ANSWER_REMINDER, ChatViewModel.userBubbleText(UserAskDeferred.ANSWER_REMINDER))
    }

    @Test fun `view model wiring`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        // Exposure: one decision per run, with the deferred mode and the answer origin.
        assertTrue(vm.contains("com.openminis.app.agent.userask.UserAskExposure.decision("))
        assertTrue(vm.contains("userAskDeferredThisRun = d.deferred"))
        assertTrue(vm.contains("decideUserAskExposureForRun(interactive = !headless, userAskAnswer = userAskAnswer)"))
        // The call: a deferred run returns at once.
        val exec = vm.substringAfter("com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME -> if (!userAskExposedThisRun) {")
            .substringBefore("else -> ToolExecutionResult(\"Unknown tool: \$name\", false)")
        assertTrue(exec.indexOf("} else if (userAskDeferredThisRun) {") in 0 until exec.indexOf("UserAskToolRunner.execute("))
        assertTrue(exec.contains("userAskAskedThisRun = true"))
        // A silent end after a deferred card is not an empty turn.
        assertTrue(vm.contains("val deferredAskShown = userAskDeferredThisRun && userAskAskedThisRun"))
        assertTrue(vm.contains("hasText = turnHasText || deferredAskShown,"))
        // The answer: no context dialog, no composer files, queued as the user's own follow-up.
        assertTrue(vm.contains("sendMessage(text, skipContextCheck = true, headless = true, userAskAnswer = true)"))
        assertTrue(vm.contains("if (headless && !userAskAnswer) QueuedPromptOrigin.PROGRAMMATIC else QueuedPromptOrigin.USER"))
        // The answer is headless: it brings no files and never takes the composer's
        // ([T-headless-own-attachments], HeadlessAttachmentsTest).
        assertTrue(vm.contains("val promptAttachments = PromptAttachments.of(ownsComposer = !headless, own = headlessAttachments, composer = _attachments.value)"))
        assertTrue(vm.contains("val pendingAttachments = PromptAttachments.of(ownsComposer, ownAttachments, _attachments.value)"))
        // A running compaction is waited out, then the answer goes.
        val onDelivered = vm.substringAfter("internal fun onUserAskDeferredDelivered(").substringBefore("\n    }\n")
        assertTrue(onDelivered.indexOf("replaceUserAskResultInMemory(d.toolUseId, d.resultJson)") < onDelivered.indexOf("submitUserAskAnswer(message)"))
        assertTrue(onDelivered.contains("while (_isCompacting.value && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(500)"))
        assertTrue(onDelivered.contains("UserAskSettings.isEnabled(context)"))
        // [T-user-ask-deferred-live-batch] While a run is live, agentHistory is
        // patched on the loop's thread before the next request, never from main.
        val replace = vm.substringAfter("internal fun replaceUserAskResultInMemory(").substringBefore("\n    }\n")
        assertTrue(replace.contains("val history = if (_isStreaming.value) {\n            pendingHistoryPatches.add(toolUseId to output)"))
        val loop = vm.substringAfter("for (turn in 0 until turnCap) {")
        assertTrue(loop.indexOf("applyPendingHistoryPatches()") in 0 until loop.indexOf("sanitizeAgentHistory()"))
        // A run that never stored its batch releases its held deferred asks.
        val wrapper = vm.substringAfter("private suspend fun runAgentLoop(").substringBefore("private suspend fun runAgentLoopBody(")
        assertTrue(wrapper.substringAfter("} finally {").contains("UserAskCenter.releaseUnstoredDeferred(activeSessionId)"))
        // A message the user types closes an open deferred card in memory before its request.
        val send = vm.substringAfter("UserAskRecoveryCoordinator.supersedeRecovered(activeSessionId)?.let { parts ->")
        assertTrue(send.substringBefore("if (editingId != null)").contains("supersedeDeferredAsks(activeSessionId)"))
        // Every live user bubble hides the model's note.
        assertEquals(3, vm.split("content = userBubbleText(").size - 1)
    }
}
