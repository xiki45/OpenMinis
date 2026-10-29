package com.openminis.app.ui.chat.search

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.FlatChatItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a highlighting Text needs: the query, how many matches each unit has,
 * and which one is current. One immutable value, so moving the current match
 * recomposes only the readers whose unit gained or lost the current match.
 */
@Stable
internal data class ChatSearchHighlight(
    val query: String,
    val countByUnit: Map<ChatSearchUnitKey, Int>,
    val current: ChatSearchMatch?,
) {
    fun count(key: ChatSearchUnitKey): Int = countByUnit[key] ?: 0

    /** The current ordinal in [key], or -1. */
    fun currentOrdinal(key: ChatSearchUnitKey): Int = if (current?.key == key) current.ordinal else -1

    /** True when any unit of [rowKey] holds the current match. */
    fun rowIsCurrent(rowKey: String): Boolean = current?.key?.rowKey == rowKey

    companion object {
        val NONE = ChatSearchHighlight("", emptyMap(), null)
    }
}

/**
 * [T-in-chat-search] State of "Search in Chat" for one open conversation: the
 * query, the results, the current one, and the work that keeps them fresh.
 * Port of iOS ChatSearchController (62b421d0d).
 *
 * Held by ChatScreen (remember(sessionId)), not by ChatViewModel: typing a
 * query or stepping through results must redraw the search bar and a couple
 * of highlighted texts, not the chat page.
 *
 * Flow:
 *   query edit --250 ms--> units from the rows (off-main, cached) --> search
 *     --> newest match becomes current, [navigations] asks the list to scroll
 *   next / previous --> move current (wrapping, with a notice), scroll
 *   rows change while open --300 ms throttle--> re-search; the current match
 *     is kept by identity, nothing scrolls
 *   done --> highlights cleared, scroll position left where it is
 */
