package com.openminis.app.agent.jobs

import com.openminis.app.IosSources
import com.openminis.app.ProductionSources
import com.openminis.app.agent.jobs.SubAgentTermination.Delivery
import com.openminis.app.agent.jobs.SubAgentTermination.Reason
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.sandbox.offload.SessionsOffloadHandler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-termination] Android port of iOS 76b9b0428: a sub agent run
 * records how it ended, a turn that ends with no text gets one closing
 * request, background callbacks record their delivery, and
 * `minis-sessions-cli status` reports it all.
 */
class SubAgentTerminationTest {

    @After fun tearDown() {
        SubAgentTerminationStore.installForTest(SubAgentTerminationStore(null))
        AgentJobRegistry.resetForTest()
    }

    // ── classification ──────────────────────────────────────────────────

    private fun classify(
        loop: String = "completed", cancelled: Boolean = false, budget: Boolean = false,
        error: String? = null, final: Boolean = false, tool: String? = null,
    ) = SubAgentTerminationRules.classify(SubAgentRunEnd(loop, cancelled, budget, error, final, tool)).first

    @Test fun `every ending has one name, in the iOS order`() {
        assertEquals(Reason.COMPLETED, classify(final = true))
        assertEquals(Reason.CANCELLED, classify(loop = "cancelled", final = true))
        assertEquals(Reason.CANCELLED, classify(cancelled = true, final = true))
        assertEquals(Reason.TIMEOUT, classify(loop = "timeout", final = true))
        assertEquals("a final answer outranks an expired budget", Reason.COMPLETED, classify(budget = true, final = true))
        assertEquals(Reason.TIMEOUT, classify(budget = true, error = "boom", tool = "failed"))
        assertEquals(Reason.MODEL_ERROR, classify(error = "HTTP 503", tool = "failed"))
        assertEquals(Reason.TOOL_ERROR, classify(tool = "failed"))
        assertEquals(Reason.MODEL_ERROR, classify(tool = "success"))
        assertEquals("the model returned no text",
            SubAgentTerminationRules.classify(SubAgentRunEnd("completed", hasFinalAnswer = false)).second)
        assertEquals("x".repeat(300), SubAgentTerminationRules.classify(SubAgentRunEnd("completed", errorText = "x".repeat(400), hasFinalAnswer = false)).second)
    }

    private fun msg(role: LLMMessage.Role, text: String = "", tool: Boolean = false, result: Boolean = false): LLMMessage {
        val parts = buildList<AgentContentPart> {
            if (text.isNotEmpty()) add(AgentContentPart.Text(text))
            if (tool) add(AgentContentPart.ToolUse("t1", "shell_execute", JSONObject()))
            if (result) add(AgentContentPart.ToolResult("t1", "shell_execute", "out"))
        }
        return LLMMessage(role, text, contentParts = parts)
    }

    @Test fun `only a last assistant text turn without a tool call is a final answer`() {
        val shapes = SubAgentTerminationRules::shapesOf
        val user = msg(LLMMessage.Role.USER, "task")
        assertTrue(SubAgentTerminationRules.endsWithFinalTurn(shapes(listOf(user, msg(LLMMessage.Role.ASSISTANT, "the report")))))
        // Narration next to a tool call, then the run stopped at the tool result.
        val narratedThenStopped = listOf(user, msg(LLMMessage.Role.ASSISTANT, "let me check X", tool = true), msg(LLMMessage.Role.USER, result = true))
        assertFalse(SubAgentTerminationRules.endsWithFinalTurn(shapes(narratedThenStopped)))
        assertFalse(SubAgentTerminationRules.endsWithFinalTurn(shapes(listOf(user, msg(LLMMessage.Role.ASSISTANT, "checking", tool = true)))))
        assertFalse(SubAgentTerminationRules.endsWithFinalTurn(shapes(listOf(user, msg(LLMMessage.Role.ASSISTANT)))))
        assertFalse(SubAgentTerminationRules.endsWithFinalTurn(emptyList()))
    }

