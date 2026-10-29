package com.openminis.app.agent.userask

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-user-ask-deferred] The pure half of deferred mode (iOS 7a818b8c0):
 * the tool result, the answer message, recovery actions, the record format
 * and the exposure decision. Vectors are iOS UserAskCoreTests 6b.
 */
class UserAskDeferredCoreTest {

    private val which = UserAskQuestion(
        UserAskQuestionType.OPTIONS, "Which?", options = listOf(UserAskOption("A"), UserAskOption("B")),
    )
    private val t0 = 1_000_000L

    private fun deferredRecord() = UserAskRecord(
        ask = PendingAsk.create("rD", "s1", "tcD", listOf(which), t0, timeoutMinutes = 0).copy(deferred = true),
        assistantPartsJson = "[]",
    )

    private fun answered(r: UserAskRecord): UserAskRecord {
        val a = r.ask.setAnswer(UserAskAnswer(selected = listOf(1), note = "after lunch"), 0).copy(submitted = setOf(0))
        return r.copy(ask = a.resolveAnswered(UserAskAnswerSource.APP)!!)
    }

    @Test
    fun `the tool result has one deferred status per question and the iOS note`() {
        val o = JSONObject(UserAskDeferred.toolResult(2))
        val results = o.getJSONArray("results")
        assertEquals("0:deferred,1:deferred", (0 until results.length()).joinToString(",") {
            results.getJSONObject(it).let { e -> "${e.getInt("i")}:${e.getString("status")}" }
        })
        assertEquals("The card is shown; the answer will arrive as the user's next message. End your turn now.", o.getString("note"))
        assertEquals(UserAskPromptText.DEFERRED_NOTE, UserAskDeferred.NOTE)
        // Sorted keys like iOS JSONSerialization .sortedKeys.
        assertTrue(UserAskDeferred.toolResult(1).startsWith("{\"note\":"))
        assertEquals(1, JSONObject(UserAskDeferred.toolResult(0)).getJSONArray("results").length())
        // Deferred is explained only where it happens, never in the description.
        assertFalse(UserAskPromptText.TOOL_DESCRIPTION.contains("deferred"))
    }

    @Test
    fun `recovery decides a deferred ask on whether its answer was handed on`() {
        val pending = deferredRecord()
        assertEquals(UserAskRecoveryAction.AWAIT_USER, UserAskRecovery.action(pending, t0 + 60_000, toolResultInStore = true))
        val ans = answered(pending)
        assertEquals(UserAskRecoveryAction.DELIVER_DEFERRED, UserAskRecovery.action(ans, t0, toolResultInStore = true))
        val consumed = ans.copy(ask = ans.ask.copy(consumed = true))
        assertEquals(UserAskRecoveryAction.ALREADY_DELIVERED, UserAskRecovery.action(consumed, t0, toolResultInStore = true))
        // Killed before the batch (with its `deferred` result) was written: the ordinary rules.
        assertEquals(UserAskRecoveryAction.DELIVER, UserAskRecovery.action(ans, t0, toolResultInStore = false))
        assertEquals(UserAskRecoveryAction.AWAIT_USER, UserAskRecovery.action(pending, t0, toolResultInStore = false))
        // A non-deferred ask is unchanged: a result in the store means delivered.
        val plain = pending.copy(ask = pending.ask.copy(deferred = false))
        assertEquals(UserAskRecoveryAction.ALREADY_DELIVERED, UserAskRecovery.action(plain, t0, toolResultInStore = true))
    }

    @Test
    fun `only a real answer becomes the next message`() {
        val pending = deferredRecord()
        val ans = answered(pending)
        assertTrue(UserAskDeferred.sendsAnswer(ans.ask))
        assertFalse(UserAskDeferred.sendsAnswer(pending.ask))
        val replaced = pending.ask.resolveDismissed(UserAskDismissReason.REPLACED_BY_NEW_ASK)!!
        assertFalse(UserAskDeferred.sendsAnswer(replaced))
        assertEquals(UserAskDismissReason.REPLACED_BY_NEW_ASK, replaced.dismissReason)
        assertFalse(UserAskDeferred.sendsAnswer(pending.ask.resolveTimedOut()!!))
        // An interactive ask never sends a message: its waiting tool call takes the result.
        assertFalse(UserAskDeferred.sendsAnswer(ans.ask.copy(deferred = false)))
    }

    @Test
    fun `the answer message is what the user chose, then a note for the model`() {
        assertEquals(
            "1. Which? → B (note: after lunch)\n<system-reminder>The user's answers to your earlier question card.</system-reminder>",
            UserAskDeferred.answerMessage(answered(deferredRecord()).ask),
        )
        val delegated = deferredRecord().ask.setAnswer(UserAskAnswer(delegated = true), 0)
        assertEquals("1. Which? → you decide", UserAskRecovery.answerLines(delegated, withStatus = false).first())
        assertEquals("1. Which? → unanswered", UserAskRecovery.answerLines(delegated, withStatus = true).first())
    }

    @Test
    fun `records round-trip the flag and older records decode as not deferred`() {
        val r = deferredRecord()
        val back = UserAskRecord.fromJson(JSONObject(r.toJson().toString()))!!
        assertTrue(back.ask.deferred)
        val old = r.toJson().also { it.getJSONObject("ask").remove("deferred") }
        assertFalse(UserAskRecord.fromJson(JSONObject(old.toString()))!!.ask.deferred)
    }

    @Test
    fun `exposure - only the switch and sub agents remove the tool`() {
        fun d(enabled: Boolean = true, helper: Boolean = false, interactive: Boolean = true, fg: Boolean = true, answer: Boolean = false) =
            UserAskExposure.decision(enabled, helper, interactive, fg, answer).let { it.exposed to it.deferred }
        assertEquals(false to false, d(enabled = false))
        assertEquals(false to false, d(helper = true))
        assertEquals(false to false, d(helper = true, interactive = false))
        assertEquals(true to false, d())
        assertEquals("headless submit", true to true, d(interactive = false))
        assertEquals("started in the background", true to true, d(fg = false))
        assertEquals("the answer, app in front", true to false, d(interactive = false, answer = true))
        assertEquals("the answer, app away", true to true, d(interactive = false, fg = false, answer = true))
        assertEquals("setting off wins over everything", false to false, d(enabled = false, answer = true))
    }
}
