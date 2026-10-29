package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.openminis.app.ProductionSources
import com.openminis.app.ui.chat.BlankTapGeometry.Band
import com.openminis.app.ui.chat.BlankTapGeometry.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-414-usage-tap-band] openminis/openminis#414: only a blank tap within five
 * lines of text above the usage capsule reveals it; blank taps anywhere else in
 * the reply do nothing.
 */
class UsageTapBandTest {

    // Pixel-4a-like density (1dp = 2.75px). An 8-line text node, lines 24dp tall,
    // 300dp wide; every line ends at 120dp, so x = 200dp is clear of text.
    private val d = 2.75f
    private fun dp(v: Float) = v * d
    private val lineH = dp(24f)
    private fun lines(n: Int, rightDp: Float = 120f) = (0 until n).map { Rect(0f, lineH * it, dp(rightDp), lineH * (it + 1)) }
    private val eight = lines(8)
    private val blankX = dp(200f)

    /** The band when this node is the block's last row: its bottom is the capsule's position. */
    private fun bandOf(lineCount: Int, lineHeight: Float = lineH) = UsageTapBand.band(lineHeight * lineCount, lineHeight)

    private fun decide(
        y: Float, band: Band, x: Float = blankX, ls: List<Rect> = eight, links: List<Rect> = emptyList(),
        selection: Boolean = false, long: Boolean = false,
    ) = BlankTapGeometry.decide(Offset(x, y), ls, links, d, selection, long, band)

    private fun midOfLine(i: Int) = lineH * i + lineH / 2

    // ── The band itself ────────────────────────────────────────────────────

    @Test fun `the band is five lines high and ends at the capsule`() {
        val b = bandOf(8) as Band.Range
        assertEquals(lineH * 8, b.bottom, 0.001f)
        assertEquals("top = bottom - 5 x the line height", lineH * 3, b.top, 0.001f)
        assertEquals(5, UsageTapBand.LINES)
    }

    @Test fun `blank inside the band reveals, blank above it does nothing`() {
        val band = bandOf(8)
        for (i in 3..7) assertEquals("line $i is one of the last five", Kind.BLANK, decide(midOfLine(i), band))
        for (i in 0..2) assertEquals("line $i is above the band", Kind.OUTSIDE_BAND, decide(midOfLine(i), band))
    }

    @Test fun `one pixel either side of the band's edges`() {
        val b = bandOf(8) as Band.Range
        assertEquals(Kind.OUTSIDE_BAND, decide(b.top - 1f, b))
        assertEquals(Kind.BLANK, decide(b.top, b))
        assertEquals(Kind.BLANK, decide(b.top + 1f, b))
        assertEquals(Kind.BLANK, decide(b.bottom - 1f, b))
        assertEquals(Kind.BLANK, decide(b.bottom, b))
        assertEquals(Kind.OUTSIDE_BAND, decide(b.bottom + 1f, b))
    }

    @Test fun `a block shorter than five lines responds over its whole height`() {
        val three = lines(3)
        val band = bandOf(3) as Band.Range
        assertTrue("the band starts above the block's top", band.top < 0f)
        for (i in 0..2) assertEquals(Kind.BLANK, decide(midOfLine(i), band, ls = three))
    }

    @Test fun `inside the band the old rules still decide - text, links, selection, long press`() {
        val band = bandOf(8)
        val y = midOfLine(6)
        val link = Rect(dp(40f), lineH * 6 + dp(2f), dp(90f), lineH * 6 + dp(22f))
        assertEquals("on the line's text", Kind.TEXT, decide(y, band, x = dp(60f)))
        assertEquals("just past the end of the line", Kind.TEXT, decide(y, band, x = dp(120f + 10f)))
        assertEquals("on a link", Kind.TEXT, decide(y, band, x = dp(60f), links = listOf(link)))
        assertEquals("beside a link", Kind.NEAR_INTERACTIVE, decide(y, band, x = dp(90f + 10f), links = listOf(link)))
        assertEquals(Kind.SUPPRESSED, decide(y, band, selection = true))
        assertEquals(Kind.SUPPRESSED, decide(y, band, long = true))
    }

    @Test fun `outside the band nothing else changes - a link still opens, a selection still wins`() {
        val band = bandOf(8)
        val y = midOfLine(1)
        val link = Rect(dp(40f), lineH + dp(2f), dp(90f), lineH + dp(22f))
        assertEquals("a link above the band is still a link", Kind.TEXT, decide(y, band, x = dp(60f), links = listOf(link)))
        assertEquals(Kind.NEAR_INTERACTIVE, decide(y, band, x = dp(90f + 10f), links = listOf(link)))
        assertEquals("text is text", Kind.TEXT, decide(y, band, x = dp(60f)))
        assertEquals(Kind.SUPPRESSED, decide(y, band, selection = true))
        assertEquals(Kind.SUPPRESSED, decide(y, band, long = true))
    }

