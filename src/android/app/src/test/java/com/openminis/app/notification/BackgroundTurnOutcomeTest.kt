package com.openminis.app.notification

import com.openminis.app.ProductionSources
import com.openminis.app.notification.BackgroundTurnOutcome.COMPLETED
import com.openminis.app.notification.BackgroundTurnOutcome.FAILED
import com.openminis.app.notification.BackgroundTurnOutcome.INTERRUPTED
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.chat.ChatMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-bgnotif-outcome] A run that did not finish is no longer notified as
 * "completed" (iOS 387bca64c). On Android only a THROWN error made the
 * notification say anything else; every deliberate stop of an unfinished
 * turn (inline error + Resume, context full, Stop) read "completed".
 *
 * SessionActivityTracker runs on the JVM here (appContext null: service
 * start/stop are logged no-ops), as in Review0923OverlayTaskSlotsTest.
 */
class BackgroundTurnOutcomeTest {

    private val sid = "bgnotif-outcome-test"
    private val seen = mutableListOf<Pair<String, BackgroundTurnOutcome>>()

    @After
    fun cleanup() {
        SessionActivityTracker.setCompletionListener(null)
        SessionActivityTracker.setInactive(sid)
        SessionActivityTracker.clearOverlayTasks()
    }

    @Test
    fun `the decision follows iOS's order`() {
        // streamError, turnError, canResume, userStopped -> outcome
        val table = listOf(
            listOf(false, false, false, false) to COMPLETED,
            listOf(true, false, false, false) to FAILED,
            listOf(false, true, false, false) to FAILED,
            listOf(false, true, true, false) to FAILED, // an error with Resume is still a failure
            listOf(false, false, true, false) to INTERRUPTED, // context full: Resume, no error
            listOf(false, false, false, true) to INTERRUPTED, // Stop
            listOf(true, false, true, true) to FAILED,
        )
        table.forEach { (c, want) -> assertEquals(c.toString(), want, BackgroundTurnOutcome.decide(c[0], c[1], c[2], c[3])) }
    }

    private fun msg(id: String, role: String, error: String? = null, queued: Boolean = false) =
        ChatMessage(id = id, role = role, content = id, error = error, isQueued = queued)

    @Test
    fun `this turn's reply is found behind a notice or a queued prompt`() {
        val behindNotice = listOf(msg("u", "user"), msg("a", "assistant", error = "Stream dropped"), msg("s", "system"))
        assertEquals("Stream dropped", BackgroundTurnOutcome.currentTurnError(behindNotice))
        val behindQueued = listOf(msg("u", "user"), msg("a", "assistant", error = "Max tokens"), msg("q", "user", queued = true))
        assertEquals("Max tokens", BackgroundTurnOutcome.currentTurnError(behindQueued))
    }

    @Test
    fun `an earlier turn's error never counts`() {
        val earlier = listOf(msg("u1", "user"), msg("a1", "assistant", error = "old"), msg("u2", "user"), msg("a2", "assistant"))
        assertNull(BackgroundTurnOutcome.currentTurnError(earlier))
        // This turn has no reply yet.
        assertNull(BackgroundTurnOutcome.currentTurnError(listOf(msg("u1", "user"), msg("a1", "assistant", error = "old"), msg("u2", "user"))))
        assertNull(BackgroundTurnOutcome.currentTurnError(listOf(msg("u", "user"), msg("a", "assistant", error = "  "))))
        assertNull(BackgroundTurnOutcome.currentTurnError(emptyList()))
    }

    @Test
    fun `the tracker reports completed, failed and interrupted`() {
        SessionActivityTracker.setCompletionListener { s, o -> if (s == sid) seen += s to o }
        SessionActivityTracker.setActive(sid)
        SessionActivityTracker.setInactive(sid)
        SessionActivityTracker.setActive(sid)
        SessionActivityTracker.markStreamInterrupted(sid)
        SessionActivityTracker.setInactive(sid)
        SessionActivityTracker.setActive(sid)
        SessionActivityTracker.markStreamInterrupted(sid)
        SessionActivityTracker.markStreamError(sid) // an error wins
        SessionActivityTracker.setInactive(sid)
        assertEquals(listOf(COMPLETED, INTERRUPTED, FAILED), seen.map { it.second })
    }

    @Test
    fun `a late mark after Stop cannot leak into the next run`() {
        SessionActivityTracker.setCompletionListener { s, o -> if (s == sid) seen += s to o }
        SessionActivityTracker.setActive(sid)
        SessionActivityTracker.markStreamInterrupted(sid)
        SessionActivityTracker.setInactive(sid) // Stop deactivates at once
        // The cancelled job's finally runs afterwards and marks again.
        SessionActivityTracker.markStreamInterrupted(sid)
        SessionActivityTracker.markStreamError(sid)
        SessionActivityTracker.setInactive(sid) // no second notification
        SessionActivityTracker.setActive(sid) // the next run starts clean
        SessionActivityTracker.setInactive(sid)
        assertEquals(listOf(INTERRUPTED, COMPLETED), seen.map { it.second })
    }

    @Test
    fun `wiring - every run end marks its outcome, Stop is paused, the notification says so`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val ends = vm.split("SessionActivityTracker.setInactive(activeSessionId)")
        // Every finally that publishes the overlay excerpt marks the outcome first.
        val finallies = ends.dropLast(1).filter { it.trimEnd().endsWith("markRunOutcomeForNotification(activeSessionId)") }
        assertEquals(5, finallies.size)
        val stop = vm.substringAfter("SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)")
            .substringBefore("SessionActivityTracker.setInactive(activeSessionId)")
        assertTrue(stop.contains("SessionActivityTracker.markStreamInterrupted(activeSessionId)"))
        val fn = vm.substringAfter("private fun markRunOutcomeForNotification(sid: String) {").substringBefore("\n    }\n")
        assertTrue(fn.contains("currentTurnError(_messages.value) != null"))
        assertTrue(fn.contains("canResume = _canResume.value"))

        val notifier = ProductionSources.read("notification/BackgroundTaskNotifier.kt")
        assertTrue(notifier.contains("\"⏸ \$rawTitle\" to context.getString(R.string.notif_task_paused_body)"))
        assertTrue(notifier.contains("\"❌ \$rawTitle\" to context.getString(R.string.notif_task_failed_body)"))
        val app = ProductionSources.read("MinisApp.kt")
        assertTrue(app.contains("backgroundTaskNotifier.notifyTaskCompleted(sessionId, outcome)"))

        val res = File(ProductionSources.mainRoot(), "../../../../res").canonicalFile
        val en = File(res, "values/strings_bg_outcome.xml").readText()
        assertTrue(en.contains("<string name=\"notif_task_paused_body\">Task paused. Open the session to continue.</string>"))
        for (d in listOf("values-zh", "values-zh-rTW")) assertTrue(d, File(res, "$d/strings_bg_outcome.xml").exists())
    }
}
