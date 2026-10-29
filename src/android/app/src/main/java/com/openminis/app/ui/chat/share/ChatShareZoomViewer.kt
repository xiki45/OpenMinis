package com.openminis.app.ui.chat.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** One page of a [ChatShareZoomViewer], in dp at the 1:1 zoom. */
internal data class ChatShareViewerPage(val widthDp: Float, val heightDp: Float)

/**
 * [T-multi-select-share] The preview's viewer for a long image (as strips) or a PDF
 * (as pages): shown at 1:1 — one dp of the export per dp of screen, the size it was
 * drawn at — scrollable both ways, pinch out to fit the width, in to 4x; double-tap
 * zooms 2x around the tap and back to 1:1 (iOS ChatExportImagePreview /
 * ChatExportPDFPreview). Pages are drawn lazily from [render] at a resolution that
 * follows the zoom, so a 50,000-pixel image never has to be decoded whole.
 *
 * Content starts below [topClearanceDp] (the floating format switch); with
 * [centerShortContent] content shorter than the screen is centred vertically, the
 * top never above that clearance.
 */
@Composable
internal fun ChatShareZoomViewer(
    pages: List<ChatShareViewerPage>,
    gapDp: Float,
    topClearanceDp: Float,
    centerShortContent: Boolean,
    render: suspend (index: Int, widthPx: Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current.density
        val vpW = maxWidth.value
        val vpH = maxHeight.value
        val contentW = pages.maxOfOrNull { it.widthDp } ?: 1f
        val minZoom = min(1f, vpW / contentW)
        val totalH = pages.sumOf { it.heightDp.toDouble() }.toFloat() + gapDp * max(0, pages.size - 1)
        var zoom by remember(pages) { mutableFloatStateOf(1f) }
        var offsetX by remember(pages) { mutableFloatStateOf(0f) }
        val listState = remember(pages) { androidx.compose.foundation.lazy.LazyListState() }
        val scope = rememberCoroutineScope()

        fun rowWidth(z: Float) = max(vpW, contentW * z)
        fun maxOffsetX(z: Float) = max(0f, (contentW * z - vpW) * density)
        fun topPad(z: Float): Float {
            val h = totalH * z
            return if (centerShortContent && h < vpH) max(topClearanceDp, (vpH - h) / 2f) else topClearanceDp
        }
        fun bottomPad(z: Float): Float {
            val h = totalH * z
            return if (centerShortContent && h < vpH) max(0f, vpH - h - topPad(z)) else 8f
        }
        /** Unscaled top of each page (dp), gaps included. */
        val tops = remember(pages, gapDp) {
            var acc = 0f
            pages.map { p -> acc.also { acc += p.heightDp + gapDp } }
        }

        /** Zooms to [target] keeping the content under [focus] (viewer px) where it is. */
        fun zoomAround(target: Float, focus: Offset) {
            val z0 = zoom
            val z1 = target.coerceIn(minZoom, MAX_ZOOM)
            if (z1 == z0) return
            val fx = focus.x / density
            val fy = focus.y / density
            // Horizontal: content x at the focus, then the offset that keeps it there.
            val left0 = (rowWidth(z0) - contentW * z0) / 2f
            val left1 = (rowWidth(z1) - contentW * z1) / 2f
            val cx = (offsetX / density + fx - left0) / z0
            offsetX = ((cx * z1 + left1 - fx) * density).coerceIn(0f, maxOffsetX(z1))
            // Vertical: content y at the focus from the list's first visible item.
            val i = listState.firstVisibleItemIndex.coerceIn(0, max(0, pages.size - 1))
            val o = listState.firstVisibleItemScrollOffset / density
            val y0 = if (pages.isEmpty()) 0f else tops[i] * z0 + o
            val cy = (y0 + fy - topPad(z0)) / z0
            val want = max(0f, cy * z1 - fy + topPad(z1))
            val unscaled = want / z1
            var j = tops.indexOfLast { it <= unscaled }.coerceAtLeast(0)
            if (pages.isEmpty()) j = 0
            val itemOffset = if (pages.isEmpty()) 0f else (want - tops[j] * z1) * density
            zoom = z1
            listState.requestScrollToItem(j, itemOffset.roundToInt())
        }

        val horizontal = rememberScrollableState { delta ->
            val before = offsetX
            offsetX = (offsetX - delta).coerceIn(0f, maxOffsetX(zoom))
            before - offsetX
        }
        val doubleTapZoom = remember(pages) { Animatable(1f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Two fingers: pinch and pan, taken before the list sees them.
                .pointerInput(pages) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val change = event.calculateZoom()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (change != 1f && centroid != Offset.Unspecified) zoomAround(zoom * change, centroid)
                                val pan = event.calculatePan()
                                if (pan.x != 0f) offsetX = (offsetX - pan.x).coerceIn(0f, maxOffsetX(zoom))
                                if (pan.y != 0f) listState.dispatchRawDelta(-pan.y)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                // Like Photos: at 1:1 zoom in 2x around the tap; otherwise back to 1:1.
                .pointerInput(pages) {
                    detectTapGestures(onDoubleTap = { point ->
                        val target = if (kotlin.math.abs(zoom - 1f) > 0.01f) 1f else DOUBLE_TAP_ZOOM
                        scope.launch {
                            doubleTapZoom.snapTo(zoom)
                            doubleTapZoom.animateTo(target, tween(250)) { zoomAround(value, point) }
                        }
                    })
                }
                .scrollable(horizontal, Orientation.Horizontal, enabled = contentW * zoom > vpW),
        ) {
            val rowW = rowWidth(zoom)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = topPad(zoom).dp, bottom = bottomPad(zoom).dp),
                verticalArrangement = Arrangement.spacedBy((gapDp * zoom).dp),
                modifier = Modifier
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    .width(rowW.dp)
                    .fillMaxHeight()
                    .offset { IntOffset(-offsetX.roundToInt(), 0) },
            ) {
                itemsIndexed(pages) { index, page ->
                    Box(modifier = Modifier.width(rowW.dp), contentAlignment = Alignment.TopCenter) {
                        ViewerPage(index, page, zoom, density, render)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerPage(
    index: Int,
    page: ChatShareViewerPage,
    zoom: Float,
    density: Float,
    render: suspend (Int, Int) -> Bitmap?,
) {
    // Resolution steps of √2, so a pinch re-renders a page a few times at most
    // rather than on every frame, while a page is never decoded at more than ~1.4x
    // the width it is shown at (2x the pixels) - a power-of-two step allowed 4x.
    val want = page.widthDp * zoom * density
    val step = 2.0.pow(ceil(2.0 * ln(max(want, 64f) / 64f) / ln(2f)) / 2.0).toFloat() * 64f
    val widthPx = step.roundToInt()
    var shown by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, widthPx) {
        render(index, widthPx)?.let { shown = it }
    }
    Box(modifier = Modifier.size((page.widthDp * zoom).dp, (page.heightDp * zoom).dp)) {
        shown?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2f

/**
 * A long PNG cut into horizontal strips for [ChatShareZoomViewer]: each strip is
 * decoded on its own (BitmapRegionDecoder), at a sample size that matches the width it
 * is shown at, so memory follows what is on screen rather than the image's height.
 */
internal class ChatShareImageStrips(private val file: File, val widthPx: Int, val heightPx: Int, val scale: Float) {
    private val decoder: BitmapRegionDecoder? by lazy {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.absolutePath)
            else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.absolutePath, false)
        }.getOrNull()
    }

    val pages: List<ChatShareViewerPage> = run {
        val out = mutableListOf<ChatShareViewerPage>()
        var y = 0
        while (y < heightPx) {
            val h = min(STRIP, heightPx - y)
            out += ChatShareViewerPage(widthPx / scale, h / scale)
            y += h
        }
        out
    }

    suspend fun render(index: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val d = decoder ?: return@withContext null
        val top = index * STRIP
        val rect = Rect(0, top, widthPx, min(heightPx, top + STRIP))
        var sample = 1
        while (widthPx / (sample * 2) >= targetWidthPx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        runCatching { d.decodeRegion(rect, opts) }.getOrNull()
    }

    fun close() {
        runCatching { decoder?.recycle() }
    }

    private companion object {
        const val STRIP = 1024
    }
}

/**
 * A PDF's pages for [ChatShareZoomViewer]: one page point per dp at 1:1, each page
 * rendered on its own at the width it is shown at (capped, so a 4x zoom of a wide
 * page cannot ask for a hundred-megabyte bitmap). PdfRenderer allows one open page
 * at a time, hence the lock.
 */
internal class ChatSharePdfPages(file: File) {
    private val fd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = android.graphics.pdf.PdfRenderer(fd)
    private val lock = kotlinx.coroutines.sync.Mutex()
    private var closed = false

    val pages: List<ChatShareViewerPage> = (0 until renderer.pageCount).map { i ->
        renderer.openPage(i).use { ChatShareViewerPage(it.width.toFloat(), it.height.toFloat()) }
    }

    suspend fun render(index: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || index !in pages.indices) return@withLock null
            val page = pages[index]
            val maxWidth = kotlin.math.sqrt(MAX_PAGE_PIXELS * page.widthDp / page.heightDp).toInt()
            val w = min(targetWidthPx, maxWidth).coerceAtLeast(1)
            val h = (w * page.heightDp / page.widthDp).roundToInt().coerceAtLeast(1)
            runCatching {
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                renderer.openPage(index).use {
                    it.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                bitmap
            }.getOrNull()
        }
    }

    suspend fun close() {
        lock.withLock {
            if (closed) return@withLock
            closed = true
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    private companion object {
        /** ~48 MB per rendered page at 4 bytes a pixel. */
        const val MAX_PAGE_PIXELS = 12_000_000f
    }
}
