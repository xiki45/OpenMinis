package com.openminis.app.usage

import java.util.Locale
import kotlin.math.roundToLong

/**
 * [T-usage-step2] Display rules for the Usage page that are worth a unit test:
 * compact token counts, percentages, and the Top-N fold. Kept out of the
 * Composable so a wrong number is caught on the JVM rather than in a
 * screenshot.
 */
object UsageDisplay {

    /** Groups shown before the rest fold into "Other N" (design 3.5). */
    const val TOP_N = 8

    /**
     * 999 -> "999", 1_234 -> "1.2k", 12_000 -> "12k", 3_100_000 -> "3.1M",
     * 15_589_934_588 -> "15.6B". One decimal, dropped when it is .0; the dot is
     * always "." (the same on both platforms, whatever the locale).
     */
    fun tokens(n: Long): String {
        if (n < 1_000) return n.coerceAtLeast(0).toString()
        val (value, unit) = when {
            n < 999_950 -> n / 1_000.0 to "k"
            n < 999_950_000 -> n / 1_000_000.0 to "M"
            else -> n / 1_000_000_000.0 to "B"
        }
        val tenths = (value * 10).roundToLong()
        val text = if (tenths % 10 == 0L) (tenths / 10).toString() else String.format(Locale.ROOT, "%.1f", tenths / 10.0)
        return text + unit
    }

    /** A ratio as a whole percent; null is the em dash, a non-zero sliver "<1%". */
    fun percent(ratio: Double?): String = when {
        ratio == null || ratio.isNaN() -> "—"
        ratio > 0 && ratio < 0.005 -> "<1%"
        else -> "${(ratio * 100).roundToLong()}%"
    }

    /** Share of [total] taken by [part]; null when there is no total. */
    fun share(part: Long, total: Long): Double? = if (total > 0) part.toDouble() / total else null

    /** True for the "unknown provider" / "unknown model" group, which always sits last. */
    fun isUnknown(g: UsageGroup): Boolean =
        g.key == UsageReportBuilder.UNKNOWN_PROVIDER || g.key == UsageReportBuilder.UNKNOWN_MODEL

    data class Folded(
        val top: List<UsageGroup>,
        /** Groups past [TOP_N], in report order; empty when nothing was folded. */
        val rest: List<UsageGroup>,
        val unknown: UsageGroup?,
    ) {
        val restTotal: Long get() = rest.sumOf { it.tokens.total }
    }

    /**
     * The first [limit] known groups, the rest folded, and the unknown group
     * kept apart so it is never folded away and always renders last. The
     * builder already sorts by total, so order is preserved.
     */
    fun fold(groups: List<UsageGroup>, limit: Int = TOP_N): Folded {
        val known = groups.filterNot(::isUnknown)
        // Folding a single group into "Other 1" hides it for nothing: show it.
        val cut = if (known.size == limit + 1) known.size else limit
        return Folded(known.take(cut), known.drop(cut), groups.firstOrNull(::isUnknown))
    }
}
