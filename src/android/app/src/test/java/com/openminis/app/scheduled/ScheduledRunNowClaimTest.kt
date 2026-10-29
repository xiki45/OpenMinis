package com.openminis.app.scheduled

import com.openminis.app.ProductionSources
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-sched-run-now] "Run now" on the timer pill and the alarm's own fire take
 * a task's pending run through one atomic claim, so a run executes once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScheduledRunNowClaimTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var manager: ScheduledTaskManager

    @Before
    fun setUp() {
        manager = ScheduledTaskManager(ctx)
        manager.list().forEach { manager.store().delete(it.id) }
    }

    private fun interval(id: String, ivSec: Long = 600, max: Int? = null, anchor: Long = System.currentTimeMillis(), count: Int? = null) =
        ScheduledTask(
            id = id, label = "loop", timeOfDayHour = 9, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE,
            prompt = "p", triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = ivSec, maxFires = max,
            anchorMs = anchor, triggeredCount = count,
        )

    private fun daily(id: String) = ScheduledTask(
        id = id, label = "daily", timeOfDayHour = 9, timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY, prompt = "p",
    )

    // (a)
    @Test
    fun `a pending run is taken once, counted once, and the next one is an interval later`() {
        manager.store().upsert(interval("a", max = 5))
        val now = System.currentTimeMillis()
        val taken = manager.claimRunNow("a", now)
        assertNotNull(taken)
        assertEquals(1, taken!!.triggeredCount)
        assertEquals(4, taken.remainingFires)
        assertEquals(now + 600_000, taken.nextTriggerMs(now))
        assertEquals(1, manager.get("a")!!.triggeredCount)
    }

    // (b)
    @Test
    fun `a run already running or being started is refused and nothing changes`() {
        manager.store().upsert(interval("b", max = 5))
        assertNull(manager.claimRunNow("b", isRunning = { it == "b" }))
        assertEquals(null, manager.get("b")!!.triggeredCount)
        // Nothing pending: disabled, exhausted, on-completion.
        manager.store().upsert(interval("b2", max = 2, count = 2))
        assertNull(manager.claimRunNow("b2"))
        manager.store().upsert(interval("b3").copy(enabled = false))
        assertNull(manager.claimRunNow("b3"))
        manager.store().upsert(interval("b4").copy(triggerKind = ScheduledTriggerKind.ON_COMPLETION, onCompletionOf = "x"))
        assertNull(manager.claimRunNow("b4"))
    }

    // (c)
    @Test
    fun `the alarm's fire and Run now racing for the same due run - exactly one wins, every time`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) { i ->
                val id = "c$i"
                // Due right now: the alarm for this slot is being delivered.
                manager.store().upsert(interval(id, ivSec = 60, max = 10, anchor = System.currentTimeMillis() - 60_000))
                // The run the menu showed when the user tapped.
                val shown = ScheduledTaskManager.pendingRunKey(manager.get(id)!!, System.currentTimeMillis())
                val wins = AtomicInteger()
                val go = CountDownLatch(1)
                val done = CountDownLatch(2)
                pool.execute { go.await(); if (manager.rescheduleNext(id)) wins.incrementAndGet(); done.countDown() }
                pool.execute { go.await(); if (manager.claimRunNow(id, expectedRunKey = shown) != null) wins.incrementAndGet(); done.countDown() }
                go.countDown()
                assertTrue(done.await(10, TimeUnit.SECONDS))
                assertEquals("round $i", 1, wins.get())
                assertEquals("count consumed once, round $i", 1, manager.get(id)!!.triggeredCount)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `after Run now took the run, its alarm arriving late does not run it again`() {
        manager.store().upsert(interval("late", ivSec = 60, max = 10, anchor = System.currentTimeMillis() - 30_000))
        assertNotNull(manager.claimRunNow("late"))
        assertFalse(manager.rescheduleNext("late"))
        assertEquals(1, manager.get("late")!!.triggeredCount)
    }

    @Test
    fun `the claim is a compare-and-set on the run the menu showed`() {
        manager.store().upsert(interval("cas", ivSec = 60, max = 10, anchor = System.currentTimeMillis() - 60_000))
        val shown = ScheduledTaskManager.pendingRunKey(manager.get("cas")!!, System.currentTimeMillis())
        assertEquals("run:0", shown)
        assertTrue("the alarm fires it first", manager.rescheduleNext("cas"))
        assertNull("the run shown is gone; the following one is not taken instead",
            manager.claimRunNow("cas", expectedRunKey = shown))
        assertEquals(1, manager.get("cas")!!.triggeredCount)
        val calNow = System.currentTimeMillis()
        assertTrue(ScheduledTaskManager.pendingRunKey(daily("k"), calNow)!!.startsWith("slot:"))
    }

    // (d)
    @Test
    fun `a double tap starts once`() {
        manager.store().upsert(interval("d", max = 5))
        val first = ScheduledRunNow.start(ctx, "d", manager)
        val second = ScheduledRunNow.start(ctx, "d", manager)
        assertTrue(first)
        assertFalse(second)
        assertEquals(1, manager.get("d")!!.triggeredCount)
    }

    @Test
    fun `concurrent taps from many threads take the run once`() {
        manager.store().upsert(interval("d2", max = 50))
        val pool = Executors.newFixedThreadPool(8)
        val wins = AtomicInteger()
        val go = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                go.await()
                if (manager.claimRunNow("d2", isRunning = { false }) != null && wins.incrementAndGet() > 0) Unit
                done.countDown()
            }
        }
        go.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        // claimRunNow alone advances the anchor, so each later claim takes the NEXT
        // run: the coordinator's in-flight / running checks are what stop a double
        // tap (above). Here every claim is a distinct run, counted exactly once.
        assertEquals(wins.get(), manager.get("d2")!!.triggeredCount)
    }

    // (e)
    @Test
    fun `a counted loop uses up its count once per run, manual or natural`() {
        manager.store().upsert(interval("e", ivSec = 60, max = 2, anchor = System.currentTimeMillis() - 30_000))
        assertNotNull(manager.claimRunNow("e"))                 // run 1 of 2, early
        assertEquals(1, manager.get("e")!!.remainingFires)
        assertTrue(manager.get("e")!!.enabled)
        assertNotNull(manager.claimRunNow("e"))                 // run 2 of 2: the last
        val last = manager.get("e")!!
        assertEquals(2, last.triggeredCount)
        assertFalse("finished like a natural last fire", last.enabled)
        assertNull(last.nextTriggerMs())
        assertNull("nothing left to take", manager.claimRunNow("e"))
    }

    @Test
    fun `calendar - the taken slot is skipped, a one-shot ends, and its alarm is refused`() {
        val d = daily("cal")
        val now = System.currentTimeMillis()
        val slot = d.nextTriggerMs(now)!!
        val taken = ScheduledTaskManager.runNowTransition(d, now)!!
        assertEquals(slot, taken.runNowSlotMs)
        assertTrue(taken.enabled)
        // The following day's slot (a DST change can move it by an hour).
        val next = taken.nextTriggerMs(now)!!
        assertTrue("the pill counts down to the following day", kotlin.math.abs(next - (slot + 86_400_000L)) <= 3_600_000L)
        assertTrue(ScheduledTaskManager.calendarSlotTakenByRunNow(taken, slot))
        assertTrue(ScheduledTaskManager.calendarSlotTakenByRunNow(taken, slot + 5 * 60_000))
        assertFalse("tomorrow's alarm is a real fire", ScheduledTaskManager.calendarSlotTakenByRunNow(taken, slot + 86_400_000L))
        val once = ScheduledTaskManager.runNowTransition(d.copy(repeatMode = ScheduledRepeatMode.ONCE), now)!!
        assertFalse(once.enabled)
        assertEquals(taken, ScheduledTask.fromJson(taken.toJson()))
    }

    @Test
    fun `wiring - the menu item is first and gated, and the alarm's calendar claim is atomic`() {
        val pill = ProductionSources.read("ui/chat/ScheduledTaskPill.kt")
        val menu = pill.substringAfter("DropdownMenu(expanded = menuOpen").substringBefore("text = { Text(hideLabel) }")
        assertTrue(menu.contains("if (offerRunNow) {"))
        assertTrue(menu.contains("onClick = { menuOpen = false; onRunNow() }"))
        assertTrue("the pill's own label, iOS's wording", pill.contains("val runNowLabel = stringResource(R.string.sched_pill_run_now)"))
        assertTrue(ScheduledRunNow.canOffer(countdown = true, nextFireAt = 1L, running = false, starting = false))
        assertFalse(ScheduledRunNow.canOffer(countdown = false, nextFireAt = 1L, running = false, starting = false))
        assertFalse(ScheduledRunNow.canOffer(countdown = true, nextFireAt = 1L, running = true, starting = false))
        assertFalse(ScheduledRunNow.canOffer(countdown = true, nextFireAt = 1L, running = false, starting = true))
        assertFalse(ScheduledRunNow.canOffer(countdown = true, nextFireAt = null, running = false, starting = false))
        val mgr = ProductionSources.read("scheduled/ScheduledTaskManager.kt")
        assertTrue(mgr.contains("if (!cur.enabled || calendarSlotTakenByRunNow(cur, now)) return@update null"))
        val runNow = ProductionSources.read("scheduled/ScheduledRunNow.kt")
        assertTrue("same fire path as the alarm",
            runNow.contains("ScheduledAgentRunner.run(app, claimed, waitForCompletion = false, scheduledFire = true)"))
    }
}
