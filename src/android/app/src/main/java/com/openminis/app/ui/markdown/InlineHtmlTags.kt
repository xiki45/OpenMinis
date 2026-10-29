package com.openminis.app.ui.markdown

/**
 * [T-md-inline-html] The few HTML tags models mix into markdown (most often in
 * table cells): `<br>` and a handful of inline styles. Everything else - any
 * tag with attributes, `<div>`, `<span style=…>`, `<img>`, `<a>`, `<script>` -
 * stays literal text; nothing is parsed as HTML or executed.
 *
 * No HTML parser: the inline parsers call [brLength] / [openAt] when they meet
 * a `<`, so text without one costs a single char comparison per position.
 *
 * Rules (shared with iOS / Windows, src/shared/markdown/html-tags.md):
 *  - Only bare tags: `<` + name + optional spaces + `>` (any case); `<br>` may
 *    also end in `/>`. Attributes, `<b/>`, `</br>`, comments stay literal.
 *  - `<br>`, `<br/>`, `<br />` are a line break, also in table cells; a newline
 *    right after one adds nothing (`a<br>\nb` is one break, not a blank line).
 *  - `<b>`/`<strong>`, `<i>`/`<em>`, `<u>`, `<s>`/`<del>`/`<strike>`, `<code>`,
 *    `<sub>`, `<sup>` style their content only when the matching close tag is on
 *    the same line. Different names never pair; the outer pair of crossing tags
 *    wins and the inner unmatched one is literal. An open tag without one (a stream that has only delivered
 *    `<b>` so far) is shown literally until the close arrives; nothing after it
 *    is swallowed. Pairing within one line also keeps the streaming parser's
 *    "prefix ++ suffix == whole" split at newlines valid.
 *  - Code spans are skipped when looking for the close tag, and the callers
 *    never look inside code (fenced blocks are not inline text; `…` is
 *    consumed before its contents are scanned), so `<br>` in code stays as is.
 *  - Content is parsed again, so `<b>**x**</b>` and `**<b>x</b>**` both nest.
 */
internal object InlineHtmlTags {

    enum class Kind { BOLD, ITALIC, UNDERLINE, STRIKE, CODE, SUB, SUP }

    /** An open tag at some offset: its lowercased [name] and source [length]. */
    data class Open(val name: String, val kind: Kind, val length: Int)

    private val tags = listOf(
        Open("b", Kind.BOLD, 3), Open("strong", Kind.BOLD, 8),
        Open("i", Kind.ITALIC, 3), Open("em", Kind.ITALIC, 4),
        Open("u", Kind.UNDERLINE, 3),
        Open("s", Kind.STRIKE, 3), Open("del", Kind.STRIKE, 5), Open("strike", Kind.STRIKE, 8),
        Open("code", Kind.CODE, 6),
        Open("sub", Kind.SUB, 5), Open("sup", Kind.SUP, 5),
    )
    private const val MAX_NAME = 6 // "strong", "strike"

    /**
     * Cheap pre-check for the inline parsers: every supported tag has a letter
     * right after the `<`, so `1 < 2`, `a<-b` and a stray `</x>` are rejected
     * without any further scan.
     */
    fun mayStartTag(text: String, i: Int): Boolean =
        i + 1 < text.length && text[i + 1].let { it in 'a'..'z' || it in 'A'..'Z' }

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** Source length of a `<br>` / `<br >` / `<br/>` / `<br />` at [i] (any case), or 0. */
    fun brLength(text: String, i: Int): Int {
        if (text[i] != '<' || !text.regionMatches(i + 1, "br", 0, 2, ignoreCase = true)) return 0
        var j = i + 3
        while (j < text.length && text[j] == ' ') j++
        if (j < text.length && text[j] == '/') j++
        return if (j < text.length && text[j] == '>') j + 1 - i else 0
    }

    /** Where text resumes after a `<br>` of [length] at [i]: past one newline that follows it. */
    fun afterBr(text: String, i: Int, length: Int): Int {
        val end = i + length
        return if (end < text.length && text[end] == '\n') end + 1 else end
    }

