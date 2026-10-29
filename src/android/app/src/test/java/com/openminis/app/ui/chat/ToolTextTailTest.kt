package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.ToolTextTail.ELLIPSIS
import com.openminis.app.ui.chat.ToolTextTail.MAX_LINE_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-tool-text-line-cap] Alice Q4: one 200 KB line with no newline in a
 * shell tool's output froze the main thread for 30 s inside the preview tile's
 * `Text` (LineBreaker / MeasuredText / EmojiCompat all walk the whole
 * paragraph; maxLines does not stop them). The tile cut by lines only, so the
 * whole line went through. These lock the per-line and total bounds.
 */
class ToolTextTailTest {

    private val n = 12
    private val bound = n * (MAX_LINE_CHARS + 2)

    private fun assertBounded(out: String) =
        assertTrue("output ${out.length} chars exceeds $bound", out.length <= bound)

    private fun assertNoBrokenSurrogate(out: String) {
        out.forEachIndexed { i, c ->
            if (Character.isHighSurrogate(c)) assertTrue("dangling high surrogate at $i", i + 1 < out.length && Character.isLowSurrogate(out[i + 1]))
            if (Character.isLowSurrogate(c)) assertTrue("dangling low surrogate at $i", i > 0 && Character.isHighSurrogate(out[i - 1]))
        }
    }

    @Test fun `50k single line is cut to the line cap and keeps the tail`() {
        val text = "a".repeat(50_000 - 3) + "END"
        val out = ToolTextTail.lastLines(text, n)
        assertBounded(out)
        assertTrue(out.startsWith(ELLIPSIS))
        assertTrue(out.endsWith("END"))
        assertEquals(MAX_LINE_CHARS + ELLIPSIS.length, out.length)
    }

    @Test fun `500k single line is cut to the line cap and keeps the tail`() {
        val text = "x".repeat(500_000) + "tail-marker"
        val out = ToolTextTail.lastLines(text, n)
        assertBounded(out)
        assertTrue(out.endsWith("tail-marker"))
    }

    @Test fun `12 long lines are each cut, total stays bounded`() {
        val text = (1..30).joinToString("\n") { i -> "L$i:" + "z".repeat(20_000) + "#$i" }
        val out = ToolTextTail.lastLines(text, n)
        assertBounded(out)
        assertTrue(out.endsWith("#30"))
        assertFalse(out.contains("#18\n"))
        out.lines().forEach { assertTrue(it.length <= MAX_LINE_CHARS + ELLIPSIS.length) }
    }

    @Test fun `scan window bounds work for many short lines plus one huge line`() {
        val text = "y".repeat(1_000_000) + "\nshort\n"
        val out = ToolTextTail.lastLines(text, n)
        assertBounded(out)
        assertTrue(out.endsWith("short"))
        assertTrue(out.lines().first().startsWith(ELLIPSIS))
    }

    @Test fun `multi-byte characters are not split into half surrogate pairs`() {
        // Each emoji is two UTF-16 units; any odd cut would leave half a pair.
        val text = "\uD83D\uDE00".repeat(60_000) + "\u4E2D\u6587"
        for (cap in listOf(MAX_LINE_CHARS, MAX_LINE_CHARS - 1, 7)) {
            val out = ToolTextTail.lastLines(text, n, maxLineChars = cap)
            assertTrue(out.length <= cap + ELLIPSIS.length)
            assertTrue(out.endsWith("\u4E2D\u6587"))
            assertNoBrokenSurrogate(out)
        }
        val head = ToolTextTail.head("\uD83D\uDE00".repeat(1_000), 7)
        assertTrue(head.length <= 7 + ELLIPSIS.length)
        assertNoBrokenSurrogate(head)
    }

    @Test fun `CRLF endings do not leave a carriage return at line ends`() {
        val text = "one\r\ntwo\r\n" + "w".repeat(3_000) + "\r\n"
        val out = ToolTextTail.lastLines(text, n)
        assertBounded(out)
        assertFalse(out.contains('\r'))
        assertEquals(listOf("one", "two"), out.lines().take(2))
    }

    @Test fun `lone carriage returns in a progress line are still bounded`() {
        val text = (1..20_000).joinToString("\r") { "progress $it%" }
        val out = ToolTextTail.lastLines(text, n)
        assertBounded(out)
        assertTrue(out.endsWith("progress 20000%"))
    }

    @Test fun `empty input and non-positive n give empty output`() {
        assertEquals("", ToolTextTail.lastLines("", n))
        assertEquals("", ToolTextTail.lastLines("abc", 0))
        assertEquals("", ToolTextTail.head(""))
    }

    @Test fun `a line exactly at the cap is kept whole without an ellipsis`() {
        val line = "e".repeat(MAX_LINE_CHARS)
        assertEquals(line, ToolTextTail.lastLines(line, n))
        assertEquals(line, ToolTextTail.lastLines("$line\n", n))
        val over = line + "f"
        assertEquals(ELLIPSIS + over.takeLast(MAX_LINE_CHARS), ToolTextTail.lastLines(over, n))
        assertEquals(line, ToolTextTail.head(line))
        assertEquals(line + ELLIPSIS, ToolTextTail.head(over))
    }

    @Test fun `short output keeps the old last-N-lines behaviour`() {
        val text = (1..20).joinToString("\n") { "line $it" } + "\n"
        assertEquals((9..20).joinToString("\n") { "line $it" }, ToolTextTail.lastLines(text, n))
        assertEquals("a\nb", ToolTextTail.lastLines("a\nb", n))
        assertEquals("\nx", ToolTextTail.lastLines("\nx", n))
    }

    @Test fun `head keeps the start and caps a huge command`() {
        val cmd = "python3 -c '" + "q".repeat(300_000) + "'"
        val out = ToolTextTail.head(cmd)
        assertTrue(out.startsWith("python3 -c '"))
        assertEquals(MAX_LINE_CHARS + ELLIPSIS.length, out.length)
    }

    @Test fun `capLongLines leaves normal output untouched`() {
        val text = (1..80).joinToString("\n") { "row $it " + "k".repeat(100) }
        assertTrue(ToolTextTail.capLongLines(text) === text)
        assertTrue(ToolTextTail.capLongLines("") === "")
    }

    @Test fun `capLongLines cuts a huge line but keeps both ends and the other lines`() {
        val cap = ToolTextTail.DETAIL_MAX_LINE_CHARS
        val text = "before\nHEAD" + "m".repeat(500_000) + "TAIL\nafter\n"
        val out = ToolTextTail.capLongLines(text)
        val lines = out.split("\n")
        assertEquals(listOf("before", "after", ""), listOf(lines[0], lines[2], lines[3]))
        assertTrue(lines[1].startsWith("HEAD"))
        assertTrue(lines[1].endsWith("TAIL"))
        assertTrue(lines[1].contains(ELLIPSIS))
        assertTrue(lines[1].length <= cap + 3)
        val exact = "e".repeat(cap)
        assertTrue(ToolTextTail.capLongLines(exact) === exact)
    }

    @Test fun `capLongLines does not split surrogate pairs`() {
        val out = ToolTextTail.capLongLines("\uD83D\uDE00".repeat(10_000), maxLineChars = 101)
        assertTrue(out.length <= 101 + 3)
        assertNoBrokenSurrogate(out)
    }
}
