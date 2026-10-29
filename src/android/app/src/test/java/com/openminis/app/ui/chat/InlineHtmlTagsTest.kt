package com.openminis.app.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-md-inline-html] The chat renderer understands `<br>` and a few inline
 * style tags; every other tag, an unclosed tag, and anything inside code stays
 * literal. Copy as plain text / rich text follow the same rules.
 */
class InlineHtmlTagsTest {

    private fun parse(s: String) = parseInlineForTest(s)

    /** Whether some span with [match] covers exactly [part] of the rendered text. */
    private fun AnnotatedString.styled(part: String, match: (SpanStyle) -> Boolean): Boolean {
        val at = text.indexOf(part)
        return at >= 0 && spanStyles.any { r -> match(r.item) && r.start <= at && r.end >= at + part.length }
    }

    private val bold: (SpanStyle) -> Boolean = { it.fontWeight == FontWeight.Bold }

    // ── <br> ───────────────────────────────────────────────────────────────

    @Test
    fun `every br spelling is a line break`() {
        for (s in listOf("a<br>b", "a<br/>b", "a<br />b", "A<BR>b")) {
            assertEquals(s, s.first() + "\nb", parse(s).text)
        }
    }

    @Test
    fun `a br inside a table cell gives the cell two lines`() {
        val md = "| 列1 | 列2 |\n|---|---|\n| x<br>y | <b>粗</b> |"
        val table = chatSearchMarkdownPieces(md).single { it.isWholeBlock }.text
        assertEquals("列1\t列2\nx\ny\t粗", table)
        assertTrue(parse("x<br>y").text == "x\ny")
        assertTrue(parse("<b>粗</b>").styled("粗", bold))
    }

    // ── inline styles ────────────────────────────────────────────────────────

    @Test
    fun `style tags style their content and drop the tag text`() {
        val a = parse("<b>**双重**</b>")
        assertEquals("双重", a.text)
        assertTrue(a.styled("双重", bold))

        val b = parse("<i>斜</i><u>下</u>")
        assertEquals("斜下", b.text)
        assertTrue(b.styled("斜") { it.fontStyle == FontStyle.Italic })
        assertTrue(b.styled("下") { it.textDecoration == TextDecoration.Underline })

        for (s in listOf("<s>删</s>", "<del>删</del>", "<strike>删</strike>")) {
            val c = parse(s)
            assertEquals(s, "删", c.text)
            assertTrue(s, c.styled("删") { it.textDecoration == TextDecoration.LineThrough })
        }
        for (s in listOf("<strong>粗</strong>", "<B>粗</B>", "<Strong>粗</sTrong>")) {
            assertTrue(s, parse(s).styled("粗", bold))
        }
        assertTrue(parse("<em>斜</em>").styled("斜") { it.fontStyle == FontStyle.Italic })
        assertTrue(parse("H<sub>2</sub>O").styled("2") { it.baselineShift == BaselineShift.Subscript })
        assertTrue(parse("x<sup>2</sup>").styled("2") { it.baselineShift == BaselineShift.Superscript })
    }

