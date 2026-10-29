package com.openminis.app.agent.userask

import com.openminis.app.ProductionSources
import com.openminis.app.tools.AgentTools
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-port-user-ask] The runtime: a tool call waits on the card, and the card,
 * the timeout, Stop and a new user message each end the wait - first one wins.
 */
class UserAskCenterTest {

    private var now = 1_000_000_000L

    @Before fun setUp() {
        UserAskCenter.resetForTest()
        UserAskCenter.clock = { now }
    }

    @After fun tearDown() = UserAskCenter.resetForTest()

    private fun argsJson(vararg qs: Map<String, Any?>) =
        JSONObject().put("questions", JSONArray(qs.map { JSONObject(it) })).toString()

    private val pick = mapOf("type" to "options", "question" to "Which DB?",
        "options" to listOf(mapOf("label" to "SQLite"), mapOf("label" to "Postgres")))
    private val confirm = mapOf("type" to "confirm", "question" to "rm -rf build/", "impact" to "deletes build output", "risk" to "high")

    /** Start the tool call, wait until its ask is registered, return (deferred, requestId). */
    private fun start(scope: kotlinx.coroutines.CoroutineScope, args: String, tool: String = "tc1", minutes: Int = 5) =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            UserAskToolRunner.execute(args, tool, "sess-1", "Title", minutes, nowMs = now, requestId = "req-$tool")
        }

    @Test fun `answering on the card resumes the tool call with the result`() = runBlocking {
        val call = start(this, argsJson(pick))
        val ask = UserAskCenter.ask(forToolUseId = "tc1")
        assertNotNull(ask)
        assertEquals(UserAskState.PENDING, ask!!.state)
        assertEquals("the recommended option starts selected", listOf(0), ask.answers[0].selected)
        assertTrue(UserAskCenter.hasPendingAsk("sess-1"))
        UserAskCenter.submitQuestion(ask.requestId, 0)
        val r = withTimeout(2_000) { call.await() }
        assertTrue(r.success)
        assertEquals("""{"results":[{"answer":"SQLite","i":0,"status":"answered"}]}""", r.output)
        assertFalse(UserAskCenter.hasPendingAsk("sess-1"))
    }

    @Test fun `a wizard goes question by question and a confirm submits on one tap`() = runBlocking {
        val call = start(this, argsJson(pick, confirm))
        val rid = UserAskCenter.ask("tc1")!!.requestId
        UserAskCenter.updateAnswer(rid, 0, UserAskAnswer(selected = listOf(1)))
        UserAskCenter.submitQuestion(rid, 0)
        assertEquals(1, UserAskCenter.asks.value[rid]!!.currentIndex)
        UserAskCenter.goTo(rid, 0)
        assertEquals("Previous submits nothing", setOf(0), UserAskCenter.asks.value[rid]!!.submitted)
        UserAskCenter.submitQuestion(rid, 0)
        UserAskCenter.confirm(rid, 1, UserAskConfirmChoice.DENY, note = "use make clean")
        val out = withTimeout(2_000) { call.await() }.output
        assertEquals(listOf(UserAskResultStatus.ANSWERED, UserAskResultStatus.DENIED), UserAskResolver.statuses(out))
        assertTrue(out.contains("\"answer\":\"Postgres\"") && out.contains("\"note\":\"use make clean\""))
    }

    @Test fun `a timeout applies nothing`() = runBlocking {
        val call = start(this, argsJson(confirm))
        now += 5 * 60_000L
        UserAskCenter.tick(now)
        val out = withTimeout(2_000) { call.await() }.output
        assertEquals(listOf(UserAskResultStatus.TIMEOUT), UserAskResolver.statuses(out))
        assertFalse(out.contains("\"status\":\"approved\""))
        assertEquals(UserAskState.TIMED_OUT, UserAskCenter.ask("tc1")!!.state)
    }

    @Test fun `any interaction restarts the countdown`() = runBlocking {
        val call = start(this, argsJson(pick))
        val rid = UserAskCenter.ask("tc1")!!.requestId
        now += 4 * 60_000L
        UserAskCenter.updateAnswer(rid, 0, UserAskAnswer(selected = listOf(1)))
        now += 2 * 60_000L
        UserAskCenter.tick(now)
        assertEquals("6 min after start, 2 after the tap: still waiting", UserAskState.PENDING, UserAskCenter.asks.value[rid]!!.state)
        UserAskCenter.dismiss(rid, UserAskDismissReason.USER_CLOSED_CARD)
        withTimeout(2_000) { call.await() }
        Unit
    }

    @Test fun `zero minutes waits indefinitely`() = runBlocking {
        val call = start(this, argsJson(pick), minutes = 0)
        now += 3 * 24 * 3600_000L
        UserAskCenter.tick(now)
        assertEquals(UserAskState.PENDING, UserAskCenter.ask("tc1")!!.state)
        UserAskCenter.dismissPending("sess-1", UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE)
        assertEquals(listOf(UserAskResultStatus.DISMISSED), UserAskResolver.statuses(withTimeout(2_000) { call.await() }.output))
    }

    @Test fun `Stop cancels the waiting call and the ask resolves as stopped`() = runBlocking {
        val call = start(this, argsJson(pick))
        val rid = UserAskCenter.ask("tc1")!!.requestId
        call.cancel()
        yield()
        val a = UserAskCenter.asks.value[rid]!!
        assertEquals(UserAskState.CANCELLED, a.state)
        assertEquals(UserAskDismissReason.STOPPED, a.dismissReason)
    }

    @Test fun `a new user message dismisses the question and the call returns`() = runBlocking {
        val call = start(this, argsJson(pick))
        UserAskCenter.dismissPending("sess-1", UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE)
        val out = withTimeout(2_000) { call.await() }.output
        assertEquals(listOf(UserAskResultStatus.DISMISSED), UserAskResolver.statuses(out))
        val a = UserAskCenter.ask("tc1")!!
        assertEquals(UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE, a.dismissReason)
        // First one wins: a late answer changes nothing.
        UserAskCenter.submitQuestion(a.requestId, 0)
        assertEquals(UserAskState.DISMISSED, UserAskCenter.ask("tc1")!!.state)
    }

    @Test fun `a bad call is an error for the model, and no session means unavailable`() = runBlocking {
        val bad = UserAskToolRunner.execute(argsJson(mapOf("type" to "options")), "tcX", "s", null, 5)
        assertFalse(bad.success)
        assertTrue(bad.output.startsWith("Error: questions[0].question is required"))
        assertTrue(UserAskCenter.asks.value.isEmpty())
        val noSession = UserAskToolRunner.execute(argsJson(pick), "tcY", null, null, 5)
        assertEquals(listOf(UserAskResultStatus.UNAVAILABLE), UserAskResolver.statuses(noSession.output))
    }

    @Test fun `the change hook sees every step, persistence-ready`() = runBlocking {
        val seen = mutableListOf<UserAskState>()
        UserAskCenter.onChanged = { seen += it.state }
        val call = start(this, argsJson(pick))
        UserAskCenter.submitQuestion(UserAskCenter.ask("tc1")!!.requestId, 0)
        withTimeout(2_000) { call.await() }
        assertEquals(UserAskState.PENDING, seen.first())
        assertEquals(UserAskState.ANSWERED, seen.last())
    }

    // ── Source guards ───────────────────────────────────────────────────────

    @Test fun `logs never carry the user's answer text`() {
        for (f in listOf("agent/userask/UserAskCenter.kt", "agent/userask/UserAskToolRunner.kt")) {
            val src = ProductionSources.read(f)
            for (call in src.split("AppLogger.").drop(1).map { it.substringBefore(")\n") }) {
                for (bad in listOf("otherText", ".note", "answers", "resultJson}", "\$result\"")) {
                    assertFalse("$f logs $bad: $call", call.contains(bad))
                }
                assertFalse("$f logs question text: $call", Regex("""\.question(?![a-zA-Z])""").containsMatchIn(call))
            }
        }
    }

    @Test fun `the tool is offered only when exposed, last, never to a sub agent`() {
        assertFalse(AgentTools.makeAgentTools().any { it.name == "user_ask" })
        assertEquals("user_ask", AgentTools.makeAgentTools(userAskExposed = true).last().name)
        assertFalse(AgentTools.makeAgentTools(userAskExposed = true, isHelper = true).any { it.name == "user_ask" })
        assertEquals(com.openminis.app.tools.AgentToolSwitch.USER_ASK, com.openminis.app.tools.AgentToolSwitch.governing("user_ask"))
        assertEquals("agent.tools.userAsk.enabled", com.openminis.app.tools.AgentToolSwitch.USER_ASK.key)
    }

    @Test fun `the view model wires exposure, dispatch, prompt, Stop and supersede`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("userAskExposed = userAskExposedThisRun,"))
        // Exposure decided right before every run's system prompt.
        val builds = Regex("""val baseSystemPrompt = buildSystemPrompt\(\)""").findAll(vm).count()
        assertEquals(builds, Regex("""decideUserAskExposureForRun\([^)]*\)\s*// \[T-port-user-ask\]\n\s*val baseSystemPrompt = buildSystemPrompt\(\)""").findAll(vm).count())
        assertTrue(vm.contains("decideUserAskExposureForRun(interactive = !headless, userAskAnswer = userAskAnswer)"))
        // The prompt line exists only when exposed.
        val prompt = vm.substringAfter("val userAskPromptLine = when {").substringBefore("}")
        assertTrue(prompt.contains("!userAskExposedThisRun -> \"\""))
        assertTrue(vm.contains("\${browserPromptBullet}\${userAskPromptLine}"))
        // Dispatcher.
        // Dispatcher: refuses when not exposed ([T-user-ask-exposure-guard]), else runs the card.
        // [T-user-ask-deferred] ...a deferred run gets the immediate call, any other the waiting one.
        val dispatch = vm.substringAfter("com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME -> if (!userAskExposedThisRun) {").take(2000)
        assertTrue(dispatch.contains("} else if (userAskDeferredThisRun) {"))
        assertTrue(dispatch.contains("} else\n                com.openminis.app.agent.userask.UserAskToolRunner.execute("))
        // Stop and a new user message.
        val cancel = vm.substringAfter("    fun cancelStream(keepBackground: Boolean = false) {").substringBefore("\n    }\n")
        assertTrue(cancel.contains("UserAskCenter.dismissPending(\n            activeSessionId, com.openminis.app.agent.userask.UserAskDismissReason.STOPPED,"))
        val enqueue = vm.substringAfter("    fun enqueuePrompt(").substringBefore("\n    }\n")
        assertTrue(enqueue.contains("if (ownsComposer) {\n            com.openminis.app.agent.userask.UserAskCenter.dismissPending("))
        assertTrue(enqueue.contains("ownsComposer: Boolean = origin == QueuedPromptOrigin.USER,"))
        assertTrue(enqueue.contains("UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE"))
    }

    @Test fun `the chat list routes user_ask blocks to the card`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("is FlatChatItem.AssistantToolUse -> if (item.block.toolName == com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME) {"))
        assertTrue(screen.contains("UserAskBlock(item.block) {"))
        val card = ProductionSources.read("ui/chat/UserAskCard.kt")
        assertFalse("no Always allow", Regex("""R\.string\.\w*always""", RegexOption.IGNORE_CASE).containsMatchIn(card) || card.contains("approveAlways"))
        assertTrue(card.contains("R.string.user_ask_allow") && !card.contains("allow_once"))
    }
}
