package com.openminis.app.scheduled

import com.openminis.app.ProductionSources
import com.openminis.app.scheduled.ScheduledTaskDetailModel.RelativeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [T-scheduled-card-subline] Field report (Pixel 6): after a recurring task's
 * next fire had passed or it was stopped, every fire card showed the badge and
 * title over an EMPTY second line. The line is now the iOS card's subline:
 * "Run N · N left · Next <relative> | Task complete · <trigger>", falling back
 * to the prompt's first line so a card is never just a title.
 */
class ScheduledCardSublineTest {

    private val p = ScheduledTaskDetailModel.CardPhrases(
        run = "Run %1\$d",
        left = "%1\$d left",
        next = "Next %1\$s",
        complete = "Task complete",
        cancelled = "Schedule cancelled",
    )

    private fun line(m: ScheduledTaskMarker, now: Long, cancelled: Boolean = false, live: Boolean = true) =
        ScheduledTaskDetailModel.cardSubline(m, cancelled, now, p, live = live) { at -> "in ${(at - now) / 60_000} min." }

    private fun marker(
        next: Long?,
        fire: Int? = null,
        remaining: Int? = null,
        trigger: String? = null,
        prompt: String = "Fetch HN AI news\nthen compare",
    ) = ScheduledTaskMarker("T", "AI news", next, prompt, fireIndex = fire, remaining = remaining, triggerLabel = trigger)

    // ── The subline ─────────────────────────────────────────────────────────

    @Test
    fun `recurring mid-run with the next fire ahead`() {
        val m = marker(next = 240_000L, fire = 2, remaining = 3, trigger = "loop(60s×5)")
        assertEquals("Run 2 · 3 left · Next in 4 min. · loop(60s×5)", line(m, now = 0L))
    }

    @Test
    fun `a passed next fire says nothing about completion but keeps the rest`() {
        // The field report's case: previously an empty line.
        val m = marker(next = 60_000L, fire = 2, remaining = 3, trigger = "loop(60s×5)")
        assertEquals("Run 2 · 3 left · loop(60s×5)", line(m, now = 120_000L))
        // Unbounded: no remaining count, still never empty.
        assertEquals("Run 7 · loop(60s×∞)", line(marker(next = 60_000L, fire = 7, trigger = "loop(60s×∞)"), now = 120_000L))
    }

    @Test
    fun `the last fire says task complete`() {
        // No next fire promised...
        assertEquals("Run 5 · 0 left · Task complete · loop(60s×5)",
            line(marker(next = null, fire = 5, remaining = 0, trigger = "loop(60s×5)"), now = 0L))
        // ...or nothing left, even with a stale next time on the envelope.
        assertEquals("Run 5 · 0 left · Task complete",
            line(marker(next = 60_000L, fire = 5, remaining = 0), now = 120_000L))
        assertTrue(ScheduledTaskDetailModel.isLastFire(marker(next = null)))
        assertTrue(ScheduledTaskDetailModel.isLastFire(marker(next = 10L, remaining = 0)))
        assertFalse(ScheduledTaskDetailModel.isLastFire(marker(next = 10L, remaining = 1)))
    }

    @Test
    fun `cancelling from the card replaces the next-fire promise`() {
        val m = marker(next = 240_000L, fire = 2, remaining = 3, trigger = "loop(60s×5)")
        assertEquals("Run 2 · 3 left · Schedule cancelled · loop(60s×5)", line(m, now = 0L, cancelled = true))
    }

    @Test
    fun `a stopped task withdraws only a next fire still ahead`() {
        // Cancelled elsewhere (settings, another card) while this card still
        // promises a run: the promise is withdrawn.
        val ahead = marker(next = 240_000L, fire = 2, remaining = 3, trigger = "loop(60s×5)")
        assertEquals("Run 2 · 3 left · Schedule cancelled · loop(60s×5)", line(ahead, now = 0L, live = false))
        // The field report's case - a stopped 60 s task, old cards past their
        // next fire - says nothing about it, like iOS...
        assertEquals("Run 2 · 3 left · loop(60s×5)", line(ahead, now = 300_000L, live = false))
        // ...and a task that ran out its count is complete, not cancelled.
        assertEquals("Run 5 · 0 left · Task complete · loop(60s×5)",
            line(marker(next = null, fire = 5, remaining = 0, trigger = "loop(60s×5)"), now = 0L, live = false))
    }

    @Test
    fun `the relative label is re-read at each minute boundary`() {
        assertEquals(30_001L, ScheduledTaskDetailModel.nextTickMs(30_000L))
        assertEquals(60_001L, ScheduledTaskDetailModel.nextTickMs(60_000L))
        // 4 min 20 s ahead reads "in 4 min." until 4:00 remains.
        assertEquals(20_001L, ScheduledTaskDetailModel.nextTickMs(260_000L))
        assertEquals(60_001L, ScheduledTaskDetailModel.nextTickMs(240_000L))
    }

