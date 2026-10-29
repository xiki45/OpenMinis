package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTriggerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-port-sched-pill] The scheduled-task pill under the chat's top bar: which
 * task it shows, the clock, the ring, the run counter and the hide rule, as
 * iOS ScheduledBannerTests pin them; plus which tasks count (the
 * keeps-the-chat-active rule) and the wiring.
 */
class ScheduledPillModelTest {

    private fun job(
        id: String,
        next: Long? = null,
        firing: Boolean = false,
        title: String = id,
        done: Int = 0,
        total: Int? = null,
        repeats: Boolean = true,
        start: Long = 0,
        remaining: Int? = null,
    ) = PillJob(id, title, next, start, remaining, firing, done, total, repeats)

    @Test
    fun `a running task wins, else the soonest, an on-completion task only when nothing has a time`() {
        assertNull(ScheduledPillModel.state(emptyList()))
        val s1 = ScheduledPillModel.state(listOf(job("a", next = 500), job("b", next = 100), job("w")))!!
        assertEquals("b", s1.job.id); assertEquals(ScheduledPillModel.Phase.COUNTDOWN, s1.phase); assertEquals(2, s1.others)
        val s2 = ScheduledPillModel.state(listOf(job("a", next = 500, firing = true), job("b", next = 100)))!!
        assertEquals("a", s2.job.id); assertEquals(ScheduledPillModel.Phase.RUNNING, s2.phase)
        val s3 = ScheduledPillModel.state(listOf(job("z", title = "Zed"), job("y", title = "Alpha")))!!
        assertEquals("y", s3.job.id); assertEquals(ScheduledPillModel.Phase.WAITING, s3.phase); assertEquals(1, s3.others)
    }

    @Test
    fun `clock - mm ss rounded up below an hour, coarse units above`() {
        assertEquals("00:48", ScheduledPillModel.countdown(48_000))
        assertEquals("00:48", ScheduledPillModel.countdown(47_001))
        assertEquals("00:01", ScheduledPillModel.countdown(1))
        assertEquals("00:00", ScheduledPillModel.countdown(0))
        assertEquals("59:59", ScheduledPillModel.countdown(3_599_000))
        assertNull(ScheduledPillModel.countdown(3_600_000))
        assertEquals(ScheduledPillModel.Coarse(0, 1, 0), ScheduledPillModel.coarse(3_600_000))
        assertEquals(ScheduledPillModel.Coarse(0, 1, 6), ScheduledPillModel.coarse(3_901_000))
        assertEquals(ScheduledPillModel.Coarse(1, 3, 0), ScheduledPillModel.coarse((27 * 3600 + 120) * 1000L))
    }

    @Test
    fun `ring fills toward the run`() {
        val j = job("a", next = 1_000, start = 0)
        assertEquals(0f, ScheduledPillModel.progress(j, 0), 0.001f)
        assertEquals(0.25f, ScheduledPillModel.progress(j, 250), 0.001f)
        assertEquals(1f, ScheduledPillModel.progress(j, 5_000), 0.001f)
        assertEquals(0f, ScheduledPillModel.progress(job("w"), 5), 0.001f)
    }

    @Test
    fun `run counter matches iOS - this run, total cap, none for a one-shot`() {
        assertEquals("2/30", ScheduledPillModel.runCounter(job("a", done = 1, total = 30), running = false))
        assertEquals("1/30", ScheduledPillModel.runCounter(job("a", done = 1, total = 30), running = true))
        assertEquals("1/3", ScheduledPillModel.runCounter(job("a", done = 0, total = 3), running = true))
        assertEquals("3/3", ScheduledPillModel.runCounter(job("a", done = 5, total = 3), running = false))
        assertEquals("#7", ScheduledPillModel.runCounter(job("a", done = 6), running = false))
        assertNull(ScheduledPillModel.runCounter(job("a", repeats = false), running = false))
    }

    @Test
    fun `width floors keep the pill still while it ticks`() {
        assertEquals("00:00", ScheduledPillModel.widthTemplate("01:39"))
        assertEquals("00h 00m", ScheduledPillModel.widthTemplate("1h 5m"))
        assertEquals("00/00", ScheduledPillModel.widthTemplate("9/30"))
        assertEquals("#00", ScheduledPillModel.widthTemplate("#7"))
        assertEquals("000/000", ScheduledPillModel.widthTemplate("100/100"))
        assertEquals("Running", ScheduledPillModel.widthTemplate("Running"))
    }

    @Test
    fun `hide covers the tasks live when chosen, a new task brings it back`() {
        assertFalse(ScheduledPillModel.isDismissed(emptySet(), setOf("a")))
        assertTrue(ScheduledPillModel.isDismissed(setOf("a"), setOf("a", "b")))
        assertFalse(ScheduledPillModel.isDismissed(setOf("a", "c"), setOf("a", "b")))
    }

    // --- Which tasks -------------------------------------------------------------

    private val chat = "chat-1"
    private fun task(
        id: String,
        trigger: ScheduledTriggerKind = ScheduledTriggerKind.INTERVAL,
        target: ScheduledTargetMode = ScheduledTargetMode.AppendToSession(chat),
        creator: String? = chat,
        enabled: Boolean = true,
        triggered: Int? = null,
        max: Int? = null,
        fireCount: Int? = null,
        repeat: ScheduledRepeatMode = ScheduledRepeatMode.DAILY,
    ) = ScheduledTask(
        id = id, label = "L-$id", timeOfDayHour = 9, timeOfDayMinute = 0, repeatMode = repeat, prompt = "p",
        targetMode = target, enabled = enabled, createdAt = 0, triggerKind = trigger, anchorMs = 1_000_000,
        intervalSec = 60, maxFires = max, triggeredCount = triggered, fireCount = fireCount, creatorSessionId = creator,
    )

