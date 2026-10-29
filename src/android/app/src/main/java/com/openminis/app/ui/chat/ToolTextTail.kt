package com.openminis.app.ui.chat

/**
 * [T-android-tool-text-line-cap] Bounded slices of tool output for places that
 * hand it to a Compose `Text(String)`.
 *
 * A `Text` measures every paragraph it is given in full before `maxLines` or
 * `overflow` get a say: EmojiCompat scans the string, MeasuredText measures
 * every glyph and LineBreaker breaks the whole paragraph. Cutting by LINES
 * alone therefore does nothing for a single huge line. A 200 KB shell line
 * with no newline froze the main thread for 30 s in the tool preview tile
 * (Alice Q4), and because the tile re-renders the session's last tool block
 * on open, the session froze again every time it was reopened.
 *
 * Both helpers here look at a bounded window of the input and bound every
 * line they emit, so their cost and their output size are independent of how
 * large the tool output is.
 */
internal object ToolTextTail {
    /** Longest line handed to a `Text`. A 100dp tile shows ~30 chars of it. */
    const val MAX_LINE_CHARS = 400

    /**
     * How far back from the end [lastLines] looks. Lines that start before
     * this window are cut at its edge; nothing before it is ever scanned.
     */
    const val MAX_SCAN_CHARS = 8_000

    const val ELLIPSIS = "\u2026"

    /**
     * Longest line the tool detail sheet lays out. The sheet shows the whole
     * output, so a cut there keeps both ends of the line; Copy still copies
     * the untouched `block.content`.
     */
    const val DETAIL_MAX_LINE_CHARS = 4_000

    /**
     * The last [n] lines of [text], each cut to its last [maxLineChars]
     * characters with a leading [ELLIPSIS] when it was longer. A single
     * trailing newline does not count as a blank line, and a `\r` before a
     * `\n` is dropped. Only the last [maxScanChars] characters are read.
     */
    fun lastLines(
        text: String,
        n: Int,
        maxLineChars: Int = MAX_LINE_CHARS,
        maxScanChars: Int = MAX_SCAN_CHARS,
    ): String {
        if (text.isEmpty() || n <= 0) return ""
        var end = text.length
        if (text[end - 1] == '\n') end--
        val floor = surrogateSafeStart(text, (end - maxScanChars).coerceAtLeast(0), end)
        val lines = ArrayList<String>(n)
        var lineEnd = end
        while (lines.size < n) {
            // Manual scan instead of lastIndexOf: lastIndexOf would keep going
            // below `floor`, which is exactly the unbounded walk to avoid.
            var nl = lineEnd - 1
            while (nl >= floor && text[nl] != '\n') nl--
            val hitFloor = nl < floor
            val start = if (hitFloor) floor else nl + 1
            var stop = lineEnd
            if (stop > start && text[stop - 1] == '\r') stop--
            lines += tailOf(text, start, stop, maxLineChars, cutBefore = hitFloor && floor > 0)
            if (hitFloor) break
            lineEnd = nl
        }
        lines.reverse()
        return lines.joinToString("\n")
    }

    /**
     * The first [maxChars] characters of [text], with a trailing [ELLIPSIS]
     * when it was longer. For single-line headers (a shell command), where the
     * start is the informative part.
     */
    fun head(text: String, maxChars: Int = MAX_LINE_CHARS): String {
        if (text.length <= maxChars) return text
        var e = maxChars
        if (e > 0 && Character.isHighSurrogate(text[e - 1])) e--
        return text.substring(0, e) + ELLIPSIS
    }

    /**
     * [text] with every line longer than [maxLineChars] replaced by its first
     * and last halves around an [ELLIPSIS]. Returns [text] itself when no
     * line is that long, so the common case allocates nothing.
     */
    fun capLongLines(text: String, maxLineChars: Int = DETAIL_MAX_LINE_CHARS): String {
        var sb: StringBuilder? = null
        var start = 0
        var copied = 0
        while (start <= text.length) {
            var stop = text.indexOf('\n', start)
            if (stop < 0) stop = text.length
            if (stop - start > maxLineChars) {
                val b = sb ?: StringBuilder(text.length.coerceAtMost(1 shl 16)).also { sb = it }
                val half = maxLineChars / 2
                var headEnd = start + half
                if (Character.isHighSurrogate(text[headEnd - 1])) headEnd--
                val tailStart = surrogateSafeStart(text, stop - half, stop)
                b.append(text, copied, headEnd).append(' ').append(ELLIPSIS).append(' ')
                copied = tailStart
            }
            start = stop + 1
        }
        val b = sb ?: return text
        return b.append(text, copied, text.length).toString()
    }

    private fun tailOf(text: String, start: Int, stop: Int, maxChars: Int, cutBefore: Boolean): String {
        if (stop - start <= maxChars) {
            val line = text.substring(start, stop)
            return if (cutBefore) ELLIPSIS + line else line
        }
        return ELLIPSIS + text.substring(surrogateSafeStart(text, stop - maxChars, stop), stop)
    }

    /** Moves [i] forward past a low surrogate so a cut never splits a pair. */
    private fun surrogateSafeStart(text: String, i: Int, limit: Int): Int =
        if (i in 1 until limit && Character.isLowSurrogate(text[i])) i + 1 else i
}
