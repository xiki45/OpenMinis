package com.openminis.app.ui.chat.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.scheduled.ScheduledTaskMarker
import com.openminis.app.ui.chat.AgentCallbackCard
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.AssistantHeaderRule
import com.openminis.app.ui.chat.InlineErrorBanner
import com.openminis.app.ui.chat.LARGE_MESSAGE_THRESHOLD_CHARS
import com.openminis.app.ui.chat.coalesceMarkdownFragments
import com.openminis.app.ui.chat.splitMarkdownIntoBlockTexts
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.HelperToolBlock
import com.openminis.app.ui.chat.ScheduledTaskCard
import com.openminis.app.ui.chat.MarkdownBlock
import com.openminis.app.ui.chat.ThinkingBlock
import com.openminis.app.ui.chat.ToolCallPill
import com.openminis.app.ui.chat.UserMessageBubble
import com.openminis.app.ui.chat.helperControlSummary

/**
 * [T-multi-select-share] What the picker and the export know about the chat: the
 * cells built from its messages, the text the height estimate needs, and one cell
 * drawn with the chat's OWN row composables. Port of the ChatExportContent /
 * ChatShareCellContent half of iOS ChatExportRenderer.swift.
 */
internal object ChatShareContent {
    /** Id of the synthetic text block for a legacy reply stored only in `content`. */
    const val LEGACY_BLOCK_ID = "legacy"

    fun source(m: ChatMessage): ChatShareUnitSource {
        val callback = m.role == "user" && AgentCallback.isCallbackText(m.content) && AgentCallback.parse(m.content) != null
        val role = when (m.role) {
            "user" -> ChatShareUnitSource.Role.USER
            "assistant" -> ChatShareUnitSource.Role.ASSISTANT
            else -> ChatShareUnitSource.Role.OTHER
        }
        return ChatShareUnitSource(m.id, role, isCallback = callback, isHidden = m.isQueued)
    }

    /** How a group starting with [cell] meets its neighbours (ChatShareSpacing). */
    fun spacingEdge(cell: ChatShareCell, message: ChatMessage): ChatShareSpacing.Edge =
        ChatShareSpacing.edge(
            cell.kind,
            isScheduledCard = cell.kind == ChatShareCell.Kind.USER && ScheduledTaskMarker.parse(message.content) != null,
        )

    /** What the chat's header rule sees in [m] (ChatShareSpacing.headerAbove). */
    fun speaker(m: ChatMessage): ChatShareSpacing.Speaker = when {
        m.role == "system" || AssistantHeaderRule.isSystemWrittenTurn(m) -> ChatShareSpacing.Speaker.SKIPPED
        m.role == "assistant" -> ChatShareSpacing.Speaker.ASSISTANT
        else -> ChatShareSpacing.Speaker.USER
    }

    /**
     * Whether the chat closes [m] with its usage row (flattenMessages: a finished
     * reply with token usage). Hidden, it has no height, but it is one more list item.
     */
    fun hasUsageRow(m: ChatMessage): Boolean = m.role == "assistant" && m.tokenUsage != null && !m.isStreaming

    /**
     * A text block as the chat lists it once a newer message exists: paragraphs
     * coalesced into rows of up to 2000 characters, each code fence a row of its own,
     * and an oversized block as one row (flattenMessages). The export draws each
     * fragment as its own MarkdownBlock at the list's row gap, so a long reply is as
     * tall as in the chat. A legacy reply (text only in `content`) is one row there.
     */
    fun textFragments(block: AssistantBlock): List<String> {
        val content = block.content
        if (block.id == LEGACY_BLOCK_ID || content.length > LARGE_MESSAGE_THRESHOLD_CHARS) return listOf(content)
        return coalesceMarkdownFragments(splitMarkdownIntoBlockTexts(content)).ifEmpty { listOf(content) }
    }

    /** The one-line title a tool capsule shows. */
    fun toolTitle(block: AssistantBlock): String = block.toolTitle.ifEmpty { block.toolName }

