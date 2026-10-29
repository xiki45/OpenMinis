package com.openminis.app.agent.userask

import com.openminis.app.ProductionSources
import com.openminis.app.agent.userask.UserAskNotificationPlan.Layout
import com.openminis.app.agent.userask.UserAskNotificationPlan.Outcome
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-user-ask-notify] The background notification for a user_ask
 * question: which controls each question shape gets (Android shows at most
 * three actions), the one-notification-per-question rule, and answers from the
 * notification racing the card, the timeout and Stop.
 */
class UserAskNotificationTest {

    private var now = 1_000_000_000L

    @Before fun setUp() {
        UserAskCenter.resetForTest()
        UserAskCenter.clock = { now }
    }

    @After fun tearDown() = UserAskCenter.resetForTest()

    private fun opts(n: Int) = (1..n).map { UserAskOption("O$it") }
    private fun pick(n: Int, multi: Boolean = false) =
        UserAskQuestion(UserAskQuestionType.OPTIONS, "Pick $n", options = opts(n), multiSelect = multi)
    private val open = UserAskQuestion(UserAskQuestionType.OPTIONS, "Anything?")
    private fun confirm(risk: UserAskRisk = UserAskRisk.MEDIUM) =
        UserAskQuestion(UserAskQuestionType.CONFIRM, "rm -rf build/", impact = "deletes", risk = risk)

    private fun ask(vararg qs: UserAskQuestion, rid: String = "req-1", minutes: Int = 5) =
        PendingAsk.create(rid, "sess-1", "tc-$rid", qs.toList(), now, minutes)

    // ── Plan: controls per question shape ──────────────────────────────────

    @Test fun `two options get both buttons and an Other reply`() {
        val c = UserAskNotificationPlan.content(ask(pick(2)))!!
        assertEquals(Layout.OPTIONS, c.layout)
        assertEquals(listOf(0, 1), c.optionButtons)
        assertTrue(c.textReply)
        assertTrue(c.optionButtons.size + 1 <= UserAskNotificationPlan.MAX_ACTIONS)
    }

    @Test fun `three options fill the three actions with no Other`() {
        val c = UserAskNotificationPlan.content(ask(pick(3)))!!
        assertEquals(Layout.OPTIONS, c.layout)
        assertEquals(listOf(0, 1, 2), c.optionButtons)
        assertFalse(c.textReply)
    }

    @Test fun `four options do not fit and open the app - never a subset`() {
        val c = UserAskNotificationPlan.content(ask(pick(4)))!!
        assertEquals(Layout.OPEN_ONLY, c.layout)
        assertTrue(c.optionButtons.isEmpty())
    }

    @Test fun `multi-select opens the app`() {
        assertEquals(Layout.OPEN_ONLY, UserAskNotificationPlan.content(ask(pick(2, multi = true)))!!.layout)
    }

    @Test fun `an open-ended question gets a text reply only`() {
        val c = UserAskNotificationPlan.content(ask(open))!!
        assertEquals(Layout.REPLY_ONLY, c.layout)
        assertTrue(c.textReply)
        assertTrue(c.optionButtons.isEmpty())
    }

    @Test fun `a confirm gets Cancel and Allow, high risk flagged`() {
        val c = UserAskNotificationPlan.content(ask(confirm(UserAskRisk.HIGH)))!!
        assertEquals(Layout.CONFIRM, c.layout)
        assertTrue(c.isApproval)
        assertTrue(c.highRisk)
        assertFalse(UserAskNotificationPlan.content(ask(confirm()))!!.highRisk)
    }

    @Test fun `body numbers the question of a multi-question ask`() {
        assertEquals("Pick 2", UserAskNotificationPlan.content(ask(pick(2)))!!.body)
        assertEquals("1/2 · Pick 2", UserAskNotificationPlan.content(ask(pick(2), confirm()))!!.body)
    }

    @Test fun `one tag per question, and every tag is cleared`() {
        assertEquals("user-ask-r-q1", UserAskNotificationPlan.tag("r", 1))
        assertEquals(listOf("user-ask-r-q0", "user-ask-r-q1", "user-ask-r-q2"), UserAskNotificationPlan.allTags("r", 3))
    }

    // ── One notification per question ──────────────────────────────────────