    @Test fun `the app's own no-answer texts are not answers`() {
        assertTrue(SubAgentTerminationRules.isFinalAnswer("Here is the report."))
        assertFalse(SubAgentTerminationRules.isFinalAnswer("   "))
        assertFalse(SubAgentTerminationRules.isFinalAnswer(SubAgentTerminationRules.NO_FINAL_ANSWER_LABEL + "\nearlier line"))
        assertFalse(SubAgentTerminationRules.isFinalAnswer(HelperRunner.emptyResultNote("completed")))
        assertFalse(SubAgentTerminationRules.isFinalAnswer(SubAgentTerminationRules.NO_ANSWER_NOTE_PREFIX + " - termination_reason=timeout.)"))
    }

    @Test fun `the labelled fallback no longer reports completed`() {
        val labelled = SubAgentTerminationRules.NO_FINAL_ANSWER_LABEL + "\nlet me look at the config first"
        assertEquals(HelperRunner.NO_DELIVERABLE, HelperRunner.resolvedStatus("completed", labelled))
        assertEquals("completed", HelperRunner.resolvedStatus("completed", "the answer"))
        assertEquals("timeout", HelperRunner.resolvedStatus("timeout", labelled))
        // childRunFacts still writes exactly that label when it falls back.
        val rows = listOf(
            row("a1", "assistant", """[{"type":"text","value":"let me check"},{"type":"toolUse","value":{"toolUseId":"t1","name":"shell_execute","input":"{}"}}]"""),
            row("u1", "user", """[{"type":"toolResult","value":{"toolUseId":"t1","name":"shell_execute","output":"x","success":true}}]"""),
            row("a2", "assistant", """[{"type":"toolUse","value":{"toolUseId":"t2","name":"shell_execute","input":"{}"}}]"""),
        )
        assertTrue(HelperRunner.childRunFacts(rows).lastText.startsWith(SubAgentTerminationRules.NO_FINAL_ANSWER_LABEL))
    }

    // ── the runner's record ─────────────────────────────────────────────

    private fun termination(
        history: List<LLMMessage>, result: String, tool: SubAgentToolSnapshot?, loop: String = "completed",
        budget: Boolean = false, error: String? = null, closing: Boolean = false,
    ) = HelperRunner.terminationOf("child", "job", "parent", loop, budget, error, history, result, tool, closing, Delivery.INLINE, nowMs = 1000)

    @Test fun `field shapes - a failed last tool, a successful one, narration, an answer`() {
        val user = msg(LLMMessage.Role.USER, "task")
        val stoppedAtResult = listOf(user, msg(LLMMessage.Role.ASSISTANT, tool = true), msg(LLMMessage.Role.USER, result = true))
        // EEA94903: `rg … timeout:30` failed, then nothing.
        val failed = SubAgentToolSnapshot("shell_execute \"rg\"", "failed", "[Command timed out after 30s]")
        val t1 = termination(stoppedAtResult, "", failed)
        assertEquals(Reason.TOOL_ERROR, t1.reason)
        assertEquals("failed", t1.lastToolStatus)
        assertFalse(t1.partialOutputAvailable)
        // D9976EE1: the last tool succeeded, then no text.
        assertEquals(Reason.MODEL_ERROR, termination(stoppedAtResult, "", failed.copy(status = "success")).reason)
        // Narration before a tool call, then the run stopped: labelled fallback text.
        val narrated = listOf(user, msg(LLMMessage.Role.ASSISTANT, "checking", tool = true), msg(LLMMessage.Role.USER, result = true))
        val t3 = termination(narrated, SubAgentTerminationRules.NO_FINAL_ANSWER_LABEL + "\nchecking", failed.copy(status = "success"), closing = true)
        assertEquals(Reason.MODEL_ERROR, t3.reason)
        assertTrue(t3.partialOutputAvailable)
        assertTrue(t3.closingAttempted)
        assertEquals(HelperRunner.NO_DELIVERABLE, HelperRunner.reportedStatus("completed", "checking", t3))
        // A real answer.
        val answered = narrated + msg(LLMMessage.Role.ASSISTANT, "the report")
        val t4 = termination(answered, "the report", failed.copy(status = "success"))
        assertEquals(Reason.COMPLETED, t4.reason)
        assertEquals("completed", HelperRunner.reportedStatus("completed", "the report", t4))
        // Budget ran out and the wrap-up came back empty; a stop from the parent.
        assertEquals(Reason.TIMEOUT, termination(stoppedAtResult, "", null, budget = true).reason)
        assertEquals(Reason.CANCELLED, termination(answered, "the report", null, loop = "cancelled").reason)
        // A "failed" loop (request error) without an answer.
        assertEquals(Reason.MODEL_ERROR, termination(stoppedAtResult, "", null, loop = "failed", error = "HTTP 429").reason)
    }

