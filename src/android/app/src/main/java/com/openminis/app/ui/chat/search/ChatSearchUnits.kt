package com.openminis.app.ui.chat.search

import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatSearchPiece
import com.openminis.app.ui.chat.FlatChatItem
import com.openminis.app.ui.chat.chatSearchMarkdownPieces
import com.openminis.app.ui.chat.isModelSwitchNotice

/*
 * [T-in-chat-search] Which texts of a conversation "Search in Chat" looks at,
 * and the strings those texts are. Rule (iOS ChatSearchUnits): what the list
 * shows is searchable, what it hides is not.
 *
 *   searched                               not searched
 *   ---------------------------------      ---------------------------------
 *   user bubble text                       thinking
 *   user file tile names                   tool command / output (detail sheet)
 *   assistant text, as rendered            usage, timestamps, buttons, labels
 *   tool capsule line                      list bullets and numbers
 *   info rows (notices, dividers)          the "Ran N tools" row of a fold
 *   footer error
 *   agent callback title
 *   compacted history (same rows, dimmed)
 *
 * Not yet (phase 2, see the A2c report): a sub agent card's title / status, an
 * answered user_ask card, a scheduled task card, a model-switch notice card -
 * their texts are worked out
 * inside composables that would need the same treatment as the capsule line.
 *
 * Units are built from the list rows BEFORE tool runs are folded, so a match
 * inside a folded run is found and navigation opens the run.
 */
internal object ChatSearchUnits {
    const val BODY = "body"
    const val ERROR = "error"
    const val TITLE = "title"
    fun attachment(index: Int) = "att:$index"
    fun markdown(path: String) = "md:$path"

    /** Field prefix of a markdown piece; the rest is the RenderBlock path. */
    const val MARKDOWN_PREFIX = "md:"

    /** Texts worked out outside this builder (Android resources, the markdown renderer). */
    interface TextSource {
        /** The pieces a markdown fragment draws; default: the renderer's own. */
        fun markdownPieces(raw: String): List<ChatSearchPiece> = chatSearchMarkdownPieces(raw)

        /** The tool capsule line, or null when the row is not a plain capsule. */
        fun toolCapsule(block: AssistantBlock): String?
    }

    /** Cached pieces of one markdown row: reused while its text is unchanged. */
    class PieceCacheEntry(val raw: String, val pieces: List<ChatSearchPiece>)

    /**
     * Every searchable text of [rows], in display order (oldest first, as the
     * rows are built). [cache] is read and a fresh one returned; null when
     * [isCancelled] turns true part-way.
     */
    fun build(
        rows: List<FlatChatItem>,
        source: TextSource,
        cache: Map<String, PieceCacheEntry> = emptyMap(),
        isCancelled: () -> Boolean = { false },
    ): Pair<List<ChatSearchUnit>, Map<String, PieceCacheEntry>>? {
        val units = ArrayList<ChatSearchUnit>(rows.size * 2)
        val newCache = HashMap<String, PieceCacheEntry>()
        fun add(row: String, field: String, text: String) {
            if (text.isNotEmpty()) units.add(ChatSearchUnit(ChatSearchUnitKey(row, field), text))
        }
        fun markdown(row: String, raw: String) {
            if (raw.isEmpty()) return
            // Same string instance or equal text: the parse is reused.
            val hit = cache[row]?.takeIf { it.raw == raw }
            val pieces = hit?.pieces ?: source.markdownPieces(raw)
            newCache[row] = hit ?: PieceCacheEntry(raw, pieces)
            for (p in pieces) add(row, markdown(p.path), p.text)
        }
        for ((i, row) in rows.withIndex()) {
            if (i % 32 == 0 && isCancelled()) return null
            when (row) {
                is FlatChatItem.UserBubble -> {
                    val m = row.message
                    // File tiles sit above the bubble; image tiles show no name
                    // (UserAttachmentList: the first imageUris.size names are images).
                    m.attachmentNames.drop(m.imageUris.size).forEachIndexed { k, name -> add(row.key, attachment(k), name) }
                    if (m.content.isNotBlank()) add(row.key, BODY, m.content)
                }
                is FlatChatItem.AgentCallbackCard -> add(row.key, TITLE, row.callback.title)
                is FlatChatItem.AssistantMarkdownBlock -> markdown(row.key, row.rawText)
                is FlatChatItem.AssistantText -> markdown(row.key, row.block.content)
                is FlatChatItem.AssistantLegacyContent -> markdown(row.key, row.content)
                is FlatChatItem.AssistantToolUse -> source.toolCapsule(row.block)?.let { add(row.key, BODY, it) }
                // A model-switch notice is drawn as a card of separate lines
                // (ModelSwitchNoticeCard), not this one text: phase 2.
                is FlatChatItem.AssistantInfo ->
                    if (!isModelSwitchNotice(row.block)) add(row.key, BODY, row.block.content)
                is FlatChatItem.AssistantError -> add(row.key, ERROR, row.error)
                is FlatChatItem.AssistantHeader, is FlatChatItem.AssistantThinking,
                is FlatChatItem.AssistantToolGroup, is FlatChatItem.AssistantTyping,
                is FlatChatItem.AssistantUsage, is FlatChatItem.ScheduledTaskCardItem -> Unit
            }
        }
        return units to newCache
    }
}