    @Test fun `posts only while away, enabled and pending`() {
        val a = ask(pick(2))
        assertEquals(0, UserAskNotificationPlan.indexToPost(a, appInForeground = false, enabled = true, nowMs = now))
        assertNull(UserAskNotificationPlan.indexToPost(a, appInForeground = true, enabled = true, nowMs = now))
        assertNull(UserAskNotificationPlan.indexToPost(a, appInForeground = false, enabled = false, nowMs = now))
        assertNull("expired", UserAskNotificationPlan.indexToPost(a, false, true, now + 5 * 60_000L))
        assertNull("resolved", UserAskNotificationPlan.indexToPost(a.resolveTimedOut()!!, false, true, now))
    }

    @Test fun `a question alerts once - going to the background again does not re-post`() {
        val a = ask(pick(2)).copy(notifiedIndex = 0)
        assertNull(UserAskNotificationPlan.indexToPost(a, appInForeground = false, enabled = true, nowMs = now))
    }

    @Test fun `the next question of a multi-question ask posts again, quietly`() {
        val first = ask(pick(2), confirm())
        assertTrue(UserAskNotificationPlan.alertsWithSound(first))
        val (next, o) = UserAskNotificationPlan.apply(first.copy(notifiedIndex = 0), 0, "opt:1", null, now)
        assertEquals(Outcome.ADVANCED, o)
        assertEquals(1, UserAskNotificationPlan.indexToPost(next, false, true, now))
        assertFalse(UserAskNotificationPlan.alertsWithSound(next))
    }

    @Test fun `the center records the notified question`() {
        UserAskCenter.begin(ask(pick(2)), ownedByToolCall = false)
        UserAskCenter.markNotified("req-1", 0)
        assertEquals(0, UserAskCenter.asks.value["req-1"]!!.notifiedIndex)
        val json = UserAskCodec.toJson(UserAskCenter.asks.value["req-1"]!!)
        assertEquals("survives a kill", 0, UserAskCodec.fromJson(json)!!.notifiedIndex)
        assertNull("older records decode", UserAskCodec.fromJson(JSONObject(json.toString()).apply { remove("notifiedIndex") })!!.notifiedIndex)
    }

    // ── Applying an answer ──────────────────────────────────────────────────

    @Test fun `an option button answers by index`() {
        val (a, o) = UserAskNotificationPlan.apply(ask(pick(3)), 0, "opt:2", null, now)
        assertEquals(Outcome.COMPLETE, o)
        assertEquals(listOf(2), a.answers[0].selected)
    }

    @Test fun `the Other reply answers with the typed text, replacing the preselected option`() {
        val base = PendingAsk.preselectRecommended(ask(pick(2)), 0)
        val (a, o) = UserAskNotificationPlan.apply(base, 0, UserAskNotificationPlan.ACTION_OTHER, "  my own  ", now)
        assertEquals(Outcome.COMPLETE, o)
        assertEquals("my own", a.answers[0].otherText)
        assertTrue(a.answers[0].selected.isEmpty())
    }

    @Test fun `an empty reply or a foreign action is rejected`() {
        assertEquals(Outcome.REJECTED, UserAskNotificationPlan.apply(ask(pick(2)), 0, "other", "  ", now).second)
        assertEquals(Outcome.REJECTED, UserAskNotificationPlan.apply(ask(pick(2)), 0, "opt:7", null, now).second)
        assertEquals(Outcome.REJECTED, UserAskNotificationPlan.apply(ask(pick(2)), 0, "confirm.allow", null, now).second)
        assertEquals(Outcome.REJECTED, UserAskNotificationPlan.apply(ask(pick(2, multi = true)), 0, "opt:0", null, now).second)
        assertEquals(Outcome.REJECTED, UserAskNotificationPlan.apply(ask(confirm()), 0, "opt:0", null, now).second)
    }

    @Test fun `Allow and Cancel settle a confirm`() {
        assertEquals(UserAskConfirmChoice.APPROVE, UserAskNotificationPlan.apply(ask(confirm()), 0, "confirm.allow", null, now).first.answers[0].confirmChoice)
        assertEquals(UserAskConfirmChoice.DENY, UserAskNotificationPlan.apply(ask(confirm()), 0, "confirm.cancel", null, now).first.answers[0].confirmChoice)
    }

    @Test fun `an answer for a question that is no longer open is stale`() {
        val a = ask(pick(2), confirm())
        assertEquals(Outcome.STALE, UserAskNotificationPlan.apply(a, 1, "confirm.allow", null, now).second)
        assertEquals(Outcome.STALE, UserAskNotificationPlan.apply(a, 0, "opt:0", null, now + 5 * 60_000L).second)
    }

    // ── Races through the center ───────────────────────────────────────────

