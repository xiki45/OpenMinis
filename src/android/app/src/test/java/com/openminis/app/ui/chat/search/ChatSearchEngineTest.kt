package com.openminis.app.ui.chat.search

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.ChatSearchPiece
import com.openminis.app.ui.chat.FlatChatItem
import com.openminis.app.ui.chat.chatSearchMarkdownPieces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-in-chat-search] "Search in Chat", the pure half (port of iOS
 * ChatSearchEngineTests, 62b421d0d):
 *
 *   [1] matcher     case / diacritic insensitive, CJK, whole-string phrase,
 *                   non-overlapping, empty and blank queries, invisible chars
 *   [2] engine      display order, per-unit and total caps, "2000+", cancel
 *   [3] navigation  newest first, wrapping both ways, keep-by-identity, counter
 *   [4] markdown    the renderer's own text, not the source: no **, no link
 *                   URL, no backticks, inline formulas masked; one piece per
 *                   drawn Text (list items, quote children, table, code)
 *   [5] units       what is searched and what is not, in display order
 */
class ChatSearchEngineTest {

    private val M = ChatSearchMatcher

    private fun unit(row: String, text: String, field: String = ChatSearchUnits.BODY) =
        ChatSearchUnit(ChatSearchUnitKey(row, field), text)

    // ── [1] matcher ────────────────────────────────────────────────────────

    @Test fun `case insensitive`() = assertEquals(3, M.count("hello", "Hello HELLO hello", 99))

    @Test fun `diacritic insensitive both ways`() {
        assertEquals(3, M.count("cafe", "Café café CAFE", 99))
        assertEquals(1, M.count("café", "cafe", 99))
        assertEquals(1, M.count("naive", "naïve", 99))
    }

    @Test fun `Chinese substring and CJK mixed with Latin`() {
        assertEquals(2, M.count("搜索", "会话内搜索，全局搜索", 99))
        assertEquals(1, M.count("iOS 版", "这是 iOS 版本", 99))
    }

    @Test fun `non-overlapping`() = assertEquals(2, M.count("aa", "aaaaa", 99))

    @Test fun `whole string is one phrase, not OR of words`() {
        assertEquals(0, M.count("apple pie", "apple tart and pie", 99))
        assertEquals(1, M.count("apple pie", "Apple Pie!", 99))
    }

    @Test fun `query is trimmed, inner space kept`() = assertEquals("a b", M.normalizedQuery("  a b \n"))

    @Test fun `empty query finds nothing`() {
        assertTrue(M.ranges("", "abc").isEmpty())
        assertTrue(M.ranges("​", "a​b").isEmpty())
    }

    @Test fun `limit respected`() = assertEquals(7, M.count("a", "a".repeat(50), 7))

    @Test fun `ranges are UTF-16 offsets of the original text`() {
        assertEquals(listOf(2 until 3), M.ranges("x", "😀x"))
        // The range covers the accented original, not its folded form.
        assertEquals(listOf(4 until 8), M.ranges("cafe", "the café"))
        assertEquals(listOf(0 until 1), M.ranges("e", "é")) // e + combining acute
    }

    @Test fun `invisible renderer characters are skipped inside a match`() {
        // Zero-width (CJK emphasis fix), hair space (breakable inline code),
        // object mark (an inline formula's masked placeholder).
        assertEquals(listOf(0 until 3), M.ranges("ab", "a​b"))
        assertEquals(listOf(1 until 4), M.ranges("ab", "xa b"))
        assertEquals(1, M.count("a b", "a ￼￼ b".replace(" ￼￼", ""), 9))
    }

    // ── [2] engine ─────────────────────────────────────────────────────────

    private val units = listOf(unit("r1", "foo bar foo"), unit("r2", "nothing"), unit("r3", "FOO"))

    @Test fun `matches in display order then ordinal, with per-unit counts`() {
        val r = ChatSearchEngine.search(units, "foo")!!
        assertEquals(
            listOf(ChatSearchMatch(units[0].key, 0), ChatSearchMatch(units[0].key, 1), ChatSearchMatch(units[2].key, 0)),
            r.matches,
        )
        assertEquals(2, r.countByUnit[units[0].key])
        assertEquals(1, r.countByUnit[units[2].key])
        assertNull(r.countByUnit[units[1].key])
    }

