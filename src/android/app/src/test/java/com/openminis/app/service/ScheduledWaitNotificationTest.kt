package com.openminis.app.service

import com.openminis.app.ProductionSources
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTriggerKind
import com.openminis.app.scheduled.ScheduledWaitModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-port-sched-wait-notification] Between the fires of a scheduled task that
 * keeps its chat active, the ongoing notification is in a WAITING phase: it
 * counts down to the next run with the system chronometer, shows "Next run ·
 * 2/30", and keeps Stop (stopAllByUser), instead of resting on "Task
 * completed" with no Stop. Port of iOS fa7adaf9e's waiting card.
 */
class ScheduledWaitNotificationTest {

    // --- Phase ------------------------------------------------------------------

    @Test
    fun `waiting wins over the previous run's completed state`() {
        assertEquals(
            AgentPhase.WAITING,
            resolveAgentPhase(isCompleted = true, toolName = null, isThinking = false, hasActiveSessions = false, isScheduledWaiting = true),
        )
    }

    @Test
    fun `a running loop wins over waiting`() {
        assertEquals(
            AgentPhase.TOOL,
            resolveAgentPhase(isCompleted = false, toolName = "shell_execute", isThinking = false, hasActiveSessions = true, isScheduledWaiting = true),
        )
        assertEquals(
            AgentPhase.GENERATING,
            resolveAgentPhase(isCompleted = false, toolName = null, isThinking = false, hasActiveSessions = true, isScheduledWaiting = true),
        )
    }

    @Test
    fun `without a wait the old phases are unchanged`() {
        assertEquals(
            AgentPhase.COMPLETED,
            resolveAgentPhase(isCompleted = true, toolName = null, isThinking = false, hasActiveSessions = false),
        )
        assertEquals(
            AgentPhase.IDLE,
            resolveAgentPhase(isCompleted = false, toolName = null, isThinking = false, hasActiveSessions = false),
        )
    }

    @Test
    fun `waiting icon is the clock, or the hourglass with nothing to count down to`() {
        assertEquals(R.drawable.ic_notification_scheduled, notificationSmallIconFor(AgentPhase.WAITING, null))
        assertEquals(R.drawable.ic_notification_hourglass, notificationSmallIconFor(AgentPhase.WAITING, null, waitHasClock = false))
    }

    // --- Content ----------------------------------------------------------------

    @Test
    fun `status line is iOS's Next run with the counter`() {
        assertEquals("Next run · 2/30", waitStatusText("Next run", "2/30"))
        assertEquals("下次执行 · #7", waitStatusText("下次执行", "#7"))
        assertEquals("Next run", waitStatusText("Next run", null))
    }

    @Test
    fun `waiting counts down to the next run with the chronometer`() {
        val now = 1_000_000L
        val c = notificationClockFor(AgentPhase.WAITING, runStartWallMs = 1, finishedWallMs = 2, nextRunWallMs = now + 90_000, nowWallMs = now)
        assertEquals(NotificationClock(showWhen = true, whenMs = now + 90_000, usesChronometer = true, countDown = true), c)
    }

    @Test
    fun `no clock for an on-completion wait or a run already due - never a negative countdown`() {
        val now = 1_000_000L
        val none = NotificationClock(showWhen = false, whenMs = now, usesChronometer = false, countDown = false)
        assertEquals(none, notificationClockFor(AgentPhase.WAITING, 1, 2, nextRunWallMs = null, nowWallMs = now))
        assertEquals(none, notificationClockFor(AgentPhase.WAITING, 1, 2, nextRunWallMs = now, nowWallMs = now))
        assertEquals(none, notificationClockFor(AgentPhase.WAITING, 1, 2, nextRunWallMs = now - 5_000, nowWallMs = now))
    }