    private fun start(scope: kotlinx.coroutines.CoroutineScope, args: String) =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            UserAskToolRunner.execute(args, "tc1", "sess-1", "Title", 5, nowMs = now, requestId = "req-tc1")
        }

    private val twoOptions = """{"questions":[{"type":"options","question":"Which DB?","options":[{"label":"SQLite"},{"label":"Postgres"}]}]}"""

    @Test fun `a notification answer resumes the waiting tool call with source notification`() = runBlocking {
        val call = start(this, twoOptions)
        assertEquals(Outcome.COMPLETE, UserAskCenter.answerFromNotification("req-tc1", 0, "opt:1", null))
        val r = withTimeout(2_000) { call.await() }
        assertEquals("""{"results":[{"answer":"Postgres","i":0,"status":"answered"}]}""", r.output)
        assertEquals(UserAskAnswerSource.NOTIFICATION, UserAskCenter.asks.value["req-tc1"]!!.source)
    }

    @Test fun `the card answered first - the notification answer is ignored`() = runBlocking {
        val call = start(this, twoOptions)
        UserAskCenter.submitQuestion("req-tc1", 0)
        assertEquals(Outcome.STALE, UserAskCenter.answerFromNotification("req-tc1", 0, "opt:1", null))
        assertTrue(withTimeout(2_000) { call.await() }.output.contains("SQLite"))
        assertEquals(UserAskAnswerSource.APP, UserAskCenter.asks.value["req-tc1"]!!.source)
    }

    @Test fun `the notification answered first - a late card submit changes nothing`() = runBlocking {
        val call = start(this, twoOptions)
        UserAskCenter.answerFromNotification("req-tc1", 0, "opt:1", null)
        UserAskCenter.submitQuestion("req-tc1", 0)
        UserAskCenter.dismiss("req-tc1", UserAskDismissReason.STOPPED)
        assertTrue(withTimeout(2_000) { call.await() }.output.contains("Postgres"))
        assertEquals(UserAskState.ANSWERED, UserAskCenter.asks.value["req-tc1"]!!.state)
    }

    @Test fun `after a timeout or Stop the notification answer is stale`() = runBlocking {
        val call = start(this, twoOptions)
        now += 5 * 60_000L
        UserAskCenter.tick(now)
        assertEquals(Outcome.STALE, UserAskCenter.answerFromNotification("req-tc1", 0, "opt:1", null))
        assertEquals(UserAskState.TIMED_OUT, UserAskCenter.asks.value["req-tc1"]!!.state)
        withTimeout(2_000) { call.await() }

        UserAskCenter.resetForTest()
        UserAskCenter.clock = { now }
        val call2 = async(start = CoroutineStart.UNDISPATCHED) {
            UserAskToolRunner.execute(twoOptions, "tc2", "sess-1", "Title", 5, nowMs = now, requestId = "req-tc2")
        }
        UserAskCenter.dismissPending("sess-1", UserAskDismissReason.STOPPED)
        assertEquals(Outcome.STALE, UserAskCenter.answerFromNotification("req-tc2", 0, "opt:0", null))
        assertEquals(UserAskState.CANCELLED, UserAskCenter.asks.value["req-tc2"]!!.state)
        withTimeout(2_000) { call2.await() }
        Unit
    }

    @Test fun `an unknown ask is reported, not invented`() {
        assertEquals(Outcome.NOT_FOUND, UserAskCenter.answerFromNotification("nope", 0, "opt:0", null))
    }

    @Test fun `a direct reply is ended by an update before its notification is cancelled`() {
        // Android 15+ ignores cancelling a notification answered through
        // RemoteInput (FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY) - seen on the Pixel 6.
        val recv = ProductionSources.read("notification/UserAskNotificationReceiver.kt")
        assertTrue(recv.contains("if (action == UserAskNotificationPlan.ACTION_OTHER) {"))
        assertTrue(recv.contains("UserAskNotifier.endReply(UserAskNotificationPlan.tag(requestId, index))"))
        val end = ProductionSources.read("notification/UserAskNotifier.kt").substringAfter("fun endReply(").substringBefore("\n    }\n")
        assertTrue("update first, then cancel", end.indexOf("nm.notify(") in 0 until end.indexOf("nm.cancel("))
    }

    @Test fun `the answer text is never logged`() {
        val src = ProductionSources.read("agent/userask/UserAskCenter.kt")
        val fn = src.substringAfter("fun answerFromNotification(").substringBefore("// ── Dismissal")
        assertFalse(Regex("""\$\{?text\b(?!\?\.length)""").containsMatchIn(fn))
    }
}
