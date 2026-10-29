package com.openminis.app.agent.userask

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-user-ask-deferred-ttl] / [T-user-ask-notify-once] iOS 07fbac2c1: an
 * unanswered deferred card closes after an hour instead of living forever
 * (restored on every launch and re-notified on every trip to the
 * background), and each question alerts once.
 */
class UserAskDeferredTtlTest {

    private val t0 = 1_000_000_000L
    private val min = 60_000L
    private val which = UserAskQuestion(UserAskQuestionType.OPTIONS, "Which?", options = listOf(UserAskOption("A"), UserAskOption("B")))
    private fun deferred(id: String = "r1") =
        PendingAsk.create(id, "s1", "tc1", listOf(which), t0, timeoutMinutes = 0).copy(deferred = true)

    @Before fun setUp() { UserAskCenter.resetForTest(); UserAskRecoveryCoordinator.resetForTest() }
    @After fun tearDown() { UserAskCenter.resetForTest(); UserAskRecoveryCoordinator.resetForTest() }

    @Test fun `no countdown, but it expires after its 1 h lifetime`() {
        val a = deferred()
        assertNull(a.expiresAtMs)
        assertFalse(a.isExpired(t0 + 59 * min))
        assertTrue(a.isExpired(t0 + 61 * min))
        assertEquals(60 * min, UserAskDeferred.LIFETIME_MS)
        // An interactive ask with no timeout still waits forever; an explicit deadline still wins.
        assertFalse(a.copy(deferred = false).isExpired(t0 + 10_000 * min))
        assertTrue(a.copy(expiresAtMs = t0 + min).isExpired(t0 + 2 * min))
        // A resolved one is never "expired".
        assertFalse(a.resolveDismissed(UserAskDismissReason.USER_CLOSED_CARD)!!.isExpired(t0 + 999 * min))
    }

    @Test fun `on expiry nothing is applied and the note names the lifetime`() {
        val timed = deferred().resolveTimedOut()!!
        assertEquals(UserAskState.TIMED_OUT, timed.state)
        assertEquals(listOf(UserAskResultStatus.TIMEOUT), timed.resultStatuses)
        assertTrue(timed.resultJson!!, timed.resultJson!!.contains("No answer within 60 min. Nothing was applied or approved."))
        assertFalse("a timed-out card sends no message", UserAskDeferred.sendsAnswer(timed))
    }

    @Test fun `recovery restores it within the hour and times it out after`() {
        val r = UserAskRecord(deferred(), "[]")
        assertEquals(UserAskRecoveryAction.AWAIT_USER, UserAskRecovery.action(r, t0 + 30 * min, toolResultInStore = true))
        assertEquals(UserAskRecoveryAction.TIME_OUT_THEN_DELIVER, UserAskRecovery.action(r, t0 + 24 * 60 * min, toolResultInStore = true))
        assertEquals(UserAskRecoveryAction.TIME_OUT_THEN_DELIVER, UserAskRecovery.action(r, t0 + 24 * 60 * min, toolResultInStore = false))
    }

    @Test fun `the ticker closes an open deferred card and hands the timeout on, without a message`() {
        var now = t0
        UserAskCenter.clock = { now }
        var resolved: PendingAsk? = null
        UserAskCenter.onDeferredResolved = { resolved = it }
        UserAskCenter.beginDeferred(deferred())
        UserAskCenter.markDelivered(listOf("tc1"))   // the loop stored the `deferred` result
        UserAskCenter.tick(t0 + 59 * min)
        assertNull(resolved)
        now = t0 + 61 * min
        UserAskCenter.tick(now)
        assertEquals(UserAskState.TIMED_OUT, resolved?.state)
        assertFalse(UserAskDeferred.sendsAnswer(resolved!!))
    }

    private class MemRecords : UserAskRecords {
        val map = LinkedHashMap<String, UserAskRecord>()
        override fun all(): Map<String, UserAskRecord> = synchronized(map) { map.toMap() }
        override fun save(r: UserAskRecord) { synchronized(map) { map[r.ask.requestId] = r } }
        override fun remove(requestId: String) { synchronized(map) { map.remove(requestId) } }
    }

    private class MemTranscript : UserAskTranscript {
        val rows = mutableListOf<Pair<String, String>>()
        override suspend fun sessionExists(sessionId: String) = true
        override suspend fun partsJsonRows(sessionId: String) = rows.map { it.second }
        override suspend fun append(sessionId: String, role: String, partsJson: String) { rows += role to partsJson }
        override suspend fun replaceToolResult(sessionId: String, toolUseId: String, output: String): Boolean {
            for (i in rows.indices) {
                val p = UserAskRecovery.replaceToolResultOutput(rows[i].second, toolUseId, output) ?: continue
                rows[i] = rows[i].first to p
                return true
            }
            return false
        }
    }

    @Test fun `a card left open for a day is closed on the next load, not restored again`() = runBlocking {
        val records = MemRecords()
        val transcript = MemTranscript()
        val delivered = mutableListOf<UserAskDeferredDelivery>()
        UserAskRecoveryCoordinator.install(records, transcript, { _, _ -> }, scanOthers = false) { delivered += it }
        UserAskCenter.onChanged = { records.onAskChanged(it) }
        val a = deferred()
        records.save(UserAskRecord(a, "[]"))
        transcript.rows += "user" to UserAskRecovery.resultRowPartsJson(a, UserAskDeferred.toolResult(1), listOf("tc1" to "user_ask"))
        UserAskRecoveryCoordinator.restoreForSession("s1", nowMs = t0 + 24 * 60 * min)
        assertNull("no card restored", UserAskCenter.ask("tc1")?.takeIf { it.state == UserAskState.PENDING })
        val out = JSONArray(transcript.rows.single().second).getJSONObject(0).getJSONObject("value").getString("output")
        assertTrue(out, out.contains("\"status\":\"timeout\""))
        assertNull(delivered.single().answerMessage)
        assertTrue(records.all().isEmpty())
    }

    @Test fun `each question alerts once, an expired card never`() {
        val a = deferred()
        assertEquals(0, UserAskNotificationPlan.indexToPost(a, appInForeground = false, enabled = true, nowMs = t0 + min))
        assertNull("already notified for this question",
            UserAskNotificationPlan.indexToPost(a.copy(notifiedIndex = 0), appInForeground = false, enabled = true, nowMs = t0 + 2 * min))
        assertNull("past its lifetime", UserAskNotificationPlan.indexToPost(a, appInForeground = false, enabled = true, nowMs = t0 + 61 * min))
        assertTrue("the first alert has sound", UserAskNotificationPlan.alertsWithSound(a))
    }
}