    @Test
    fun `tags and markdown nest both ways`() {
        val a = parse("**<i>斜</i>和粗**")
        assertEquals("斜和粗", a.text)
        assertTrue(a.styled("斜和粗", bold))
        assertTrue(a.styled("斜") { it.fontStyle == FontStyle.Italic })
        assertEquals("a\nb", parse("**a<br>b**").text)
        assertTrue(parse("<b><i>x</i></b>").styled("x") { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `the code tag is code and its content literal`() {
        val a = parse("<code>a<b>b</b></code>")
        assertTrue(a.text, a.text.contains("a<b>b</b>"))
        assertTrue(a.getStringAnnotations("inline_code", 0, a.length).isNotEmpty())
    }

    // ── what stays literal ─────────────────────────────────────────────────

    @Test
    fun `br in code stays as written`() {
        assertTrue(parse("`a<br>b`").text.contains("a<br>b"))
        val fenced = chatSearchMarkdownPieces("```\na<br>b\n```").joinToString("\n") { it.text }
        assertTrue(fenced, fenced.contains("a<br>b"))
        // A close tag inside a code span does not close the tag outside it.
        assertEquals("<b>x </b> y", parse("<b>x `</b>` y").text.replace("\u2006", ""))
    }

    @Test
    fun `unclosed, stray and unsupported tags are shown as written`() {
        for (s in listOf(
            "<b>半截", "<", "1 < 2 > 0", "<div>x</div>", "<span style=\"color:red\">y</span>",
            "<img src=x>", "<a href=\"u\">l</a>", "<script>alert(1)</script>", "<b",
            "a</b>", "<br", "<br/ >", "<b>a\nb</b>", "a < b", "a <div>x</div> b",
            "<b class=\"x\">y</b>", "a</br>b", "a<b/>b", "a <!-- c --> b", "<b>x</strong>",
        )) {
            assertEquals(s, s, parse(s).text)
        }
        // An escaped tag is literal too.
        assertEquals("<b>x</b>", parse("\\<b>x</b>").text)
    }

    @Test
    fun `an open tag swallows nothing that follows it`() {
        val a = parse("<b>还没闭合 **粗** 后文")
        assertEquals("<b>还没闭合 粗 后文", a.text)
        assertTrue(a.styled("粗", bold))
    }

    // ── the rest of src/shared/markdown/html-tags.md's table ────────────

    @Test
    fun `shared spec cases`() {
        // 21: the newline after a <br> adds no second break.
        assertEquals("a\nb", parse("a<br>\nb").text)
        // 22 + rule 1: any case, spaces before '>'.
        assertTrue(parse("<B>x</b>").styled("x", bold))
        assertTrue(parse("<b >x</b >").styled("x", bold))
        assertEquals("a\nb", parse("a<br >b").text)
        // 9, 11
        val nine = parse("<strong>a</strong><em>b</em>")
        assertEquals("ab", nine.text)
        assertTrue(nine.styled("a", bold) && nine.styled("b") { it.fontStyle == FontStyle.Italic })
        assertTrue(parse("<code>let x</code>").text.contains("let x"))
        // 27: crossing tags - the outer pair wins, the inner one is literal.
        val cross = parse("<b>a<i>b</b>c</i>")
        assertEquals("a<i>bc</i>", cross.text)
        assertTrue(cross.styled("a<i>b", bold))
        assertFalse(cross.styled("c</i>", bold))
        // 28: same-name tags nest.
        val nested = parse("<b>a<b>b</b>c</b>")
        assertEquals("abc", nested.text)
        assertTrue(nested.styled("abc", bold))
        // 29, 30, 31
        assertTrue(parse("**a<b>b</b>**").let { it.text == "ab" && it.styled("ab", bold) })
        assertTrue(parse("<b>a<br>b</b>").let { it.text == "a\nb" && it.styled("a\nb", bold) })
        assertTrue(parse("<code>a<br>b</code>").text.contains("a<br>b"))
        // 32: a tag in a link label.
        val link = parse("[<b>x</b>](https://e.com)")
        assertEquals("x", link.text)
        assertTrue(link.styled("x", bold))
        assertTrue(link.getStringAnnotations("url", 0, link.length).single().item == "https://e.com")
        // A markdown pair crossing an HTML pair does not match either way round.
        assertEquals("x<b>y</b>", parse("**x<b>y**</b>").text)
        // 33, 34: list items and headings.
        assertTrue(chatSearchMarkdownPieces("- a<br>b").any { it.text == "a\nb" })
        assertTrue(chatSearchMarkdownPieces("# a<b>b</b>").any { it.text == "ab" })
    }

    // ── streaming ───────────────────────────────────────────────────────────

    @Test
    fun `feeding a reply one character at a time never throws or loses text`() {
        val full = "表头 <b>粗**嵌**</b> 与 <i>斜</i><br/>下一行 `<br>` <u>下</u> <s>删</s> " +
            "1 < 2 > 0 <div>x</div> H<sub>2</sub>O x<sup>2</sup> <code>c</code> 结尾"
        for (n in 1..full.length) {
            val rendered = parse(full.substring(0, n)).text
            assertTrue("prefix $n rendered empty", rendered.isNotEmpty())
        }
        val done = parse(full).text.replace("\u2006", "")
        assertEquals("表头 粗嵌 与 斜\n下一行 <br> 下 删 1 < 2 > 0 <div>x</div> H2O x2 c 结尾", done)

        // The shared spec's streaming reply, through the whole block parser.
        val reply = "开头<b>粗体</b>中间<br>换行<i>斜<u>下划</u>体</i>尾巴<b>未闭合\n\n" +
            "| 列 | 值 |\n|---|---|\n| 甲<br>乙 | <s>删</s> |"
        for (n in 1..reply.length) chatSearchMarkdownPieces(reply.substring(0, n))
        val pieces = chatSearchMarkdownPieces(reply).map { it.text }
        assertEquals(listOf("开头粗体中间\n换行斜下划体尾巴<b>未闭合", "列\t值\n甲\n乙\t删"), pieces)
    }

    @Test
    fun `tags pair within a line, so the incremental split stays exact`() {
        val text = (1..120).joinToString("\n") { "第$it 行 <b>粗$it</b> 和 a<br>b <i>斜</i>" } + "\n" + "z".repeat(300)
        val split = safeInlineSplitOffset(text)
        assertTrue("expected a safe split, got $split", split > 0)
        val whole = parse(text)
        val joined = AnnotatedString.Builder().apply {
            append(parse(text.substring(0, split)))
            append(parse(text.substring(split)))
        }.toAnnotatedString()
        assertEquals(whole.text, joined.text)
        assertEquals(whole.spanStyles, joined.spanStyles)
    }

    // ── copy / export ───────────────────────────────────────────────────────

    @Test
    fun `plain text copy turns br into a newline and drops style tags`() {
        val plain = MarkdownClipboard::markdownToPlainText
        assertEquals("a\nb", plain("a<br>b"))
        assertEquals("a\nb", plain("a<br>\nb"))
        assertEquals("粗 x 斜下", plain("<b>粗</b> **x** <i>斜</i><u>下</u>"))
        assertEquals("a<br>b", plain("`a<br>b`"))
        assertEquals("<div>x</div> <b>半截", plain("<div>x</div> <b>半截"))
        assertTrue(plain("```\na<br>b\n```").contains("a<br>b"))
        val table = plain("| 列1 | 列2 |\n|---|---|\n| x<br>y | <b>粗</b> |")
        assertTrue(table, table.contains("x\ny") && table.contains("粗") && !table.contains("<b>"))
    }

    @Test
    fun `rich text copy writes real tags for the whitelist and escapes the rest`() {
        val html = MarkdownClipboard.markdownToHtml("a<br>b <b>粗</b> <s>删</s> <div>x</div> `<br>`")
        assertTrue(html, html.contains("a<br/>b"))
        assertTrue(html, html.contains("<strong>粗</strong>"))
        assertTrue(html, html.contains("<del>删</del>"))
        assertTrue(html, html.contains("&lt;div&gt;x&lt;/div&gt;"))
        assertTrue(html, html.contains("<code>&lt;br&gt;</code>"))
        assertFalse(html, html.contains("<div>"))
    }
}
