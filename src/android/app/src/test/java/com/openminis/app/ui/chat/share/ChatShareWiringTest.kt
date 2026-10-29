package com.openminis.app.ui.chat.share

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-multi-select-share] Pins the entry points and the render path (iOS
 * ChatShareModelTests "Wiring"): Share Chat… in the user-bubble menu after
 * Copy / Retry / Edit, last in both selection bars, in the "…" menu, all opening the
 * same picker; the export draws the chat's own rows in its own appearance.
 */
class ChatShareWiringTest {
    @Test fun `every entry opens the same picker`() {
        val bubble = ProductionSources.read("ui/chat/ChatUserMessageUI.kt")
        val edit = bubble.indexOf("R.string.chat_longpress_edit")
        val share = bubble.indexOf("R.string.chat_share_share_chat")
        val delete = bubble.indexOf("R.string.chat_longpress_delete_from_here")
        assertTrue("user menu: Copy / Retry / Edit, then Share Chat…, destructive items last", edit in 0 until share && share < delete)

        val bar = ProductionSources.read("ui/chat/MinisTextKitGesture.kt")
        val tableImage = bar.indexOf("add(SelectionAction(labelCopyTableImage)")
        val shareItem = bar.indexOf("add(SelectionAction(labelShareChat)")
        assertTrue("selection bar: Share Chat… is the last item", tableImage in 0 until shareItem &&
            bar.indexOf("add(SelectionAction(", shareItem + 1) < 0)
        val toolbar = ProductionSources.read("ui/chat/MinisMarkdownTextToolbar.kt")
        assertTrue(toolbar.indexOf("R.string.chat_share_share_chat") > toolbar.indexOf("R.string.markdown_table_copy_table_image"))

        val chat = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue("long-press, selection bars and the … menu open the same picker",
            chat.contains("openSharePicker(originalMessageId(item.message.id))") &&
                chat.split("onShareChat = { messageId -> openSharePicker(messageId) }").size - 1 == 2 &&
                chat.contains("openSharePicker(null)") &&
                chat.contains("ChatShareSelectionDialog("))
        val newChat = chat.indexOf("R.string.chat_menu_new_chat")
        assertTrue("… menu: Share Chat… right after New Chat", chat.indexOf("R.string.chat_share_share_chat", newChat) in newChat until chat.indexOf("R.string.chat_menu_clear_chat", newChat))
    }

    @Test fun `the export draws the chat's own rows`() {
        val content = ProductionSources.read("ui/chat/share/ChatShareContent.kt")
        assertTrue(content.contains("UserMessageBubble(message = message, precededByUser = precededByUser, onRetry = null)") &&
            content.contains("AgentCallbackCard(callback = callback, onTap = {})") &&
            content.contains("ToolCallPill(block, allToolBlocks = toolBlocks)"))
        val renderer = ProductionSources.read("ui/chat/share/ChatExportRenderer.kt")
        assertTrue("a dark app can export light: the export has its own appearance",
            renderer.contains("MinisAppearance(darkTheme = dark)"))
        assertTrue("software canvases cannot draw hardware bitmaps",
            renderer.contains("allowHardware(false)"))
        val picker = ProductionSources.read("ui/chat/share/ChatShareSelectionScreen.kt")
        assertTrue("picker rows are display-only: a touch layer covers the chat content",
            picker.contains(".matchParentSize()\n                .combinedClickable("))
    }

    @Test fun `pdf - searchable text, A4 pages, clean breaks, footer`() {
        val renderer = ProductionSources.read("ui/chat/share/ChatExportRenderer.kt")
        assertTrue("drawn into PdfDocument pages, so text stays text", renderer.contains("doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, openIndex + 1)"))
        assertTrue("A4 proportions at the export width", renderer.contains("(pageWidth * 297f / 210f).roundToInt()"))
        assertTrue("page footer \"title · n\"", renderer.contains("val label = \"\$title · \${openIndex + 1}\""))
        assertTrue("breaks: every cell bottom and every text line bottom",
            renderer.contains("SemanticsActions.GetTextLayoutResult") && renderer.contains("r.getLineBottom(i)") &&
                renderer.contains("sink.itemBottoms[index] = top + c.size.height"))
        assertTrue("pagination rule from the shared model",
            renderer.contains("ChatSharePagination.place(heightDp, breaks, contentHeight, gap, cursor)"))
    }

