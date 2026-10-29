package com.openminis.app.ui.chat

import com.openminis.app.data.db.FolderMemberTitle

/**
 * [T-autogroup-folder-titles] Up to three titles of a group's recent chats
 * after its line in the title prompt's group offer, so the title model sees
 * what a group holds instead of guessing from its name (iOS FolderOfferFormat,
 * 72350576a; Windows `folder_offer_format`, whose vectors the test reuses).
 *
 * `"Work" — desc (e.g. "A", "B", "C")`, or `"Work" (e.g. …)` without a
 * description. Groups with no titled chat keep their plain line, and when no
 * group has one the offer is byte-identical to before.
 */
internal object FolderOfferFormat {
    const val TITLES_PER_FOLDER = 3

    /** Rows kept per group: more than shown, so duplicates can be skipped. */
    const val FETCH_PER_FOLDER = 8
    const val TITLE_LIMIT = 40

    /** Only the most recently active groups get titles, so many groups do not grow the prompt without bound. */
    const val MAX_TITLED_FOLDERS = 20

    /** The prompt sentence added when some group carries example titles (iOS, verbatim). */
    const val EXAMPLES_NOTE =
        "The quoted titles after \"e.g.\" are recent chats already in that group; " +
            "they only show the group's topic and are not titles for this conversation. "

    /** A group's newest member activity (ms) and its newest-first titles. */
    data class Recent(val activity: Long, val titles: List<String>)

    /**
     * One title as shown: one line, spaces collapsed, at most [TITLE_LIMIT]
     * code points (an ellipsis marks a cut). Double quotes become single as on
     * iOS; on top of that, as the group names already are
     * ([ChatViewModel.promptSafe]), nothing in a title may close the quoted
     * example or the bracketed group list: curly double quotes become single,
     * brackets become parentheses, `;` (the list separator) a comma, braces
     * and backslashes a space, and invisible format/bidi controls go.
     */
    fun clean(title: String): String {
        val flat = title
            .replace(Regex("[\"\\u201C\\u201D]"), "'")
            .replace('[', '(').replace(']', ')')
            .replace(';', ',')
            .replace(Regex("[{}\\\\]"), " ")
            .replace(Regex("\\p{Cf}"), "")
            .replace(Regex("[\\s\\u2028\\u2029\\u00A0]+"), " ")
            .trim()
        if (flat.codePointCount(0, flat.length) <= TITLE_LIMIT) return flat
        return flat.substring(0, flat.offsetByCodePoints(0, TITLE_LIMIT - 1)) + "…"
    }

    /** Newest-first titles → at most [TITLES_PER_FOLDER] distinct, non-empty ones. */
    fun sample(titles: List<String>): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (t in titles) {
            val c = clean(t)
            if (c.isEmpty() || !seen.add(c)) continue
            out += c
            if (out.size == TITLES_PER_FOLDER) break
        }
        return out
    }

    /**
     * The offer's lines with titles added: [ids] in offer order, [lines] their
     * plain lines; [recent]: group id → activity and titles. Returns the lines
     * and how many got titles.
     */
    fun lines(ids: List<String>, lines: List<String>, recent: Map<String, Recent>): Pair<List<String>, Int> {
        // Most recent activity first; equal times keep the offer order.
        val titled = ids.withIndex()
            .mapNotNull { (i, id) -> recent[id]?.let { Triple(i, id, it.activity) } }
            .sortedWith(compareByDescending<Triple<Int, String, Long>> { it.third }.thenBy { it.first })
            .take(MAX_TITLED_FOLDERS)
            .map { it.second }
            .toSet()
        var withTitles = 0
        val out = ids.zip(lines).map { (id, line) ->
            val r = recent[id]?.takeIf { id in titled } ?: return@map line
            val examples = sample(r.titles)
            if (examples.isEmpty()) return@map line
            withTitles++
            line + " (e.g. " + examples.joinToString(", ") { "\"$it\"" } + ")"
        }
        return out to withTitles
    }

    /**
     * Rows from [com.openminis.app.data.db.ChatDao.titledFolderMembers]
     * (ordered by group, newest first) → at most [perFolder] titles per group,
     * activity = its newest titled member.
     */
    fun group(rows: List<FolderMemberTitle>, perFolder: Int = FETCH_PER_FOLDER): Map<String, Recent> {
        val out = linkedMapOf<String, Recent>()
        for (row in rows) {
            val r = out[row.folderId]
            when {
                r == null -> out[row.folderId] = Recent(row.updatedAt, listOf(row.title))
                r.titles.size < perFolder -> out[row.folderId] = r.copy(titles = r.titles + row.title)
            }
        }
        return out
    }
}
