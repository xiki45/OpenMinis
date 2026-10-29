package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ISS-017] Headers that expand or collapse a card in the chat list have no
 * Material ripple: they go through `expandToggleClickable` (indication = null
 * plus a flat pressed tint). Buttons and rows that navigate keep `clickable`.
 *
 * Source guards, in the style of ToolGroupRowAlignTest: the regression this
 * prevents is someone writing a plain `.clickable { expanded = !expanded }`
 * again, which compiles and looks fine in review.
 */
class ExpandHeaderNoRippleTest {

    private fun src(path: String) = ProductionSources.read(path)

    @Test
    fun `the helper drops the indication but keeps click semantics`() {
        val helper = src("ui/util/ExpandToggleClickable.kt")
        assertTrue(helper.contains("indication = null,"))
        assertTrue("pressed tint and click share one interaction source",
            helper.contains("val pressed by interaction.collectIsPressedAsState()") &&
                helper.contains("interactionSource = interaction,"))
        assertTrue("TalkBack label and role are forwarded",
            helper.contains("onClickLabel = onClickLabel,") && helper.contains("role = role,"))
        assertTrue("pressed feedback reuses the theme, no new colour",
            helper.contains("MaterialTheme.colorScheme.onSurface.copy(alpha = PRESSED_TINT_ALPHA)"))
    }

    @Test
    fun `the thinking block header has no ripple and its toggle logic is unchanged`() {
        val ui = src("ui/chat/ChatAssistantMessageUI.kt")
        val block = ui.substringAfter("internal fun ThinkingBlock(")
            .substringBefore("AnimatedVisibility(visible = expanded && !overHardCap)")
        // [T-thinking-autoexpand-toggle-longpress] The header also takes a
        // long-press (settings menu) through the same ripple-free helper.
        assertTrue(block.contains(".expandToggleClickable(onLongClick = { showThinkingMenu = true }) {"))
        assertFalse(block.contains(".clickable"))
        assertFalse(block.contains("combinedClickable"))
        val tap = block.substringAfter(".expandToggleClickable(onLongClick = { showThinkingMenu = true }) {")
        assertTrue(tap.contains("if (!overHardCap) userIntent = !expanded"))
        // [#467] Over the hard cap the header opens the full-thinking viewer,
        // and only once the block has stopped streaming.
        assertTrue(tap.contains(
            "if (overHardCap) {\n                        if (canOpenFull) showFullContent = true\n" +
                "                    } else {\n                        expanded = !expanded\n                    }",
        ))
    }

    @Test
    fun `the tool fold row has no ripple and keeps its spoken semantics`() {
        val row = src("ui/chat/ToolGroupRow.kt")
        assertTrue(row.contains(".expandToggleClickable(onClick = onToggle)"))
        assertFalse(row.contains(".clickable("))
        assertTrue(row.contains("role = Role.Button"))
        assertTrue(row.contains("onClick(label = hint) { onToggle(); true }"))
    }

    @Test
    fun `only the card that expands in place loses the ripple`() {
        val frame = src("ui/chat/TaskCardFrame.kt")
        assertTrue(frame.contains("expandsInPlace: Boolean = false,"))
        assertTrue(frame.contains(
            "Modifier.expandToggleClickable(onClickLabel = onClickLabel, pressedShape = RectangleShape, onClick = onClick)",
        ))
        assertTrue("navigating cards keep the ripple",
            frame.contains("Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)"))

        val ask = src("ui/chat/UserAskCard.kt")
        assertTrue("resolved question card toggles in place",
            ask.contains("onClick = { resize.toggle(scope, anchor) { expanded = !expanded } },\n        expandsInPlace = true,"))
        // Scheduled and sub agent cards open a detail sheet: navigation.
        assertFalse(src("ui/chat/ScheduledTaskCard.kt").contains("expandsInPlace"))
        assertFalse(src("ui/chat/HelperUi.kt").contains("expandsInPlace"))
    }

    /**
     * Sweep of the files that render chat-list cards: a ripple-bearing
     * `clickable` whose handler flips an expansion is a regression. There is
     * no exception left: the active question's "Show more / Show less" used to
     * be a plain text link, and since T-user-ask-fold-by-layout it is a
     * full-size toggle that goes through `expandToggleClickable` too.
     */
    @Test
    fun `no plain clickable in a chat card flips an expansion`() {
        val files = listOf(
            "ui/chat/ChatAssistantMessageUI.kt", "ui/chat/ToolGroupRow.kt", "ui/chat/TaskCardFrame.kt",
            "ui/chat/UserAskCard.kt", "ui/chat/HelperUi.kt", "ui/chat/ChatMiscViews.kt",
            "ui/chat/ChatUserMessageUI.kt", "ui/chat/ScheduledTaskCard.kt",
        )
        val toggle = Regex("""(?:[eE]xpanded|userIntent)\s*=\s*!|\.toggle\(|onToggle""")
        val offenders = mutableListOf<String>()
        for (f in files) {
            val text = src(f)
            for (m in Regex("""\.(clickable|combinedClickable)\b""").findAll(text)) {
                val handler = text.substring(m.range.first, minOf(text.length, m.range.first + 400))
                    .substringBefore("\n            )\n")
                if (toggle.containsMatchIn(handler)) {
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    offenders += "$f:$line"
                }
            }
        }
        assertEquals("$offenders", 0, offenders.size)
        // The question's fold toggle and its tap-to-open box: both without ripple.
        val ask = src("ui/chat/UserAskCard.kt")
        assertEquals(2, Regex("""expandToggleClickable\(pressedShape = RectangleShape, onClick = toggleFold\)""").findAll(ask).count())
        assertFalse(ask.contains("clickable(onClick = toggleFold)"))
    }
}