    @Test fun `a text with no band never reveals, an unrestricted one behaves as before`() {
        for (i in 0..7) {
            assertEquals(Kind.OUTSIDE_BAND, decide(midOfLine(i), Band.None))
            assertEquals(Kind.BLANK, decide(midOfLine(i), Band.Anywhere))
        }
        assertEquals("the default is the pre-#414 rule", Kind.BLANK,
            BlankTapGeometry.decide(Offset(blankX, midOfLine(0)), eight, emptyList(), d, false, false))
    }

    @Test fun `the band follows the line height - larger font, taller band`() {
        val small = bandOf(8, lineHeight = 40f) as Band.Range
        val large = bandOf(8, lineHeight = 66f) as Band.Range
        assertEquals(200f, small.bottom - small.top, 0.001f)
        assertEquals(330f, large.bottom - large.top, 0.001f)
    }

    @Test fun `an earlier row of the same block - the band is measured from the capsule, not from that row`() {
        // The tapped row is 3 lines tall; the block's last row (2 lines) and a
        // 22px gap sit below it, so the capsule is 66*2+22 px under this row's bottom.
        val rowLines = lines(3)
        val anchor = lineH * 3 + 22f + lineH * 2
        val band = UsageTapBand.band(anchor, lineH) as Band.Range
        // 5 lines up from the capsule: the last row's 2 lines, the gap, and the
        // lower part of this row.
        assertEquals(anchor - 5 * lineH, band.top, 0.001f)
        assertEquals(Kind.BLANK, decide(midOfLine(2), band, ls = rowLines))
        assertEquals(Kind.BLANK, decide(midOfLine(1), band, ls = rowLines))
        assertEquals("further than five lines from the capsule", Kind.OUTSIDE_BAND, decide(lineH * 0.2f, band, ls = rowLines))
    }

    @Test fun `an unknown anchor or line height gives no band`() {
        assertEquals(Band.None, UsageTapBand.band(null, 66f))
        assertEquals(Band.None, UsageTapBand.band(Float.NaN, 66f))
        assertEquals(Band.None, UsageTapBand.band(500f, 0f))
        assertEquals(Band.None, UsageTapBand.band(500f, Float.NaN))
    }

    // ── The line height unit ───────────────────────────────────────────────

    @Test fun `the unit is the line height the text was laid out with, not a constant`() {
        // A middle-of-paragraph-sized last line: box = line height.
        assertEquals(66f, UsageTapBand.lastLineHeightPx(132f, 198f, 66f), 0.001f)
        // Trim.Both: the last line's box has no leading below it (55 of 66), and
        // in a one-line paragraph none above either (44 of 66). Still a full line.
        assertEquals(66f, UsageTapBand.lastLineHeightPx(55f, 110f, 66f), 0.001f)
        assertEquals(66f, UsageTapBand.lastLineHeightPx(0f, 44f, 66f), 0.001f)
        // A larger font size setting: the style value scales, so does the unit.
        assertEquals(85.8f, UsageTapBand.lastLineHeightPx(0f, 57f, 85.8f), 0.001f)
    }

    @Test fun `lines of different sizes - the last line's height is the unit`() {
        // No line height in the style: the last line's own box (35), not the
        // taller lines above it (90, 40).
        assertEquals(35f, UsageTapBand.lastLineHeightPx(130f, 165f, null), 0.001f)
        // A last line taller than the style says (a fallback font): its box.
        assertEquals(70f, UsageTapBand.lastLineHeightPx(0f, 70f, 66f), 0.001f)
        // A heading as the block's last text is laid out with its own, larger
        // line height: the band is five of THOSE lines.
        val heading = UsageTapBand.band(500f, UsageTapBand.lastLineHeightPx(0f, 80f, 96f)) as Band.Range
        assertEquals(480f, heading.bottom - heading.top, 0.001f)
        assertEquals(0f, UsageTapBand.lastLineHeightPx(null, null, null), 0f)
    }

    // ── Which text block has a band: fed through the real row builder ──────

    private var n = 0
    private fun block(kind: String, content: String = "") = AssistantBlock(id = "b${n++}", kind = kind, content = content)
    private fun text(s: String) = block("text", s)
    private fun tool() = AssistantBlock(id = "t${n++}", kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS, toolName = "shell_execute")
    private fun rows(vararg blocks: AssistantBlock, streaming: Boolean = false) =
        buildFlatChatItems(listOf(ChatMessage(id = "m", role = "assistant", content = "", toolBlocks = blocks.toList(), isStreaming = streaming)))
            .filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

