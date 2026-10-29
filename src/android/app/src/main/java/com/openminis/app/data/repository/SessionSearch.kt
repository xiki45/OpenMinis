package com.openminis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * [T-android-search-visible-only] Session-list search: what a result shows and
 * which messages may make a session one. Port of iOS ChatStore
 * (6b0ee14c1 T-search-hit-line, bbd21900a T-search-visible-only).
 *
 * A result must show WHY it matched: its title holds the query, or a line of a
 * message the user can actually see does. Search used to match the raw
 * `parts_json` — tool-call ids and Gemini thought signatures included, random
 * base64 in which a short keyword like "gpt" turns up by chance — and then
 * showed a snippet from the oldest text hit only, so rows appeared with
 * nothing in them explaining the match.
 *
 * Pure: no database, no Android. [ChatRepository.searchSessions] runs the SQL.
 */
object SessionSearch {

    // ── Keywords ([T-search-multi-keyword], iOS b09df3824) ─────────────────
    //
    // The entry used to be ONE `LIKE '%entry%'`, spaces included: "apple
    // banana" found only sessions holding exactly that text in that order,
    // and `%` / `_` in it were wildcards ("100%" matched "1000 items", "a_b"
    // matched "axb"). Now whitespace separates keywords and a session holding
    // ANY of them is a result (iOS `SearchKeywords`; Windows
    // minis-core/src/search.rs has the same rules).
    //
    // Bind layout of every statement below, for n = terms.size:
    //   ?1 … ?n        the LIKE patterns ([likePattern]), see [searchArgs]
    //   ?n+1 … ?2n     the bare keywords (to cut the window of an oversized
    //                  row around the first one present, see [cappedParts])
    //   ?2n+1          the session id ([olderMatchesSql] only)

    /** Keywords past this many are ignored: each one adds a LIKE per message. */
    const val MAX_TERMS = 10