    /** The whitelisted open tag at [i] (`<name>` or `<name >`, any case), or null. */
    fun openAt(text: String, i: Int): Open? {
        if (text[i] != '<') return null
        var j = i + 1
        while (j < text.length && j - i - 1 <= MAX_NAME && isAsciiLetter(text[j])) j++
        val len = j - i - 1
        if (len == 0 || len > MAX_NAME) return null
        while (j < text.length && text[j] == ' ') j++
        if (j >= text.length || text[j] != '>') return null
        // No allocation on the common path: compare in place against the few names.
        for (t in tags) {
            if (t.name.length == len && text.regionMatches(i + 1, t.name, 0, len, ignoreCase = true)) {
                val length = j + 1 - i
                return if (length == t.length) t else t.copy(length = length)
            }
        }
        return null
    }

    /** Length of the close tag `</name>` / `</name >` for [open] at [k], or 0. */
    private fun closeTagLength(text: String, k: Int, open: Open): Int {
        if (text[k] != '<' || k + 1 >= text.length || text[k + 1] != '/') return 0
        if (!text.regionMatches(k + 2, open.name, 0, open.name.length, ignoreCase = true)) return 0
        var j = k + 2 + open.name.length
        while (j < text.length && text[j] == ' ') j++
        return if (j < text.length && text[j] == '>') j + 1 - k else 0
    }

    /** Offset just past the close tag that [Pairs.closeOf] gave for [open]. */
    fun closeEnd(text: String, close: Int, open: Open): Int = close + closeTagLength(text, close, open)

    /** Which open tag of one inline text is closed where; see [pairs]. */
    class Pairs internal constructor(private val closes: HashMap<Int, Int>) {
        /** Start of the close tag pairing the open tag at [open], or -1. */
        fun closeOf(open: Int): Int = closes[open] ?: -1
    }

    /**
     * Pairs every whitelisted open tag of [text] with its close tag in ONE
     * linear pass - a stack per name, cleared at each newline, code spans
     * skipped - so a line of thousands of unclosed `<b>` costs O(n), not a
     * search to the end of the line for each. Same-name tags nest. Crossing
     * pairs of different names both pair here; the inner one is then inside
     * the outer one's content, which is parsed again on its own and finds no
     * partner there, so the outer pair wins as the spec asks.
     */
    fun pairs(text: String): Pairs {
        val closes = HashMap<Int, Int>()
        val stacks = HashMap<String, ArrayDeque<Int>>()
        var k = 0
        while (k < text.length) {
            val c = text[k]
            when {
                c == '\n' -> stacks.clear()
                c == '`' -> {
                    val end = text.indexOf('`', k + 1)
                    val nl = text.indexOf('\n', k + 1)
                    if (end != -1 && (nl == -1 || end < nl)) { k = end + 1; continue }
                }
                c == '<' && k + 1 < text.length && text[k + 1] == '/' -> {
                    val name = closeNameAt(text, k)
                    if (name != null) stacks[name]?.removeLastOrNull()?.let { closes[it] = k }
                }
                c == '<' -> {
                    val open = openAt(text, k)
                    if (open != null) {
                        stacks.getOrPut(open.name) { ArrayDeque() }.addLast(k)
                        k += open.length
                        continue
                    }
                }
            }
            k++
        }
        return Pairs(closes)
    }

    /** The whitelisted name of a close tag `</name>` / `</name >` at [k], or null. */
    private fun closeNameAt(text: String, k: Int): String? {
        var j = k + 2
        while (j < text.length && j - k - 2 <= MAX_NAME && isAsciiLetter(text[j])) j++
        val len = j - k - 2
        if (len == 0 || len > MAX_NAME) return null
        while (j < text.length && text[j] == ' ') j++
        if (j >= text.length || text[j] != '>') return null
        return tags.firstOrNull { it.name.length == len && text.regionMatches(k + 2, it.name, 0, len, ignoreCase = true) }?.name
    }

    /** [pairs] of one text, computed on the first [closeOf] (a `<br>` needs none). */
    class LazyPairs(private val text: String) {
        private var pairs: Pairs? = null
        fun closeOf(open: Int): Int = (pairs ?: pairs(text).also { pairs = it }).closeOf(open)
    }

    /**
     * For Copy as plain text: `<br>` becomes a newline and paired style tags
     * go, their content kept; unpaired or unknown tags and code spans stay as
     * they are (the caller unwraps code afterwards).
     */
    fun toPlain(s: String): String {
        if ('<' !in s && '&' !in s) return s
        val out = StringBuilder(s.length)
        appendPlain(s, out)
        return out.toString()
    }