    /**
     * The picker's cells for a chat, from its messages as they are now. A thinking
     * block the chat hides (its thinking level is off) is not a row either, so the
     * picker shows exactly the chat's rows. [currentThinkingEnabled] answers for
     * messages restored without a level snapshot, as ChatScreen does.
     */
    fun cells(messages: List<ChatMessage>, currentThinkingEnabled: Boolean): List<ChatShareCell> {
        val units = ChatShareUnits.group(messages.map(::source))
        val blocks = mutableMapOf<String, List<ChatShareBlockSource>>()
        val errors = mutableSetOf<String>()
        for (m in messages) {
            if (m.role != "assistant") continue
            val thinkingShown = m.thinkingLevel?.isEnabled ?: currentThinkingEnabled
            val list = m.toolBlocks.map { b ->
                val kind = when (b.kind) {
                    "text" -> ChatShareBlockSource.Kind.TEXT
                    "thinking" -> if (thinkingShown) ChatShareBlockSource.Kind.THINKING else ChatShareBlockSource.Kind.HIDDEN
                    "info" -> ChatShareBlockSource.Kind.HIDDEN
                    else -> ChatShareBlockSource.Kind.TOOL
                }
                val empty = if (kind == ChatShareBlockSource.Kind.TOOL) toolTitle(b).isEmpty() else b.content.isBlank()
                ChatShareBlockSource(b.id, kind, empty)
            }.toMutableList()
            // A reply stored before text blocks existed keeps its text in `content`
            // (the chat renders it after the blocks, as AssistantLegacyContent).
            if (m.toolBlocks.none { it.kind == "text" } && m.content.isNotBlank()) {
                list += ChatShareBlockSource(LEGACY_BLOCK_ID, ChatShareBlockSource.Kind.TEXT, false)
            }
            blocks[m.id] = list
            if (!m.error.isNullOrEmpty()) errors += m.id
        }
        return ChatShareCells.cells(units, blocks, errors)
    }

    fun block(cell: ChatShareCell, message: ChatMessage): AssistantBlock? {
        val id = cell.blockId ?: return null
        if (id == LEGACY_BLOCK_ID && message.toolBlocks.none { it.id == id }) {
            return AssistantBlock(id = LEGACY_BLOCK_ID, kind = "text", content = message.content)
        }
        return message.toolBlocks.firstOrNull { it.id == id }
    }

    /** What the height estimate needs about the selected cells (their text). */
    fun estimateInputs(groups: List<List<ChatShareCell>>, byId: Map<String, ChatMessage>): List<ChatShareEstimateInput> =
        groups.flatten().mapNotNull { cell ->
            val m = byId[cell.messageId] ?: return@mapNotNull null
            val text = when (cell.kind) {
                ChatShareCell.Kind.USER -> m.content
                ChatShareCell.Kind.TEXT, ChatShareCell.Kind.THINKING, ChatShareCell.Kind.TOOL -> block(cell, m)?.content.orEmpty()
                ChatShareCell.Kind.CALLBACK -> AgentCallback.parse(m.content)?.title.orEmpty()
                ChatShareCell.Kind.NOTE -> m.error.orEmpty()
            }
            ChatShareEstimateInput(cell.kind, text)
        }
}

/** A text block's markdown split for adaptive tables: prose at the page width, each table at its own width. */
data class ChatShareTableChunk(val text: String, val widthDp: Float, val isTable: Boolean = false)

/**
 * One cell drawn with the chat's own composables, so the picker and the export look
 * like the session: user rows are UserMessageBubble, callback cards AgentCallbackCard,
 * reply pieces the same markdown renderer, tool capsules and thinking chip, all
 * inside the list's 16dp side padding. Nothing here is interactive — the picker
 * covers each row with its own touch layer, and the export has no input at all.
 * [tableChunks] (adaptive tables) swaps a text block for its prose and tables laid
 * out at their own widths.
 */
