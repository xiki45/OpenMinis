package com.openminis.app.ui.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-tool-title-reminder] The soft note for a missing tool_title: text,
 * detection and display stripping. The 1st/11th/21st cadence is tested in
 * ToolLoopDetectorTest, where the counting lives. Mirrors iOS
 * ToolTitleReminderTests.
 */
class ToolTitleReminderTest {

    @Test fun detectsMissingTitle() {
        assertTrue(ToolTitleReminder.isMissingTitle(JSONObject().put("command", "ls")))
        assertTrue(ToolTitleReminder.isMissingTitle(JSONObject().put("tool_title", "  \n")))
        assertTrue(ToolTitleReminder.isMissingTitle(JSONObject().put("tool_title", 42)))
        assertFalse(ToolTitleReminder.isMissingTitle(JSONObject().put("tool_title", "List files")))
    }

    @Test fun onlyToolsDeclaringATitle() {
        assertTrue(ToolTitleReminder.declaresTitle(listOf("tool_title", "command")))
        assertFalse(ToolTitleReminder.declaresTitle(listOf("query", "limit")))
    }

    @Test fun appendsAfterOutputAndStripsBackExactly() {
        val out = "total 3\nfoo\nbar"
        val with = ToolTitleReminder.appending(out)
        assertTrue(with.startsWith("$out\n\n<system-reminder>"))
        assertTrue(with.contains(ToolTitleReminder.MARKER))
        assertTrue(with.endsWith("</system-reminder>"))
        assertEquals(ToolTitleReminder.TEXT, ToolTitleReminder.appending(""))
        assertEquals(out, ToolTitleReminder.stripForDisplay(with))
    }

    @Test fun stripLeavesOtherRemindersAlone() {
        val cancel = "<system-reminder>The user cancelled this operation.</system-reminder>\npartial"
        assertEquals(cancel, ToolTitleReminder.stripForDisplay(cancel))
        assertEquals(cancel, ToolTitleReminder.stripForDisplay(ToolTitleReminder.appending(cancel)))
    }

    @Test fun exactShortWording() {
        assertEquals(
            "<system-reminder>[minis:tool-title-reminder] This is a soft reminder. " +
                "This tool call has no `tool_title`; please include a short one next time.</system-reminder>",
            ToolTitleReminder.TEXT,
        )
    }

    /** Same text and marker as iOS, so a synced session is recognised on both. */
    @Test fun textMatchesIOS() {
        val ios = File("../../ios/Agent/Chat/AIChatViewModel+ConcurrentTools.swift")
        if (!ios.exists()) return
        val src = ios.readText()
        assertTrue(src.contains("static let marker = \"${ToolTitleReminder.MARKER}\""))
        val iosText = Regex("static let text = \"(.*)\"").find(src)!!.groupValues[1]
            .replace("\\(marker)", ToolTitleReminder.MARKER)
        assertEquals(iosText, ToolTitleReminder.TEXT)
    }
}