    @Test fun `blank query is no results`() = assertEquals(ChatSearchResult.EMPTY, ChatSearchEngine.search(units, "   "))

    @Test fun `per-unit cap 200`() {
        val big = listOf(unit("r", "ab ".repeat(300)))
        val r = ChatSearchEngine.search(big, "ab")!!
        assertEquals(200, r.matches.size)
        assertEquals(200, r.countByUnit[big[0].key])
        assertFalse(r.truncated)
    }

    @Test fun `total cap 2000 and truncated`() {
        val many = (0 until 15).map { unit("r$it", "q ".repeat(150)) }
        val r = ChatSearchEngine.search(many, "q")!!
        assertEquals(2000, r.matches.size)
        assertTrue(r.truncated)
    }

    @Test fun `exactly 2000 is not truncated`() {
        val exact = (0 until 10).map { unit("r$it", "q ".repeat(200)) }
        val r = ChatSearchEngine.search(exact, "q")!!
        assertEquals(2000, r.matches.size)
        assertFalse(r.truncated)
    }

    @Test fun `cancel returns null`() {
        val many = (0 until 15).map { unit("r$it", "q ".repeat(150)) }
        assertNull(ChatSearchEngine.search(many, "q", isCancelled = { true }))
    }

    @Test fun `same input, same order`() =
        assertEquals(ChatSearchEngine.search(units, "o"), ChatSearchEngine.search(units, "o"))

    // ── [3] navigation ─────────────────────────────────────────────────────

    private val N = ChatSearchNavigation

    @Test fun `lands on newest`() {
        assertEquals(11, N.initialIndex(12))
        assertNull(N.initialIndex(0))
    }

    @Test fun `wrapping both ways`() {
        assertEquals(ChatSearchNavigation.Step(0, true), N.step(11, 12, forward = true))
        assertEquals(ChatSearchNavigation.Step(11, true), N.step(0, 12, forward = false))
    }

    @Test fun `plain steps`() {
        assertEquals(ChatSearchNavigation.Step(3, false), N.step(4, 12, forward = false))
        assertEquals(ChatSearchNavigation.Step(5, false), N.step(4, 12, forward = true))
    }

    @Test fun `single result never reports a wrap, no results no step`() {
        assertEquals(ChatSearchNavigation.Step(0, false), N.step(0, 1, forward = true))
        assertNull(N.step(null, 0, forward = true))
        assertEquals(ChatSearchNavigation.Step(0, false), N.step(null, 3, forward = true))
        assertEquals(ChatSearchNavigation.Step(2, false), N.step(null, 3, forward = false))
    }

    @Test fun `relocate keeps the same match after inserts, clamps when gone`() {
        val a = ChatSearchMatch(units[0].key, 1)
        val list = listOf(ChatSearchMatch(units[0].key, 0), a, ChatSearchMatch(units[2].key, 0))
        assertEquals(2, N.relocate(a, 1, listOf(ChatSearchMatch(units[1].key, 0)) + list))
        assertEquals(2, N.relocate(ChatSearchMatch(units[1].key, 9), 7, list))
        assertNull(N.relocate(a, 1, emptyList()))
    }

    @Test fun `counter`() {
        assertEquals("3/12", N.counterText(2, 12, truncated = false))
        assertEquals("1/2000+", N.counterText(0, 2000, truncated = true))
        assertEquals("0/0", N.counterText(null, 0, truncated = false))
    }

    // ── [4] markdown: the renderer's own text ──────────────────────────────

    private fun pieces(md: String) = chatSearchMarkdownPieces(md)
    private fun texts(md: String) = pieces(md).map { it.text }

    @Test fun `strong, link and code render as their text`() {
        val p = texts("A **bold** and [link](https://x.io) with `a_b`").single()
        assertTrue(p, p.contains("A ") && p.contains("bold") && p.contains("link") && p.contains("a_b"))
        assertEquals(0, M.count("**", p, 9))
        assertEquals(0, M.count("x.io", p, 9))
        assertEquals(0, M.count("`", p, 9))
        assertEquals(1, M.count("bold and link", p, 9))
    }