    @Test
    fun `a one-shot says task complete with its trigger`() {
        assertEquals("Task complete · once(+1800s)", line(marker(next = null, trigger = "once(+1800s)"), now = 0L))
    }

    @Test
    fun `an envelope with only a prompt falls back to its first line`() {
        // Written before fire / remaining / trigger existed, next fire passed:
        // exactly the cards in the field report's screenshot.
        assertEquals("Fetch HN AI news", line(marker(next = 60_000L, prompt = "\n  Fetch HN AI news  \nthen compare"), now = 120_000L))
        // Nothing at all to say stays empty rather than inventing text.
        assertEquals("", line(marker(next = 60_000L, prompt = " \n "), now = 120_000L))
    }

    @Test
    fun `relative units truncate to the largest non-zero one`() {
        assertEquals(30L to RelativeUnit.SECOND, ScheduledTaskDetailModel.relativeUnit(30))
        assertEquals(1L to RelativeUnit.MINUTE, ScheduledTaskDetailModel.relativeUnit(90))
        assertEquals(59L to RelativeUnit.MINUTE, ScheduledTaskDetailModel.relativeUnit(3_599))
        assertEquals(1L to RelativeUnit.HOUR, ScheduledTaskDetailModel.relativeUnit(5_400))
        assertEquals(1L to RelativeUnit.DAY, ScheduledTaskDetailModel.relativeUnit(90_000))
        assertEquals(1L to RelativeUnit.WEEK, ScheduledTaskDetailModel.relativeUnit(8 * 86_400L))
    }

    // ── The envelope ────────────────────────────────────────────────────────

    @Test
    fun `old envelopes with an earlier note wording still show their prompt`() {
        // 59a58884f wrote a longer insertion reminder; the exact-match strip
        // missed it and the card's fallback line was the reminder itself.
        val oldInserted = "<scheduled_task id=\"T\" label=\"AI news\" next=\"5\" prefilled=\"shell_execute\" inserted=\"1\">\n" +
            "<system-reminder>[Scheduled task, inserted] The scheduled task \"AI news\" fired while you were in the middle " +
            "of other work. Its prefilled command (shell_execute) has already been run for you.</system-reminder>\n" +
            "Fetch HN AI news\nthen compare\n</scheduled_task>"
        assertEquals("Fetch HN AI news\nthen compare", ScheduledTaskMarker.parse(oldInserted)!!.prompt)
        assertEquals("Fetch HN AI news", line(ScheduledTaskMarker.parse(oldInserted)!!, now = 60L))
        val oldNote = "<scheduled_task id=\"T\" label=\"L\" prefilled=\"shell_execute\">\n" +
            "[This task's first step already ran for you: shell_execute (older wording).]\nSummarise it\n</scheduled_task>"
        assertEquals("Summarise it", ScheduledTaskMarker.parse(oldNote)!!.prompt)
        // Without the attribute nothing is stripped: a user's own prompt may
        // legitimately start with either shape.
        val plain = "<scheduled_task id=\"T\" label=\"L\">\n<system-reminder>mine</system-reminder>\nx\n</scheduled_task>"
        assertEquals("<system-reminder>mine</system-reminder>\nx", ScheduledTaskMarker.parse(plain)!!.prompt)
    }

    @Test
    fun `fire, remaining and trigger round-trip and are omitted when unset`() {
        val m = marker(next = 5L, fire = 3, remaining = 2, trigger = "cron(9:05 days=[2, 3])")
        val xml = m.xml
        assertTrue(xml.contains(" fire=\"3\" remaining=\"2\" trigger=\"cron(9:05 days=[2, 3])\""))
        val back = ScheduledTaskMarker.parse(xml)!!
        assertEquals(3, back.fireIndex)
        assertEquals(2, back.remaining)
        assertEquals("cron(9:05 days=[2, 3])", back.triggerLabel)
        // An old-style marker serialises exactly as before.
        val plain = ScheduledTaskMarker("T", "L", 5L, "p", firedAtMs = 1L).xml
        assertEquals("<scheduled_task id=\"T\" label=\"L\" next=\"5\" fired=\"1\">\np\n</scheduled_task>", plain)
        val old = ScheduledTaskMarker.parse(plain)!!
        assertNull(old.fireIndex); assertNull(old.remaining); assertNull(old.triggerLabel)
    }