    @Test fun `only the text block that ends the message has a band`() {
        assertEquals(UsageTapBand.endsWithText("text", true), true)
        assertEquals(UsageTapBand.endsWithText("text", false), false)
        assertEquals(UsageTapBand.endsWithText("tool_use", true), false)
        assertEquals(UsageTapBand.endsWithText("thinking", true), false)
        assertEquals(UsageTapBand.endsWithText(null, false), false)

        // tool, then the final reply: the reply has the band.
        val a = rows(text("Let me check."), tool(), text("Done.\n\nAll good."))
        val first = a.filter { it.rawText.startsWith("Let me") }
        val last = a.filter { !it.rawText.startsWith("Let me") }
        assertTrue(first.isNotEmpty() && last.isNotEmpty())
        assertTrue("the earlier text block has none", first.none { it.inFinalTextBlock })
        assertTrue("every row of the final text block is in the band's block", last.all { it.inFinalTextBlock })
        assertEquals("exactly one of them is the capsule's anchor row", 1, last.count { it.isLastBlockOfMessage })
        assertTrue(last.last().isLastBlockOfMessage)
    }

    @Test fun `a reply that ends with a tool or thinking block has no response zone, and no earlier text is borrowed`() {
        assertTrue(rows(text("I will run it."), tool()).none { it.inFinalTextBlock })
        assertTrue(rows(text("Answer."), block("thinking", "hmm")).none { it.inFinalTextBlock })
        assertTrue("an empty trailing text block renders nothing and does not count",
            rows(text("Answer."), tool(), text("")).none { it.inFinalTextBlock })
        // Two text blocks in a row: only the second, which the capsule follows.
        val two = rows(text("First."), text("Second."))
        assertEquals(listOf(false, true), two.map { it.inFinalTextBlock })
    }

    @Test fun `while streaming the final text is whatever is last at the time of the tap`() {
        val t = text("Working on it.")
        assertTrue(rows(t, streaming = true).all { it.inFinalTextBlock })
        // A tool call lands after it: the same text block no longer ends the message.
        val after = rows(t, tool(), streaming = true)
        assertTrue(after.none { it.inFinalTextBlock })
        assertFalse("the row is a different value, so the list re-renders it",
            rows(t, streaming = true).single() == after.single())
    }

    // ── Wiring guards ──────────────────────────────────────────────────────

    @Test fun `the tap handler asks the registry for the band at tap time and passes it to the decision`() {
        val src = ProductionSources.read("ui/chat/StreamingMarkdownText.kt")
        val tap = src.substringAfter("val tapModifier = if (hasUrlAnnotation").substringBefore("    } else Modifier\n    Text(")
        assertTrue(tap.contains("bandRegistry.bandFor(bandRow, layoutCoordinatesHolder[0], result)"))
        assertTrue(tap.contains("band = band,"))
        // In a chat list a text outside a final text block is not a blank-tap target at all.
        assertTrue(src.contains("if (bandRegistry != null) bandRow?.messageId else LocalShardId.current?.messageId"))
        // The band only filters what was already blank.
        val geo = ProductionSources.read("ui/chat/BlankTapGeometry.kt").substringAfter("fun decide(").substringBefore("\n    }\n")
        assertTrue(geo.trimEnd().endsWith("return if (band.contains(tap.y)) Kind.BLANK else Kind.OUTSIDE_BAND"))
        assertEquals("the only place the band is consulted", 1, Regex("band\\.contains").findAll(geo).count())
    }

    @Test fun `both screens provide the registry and scope the rows of the final text block`() {
        for ((screen, owner) in listOf("ui/chat/ChatScreen.kt" to "viewModel", "ui/chat/HelperUi.kt" to "vm")) {
            val src = ProductionSources.read(screen)
            assertTrue(screen, src.contains("LocalUsageTapBandRegistry provides remember($owner) { UsageTapBandRegistry() }"))
            val md = src.substringAfter("is FlatChatItem.AssistantMarkdownBlock -> ").let {
                if (screen.endsWith("ChatScreen.kt")) src.substringAfter("is FlatChatItem.AssistantMarkdownBlock -> BoundsTrackedBlock(") else it
            }.take(900)
            assertTrue(screen, md.contains("UsageTapBandRowScope("))
            assertTrue(screen, md.contains("inFinalTextBlock = item.inFinalTextBlock,"))
            assertTrue(screen, md.contains("isLastRow = item.isLastBlockOfMessage,"))
        }
        // The capsule row itself is untouched: dismiss-only, exact bounds.
        val row = ProductionSources.read("ui/chat/BlankTapGeometry.kt").substringAfter("internal fun UsageCapsuleRow(")
        assertTrue(row.contains("if (shown) {"))
        assertFalse(row.contains("UsageTapBand"))
    }

    @Test fun `the line height comes from the layout and the text style, not from a dp constant`() {
        val src = ProductionSources.read("ui/chat/UsageTapBand.kt")
        val unit = src.substringAfter("fun lastLineHeightPx(layout: TextLayoutResult): Float {").substringBefore("\n        }\n")
        assertTrue(unit.contains("layout.getLineTop(last)"))
        assertTrue(unit.contains("layout.layoutInput.style.lineHeight"))
        assertTrue("sp -> px with the layout's own density and font scale", unit.contains("with(layout.layoutInput.density) { style.toPx() }"))
        assertFalse("no hard-coded size", Regex("""\d+(\.\d+)?\.(dp|sp)""").containsMatchIn(src))
    }
}
