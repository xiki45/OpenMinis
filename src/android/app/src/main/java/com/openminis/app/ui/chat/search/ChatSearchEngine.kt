package com.openminis.app.ui.chat.search

import java.text.Normalizer
import java.util.Locale

/*
 * [T-in-chat-search] The pure half of "Search in Chat": what a search unit is,
 * how a query matches its text, and how the current result moves. Port of iOS
 * ChatSearchEngine.swift (62b421d0d); Windows has the same rules
 * (minis-ui/src/chat/chat_search.rs).
 *
 * A match is identified by (unit key, ordinal) - "the Nth occurrence of the
 * query in this piece of visible text" - never by a character range. The
 * search runs over a text snapshot of the list rows, and the Text that draws
 * the highlight finds the Nth occurrence again in the text it actually shows.
 * Both sides call [ChatSearchMatcher.ranges], so as long as the two texts
 * agree on the occurrences, the ordinals agree too.
 *
 * No Android or Compose types: ChatSearchEngineTest runs it on the JVM.
 */

/**
 * One piece of visible text: a list row ([rowKey] = the FlatChatItem key) and
 * which separately drawn Text of that row ([field]). A row can draw several
 * texts (a user bubble and its file tiles, the paragraphs and list items of a
 * markdown fragment), and each is its own unit so the highlighter can find the
 * occurrence inside the Text that draws it.
 */
data class ChatSearchUnitKey(val rowKey: String, val field: String)

/** One unit's text, in display order. */
data class ChatSearchUnit(val key: ChatSearchUnitKey, val text: String)

/** The [ordinal]-th (0-based) non-overlapping occurrence of the query in a unit. */
data class ChatSearchMatch(val key: ChatSearchUnitKey, val ordinal: Int)

object ChatSearchMatcher {
    /**
     * The query as searched: surrounding whitespace dropped, inner spaces
     * kept. The whole string is ONE substring - "apple pie" does not find
     * "apple" alone (unlike the session list's keyword OR search).
     */
    fun normalizedQuery(raw: String): String = raw.trim()

    /**
     * Characters the renderer adds that no reader sees, skipped when matching
     * (iOS `normalizedScalar`): zero-width and hair spaces (CJK emphasis fix,
     * breakable inline code), the six-per-em space the inline parser pads
     * inline code with, the object replacement character (an inline formula's
     * placeholder, see ChatSearchMarkdown.searchable) and the zero-width
     * no-break space. "use `b` now" is drawn as "use b now" with a U+2006 on
     * each side of the b, and must still be found by "use b now".
     */
    fun isInvisible(c: Char): Boolean =
        c == '​' || c == ' ' || c == ' ' || c == '￼' || c == '﻿'

    /**
     * Case- and diacritic-insensitive form of [text] (iOS `.caseInsensitive,
     * .diacriticInsensitive`: "cafe" finds "Café"), with, for each folded
     * char, the original [start, end) it came from. Folding goes code point by
     * code point: NFD, combining marks dropped, lower-cased; invisible
     * characters fold to nothing.
     */
    private class Folded(val text: String, val start: IntArray, val end: IntArray)

    /**
     * [T-in-chat-search-fold-cost] Folded form per non-ASCII code point. The
     * fold of a code point never depends on its neighbours, and a chat uses a
     * few thousand distinct ones at most, so each is normalised once instead of
     * once per occurrence (a substring, Normalizer, two builders and a
     * lowercase per CJK character, on every search and every streaming
     * refresh). Capped as a guard; past the cap code points are folded fresh.
     */
    private val foldCache = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private const val FOLD_CACHE_MAX = 16_384

    private fun foldCodePoint(cp: Int): String {
        foldCache[cp]?.let { return it }
        val nfd = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
        val base = StringBuilder(nfd.length)
        var k = 0
        while (k < nfd.length) {
            val q = nfd.codePointAt(k)
            if (Character.getType(q) != Character.NON_SPACING_MARK.toInt()) base.appendCodePoint(q)
            k += Character.charCount(q)
        }
        val folded = base.toString().lowercase(Locale.ROOT)
        if (foldCache.size < FOLD_CACHE_MAX) foldCache[cp] = folded
        return folded
    }

    private fun fold(text: String): Folded {
        val out = StringBuilder(text.length)
        // Primitive arrays (grown by doubling): the boxed lists cost two
        // Integer objects per folded char.
        var starts = IntArray(text.length.coerceAtLeast(1))
        var ends = IntArray(starts.size)
        var count = 0
        fun push(start: Int, end: Int) {
            if (count == starts.size) {
                starts = starts.copyOf(count * 2)
                ends = ends.copyOf(count * 2)
            }
            starts[count] = start
            ends[count] = end
            count++
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val c0 = text[i]
            if (n == 1 && isInvisible(c0)) {
                i += n
                continue
            }
            if (cp < 0x80) {
                // ASCII fast path: no marks to drop, no allocation.
                out.append(if (c0 in 'A'..'Z') c0 + 32 else c0)
                push(i, i + n)
            } else {
                for (ch in foldCodePoint(cp)) {
                    out.append(ch)
                    push(i, i + n)
                }
            }
            i += n
        }
        return Folded(out.toString(), starts.copyOf(count), ends.copyOf(count))
    }

