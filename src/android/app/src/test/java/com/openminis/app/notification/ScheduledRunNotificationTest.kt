package com.openminis.app.notification

import com.openminis.app.ProductionSources
import com.openminis.app.data.db.MessageHeadRow
import com.openminis.app.scheduled.ScheduledTaskMarker
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-port-sched-notif-single] A scheduled background run posts ONE
 * notification, from BackgroundTaskNotifier, and a task's repeated runs
 * replace it in place (iOS b0c0c87ea). ScheduledAgentRunner's second,
 * hard-coded English notification - which ignored the Task Notifications
 * toggle and the foreground state and was never cleared - is gone.
 */
class ScheduledRunNotificationTest {

    private fun text(value: String) = JSONObject().put("type", "text").put("value", value)
    private fun toolResult(id: String) = JSONObject().put("type", "toolResult")
        .put("value", JSONObject().put("toolUseId", id).put("output", "ok").put("success", true))
    private fun toolUse(id: String) = JSONObject().put("type", "toolUse")
        .put("value", JSONObject().put("id", id).put("name", "shell_execute").put("input", "{}"))

    private fun row(role: String, vararg parts: JSONObject) =
        MessageHeadRow(id = "r${parts.hashCode()}", role = role, partsJson = JSONArray(parts.toList()).toString())

    private fun envelope(taskId: String) = ScheduledTaskMarker(
        taskId = taskId, label = "Check mail", nextFireAtMs = 1_800_000_000_000, prompt = "check mail",
    ).xml

    private fun scan(rowsNewestFirst: List<MessageHeadRow>): String? = runBlocking {
        ScheduledRunNotification.taskIdOpeningTurn { rowsNewestFirst.getOrNull(it) }
    }

    @Test
    fun `a run opened by a fire is attributed to its task through tool turns`() {
        val rows = listOf(
            row("assistant", text("Done.")),
            row("user", toolResult("t2")),
            row("assistant", toolUse("t2")),
            row("user", toolResult("t1")),
            row("assistant", toolUse("t1")),
            row("user", text(envelope("task-A"))),
            row("assistant", text("older reply")),
            row("user", text(envelope("task-OLD"))),
        )
        assertEquals("task-A", scan(rows))
    }

    @Test
    fun `an ordinary turn is not attributed, even after earlier fires`() {
        val rows = listOf(
            row("assistant", text("Sure.")),
            row("user", text("what's the weather")),
            row("assistant", text("fired reply")),
            row("user", text(envelope("task-A"))),
        )
        assertNull(scan(rows))
    }

    @Test
    fun `an envelope next to another part counts, a quoted one does not`() {
        assertEquals(
            "task-B",
            scan(listOf(row("user", text("<system-reminder>queued</system-reminder>"), text("  \n" + envelope("task-B"))))),
        )
        assertNull(scan(listOf(row("user", text("look at this: " + envelope("task-B"))))))
    }

    @Test
    fun `empty transcript, unparseable parts and the scan cap give null`() {
        assertNull(scan(emptyList()))
        assertNull(ScheduledRunNotification.taskIdIn("not json"))
        // Only tool-result rows within the cap: no turn start found.
        val endless = List(ScheduledRunNotification.MAX_SCAN_ROWS + 5) { row("user", toolResult("t$it")) } +
            row("user", text(envelope("far")))
        assertNull(scan(endless))
    }

    @Test
    fun `the tag is one per task and absent for ordinary completions`() {
        assertEquals("job-task-A", ScheduledRunNotification.tag("task-A"))
        assertNull(ScheduledRunNotification.tag(null))
        // Same task -> same tag on every run (in place); other task -> its own.
        assertEquals(ScheduledRunNotification.tag("x"), ScheduledRunNotification.tag("x"))
        assertTrue(ScheduledRunNotification.tag("x") != ScheduledRunNotification.tag("y"))
    }

    // --- Wiring ---------------------------------------------------------------

    @Test
    fun `the runner posts no notification of its own`() {
        val runner = ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        assertFalse(runner.contains("postCompletionNotification"))
        assertFalse(runner.contains("NotificationCompat"))
        assertFalse(runner.contains(".notify("))
        assertFalse(runner.contains("\"Minis: "))
        assertFalse(runner.contains("\"(no response)\""))
        assertTrue(runner.contains(".take(200).ifBlank { null }"))
    }

    @Test
    fun `the notifier keeps its gates and posts with the per-task tag`() {
        val n = ProductionSources.read("notification/BackgroundTaskNotifier.kt")
        val gates = n.indexOf("if (!backgroundSettings.taskNotificationsEnabled.value) return")
        val fg = n.indexOf("if (isAppForeground()) return")
        val lookup = n.indexOf("ScheduledRunNotification.taskIdOpeningTurn")
        assertTrue(gates in 0 until lookup && fg in 0 until lookup)
        assertTrue(n.contains("postNotification(sessionId, title, body, ScheduledRunNotification.tag(taskId))"))
        assertTrue(n.contains("nm.notify(tag, sessionId.hashCode(), notification)"))
        // Cleared on foreground by (tag, id), so tagged entries go too.
        assertTrue(n.contains("nm.cancel(sb.tag, sb.id)"))
    }

    @Test
    fun `the English-only Scheduled Tasks channel is no longer created`() {
        val m = ProductionSources.read("scheduled/ScheduledTaskManager.kt")
        assertFalse(m.contains("\"Scheduled Tasks\""))
        assertFalse(m.contains("createNotificationChannel"))
        assertTrue(m.contains("deleteNotificationChannel(OBSOLETE_CHANNEL_ID)"))
    }
}
