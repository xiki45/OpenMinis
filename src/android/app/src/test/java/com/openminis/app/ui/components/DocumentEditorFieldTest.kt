package com.openminis.app.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-editor-keyboard-overlap] The keyboard must not cover the line
 * being edited in the Memory / Persona / Skill editors.
 */
class DocumentEditorFieldTest {

    // A 1080 x 2400 window, keyboard 900 px tall: its top edge is at y = 1500.
    private val window = 2400
    private val ime = 900
    private val least = 250

    @Test
    fun `an editor that reaches the bottom of the window loses exactly the keyboard's height`() {
        assertEquals(900, EditorCaret.imeOverlap(editorBottomInWindow = 2400f, windowHeight = window, imeBottom = ime, editorHeight = 2000, minVisible = least))
    }

    @Test
    fun `what sits below the editor is not subtracted twice`() {
        // A Scaffold's navigation-bar padding (126 px) or a footer below the editor.
        assertEquals(774, EditorCaret.imeOverlap(2274f, window, ime, 1900, least))
        // An editor that ends above the keyboard (tablet, split window, sheet) is not touched.
        assertEquals(0, EditorCaret.imeOverlap(1400f, window, ime, 900, least))
        assertEquals(0, EditorCaret.imeOverlap(1500f, window, ime, 900, least))
    }

    @Test
    fun `no keyboard, no change`() {
        assertEquals(0, EditorCaret.imeOverlap(2400f, window, imeBottom = 0, editorHeight = 2000, minVisible = least))
        assertEquals(0, EditorCaret.imeOverlap(2400f, windowHeight = 0, imeBottom = ime, editorHeight = 2000, minVisible = least))
        assertEquals("not laid out yet", 0, EditorCaret.imeOverlap(0f, window, ime, editorHeight = 0, minVisible = least))
    }

    @Test
    fun `an oversized inset still leaves a few lines to type in`() {
        // Gboard in pan mode has reported nearly the whole screen as the IME inset.
        assertEquals(2000 - least, EditorCaret.imeOverlap(2400f, window, imeBottom = 2265, editorHeight = 2000, minVisible = least))
        // Landscape: a short editor under a tall keyboard.
        assertEquals(500 - least, EditorCaret.imeOverlap(1080f, 1080, 700, editorHeight = 500, minVisible = least))
        // An editor already smaller than the minimum is left alone.
        assertEquals(0, EditorCaret.imeOverlap(2400f, window, ime, editorHeight = 200, minVisible = least))
    }

    @Test
    fun `the caret's line is kept in view with a margin, never above the top of the text`() {
        val caret = Rect(40f, 900f, 42f, 948f)
        assertEquals(Rect(40f, 828f, 42f, 1020f), EditorCaret.revealRect(caret, 72f))
        assertEquals("first line", Rect(0f, 0f, 2f, 120f), EditorCaret.revealRect(Rect(0f, 0f, 2f, 48f), 72f))
    }

    @Test
    fun `a selection survives the text being replaced from outside`() {
        assertEquals(TextRange(3, 5), EditorCaret.clamp(TextRange(3, 5), 10))
        assertEquals(TextRange(4, 4), EditorCaret.clamp(TextRange(7, 9), 4))
        assertEquals(TextRange(0, 0), EditorCaret.clamp(TextRange(2, 3), 0))
    }

    // -- wiring --

    private val field = ProductionSources.read("ui/components/DocumentEditorField.kt")

    @Test
    fun `the caret is brought into view when it moves, when the keyboard moves and when the text re-wraps`() {
        assertTrue(field.contains("LaunchedEffect(focused, field.selection, imeBottom, viewport, layout?.size) {"))
        assertTrue(field.contains("requester.bringIntoView(EditorCaret.revealRect(result.getCursorRect(caret), margin))"))
        assertTrue("only while editing", field.contains("if (!focused) return@LaunchedEffect"))
        assertTrue("the visible area ends at the keyboard", field.contains(".padding(bottom = with(density) { overlap.toDp() })"))
        // The requester sits inside the content padding, so its coordinates are the text's.
        assertTrue(field.indexOf(".padding(contentPadding)") < field.indexOf(".bringIntoViewRequester(requester)"))
    }

    @Test
    fun `the memory, persona and skill editors all use it`() {
        val users = mapOf(
            "ui/settings/MemoryManagementScreen.kt" to 1,   // Settings > Memory > a file
            "ui/settings/SkillsManagementScreen.kt" to 1,   // a skill's SKILL.md / other files
            "ui/settings/SoulSettingsScreen.kt" to 1,       // persona body
            "ui/chat/MemoryDetailScreens.kt" to 2,          // the in-chat memory sheet: file and written-memory editors
        )
        for ((path, count) in users) {
            val src = ProductionSources.read(path)
            assertEquals(path, count, Regex("""DocumentEditorField\(""").findAll(src).count())
        }
        // The persona body lives in a page that scrolls and pads for the keyboard itself.
        val soul = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
        assertTrue(soul.substringAfter("DocumentEditorField(").substringBefore("\n                )").contains("ownScroll = false,"))
        assertTrue(ProductionSources.read("ui/settings/SettingsComponents.kt").contains(".imePadding()"))
        // The full-screen editors no longer hand the document to a field with no keyboard handling.
        assertFalse(ProductionSources.read("ui/chat/MemoryDetailScreens.kt").contains("BasicTextField("))
    }

    @Test
    fun `the chat composer is not involved`() {
        for (path in listOf("ui/chat/ChatComposerWidgets.kt", "ui/chat/ChatScreen.kt")) {
            assertFalse(path, ProductionSources.read(path).contains("DocumentEditorField"))
        }
    }
}