    /**
     * The keywords of [query]: split on any whitespace (runs of it, tabs,
     * newlines, the full-width space), empties dropped, a keyword repeated in
     * another letter case kept once, at most [MAX_TERMS]. Empty for a blank
     * query. Port of iOS `SearchKeywords.terms`.
     */
    fun searchTerms(query: String): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        val piece = StringBuilder()
        fun flush(): Boolean {
            if (piece.isEmpty()) return false
            val term = piece.toString()
            piece.setLength(0)
            if (seen.add(term.lowercase())) out.add(term)
            return out.size == MAX_TERMS
        }
        for (c in query) {
            if (!c.isWhitespace()) piece.append(c) else if (flush()) return out
        }
        flush()
        return out
    }

    /**
     * `%term%` for `LIKE … ESCAPE '\'`: `\`, `%` and `_` in the keyword are
     * matched as themselves, not as wildcards. Port of iOS `likePattern`.
     */
    fun likePattern(term: String): String {
        val escaped = StringBuilder(term.length + 2)
        for (c in term) {
            if (c == '\\' || c == '%' || c == '_') escaped.append('\\')
            escaped.append(c)
        }
        return "%$escaped%"
    }

    /** The arguments for a search statement over [terms]: patterns, then the bare keywords. */
    fun searchArgs(terms: List<String>): Array<Any> =
        (terms.map { likePattern(it) } + terms).toTypedArray()

    /**
     * `(column LIKE ?1 ESCAPE '\' OR column LIKE ?2 ESCAPE '\' …)`: [column]
     * holds any of the [termCount] patterns. Port of iOS `anyLike`.
     */
    fun anyLike(column: String, termCount: Int): String =
        (1..maxOf(1, termCount)).joinToString(" OR ", "(", ")") { "$column LIKE ?$it ESCAPE '\\'" }

    /**
     * SQL condition: message `m` holds one of the patterns in a field the user
     * can see — a text part, a tool call's input, or a tool's output. The plain
     * LIKE stays first as the cheap prefilter; `json_each` only runs on its
     * survivors. Identical to iOS `visibleHitClause(termCount:)`.
     */
    fun visibleHitClause(termCount: Int): String = """
        ${anyLike("m.parts_json", termCount)} AND EXISTS (
            SELECT 1 FROM json_each(m.parts_json) p
            WHERE (json_extract(p.value, '$.type') = 'text' AND ${anyLike("json_extract(p.value, '$.value')", termCount)})
               OR (json_extract(p.value, '$.type') = 'toolUse' AND ${anyLike("json_extract(p.value, '$.value.input')", termCount)})
               OR (json_extract(p.value, '$.type') = 'toolResult' AND ${anyLike("json_extract(p.value, '$.value.output')", termCount)}))
    """

    /**
     * [T-android-search-blob-cap] Longest `parts_json` a search query returns
     * whole. Android reads a result row through a 2 MB CursorWindow: one bigger
     * row throws SQLiteBlobTooBigException, which is also an SQLiteException,
     * so it used to be mistaken for "no JSON1" and the legacy query re-read the
     * same row and threw again, uncaught — every search for a word inside a big
     * restored tool output crashed the app. Such rows exist: iOS caps nothing,
     * and a backup restore inserts them as they are. Also bounds the memory a
     * broad query holds, since every result carries its hit message.
     */
    const val MAX_HIT_JSON_CHARS = 200_000

    /** Characters of an oversized row returned around its first hit. */
    const val HIT_WINDOW_CHARS = 4_000

    /**
     * Prefix of such a window. A real `parts_json` always starts with `[`, so
     * the mark cannot collide with one; it tells [searchSnippet] to read the
     * window as raw text, while any other unparseable row still gives no line,
     * as on iOS.
     */
    const val WINDOW_MARK = "#minis-search-window#"

    /**
     * `col` whole when it is short enough, else a [HIT_WINDOW_CHARS] window
     * starting a little before the first (ASCII case-insensitive, like LIKE)
     * occurrence of the first keyword, in the order typed, that `col` holds —
     * the keyword [searchSnippet] then takes its line from — marked with
     * [WINDOW_MARK]. The bare keywords are bound to ?n+1 … ?2n. The window is
     * not valid JSON; [searchSnippet] reads it as raw text.
     */
    fun cappedParts(col: String, termCount: Int): String {
        val n = maxOf(1, termCount)
        val firstHit = (n + 1..2 * n).joinToString(", ", "COALESCE(", ", 1)") { "NULLIF(instr(lower($col), lower(?$it)), 0)" }
        return """
        CASE WHEN length($col) <= $MAX_HIT_JSON_CHARS THEN $col
             ELSE '$WINDOW_MARK' || substr($col, max(1, $firstHit - ${HIT_WINDOW_CHARS / 4}), $HIT_WINDOW_CHARS)
        END"""
    }

    /**
     * Matching sessions (title, or a visible-field hit) with each session's
     * NEWEST such message: SQLite returns a bare column beside MAX() from that
     * row. Binds [searchArgs].
     */
    fun visibleSearchSql(termCount: Int): String = """
        WITH newest AS (
            SELECT m.session_id, m.parts_json, MAX(m.sort_order)
            FROM messages m
            WHERE ${visibleHitClause(termCount)}
            GROUP BY m.session_id)
        SELECT s.*, ${cappedParts("n.parts_json", termCount)} AS hit_parts_json
        FROM sessions s
        LEFT JOIN newest n ON n.session_id = s.id
        WHERE ${anyLike("s.title", termCount)} OR n.session_id IS NOT NULL
        ORDER BY s.updated_at DESC
    """

    /** Fallback when the SQLite build has no JSON functions: raw-JSON LIKE, as before. Binds [searchArgs]. */
    fun legacySearchSql(termCount: Int): String = """
        SELECT s.*,
               (SELECT ${cappedParts("m2.parts_json", termCount)}
                FROM messages m2
                WHERE m2.session_id = s.id AND ${anyLike("m2.parts_json", termCount)}
                ORDER BY m2.sort_order DESC LIMIT 1) AS hit_parts_json
        FROM sessions s
        WHERE ${anyLike("s.title", termCount)}
           OR EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id AND ${anyLike("m.parts_json", termCount)})
        ORDER BY s.updated_at DESC
    """

    /**
     * Up to 20 matching messages of one session, newest first (the
     * look-further-back pass). Binds [searchArgs], then the session id at
     * ?2n+1.
     */
    fun olderMatchesSql(visibleOnly: Boolean, termCount: Int): String {
        val n = maxOf(1, termCount)
        return """
        SELECT ${cappedParts("m.parts_json", n)} AS parts_json FROM messages m
        WHERE m.session_id = ?${2 * n + 1} AND ${if (visibleOnly) visibleHitClause(n) else anyLike("m.parts_json", n)}
        ORDER BY m.sort_order DESC LIMIT $OLDER_LOOKBACK
    """
    }

    /** Messages tried per session when the newest match shows nothing. */
    const val OLDER_LOOKBACK = 20

    /** Sessions per search that may take the look-further-back pass (newest first). */
    const val OLDER_LOOKBACK_SESSIONS = 40

    /**
     * The line of [text] holding the first case-insensitive hit of [query], cut
     * to at most [maxLength] characters that keep the hit visible: [lead]
     * characters before it, the rest after, "…" marking each cut. Null when
     * [text] does not contain [query]. Port of iOS `searchHitLine`.
     *
     * Always [lead] characters before the hit, even near the line's end: a
     * row shows two lines, and a window that back-fills to [maxLength] before
     * a late hit pushed the keyword out of view.
     *
     * Counted in code points (iOS counts Characters), so a window never splits
     * a surrogate pair.
     */
    fun searchHitLine(text: String, query: String, maxLength: Int = 90, lead: Int = 18): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val hit = text.indexOf(q, ignoreCase = true)
        if (hit < 0) return null
        var lineStart = hit
        while (lineStart > 0 && !isNewline(text[lineStart - 1])) lineStart--
        var lineEnd = hit + q.length
        while (lineEnd < text.length && !isNewline(text[lineEnd])) lineEnd++
        val line = text.substring(lineStart, lineEnd)

        val cpCount = line.codePointCount(0, line.length)
        val hitStart = line.codePointCount(0, hit - lineStart)
        val hitLength = line.codePointCount(hit - lineStart, hit - lineStart + q.length)
        var from = 0
        var to = cpCount
        if (cpCount > maxLength) {
            from = maxOf(0, hitStart - lead)
            to = minOf(cpCount, maxOf(from + maxLength, hitStart + hitLength))
        }
        var window = line.substring(line.offsetByCodePoints(0, from), line.offsetByCodePoints(0, to))
        // Trim the window's own edge whitespace, but never into the hit.
        val body = window.trim { it == ' ' || it == '\t' }
        if (body.contains(q, ignoreCase = true)) window = body
        return (if (from > 0) "…" else "") + window + (if (to < cpCount) "…" else "")
    }

    private fun isNewline(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' ' || c == '\u0085'

    /**
     * The string values of a tool call's JSON input, depth-first, object keys
     * in sorted order for determinism. Empty when the input is not JSON. Port
     * of iOS `toolInputStrings`.
     */
    fun toolInputStrings(input: String): List<String> {
        // org.json's tokener is lenient: it reads unquoted text ("ls -la") as a
        // string. iOS JSONSerialization rejects that, so only a real object,
        // array or quoted string that is the WHOLE input counts as JSON here.
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed[0] !in "{[\"") return emptyList()
        val root = try {
            val tokener = JSONTokener(trimmed)
            tokener.nextValue().also { if (tokener.more()) return emptyList() }
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<String>()
        fun walk(v: Any?) {
            when (v) {
                is String -> out.add(v)
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is JSONObject -> v.keys().asSequence().sorted().forEach { walk(v.opt(it)) }
            }
        }
        walk(root)
        return out
    }

    /**
     * The first keyword, in the order typed, that occurs in [text]
     * (case-insensitive). The keyword a hit line is taken from. Port of iOS
     * `SearchKeywords.firstHit`.
     */
    fun firstHit(text: String, terms: List<String>): String? =
        terms.firstOrNull { it.isNotEmpty() && text.contains(it, ignoreCase = true) }

    /**
     * Every occurrence of every keyword in [text] as char ranges, in text
     * order, with overlapping and touching occurrences joined ("gpt" and
     * "gpt-5" in "GPT-5" are one range). What a result row highlights. Port of
     * iOS `SearchKeywords.hitRanges` / Windows `hit_ranges`.
     */
    fun hitRanges(text: String, terms: List<String>): List<IntRange> {
        val found = ArrayList<IntRange>()
        for (term in terms) {
            if (term.isEmpty()) continue
            var from = 0
            while (from < text.length) {
                val at = text.indexOf(term, from, ignoreCase = true)
                if (at < 0) break
                found.add(at until at + term.length)
                from = at + term.length
            }
        }
        found.sortBy { it.first }
        val merged = ArrayList<IntRange>(found.size)
        for (r in found) {
            val last = merged.lastOrNull()
            // Inclusive ranges: touching means r.first == last.last + 1.
            if (last != null && r.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            } else {
                merged.add(r)
            }
        }
        return merged
    }

    /** One keyword: the whole trimmed [query], as before keywords were split. */
    fun searchSnippet(partsJson: String, query: String): String? =
        query.trim().takeIf { it.isNotEmpty() }?.let { searchSnippet(partsJson, listOf(it)) }

    /**
     * The display line for a matched message: the first hit in its visible
     * text, then in its tool calls' inputs, then in their outputs. Tool hits
     * are prefixed with 🔧 (and the tool name for an input). Each candidate
     * text gives the line of the first keyword, in the order typed, that it
     * holds ([firstHit]). Null when no part shows any of [terms]. Port of iOS
     * `searchSnippet(fromPartsJSON:terms:)`.
     */
    fun searchSnippet(partsJson: String, terms: List<String>): String? {
        fun hitLine(text: String): Pair<String, String>? {
            val term = firstHit(text, terms) ?: return null
            return searchHitLine(text, term)?.let { it to term }
        }
        // [T-android-search-blob-cap] The window cut out of an oversized row
        // (see MAX_HIT_JSON_CHARS) is raw JSON text: its raw line still shows
        // why the session matched, which beats dropping the result.
        if (partsJson.startsWith(WINDOW_MARK)) return hitLine(partsJson.removePrefix(WINDOW_MARK))?.first
        val parts = try {
            val arr = JSONArray(partsJson)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Exception) {
            return null
        }
        for (p in parts) {
            if (p.optString("type") != "text") continue
            val t = stripAttachmentMarkers(stripAttachedFiles(ChatRepository.stripSystemReminders(p.optString("value"))))
            // [T-md-inline-html] The snippet reads like the preview: <br> → space,
            // style tags out, code untouched. A term only the raw text holds (a
            // search for "<b>") still finds its line there.
            val shown = com.openminis.app.ui.markdown.InlineHtmlTags.stripForPreview(t)
            (hitLine(shown) ?: hitLine(t))?.let { (line, term) -> return readable(line, term) }
        }
        for (p in parts) {
            if (p.optString("type") != "toolUse") continue
            val v = p.optJSONObject("value") ?: continue
            val input = v.optString("input")
            // The input's string values (what the tool card shows), so the line
            // is real text rather than escaped JSON; the raw JSON only as a last
            // resort (a hit in a key name).
            for (text in toolInputStrings(input) + input) {
                hitLine(text)?.let { return "🔧 ${v.optString("name")}: ${it.first}" }
            }
        }
        for (p in parts) {
            if (p.optString("type") != "toolResult") continue
            val output = p.optJSONObject("value")?.optString("output").orEmpty()
            hitLine(output)?.let { return "🔧 ${it.first}" }
        }
        return null
    }

    /** Markdown syntax stripped from the line, when that keeps the hit intact. */
    internal fun readable(line: String, query: String): String {
        val plain = stripInlineMarkdown(line).trim { it == ' ' || it == '\t' }
        return if (plain.contains(query.trim(), ignoreCase = true)) plain else line
    }

    private val MD_LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
    private val MD_EMPHASIS = Regex("""(\*\*|__|~~|`)""")
    private val MD_LINE_PREFIX = Regex("""^\s*(#{1,6}\s+|>\s?|[-*+]\s+|\d+[.)]\s+)""")

    /**
     * Inline Markdown only (a single line): links and images to their text,
     * emphasis / code / strike markers, and a leading heading, quote or list
     * marker. iOS uses its full MarkdownStripper; a hit line needs no more.
     */
    internal fun stripInlineMarkdown(line: String): String {
        var s = MD_LINE_PREFIX.replace(line, "")
        s = MD_LINK.replace(s) { it.groupValues[1] }
        s = MD_EMPHASIS.replace(s, "")
        return s
    }

    private fun stripAttachedFiles(text: String): String {
        val start = text.indexOf("<user-attached-files>")
        if (start < 0) return text
        val endTag = "</user-attached-files>"
        val end = text.indexOf(endTag, start)
        return if (end >= 0) text.substring(0, start) + text.substring(end + endTag.length) else text.substring(0, start)
    }

    private val ATTACHMENT_MARKERS = listOf(
        Regex("""\[attached [A-Za-z]+:[^\]]*]"""),
        Regex("""\[image omitted to save context[^\]]*]"""),
    )

    /** iOS `stripAttachmentMarkers`: drop the model-facing attachment captions. */
    private fun stripAttachmentMarkers(text: String): String {
        if (!text.contains('[')) return text
        var out = text
        for (re in ATTACHMENT_MARKERS) out = re.replace(out, "")
        return out
    }
}
