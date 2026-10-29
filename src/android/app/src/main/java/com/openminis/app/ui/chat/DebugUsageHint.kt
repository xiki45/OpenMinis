package com.openminis.app.ui.chat

/**
 * [T-port-composer-hint-debug] Builds a composer usage line with chosen
 * figures, for the DEV_TOOLS-only `debug.chat.usageHint` RPC. The real line
 * only appears once a turn ends at 70%+ of the context window, which is hard
 * to reach on a device; this lets the carousel (two pages, 5 s x 3 rounds),
 * the shrink-to-fit and the focus-rotation entries be tested directly.
 * Pure, so the figures are unit-tested.
 */
internal object DebugUsageHint {
    /** A quota page given by hand. */
    data class Quota(val text: String, val highlight: String?, val tier: ContextUsage.Tier)

    /** The fake quota page used when the caller asks for one without text. */
    val SAMPLE_QUOTA = Quota("Quota 5h 14% / 7d 32% used", "14%", ContextUsage.Tier.NORMAL)

    fun build(generation: Int, percent: Int, windowTokens: Int, quota: Quota?): ContextUsageHint {
        val window = windowTokens.coerceAtLeast(1)
        val used = (window.toLong() * percent.coerceIn(1, 100) / 100).toInt().coerceAtLeast(1)
        val usage = ContextUsage(used, window)
        return ContextUsageHint(
            generation = generation,
            text = "",
            highlights = listOf("${usage.percent}%", TokenCountFormatter.sizePair(usage)),
            tier = usage.tier,
            quotaText = quota?.text,
            quotaHighlight = quota?.highlight,
            quotaTier = quota?.tier ?: ContextUsage.Tier.NORMAL,
        )
    }

    fun tierOf(name: String?): ContextUsage.Tier = when (name?.lowercase()) {
        "warning", "low" -> ContextUsage.Tier.WARNING
        "critical", "depleted" -> ContextUsage.Tier.CRITICAL
        else -> ContextUsage.Tier.NORMAL
    }
}
