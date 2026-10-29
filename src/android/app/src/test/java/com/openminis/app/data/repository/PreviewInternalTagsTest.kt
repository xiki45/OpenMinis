package com.openminis.app.data.repository

import com.openminis.app.scheduled.ScheduledTaskMarker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-preview-scheduled-tag-leak] The session list's last-message preview never
 * shows an app-authored envelope: a scheduled fire reads "Scheduled task ·
 * <label>" (user report: the raw "<scheduled_task id=… label=…>" showed when a
 * task fired), and reminders / attached-file listings / attachment captions
 * are stripped whatever their case and even when never closed.
 */
class PreviewInternalTagsTest {

    private fun textRow(vararg texts: String): String =
        JSONArray().apply { texts.forEach { put(JSONObject().put("type", "text").put("value", it)) } }.toString()

    private fun preview(vararg texts: String) = ChatRepository.extractTextPreview(textRow(*texts))

    private fun fire(label: String, prompt: String = "Say: tick", inserted: Boolean = false) = ScheduledTaskMarker(
        taskId = "11111111-2222-3333-4444-555555555555", label = label, nextFireAtMs = 1_791_024_000_000,
        prompt = prompt, firedAtMs = 1_791_023_940_000, fireIndex = 2, remaining = 1,
        triggerLabel = "loop(60s×3)", insertedMidTask = inserted,
    ).xml

    // --- Scheduled fires ------------------------------------------------------------

    @Test
    fun `a scheduled fire previews as the task label, never the envelope`() {
        val p = preview(fire("tick-3x"))!!
        assertEquals("Scheduled task · tick-3x", p)
        assertFalse(p.contains("<"))
    }

    @Test
    fun `a fire without a label previews its prompt, cleaned`() {
        assertEquals("Scheduled task · Check the build log", preview(fire("", prompt = "**Check** the\nbuild log")))
        assertEquals("Scheduled task", preview(fire("", prompt = "   ")))
    }

    @Test
    fun `an inserted fire and a fire behind a reminder read the same`() {
        assertEquals("Scheduled task · tick", preview(fire("tick", inserted = true)))
        assertEquals("Scheduled task · tick", preview("<system-reminder>queued</system-reminder>\n" + fire("tick")))
    }

    @Test
    fun `a damaged envelope still never shows the raw tag`() {
        assertEquals("Scheduled task", preview("<scheduled_task id=\"\" label=\"x\">\nSay: tick\n</scheduled_task>"))
        assertEquals("Scheduled task", preview("<SCHEDULED_TASK id=\"a\""))
        assertEquals("Scheduled task", InternalTags.scheduledPreviewLine("<scheduled_task", "Scheduled task") { it })
    }

    // --- Other envelopes --------------------------------------------------------------

    @Test
    fun `system reminders go whatever their case, closed or not`() {
        assertEquals("hello", preview("<system-reminder>be brief</system-reminder>hello"))
        assertEquals("hello", preview("<SYSTEM-REMINDER>x</System-Reminder>hello"))
        assertEquals("hello", preview("<system-reminder data=\"1\">multi\nline</system-reminder>\nhello"))
        assertEquals("hello", preview("hello <system-reminder>never closed, up to the end"))
        assertEquals("hello", preview("hello</system-reminder>"))
        assertEquals("a b", preview("<system-reminder>1</system-reminder>a <system-reminder>2</system-reminder>b"))
    }

    @Test
    fun `a row that is only envelopes previews as nothing, falling through to later parts`() {
        assertNull(preview("<system-reminder>only a nudge</system-reminder>"))
        assertEquals("real text", preview("<system-reminder>nudge</system-reminder>", "real text"))
    }

    @Test
    fun `attached-file listings and attachment captions are dropped`() {
        assertEquals("look at this", preview("look at this\n<user-attached-files>\n/var/minis/attachments/a.pdf\n</user-attached-files>"))
        assertEquals("look", preview("look <USER-ATTACHED-FILES>/x.pdf"))
        assertEquals("what is it?", preview("[attached image: photo.jpg] what is it?"))
        assertEquals("see", preview("see [image omitted to save context (2 images)]"))
    }

    @Test
    fun `ordinary text is unchanged`() {
        assertEquals("Hello world, this is a normal reply.", preview("Hello world, this is a normal reply."))
        assertEquals("Use a < b and c > d", preview("Use a < b and c > d"))
        assertEquals("Title body", preview("## Title\n**body**"))
    }

    @Test
    fun `stripSystemReminders, shared with search and offload, is the tolerant one`() {
        assertEquals("x", ChatRepository.stripSystemReminders("<System-Reminder>a</system-reminder>x"))
        assertEquals("x", ChatRepository.stripSystemReminders("x<system-reminder>open"))
        assertTrue(ChatRepository.stripSystemReminders("plain").equals("plain"))
    }
}
