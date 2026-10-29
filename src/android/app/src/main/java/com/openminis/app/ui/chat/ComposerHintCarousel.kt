package com.openminis.app.ui.chat

/**
 * [T-port-composer-hint-carousel] The composer's usage figures as separate
 * one-line pages that take turns, instead of one joined "context · quota" line
 * that a narrow screen or a large font cut off at the quota. Port of iOS
 * b76030535 (pages), 81488a84d (5 s, focus-rotation entries) and ffa76773a
 * (3 rounds, then settle on the context page).
 *
 * Pure (no Android / Compose types) so the timing rules are JVM-tested.
 */
internal enum class UsageHintPage { CONTEXT, QUOTA }

/** Context first, then the quota when the model has one. */
internal fun ContextUsageHint.pages(): List<UsageHintPage> =
    if (quotaText.isNullOrEmpty()) listOf(UsageHintPage.CONTEXT)
    else listOf(UsageHintPage.CONTEXT, UsageHintPage.QUOTA)

/**
 * Which page of the transient usage line is up, and when it turns.
 *
 * - A new figure set (a new [ContextUsageHint.generation]) starts on the
 *   context page with its own [ROUNDS] rounds.
 * - Every [INTERVAL_MS] the line turns to the next page while it may turn
 *   ([canTurn]). After [ROUNDS] x page-count turns it is back on the context
 *   page and stops for good for these figures.
 * - The count survives a pause (typing aside, the composer leaving the
 *   screen), so coming back does not start the rounds over.
 */
internal class ComposerHintCarousel {
    var generation: Int = -1
        private set
    var page: Int = 0
        private set
    var turns: Int = 0
        private set
    var finished: Boolean = false
        private set

    /** Adopt the figures of [generation]; a different generation starts over. */
    fun adopt(generation: Int) {
        if (generation == this.generation) return
        this.generation = generation
        page = 0
        turns = 0
        finished = false
    }

    /**
     * Whether the line may turn now: more than one page, rounds left, the line
     * up on an empty composer, and no screen reader (rotating would
     * re-announce the field over the user).
     */
    fun canTurn(pageCount: Int, lineShowing: Boolean, composerEmpty: Boolean, screenReaderOn: Boolean): Boolean =
        pageCount > 1 && !finished && lineShowing && composerEmpty && !screenReaderOn

    /** Turn to the next page; returns it. */
    fun turn(pageCount: Int): Int {
        if (pageCount <= 1 || finished) return page
        page = (page + 1) % pageCount
        turns += 1
        if (turns >= ROUNDS * pageCount) {
            finished = true
            page = 0
        }
        return page
    }

    companion object {
        const val INTERVAL_MS = 5_000L
        const val ROUNDS = 3
    }
}

/**
 * [T-port-composer-hint-carousel] The focus-rotation pool: the text entries
 * ([ComposerPlaceholderRotation.POOL_SIZE]) followed by one entry per usage
 * page of the latest figures, as on iOS. An index at or past the text entries
 * is a usage page; with no figures (or fewer pages than when the index was
 * picked) it falls back to the default entry.
 */
internal object ComposerPlaceholderPool {
    fun size(latest: ContextUsageHint?): Int =
        ComposerPlaceholderRotation.POOL_SIZE + (latest?.pages()?.size ?: 0)

    /** The usage page entry [index] stands for, or null for a text entry. */
    fun usagePage(index: Int, latest: ContextUsageHint?): UsageHintPage? {
        if (index < ComposerPlaceholderRotation.POOL_SIZE) return null
        return latest?.pages()?.getOrNull(index - ComposerPlaceholderRotation.POOL_SIZE)
    }

    /** A text entry index safe to render: a stale usage index becomes the default. */
    fun textIndex(index: Int): Int =
        if (index in 0 until ComposerPlaceholderRotation.POOL_SIZE) index else ComposerPlaceholderRotation.DEFAULT_INDEX
}
