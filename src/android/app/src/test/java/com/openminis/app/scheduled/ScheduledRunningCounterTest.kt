package com.openminis.app.scheduled

import com.openminis.app.ProductionSources
import com.openminis.app.service.withRunCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-port-sched-running-counter] While a scheduled fire runs, the ongoing
 * notification's running status carries the run counter - iOS "Working… ·
 * 2/30" (ScheduledBannerModel.runCounter with running = true).
 */
class ScheduledRunningCounterTest {

    private val chat = "chat-1"
    private val t0 = 1_000_000_000L

    private fun interval(maxFires: Int?) = ScheduledTask(
        id = "task", label = "tick", timeOfDayHour = 9, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE,
        prompt = "p", targetMode = ScheduledTargetMode.AppendToSession(chat), createdAt = t0,
        triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = 60, maxFires = maxFires, anchorMs = t0,
        creatorSessionId = chat,
    )

    @Test
    fun `count 3 - each running fire shows its own number, the last one too`() {
        var task = interval(3)
        for (n in 1..3) {
            // The alarm counts the fire (and disables the task at the last one)
            // before the run starts, exactly as rescheduleNext does.
            task = ScheduledTaskManager.afterAlarmFire(task, t0 + n * 60_000L)!!.first
            val firing = ScheduledWaitModel.firing(listOf(task), setOf(task.id))
            assertEquals("fire $n", ScheduledFiring(chat, "task", "$n/3"), firing)
        }
        assertEquals(false, task.enabled)
        // Not running: nothing to show.
        assertNull(ScheduledWaitModel.firing(listOf(task), emptySet()))
    }

    @Test
    fun `unbounded loop and repeating calendar show #n, one-shots nothing`() {
        var loop = interval(null)
        repeat(7) { loop = ScheduledTaskManager.afterAlarmFire(loop, t0 + (it + 1) * 60_000L)!!.first }
        assertEquals("#7", ScheduledWaitModel.firing(listOf(loop), setOf("task"))!!.runCounter)

        val daily = interval(null).copy(triggerKind = ScheduledTriggerKind.CALENDAR, repeatMode = ScheduledRepeatMode.DAILY, fireCount = 3)
        assertEquals("#4", ScheduledWaitModel.runningCounter(daily))

        val once = interval(null).copy(triggerKind = ScheduledTriggerKind.AFTER, delaySec = 60, triggeredCount = 1)
        assertNull(ScheduledWaitModel.firing(listOf(once), setOf("task")))
    }

    @Test
    fun `running and waiting counters agree across a gap`() {
        // After fire 1 the wait shows the NEXT run "2/3"; while fire 2 runs it shows "2/3".
        val afterFire1 = ScheduledTaskManager.afterAlarmFire(interval(3), t0 + 60_000)!!.first
        assertEquals("2/3", ScheduledWaitModel.runCounter(afterFire1))
        val runningFire2 = ScheduledTaskManager.afterAlarmFire(afterFire1, t0 + 120_000)!!.first
        assertEquals("2/3", ScheduledWaitModel.runningCounter(runningFire2))
    }

    @Test
    fun `only a fire in a chat it keeps active`() {
        val elsewhere = interval(3).copy(targetMode = ScheduledTargetMode.AppendToSession("chat-2"), triggeredCount = 1)
        val newChat = interval(3).copy(targetMode = ScheduledTargetMode.NewSession, triggeredCount = 1)
        assertNull(ScheduledWaitModel.firing(listOf(elsewhere, newChat), setOf("task")))
    }

    @Test
    fun `status format and wiring`() {
        assertEquals("Running shell · 2/30", withRunCounter("Running shell", "2/30"))
        assertEquals("Thinking", withRunCounter("Thinking", null))
        val svc = ProductionSources.read("service/AgentForegroundService.kt")
        assertTrue(svc.contains("?.takeIf { it.sessionId in SessionActivityTracker.activeSessions.value }"))
        assertEquals("all three running phases carry it", 3, Regex("withRunCounter\\(").findAll(svc).count())
        assertTrue(ProductionSources.read("MinisApp.kt").contains("ScheduledWaitModel.observeFiring(this@MinisApp).collect {"))
        val tracker = ProductionSources.read("service/SessionActivityTracker.kt")
        val setter = tracker.substringAfter("fun setScheduledFiring(").substringBefore("\n    }\n")
        assertTrue("refreshes, never starts", setter.contains("if (AgentForegroundService.isAlive && shouldRunService()) updateService()"))
    }
}
