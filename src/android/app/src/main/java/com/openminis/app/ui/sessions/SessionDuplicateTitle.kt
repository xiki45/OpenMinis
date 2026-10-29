package com.openminis.app.ui.sessions

/**
 * [ISS-038] Title of a duplicated session. Port of iOS SessionDuplicateTitle
 * (03da252a1); pure, so the rules are unit-tested as shipped.
 *
 * - Format: "<base> (<word>)" for the first copy, "<base> (<word> N)" for
 *   N >= 2, with ASCII parentheses and a space before them. The word is
 *   localized ("Copy", "副本").
 * - Base: the source title minus ONE trailing copy suffix, so copying
 *   "Foo (Copy 2)" never gives "Foo (Copy 2) (Copy)". A trailing suffix is
 *   "(" or a full-width "(", a known word in any shipped language
 *   (case-insensitive), an optional integer >= 2 without a leading zero, then
 *   ")" or its full-width form, at the very end after trimming. Anything else
 *   is part of the title: "Foo (draft)", "Foo (Copy) notes", "Foo (Copy 2x)",
 *   "Foo (Copy 0)", "Foo (Copy 1)". A title that is nothing but a suffix keeps
 *   itself as the base.
 * - Number: the SMALLEST free number among existing titles with the same base,
 *   in any language - deleting "Foo (Copy 2)" and copying again refills 2.
 * - Blank source title: [untitledTitle] is the base.
 */
internal object SessionDuplicateTitle {

    fun make(
        sourceTitle: String?,
        existingTitles: Collection<String>,
        suffixWord: String,
        knownSuffixWords: List<String>,
        untitledTitle: String,
    ): String {
        val words = recognizedWords(suffixWord, knownSuffixWords)
        val trimmed = (sourceTitle ?: "").trim()
        val base = if (trimmed.isEmpty()) untitledTitle else (parse(trimmed, words)?.first ?: trimmed)
        val taken = HashSet<Int>()
        for (title in existingTitles) {
            val p = parse(title, words) ?: continue
            if (p.first == base) taken.add(p.second)
        }
        var number = 1
        while (number in taken) number++
        return format(base, suffixWord, number)
    }

    fun format(base: String, word: String, number: Int): String =
        if (number <= 1) "$base ($word)" else "$base ($word $number)"

    fun recognizedWords(suffixWord: String, known: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (w in listOf(suffixWord, "Copy") + known) {
            val t = w.trim()
            if (t.isEmpty() || !seen.add(t.lowercase())) continue
            out.add(t)
        }
        return out
    }

    /** (base, number) when [title] ends with a copy suffix; number 1 without a number. Null otherwise. */
    fun parse(title: String, words: List<String>): Pair<String, Int>? {
        val t = title.trim()
        val last = t.lastOrNull() ?: return null
        if (last != ')' && last != '）') return null
        val open = t.indexOfLast { it == '(' || it == '（' }
        if (open < 0) return null
        val inner = t.substring(open + 1, t.length - 1).trim()
        val base = t.substring(0, open).trim()
        if (base.isEmpty()) return null
        for (word in words) {
            if (inner.equals(word, ignoreCase = true)) return base to 1
            if (inner.length <= word.length || !inner.regionMatches(0, word, 0, word.length, ignoreCase = true)) continue
            val rest = inner.substring(word.length)
            if (rest[0] != ' ' && rest[0] != '　') continue
            val digits = rest.substring(1).trim()
            if (digits.isEmpty() || digits.length > 9 || digits.startsWith("0") || !digits.all { it in '0'..'9' }) continue
            val n = digits.toInt()
            if (n < 2) continue
            return base to n
        }
        return null
    }
}
