package com.openminis.app.ui.chat.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/*
 * [T-in-chat-search] Painting the matches where they are drawn (iOS
 * ChatSearchHighlights): each Text that is a search unit looks up its own
 * count in [ChatSearchHighlight], finds those occurrences again in the very
 * AnnotatedString it draws (ChatSearchMarkdown.searchable + the same
 * matcher), and adds a background span. Only spans are added - the text is
 * unchanged, so selection, copy and the streaming fade keep their offsets.
 */

/** The current search highlight, provided once above the message list. */
internal val LocalChatSearchHighlight = compositionLocalOf { ChatSearchHighlight.NONE }

/** The list row (FlatChatItem key) the composable belongs to; null outside the chat list. */
internal val LocalChatSearchRow = compositionLocalOf<String?> { null }

/** Background of every match, and of the current one (iOS: yellow / orange). */
internal val ChatSearchMatchBackground = Color(0x66FFD60A)
internal val ChatSearchCurrentBackground = Color(0xCCFF9F0A)

/**
 * [text] with the matches of [key] painted: the first `count(key)`
 * occurrences of the query, the current one in [current], the others in
 * [match]. [offset] shifts the search start: a unit that is the tail of the
 * drawn string (an agent callback's title after "Agent result · ").
 */
internal fun applyChatSearchHighlight(
    text: AnnotatedString,
    key: ChatSearchUnitKey,
    highlight: ChatSearchHighlight,
    match: SpanStyle = SpanStyle(background = ChatSearchMatchBackground),
    current: SpanStyle = SpanStyle(background = ChatSearchCurrentBackground),
    offset: Int = 0,
): AnnotatedString {
    val count = highlight.count(key)
    if (count <= 0 || offset > text.length) return text
    val searchable = ChatSearchMarkdown.searchable(text).substring(offset)
    val ranges = ChatSearchMatcher.ranges(highlight.query, searchable, count)
    if (ranges.isEmpty()) return text
    val currentOrdinal = highlight.currentOrdinal(key)
    return buildAnnotatedString {
        append(text)
        ranges.forEachIndexed { i, r ->
            addStyle(if (i == currentOrdinal) current else match, offset + r.first, offset + r.last + 1)
        }
    }
}

/** Highlight for a plain string unit of the current row. */
@Composable
internal fun rememberChatSearchHighlighted(text: String, field: String, offset: Int = 0): AnnotatedString {
    val row = LocalChatSearchRow.current
    val h = LocalChatSearchHighlight.current
    return remember(text, row, field, h, offset) {
        val base = AnnotatedString(text)
        if (row == null) base else applyChatSearchHighlight(base, ChatSearchUnitKey(row, field), h, offset = offset)
    }
}

/** Highlight for an AnnotatedString unit of the current row (markdown pieces). */
@Composable
internal fun rememberChatSearchHighlighted(text: AnnotatedString, field: String?): AnnotatedString {
    val row = LocalChatSearchRow.current
    val h = LocalChatSearchHighlight.current
    if (row == null || field == null || h.countByUnit.isEmpty()) return text
    return remember(text, row, field, h) { applyChatSearchHighlight(text, ChatSearchUnitKey(row, field), h) }
}

/** True when the current match is in [field] of the current row (whole-block outline). */
@Composable
internal fun chatSearchFieldIsCurrent(field: String?): Boolean {
    val row = LocalChatSearchRow.current ?: return false
    if (field == null) return false
    return LocalChatSearchHighlight.current.current?.key == ChatSearchUnitKey(row, field)
}

/**
 * Provides the highlight and this row's key to one list row. The highlight is
 * read HERE, in this small scope, so moving the current match recomposes this
 * wrapper and the Texts that read [LocalChatSearchHighlight] - not the chat
 * screen and not the row's own content.
 */
@Composable
internal fun ChatSearchRowScope(controller: ChatSearchController, rowKey: String, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalChatSearchHighlight provides controller.highlight,
        LocalChatSearchRow provides rowKey,
        content = content,
    )
}
