package com.openminis.app.service

import com.openminis.app.ProductionSources
import com.openminis.app.R
import com.openminis.app.agent.userask.PendingAsk
import com.openminis.app.agent.userask.UserAskQuestion
import com.openminis.app.agent.userask.UserAskQuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-user-ask-chip] While a user_ask question waits, the ongoing
 * notification / Android 16 chip is in an ASKING phase - its own icon and
 * text, counting down to the question's timeout - instead of "Running".
 */
class UserAskChipPhaseTest {

    private val now = 1_000_000_000L

    private fun phase(
        asking: Boolean,
        completed: Boolean = false,
        tool: String? = "user_ask",
        active: Boolean = true,
        waiting: Boolean = false,
    ) = resolveAgentPhase(
        isCompleted = completed, toolName = tool, isThinking = false,
        hasActiveSessions = active, isScheduledWaiting = waiting, isAwaitingAnswer = asking,
    )

    @Test fun `a waiting question wins over the user_ask tool row and other phases`() {
        assertEquals(AgentPhase.ASKING, phase(asking = true))
        assertEquals(AgentPhase.ASKING, phase(asking = true, tool = null))
        assertEquals(AgentPhase.ASKING, phase(asking = true, active = false, waiting = true))
        assertEquals(AgentPhase.ASKING, phase(asking = true, completed = true))
    }

    @Test fun `no question - the phases are unchanged`() {
        assertEquals(AgentPhase.TOOL, phase(asking = false))
        assertEquals(AgentPhase.GENERATING, phase(asking = false, tool = null))
        assertEquals(AgentPhase.WAITING, phase(asking = false, tool = null, active = false, waiting = true))
    }

    @Test fun `asking is never presence-only - it keeps the chip`() {
        assertFalse(isPresenceOnly(AgentPhase.ASKING, hasScheduledHold = false))
    }

    @Test fun `asking has its own icon`() {
        assertEquals(R.drawable.ic_notification_question, notificationSmallIconFor(AgentPhase.ASKING, "user_ask"))
    }

    @Test fun `the clock counts down to the question's timeout`() {
        val c = notificationClockFor(AgentPhase.ASKING, runStartWallMs = now - 60_000, finishedWallMs = now,
            nextRunWallMs = null, nowWallMs = now, askExpiresWallMs = now + 300_000)
        assertTrue(c.showWhen); assertTrue(c.usesChronometer); assertTrue(c.countDown)
        assertEquals(now + 300_000, c.whenMs)
    }

    @Test fun `no clock for a question with no deadline, or one already past`() {
        for (exp in listOf<Long?>(null, now - 1)) {
            val c = notificationClockFor(AgentPhase.ASKING, now - 60_000, now, null, now, askExpiresWallMs = exp)
            assertFalse(c.showWhen); assertFalse(c.usesChronometer)
        }
    }

    @Test fun `chip text is the time left`() {
        assertEquals("4:59", askChipText(299_000))
        assertEquals("0:00", askChipText(-5))
        assertEquals("2h0m", askChipText(2 * 3_600_000L))
        assertEquals("?", askChipText(null))
        assertTrue(askChipText(1440 * 60_000L).length <= 7)
    }

    private fun ask(rid: String, title: String?, minutes: Int, created: Long = now) = PendingAsk.create(
        rid, "s-$rid", "tc-$rid", listOf(UserAskQuestion(UserAskQuestionType.OPTIONS, "Q?")), created, minutes,
    ).copy(sessionTitle = title)

    @Test fun `the soonest deadline is shown, resolved asks are ignored`() {
        assertNull(askingStateFor(emptyList()))
        val soon = ask("a", "Chat A", 2)
        val later = ask("b", "Chat B", 10)
        val forever = ask("c", "Chat C", 0)
        assertEquals(AskingState("Chat A", now + 120_000), askingStateFor(listOf(forever, later, soon)))
        assertEquals(AskingState("Chat C", null), askingStateFor(listOf(forever)))
        assertNull(askingStateFor(listOf(soon.resolveTimedOut()!!)))
        assertNull("blank title falls back", askingStateFor(listOf(ask("d", " ", 5)))!!.sessionTitle)
    }

    @Test fun `the service re-posts on every asking transition and uses the phase everywhere`() {
        val src = ProductionSources.read("service/AgentForegroundService.kt")
        assertTrue(src.contains("startAskingObserver()"))
        val observer = src.substringAfter("private fun startAskingObserver()").substringBefore("private fun askingNow()")
        assertTrue(observer.contains("distinctUntilChanged()") && observer.contains("refreshOngoingNotification()"))
        assertEquals("buildNotification and presenceOnlyNow both pass the question",
            2, Regex("""isAwaitingAnswer = ask""").findAll(src).count())
    }
}