    @Test fun `preview - PDF only when too long, remembered format, floating switch`() {
        val preview = ProductionSources.read("ui/chat/share/ChatSharePreviewScreen.kt")
        assertTrue("estimated per width; an actual imageTooLarge also flips that width to PDF",
            preview.contains("ChatShareHeightEstimate.tooTallForImage(") &&
                preview.contains("imageFailedWidths[key.wideLayout] = true") &&
                preview.contains("val format = if (imageUnavailable) ChatExportOptions.Format.PDF else storedFormat"))
        assertTrue("the format is remembered", preview.contains("ChatSharePrefs.setFormat(context, it)"))
        val controls = ProductionSources.read("ui/chat/share/ChatSharePreviewControls.kt")
        assertTrue("swipe left -> PDF, right -> Long Image; a blocked Long Image only explains",
            controls.contains("if (total < -threshold) select(ChatExportOptions.Format.PDF)") &&
                controls.contains("if (total > threshold) select(ChatExportOptions.Format.IMAGE)") &&
                controls.contains("onBlockedImage()") && controls.contains("0.35f"))
    }

    @Test fun `options strip, header and adaptive tables`() {
        val preview = ProductionSources.read("ui/chat/share/ChatSharePreviewScreen.kt")
        assertTrue("width and tables are not remembered; appearance follows the app until picked",
            preview.contains("var wideLayout by mutableStateOf(false)") &&
                preview.contains("var adaptiveTables by mutableStateOf(false)") &&
                preview.contains("val dark = darkChoice ?: appDark"))
        assertTrue("each toggle says what it switched to",
            preview.split("switched(context.getString(").size - 1 == 3 &&
                preview.contains("R.string.chat_share_width_label") && preview.contains("R.string.chat_share_table_label") &&
                preview.contains("R.string.chat_share_appearance_label"))

        val header = ProductionSources.read("ui/chat/share/ChatExportHeader.kt")
        assertTrue("ringed round avatar, title, model · date, brand",
            header.contains(".clip(CircleShape)") && header.contains("text = \"Open Minis\"") &&
                header.contains("parts.joinToString(\" · \")") && header.contains("if (i < parts.lastIndex) \"\$part ·\" else part"))
        val both = chatExportSubtitle(" GPT-5 ", 1_790_000_000_000L, java.util.Locale.US)
        assertTrue("model first (trimmed), then a medium date with the time",
            both.size == 2 && both[0] == "GPT-5" && both[1].contains("2026"))
        assertEquals("either part dropped when unknown", listOf("GPT-5"), chatExportSubtitle("GPT-5", null))
        assertTrue(chatExportSubtitle(null, null).isEmpty())

        val renderer = ProductionSources.read("ui/chat/share/ChatExportRenderer.kt")
        assertTrue("tables measured at their natural width by the table renderer itself",
            renderer.contains("LocalTableNaturalWidthSink provides") &&
                ProductionSources.read("ui/chat/StreamingMarkdownText.kt").contains("naturalWidthSink?.invoke("))
        assertTrue("prose re-wraps at the page width; tables keep their own",
            renderer.contains("split.map { if (it.isTable) it else ChatShareTableChunk(it.text, proseWidth) }"))
        assertTrue("user bubbles and callback cards keep the reading width, a bubble at the right edge",
            renderer.contains("cell.kind == ChatShareCell.Kind.USER || cell.kind == ChatShareCell.Kind.CALLBACK") &&
                renderer.contains("contentAlignment = Alignment.TopEnd"))
    }