    @Test fun `the parent result names the reason and the last tool`() {
        val t = termination(listOf(msg(LLMMessage.Role.USER, "t")), "partial notes",
            SubAgentToolSnapshot("shell_execute \"build\"", "failed", "exit 1: cannot find symbol"))
        val text = SubAgentTerminationRules.helperResultText("partial notes", t)
        assertTrue(text.startsWith("(the agent ended without a final answer - termination_reason=tool_error: "))
        assertTrue(text.contains(" Last tool: shell_execute \"build\" → failed: exit 1: cannot find symbol."))
        assertTrue(text.contains("It wrote partial notes before ending; they follow below."))
        assertTrue(text.endsWith("\npartial notes"))
        assertFalse(SubAgentTerminationRules.isFinalAnswer(text))
        val done = termination(listOf(msg(LLMMessage.Role.ASSISTANT, "answer")), "answer", null)
        assertEquals("answer", SubAgentTerminationRules.helperResultText("answer", done))
    }

    @Test fun `payload keys are the iOS snake_case fields`() {
        val t = termination(listOf(msg(LLMMessage.Role.USER, "t")), "", SubAgentToolSnapshot("file_read", "success", "ok"))
            .copy(delivery = Delivery.PENDING, deliveryDetail = "d")
        val p = t.payload()
        assertEquals(setOf(
            "termination_reason", "partial_output_available", "closing_attempted", "termination_detail",
            "last_tool_name", "last_tool_status", "last_tool_result", "delivery_state", "delivery_detail", "ended_at",
        ), p.keys().asSequence().toSet())
        assertEquals("model_error", p.getString("termination_reason"))
        assertEquals("pending", p.getString("delivery_state"))
        assertEquals("1970-01-01T00:00:01Z", p.getString("ended_at"))
        // The final payload carries them too, with the note as `result`.
        val json = JSONObject(HelperRunner.resultJson(
            "no_deliverable", "", "m", HelperModelTier.PRIMARY, "primary", 3, 1000, "child", "job", termination = t,
        ))
        assertEquals("model_error", json.getString("termination_reason"))
        assertTrue(json.getString("result").startsWith(SubAgentTerminationRules.NO_ANSWER_NOTE_PREFIX))
    }

    // ── the store ───────────────────────────────────────────────────────

    private fun tempFile() = java.io.File(java.nio.file.Files.createTempDirectory("subterm").toFile(), SubAgentTerminationStore.FILE_NAME)

    @Test fun `a running record with nothing alive behind it reads as interrupted`() {
        val store = SubAgentTerminationStore(tempFile())
        store.begin("c1", "j1", "p1", nowMs = 10)
        assertEquals(Reason.RUNNING, store.resolved("c1", isLive = true)!!.reason)
        val r = store.resolved("c1", isLive = false, nowMs = 50)!!
        assertEquals(Reason.INTERRUPTED, r.reason)
        assertEquals(50L, r.endedAtMs)
        assertEquals("rewritten in the store", Reason.INTERRUPTED, store.record("c1")!!.reason)
        assertNull(store.resolved("unknown", isLive = false))
    }

    @Test fun `end keeps the start time and an earlier delivery, delivery moves by job, records survive a reload`() {
        val f = tempFile()
        val store = SubAgentTerminationStore(f)
        store.begin("c1", "j1", "p1", nowMs = 10)
        store.begin("c1", "j1", "p1", nowMs = 99) // same running job: no-op
        store.end(SubAgentTermination("c1", "j1", "p1", Reason.TOOL_ERROR, delivery = Delivery.PENDING, startedAtMs = 500, endedAtMs = 600))
        assertEquals(10L, store.record("c1")!!.startedAtMs)
        store.setDeliveryForJob("j1", Delivery.QUEUED, "waiting behind the parent's running turn")
        store.end(store.record("c1")!!.copy(delivery = null))
        assertEquals("a later end without delivery keeps it", Delivery.QUEUED, store.record("c1")!!.delivery)
        store.setDeliveryForJob("nope", Delivery.FAILED) // unknown job: no-op
        val reloaded = SubAgentTerminationStore(f).record("c1")!!
        assertEquals(Reason.TOOL_ERROR, reloaded.reason)
        assertEquals(Delivery.QUEUED, reloaded.delivery)
        assertEquals("waiting behind the parent's running turn", reloaded.deliveryDetail)
        assertEquals(600L, reloaded.endedAtMs)
        // A new job in the same session restarts the record.
        store.begin("c1", "j2", "p1", nowMs = 700)
        assertEquals(Reason.RUNNING, store.record("c1")!!.reason)
        assertNull(store.record("c1")!!.delivery)
    }

