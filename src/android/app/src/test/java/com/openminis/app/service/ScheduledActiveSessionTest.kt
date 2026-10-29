package com.openminis.app.service

import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-active-session-android] A chat whose own scheduled task is still
 * due stays "processing" (port of iOS f2891c01b): one set in the tracker,
 * which keep-alive, Stop, the list spinner and the notification follow - while
 * activeSessions keeps meaning "its loop is running now".
 */
class ScheduledActiveSessionTest {

    @After fun reset() {
        SessionActivityTracker.setScheduledSessions(emptySet())
        SessionActivityTracker.setInactive("A")
        SessionActivityTracker.setInactive("S")
    }

    @Test fun `a scheduled chat is processing but not active`() {
        SessionActivityTracker.setScheduledSessions(setOf("S"))
        assertTrue(SessionActivityTracker.isProcessing("S"))
        assertTrue("S" in SessionActivityTracker.processingSessions.value)
        assertFalse("activeSessions is the loop alone", SessionActivityTracker.isActive("S"))
        assertTrue("keep-alive follows it", SessionActivityTracker.shouldRunServiceNow())
        SessionActivityTracker.setScheduledSessions(emptySet())
        assertFalse(SessionActivityTracker.isProcessing("S"))
        assertFalse(SessionActivityTracker.shouldRunServiceNow())
    }

    @Test fun `processing is the union, and a turn ending does not drop the scheduled hold`() {
        SessionActivityTracker.setScheduledSessions(setOf("S"))
        SessionActivityTracker.setActive("A")
        SessionActivityTracker.setActive("S")
        assertEquals(setOf("A", "S"), SessionActivityTracker.processingSessions.value)
        SessionActivityTracker.setInactive("S")   // the fire's turn ends
        SessionActivityTracker.setInactive("A")
        assertEquals("the gap between fires stays processing", setOf("S"), SessionActivityTracker.processingSessions.value)
        assertTrue(SessionActivityTracker.activeSessions.value.isEmpty())
    }

    // ── Source guards ────────────────────────────────────────────────────

    private val tracker = ProductionSources.read("service/SessionActivityTracker.kt")
    private val service = ProductionSources.read("service/AgentForegroundService.kt")
    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
    private val screen = ProductionSources.read("ui/chat/ChatScreen.kt")

    @Test fun `scheduled changes never start the service from the background`() {
        val body = tracker.substringAfter("fun setScheduledSessions(").substringBefore("\n    }\n")
        assertTrue(body.contains("if (AgentForegroundService.isAlive) stopService()"))
        assertTrue(body.contains("} else if (AgentForegroundService.isAlive) {\n            updateService()"))
        assertFalse(body.contains("startServiceIfNeeded()"))
    }

    @Test fun `loop-only consumers still read activeSessions`() {
        val main = ProductionSources.read("MainActivity.kt")
        assertTrue("keep screen awake", main.contains("SessionActivityTracker.activeSessions.collect { active ->\n                applyKeepScreenAwakeFlag(active.isNotEmpty())"))
        // [T-port-sched-wait-notification] "Completed" also excludes a
        // scheduled wait now (the WAITING phase), but still reads the loop set.
        assertTrue("live notification completion",
            service.contains("finishedAtMs != null && SessionActivityTracker.activeSessions.value.isEmpty()"))
        assertTrue(service.contains("val hasActiveSessions = SessionActivityTracker.activeSessions.value.isNotEmpty()"))
        assertTrue("resume detection", vm.contains("val trackerActive = SessionActivityTracker.isActive(activeSessionId)"))
        // Never fold the scheduled chats into the loop set.
        assertFalse(tracker.contains("_activeSessions.value = _activeSessions.value + _scheduledSessions"))
    }

    @Test fun `stop - only the user's entries take the timers down`() {
        val cancel = vm.substringAfter("    fun cancelStream(keepBackground: Boolean = false) {").substringBefore("\n    }\n")
        assertFalse("internal stops (model switch, New Chat, tool pill) keep the timers",
            cancel.contains("ScheduledTaskManager") || cancel.contains("disableAllCreatedBy"))
        val stopByUser = vm.substringAfter("    fun stopByUser() {").substringBefore("\n    }\n")
        assertTrue(stopByUser.contains("disableAllCreatedBy(id, \"user stopped the conversation\")"))
        // stopByUser is called from the composer's Stop and nowhere else.
        val mainRoot = ProductionSources.mainRoot()!!
        val callers = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(".stopByUser()") }.map { it.name }.toList()
        assertEquals(listOf("ChatScreen.kt"), callers)
        val composer = screen.substringAfter("val showStop = sessionBusy && !hasContent").substringBefore("} else {\n                            // Streaming with content")
        assertTrue(composer.contains("viewModel.stopByUser()") && composer.contains("viewModel.stopSummary()"))
        assertTrue("the notification's Stop", service.contains("SessionActivityTracker.stopAllByUser()\n            stopSelf()"))
        assertTrue(tracker.substringAfter("fun stopAllByUser()").contains("disableAllCreatedBy(sid, \"user stopped from the notification\")"))
    }

    @Test fun `readers of processing`() {
        assertTrue(screen.contains("viewModel.currentSessionId in processingSessions"))
        val list = ProductionSources.read("ui/sessions/SessionListScreen.kt")
        assertEquals(2, list.split("SessionActivityTracker.processingSessions.collectAsState()").size - 1)
        assertTrue("swipe-away keep-alive", service.contains("SessionActivityTracker.scheduledSessions.value.isEmpty()\n        ) {"))
        assertTrue(ProductionSources.read("debug/ChatDebugMethods.kt").contains("put(\"isRunning\", SessionActivityTracker.isProcessing(session.id))"))
    }

    @Test fun `the hold is fed, held through each fire, and released on delete`() {
        assertTrue(ProductionSources.read("MinisApp.kt").contains("ScheduledSessionHold.observe(this@MinisApp).collect { held ->"))
        val receiver = ProductionSources.read("scheduled/ScheduledTaskAlarmReceiver.kt")
        assertTrue(receiver.indexOf("ScheduledSessionHold.beginRun(taskId)") in 0 until receiver.indexOf("manager.rescheduleNext(taskId)"))
        assertTrue(receiver.contains("ScheduledSessionHold.endRun(taskId)"))
        val runner = ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        assertEquals(2, runner.split("ScheduledSessionHold.endRun(task.id)").size - 1)
        assertTrue(ProductionSources.read("data/session/SessionDeleter.kt").contains(".disableAllCreatedBy(id, \"session-deleted:\$reason\")"))
        assertTrue(ProductionSources.read("sandbox/offload/ScheduledTaskOffloadHandler.kt").contains("creatorSessionId = callerSessionId,"))
    }
}