    @Test fun `cross-review fixes stay in place`() {
        val preview = ProductionSources.read("ui/chat/share/ChatSharePreviewScreen.kt")
        assertTrue("#1: one viewer per file, so switching back never shows the previous option's pages",
            preview.contains("androidx.compose.runtime.key(current.result.file)"))
        assertTrue("device: options and renders live in the picker-owned state, not the preview's composition",
            preview.contains("var darkChoice by state::darkChoice") && preview.contains("val rendered = state.rendered"))
        assertTrue("#7: a cancelled date lookup is not saved as a date-less subtitle",
            preview.contains("throw e // superseded"))
        val picker = ProductionSources.read("ui/chat/share/ChatShareSelectionScreen.kt")
        assertTrue("a fresh preview state per opening", picker.contains("previewState = ChatSharePreviewState()"))
        val renderer = ProductionSources.read("ui/chat/share/ChatExportRenderer.kt")
        assertTrue("#2: an open PDF page is finished before close()",
            renderer.contains("openPage?.let { page -> runCatching { doc.finishPage(page) } }"))
        assertTrue("#3: a view cancelled while settling is removed", renderer.contains("layer.removeView(view)\n            throw t"))
        assertTrue("#11: a failed export leaves no folder behind", renderer.split("file.parentFile?.deleteRecursively()").size - 1 >= 3)
    }

    @Test fun `file names are cut on character boundaries`() {
        // take(60) would keep only the first half of the surrogate pair.
        val title = "a".repeat(59) + "😀tail"
        assertEquals("a".repeat(59) + "😀", takeGraphemes(title, 60))
        assertEquals("short", takeGraphemes("short", 60))
    }

    @Test fun `rows are spaced like the chat list in both formats`() {
        val renderer = ProductionSources.read("ui/chat/share/ChatExportRenderer.kt")
        val group = renderer.substringAfter("internal fun ChatExportGroupView(").substringBefore("internal class ChatExportRenderer(")
        assertTrue("rows at the list's own gap, not a fixed 8dp",
            group.contains("Arrangement.spacedBy(ChatShareSpacing.ROW_GAP.dp)") && !group.contains("spacedBy(8.dp)"))
        assertTrue("a bubble under a user-role row gets the chat's precededByUser",
            group.contains("precededByUser = index == 0 && afterUserRow"))
        assertTrue("the hidden usage row between two messages of one reply",
            group.contains("above.messageId in usageRows") && group.contains("Spacer(Modifier.height(0.dp))"))
        assertTrue("each group's gap: header rule over the whole chat, usage row above",
            renderer.contains("val speakers = messages.map(ChatShareContent::speaker)") &&
                renderer.contains("ChatShareSpacing.headerAbove(speakers, it)") &&
                renderer.contains("ChatShareSpacing.gapBetween(usageAbove, header)"))
        assertTrue("long image: the next group's gap, not the fixed unit gap",
            renderer.contains("blank(if (index == 0) HEADER_GAP else parts[index + 1].gapBefore)"))
        assertTrue("PDF: the same gap feeds the pagination",
            renderer.contains("val gap = if (index <= 1) UNIT_GAP else part.gapBefore"))
        assertTrue("PDF: no page ends inside a card, capsule, chip or banner",
            renderer.contains("if (solid) sink.solidSpans[index] = top to top + c.size.height") &&
                renderer.contains("return ChatSharePagination.withoutCutsThrough(out, solid)"))
        assertTrue("prewarmed texts are the fragments that are drawn",
            renderer.contains("?.let { ChatShareContent.textFragments(it) }.orEmpty()"))
        val content = ProductionSources.read("ui/chat/share/ChatShareContent.kt")
        assertTrue("the bubble is told what the chat tells it",
            content.contains("UserMessageBubble(message = message, precededByUser = precededByUser, onRetry = null)"))
        assertTrue("text is listed in the chat's fragments at the row gap",
            content.contains("coalesceMarkdownFragments(splitMarkdownIntoBlockTexts(content))") &&
                content.contains("for (fragment in ChatShareContent.textFragments(block))"))
        assertTrue("the error row is the chat's banner", content.contains("InlineErrorBanner(error = message.error.orEmpty(), onRetry = null)"))
        assertTrue("usage row as flattenMessages emits it",
            content.contains("m.role == \"assistant\" && m.tokenUsage != null && !m.isStreaming"))
    }
}