    @Test fun `one piece per drawn text, addressed like RenderBlock`() {
        val ps = pieces("# Title\n\nPara one.\n\n- one\n- two\n\n> quoted line")
        assertEquals(listOf("b0", "b1", "b2/0", "b2/1", "b3/q/b0"), ps.map { it.path })
        assertEquals(listOf("Title", "Para one.", "one", "two", "quoted line"), ps.map { it.text.trim() })
    }

    @Test fun `numbered and task items without their numbers or boxes`() {
        assertEquals(listOf("x", "y"), texts("3. x\n4. y").map { it.trim() })
        assertEquals(listOf("t", "u"), texts("- [x] t\n- [ ] u").map { it.trim() })
    }

    @Test fun `code block and table are whole-block pieces with their visible text`() {
        val code = pieces("```kotlin\nval x = 1\n```").single()
        assertTrue(code.isWholeBlock)
        assertTrue(code.text, code.text.contains("val x = 1") && !code.text.contains("```"))
        val table = pieces("| h1 | **h2** |\n|---|---|\n| a | `b` |").single()
        assertTrue(table.isWholeBlock)
        // Inline code is padded with U+2006 by the inline parser; the matcher skips it.
        assertEquals("h1\th2\na\t b ", table.text)
        assertEquals(1, M.count("a\tb", table.text, 9))
    }

    @Test fun `text around inline code is found across its padding`() {
        val p = texts("use `b` now").single()
        assertEquals(1, M.count("use b now", p, 9))
    }

    @Test fun `rules, images and display math contribute nothing`() {
        assertEquals(listOf("a"), texts("a\n\n---\n\n![alt](p.png)\n\n$$\ny\n$$").map { it.trim() })
    }

    @Test fun `an inline formula is masked, its LaTeX is not searchable`() {
        val p = texts("before \$x^2\$ after").single()
        assertEquals(0, M.count("x^2", p, 9))
        assertEquals(1, M.count("before", p, 9))
        assertEquals(1, M.count("after", p, 9))
    }

    @Test fun `searchable keeps offsets and masks inline content`() {
        val ann = buildAnnotatedString {
            append("a ")
            appendInlineContent("k", "LATEX")
            append(" b")
        }
        val s = ChatSearchMarkdown.searchable(ann)
        assertEquals(ann.text.length, s.length)
        assertEquals("a ￼￼￼￼￼ b", s)
        assertEquals(0, M.count("LATEX", s, 9))
    }

    // ── [5] units ──────────────────────────────────────────────────────────

    private val source = object : ChatSearchUnits.TextSource {
        override fun markdownPieces(raw: String) = listOf(ChatSearchPiece("b0", raw))
        override fun toolCapsule(block: AssistantBlock) = block.toolTitle.ifEmpty { null }
    }

    private fun user(id: String, content: String, names: List<String> = emptyList(), images: Int = 0) =
        FlatChatItem.UserBubble(
            ChatMessage(
                id = id, role = "user", content = content, attachmentNames = names,
                imageUris = List(images) { android.net.Uri.EMPTY },
            ),
        )

    @Test fun `user bubble, file tiles, reply, capsule, info and error are searched in order`() {
        val rows = listOf(
            user("u1", "hello", names = listOf("img.png", "notes.txt"), images = 1),
            FlatChatItem.AssistantHeader("a1"),
            FlatChatItem.AssistantThinking("a1", AssistantBlock("t", "thinking", content = "secret thought"), isLast = true, messageIsStreaming = false),
            FlatChatItem.AssistantMarkdownBlock("a1", "b1", "reply text", 0, true, false, "reply text"),
            FlatChatItem.AssistantToolUse("a1", AssistantBlock("k", "tool_use", toolTitle = "List files", toolArgs = "{\"command\":\"ls -la\"}"), emptyList()),
            FlatChatItem.AssistantInfo("a1", AssistantBlock("i", "info", content = "Compacted 12 messages")),
            FlatChatItem.AssistantError("a1", "Rate limited"),
        )
        val (u, _) = ChatSearchUnits.build(rows, source)!!
        assertEquals(
            listOf("att:0" to "notes.txt", "body" to "hello", "md:b0" to "reply text", "body" to "List files",
                "body" to "Compacted 12 messages", "error" to "Rate limited"),
            u.map { it.key.field to it.text },
        )
        assertFalse("thinking is not searched", u.any { it.text.contains("secret") })
        assertFalse("tool arguments are not searched", u.any { it.text.contains("ls -la") })
        assertFalse("image tiles show no name", u.any { it.text == "img.png" })
    }

