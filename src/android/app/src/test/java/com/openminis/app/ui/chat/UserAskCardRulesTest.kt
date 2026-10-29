package com.openminis.app.ui.chat

import com.openminis.app.agent.userask.UserAskQuestion
import com.openminis.app.agent.userask.UserAskQuestionType
import com.openminis.app.agent.userask.UserAskResolver
import com.openminis.app.agent.userask.UserAskResultStatus
import com.openminis.app.agent.userask.UserAskRisk
import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-port-user-ask] The card's pure rules: phase, typography, collapsed text. */
class UserAskCardRulesTest {

    private fun phase(live: String?, results: Boolean, parses: Boolean, status: ToolBlockStatus?) =
        UserAskCardPhase.resolve(live == "pending", live == "done", results, parses, status)

    @Test fun `phase follows live state, then the stored result, then the call's status`() {
        assertEquals(UserAskCardPhase.PENDING, phase("pending", false, true, ToolBlockStatus.RUNNING))
        assertEquals(UserAskCardPhase.RESOLVED_LIVE, phase("done", true, true, ToolBlockStatus.SUCCESS))
        assertEquals("after a reload", UserAskCardPhase.RESOLVED_STORED, phase(null, true, true, ToolBlockStatus.SUCCESS))
        assertEquals("stopped mid-wait, then reloaded", UserAskCardPhase.STOPPED_STORED, phase(null, false, true, ToolBlockStatus.CANCELLED))
        assertEquals("a rejected call is a plain pill", UserAskCardPhase.NOT_ASKED, phase(null, false, false, ToolBlockStatus.FAILED))
        assertEquals("blocked although it parses", UserAskCardPhase.NOT_ASKED, phase(null, false, true, ToolBlockStatus.FAILED))
        assertEquals("arguments still streaming", UserAskCardPhase.WAITING, phase(null, false, false, ToolBlockStatus.STREAMING))
        assertEquals("running with no live ask (recovery pending)", UserAskCardPhase.WAITING, phase(null, false, true, ToolBlockStatus.RUNNING))
    }

    @Test fun `chips are compact - TaskCardChip's line ratio at every font scale, 1dp vertical padding`() {
        // [T-user-ask-chip-height] The header and "Recommended" chips inherited
        // the chat body's line height plus 2dp padding, so their tint block was
        // much taller than the text.
        assertEquals(1.1f, UserAskTypography.CHIP_LINE_RATIO, 0.001f)
        for (scale in listOf(0.5f, 0.85f, 1f, 1.3f, 2f)) {
            val t = UserAskTypography.forScale(scale)
            assertEquals("follows the chat font size at $scale", t.chip * 1.1f, t.chipLineHeight, 0.001f)
            assertTrue("never below the font size (no clipped glyphs) at $scale", t.chipLineHeight >= t.chip)
        }
        val chip = ProductionSources.read("ui/chat/UserAskCard.kt")
            .substringAfter("private fun Chip(text: String, tint: Color, t: UserAskTypography) {").substringBefore("\n}\n")
        assertTrue(chip.contains("fontSize = t.chip.tsp, lineHeight = t.chipLineHeight.tsp,"))
        assertTrue(chip.contains(".padding(horizontal = 6.dp, vertical = 1.dp)"))
        assertTrue("no fixed height: a CJK line must be free to grow", !chip.contains(".height("))
    }

    @Test fun `pending header - i of N and the countdown are pinned right, the title ellipsizes in its own group`() {
        // [T-user-ask-header-right-align] Tablet, landscape: a title with
        // weight(1f, fill = false) and a weight(1f) Spacer split the spare width
        // 1:1, so "2/4  4:57" stopped mid-row with a wide gap on the right.
        val card = ProductionSources.read("ui/chat/UserAskCard.kt")
        val header = card.substringAfter("private fun PendingHeader(").substringBefore("\n}\n")
        assertTrue(header.contains("modifier = Modifier.fillMaxWidth(),"))
        assertTrue("the leading group takes all spare width", header.contains("PendingHeaderLeading(q, tint, t, Modifier.weight(1f))"))
        assertTrue("no Spacer competing for it", !header.contains("Spacer("))
        assertTrue(header.indexOf("PendingHeaderLeading(") < header.indexOf("Countdown("))
        val leading = card.substringAfter("private fun PendingHeaderLeading(").substringBefore("\n}\n")
        assertTrue(leading.contains("Row(modifier = modifier,"))
        assertTrue(leading.contains("maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),"))
        // File-wide: no composable mixes a fill = false weight with a weighted Spacer.
        for (fn in card.split("\n@Composable\n").drop(1)) {
            val name = fn.substringAfter("fun ").substringBefore("(")
            assertTrue(
                "$name mixes weight(fill = false) with a weight(1f) Spacer; they split the spare width",
                !(fn.contains("weight(1f, fill = false)") && fn.contains("Spacer(Modifier.weight(1f))")),
            )
        }
    }

