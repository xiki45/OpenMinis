package com.openminis.app.ui.chat

import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.SessionSearch
import com.openminis.app.ui.markdown.InlineHtmlTags
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-md-inline-html] Round 2: one-line previews (session list, search snippet,
 * skill subtitle, scheduled task, read-aloud) treat the tag whitelist the way
 * the renderer does, and the three ways to show a tag literally - code, a
 * backslash, an entity - work the same on screen, in previews and in copy.
 */
class InlineHtmlPreviewTest {

    private fun strip(s: String) = InlineHtmlTags.stripForPreview(s)

    private fun parts(text: String) = JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString()

    private fun preview(text: String) = ChatRepository.extractTextPreview(parts(text))

    // ── the preview stripper ───────────────────────────────────────────────

    @Test
    fun `br is a space and whitelisted tags go, open or close`() {
        assertEquals("a b", strip("a<br>b"))
        assertEquals("a b c d", strip("a<br/>b<BR />c<br >d"))
        assertEquals("粗 斜下 删", strip("<b>粗</b> <i>斜</i><u>下</u> <s>删</s>"))
        assertEquals("H2O x2 code", strip("H<sub>2</sub>O x<sup>2</sup> <code>code</code>"))
        // Open or close alone are removed too - a preview has no pairing to wait for.
        assertEquals("半截", strip("<b>半截"))
        assertEquals("x", strip("x</strong>"))
        assertEquals("a b ", strip("<strong>a</strong> <em>b</em> <del>"))
    }

    @Test
    fun `tags outside the whitelist and stray brackets stay`() {
        for (s in listOf(
            "<div>x</div>", "<a href=\"u\">l</a>", "<b class=x>y</b>", "a<b/>b", "1 < 2 > 0", "a <", "<",
            "a</br>b", "a <!-- c --> b", "<span style=\"c\">x</span>", "<script>x</script>",
        )) {
            val expected = s.replace("</b>", "")
            assertEquals(s, expected, strip(s))
        }
    }

    @Test
    fun `code is never touched`() {
        assertEquals("x `a<br>b` y", strip("x `a<br>b` y"))
        assertEquals("x ``a`<b>`b`` y", strip("x ``a`<b>`b`` y"))
        val fenced = "前<br>\n```html\na<br><b>b</b>\n```\n后<b>粗</b>"
        assertEquals("前 \n```html\na<br><b>b</b>\n```\n后粗", strip(fenced))
        val tilde = "~~~\n<br>\n~~~\n<br>"
        assertEquals("~~~\n<br>\n~~~\n ", strip(tilde))
        // An indented code block: after a blank line, four spaces or a tab.
        assertEquals("p\n\n    a<br>b\n\tc<b>d</b>\nq ", strip("p\n\n    a<br>b\n\tc<b>d</b>\nq<br>"))
        // Four spaces continuing a paragraph are not code.
        assertEquals("p\n    a b", strip("p\n    a<br>b"))
    }

    @Test
    fun `escape and entity give the literal tag, never a tag`() {
        assertEquals("<br>", strip("\\<br>"))
        assertEquals("<b>x", strip("\\<b>x"))
        assertEquals("<br>", strip("&lt;br&gt;"))
        assertEquals("<b>x</b>", strip("&lt;b&gt;x&lt;/b&gt;"))
        assertEquals("<br>", strip("&#60;br&#x3E;"))
        assertEquals("a & b", strip("a &amp; b"))
        assertEquals("&unknown; &", strip("&unknown; &"))
        // An entity inside code stays as written.
        assertEquals("`&lt;br&gt;`", strip("`&lt;br&gt;`"))
    }

    @Test
    fun `fast path - text without lt or amp is returned as is`() {
        val s = "plain **markdown** `code` [link](u)"
        assertTrue(strip(s) === s)
    }

    // ── the surfaces that use it ───────────────────────────────────────────

    @Test
    fun `session list preview`() {
        assertEquals("第一行 第二行 粗 和 斜", preview("第一行<br>第二行 <b>粗</b> 和 <i>斜</i>"))
        assertEquals("看 a<br>b 代码", preview("看 `a<br>b` 代码"))
        assertEquals("字面 <br> 与 <br>", preview("字面 \\<br> 与 &lt;br&gt;"))
        assertEquals("<div>x</div>", preview("<div>x</div>"))
        assertEquals("| x y | 粗 |", preview("| x<br>y | <b>粗</b> |"))
    }

    @Test
    fun `search snippet`() {
        val json = parts("前言\n结果是 <b>重点</b><br>下一句")
        assertEquals("结果是 重点 下一句", SessionSearch.searchSnippet(json, listOf("重点")))
        // A search for the tag itself still finds its line in the raw text.
        assertTrue(SessionSearch.searchSnippet(json, listOf("<b>"))!!.contains("<b>"))
        val code = parts("```\nx<br>y 命中\n```")
        assertTrue(SessionSearch.searchSnippet(code, listOf("命中"))!!.contains("x<br>y"))
    }

    // ── the same three literal forms on screen and in copy ─────────────────

    @Test
    fun `renderer shows the literal forms literally`() {
        assertEquals("<br>", parseInlineForTest("\\<br>").text)
        assertEquals("<br>", parseInlineForTest("&lt;br&gt;").text)
        assertEquals("<b>x</b>", parseInlineForTest("&lt;b&gt;x&lt;/b&gt;").text)
        assertTrue(parseInlineForTest("`a<br>b`").text.contains("a<br>b"))
        assertTrue(parseInlineForTest("`&lt;br&gt;`").text.contains("&lt;br&gt;"))
        assertEquals("AT&T  <", parseInlineForTest("AT&amp;T &nbsp;&#60;").text)
        assertEquals("**<br>** 链接", parseInlineForTest("\\*\\*&lt;br&gt;\\*\\* [链接](u)").text)
        val label = parseInlineForTest("[a&amp;b](https://e.com)")
        assertEquals("a&b", label.text)
    }

    @Test
    fun `copy keeps code and the literal forms`() {
        val plain = MarkdownClipboard::markdownToPlainText
        assertEquals("<br>", plain("\\<br>"))
        assertEquals("<br>", plain("&lt;br&gt;"))
        assertEquals("a<br>b", plain("`a<br>b`"))
        val fenced = plain("说明<br>如下\n```\nx<br>y\n```")
        assertEquals("说明\n如下\nx<br>y", fenced)
        val html = MarkdownClipboard.markdownToHtml("\\<br> &lt;b&gt; <b>x</b>")
        assertTrue(html, html.contains("&lt;br&gt; &lt;b&gt; <strong>x</strong>"))
    }
}
