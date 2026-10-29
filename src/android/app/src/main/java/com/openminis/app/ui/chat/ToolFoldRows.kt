package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-port-fold-tools] The block a list row draws, or null for rows that are
 * not one of a message's blocks (header, typing, usage, error, ...). An
 * assistant text block is drawn as one or more markdown fragments, all
 * belonging to the same block.
 */
internal fun FlatChatItem.foldBlockId(): String? = when (this) {
    is FlatChatItem.AssistantToolUse -> block.id
    is FlatChatItem.AssistantThinking -> block.id
    is FlatChatItem.AssistantMarkdownBlock -> parentBlockId
    is FlatChatItem.AssistantText -> block.id
    else -> null
}

/** The message a block row belongs to, without the defensive `#n` key suffix. */
internal fun FlatChatItem.foldMessageId(): String? = when (this) {
    is FlatChatItem.AssistantToolUse -> messageId
    is FlatChatItem.AssistantThinking -> messageId
    is FlatChatItem.AssistantMarkdownBlock -> messageId
    is FlatChatItem.AssistantText -> messageId
    else -> null
}?.substringBefore('#')

/**
 * [T-port-fold-tools] Replace each folded run's rows with one
 * [FlatChatItem.AssistantToolGroup] row, placed where its first member was.
 * An [expanded] run keeps its members after the group row, so tapping the
 * row opens it in place. Rows of other blocks are passed through untouched
 * (same instances), so LazyColumn sees no change for them. With no runs the
 * input list itself is returned.
 */
internal fun applyToolFolds(
    rows: List<FlatChatItem>,
    runsByMessage: Map<String, List<ToolFoldPlanner.FoldedRun>>,
    expanded: Set<String>,
): List<FlatChatItem> {
    if (runsByMessage.isEmpty()) return rows
    val runOfBlock = HashMap<String, HashMap<String, ToolFoldPlanner.FoldedRun>>()
    for ((mid, runs) in runsByMessage) {
        val byBlock = runOfBlock.getOrPut(mid) { HashMap() }
        for (run in runs) for (id in run.memberIds) byBlock[id] = run
    }
    val emitted = HashSet<String>()
    val out = ArrayList<FlatChatItem>(rows.size)
    for (row in rows) {
        val bid = row.foldBlockId()
        val run = bid?.let { b -> row.foldMessageId()?.let { runOfBlock[it]?.get(b) } }
        if (run == null) {
            out.add(row)
            continue
        }
        val messageId = (row.foldMessageId() ?: "")
        val isExpanded = run.key in expanded
        if (emitted.add("$messageId\u0000${run.key}")) {
            out.add(
                FlatChatItem.AssistantToolGroup(
                    messageId = messageId,
                    runKey = run.key,
                    toolCount = run.group.toolCount,
                    failedCount = run.group.failedCount,
                    cancelledCount = run.group.cancelledCount,
                    durationMs = run.durationMs,
                    expanded = isExpanded,
                ),
            )
        }
        if (isExpanded) out.add(row)
    }
    return out
}

/**
 * [T-port-fold-tools] One chat's folding state: the setting, which runs the
 * user has opened, which runs have been folded once, and whether the user is
 * browsing (scrolled up). [changes] ticks whenever an input that is not part
 * of the message list changes, so the list pipeline re-folds.
 *
 * Called from the list pipeline's collector, one call at a time.
 */
internal class ToolFoldController {
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()

    @Volatile var enabled: Boolean = false
        set(value) { if (field != value) { field = value; bump() } }

    /** True while the user is scrolled up, reading. */
    @Volatile var browsing: Boolean = false
        set(value) { if (field != value) { field = value; bump() } }

    /** Runs folded at least once: they stay folded (no hold-back). */
    private val foldedOnce = HashSet<String>()

    /**
     * The rows the last [fold] call published. Their keys are only collected
     * when a hold-back check needs them (scrolled up, a run not folded yet),
     * not on every streaming tick.
     */
    private var shownRows: List<FlatChatItem> = emptyList()

    private class Cached(val blocks: List<AssistantBlock>, val streaming: Boolean, val runs: List<ToolFoldPlanner.FoldedRun>)
    private val planCache = HashMap<String, Cached>()

    fun toggle(runKey: String) {
        _expanded.value = _expanded.value.let { if (runKey in it) it - runKey else it + runKey }
        bump()
    }

    private fun bump() { _changes.value = _changes.value + 1 }

    /**
     * [T-in-chat-search] Opens [runKey] without toggling (a search result in
     * a folded run must open it, never close an open one). iOS:
     * expandedToolGroups.insert(run.key).
     */
    fun expand(runKey: String) {
        if (runKey in _expanded.value) return
        _expanded.value = _expanded.value + runKey
        bump()
    }

    /** [T-in-chat-search] The folded run of [messageId] that holds block [blockId], if any. */
    fun runKeyContaining(messageId: String, blockId: String): String? =
        planCache[messageId]?.runs?.firstOrNull { blockId in it.memberIds }?.key

    private fun runsFor(message: ChatMessage): List<ToolFoldPlanner.FoldedRun> {
        val c = planCache[message.id]
        if (c != null && c.blocks === message.toolBlocks && c.streaming == message.isStreaming) return c.runs
        val runs = ToolFoldPlanner.foldedRuns(message)
        planCache[message.id] = Cached(message.toolBlocks, message.isStreaming, runs)
        return runs
    }

    /**
     * Fold [rows], built from [messages] (with the live overlay applied to the
     * message(s) in [live], which take precedence by id).
     */
    fun fold(rows: List<FlatChatItem>, messages: List<ChatMessage>, live: List<ChatMessage>): List<FlatChatItem> {
        if (!enabled) {
            shownRows = emptyList()
            return rows
        }
        val liveById = if (live.isEmpty()) emptyMap() else live.associateBy { it.id }
        val accepted = HashMap<String, List<ToolFoldPlanner.FoldedRun>>()
        var rowKeys: Set<String>? = null
        for (m in messages) {
            // [T-fold-live-turn] The live copy first: during a turn the stored
            // message has no tool blocks yet (they stream into the overlay), so
            // checking the stored one never folded a running task until it ended.
            val message = liveById[m.id] ?: m
            if (message.role != "assistant" || message.toolBlocks.size < ToolFoldPlanner.MINIMUM_TOOL_COUNT) continue
            val runs = runsFor(message)
            if (runs.isEmpty()) continue
            val kept = runs.filter { run ->
                val held = ToolFoldPlanner.holdsBack(
                    browsing = browsing,
                    alreadyFolded = run.key in foldedOnce,
                    inRunningMessage = message.isStreaming,
                ) {
                    val keys = rowKeys ?: shownRows.mapTo(HashSet(shownRows.size)) { it.key }.also { rowKeys = it }
                    "tool:${message.id}:${run.key}" in keys &&
                        "toolgroup:${message.id}:${run.key}" !in keys
                }
                if (!held) foldedOnce.add(run.key)
                !held
            }
            if (kept.isNotEmpty()) accepted[message.id] = kept
        }
        val out = applyToolFolds(rows, accepted, _expanded.value)
        shownRows = out
        return out
    }
}
