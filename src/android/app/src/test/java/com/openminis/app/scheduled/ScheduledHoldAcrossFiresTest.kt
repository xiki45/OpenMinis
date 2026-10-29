package com.openminis.app.scheduled

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-port-sched-wait-hold-gap] Replays a scheduled task's fires exactly as the
 * app performs them, and checks that the chat stays held - and the ongoing
 * notification keeps a wait to show - through every gap until the last fire's
 * run is done.
 *
 * Per fire, in the app's order (ScheduledTaskAlarmReceiver, ScheduledAgentRunner):
 *  1. the receiver takes a run hold (beginRun) BEFORE touching the row;
 *  2. rescheduleNext writes ScheduledTaskManager.afterAlarmFire (count, new
 *     anchor; the last fire disables the task);
 *  3. ScheduledAgentRunner.run takes its own run hold;
 *  4. the receiver's handoff returns and releases its hold;
 *  5. the fire's agent loop ends and the runner releases its hold.
 * After every step the held set (ScheduledSessionHold.heldNow, what observe()
 * emits) is checked.
 */
class ScheduledHoldAcrossFiresTest {

    private val chat = "chat-1"
    private val t0 = 1_000_000_000L

    private fun interval(maxFires: Int?) = ScheduledTask(
        id = "task", label = "tick", timeOfDayHour = 9, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE,
        prompt = "Say: tick", targetMode = ScheduledTargetMode.AppendToSession(chat), createdAt = t0,
        triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = 60, maxFires = maxFires, anchorMs = t0,
        creatorSessionId = chat,
    )

    /** One run hold count per holder, like ScheduledSessionHold._runningRuns. */
    private class Runs {
        private val counts = HashMap<String, Int>()
        fun begin(id: String) { counts[id] = (counts[id] ?: 0) + 1 }
        fun end(id: String) { val n = (counts[id] ?: 0) - 1; if (n > 0) counts[id] = n else counts.remove(id) }
        val ids: Set<String> get() = counts.keys
    }

    private fun held(task: ScheduledTask, runs: Runs, now: Long) =
        ScheduledSessionHold.heldNow(listOf(task), runs.ids, emptyMap(), now)

    /**
     * Fire [task] at [now]; [check] sees the held set after each step.
     * Returns the row after the fire.
     */
    private fun fire(task: ScheduledTask, runs: Runs, now: Long, check: (String, Set<String>) -> Unit): ScheduledTask {
        runs.begin(task.id); check("receiver hold", held(task, runs, now))
        assertTrue("alarm must be a due fire", ScheduledTaskManager.relativeAlarmDue(task, now))
        val after = ScheduledTaskManager.afterAlarmFire(task, now)!!.first
        check("row advanced", held(after, runs, now))
        runs.begin(after.id); check("runner hold", held(after, runs, now))
        runs.end(after.id); check("handoff returned", held(after, runs, now))
        // The fire's chat turn runs for a while.
        check("turn running", held(after, runs, now + 3_000))
        runs.end(after.id)
        return after
    }

    @Test
    fun `count 3 - held through fire 1, the gap, fire 2, the gap and fire 3, released after the last run`() {
        val runs = Runs()
        var task = interval(maxFires = 3)
        assertEquals(setOf(chat), held(task, runs, t0))
        assertEquals("1/3", ScheduledWaitModel.runCounter(task))
        val expectedNext = listOf("2/3", "3/3")
        for (n in 1..3) {
            val now = t0 + n * 60_000L
            task = fire(task, runs, now) { step, set -> assertEquals("fire $n: $step", setOf(chat), set) }
            val gapNow = now + 30_000
            if (n < 3) {
                assertEquals("gap after fire $n stays held", setOf(chat), held(task, runs, gapNow))
                assertTrue(task.enabled)
                val wait = ScheduledWaitModel.pick(listOf(task), gapNow) { false }!!
                assertEquals(now + 60_000, wait.nextRunMs)
                assertEquals(expectedNext[n - 1], wait.runCounter)
            } else {
                assertEquals("released once the last run is done", emptySet<String>(), held(task, runs, gapNow))
                assertEquals(false, task.enabled)
                assertEquals(null, ScheduledWaitModel.pick(listOf(task), gapNow) { false })
            }
        }
    }

    @Test
    fun `without the receiver's early hold the last fire would flash the chat idle`() {
        // Documents why the receiver holds before rescheduleNext: the last
        // fire's "disabled" row with no run hold yet holds nothing.
        val last = ScheduledTaskManager.afterAlarmFire(interval(3).copy(triggeredCount = 2), t0 + 180_000)!!.first
        assertEquals(emptySet<String>(), held(last, Runs(), t0 + 180_000))
        val recv = ProductionSources.read("scheduled/ScheduledTaskAlarmReceiver.kt")
        assertTrue(
            "beginRun must come before rescheduleNext",
            recv.indexOf("ScheduledSessionHold.beginRun(taskId)") in 0 until recv.indexOf("manager.rescheduleNext(taskId, slot)"),
        )
        assertTrue(recv.contains("ScheduledSessionHold.endRun(taskId)"))
    }

    @Test
    fun `an unbounded loop stays held across every fire`() {
        val runs = Runs()
        var task = interval(maxFires = null)
        for (n in 1..6) {
            val now = t0 + n * 60_000L
            task = fire(task, runs, now) { step, set -> assertEquals("fire $n: $step", setOf(chat), set) }
            assertEquals("gap after fire $n", setOf(chat), held(task, runs, now + 30_000))
            assertEquals("#${n + 1}", ScheduledWaitModel.pick(listOf(task), now + 30_000) { false }!!.runCounter)
        }
    }

    @Test
    fun `a one-shot is held until its single run is done`() {
        val runs = Runs()
        var task = interval(null).copy(triggerKind = ScheduledTriggerKind.AFTER, delaySec = 60, intervalSec = null)
        assertEquals(setOf(chat), held(task, runs, t0 + 10_000))
        assertEquals(null, ScheduledWaitModel.pick(listOf(task), t0) { false }!!.runCounter)
        task = fire(task, runs, t0 + 60_000) { step, set -> assertEquals("one-shot: $step", setOf(chat), set) }
        assertEquals(emptySet<String>(), held(task, runs, t0 + 90_000))
    }

    @Test
    fun `observe emits heldNow`() {
        val src = ProductionSources.read("scheduled/ScheduledSessionHold.kt")
        assertTrue(src.contains("heldNow(i.tasks, i.runningIds, i.jobs, now, i.leadMs)"))
    }
}
