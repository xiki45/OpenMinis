package com.openminis.app.ui.chat.search

import androidx.compose.ui.text.AnnotatedString

/*
 * [T-in-chat-search] The searchable form of a text a markdown row draws.
 *
 * The chat renders an assistant reply with StreamingMarkdownText: each block
 * (paragraph, heading, list item, ...) is its own Text over an AnnotatedString
 * built by the inline parser - markdown marks already gone, link text without
 * its URL, inline code without backticks. Search uses those same
 * AnnotatedStrings (chatSearchMarkdownPieces in StreamingMarkdownText.kt), so
 * what is searched is exactly what is shown (iOS ChatSearchMarkdownText
 * reaches the same goal by mirroring its renderer).
 */
object ChatSearchMarkdown {
    /** Compose's tag for `appendInlineContent` (an inline formula's placeholder). */
    private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

    /**
     * [text] with every inline-content range (an inline formula drawn as an
     * image; its LaTeX is only the placeholder's alternate text) replaced by
     * U+FFFC, which the matcher skips. Same length as the input, so offsets
     * found in the result are offsets into the drawn string: the highlighter
     * calls this on the AnnotatedString it draws and paints those ranges.
     */
    fun searchable(text: AnnotatedString): String {
        val spans = text.getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length)
        if (spans.isEmpty()) return text.text
        val chars = text.text.toCharArray()
        for (s in spans) for (i in s.start until minOf(s.end, chars.size)) chars[i] = '￼'
        return String(chars)
    }
}