    private fun appendPlain(s: String, out: StringBuilder) {
        var pairs: Pairs? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                val nl = s.indexOf('\n', i + 1)
                if (end != -1 && (nl == -1 || end < nl)) {
                    out.append(s, i, end + 1); i = end + 1; continue
                }
            }
            // `\<br>` and `&lt;br&gt;` are the literal text `<br>`, as on screen.
            if (isEscapedLiteral(s, i)) { out.append(s[i + 1]); i += 2; continue }
            if (c == '&') {
                val entity = entityAt(s, i)
                if (entity != null) { out.append(entity.first); i += entity.second; continue }
            }
            if (c == '<' && mayStartTag(s, i)) {
                val br = brLength(s, i)
                if (br > 0) {
                    // The caller joins lines with a newline of its own: a
                    // `<br>` ending this line would make a blank line.
                    if (i + br < s.length) out.append('\n')
                    i = afterBr(s, i, br)
                    continue
                }
                val open = openAt(s, i)
                if (open != null) {
                    val close = (pairs ?: pairs(s).also { pairs = it }).closeOf(i)
                    if (close != -1) {
                        appendPlain(s.substring(i + open.length, close), out)
                        i = closeEnd(s, close, open)
                        continue
                    }
                }
            }
            out.append(c); i++
        }
    }

    /**
     * For Copy as rich text: replaces `<br>` and paired whitelisted tags with
     * [marker]-wrapped indexes into [sink] (canonical HTML, e.g. `<br/>`,
     * `<strong>`), so the caller can escape everything else and restore them.
     * A pair crossing an already open one is left as text, so the HTML nests.
     */
    fun protectForHtml(s: String, sink: MutableList<String>, marker: Char): String {
        if ('<' !in s && '&' !in s) return s
        val pairs = pairs(s)
        val out = StringBuilder(s.length)
        fun token(html: String) {
            sink.add(html)
            out.append(marker).append(sink.size - 1).append(marker)
        }
        // Emitted pairs still open: (source offset of the close tag, its HTML).
        val open = ArrayDeque<Pair<Int, String>>()
        var i = 0
        while (i < s.length) {
            val top = open.lastOrNull()
            if (top != null && top.first == i) {
                open.removeLast()
                token(top.second)
                i = s.indexOf('>', i) + 1
                continue
            }
            // Literal `<`/`&` (escaped or as an entity) stay literal in the HTML.
            if (isEscapedLiteral(s, i)) { token(escapeHtml(s[i + 1].toString())); i += 2; continue }
            if (s[i] == '&') {
                val entity = entityAt(s, i)
                if (entity != null) { token(escapeHtml(entity.first)); i += entity.second; continue }
            }
            if (s[i] == '<' && mayStartTag(s, i)) {
                val br = brLength(s, i)
                if (br > 0) { token("<br/>"); i += br; continue }
                val tag = openAt(s, i)
                val close = if (tag != null) pairs.closeOf(i) else -1
                if (tag != null && close != -1 && (top == null || close < top.first)) {
                    val name = htmlName(tag.kind)
                    token("<$name>")
                    open.addLast(close to "</$name>")
                    i += tag.length
                    continue
                }
            }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    private fun escapeHtml(t: String): String =
        t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** A backslash-escaped `<` or `&` at [i]: the character itself, never a tag or entity. */
    fun isEscapedLiteral(text: String, i: Int): Boolean =
        text[i] == '\\' && i + 1 < text.length && (text[i + 1] == '<' || text[i + 1] == '&')

    private val namedEntities = mapOf(
        "lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
    )

    /**
     * The HTML entity at [i] - `&lt;` `&gt;` `&amp;` `&quot;` `&apos;` `&nbsp;`,
     * or a numeric `&#60;` / `&#x3C;` - as (decoded text, source length), or
     * null. The decoded text is appended as is and never scanned again, so
     * `&lt;br&gt;` shows the literal `<br>` (spec "Showing a tag literally").
     * Only these named entities: CommonMark knows all of HTML5's, iOS (cmark)
     * decodes them all; the rest stay as written here.
     */
    fun entityAt(text: String, i: Int): Pair<String, Int>? {
        if (text[i] != '&') return null
        val semi = text.indexOf(';', i + 1)
        if (semi == -1 || semi - i > 10) return null
        if (i + 1 < semi && text[i + 1] == '#') {
            val hex = i + 2 < semi && (text[i + 2] == 'x' || text[i + 2] == 'X')
            val digits = text.substring(if (hex) i + 3 else i + 2, semi)
            if (digits.isEmpty() || digits.length > (if (hex) 6 else 7)) return null
            val cp = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
            val valid = cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF
            return (if (valid) String(Character.toChars(cp)) else "\uFFFD") to semi + 1 - i
        }
        val decoded = namedEntities[text.substring(i + 1, semi)] ?: return null
        return decoded to semi + 1 - i
    }

    /**
     * For one-line previews (session list, search snippets, skill subtitles,
     * read-aloud): `<br>` becomes a space and every whitelisted style tag, open
     * or close, paired or not, goes - its text stays. Other tags stay as written.
     * Code is left exactly as it is: fenced blocks, indented code blocks and
     * backtick spans are recognised FIRST and copied verbatim, so
     * `` `a<br>b` `` still previews as `a<br>b`. `\<` and the entities of
     * [entityAt] give their literal character, which is never re-scanned.
     * Newlines are kept; the caller folds them.
     */
    fun stripForPreview(text: String): String {
        if (text.indexOf('<') < 0 && text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var fenceChar = ' '
        var fenceLen = 0
        var prevBlank = true
        var indented = false
        var start = 0
        while (true) {
            val nl = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            var lead = start
            while (lead < nl && text[lead] == ' ') lead++
            val blank = (lead until nl).all { text[it].isWhitespace() }
            val run = if (lead - start <= 3 && lead < nl && (text[lead] == '`' || text[lead] == '~')) {
                var r = lead
                while (r < nl && text[r] == text[lead]) r++
                r - lead
            } else {
                0
            }
            val code: Boolean
            if (fenceLen > 0) {
                code = true
                if (run >= fenceLen && text[lead] == fenceChar) fenceLen = 0
            } else if (run >= 3) {
                code = true
                fenceChar = text[lead]
                fenceLen = run
            } else if (!blank && (lead - start >= 4 || (nl > start && text[start] == '\t')) && (prevBlank || indented)) {
                code = true
                indented = true
            } else {
                code = blank && indented
                if (!blank) indented = false
            }
            if (code) out.append(text, start, nl) else appendPreviewLine(text, start, nl, out)
            prevBlank = blank
            if (nl >= text.length) break
            out.append('\n')
            start = nl + 1
        }
        return out.toString()
    }

    private fun appendPreviewLine(text: String, from: Int, to: Int, out: StringBuilder) {
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '`' -> {
                    var r = i
                    while (r < to && text[r] == '`') r++
                    val n = r - i
                    // The matching run of exactly n backticks on this line.
                    var k = r
                    var close = -1
                    while (k < to) {
                        if (text[k] == '`') {
                            var e = k
                            while (e < to && text[e] == '`') e++
                            if (e - k == n) { close = e; break }
                            k = e
                        } else {
                            k++
                        }
                    }
                    val end = if (close == -1) r else close
                    out.append(text, i, end)
                    i = end
                }
                isEscapedLiteral(text, i) -> { out.append(text[i + 1]); i += 2 }
                c == '&' -> {
                    val entity = entityAt(text, i)
                    if (entity != null && i + entity.second <= to) {
                        out.append(entity.first); i += entity.second
                    } else {
                        out.append(c); i++
                    }
                }
                c == '<' && mayStartTag(text, i) -> {
                    val br = brLength(text, i)
                    val tag = if (br > 0) br else openAt(text, i)?.length ?: 0
                    if (br > 0) out.append(' ')
                    if (tag > 0 && i + tag <= to) i += tag else { out.append(c); i++ }
                }
                c == '<' && i + 1 < to && text[i + 1] == '/' && closeNameAt(text, i) != null -> {
                    i = text.indexOf('>', i) + 1
                }
                else -> { out.append(c); i++ }
            }
        }
    }

    private fun htmlName(kind: Kind): String = when (kind) {
        Kind.BOLD -> "strong"
        Kind.ITALIC -> "em"
        Kind.UNDERLINE -> "u"
        Kind.STRIKE -> "del"
        Kind.CODE -> "code"
        Kind.SUB -> "sub"
        Kind.SUP -> "sup"
    }
}