    @Test fun `agent callback searches its title only`() {
        val cb = AgentCallback(
            kind = AgentCallback.Kind.FINISHED, jobId = "j1", childSessionId = "c1",
            title = "Fix login", status = "done", body = "the whole report body",
        )
        val row = FlatChatItem.AgentCallbackCard(ChatMessage("m", "user", cb.xml), cb)
        val (u, _) = ChatSearchUnits.build(listOf(row), source)!!
        assertEquals(listOf("title" to "Fix login"), u.map { it.key.field to it.text })
    }

    @Test fun `markdown pieces are cached per row while the text is unchanged`() {
        var parses = 0
        val counting = object : ChatSearchUnits.TextSource {
            override fun markdownPieces(raw: String): List<ChatSearchPiece> { parses++; return listOf(ChatSearchPiece("b0", raw)) }
            override fun toolCapsule(block: AssistantBlock): String? = null
        }
        val row = FlatChatItem.AssistantMarkdownBlock("a", "b", "text", 0, true, false, "text")
        val (_, cache) = ChatSearchUnits.build(listOf(row), counting)!!
        ChatSearchUnits.build(listOf(row), counting, cache)
        assertEquals(1, parses)
        val grown = FlatChatItem.AssistantMarkdownBlock("a", "b", "text more", 0, true, false, "text more")
        ChatSearchUnits.build(listOf(grown), counting, cache)
        assertEquals(2, parses)
    }

    @Test fun `units build returns null when cancelled`() {
        assertNull(ChatSearchUnits.build(listOf(user("u", "x")), source, isCancelled = { true }))
    }

    // [T-in-chat-search-fold-cost] Folding is cached per code point and kept in
    // primitive arrays; the ranges must stay exactly what they were.
    @Test fun `folding keeps ranges exact for CJK, accents, astral and expanding code points`() {
        val text = "你好世界，你好 Café 🍎ẞ İx"
        repeat(2) { // the second pass reads the per-code-point cache
            assertEquals(listOf(0 until 2, 5 until 7), ChatSearchMatcher.ranges("你好", text))
            assertEquals(listOf(8 until 12), ChatSearchMatcher.ranges("cafe", text))
            assertEquals(listOf(13 until 15), ChatSearchMatcher.ranges("🍎", text))
            // İ: NFD drops its dot, the rest folds to "i" and maps back to İ.
            assertEquals(listOf(17 until 19), ChatSearchMatcher.ranges("ix", text))
        }
    }

    @Test fun `a long CJK text folds without per-character objects`() {
        val text = buildString { repeat(200_000) { append('漢') } }
        val t0 = System.nanoTime()
        assertEquals(100_000, ChatSearchMatcher.count("漢漢", text, Int.MAX_VALUE))
        assertTrue("fold of 200k CJK chars took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 2_000_000_000L)
    }

    @Test fun `a streaming refresh waits for a running search instead of cancelling it`() {
        val src = com.openminis.app.ProductionSources.read("ui/chat/search/ChatSearchController.kt")
        val onRows = src.substringAfter("fun onRows(newRows: List<FlatChatItem>) {").substringBefore("\n    }\n")
        assertTrue(onRows.contains("searchJob?.takeIf { it.isActive }?.join()"))
        assertFalse("the signature is not taken on every tick", onRows.substringBefore("refreshJob = scope.launch").contains("signature("))
    }
}
