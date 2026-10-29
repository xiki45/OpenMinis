package com.openminis.app.scheduled

import com.openminis.app.agent.jobs.AgentJobOrigin
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.agent.jobs.AgentJobThen
import com.openminis.app.agent.jobs.AgentJobTrigger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-active-session-android] Which chats a scheduled task keeps
 * processing between its fires (port of iOS sessionsKeptActiveBySchedule).
 */
class ScheduledSessionHoldTest {

    private val now = 1_800_000_000_000L
    private val none: (String) -> Boolean = { false }

    private fun task(
        target: ScheduledTargetMode = ScheduledTargetMode.AppendToSession("S"),
        creator: String? = "S",
        enabled: Boolean = true,
        kind: ScheduledTriggerKind = ScheduledTriggerKind.INTERVAL,
        repeat: ScheduledRepeatMode = ScheduledRepeatMode.DAILY,
        customDays: Set<Int> = emptySet(),
        endDateMs: Long? = null,
        of: String? = null,
        id: String = java.util.UUID.randomUUID().toString(),
    ) = ScheduledTask(
        id = id, label = "loop", timeOfDayHour = 9, timeOfDayMinute = 0, repeatMode = repeat,
        customDays = customDays, prompt = "p", targetMode = target, enabled = enabled,
        endDateMs = endDateMs, triggerKind = kind,
        delaySec = if (kind == ScheduledTriggerKind.AFTER) 600 else null,
        intervalSec = if (kind == ScheduledTriggerKind.INTERVAL) 60 else null,
        maxFires = if (kind == ScheduledTriggerKind.INTERVAL) 3 else null,
        onCompletionOf = of,
        anchorMs = if (kind == ScheduledTriggerKind.INTERVAL || kind == ScheduledTriggerKind.AFTER) now else null,
        triggeredCount = if (kind == ScheduledTriggerKind.INTERVAL) 1 else null,
        creatorSessionId = creator,
    )

    private fun held(t: ScheduledTask, upstream: (String) -> Boolean = none) =
        ScheduledSessionHold.heldSession(t, now, upstream)

    @Test fun `a follow-up loop aimed back at the chat that created it keeps that chat`() {
        assertEquals("S", held(task()))
        assertEquals("S", held(task(kind = ScheduledTriggerKind.AFTER)))
        assertEquals("S", held(task(kind = ScheduledTriggerKind.CALENDAR, repeat = ScheduledRepeatMode.DAILY)))
        assertEquals("S", held(task(kind = ScheduledTriggerKind.CALENDAR, repeat = ScheduledRepeatMode.ONCE)))
    }

    @Test fun `a finished or cancelled task holds nothing`() {
        // ONCE / AFTER / an INTERVAL's last fire are disabled by their alarm.
        assertNull(held(task(enabled = false)))
        assertNull(held(task(kind = ScheduledTriggerKind.AFTER, enabled = false)))
        assertNull(held(task(kind = ScheduledTriggerKind.CALENDAR, repeat = ScheduledRepeatMode.ONCE, enabled = false)))
        // A repeating calendar task past its end date, or with no days chosen.
        assertNull(held(task(kind = ScheduledTriggerKind.CALENDAR, endDateMs = now - 3 * 86_400_000L)))
        assertNull(held(task(kind = ScheduledTriggerKind.CALENDAR, repeat = ScheduledRepeatMode.CUSTOM, customDays = emptySet())))
    }

    @Test fun `only follow-ups into their own creator count, as on ios`() {
        assertNull("another chat", held(task(target = ScheduledTargetMode.AppendToSession("OTHER"), creator = "S")))
        assertNull("made outside any chat (editor, rpc, before this build)", held(task(creator = null)))
        assertNull("new chat each fire", held(task(target = ScheduledTargetMode.NewSession)))
        assertNull("rerun", held(task(target = ScheduledTargetMode.RerunMessage("S", "m1"))))
        assertNull("hidden child run", held(task(target = ScheduledTargetMode.ChildOfCurrent("S"))))
    }

    @Test fun `on-completion counts only while its upstream is unfinished`() {
        val t = task(kind = ScheduledTriggerKind.ON_COMPLETION, of = "UP")
        assertEquals("S", held(t) { it == "UP" })
        assertNull(held(t) { false })
        assertNull("no upstream named", held(task(kind = ScheduledTriggerKind.ON_COMPLETION, of = null)) { true })
    }