    @Test
    fun `only tasks that keep this chat active, plus one running here`() {
        val now = 1_000_000L
        val held = task("held", triggered = 1, max = 30)
        val other = task("other", target = ScheduledTargetMode.AppendToSession("chat-2"), creator = "chat-2")
        val newChat = task("new", target = ScheduledTargetMode.NewSession)
        val lastFire = task("last", enabled = false, triggered = 3, max = 3)
        val jobs = ScheduledPillModel.jobs(chat, listOf(held, other, newChat, lastFire), setOf("last"), now) { false }
        assertEquals(listOf("held", "last"), jobs.map { it.id })
        val h = jobs[0]
        assertEquals(now + 60_000, h.nextFireAt)
        assertEquals(now, h.windowStart)
        assertEquals("2/30", ScheduledPillModel.runCounter(h, running = false))
        assertEquals(29, h.remaining)
        val l = jobs[1]
        assertTrue(l.isFiringNow)
        assertEquals("3/3", ScheduledPillModel.runCounter(l, running = true))
        // Not running any more: the disabled task is gone.
        assertEquals(listOf("held"), ScheduledPillModel.jobs(chat, listOf(held, lastFire), emptySet(), now) { false }.map { it.id })
    }

    @Test
    fun `a calendar fire counts once its run starts`() {
        val daily = task("d", trigger = ScheduledTriggerKind.CALENDAR, fireCount = 3)
        assertEquals("#4", ScheduledPillModel.runCounter(ScheduledPillModel.pillJob(daily, 0, firing = false), running = false))
        assertEquals("#4", ScheduledPillModel.runCounter(ScheduledPillModel.pillJob(daily, 0, firing = true), running = true))
        val once = task("o", trigger = ScheduledTriggerKind.CALENDAR, repeat = ScheduledRepeatMode.ONCE)
        assertNull(ScheduledPillModel.runCounter(ScheduledPillModel.pillJob(once, 0, false), false))
    }

    // --- Wiring ----------------------------------------------------------------

    @Test
    fun `mounted under the app bar in the top bar slot, cancel-all is the Stop scope`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("topBar = { Column {"))
        assertTrue(screen.contains("            ScheduledTaskPill(currentSessionId = { viewModel.currentSessionId })\n        } },"))
        val pill = ProductionSources.read("ui/chat/ScheduledTaskPill.kt")
        assertTrue(pill.contains("manager.disableAllCreatedBy(sessionId, \"user cancelled all from the timer pill\")"))
        assertTrue("ticks only while resumed", pill.contains("lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED)"))
        assertTrue("one rule with busy / Stop", ProductionSources.read("ui/chat/ScheduledPillModel.kt")
            .contains("ScheduledSessionHold.heldSession(t, now, upstreamUnfinished) == sessionId"))
        assertTrue(pill.contains("CustomAccessibilityAction(hideLabel)") && pill.contains("CustomAccessibilityAction(cancelLabel)"))
    }

    @Test
    fun `T-port-sched-pill-visible - a chat started fresh is matched by its real id, not the draft route id`() {
        // ChatScreen's route id for a new chat is "__new__…" and never changes;
        // the task names the real id the draft got on its first message.
        val now = 1_000_000L
        val t = task("t", triggered = 0, max = 3)
        assertTrue(ScheduledPillModel.jobs("__new__1791024000", listOf(t), emptySet(), now) { false }.isEmpty())
        assertEquals(listOf("t"), ScheduledPillModel.jobs(chat, listOf(t), emptySet(), now) { false }.map { it.id })
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertFalse("the pill must not get the route id", screen.contains("ScheduledTaskPill(sessionId = sessionId)"))
        assertTrue("the scheduled card's host is the real id too", screen.contains("hostSessionId = viewModel.currentSessionId, // [T-port-sched-pill-visible]"))
        assertFalse(screen.contains("hostSessionId = sessionId,"))
        val pill = ProductionSources.read("ui/chat/ScheduledTaskPill.kt")
        assertTrue("read on every composition", pill.contains("val sessionId = currentSessionId()"))
    }

    @Test
    fun `every locale has the pill strings`() {
        val res = File(ProductionSources.mainRoot()!!, "../../../../res")
        val locales = listOf("values", "values-de", "values-es", "values-fil", "values-fr", "values-hr", "values-in", "values-ja",
            "values-ko", "values-ms", "values-pl", "values-pt-rBR", "values-ro", "values-ru", "values-th", "values-tr", "values-zh", "values-zh-rTW")
        val base = Regex("name=\"(sched_pill_[a-z0-9_]+)\"").findAll(File(res, "values/strings_sched_pill.xml").readText()).map { it.groupValues[1] }.toList()
        assertEquals(19, base.size)  // + sched_pill_run_now [T-sched-run-now]
        for (l in locales) {
            val text = File(res, "$l/strings_sched_pill.xml").readText()
            for (k in base) assertTrue("$l lacks $k", text.contains("name=\"$k\""))
            assertTrue("$l keeps both run numbers", Regex("sched_pill_a11y_run_of\">[^<]*%1\\\$d[^<]*").containsMatchIn(text) && text.contains("%2\$d"))
        }
    }
}
