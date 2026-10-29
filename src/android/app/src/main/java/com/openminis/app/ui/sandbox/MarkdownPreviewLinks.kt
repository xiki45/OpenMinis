package com.openminis.app.ui.sandbox

import java.io.File
import java.net.URLDecoder

/**
 * [T-md-preview-links] Where a link tapped inside a markdown FILE preview
 * points. FilePreviewScreen is a NavHost destination outside ChatScreen, so
 * the chat's LocalMarkdownUrlClickHandler is null there and every link in a
 * previewed document (relative, minis:// and https alike) did nothing.
 */
sealed class MarkdownPreviewLinkTarget {
    /** Same-document anchor (`#section`) or nothing to open. */
    data object Ignore : MarkdownPreviewLinkTarget()

    /** A path relative to the shown document, resolved on the host. */
    data class Relative(val file: File) : MarkdownPreviewLinkTarget()

    /** Has a scheme or is a guest absolute path: route like a chat link. */
    data class Chat(val url: String) : MarkdownPreviewLinkTarget()
}

object MarkdownPreviewLinks {
    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun target(rawUrl: String, document: File): MarkdownPreviewLinkTarget {
        val url = rawUrl.trim()
        if (url.isEmpty() || url.startsWith("#")) return MarkdownPreviewLinkTarget.Ignore
        if (url.startsWith("/") || schemePrefix.containsMatchIn(url)) {
            return MarkdownPreviewLinkTarget.Chat(url)
        }
        val path = url.substringBefore('#').substringBefore('?')
        if (path.isEmpty()) return MarkdownPreviewLinkTarget.Ignore
        // `+` is a literal in a path; URLDecoder would turn it into a space.
        val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(path)
        val folder = document.absoluteFile.parentFile ?: return MarkdownPreviewLinkTarget.Ignore
        return MarkdownPreviewLinkTarget.Relative(File(folder, decoded).normalize())
    }
}