    @Test fun `upstream - a scheduled task due or running, or an agent job still active`() {
        val upTask = task(id = "UP")
        assertTrue(ScheduledSessionHold.upstreamUnfinished("UP", listOf(upTask), emptySet(), emptyMap(), now))
        val doneTask = task(id = "UP", enabled = false)
        assertFalse(ScheduledSessionHold.upstreamUnfinished("UP", listOf(doneTask), emptySet(), emptyMap(), now))
        assertTrue("its last fire still running",
            ScheduledSessionHold.upstreamUnfinished("UP", listOf(doneTask), setOf("UP"), emptyMap(), now))
        assertFalse("gone (deleted, or a job from a previous process)",
            ScheduledSessionHold.upstreamUnfinished("UP", emptyList(), emptySet(), emptyMap(), now))

        AgentJobRegistry.resetForTest()
        val job = AgentJobRegistry.register(
            title = "t", origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
            target = AgentJobTarget.ChildOfCurrent("P", "tu"), prompt = "do", then = AgentJobThen.None,
        )
        assertTrue(ScheduledSessionHold.upstreamUnfinished(job.id, emptyList(), emptySet(), AgentJobRegistry.jobs.value, now))
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, "ok")
        assertFalse(ScheduledSessionHold.upstreamUnfinished(job.id, emptyList(), emptySet(), AgentJobRegistry.jobs.value, now))
        AgentJobRegistry.resetForTest()
    }

    @Test fun `a fire being run keeps its chat even after the alarm disabled the task`() {
        // The LAST fire of --count: rescheduleNext disabled it before the run.
        val last = task(enabled = false)
        assertNull(held(last))
        assertEquals("S", ScheduledSessionHold.runHold(last))
        assertNull(ScheduledSessionHold.runHold(task(target = ScheduledTargetMode.NewSession)))
        assertNull(ScheduledSessionHold.runHold(task(creator = null)))
    }

    @Test fun `run holds are counted, so overlapping holders do not release early`() {
        ScheduledSessionHold.beginRun("T")   // the alarm receiver's handoff
        ScheduledSessionHold.beginRun("T")   // run()'s own hold
        ScheduledSessionHold.endRun("T")     // handoff done
        assertTrue("T" in ScheduledSessionHold.runningTaskIds.value)
        ScheduledSessionHold.endRun("T")     // loop finished
        assertFalse("T" in ScheduledSessionHold.runningTaskIds.value)
    }

    @Test fun `isLive is what Stop turns off and asks about`() {
        assertTrue(ScheduledSessionHold.isLive(task(), now))
        assertFalse(ScheduledSessionHold.isLive(task(enabled = false), now))
        assertTrue(ScheduledSessionHold.isLive(task(kind = ScheduledTriggerKind.ON_COMPLETION, of = "X"), now))
    }

    @Test fun `stop and delete turn off only what that chat created`() {
        val mine = task(creator = "S")
        val mineNewChat = task(creator = "S", target = ScheduledTargetMode.NewSession)
        val aimedAtMeFromEditor = task(creator = null, target = ScheduledTargetMode.AppendToSession("S"))
        val other = task(creator = "OTHER", target = ScheduledTargetMode.AppendToSession("OTHER"))
        val off = task(creator = "S", enabled = false)
        val picked = ScheduledTaskManager.createdByEnabled(listOf(mine, mineNewChat, aimedAtMeFromEditor, other, off), "S")
        assertEquals(listOf(mine.id, mineNewChat.id), picked.map { it.id })
    }

    @Test fun `the creator survives json and an edit in the editor`() {
        val t = task(creator = "S")
        assertEquals("S", ScheduledTask.fromJson(JSONObject(t.toJson().toString())).creatorSessionId)
        assertNull(ScheduledTask.fromJson(JSONObject(task(creator = null).toJson().toString())).creatorSessionId)
        assertFalse("absent when null, so old builds read it unchanged", task(creator = null).toJson().has("creatorSessionId"))
        // The editor rebuilds the task without knowing its creator.
        val edited = ScheduledTaskManager.mergeBookkeeping(t.copy(creatorSessionId = null, label = "renamed"), t)
        assertEquals("S", edited.creatorSessionId)
    }

    @Test fun `held sessions over a list`() {
        val set = ScheduledSessionHold.heldSessions(
            listOf(task(), task(creator = "B", target = ScheduledTargetMode.AppendToSession("B")), task(enabled = false)),
            now, none,
        )
        assertEquals(setOf("S", "B"), set)
    }
}
