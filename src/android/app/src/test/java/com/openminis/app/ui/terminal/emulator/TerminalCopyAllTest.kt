package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [#451] Terminal "Copy All": [TerminalBuffer.plainText] / [TerminalEmulator.copyableText],
 * and the selection copy sharing its row export (the CJK padding-space fix).
 */
class TerminalCopyAllTest {

    private fun emulator(cols: Int = 20, rows: Int = 5, output: String) =
        TerminalEmulator(cols, rows).also { it.feed(output.toByteArray(Charsets.UTF_8)) }

    @Test
    fun rowsLoseTrailingBlanksAndEmptyRowsBelowTheOutputGo() {
        val em = emulator(output = "hello   \r\n\r\nworld\r\n")
        // The blank line between stays; the empty rows under "world" do not.
        assertEquals("hello\n\nworld", em.copyableText())
    }

    @Test
    fun wideCharactersCarryNoPaddingSpace() {
        val em = emulator(output = "中文 ok\r\n日本語😀x")
        assertEquals("中文 ok\n日本語😀x", em.copyableText())
    }

    @Test
    fun scrollbackComesBeforeTheScreen() {
        val lines = (1..10).map { "line $it" }
        val em = emulator(rows = 3, output = lines.joinToString("\r\n"))
        assertEquals(lines.joinToString("\n"), em.copyableText())
    }

    @Test
    fun anEmptyTerminalCopiesNothing() {
        assertEquals("", TerminalEmulator(20, 5).copyableText())
        assertEquals("", emulator(output = "   \r\n  ").copyableText())
    }

    @Test
    fun theAlternateScreenIsWhatIsCopiedWhileItIsOpen() {
        val em = emulator(output = "shell history\r\n\u001B[?1049h\u001B[Hvim screen")
        assertEquals("vim screen", em.copyableText())
        em.feed("\u001B[?1049l".toByteArray())
        assertEquals("shell history", em.copyableText())
    }

    @Test
    fun selectionCopyNoLongerSpacesOutCjk() {
        val em = emulator(output = "中文abc\r\nnext")
        // Row 0 from column 0 to the last column: "中" "trailer" "文" "trailer" "a" "b" "c".
        assertEquals("中文abc", em.getSelectedText(0, 0, 19, 0))
        assertEquals("中文abc\nnext", em.getSelectedText(0, 0, 3, 1))
    }
}