    @Test
    fun `running and completed clocks are as before`() {
        assertEquals(
            NotificationClock(showWhen = true, whenMs = 10, usesChronometer = true, countDown = false),
            notificationClockFor(AgentPhase.TOOL, runStartWallMs = 10, finishedWallMs = 20, nextRunWallMs = 99, nowWallMs = 30),
        )
        assertEquals(
            NotificationClock(showWhen = true, whenMs = 20, usesChronometer = false, countDown = false),
            notificationClockFor(AgentPhase.COMPLETED, runStartWallMs = 10, finishedWallMs = 20, nextRunWallMs = null, nowWallMs = 30),
        )
    }

    @Test
    fun `chip text is whole minutes rounded up and short`() {
        assertNull(waitChipText(null))
        assertNull(waitChipText(0))
        assertEquals("1m", waitChipText(1))
        assertEquals("1m", waitChipText(60_000))
        assertEquals("2m", waitChipText(60_001))
        assertEquals("59m", waitChipText(59 * 60_000L))
        assertEquals("1h0m", waitChipText(59 * 60_000L + 1))
        assertEquals("1h5m", waitChipText(65 * 60_000L))
        assertEquals("23h59m", waitChipText(24 * 3_600_000L - 60_000))
        assertEquals("2d3h", waitChipText((2 * 24 + 3) * 3_600_000L))
        assertTrue(waitChipText(99 * 24 * 3_600_000L)!!.length <= 7)
    }

    @Test
    fun `refresh lands just after each minute change with the chip, once at the end without`() {
        assertNull(waitRefreshDelayMs(null, promotedChip = true))
        assertNull(waitRefreshDelayMs(0, promotedChip = false))
        assertEquals(5_250L, waitRefreshDelayMs(125_000, promotedChip = true))
        assertEquals(60_250L, waitRefreshDelayMs(120_000, promotedChip = true))
        assertEquals(125_250L, waitRefreshDelayMs(125_000, promotedChip = false))
        // The chip text really changes at that moment.
        assertEquals("3m", waitChipText(125_000))
        assertEquals("2m", waitChipText(125_000 - 5_250))
    }

    // --- Which wait, and its counter ----------------------------------------------

    private val chat = "chat-1"

    private fun task(
        id: String,
        trigger: ScheduledTriggerKind = ScheduledTriggerKind.INTERVAL,
        label: String = "Check $id",
        target: ScheduledTargetMode = ScheduledTargetMode.AppendToSession(chat),
        creator: String? = chat,
        anchorMs: Long = 0,
        intervalSec: Long? = 60,
        maxFires: Int? = null,
        triggered: Int? = null,
        repeat: ScheduledRepeatMode = ScheduledRepeatMode.DAILY,
        fireCount: Int? = null,
        onCompletionOf: String? = null,
    ) = ScheduledTask(
        id = id, label = label, timeOfDayHour = 9, timeOfDayMinute = 0, repeatMode = repeat,
        prompt = "p", targetMode = target, createdAt = 0, triggerKind = trigger, anchorMs = anchorMs,
        intervalSec = intervalSec, maxFires = maxFires, triggeredCount = triggered, fireCount = fireCount,
        onCompletionOf = onCompletionOf, creatorSessionId = creator,
    )

    @Test
    fun `run counter matches iOS - this run of a counted loop, #n when unbounded, none for a one-shot`() {
        assertEquals("2/30", ScheduledWaitModel.runCounter(task("a", maxFires = 30, triggered = 1)))
        assertEquals("1/3", ScheduledWaitModel.runCounter(task("a", maxFires = 3, triggered = null)))
        assertEquals("3/3", ScheduledWaitModel.runCounter(task("a", maxFires = 3, triggered = 5)))
        assertEquals("#7", ScheduledWaitModel.runCounter(task("a", triggered = 6)))
        assertEquals("#4", ScheduledWaitModel.runCounter(task("a", trigger = ScheduledTriggerKind.CALENDAR, fireCount = 3)))
        assertNull(ScheduledWaitModel.runCounter(task("a", trigger = ScheduledTriggerKind.CALENDAR, repeat = ScheduledRepeatMode.ONCE)))
        assertNull(ScheduledWaitModel.runCounter(task("a", trigger = ScheduledTriggerKind.AFTER)))
        assertNull(ScheduledWaitModel.runCounter(task("a", trigger = ScheduledTriggerKind.ON_COMPLETION)))
    }

