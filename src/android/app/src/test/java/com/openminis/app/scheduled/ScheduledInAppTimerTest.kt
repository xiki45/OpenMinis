package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [FIX-261025-19] The in-app timer and the alarm race for the same run:
 * whichever comes first runs it, the other finds it taken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScheduledInAppTimerTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var manager: ScheduledTaskManager

    @Before
    fun setUp() {
        manager = ScheduledTaskManager(ctx)
        manager.list().forEach { manager.store().delete(it.id) }
    }

    private fun after(id: String, delaySec: Long, anchor: Long) = ScheduledTask(
        id = id, label = "after", timeOfDayHour = 9, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE,
        prompt = "p", triggerKind = ScheduledTriggerKind.AFTER, delaySec = delaySec, anchorMs = anchor,
    )

    private fun daily(id: String) = ScheduledTask(
        id = id, label = "daily", timeOfDayHour = 9, timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY, prompt = "p",
    )

    /** What the timer does at the due moment: claim the run it waited for, just before its slot. */
    private fun timerClaim(due: ScheduledInAppTimer.Due) =
        manager.claimRunNow(due.task.id, due.at - 1, expectedRunKey = due.runKey)

    @Test
    fun `the soonest timed run is picked - disabled, on-completion and tried runs are not`() {
        val now = System.currentTimeMillis()
        val soon = after("soon", 60, now)
        val later = after("later", 600, now)
        val off = after("off", 10, now).copy(enabled = false)
        val chained = after("chained", 10, now).copy(triggerKind = ScheduledTriggerKind.ON_COMPLETION, onCompletionOf = "x")
        val tasks = listOf(later, off, chained, soon)

        val first = ScheduledInAppTimer.nextDue(tasks, now, emptySet())!!
        assertEquals("soon", first.task.id)
        assertEquals(now + 60_000, first.at)
        assertEquals("later", ScheduledInAppTimer.nextDue(tasks, now, setOf(first.tryKey))!!.task.id)
    }

    @Test
    fun `timer first - the alarm arriving after it does not run the task again`() {
        val now = System.currentTimeMillis()
        manager.store().upsert(after("t", 60, now - 120_000))
        val due = ScheduledInAppTimer.nextDue(manager.list(), now, emptySet())!!

        assertNotNull(timerClaim(due))
        assertFalse(manager.rescheduleNext("t", due.at))
        assertEquals(1, manager.get("t")!!.triggeredCount)
    }

    @Test
    fun `alarm first - the timer finds the run taken`() {
        val now = System.currentTimeMillis()
        manager.store().upsert(after("a", 60, now - 120_000))
        val due = ScheduledInAppTimer.nextDue(manager.list(), now, emptySet())!!

        assertTrue(manager.rescheduleNext("a", due.at))
        assertNull(timerClaim(due))
        assertEquals(1, manager.get("a")!!.triggeredCount)
    }

    @Test
    fun `daily slot - timer first, the alarm for that slot is turned away`() {
        manager.store().upsert(daily("d"))
        val due = ScheduledInAppTimer.nextDue(manager.list(), System.currentTimeMillis(), emptySet())!!

        val taken = timerClaim(due)!!
        assertEquals(due.at, taken.runNowSlotMs)
        assertTrue(taken.enabled)
        assertTrue(ScheduledTaskManager.calendarSlotTakenByRunNow(taken, due.at + 120_000))
        // The schedule carries on at the next day's slot.
        assertTrue(taken.nextTriggerMs(due.at - 1)!! > due.at)
    }

    @Test
    fun `daily slot - alarm first records the slot, so the timer does not run it again`() {
        manager.store().upsert(daily("e"))
        val due = ScheduledInAppTimer.nextDue(manager.list(), System.currentTimeMillis(), emptySet())!!

        assertTrue(manager.rescheduleNext("e", due.at))
        assertEquals(due.at, manager.get("e")!!.runNowSlotMs)
        assertNull(timerClaim(due))
    }

    @Test
    fun `an alarm registered before this fix carries no slot and fires as before`() {
        manager.store().upsert(daily("f"))
        assertTrue(manager.rescheduleNext("f"))
        assertNull(manager.get("f")!!.runNowSlotMs)
    }
}
