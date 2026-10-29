package com.openminis.app.ui.chat.search

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.FlatChatItem
import com.openminis.app.ui.chat.ToolBlockStatus
import com.openminis.app.ui.chat.ToolFoldController
import com.openminis.app.ui.chat.buildFlatChatItems
import com.openminis.app.ui.chat.chatSearchMarkdownPieces
import com.openminis.app.ui.chat.foldBlockId
import com.openminis.app.ui.chat.foldMessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-in-chat-search] Part 3: the matches painted where they are drawn, the
 * renderer's paths agreeing with the search's, and a match in a folded tool
 * run opening that run.
 */
class ChatSearchHighlightTest {

    private val key = ChatSearchUnitKey("row", "body")
    private val match = SpanStyle(background = ChatSearchMatchBackground)
    private val current = SpanStyle(background = ChatSearchCurrentBackground)

    private fun spans(a: AnnotatedString) = a.spanStyles.map { (it.item.background to (it.start until it.end)) }

    @Test fun `the counted occurrences are painted, the current one apart, the text unchanged`() {
        val h = ChatSearchHighlight("ab", mapOf(key to 3), ChatSearchMatch(key, 1))
        val out = applyChatSearchHighlight(AnnotatedString("ab x AB y ab"), key, h)
        assertEquals("ab x AB y ab", out.text)
        assertEquals(
            listOf(match.background to (0 until 2), current.background to (5 until 7), match.background to (10 until 12)),
            spans(out),
        )
    }

    @Test fun `only count(key) occurrences are painted - the per-unit cap`() {
        val h = ChatSearchHighlight("a", mapOf(key to 2), null)
        assertEquals(2, applyChatSearchHighlight(AnnotatedString("a a a a"), key, h).spanStyles.size)
    }

    @Test fun `a unit with no matches, or another unit's matches, is returned as is`() {
        val text = AnnotatedString("abc")
        assertSame(text, applyChatSearchHighlight(text, key, ChatSearchHighlight.NONE))
        val other = ChatSearchHighlight("abc", mapOf(ChatSearchUnitKey("row", "error") to 1), null)
        assertSame(text, applyChatSearchHighlight(text, key, other))
    }

    @Test fun `an inline formula is never painted, and offsets stay those of the drawn string`() {
        val drawn = buildAnnotatedString {
            append("x ")
            appendInlineContent("k", "x^2")
            append(" x")
        }
        val h = ChatSearchHighlight("x", mapOf(key to 2), null)
        val out = applyChatSearchHighlight(drawn, key, h)
        assertEquals(listOf(0 until 1, drawn.length - 1 until drawn.length), out.spanStyles.map { it.start until it.end })
    }

    @Test fun `a title after a label is searched from the offset`() {
        val headline = "Agent result · Fix agent login"
        val title = "Fix agent login"
        val h = ChatSearchHighlight("agent", mapOf(key to 1), ChatSearchMatch(key, 0))
        val out = applyChatSearchHighlight(AnnotatedString(headline), key, h, offset = headline.length - title.length)
        val s = out.spanStyles.single()
        assertEquals("agent", headline.substring(s.start, s.end).lowercase())
        assertTrue("not the label's 'Agent'", s.start > headline.indexOf('·'))
    }

    @Test fun `painting agrees with the search on every piece of a markdown fragment`() {
        // Each piece's text is what MdText draws; highlighting it with the
        // count the engine found must paint exactly that many matches.
        val md = "# Plan for **plan**\n\n- plan one\n- see [plan](https://plan.io)\n\n> plan `plan` plan"
        val units = chatSearchMarkdownPieces(md).map { ChatSearchUnit(ChatSearchUnitKey("r", "md:${it.path}"), it.text) }
        val result = ChatSearchEngine.search(units, "plan")!!
        val h = ChatSearchHighlight("plan", result.countByUnit, null)
        for (u in units) {
            val painted = applyChatSearchHighlight(AnnotatedString(u.text), u.key, h).spanStyles.size
            assertEquals(u.key.field, h.count(u.key), painted)
        }
        assertEquals("heading 2 + items 1 + 1 + quote 3", 7, result.matches.size)
        assertFalse("the link URL is not a hit", units.any { it.text.contains("plan.io") })
    }

    // ── renderer paths = search paths ─────────────────────────────────────

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val renderer = File(main, "java/com/openminis/app/ui/chat/StreamingMarkdownText.kt").readText()