    @Test
    fun `the soonest held run is shown`() {
        val now = 10_000_000L
        val later = task("later", anchorMs = now, intervalSec = 600, maxFires = 5, triggered = 2)
        val sooner = task("sooner", anchorMs = now, intervalSec = 60, maxFires = 30, triggered = 1)
        val w = ScheduledWaitModel.pick(listOf(later, sooner), now) { false }!!
        assertEquals("sooner", w.taskId)
        assertEquals(chat, w.sessionId)
        assertEquals(now + 60_000, w.nextRunMs)
        assertEquals("2/30", w.runCounter)
        assertEquals("Check sooner", w.label)
    }

    @Test
    fun `tasks that keep no chat active are not shown`() {
        val now = 10_000_000L
        val newChat = task("n", target = ScheduledTargetMode.NewSession)
        val otherChat = task("o", target = ScheduledTargetMode.AppendToSession("chat-2"))
        val noCreator = task("c", creator = null)
        val done = task("d", maxFires = 3, triggered = 3)
        val disabled = task("x").copy(enabled = false)
        assertNull(ScheduledWaitModel.pick(listOf(newChat, otherChat, noCreator, done, disabled), now) { false })
        assertNull(ScheduledWaitModel.pick(emptyList(), now) { false })
    }

    @Test
    fun `an on-completion wait is shown only when nothing has a time`() {
        val now = 10_000_000L
        val onDone = task("w", trigger = ScheduledTriggerKind.ON_COMPLETION, onCompletionOf = "up")
        val w = ScheduledWaitModel.pick(listOf(onDone), now) { it == "up" }!!
        assertEquals("w", w.taskId)
        assertNull(w.nextRunMs)
        val timed = task("t", anchorMs = now, intervalSec = 120)
        assertEquals("t", ScheduledWaitModel.pick(listOf(onDone, timed), now) { it == "up" }!!.taskId)
        // Its upstream is gone: it waits for nothing and holds nothing.
        assertNull(ScheduledWaitModel.pick(listOf(onDone), now) { false })
    }

    // --- Wiring -------------------------------------------------------------------

    @Test
    fun `the service keeps Stop while waiting and counts down without per-second posts`() {
        val svc = ProductionSources.read("service/AgentForegroundService.kt")
        // Waiting is never "completed", and Stop is only dropped when completed.
        assertTrue(svc.contains("val isCompleted = scheduledWait == null &&"))
        assertEquals(2, Regex("""if \(!isCompleted\) \{\s+builder\.addAction""").findAll(svc).count())
        assertEquals(2, Regex("""\.setChronometerCountDown\(clock\.countDown\)""").findAll(svc).count())
        assertTrue(svc.contains("isScheduledWaiting = scheduledWait != null"))
        assertTrue(svc.contains("waitStatusText(getString(R.string.scheduled_detail_next_run), scheduledWait?.runCounter)"))
        assertTrue(svc.contains("waitRefreshDelayMs("))
        // The Stop action still turns the schedules off.
        assertTrue(svc.contains("SessionActivityTracker.stopAllByUser()"))
    }

    @Test
    fun `the wait is fed from the task store and leaves activeSessions alone`() {
        val app = ProductionSources.read("MinisApp.kt")
        assertTrue(app.contains("ScheduledWaitModel.observe(this@MinisApp).collect"))
        assertTrue(app.contains("SessionActivityTracker.setScheduledWait(wait?.copy(chatTitle = title))"))
        val tracker = ProductionSources.read("service/SessionActivityTracker.kt")
        val setter = tracker.substringAfter("fun setScheduledWait(").substringBefore("\n    }\n")
        assertFalse(setter.contains("_activeSessions"))
        assertFalse(setter.contains("publishProcessing"))
        // Refreshes a running service, never starts one from the background.
        assertTrue(setter.contains("if (AgentForegroundService.isAlive && shouldRunService()) updateService()"))
    }
}
