package com.openminis.app.ui.chat.voice

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-voice-dblclick-select] Double-tapping a word in the voice
 * transcript's edit mode selects it, as on iOS. Compose text fields count
 * clicks for the mouse only, so the editor is a platform EditText.
 */
class VoiceTranscriptEditorTest {

    private val panel = ProductionSources.read("ui/chat/voice/InlineVoiceInputPanel.kt")
    private val editor = ProductionSources.read("ui/chat/voice/VoiceTranscriptEditor.kt")

    @Test
    fun `edit mode uses the platform editor, display mode keeps double-tap-to-edit`() {
        val area = panel.substringAfter("private fun TranscriptArea(").substringBefore("\n}\n")
        val editing = area.substringAfter("if (isEditing) {").substringBefore("} else {")
        assertTrue(editing.contains("VoiceTranscriptEditor("))
        assertFalse("no Compose text field in edit mode", editing.contains("BasicTextField("))
        // Outside edit mode a double tap still opens the editor.
        assertTrue(area.substringAfter("} else {").contains("onDoubleTap = { onBeginEdit() },"))
    }

    @Test
    fun `the editor is an EditText that reports edits, follows outside changes and owns the keyboard`() {
        assertTrue(editor.contains("EditText(context).apply {"))
        assertTrue("every edit reaches the caller", editor.contains("latestOnChange.value(now)"))
        assertTrue("a Clear from outside replaces the text", editor.contains("if (edit.tag != value) {"))
        assertTrue("no feedback loop from its own setText", editor.contains("edit.tag = value\n                edit.setText(value)"))
        assertTrue(editor.contains("showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)"))
        assertTrue(editor.contains("hideSoftInputFromWindow(edit.windowToken, 0)"))
        assertTrue("still multi-line", editor.contains("InputType.TYPE_TEXT_FLAG_MULTI_LINE"))
    }
}