@Composable
internal fun ChatShareCellContent(
    cell: ChatShareCell,
    message: ChatMessage,
    modifier: Modifier = Modifier,
    tableChunks: List<ChatShareTableChunk>? = null,
    /** The chat's precededByUser: a user bubble right under another user-role row. */
    precededByUser: Boolean = false,
) {
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        when (cell.kind) {
            ChatShareCell.Kind.USER -> {
                val marker = ScheduledTaskMarker.parse(message.content)
                if (marker != null) {
                    ScheduledTaskCard(marker = marker)
                } else {
                    UserMessageBubble(message = message, precededByUser = precededByUser, onRetry = null)
                }
            }
            ChatShareCell.Kind.CALLBACK -> AgentCallback.parse(message.content)?.let { callback ->
                AgentCallbackCard(callback = callback, onTap = {})
            }
            ChatShareCell.Kind.TEXT -> ChatShareContent.block(cell, message)?.let { block ->
                // MarkdownBlock renders at once when the parse is cached, which the
                // export makes sure of before composing (it prewarms the text).
                if (tableChunks != null) {
                    Column {
                        for (chunk in tableChunks) {
                            MarkdownBlock(
                                rawText = chunk.text,
                                isStreaming = false,
                                modifier = Modifier.width(chunk.widthDp.dp),
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(ChatShareSpacing.ROW_GAP.dp)) {
                        for (fragment in ChatShareContent.textFragments(block)) {
                            MarkdownBlock(rawText = fragment, isStreaming = false)
                        }
                    }
                }
            }
            ChatShareCell.Kind.THINKING -> ChatShareContent.block(cell, message)?.let { block ->
                ThinkingBlock(block = block, isStreaming = false, isLast = true)
            }
            ChatShareCell.Kind.TOOL -> ChatShareContent.block(cell, message)?.let { block ->
                val toolBlocks = message.toolBlocks.filter { it.kind == "tool_use" }
                when {
                    HelperRunner.isSubAgentToolName(block.toolName) && helperControlSummary(block) != null ->
                        ToolCallPill(block, allToolBlocks = toolBlocks)
                    HelperRunner.isSubAgentToolName(block.toolName) ->
                        HelperToolBlock(block = block, onOpenDetail = {})
                    else -> ToolCallPill(block, allToolBlocks = toolBlocks)
                }
            }
            // The chat's own error row, without its Retry action.
            ChatShareCell.Kind.NOTE -> InlineErrorBanner(error = message.error.orEmpty(), onRetry = null)
        }
    }
}

/**
 * [T-multi-select-share] Builds the picker's request over this chat's messages as they
 * are now (a reply still streaming is taken as it is at this moment), with the round
 * containing [messageId] selected (null: nothing selected). Null when there is nothing
 * to share. iOS AIChatView.openSharePicker(selecting:).
 */
internal fun buildChatShareRequest(
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    messageId: String?,
    newChatTitle: String,
    context: android.content.Context,
): ChatShareRequest? {
    val messages = com.openminis.app.ui.chat.mergeStreamingOverlay(
        viewModel.uiMessages.value,
        viewModel.streamingById.value,
    )
    val cells = ChatShareContent.cells(messages, viewModel.thinkingLevel.value.isEnabled)
    if (cells.isEmpty()) return null
    val units = ChatShareUnits.group(messages.map(ChatShareContent::source))
    val unit = messageId?.substringBefore('#')?.let { ChatShareUnits.unitContaining(it, units)?.id }
    val title = viewModel.sessionTitle.value.trim()
    return ChatShareRequest(
        cells = cells,
        messages = messages,
        initialSelection = ChatShareCells.roundSelection(unit, cells),
        title = if (title.isEmpty() || title == "New Chat") newChatTitle else title,
        modelName = viewModel.modelName.value.trim().ifEmpty { null },
        loadDate = { covered -> chatShareFirstMessageDate(context.applicationContext, covered) },
    )
}

/**
 * When the first covered message was written, for the export header's date: the
 * earliest of its database rows (a merged reply carries several). Null when it
 * cannot be told (then the header shows no date, as on iOS when a part is missing).
 */
internal suspend fun chatShareFirstMessageDate(
    appContext: android.content.Context,
    covered: List<com.openminis.app.ui.chat.ChatMessage>,
): Long? {
    val first = covered.firstOrNull() ?: return null
    val ids = (listOf(first.id.substringBefore('#')) + first.sourceDbIds).distinct()
    val dao = (appContext as? com.openminis.app.MinisApp)?.chatRepositoryOrNull?.dao ?: return first.completedAt
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { dao.earliestCreatedAt(ids) }.getOrNull()
    } ?: first.completedAt
}