    @Test fun `capacity drops the oldest records and a corrupt file is tolerated`() {
        val f = tempFile()
        val store = SubAgentTerminationStore(f)
        for (i in 0..SubAgentTerminationStore.CAPACITY + 4) store.begin("c$i", "j$i", null, nowMs = i.toLong())
        assertEquals(SubAgentTerminationStore.CAPACITY, store.count())
        assertNull(store.record("c0"))
        assertTrue(store.record("c${SubAgentTerminationStore.CAPACITY + 4}") != null)
        f.writeText("{not json")
        assertEquals(0, SubAgentTerminationStore(f).count())
    }

    // ── the closing request ─────────────────────────────────────────────

    @Test fun `the closing request goes once, to a helper, after a turn with no text, not on the last round`() {
        assertTrue(HelperRunner.shouldSendClosingRequest(isHelper = true, alreadySent = false, turnHasText = false, turn = 3, turnCap = 200))
        assertFalse(HelperRunner.shouldSendClosingRequest(isHelper = false, alreadySent = false, turnHasText = false, turn = 3, turnCap = 200))
        assertFalse(HelperRunner.shouldSendClosingRequest(isHelper = true, alreadySent = true, turnHasText = false, turn = 3, turnCap = 200))
        assertFalse(HelperRunner.shouldSendClosingRequest(isHelper = true, alreadySent = false, turnHasText = true, turn = 3, turnCap = 200))
        assertFalse(HelperRunner.shouldSendClosingRequest(isHelper = true, alreadySent = false, turnHasText = false, turn = 199, turnCap = 200))
    }

    @Test fun `the closing nudge is byte-identical to iOS and hidden`() {
        val ios = runCatching { IosSources.declaring("static let helperClosingNudgeText") }.getOrNull() ?: return
        val literal = ios.substringAfter("static let helperClosingNudgeText =").substringAfter('"').substringBefore("\"\n")
        assertEquals(literal, HelperRunner.CLOSING_NUDGE_TEXT)
        assertTrue(HelperRunner.CLOSING_NUDGE_TEXT.startsWith("<system-reminder>"))
    }

