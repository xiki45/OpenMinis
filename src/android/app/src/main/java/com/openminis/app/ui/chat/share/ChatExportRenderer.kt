package com.openminis.app.ui.chat.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.compose.LocalImageLoader
import coil.imageLoader
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.LocalMinisSelectionController
import com.openminis.app.ui.theme.DarkChatPalette
import com.openminis.app.ui.theme.LightChatPalette
import com.openminis.app.ui.theme.MinisAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val LOG = "ChatExport"

// [T-multi-select-share] Renders the selected cells of a chat into one long PNG or a
// paged PDF. Port of iOS ChatExportRenderer.swift.
//
// Each group of selected cells (one question, or the selected pieces of one reply) is
// composed offscreen in its own ComposeView using the chat's OWN row composables
// (ChatShareCellContent), so the export looks like the session; it is drawn and
// thrown away before the next group. That lets any selection be shared, keeps peak
// memory to one group, and — because the views are drawn into a software canvas —
// the PDF keeps text as real, searchable text rather than a picture of it.

internal data class ChatExportOptions(
    val format: Format = Format.IMAGE,
    /** Lay the chat out at tablet width (1024dp) instead of the phone's. */
    val wideLayout: Boolean = false,
    /** Dark appearance; the preview starts from the app's current one. */
    val dark: Boolean = false,
    /** Adaptive table width: each table at its natural width, the page widening to the widest (capped). */
    val adaptiveTables: Boolean = false,
) {
    enum class Format { IMAGE, PDF }
}

internal sealed class ChatExportError(@StringRes val messageRes: Int) : Exception() {
    object NothingToExport : ChatExportError(R.string.chat_share_error_nothing)
    object ImageTooLarge : ChatExportError(R.string.chat_share_error_image_too_large)
    object WriteFailed : ChatExportError(R.string.chat_share_error_write_failed)
}

/**
 * Where groups are composed: an invisible layer added to the window of the screen
 * that owns [parentContext], so the views lay out exactly as on screen (same density,
 * fonts, theme, composition locals) but are never drawn by the window — only into
 * the canvases the renderer hands them. Children get their width exactly and an
 * unbounded height. [ownerSource] is a view of that screen: a ComposeView needs the
 * lifecycle / saved-state owners on its view tree, and a Compose Dialog sets them on
 * its content view, not on the window's decor view the layer is added to.
 */
