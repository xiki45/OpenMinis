package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thinking-hint-align] The "showing last 8K of 10K characters" hint above a
 * truncated thinking block lines up with the thinking text: the text has no
 * horizontal inset, so the hint must not have one either.
 */
class ThinkingTruncatedHintAlignmentTest {

    @Test
    fun `truncation hint has no horizontal inset of its own`() {
        val src = ProductionSources.read("ui/chat/ChatAssistantMessageUI.kt")
        val start = src.indexOf("R.string.thinking_truncated_hint")
        assertTrue("hint still rendered here", start >= 0)
        val hint = src.substring(start).substringBefore("Text(\n")
        assertTrue(hint.contains("Modifier.padding(vertical = 4.dp)"))
        assertFalse("indented past the thinking text", hint.contains("horizontal = 12.dp"))
    }
}