    private fun task(
        kind: ScheduledTriggerKind,
        repeat: ScheduledRepeatMode = ScheduledRepeatMode.DAILY,
        days: Set<Int> = emptySet(),
        interval: Long? = null,
        max: Int? = null,
        triggered: Int? = null,
        fireCount: Int? = null,
        delay: Long? = null,
        of: String? = null,
    ) = ScheduledTask(
        id = "T", label = "AI news", timeOfDayHour = 9, timeOfDayMinute = 5, repeatMode = repeat,
        customDays = days, prompt = "p", triggerKind = kind, intervalSec = interval, maxFires = max,
        triggeredCount = triggered, fireCount = fireCount, delaySec = delay, onCompletionOf = of,
    )

    @Test
    fun `trigger labels use the iOS logLabel form`() {
        fun l(t: ScheduledTask) = ScheduledTaskMarker.triggerLabel(t)
        assertEquals("loop(60s×5)", l(task(ScheduledTriggerKind.INTERVAL, interval = 60, max = 5)))
        assertEquals("loop(600s×∞)", l(task(ScheduledTriggerKind.INTERVAL, interval = 600)))
        assertEquals("once(+1800s)", l(task(ScheduledTriggerKind.AFTER, delay = 1800)))
        assertEquals("onCompletion(abcdef12)", l(task(ScheduledTriggerKind.ON_COMPLETION, of = "abcdef1234567")))
        assertEquals("cron(9:05 days=[1, 2, 3, 4, 5, 6, 7])", l(task(ScheduledTriggerKind.CALENDAR)))
        assertEquals("cron(9:05 days=[2, 3, 4, 5, 6])", l(task(ScheduledTriggerKind.CALENDAR, ScheduledRepeatMode.WEEKDAYS)))
        assertEquals(
            "cron(9:05 days=[1, 4])",
            l(task(ScheduledTriggerKind.CALENDAR, ScheduledRepeatMode.CUSTOM, days = setOf(Calendar.WEDNESDAY, Calendar.SUNDAY))),
        )
        assertEquals("once", l(task(ScheduledTriggerKind.CALENDAR, ScheduledRepeatMode.ONCE)))
    }

    @Test
    fun `a fire numbers itself like the iOS envelope`() {
        // Interval: the receiver counted this fire before the run.
        val iv = ScheduledTaskMarker.forFire(
            task(ScheduledTriggerKind.INTERVAL, interval = 60, max = 5, triggered = 2), 1L, scheduledFire = true, prefilledTool = null, nextFireAtMs = 9L,
        )
        assertEquals(2, iv.fireIndex)
        assertEquals(3, iv.remaining)
        assertEquals("loop(60s×5)", iv.triggerLabel)
        assertEquals(1L, iv.firedAtMs)
        // Calendar repeat: recorded on completion, so one past the recorded fires; no count.
        val cal = ScheduledTaskMarker.forFire(task(ScheduledTriggerKind.CALENDAR, fireCount = 4), 1L, true, null, 9L)
        assertEquals(5, cal.fireIndex)
        assertNull(cal.remaining)
        // One-shots and manual runs are not numbered fires of a schedule.
        assertNull(ScheduledTaskMarker.forFire(task(ScheduledTriggerKind.AFTER, delay = 60, triggered = 1), 1L, true, null, null).fireIndex)
        val manual = ScheduledTaskMarker.forFire(task(ScheduledTriggerKind.INTERVAL, interval = 60, max = 5, triggered = 2), 1L, false, null, 9L)
        assertNull(manual.fireIndex)
        assertNull(manual.remaining)
        assertEquals("loop(60s×5)", manual.triggerLabel)
    }

    @Test
    fun `the runner writes the envelope through forFire with the fire kind`() {
        val runner = ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        assertTrue(runner.contains("val marked = ScheduledTaskMarker.forFire("))
        assertTrue(runner.contains("scheduledFire = scheduledFire,"))
        assertEquals(2, runner.split("firedAt = firedAt, scheduledFire = scheduledFire)").size - 1)
    }

    @Test
    fun `cancel is offered only while the task is a live timer`() {
        val card = ProductionSources.read("ui/chat/ScheduledTaskCard.kt")
        assertTrue(card.contains("tasks.any { it.id == marker.taskId && it.enabled }"))
        assertTrue(card.contains("action = if (live && !cancelled) {"))
        assertTrue(card.contains("cardSubline(marker, cancelled, now, phrases, live = live)"))
    }

    @Test
    fun `the status text cannot make the card taller than the sub-agent card`() {
        val card = ProductionSources.read("ui/chat/ScheduledTaskCard.kt")
        assertTrue(card.contains(".height(taskCardStatusLineHeight())"))
        assertTrue(card.contains(".wrapContentHeight(Alignment.CenterVertically, unbounded = true)"))
        assertTrue(card.contains("delay(ScheduledTaskDetailModel.nextTickMs(wait))"))
        val frame = ProductionSources.read("ui/chat/TaskCardFrame.kt")
        assertTrue(frame.contains("val h = taskCardStatusLineHeight()"))
    }
}