@Stable
internal class ChatSearchController(
    private val scope: CoroutineScope,
    private val source: ChatSearchUnits.TextSource,
    /** Where units are built and searched; injectable for tests. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
) {
    enum class Notice { WRAPPED_TO_FIRST, WRAPPED_TO_LAST }

    var isActive by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var isTruncated by mutableStateOf(false)
        private set
    var currentIndex by mutableStateOf<Int?>(null)
        private set
    /** A search for the CURRENT query has not come back yet. */
    var isSearching by mutableStateOf(false)
        private set
    /** Short-lived "wrapped round" message. */
    var notice by mutableStateOf<Notice?>(null)
        private set
    /** Bumped to ask the search field to take focus. */
    var focusRequest by mutableIntStateOf(0)
        private set
    var highlight by mutableStateOf(ChatSearchHighlight.NONE)
        private set

    private var matches: List<ChatSearchMatch> = emptyList()

    /** The current match each time the list should scroll to it. */
    private val _navigations = MutableSharedFlow<ChatSearchMatch>(extraBufferCapacity = 4)
    val navigations: SharedFlow<ChatSearchMatch> = _navigations

    val currentMatch: ChatSearchMatch? get() = currentIndex?.let { matches.getOrNull(it) }
    val counterText: String get() = ChatSearchNavigation.counterText(currentIndex, matchCount, isTruncated)

    /** The row [key] in the rows last published (before folding), or null. */
    fun rowFor(key: String): FlatChatItem? = rows.firstOrNull { it.key == key }

    /** The rows (before tool folding) as last published by the list pipeline. Main thread. */
    private var rows: List<FlatChatItem> = emptyList()
    private var searchedSignature: Int? = null
    private var pieceCache: Map<String, ChatSearchUnits.PieceCacheEntry> = emptyMap()

    private var pending: Job? = null
    private var searchJob: Job? = null
    private var refreshJob: Job? = null
    private var noticeJob: Job? = null
    private var generation = 0

    // ── Open / close ──────────────────────────────────────────────────────

    /**
     * Shows the bar. A non-blank [prefill] (the text selected when the user
     * chose "Search in Chat") becomes the query and is searched at once;
     * otherwise the field takes focus with the previous query kept.
     */
    fun activate(prefill: String? = null) {
        val trimmed = prefill?.let { ChatSearchMatcher.normalizedQuery(it) }.orEmpty()
        if (!isActive) {
            isActive = true
            AppLogger.info(TAG, "[ChatSearch] open prefill=${trimmed.length} chars")
        }
        focusRequest++
        if (trimmed.isNotEmpty() && trimmed != query) {
            query = trimmed
            schedule(0, landOnNewest = true)
        }
    }

    /** Closes the bar: highlights go, the scroll position stays. */
    fun deactivate() {
        if (!isActive) return
        AppLogger.info(TAG, "[ChatSearch] close query=${query.length} chars results=${matches.size}")
        isActive = false
        cancelAll()
        query = ""
        apply(ChatSearchResult.EMPTY, "", landOnNewest = false)
        notice = null
    }

    fun updateQuery(value: String) {
        if (value == query) return
        query = value
        if (isActive) schedule(TYPING_DEBOUNCE_MS, landOnNewest = true)
    }

    // ── Moving between results ────────────────────────────────────────────

    /** Toward newer messages (down the list); wraps to the first. */
    fun next() = step(forward = true)

    /** Toward older messages (up the list); wraps to the last. */
    fun previous() = step(forward = false)

    private fun step(forward: Boolean) {
        val moved = ChatSearchNavigation.step(currentIndex, matches.size, forward) ?: return
        currentIndex = moved.index
        highlight = highlight.copy(current = currentMatch)
        if (moved.wrapped) showNotice(if (forward) Notice.WRAPPED_TO_FIRST else Notice.WRAPPED_TO_LAST)
        navigateToCurrent()
    }

    // ── Rows from the list pipeline ───────────────────────────────────────

    /**
     * The list pipeline's rows, before tool runs are folded. While the bar
     * is open, a change re-runs the search at most every 300 ms; the current
     * match is kept by identity and the list is not scrolled, so streaming
     * never moves what the user is reading.
     */
    fun onRows(newRows: List<FlatChatItem>) {
        // [T-in-chat-search-refresh] Only the rows are kept here: this runs on
        // the main thread on every flatten tick (~80 ms while streaming), also
        // when the bar was never opened, so the O(rows) signature is taken
        // only when a refresh is about to decide.
        rows = newRows
        if (!isActive || ChatSearchMatcher.normalizedQuery(query).isEmpty()) return
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            delay(STREAMING_THROTTLE_MS)
            // [T-in-chat-search-refresh] A refresh never cancels a search in
            // flight: on a long chat one search can outlast the 300 ms
            // throttle, and cancelling it from every refresh meant no result
            // landed for the whole streamed turn while the CPU redid the
            // search back to back. Wait for it, then refresh if the rows
            // moved on since. Only a new query cancels a running search.
            searchJob?.takeIf { it.isActive }?.join()
            // A query search still pending owns the next result; do not race it.
            // (this@: inside a coroutine a bare isActive is the coroutine's.)
            if (!this@ChatSearchController.isActive || pending?.isActive == true || isSearching) return@launch
            if (signature(rows) == searchedSignature) return@launch
            runSearch(landOnNewest = false)
        }
    }

    /**
     * Cheap fingerprint of what the units read: row keys and the lengths of
     * their texts. Enough to notice streaming, new rows, compaction, errors.
     */
    private fun signature(rows: List<FlatChatItem>): Int {
        var h = rows.size
        for (r in rows) {
            h = h * 31 + r.key.hashCode()
            h = h * 31 + when (r) {
                is FlatChatItem.AssistantMarkdownBlock -> r.rawText.length
                is FlatChatItem.AssistantText -> r.block.content.length
                is FlatChatItem.AssistantLegacyContent -> r.content.length
                is FlatChatItem.UserBubble -> r.message.content.length + r.message.attachmentNames.size
                is FlatChatItem.AssistantToolUse -> r.block.toolTitle.length + r.block.content.length
                is FlatChatItem.AssistantInfo -> r.block.content.length
                is FlatChatItem.AssistantError -> r.error.length
                else -> 0
            }
        }
        return h
    }

    // ── Searching ─────────────────────────────────────────────────────────

    private fun cancelAll() {
        pending?.cancel(); pending = null
        searchJob?.cancel(); searchJob = null
        refreshJob?.cancel(); refreshJob = null
        generation++
        isSearching = false
    }

    /**
     * Runs a search after [delayMs], replacing any search not yet finished.
     * [landOnNewest]: a new query moves to the newest match and scrolls; a
     * refresh after new content keeps the current match and does not scroll.
     */
    private fun schedule(delayMs: Long, landOnNewest: Boolean) {
        pending?.cancel()
        if (landOnNewest) {
            // A new query invalidates whatever is in flight right away, so a
            // slow old search can never land after the user typed on.
            searchJob?.cancel()
            generation++
            isSearching = ChatSearchMatcher.normalizedQuery(query).isNotEmpty()
        }
        pending = scope.launch {
            if (delayMs > 0) delay(delayMs)
            runSearch(landOnNewest)
        }
    }

    private fun runSearch(landOnNewest: Boolean) {
        pending = null
        val q = ChatSearchMatcher.normalizedQuery(query)
        if (!isActive || q.isEmpty()) {
            searchJob?.cancel()
            isSearching = false
            apply(ChatSearchResult.EMPTY, q, landOnNewest)
            return
        }
        searchJob?.cancel()
        val gen = ++generation
        val snapshot = rows
        val sig = signature(snapshot)
        val cache = pieceCache
        val started = System.nanoTime()
        searchJob = scope.launch {
            val outcome = withContext(background) {
                // Bare isActive here is the coroutine's: a cancelled (stale) run stops.
                val built = ChatSearchUnits.build(snapshot, source, cache) { !isActive } ?: return@withContext null
                val result = ChatSearchEngine.search(built.first, q) { !isActive } ?: return@withContext null
                Triple(built.first.size, built.second, result)
            } ?: return@launch
            if (gen != generation || !this@ChatSearchController.isActive) return@launch
            pieceCache = outcome.second
            searchedSignature = sig
            searchJob = null
            AppLogger.info(
                TAG,
                "[ChatSearch] searched units=${outcome.first} matches=${outcome.third.matches.size}" +
                    "${if (outcome.third.truncated) "+" else ""} ms=${(System.nanoTime() - started) / 1_000_000} " +
                    "landOnNewest=$landOnNewest",
            )
            apply(outcome.third, q, landOnNewest)
        }
    }

    private fun apply(result: ChatSearchResult, q: String, landOnNewest: Boolean) {
        val old = currentMatch
        val oldIndex = currentIndex
        matches = result.matches
        matchCount = result.matches.size
        isTruncated = result.truncated
        isSearching = false
        currentIndex = if (landOnNewest) ChatSearchNavigation.initialIndex(matches.size)
            else ChatSearchNavigation.relocate(old, oldIndex, matches)
        highlight = if (result.matches.isEmpty()) ChatSearchHighlight.NONE
            else ChatSearchHighlight(q, result.countByUnit, currentMatch)
        if (landOnNewest) navigateToCurrent()
    }

    private fun navigateToCurrent() {
        currentMatch?.let { _navigations.tryEmit(it) }
    }

    // ── Notices ───────────────────────────────────────────────────────────

    private fun showNotice(n: Notice) {
        notice = n
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            notice = null
        }
    }

    companion object {
        private const val TAG = "ChatSearch"
        const val TYPING_DEBOUNCE_MS = 250L
        const val STREAMING_THROTTLE_MS = 300L
        const val NOTICE_MS = 1_600L
    }
}