    @Test fun `source - the loop sends the closing request after the empty-turn retry, tools withdrawn, persisted`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val loop = vm.substringAfter("// If no tool calls, we're done").substringBefore("// [T-android-scheduled-resume-nudge]")
        assertTrue(loop.contains("val closingRequestDue = com.openminis.app.agent.jobs.HelperRunner.shouldSendClosingRequest("))
        // [T-android-empty-turn-this-turn] The per-turn test moved into EmptyTurnRecovery.turnHasText.
        assertTrue("this turn's text, not the bubble's", loop.contains("val turnHasText = EmptyTurnRecovery.turnHasText(turnText, allToolBlocks, turnStartBlockIndex)"))
        assertTrue(ProductionSources.read("ui/chat/EmptyTurnRecovery.kt")
            .contains("blocks.drop(turnStartBlockIndex.coerceAtLeast(0)).any { it.kind == \"text\" && it.content.isNotBlank() }"))
        assertTrue("not after a refusal", loop.contains("&& !com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason)"))
        assertTrue("the empty-turn retry runs first", loop.indexOf("EmptyTurnRecovery.plan(") < loop.indexOf("if (closingRequestDue) {"))
        val send = loop.substringAfter("if (closingRequestDue) {")
        assertTrue(send.contains("helperClosingAttempted = true"))
        assertTrue("tools withdrawn", send.contains("helperWrapUpInjected = true"))
        assertTrue(send.contains("val nudge = com.openminis.app.agent.jobs.HelperRunner.CLOSING_NUDGE_TEXT"))
        assertTrue("persisted", send.contains("chatRepository.appendMessage(nudgeSid, \"user\""))
        assertTrue(send.contains("continue"))
    }

    @Test fun `source - every runner path records the termination and the start writes running`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertEquals("wait (incl. cancel) + background + resume", 4, Regex("recordChildTermination\\(\\s*childVm,").findAll(vm).count())
        assertEquals("wait mode (normal + cancel) is inline", 2, Regex("Delivery\\.INLINE").findAll(vm).count())
        assertEquals("begin on a fresh run and a resume", 2, Regex("SubAgentTerminationStore\\.shared\\.begin\\(").findAll(vm).count())
        assertEquals("background and resume hooks start pending", 2, Regex("Delivery\\.PENDING").findAll(vm).count())
        assertTrue(vm.contains("finalStatus = hr.reportedStatus(finalStatus, facts.lastText, termination)"))
        assertEquals("all three payloads carry it", 3, Regex("termination = termination,").findAll(vm).count())
    }

    @Test fun `source - the dispatcher records every delivery outcome`() {
        val app = ProductionSources.read("MinisApp.kt")
        val d = app.substringAfter("AgentJobRegistry.followUpDispatcher = {").substringBefore("AgentCallbackLabels.install")
        assertTrue(d.contains("\"Running\" -> terminations.setDeliveryForJob(jobId, com.openminis.app.agent.jobs.SubAgentTermination.Delivery.DELIVERED)"))
        assertTrue(d.contains("\"Queued\" -> terminations.setDeliveryForJob(jobId, com.openminis.app.agent.jobs.SubAgentTermination.Delivery.QUEUED"))
        assertTrue(d.contains("\"parent session no longer exists\""))
        assertEquals(3, Regex("Delivery\\.FAILED").findAll(d).count())
        assertTrue(app.contains("SubAgentTerminationStore.install(filesDir)"))
    }

    // ── the callback (background mode) ──────────────────────────────────

    private fun backgroundJob(): AgentJob {
        val job = AgentJobRegistry.register(
            title = "t", origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
            target = AgentJobTarget.ChildOfCurrent("parent", "tool1"), prompt = "p",
            then = AgentJobThen.FollowUpParent(null),
        )
        AgentJobRegistry.markRunning(job.id, "child")
        return job
    }

    @Test fun `the callback carries the termination note and a muted parent marks the delivery failed`() {
        val store = SubAgentTerminationStore(null).also { SubAgentTerminationStore.installForTest(it) }
        val sent = mutableListOf<String>()
        AgentJobRegistry.followUpDispatcher = { _, text, _ -> sent += text }
        val job = backgroundJob()
        store.begin("child", job.id, "parent")
        AgentJobRegistry.setCompletionHook(job.id) {
            store.end(SubAgentTermination("child", job.id, "parent", Reason.TOOL_ERROR, lastTool = "shell_execute",
                lastToolStatus = "failed", lastToolSummary = "exit 2", delivery = Delivery.PENDING, startedAtMs = 0))
        }
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, SubAgentTerminationRules.NO_FINAL_ANSWER_LABEL + "\nnotes")
        assertEquals(1, sent.size)
        assertTrue(sent[0].contains("termination_reason=tool_error"))
        assertTrue(sent[0].contains("Last tool: shell_execute → failed: exit 2."))
        assertEquals("dispatched; the app's dispatcher records the outcome", Delivery.PENDING, store.record("child")!!.delivery)

        val muted = backgroundJob()
        store.begin("child", muted.id, "parent")
        AgentJobRegistry.muteDelegationResults("parent")
        AgentJobRegistry.setCompletionHook(muted.id) {
            store.end(store.record("child")!!.copy(reason = Reason.CANCELLED, delivery = Delivery.PENDING))
        }
        AgentJobRegistry.finish(muted.id, AgentJobState.CANCELLED, null)
        assertEquals(1, sent.size)
        assertEquals(Delivery.FAILED, store.record("child")!!.delivery)
        assertEquals("withheld: the user stopped the parent's agents", store.record("child")!!.deliveryDetail)
    }

    @Test fun `no dispatcher installed is a failed delivery`() {
        val store = SubAgentTerminationStore(null).also { SubAgentTerminationStore.installForTest(it) }
        val job = backgroundJob()
        store.begin("child", job.id, "parent")
        AgentJobRegistry.setCompletionHook(job.id) {
            store.end(store.record("child")!!.copy(reason = Reason.COMPLETED, delivery = Delivery.PENDING))
        }
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, "answer")
        assertEquals(Delivery.FAILED, store.record("child")!!.delivery)
    }

    // ── sessions-cli status ─────────────────────────────────────────────

    private fun row(id: String, role: String, parts: String) =
        MessageEntity(id = id, sessionId = "child", role = role, partsJson = parts, createdAt = 0, sortOrder = 0)

    private val transcript = listOf(
        row("u0", "user", """[{"type":"text","value":"task"}]"""),
        row("a1", "assistant", """[{"type":"text","value":"I'll run the build."},{"type":"toolUse","value":{"toolUseId":"t1","name":"shell_execute","input":"{}","description":"Build the app"}}]"""),
        row("u1", "user", """[{"type":"toolResult","value":{"toolUseId":"t1","name":"shell_execute","output":"error:\n  cannot   find symbol","success":false}}]"""),
    )

    @Test fun `last activity comes from the stored transcript`() {
        val (message, tool) = SubAgentTerminationRules.lastActivity(transcript)
        assertEquals("I'll run the build.", message)
        assertEquals(SubAgentToolSnapshot("shell_execute \"Build the app\"", "failed", "error: cannot find symbol"), tool)
        val cancelled = row("u2", "user", JSONArray().put(JSONObject().put("type", "toolResult").put("value", JSONObject()
            .put("toolUseId", "t1").put("name", "shell_execute").put("success", false)
            .put("output", "<system-reminder>The user cancelled this operation. The returned result may be incomplete.</system-reminder>"))).toString())
        assertEquals("cancelled", SubAgentTerminationRules.lastActivity(transcript + cancelled).second!!.status)
        assertEquals("" to null, SubAgentTerminationRules.lastActivity(emptyList()))
    }

    private fun session(preview: String? = null) = ChatSessionEntity(
        id = "child", title = "Agent · build", modelId = "claude-x", createdAt = 0, updatedAt = 0,
        lastMessage = preview, parentSessionId = "parent",
    )

    @Test fun `status reports the iOS fields, the termination and a last-activity fallback`() {
        val t = SubAgentTermination("child", "j1", "parent", Reason.TOOL_ERROR, detail = "d", lastTool = "shell_execute",
            lastToolStatus = "failed", lastToolSummary = "error", delivery = Delivery.DELIVERED, startedAtMs = 0, endedAtMs = 0)
        val data = SessionsOffloadHandler.statusData(session(), transcript, isRunning = false, termination = t)
        for (k in listOf("session_id", "title", "model_name", "is_running", "last_message", "last_tool", "updated_at",
            "last_tool_status", "last_tool_result", "parent_session_id", "termination_reason", "delivery_state")) {
            assertTrue("missing $k", data.has(k))
        }
        assertFalse(data.getBoolean("is_running"))
        assertEquals("I'll run the build.", data.getString("last_message"))
        assertEquals("shell_execute \"Build the app\"", data.getString("last_tool"))
        assertEquals("failed", data.getString("last_tool_status"))
        assertEquals("tool_error", data.getString("termination_reason"))
        assertEquals("delivered", data.getString("delivery_state"))
        // No assistant text at all: the session's recorded last activity stands in.
        val toolOnly = listOf(transcript[0], row("a1", "assistant", """[{"type":"toolUse","value":{"toolUseId":"t1","name":"file_read","input":"{}"}}]"""))
        assertEquals("Read file", SessionsOffloadHandler.statusData(session("Read file"), toolOnly, true, null).getString("last_message"))
        // A plain chat has no termination fields.
        assertFalse(SessionsOffloadHandler.statusData(session(), transcript, true, null).has("termination_reason"))
    }

    @Test fun `source - status is a sessions-cli subcommand`() {
        val h = ProductionSources.read("sandbox/offload/SessionsOffloadHandler.kt")
        assertTrue(h.contains("\"status\" -> cmdStatus(args)"))
        assertTrue(h.contains("  status    Get current status of a session"))
        assertTrue(h.contains(".resolved(sessionId, isLive = running || hasLiveJob(sessionId))"))
    }
}