    @Test fun `RenderBlock gives each Text the path chatSearchMarkdownPieces gives its piece`() {
        // Top level b<i> in all three MarkdownBlockBody branches.
        assertTrue(renderer.contains("cached.forEachIndexed { idx, block -> WithSearchPath(\"b\$idx\") { RenderBlock(block) } }"))
        assertTrue(renderer.contains("blocks.forEachIndexed { idx, block -> WithSearchPath(\"b\$idx\") { RenderBlock(block) } }"))
        assertTrue(renderer.contains("LocalChatSearchPath provides \"b\$idx\","))
        // Into a quote, a list item, an item's child.
        assertTrue(renderer.contains("WithSearchPath(quotePath?.let { \"\$it/q/b\$k\" }) { RenderBlock(inner) }"))
        assertTrue(renderer.contains("WithSearchPath(listPath?.let { \"\$it/\$j\" })"))
        assertTrue(renderer.contains("WithSearchPath(LocalChatSearchPath.current?.let { \"\$it/\$index\" })"))
        assertTrue(renderer.contains("WithSearchPath(taskPath?.let { \"\$it/\$j\" })"))
        assertTrue(renderer.contains("WithSearchPath(itemPath?.let { \"\$it/\$k\" })"))
        // The search side builds the same shapes.
        assertTrue(renderer.contains("walk(block.innerBlocks, \"\$p/q/\")"))
        assertTrue(renderer.contains("val p = \"\${prefix}b\$i\""))
        // Table cells are not units; MdText draws the highlighted string.
        assertTrue(renderer.contains("WithSearchPath(null) {"))
        assertTrue(renderer.contains("    Text(\n        text = searchShown,"))
    }

    // ── folded runs ───────────────────────────────────────────────────────

    @Test fun `a match in a folded run finds its run, and expand opens it without toggling`() {
        val a = AssistantBlock(id = "tuA", kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS, toolName = "shell_execute", toolTitle = "List files")
        val b = AssistantBlock(id = "tuB", kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS, toolName = "shell_execute", toolTitle = "Read config")
        val reply = AssistantBlock(id = "x1", kind = "text", content = "Done.\nAll good.")
        val m = ChatMessage(id = "m", role = "assistant", content = "", toolBlocks = listOf(a, b, reply))
        val rows = buildFlatChatItems(listOf(m))
        val fold = ToolFoldController().apply { enabled = true }
        val shown = fold.fold(rows, listOf(m), emptyList())
        val hidden = rows.first { it.key == "tool:m:tuB" }
        assertFalse("folded away", shown.any { it.key == hidden.key })
        val run = fold.runKeyContaining(hidden.foldMessageId()!!, hidden.foldBlockId()!!)
        assertTrue(run != null)
        fold.expand(run!!)
        fold.expand(run) // a second expand must not close it
        assertTrue(fold.expanded.value.contains(run))
        assertTrue(fold.fold(rows, listOf(m), emptyList()).any { it.key == hidden.key })
        assertNull(fold.runKeyContaining("m", "x-unknown"))
    }

    @Test fun `the units of a folded run come from the rows before folding`() {
        val src = File(main, "java/com/openminis/app/ui/chat/ChatScreen.kt").readText()
        assertTrue(src.contains("toolFold.expand(run)"))
        assertFalse("navigation never toggles a run", src.contains("toolFold.toggle(run)"))
        assertTrue(src.contains("com.openminis.app.ui.chat.search.ChatSearchRowScope(chatSearch, item.key) {"))
    }

    @Test fun `plain units paint with their own field`() {
        val ui = File(main, "java/com/openminis/app/ui/chat/ChatUserMessageUI.kt").readText()
        assertTrue(ui.contains("rememberChatSearchHighlighted(message.content, com.openminis.app.ui.chat.search.ChatSearchUnits.BODY)"))
        assertTrue(ui.contains("ChatSearchUnits.attachment(searchIndex)"))
        val asst = File(main, "java/com/openminis/app/ui/chat/ChatAssistantMessageUI.kt").readText()
        assertTrue(asst.contains("rememberChatSearchHighlighted(controlSummary ?: displayToolTitle(block), com.openminis.app.ui.chat.search.ChatSearchUnits.BODY)"))
        assertTrue(asst.contains("rememberChatSearchHighlighted(error, com.openminis.app.ui.chat.search.ChatSearchUnits.ERROR)"))
        val helper = File(main, "java/com/openminis/app/ui/chat/HelperUi.kt").readText()
        assertTrue(helper.contains("headline, com.openminis.app.ui.chat.search.ChatSearchUnits.TITLE, offset = headline.length - callback.title.length"))
        val misc = File(main, "java/com/openminis/app/ui/chat/ChatMiscViews.kt").readText()
        assertTrue(misc.contains("rememberChatSearchHighlighted(block.content, com.openminis.app.ui.chat.search.ChatSearchUnits.BODY)"))
    }
}