internal class ChatExportStage(
    private val host: ViewGroup,
    ownerSource: View,
    private val parentContext: CompositionContext,
) {
    val context: Context get() = host.context
    val density: Float get() = host.resources.displayMetrics.density

    private val layer = object : ViewGroup(host.context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.measure(
                    MeasureSpec.makeMeasureSpec(max(1, child.layoutParams.width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
            }
            setMeasuredDimension(1, 1)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.layout(0, 0, child.measuredWidth, child.measuredHeight)
            }
        }

        // Never part of the window's own picture, never a touch target.
        override fun dispatchDraw(canvas: Canvas) = Unit
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
    }

    init {
        layer.setViewTreeLifecycleOwner(ownerSource.findViewTreeLifecycleOwner())
        layer.setViewTreeViewModelStoreOwner(ownerSource.findViewTreeViewModelStoreOwner())
        layer.setViewTreeSavedStateRegistryOwner(ownerSource.findViewTreeSavedStateRegistryOwner())
        host.addView(layer, ViewGroup.LayoutParams(1, 1))
    }

    /**
     * [T-share-preview-close-crash] Called from the preview's onDispose, which runs
     * INSIDE the dialog window's teardown: dismiss() detaches the decor view, whose
     * children loop has already cached its count, and detaching the Compose content
     * disposes the preview right there. Taking [layer] out of [host] at that moment
     * leaves a null slot the loop then calls into (NPE in
     * ViewGroup.dispatchDetachedFromWindow, Pixel 6, every close). The layer's own
     * children are not being walked yet, so they go now; the empty, never-drawn
     * layer leaves the host on the next main-loop turn (a no-op if the window is gone).
     */
    fun dispose() {
        layer.removeAllViews()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            (layer.parent as? ViewGroup)?.removeView(layer)
        }
    }

    /** A composed and laid-out group, alive until [discard]. */
    class Laid(val view: ComposeView) {
        val widthPx: Int get() = view.width
        val heightPx: Int get() = view.height
    }

    suspend fun layOut(widthPx: Int, minSettleMs: Long, content: @Composable () -> Unit): Laid {
        val view = ComposeView(host.context).apply {
            setParentCompositionContext(parentContext)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
        layer.addView(view, ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        try {
            settle(view, minSettleMs)
        } catch (t: Throwable) {
            // Cancelled while settling: the caller never gets a Laid to discard.
            layer.removeView(view)
            throw t
        }
        return Laid(view)
    }

    fun discard(laid: Laid) {
        layer.removeView(laid.view)
    }

    /**
     * Waits until the view has laid out and its height has held for a few checks.
     * Content that arrives on a later hop (math images, pictures) gets [minMs].
     */
    private suspend fun settle(view: View, minMs: Long) {
        var last = -1
        var stable = 0
        var waited = 0L
        while (waited < MAX_SETTLE_MS) {
            delay(SETTLE_STEP_MS)
            waited += SETTLE_STEP_MS
            if (!view.isLaidOut || view.isLayoutRequested) { stable = 0; continue }
            val h = view.height
            if (h > 0 && h == last) stable++ else { stable = 0; last = h }
            if (stable >= 2 && waited >= minMs) return
        }
        AppLogger.warning(LOG, "[ChatExport] layout did not settle in ${MAX_SETTLE_MS}ms (h=${view.height})")
    }

    private companion object {
        const val SETTLE_STEP_MS = 50L
        const val MAX_SETTLE_MS = 4_000L
    }
}

/**
 * Collects the bottom edge of every cell (PDF break points) while a group lays out, and
 * the span of every cell a page must not cut (ChatSharePagination.withoutCutsThrough).
 */
internal class ChatExportBreakSink {
    val itemBottoms = HashMap<Int, Float>()
    val solidSpans = HashMap<Int, Pair<Float, Float>>()
}

/**
 * The export's appearance: the chosen light / dark theme, software bitmaps for
 * images (a hardware bitmap cannot be drawn into the software canvases the export
 * uses), and no chat selection controller (nothing here is selectable).
 */
@Composable
internal fun ChatExportSurface(dark: Boolean, content: @Composable () -> Unit) {
    MinisAppearance(darkTheme = dark) {
        val context = LocalContext.current
        val loader = remember(context) { context.imageLoader.newBuilder().allowHardware(false).build() }
        @Suppress("DEPRECATION")
        CompositionLocalProvider(
            LocalImageLoader provides loader,
            LocalMinisSelectionController provides null,
            content = content,
        )
    }
}

/**
 * One group of selected cells, as the export lays it out: the chat's rows at the
 * chosen reading width. With adaptive tables the page can be wider than a row: the
 * reply (prose, thinking, tools) re-wraps at the page width so its text lines up with
 * the tables, while user bubbles and callback cards keep the reading width (a user
 * bubble stays at the page's right edge).
 */
@Composable
internal fun ChatExportGroupView(
    cells: List<ChatShareCell>,
    messages: Map<String, ChatMessage>,
    chunks: Map<String, List<ChatShareTableChunk>>,
    rowWidthDp: Float,
    canvasWidthDp: Float,
    pageColor: Color,
    sink: ChatExportBreakSink? = null,
    /** The group right above is a user-role row (a bubble or a card): the chat's precededByUser. */
    afterUserRow: Boolean = false,
    /** Messages the chat closes with its hidden usage row (ChatShareContent.hasUsageRow). */
    usageRows: Set<String> = emptySet(),
) {
    // Rows sit at the chat list's own gap; their paddings come with the composables
    // (ChatShareSpacing).
    Column(
        verticalArrangement = Arrangement.spacedBy(ChatShareSpacing.ROW_GAP.dp),
        modifier = Modifier.width(canvasWidthDp.dp).background(pageColor),
    ) {
        cells.forEachIndexed { index, cell ->
            val message = messages[cell.messageId] ?: return@forEachIndexed
            // A reply spanning several messages: the chat's zero-height usage row
            // between them is one more row gap.
            val above = cells.getOrNull(index - 1)
            if (above != null && above.messageId != cell.messageId && above.messageId in usageRows) {
                Spacer(Modifier.height(0.dp))
            }
            // Cards, tool capsules, the thinking chip and the error banner are one
            // piece each: a page never ends between their text lines. Prose and user
            // bubbles can be longer than a page and break at any line.
            val solid = cell.kind != ChatShareCell.Kind.TEXT &&
                ChatShareContent.spacingEdge(cell, message) != ChatShareSpacing.Edge.BUBBLE
            val edge = if (sink == null) Modifier else Modifier.onGloballyPositioned { c ->
                val top = c.positionInRoot().y
                sink.itemBottoms[index] = top + c.size.height
                if (solid) sink.solidSpans[index] = top to top + c.size.height
            }
            // Bubbles and cards are objects with a width of their own; stretching
            // them across a wide page reads as a layout bug rather than more room.
            val keepsReadingWidth = cell.kind == ChatShareCell.Kind.USER || cell.kind == ChatShareCell.Kind.CALLBACK
            val width = if (keepsReadingWidth) rowWidthDp else canvasWidthDp
            if (cell.kind == ChatShareCell.Kind.USER) {
                Box(modifier = edge.width(canvasWidthDp.dp), contentAlignment = Alignment.TopEnd) {
                    ChatShareCellContent(
                        cell, message, Modifier.width(width.dp),
                        precededByUser = index == 0 && afterUserRow,
                    )
                }
            } else {
                ChatShareCellContent(cell, message, edge.width(width.dp), tableChunks = chunks[cell.id])
            }
        }
    }
}

internal class ChatExportRenderer(
    private val stage: ChatExportStage,
    /** Parses the markdown of a group before it is composed, so it lays out at once. */
    private val prewarmMarkdown: suspend (List<String>) -> Unit,
) {
    class Result(
        val file: File,
        /** Long image: pixel size. PDF: page size in points. */
        val width: Int,
        val height: Int,
        /** Pixels per dp of a long image (1 for a PDF): the preview shows it at 1:1. */
        val scale: Float,
        val pageCount: Int,
        val units: Int,
    )

    /** One part of the export (the header or a group): its composable and what it needs first. */
    private class Part(
        val markdown: List<String>,
        val minSettleMs: Long,
        val sink: ChatExportBreakSink?,
        /** Space above this group when it follows another group (ChatShareSpacing); unused for the first. */
        val gapBefore: Float = 0f,
        val content: @Composable () -> Unit,
    )

    /**
     * Renders the selected cells. [groups] is ChatShareCells.renderGroups: each group
     * is the selected part of one unit and is laid out (and page-broken) as one block.
     * [subtitle] is the header's "<model> · <date>" parts.
     */
    suspend fun render(
        groups: List<List<ChatShareCell>>,
        messages: List<ChatMessage>,
        title: String,
        subtitle: List<String>,
        options: ChatExportOptions,
        progress: (Int, Int) -> Unit,
    ): Result {
        val started = System.currentTimeMillis()
        val byId = LinkedHashMap<String, ChatMessage>().also { map -> messages.forEach { map.putIfAbsent(it.id, it) } }
        val pageColor = if (options.dark) DarkChatPalette.background else LightChatPalette.background
        val base = rowWidthDp(options.wideLayout, stage.context)
        val kept = groups.map { g -> g.filter { byId[it.messageId] != null } }.filter { it.isNotEmpty() }
        if (kept.isEmpty()) throw ChatExportError.NothingToExport
        var chunks = if (options.adaptiveTables) tableChunks(kept, byId, base, options.dark) else emptyMap()
        // The page follows the widest table, up to ChatShareGeometry.maxCanvasWidth;
        // a table capped below its natural width wraps its cells to fit.
        val canvas = ChatShareGeometry.canvasWidth(
            base, chunks.values.flatten().map { it.widthDp + ChatShareGeometry.ROW_PADDING },
        )
        // Prose was split at the reading width only to find the table widths; once
        // the page is known it re-wraps at the page's content width, so the text
        // around a table is as wide as the widest table rather than a narrow column
        // beside it. Tables keep their own width (a narrow one is not stretched).
        val proseWidth = canvas - ChatShareGeometry.ROW_PADDING
        chunks = chunks.mapValues { (_, split) ->
            split.map { if (it.isTable) it else ChatShareTableChunk(it.text, proseWidth) }
        }
        val header = Part(emptyList(), BASE_SETTLE_MS, null) {
            ChatExportSurface(options.dark) { ChatExportHeaderView(title, subtitle, canvas, pageColor) }
        }
        val edges = kept.map { group -> ChatShareContent.spacingEdge(group[0], byId.getValue(group[0].messageId)) }
        // The chat's header rule runs over the whole chat, not over the selection.
        val speakers = messages.map(ChatShareContent::speaker)
        val chatIndex = HashMap<String, Int>().also { map -> messages.forEachIndexed { i, m -> map.putIfAbsent(m.id, i) } }
        val usageRows = byId.values.filter(ChatShareContent::hasUsageRow).mapTo(HashSet()) { it.id }
        val parts = listOf(header) + kept.mapIndexed { groupIndex, group ->
            val texts = group.filter { it.kind == ChatShareCell.Kind.TEXT }.flatMap { cell ->
                chunks[cell.id]?.map { it.text }
                    ?: byId[cell.messageId]?.let { ChatShareContent.block(cell, it) }
                        ?.let { ChatShareContent.textFragments(it) }.orEmpty()
            }
            val waitsForMedia = group.any { cell ->
                val m = byId[cell.messageId]
                cell.kind == ChatShareCell.Kind.USER && m != null && m.imageUris.isNotEmpty()
            } || texts.any { it.contains('$') || it.contains("\\(") || it.contains("\\[") || it.contains("![") }
            val sink = ChatExportBreakSink()
            val previous = edges.getOrNull(groupIndex - 1)
            val header = edges[groupIndex] == ChatShareSpacing.Edge.REPLY &&
                chatIndex[group[0].unitId]?.let { ChatShareSpacing.headerAbove(speakers, it) } ?: true
            val usageAbove = kept.getOrNull(groupIndex - 1)?.last()?.messageId in usageRows
            val gap = if (previous == null) 0f else ChatShareSpacing.gapBetween(usageAbove, header)
            Part(texts, if (waitsForMedia) MEDIA_SETTLE_MS else BASE_SETTLE_MS, sink, gap) {
                ChatExportSurface(options.dark) {
                    ChatExportGroupView(
                        group, byId, chunks, base, canvas, pageColor, sink,
                        afterUserRow = ChatShareSpacing.followsUserRow(previous),
                        usageRows = usageRows,
                    )
                }
            }
        }
        AppLogger.info(
            LOG,
            "[ChatExport] start format=${options.format} groups=${kept.size} cells=${kept.sumOf { it.size }} " +
                "canvas=${canvas.roundToInt()} wide=${options.wideLayout} dark=${options.dark} " +
                "adaptiveTables=${options.adaptiveTables} splitBlocks=${chunks.size}",
        )
        val result = when (options.format) {
            ChatExportOptions.Format.IMAGE -> renderImage(parts, canvas, title, pageColor, progress)
            ChatExportOptions.Format.PDF -> renderPdf(parts, canvas, title, pageColor, footerColor(options.dark), progress)
        }
        AppLogger.info(
            LOG,
            "[ChatExport] done format=${options.format} size=${result.width}x${result.height} pages=${result.pageCount} " +
                "bytes=${result.file.length()} ms=${System.currentTimeMillis() - started}",
        )
        return result
    }

    private suspend fun layOut(part: Part, widthPx: Int): ChatExportStage.Laid {
        if (part.markdown.isNotEmpty()) withContext(Dispatchers.Default) { prewarmMarkdown(part.markdown) }
        return stage.layOut(widthPx, part.minSettleMs, part.content)
    }

    // ── Long image ───────────────────────────────────────────────────────────

    /**
     * Each part is drawn strip by strip at [ChatShareImageBudget.PREFERRED_SCALE]
     * pixels per dp straight into a streamed PNG, so only one composed part and one
     * strip are alive at a time. Text is drawn by the views themselves into the
     * scaled canvas (glyphs, not a copy of the screen's bitmaps), so it is sharp at
     * 3x. When the finished height turns out past the budget, the image is drawn
     * again at the lower scale the budget allows, or refused below 1x.
     */
    private suspend fun renderImage(
        parts: List<Part>,
        canvasDp: Float,
        title: String,
        pageColor: Color,
        progress: (Int, Int) -> Unit,
    ): Result {
        val first = drawImage(parts, canvasDp, title, pageColor, ChatShareImageBudget.PREFERRED_SCALE, progress)
        if (first.second != null) return first.second!!
        val heightDp = first.first
        val scale = ChatShareImageBudget.scale(canvasDp + MARGIN * 2, heightDp)
        if (scale == null) {
            AppLogger.warning(LOG, "[ChatExport] image too large ${(canvasDp + MARGIN * 2).roundToInt()}x${heightDp.roundToInt()}dp")
            throw ChatExportError.ImageTooLarge
        }
        AppLogger.info(LOG, "[ChatExport] image past the budget at 3x, drawing again at ${scale}x")
        return drawImage(parts, canvasDp, title, pageColor, scale, progress).second
            ?: throw ChatExportError.ImageTooLarge
    }

    /**
     * Draws the long image at [scale]. Returns (height in dp, result); the result is
     * null when the image went past the pixel budget, in which case the remaining
     * parts are only measured so the caller can pick a scale that fits.
     */
    private suspend fun drawImage(
        parts: List<Part>,
        canvasDp: Float,
        title: String,
        pageColor: Color,
        scale: Float,
        progress: (Int, Int) -> Unit,
    ): Pair<Float, Result?> = coroutineScope {
        val density = stage.density
        val viewWidthPx = (canvasDp * density).roundToInt()
        val outWidth = ceil((canvasDp + MARGIN * 2) * scale).toInt()
        val k = scale / density
        val page = pageColor.toArgb()
        val file = exportFile(stage.context, title, "png")
        var writer: ChatSharePngWriter? = null
        var strip: Bitmap? = null
        val encoder = Dispatchers.Default.limitedParallelismCompat()
        var encoding: Job? = null
        var rows = 0
        var heightDp = 0f
        var overBudget = false
        val maxRows = min(
            ChatShareImageBudget.MAX_PIXEL_HEIGHT,
            ChatShareImageBudget.MAX_PIXELS / outWidth,
        ).toInt()

        suspend fun emit(pixels: IntArray, count: Int) {
            val previous = encoding
            previous?.join()
            val w = writer!!
            encoding = launch(encoder) { w.writeRows(pixels, 0, count) }
            rows += count
        }

        suspend fun blank(dp: Float) {
            heightDp += dp
            if (overBudget) return
            var remaining = (dp * scale).roundToInt()
            while (remaining > 0) {
                val n = min(remaining, STRIP_PX)
                emit(IntArray(outWidth * n) { page }, n)
                remaining -= n
            }
        }

        // The closing margin is part of the height the budget is checked against.
        val trailingRows = ceil(MARGIN * scale).toInt()
        try {
            writer = ChatSharePngWriter(outWidth, File(file.parentFile, "image.idat"))
            val bitmap = Bitmap.createBitmap(outWidth, STRIP_PX, Bitmap.Config.ARGB_8888).also { strip = it }
            val drawCanvas = Canvas(bitmap)
            blank(MARGIN)
            parts.forEachIndexed { index, part ->
                coroutineContext.ensureActive()
                val laid = layOut(part, viewWidthPx)
                try {
                    val partDp = laid.heightPx / density
                    heightDp += partDp
                    if (!overBudget && rows + ceil(partDp * scale).toInt() + trailingRows > maxRows) overBudget = true
                    if (!overBudget) {
                        val outHeight = (partDp * scale).roundToInt()
                        var y = 0
                        while (y < outHeight) {
                            val n = min(STRIP_PX, outHeight - y)
                            bitmap.eraseColor(page)
                            drawCanvas.save()
                            drawCanvas.translate(MARGIN * scale, -y.toFloat())
                            drawCanvas.scale(k, k)
                            try {
                                laid.view.draw(drawCanvas)
                            } catch (e: IllegalArgumentException) {
                                AppLogger.error(LOG, "[ChatExport] draw failed: ${e.message}")
                                throw ChatExportError.WriteFailed
                            } finally {
                                drawCanvas.restore()
                            }
                            val pixels = IntArray(outWidth * n)
                            bitmap.getPixels(pixels, 0, outWidth, 0, 0, outWidth, n)
                            emit(pixels, n)
                            y += n
                            yield()
                        }
                    }
                } finally {
                    stage.discard(laid)
                }
                if (index > 0) progress(index, parts.size - 1)
                // The header is followed by a short gap, each group by the chat's gap
                // to the next one (ChatShareSpacing).
                if (index < parts.lastIndex) blank(if (index == 0) HEADER_GAP else parts[index + 1].gapBefore)
            }
            blank(MARGIN)
            encoding?.join()
            val w = writer!!
            if (overBudget) {
                w.close()
                file.parentFile?.deleteRecursively()
                return@coroutineScope heightDp to null
            }
            withContext(Dispatchers.IO) { w.finish(file) }
            heightDp to Result(file, outWidth, w.rows, scale, 1, parts.size - 1)
        } catch (t: Throwable) {
            // The encoder may be inside writeRows: let it finish before the writer
            // is closed under it (a superseded render arrives here cancelled).
            withContext(NonCancellable) { encoding?.cancelAndJoin() }
            writer?.close()
            file.parentFile?.deleteRecursively()
            throw if (t is java.io.IOException) ChatExportError.WriteFailed else t
        } finally {
            strip?.recycle()
        }
    }

    // ── Adaptive tables ──────────────────────────────────────────────────────

    /**
     * The chunks of every selected text block that holds a table: prose at the
     * reading width (re-wrapped at the page width by the caller), each table at its
     * natural width (ChatShareGeometry.tableWidth). The natural widths are measured
     * by laying every table out once, offscreen, with the table renderer reporting
     * the width its NATURAL plan needs.
     */
    private suspend fun tableChunks(
        groups: List<List<ChatShareCell>>,
        byId: Map<String, ChatMessage>,
        base: Float,
        dark: Boolean,
    ): Map<String, List<ChatShareTableChunk>> {
        val contentWidth = base - ChatShareGeometry.ROW_PADDING
        val splits = LinkedHashMap<String, List<ChatShareMarkdownChunker.Chunk>>()
        for (cell in groups.flatten()) {
            if (cell.kind != ChatShareCell.Kind.TEXT) continue
            val text = byId[cell.messageId]?.let { ChatShareContent.block(cell, it)?.content } ?: continue
            val split = ChatShareMarkdownChunker.split(text)
            if (split.any { it.isTable }) splits[cell.id] = split
        }
        if (splits.isEmpty()) return emptyMap()
        val tables = splits.values.flatten().filter { it.isTable }.map { it.text }
        val naturals = FloatArray(tables.size)
        val probe = Part(tables, BASE_SETTLE_MS, null) {
            ChatExportSurface(dark) {
                Column(modifier = Modifier.width(contentWidth.dp)) {
                    tables.forEachIndexed { i, text ->
                        CompositionLocalProvider(
                            com.openminis.app.ui.chat.LocalTableNaturalWidthSink provides { w -> naturals[i] = w },
                        ) {
                            com.openminis.app.ui.chat.MarkdownBlock(rawText = text, isStreaming = false)
                        }
                    }
                }
            }
        }
        val laid = layOut(probe, (contentWidth * stage.density).roundToInt())
        stage.discard(laid)
        var t = 0
        return splits.mapValues { (_, split) ->
            split.map { chunk ->
                if (!chunk.isTable) return@map ChatShareTableChunk(chunk.text, contentWidth)
                val natural = naturals[t++]
                val width = if (natural > 0f) ChatShareGeometry.tableWidth(natural, contentWidth, base) else contentWidth
                ChatShareTableChunk(chunk.text, width, isTable = true)
            }
        }
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    /**
     * A4 proportions at the export width (1dp = 1pt; an adaptive-table page grows
     * with its width). Each part is placed by ChatSharePagination: it moves to the
     * next page whole only when little of the page is left, otherwise it is cut at
     * the lowest text-line or cell edge in the lower part of the page. Every page
     * ends with a "title · n" footer. The parts are drawn straight into the page's
     * canvas, so text stays real text: selectable and searchable in any PDF viewer.
     */
    private suspend fun renderPdf(
        parts: List<Part>,
        canvasDp: Float,
        title: String,
        pageColor: Color,
        footerColor: Color,
        progress: (Int, Int) -> Unit,
    ): Result {
        val density = stage.density
        val viewWidthPx = (canvasDp * density).roundToInt()
        val m = MARGIN
        val pageWidth = ceil(canvasDp + m * 2).toInt()
        val pageHeight = (pageWidth * 297f / 210f).roundToInt()
        val contentHeight = pageHeight - m * 2 - PDF_FOOTER
        val file = exportFile(stage.context, title, "pdf")
        val doc = PdfDocument()
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = footerColor.toArgb()
            textSize = 9f
        }
        var openPage: PdfDocument.Page? = null
        var openIndex = -1

        fun closePage() {
            val page = openPage ?: return
            val label = "$title · ${openIndex + 1}"
            val top = pageHeight - m - 12f
            page.canvas.drawText(label, m, top - footerPaint.fontMetrics.ascent, footerPaint)
            doc.finishPage(page)
            openPage = null
        }

        fun ensurePage(index: Int): Canvas {
            while (openIndex < index) {
                closePage()
                openIndex++
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, openIndex + 1).create())
                page.canvas.drawColor(pageColor.toArgb())
                openPage = page
            }
            return openPage!!.canvas
        }

        try {
            val cursor = ChatSharePagination.Cursor()
            parts.forEachIndexed { index, part ->
                kotlin.coroutines.coroutineContext.ensureActive()
                val laid = layOut(part, viewWidthPx)
                try {
                    val heightDp = laid.heightPx / density
                    val breaks = collectBreaks(laid, part.sink, density)
                    // The first group keeps the header's page gap; the rest are spaced as in the chat.
                    val gap = if (index <= 1) UNIT_GAP else part.gapBefore
                    val slices = ChatSharePagination.place(heightDp, breaks, contentHeight, gap, cursor)
                    for (slice in slices) {
                        val canvas = ensurePage(slice.page)
                        canvas.save()
                        canvas.translate(m, m + slice.pageY - slice.unitY)
                        canvas.clipRect(0f, slice.unitY, canvasDp, slice.unitY + slice.height)
                        canvas.scale(1f / density, 1f / density)
                        try {
                            laid.view.draw(canvas)
                        } catch (e: IllegalArgumentException) {
                            AppLogger.error(LOG, "[ChatExport] draw failed: ${e.message}")
                            throw ChatExportError.WriteFailed
                        } finally {
                            canvas.restore()
                        }
                    }
                } finally {
                    stage.discard(laid)
                }
                if (index > 0) progress(index, parts.size - 1)
                yield()
            }
            closePage()
            withContext(Dispatchers.IO) { file.outputStream().use { doc.writeTo(it) } }
            return Result(file, pageWidth, pageHeight, 1f, openIndex + 1, parts.size - 1)
        } catch (t: Throwable) {
            file.parentFile?.deleteRecursively()
            throw if (t is java.io.IOException) ChatExportError.WriteFailed else t
        } finally {
            // A render cancelled mid-part still has a page open, and close() throws
            // on an unfinished page - which would replace the cancellation with a
            // false "Couldn't create the file." and skip freeing the document.
            openPage?.let { page -> runCatching { doc.finishPage(page) } }
            runCatching { doc.close() }
        }
    }

    /**
     * Clean places to cut a page inside a part, in dp: every cell's bottom and every
     * text line's bottom (read from the semantics tree, which exposes each text's
     * layout — the markdown, table cells, code, bubbles), so a page never ends
     * through the middle of a line.
     */
    private fun collectBreaks(laid: ChatExportStage.Laid, sink: ChatExportBreakSink?, density: Float): List<Float> {
        val out = ArrayList<Float>()
        sink?.itemBottoms?.values?.forEach { out += it / density }
        val root = (laid.view.getChildAt(0) as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode
            ?: return out
        fun walk(node: SemanticsNode) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (action != null) {
                val results = ArrayList<TextLayoutResult>()
                if (runCatching { action(results) }.getOrDefault(false)) {
                    val top = node.positionInRoot.y
                    results.firstOrNull()?.let { r ->
                        for (i in 0 until r.lineCount) out += (top + r.getLineBottom(i)) / density
                    }
                }
            }
            node.children.forEach(::walk)
        }
        runCatching { walk(root) }.onFailure { AppLogger.warning(LOG, "[ChatExport] break scan failed: ${it.message}") }
        val solid = sink?.solidSpans?.values?.map { (top, bottom) -> top / density to bottom / density }.orEmpty()
        return ChatSharePagination.withoutCutsThrough(out, solid)
    }

    companion object {
        const val MARGIN = 8f
        const val UNIT_GAP = 18f
        private const val HEADER_GAP = 8f
        private const val STRIP_PX = 1024
        private const val PDF_FOOTER = 22f
        private const val BASE_SETTLE_MS = 150L
        private const val MEDIA_SETTLE_MS = 700L

        /**
         * The page's content width: the chat list's width on a phone (rows carry
         * their own 16dp side padding), or a tablet's portrait width (1024dp) for the
         * wide layout, where a 7-column table still plans its columns as it would in
         * a tablet chat.
         */
        /** iOS secondaryLabel for the page footer, resolved for the export's appearance. */
        fun footerColor(dark: Boolean): Color =
            if (dark) Color(0x99EBEBF5) else Color(0x993C3C43)

        fun rowWidthDp(wide: Boolean, context: Context): Float =
            if (wide) 1024f else context.resources.configuration.screenWidthDp.toFloat().coerceIn(360f, 460f)

        private fun exportRoot(context: Context) = File(context.cacheDir, "share/ChatExport")

        /**
         * Drops earlier exports; the preview calls it when it opens (its own renders
         * stay until the next time, so switching back reuses them).
         */
        fun clearExports(context: Context) {
            exportRoot(context).deleteRecursively()
        }

        /**
         * Its own folder per export, so the image and the PDF of one selection can
         * both be kept while the preview switches between them.
         */
        fun exportFile(context: Context, title: String, ext: String): File {
            val dir = File(exportRoot(context), UUID.randomUUID().toString())
            if (!dir.mkdirs() && !dir.isDirectory) throw ChatExportError.WriteFailed
            val clean = takeGraphemes(
                title.map { if (it in "/\\:?%*|\"<>\n\r") ' ' else it }.joinToString("").trim(), 60,
            )
            val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
            return File(dir, "${clean.ifEmpty { "Chat" }} $stamp.$ext")
        }
    }
}

/** The first [max] user-perceived characters of [text], never splitting an emoji or a combining sequence. */
internal fun takeGraphemes(text: String, max: Int): String {
    val chars = java.text.BreakIterator.getCharacterInstance()
    chars.setText(text)
    var end = 0
    repeat(max) {
        val next = chars.next()
        if (next == java.text.BreakIterator.DONE) return text
        end = next
    }
    return text.substring(0, end)
}

/** One encoding job at a time, in submission order. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun kotlinx.coroutines.CoroutineDispatcher.limitedParallelismCompat() = limitedParallelism(1)