    @Test fun `text follows the chat font scale with iOS's floors`() {
        val d = UserAskTypography.forScale(1f)
        assertEquals(16.5f, d.question)
        assertEquals(14.5f, d.secondary)
        assertEquals(12.5f, d.meta)
        assertEquals(11f, d.chip)
        val small = UserAskTypography.forScale(0.5f)
        assertEquals("floors hold at small sizes", 13f, small.question)
        assertEquals(11f, small.meta)
        assertEquals(14f, small.button)
        assertEquals(44f, UserAskTypography.MIN_TAP_HEIGHT.value)
    }

    @Test fun `tints - blue for questions, orange or red for confirms`() {
        val opt = UserAskQuestion(UserAskQuestionType.OPTIONS, "q")
        assertEquals(UserAskTint.question, UserAskTint.forQuestion(opt))
        assertEquals(UserAskTint.medium, UserAskTint.forQuestion(UserAskQuestion(UserAskQuestionType.CONFIRM, "x")))
        assertEquals(UserAskTint.high, UserAskTint.forQuestion(UserAskQuestion(UserAskQuestionType.CONFIRM, "x", risk = UserAskRisk.HIGH)))
    }

    @Test fun `collapsed topic and digest`() {
        val a = UserAskQuestion(UserAskQuestionType.OPTIONS, "Which DB?", header = "DB")
        val b = UserAskQuestion(UserAskQuestionType.OPTIONS, "Which port?")
        assertEquals("Which DB?", UserAskResolvedText.topic(listOf(a)))
        assertEquals("DB · Which port?", UserAskResolvedText.topic(listOf(a, b)))
        val results = listOf(
            UserAskResolver.Parsed(UserAskResultStatus.ANSWERED, listOf("SQLite"), null),
            UserAskResolver.Parsed(UserAskResultStatus.DELEGATED, emptyList(), null),
            UserAskResolver.Parsed(UserAskResultStatus.DENIED, emptyList(), "use make clean"),
            UserAskResolver.Parsed(UserAskResultStatus.TIMEOUT, emptyList(), "No answer"),
        )
        assertEquals("SQLite · Left to Minis · use make clean", UserAskResolvedText.digest(results, dismissed = false, leftToMinis = "Left to Minis"))
        assertEquals("", UserAskResolvedText.digest(results, dismissed = true, leftToMinis = "x"))
        assertEquals(3, UserAskResolvedText.answeredCount(results))
        assertEquals("delegated alone adds no digest", "",
            UserAskResolvedText.digest(listOf(UserAskResolver.Parsed(UserAskResultStatus.DELEGATED, emptyList(), null)), false, "Left to Minis"))
    }

    @Test fun `the question's Show more toggle follows the text layout, not its length`() {
        // [T-user-ask-fold-by-layout] It used to appear above 140 characters,
        // while the fold is at three LINES: a 60-character CJK question, or a
        // short multi-line command, was cut with no way to open it.
        assertEquals(3, UserAskQuestionFold.FOLDED_LINES)
        assertTrue("folded and cut", UserAskQuestionFold.isFoldable(expanded = false, hasVisualOverflow = true, lineCount = 3))
        assertTrue("folded and fits: no toggle", !UserAskQuestionFold.isFoldable(expanded = false, hasVisualOverflow = false, lineCount = 3))
        assertTrue("open: 'Show less' stays", UserAskQuestionFold.isFoldable(expanded = true, hasVisualOverflow = false, lineCount = 4))
        assertTrue("open and now fits (rotated): gone", !UserAskQuestionFold.isFoldable(expanded = true, hasVisualOverflow = false, lineCount = 3))

        val card = ProductionSources.read("ui/chat/UserAskCard.kt")
        assertTrue("no character-count rule left", !Regex("""question\.length\s*>""").containsMatchIn(card))
        assertTrue(card.contains("maxLines = if (expanded) Int.MAX_VALUE else UserAskQuestionFold.FOLDED_LINES"))
        assertTrue(card.contains("onTextLayout = { foldable = UserAskQuestionFold.isFoldable(expanded, it.hasVisualOverflow, it.lineCount) }"))
        // The toggle is a full-height tap target, and a cut question opens on a tap on itself.
        val toggle = card.substringAfter("if (foldable) {").substringBefore("when (q.type)")
        assertTrue(toggle.contains(".heightIn(min = UserAskTypography.MIN_TAP_HEIGHT)"))
        assertTrue(toggle.contains(".expandToggleClickable(pressedShape = RectangleShape, onClick = toggleFold)"))
        assertTrue(toggle.contains(".padding(horizontal = 8.dp)"))
        assertTrue(toggle.contains("R.string.user_ask_show_less else R.string.user_ask_show_more"))
        assertTrue(card.contains("if (foldable && !expanded) Modifier.expandToggleClickable(pressedShape = RectangleShape, onClick = toggleFold) else Modifier"))
    }
}