    /** The folded form of a query (also drops its invisible characters). */
    fun foldQuery(query: String): String = fold(query).text

    /**
     * Non-overlapping occurrences of [query] in [text], first to last, at most
     * [limit], as ranges of [text] (end exclusive: `start until end`). Empty
     * for an empty query.
     */
    fun ranges(query: String, text: String, limit: Int = Int.MAX_VALUE): List<IntRange> {
        if (query.isEmpty() || text.isEmpty() || limit <= 0) return emptyList()
        val q = foldQuery(query)
        if (q.isEmpty()) return emptyList()
        val f = fold(text)
        val out = ArrayList<IntRange>()
        var from = 0
        while (from <= f.text.length - q.length) {
            val at = f.text.indexOf(q, from)
            if (at < 0) break
            out.add(f.start[at] until f.end[at + q.length - 1])
            if (out.size >= limit) break
            from = at + q.length
        }
        return out
    }

    /** Number of occurrences, capped at [limit]. */
    fun count(query: String, text: String, limit: Int): Int = ranges(query, text, limit).size
}

data class ChatSearchResult(
    /** Every match in display order (unit order, then ordinal). */
    val matches: List<ChatSearchMatch>,
    /** Matches per unit, for the highlighter. */
    val countByUnit: Map<ChatSearchUnitKey, Int>,
    /** True when the total limit cut the list short ("2000+"). */
    val truncated: Boolean,
) {
    companion object {
        val EMPTY = ChatSearchResult(emptyList(), emptyMap(), false)
    }
}

object ChatSearchEngine {
    /** Highlights drawn in one unit; also the matches counted for it. */
    const val PER_UNIT_LIMIT = 200

    /** Matches kept for the whole chat; the count then reads "2000+". */
    const val TOTAL_LIMIT = 2000

    /**
     * Searches [units] (already in display order). Returns null when
     * [isCancelled] turns true part-way, so a stale run never publishes.
     */
    fun search(
        units: List<ChatSearchUnit>,
        query: String,
        perUnitLimit: Int = PER_UNIT_LIMIT,
        totalLimit: Int = TOTAL_LIMIT,
        isCancelled: () -> Boolean = { false },
    ): ChatSearchResult? {
        val q = ChatSearchMatcher.normalizedQuery(query)
        if (q.isEmpty() || ChatSearchMatcher.foldQuery(q).isEmpty()) return ChatSearchResult.EMPTY
        val matches = ArrayList<ChatSearchMatch>()
        val countByUnit = HashMap<ChatSearchUnitKey, Int>()
        var truncated = false
        for ((i, unit) in units.withIndex()) {
            if (i % 64 == 0 && isCancelled()) return null
            val remaining = totalLimit - matches.size
            if (remaining <= 0) {
                // Only report truncation when something was actually dropped.
                if (ChatSearchMatcher.count(q, unit.text, 1) > 0) {
                    truncated = true
                    break
                }
                continue
            }
            val n = ChatSearchMatcher.count(q, unit.text, minOf(perUnitLimit, remaining + 1))
            if (n <= 0) continue
            val kept = minOf(n, remaining, perUnitLimit)
            if (n > remaining) truncated = true
            countByUnit[unit.key] = kept
            for (ordinal in 0 until kept) matches.add(ChatSearchMatch(unit.key, ordinal))
        }
        return ChatSearchResult(matches, countByUnit, truncated)
    }
}

/**
 * Moving through the results. Index 0 is the earliest match; "next" goes
 * toward newer messages, "previous" toward older ones, both wrapping.
 */
object ChatSearchNavigation {
    data class Step(val index: Int, val wrapped: Boolean)

    /** Where a fresh search lands: the newest match (a chat reads bottom-up). */
    fun initialIndex(count: Int): Int? = if (count > 0) count - 1 else null

    /** One step from [index]; [Step.wrapped] when it went past an end and came round. */
    fun step(index: Int?, count: Int, forward: Boolean): Step? {
        if (count <= 0) return null
        if (index == null || index < 0 || index >= count) return Step(if (forward) 0 else count - 1, false)
        if (forward) return if (index + 1 < count) Step(index + 1, false) else Step(0, count > 1)
        return if (index > 0) Step(index - 1, false) else Step(count - 1, count > 1)
    }

    /**
     * The current match after the results were recomputed (new content
     * streamed in): the same match if it still exists, otherwise the old
     * index clamped to the new count.
     */
    fun relocate(previous: ChatSearchMatch?, previousIndex: Int?, matches: List<ChatSearchMatch>): Int? {
        if (matches.isEmpty()) return null
        if (previous != null) {
            val i = matches.indexOf(previous)
            if (i >= 0) return i
        }
        if (previousIndex == null) return null
        return previousIndex.coerceIn(0, matches.size - 1)
    }

    /** The "3/12" counter (1-based), "2000+" when truncated. */
    fun counterText(index: Int?, count: Int, truncated: Boolean): String {
        val total = if (truncated) "$count+" else "$count"
        if (index == null || count <= 0) return "0/$total"
        return "${index + 1}/$total"
    }
}
