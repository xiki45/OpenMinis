package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-bridge-message-ui-leak-android] Verifies ChatMessage.isInternalBridge —
 * the belt-and-suspenders classifier the uiMessages sink uses to keep the
 * internal role-alternation bridge out of the chat UI. Mirrors the intent of
 * iOS's isInternalBridge coverage.
 */
class InternalBridgeFilterTest {

    private val currentBridge =
        "(Interrupted mid-task by a new user message. Decide based on the new " +
            "message and overall context whether the prior task should continue — do " +
            "not forget or abandon it unless the user explicitly says to stop, or the " +
            "new message makes clear it is no longer needed.)"

    private val oldBridge =
        "(Interrupted mid-task to handle your new message. Will return to the prior task after.)"

    private fun msg(role: String, content: String) =
        ChatMessage(id = "x", role = role, content = content)

    @Test
    fun `current bridge wording is flagged`() {
        assertTrue(msg("assistant", currentBridge).isInternalBridge)
    }

    @Test
    fun `old bridge wording is still flagged`() {
        // A message produced by an older build carries the previous wording;
        // it must still be recognized so it can't leak after the reword.
        assertTrue(msg("assistant", oldBridge).isInternalBridge)
    }

    @Test
    fun `whitespace drift around the bridge text is tolerated`() {
        assertTrue(msg("assistant", "  $currentBridge\n").isInternalBridge)
    }

    @Test
    fun `a bridge in a non-assistant role is not flagged`() {
        // The bridge is only ever emitted as an assistant message; anything
        // else with the same text is not the internal bridge.
        assertFalse(msg("user", currentBridge).isInternalBridge)
    }

    @Test
    fun `an ordinary assistant message is not flagged`() {
        assertFalse(msg("assistant", "Sure, here is the answer.").isInternalBridge)
        assertFalse(msg("assistant", "").isInternalBridge)
    }

    // [T-bridge-to-reminder] The bridge is no longer written; the note that
    // replaced it is a user-side <system-reminder>.

    @Test
    fun `the interrupt note is a system-reminder that does not pose as the user`() {
        val note = ChatMessage.QUEUED_INTERRUPT_REMINDER_TEXT
        assertTrue(note.startsWith("<system-reminder>"))
        assertTrue(note.endsWith("</system-reminder>"))
        assertTrue(note.contains("not something the user wrote"))
        assertTrue(note.contains("do not forget or abandon it unless the user explicitly asks you to stop"))
        assertTrue(note.contains("Do not repeat or mention this note."))
        // It is not a bridge: nothing would hide it as one.
        assertFalse(ChatMessage.isInternalBridgeText(note))
    }

    @Test
    fun `the interrupt note is word for word the iOS one`() {
        // Walk up to the repo root; skip on an Android-only checkout.
        var dir: java.io.File? = java.io.File("").absoluteFile
        var ios: java.io.File? = null
        while (dir != null && ios == null) {
            ios = java.io.File(dir, "src/ios/Agent/Chat/ChatStoreModels.swift").takeIf { it.exists() }
            dir = dir.parentFile
        }
        if (ios == null) return
        assertTrue(ios.readText().contains("\"" + ChatMessage.QUEUED_INTERRUPT_REMINDER_TEXT + "\""))
    }

    @Test
    fun `a message that merely mentions interruption is not flagged`() {
        // Substring / fuzzy matches must not trip the filter — only the exact
        // known bridge strings (after trim) count.
        assertFalse(
            msg("assistant", "The task was interrupted mid-task, so I stopped.").isInternalBridge,
        )
    }
}
