package com.openminis.app.usage

import java.util.Locale

/**
 * [T-usage-step1] Model-name normalization for the Usage page (spec 6.1). The
 * regular expressions are the ones in src/shared/usage/check_vectors.py, and
 * the shared vector M01 holds the examples, counterexamples included.
 */
object ModelKey {
    // Channel prefix such as `cn:` - only before a letter-led hyphenated name, so
    // Ollama tags (`qwq:32b`, `phi:latest`) are left alone.
    private val CHANNEL_PREFIX = Regex("^[a-z]{2,3}:(?=[a-z][a-z0-9.]*-)")
    // One trailing date snapshot. -MMDD only with a real month and day, so
    // YYMM version numbers (mistral-large-2411, ...-2507) stay.
    private val DATE_SUFFIX = Regex("(-20\\d{6}|-20\\d\\d-\\d\\d-\\d\\d|@20\\d{6}|-(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01]))$")
    private val LATEST_SUFFIX = Regex("-latest$")
    private val DOT_BETWEEN_DIGITS = Regex("(?<=\\d)\\.(?=\\d)")

    /** Merged key: snapshots and router spellings of one model fold together. */
    fun merged(id: String): String {
        var s = id.trim().lowercase(Locale.ROOT).substringAfterLast('/')
        s = CHANNEL_PREFIX.replaceFirst(s, "")
        s = DATE_SUFFIX.replaceFirst(s, "")
        s = LATEST_SUFFIX.replaceFirst(s, "")
        return DOT_BETWEEN_DIGITS.replace(s, "-")
    }

    /** Exact-ID mode: the trimmed raw ID, case kept. */
    fun exact(id: String): String = id.trim()

    fun of(id: String, exactModelId: Boolean): String = if (exactModelId) exact(id) else merged(id)
}
