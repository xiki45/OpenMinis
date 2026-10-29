package com.openminis.app.ui.chat.share

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.outlined.Height
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.rememberMarkdownPrewarmer
import com.openminis.app.ui.theme.LocalChatPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/**
 * [T-multi-select-share] Opens straight into the rendered result: the long image is
 * made as soon as the preview appears; switching format, the tablet-width layout, the
 * table width or the appearance renders that variant, and every render is kept so
 * switching back is instant. The result is shown at 1:1 and pinches down to fit. The
 * top bar's share button hands the current file to the system share sheet. Port of
 * iOS ChatSharePreviewSheet.
 */
@Composable
internal fun ChatSharePreviewDialog(
    groups: List<List<ChatShareCell>>,
    count: Int,
    request: ChatShareRequest,
    state: ChatSharePreviewState,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ChatShareDialogSystemBars()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ChatSharePreviewContent(groups, count, request, state, onDismiss)
        }
    }
}

internal data class RenderKey(
    val format: ChatExportOptions.Format,
    val wideLayout: Boolean,
    val dark: Boolean,
    val adaptiveTables: Boolean,
)

internal class Rendered(val result: ChatExportRenderer.Result)

/**
 * One preview's options and finished renders. Owned by the picker and created afresh
 * each time the preview opens (so every share starts at phone width, tables as in
 * the chat, appearance following the app - iOS's sheet @State), but NOT by the
 * preview's own composition: if the preview's subtree is ever recomposed from
 * scratch while it stays open (seen on a Pixel 4a after returning from the system
 * share sheet: the picked appearance fell back to Light and nothing was shown), the
 * options and the finished files survive instead of resetting underneath the user.
 */
internal class ChatSharePreviewState {
    var wideLayout by mutableStateOf(false)
    var adaptiveTables by mutableStateOf(false)
    /** null = follow the app's current appearance; set once the user picks one. */
    var darkChoice by mutableStateOf<Boolean?>(null)
    /** The header's "<model> · <date>", resolved once. */
    var subtitle by mutableStateOf<List<String>?>(null)
    val rendered = androidx.compose.runtime.mutableStateMapOf<RenderKey, Rendered>()
    /** Widths (phone / tablet) whose long image turned out too tall when rendered, in case the estimate was low. */
    val imageFailedWidths = androidx.compose.runtime.mutableStateMapOf<Boolean, Boolean>()
    val id = Integer.toHexString(System.identityHashCode(this))
}

@Composable
private fun ChatSharePreviewContent(
    groups: List<List<ChatShareCell>>,
    count: Int,
    request: ChatShareRequest,
    state: ChatSharePreviewState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val parent = rememberCompositionContext()
    val prewarm = rememberMarkdownPrewarmer()
    val appDark = LocalChatPalette.current.isDark
    val stage = remember { ChatExportStage(view.rootView as ViewGroup, view, parent) }
    DisposableEffect(stage) { onDispose { stage.dispose() } }
    val renderer = remember(stage) { ChatExportRenderer(stage, prewarm) }

    // Options and renders live in [state] (see ChatSharePreviewState); width and
    // tables are not remembered across shares, the format is.
    var wideLayout by state::wideLayout
    var adaptiveTables by state::adaptiveTables
    var darkChoice by state::darkChoice
    val dark = darkChoice ?: appDark
    var subtitle by state::subtitle
    var storedFormat by remember { mutableStateOf(ChatSharePrefs.format(context)) }
    val rendered = state.rendered
    val imageFailedWidths = state.imageFailedWidths
    DisposableEffect(state) {
        AppLogger.info("ChatExport", "[ChatShare] preview composed state=${state.id} cached=${rendered.size} dark=$darkChoice")
        onDispose { AppLogger.info("ChatExport", "[ChatShare] preview left composition state=${state.id}") }
    }
    /** Per width: whether the selection is estimated taller than a long image may be. */
    val estimatedTooTall = remember(groups) {
        val byId = LinkedHashMap<String, com.openminis.app.ui.chat.ChatMessage>()
            .also { m -> request.messages.forEach { m.putIfAbsent(it.id, it) } }
        val inputs = ChatShareContent.estimateInputs(groups, byId)
        listOf(false, true).associateWith { wide ->
            ChatShareHeightEstimate.tooTallForImage(
                ChatShareHeightEstimate.points(inputs, ChatExportRenderer.rowWidthDp(wide, context)),
            )
        }
    }
    /** Too long for one image at the current width: only a PDF is offered. */
    val imageUnavailable = estimatedTooTall[wideLayout] == true || imageFailedWidths[wideLayout] == true
    val format = if (imageUnavailable) ChatExportOptions.Format.PDF else storedFormat
    var toast by remember { mutableStateOf<ChatShareToastMessage?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    /**
     * The render the progress overlay belongs to: a superseded render finishing its
     * cancellation must not clear the progress of the one that replaced it.
     */
    var activeKey by remember { mutableStateOf<RenderKey?>(null) }
    val key = RenderKey(format, wideLayout, dark, adaptiveTables)

    LaunchedEffect(key) {
        if (rendered[key] != null) return@LaunchedEffect
        val parts = subtitle ?: run {
            val covered = groups.flatten().mapNotNull { c -> request.messages.firstOrNull { it.id == c.messageId } }
            val date = try {
                request.loadDate(covered)
            } catch (e: CancellationException) {
                throw e // superseded: the next render looks the date up again
            } catch (e: Exception) {
                null
            }
            chatExportSubtitle(request.modelName, date).also { subtitle = it }
        }
        if (rendered.isEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ChatExportRenderer.clearExports(context) }
        }
        errorRes = null
        activeKey = key
        progress = 0 to groups.size
        try {
            val result = renderer.render(
                groups = groups,
                messages = request.messages,
                title = request.title,
                subtitle = parts,
                options = ChatExportOptions(key.format, key.wideLayout, key.dark, key.adaptiveTables),
            ) { done, total -> if (activeKey == key) progress = done to total }
            rendered[key] = Rendered(result)
            AppLogger.info("ChatExport", "[ChatShare] rendered state=${state.id} key=$key")
        } catch (e: CancellationException) {
            AppLogger.info("ChatExport", "[ChatExport] superseded or cancelled")
            throw e
        } catch (e: ChatExportError.ImageTooLarge) {
            // The estimate was low: offer the PDF for this width instead.
            AppLogger.info("ChatExport", "[ChatExport] image too tall at wide=${key.wideLayout} - switching to PDF")
            imageFailedWidths[key.wideLayout] = true
        } catch (e: ChatExportError) {
            AppLogger.info("ChatExport", "[ChatExport] failed: ${e::class.simpleName}")
            errorRes = e.messageRes
        } catch (e: Exception) {
            AppLogger.error("ChatExport", "[ChatExport] failed: ${e.message}")
            errorRes = R.string.chat_share_error_write_failed
        } finally {
            if (activeKey == key) { progress = null; activeKey = null }
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        val current = rendered[key]
        ChatShareTopBar(
            title = stringResource(R.string.chat_share_share_count, count),
            leading = stringResource(R.string.common_cancel),
            onLeading = onDismiss,
            trailingContent = {
                if (current != null) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.common_share),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { shareExport(context, current.result.file, key.format) }
                            .padding(10.dp)
                            .size(22.dp),
                    )
                }
            },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(LocalChatPalette.current.secondaryBg),
        ) {
            if (current != null) {
                // One viewer per file: switching back to an option already rendered
                // must show THAT file from 1:1 at the top, not keep the previous
                // option's page bitmaps, zoom and scroll (iOS reloads on a new URL).
                androidx.compose.runtime.key(current.result.file) {
                    if (key.format == ChatExportOptions.Format.PDF) ChatSharePdfPreview(current.result)
                    else ChatShareImagePreview(current.result)
                }
            }
            val p = progress
            val err = errorRes
            if (p != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f))
                        .padding(20.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    Text(
                        text = "${stringResource(R.string.chat_share_creating)} ${p.first}/${p.second}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (err != null && current == null) {
                Text(
                    text = stringResource(err),
                    color = Color(0xFFFF3B30),
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
            val tooLong = stringResource(R.string.chat_share_too_long_image)
            ChatShareFormatSwitch(
                format = format,
                imageUnavailable = imageUnavailable,
                onSelect = { storedFormat = it; ChatSharePrefs.setFormat(context, it) },
                onBlockedImage = { toast = ChatShareToastMessage(tooLong, info = true) },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            )
            ChatShareToast(toast, onDone = { if (toast === it) toast = null }, modifier = Modifier.align(Alignment.BottomCenter))
        }
        HorizontalDivider(color = LocalChatPalette.current.separator, thickness = 0.5.dp)
        val phone = stringResource(R.string.chat_share_width_phone)
        val tablet = stringResource(R.string.chat_share_width_tablet)
        val adaptive = stringResource(R.string.chat_share_table_adaptive)
        val defaultWidth = stringResource(R.string.chat_share_table_default)
        val darkLabel = stringResource(R.string.appearance_theme_dark)
        val lightLabel = stringResource(R.string.appearance_theme_light)
        val widthLabel = stringResource(R.string.chat_share_width_label, if (wideLayout) tablet else phone)
        val tableLabel = stringResource(R.string.chat_share_table_label, if (adaptiveTables) adaptive else defaultWidth)
        val appearanceLabel = stringResource(R.string.chat_share_appearance_label, if (dark) darkLabel else lightLabel)
        val switchedTo = stringResource(R.string.chat_share_switched_to)
        fun switched(label: String) { toast = ChatShareToastMessage(switchedTo.format(label)) }
        ChatShareControlStrip(
            items = listOf(
                ChatShareControl(
                    icon = if (wideLayout) Icons.Outlined.TabletAndroid else Icons.Outlined.PhoneAndroid,
                    label = widthLabel,
                    active = wideLayout,
                    onClick = {
                        wideLayout = !wideLayout
                        switched(context.getString(R.string.chat_share_width_label, if (wideLayout) tablet else phone))
                    },
                ),
                ChatShareControl(
                    icon = if (adaptiveTables) Icons.Outlined.Height else Icons.Outlined.TableChart,
                    iconRotation = if (adaptiveTables) 90f else 0f,
                    label = tableLabel,
                    active = adaptiveTables,
                    onClick = {
                        adaptiveTables = !adaptiveTables
                        switched(context.getString(R.string.chat_share_table_label, if (adaptiveTables) adaptive else defaultWidth))
                    },
                ),
                ChatShareControl(
                    icon = if (dark) Icons.Filled.DarkMode else Icons.Outlined.LightMode,
                    label = appearanceLabel,
                    active = dark,
                    onClick = {
                        val next = !dark
                        darkChoice = next
                        switched(context.getString(R.string.chat_share_appearance_label, if (next) darkLabel else lightLabel))
                    },
                ),
            ),
        )
    }
}

/** The long image at 1:1, strip by strip. */
@Composable
private fun ChatShareImagePreview(result: ChatExportRenderer.Result) {
    val strips = remember(result.file) { ChatShareImageStrips(result.file, result.width, result.height, result.scale) }
    DisposableEffect(strips) { onDispose { strips.close() } }
    ChatShareZoomViewer(
        pages = strips.pages,
        gapDp = 0f,
        topClearanceDp = FORMAT_SWITCH_CLEARANCE,
        centerShortContent = true,
        render = strips::render,
    )
}

/** The PDF at 1:1 (one page point per dp), pages stacked, starting below the format switch. */
@Composable
private fun ChatSharePdfPreview(result: ChatExportRenderer.Result) {
    val pdf = remember(result.file) { runCatching { ChatSharePdfPages(result.file) }.getOrNull() } ?: return
    DisposableEffect(pdf) {
        // Closing waits for a page render in flight; off the main thread.
        onDispose { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { pdf.close() } }
    }
    ChatShareZoomViewer(
        pages = pdf.pages,
        gapDp = 8f,
        topClearanceDp = FORMAT_SWITCH_CLEARANCE,
        centerShortContent = false,
        render = pdf::render,
    )
}

/** Hands a finished export to the system share sheet. */
internal fun shareExport(context: Context, file: File, format: ChatExportOptions.Format) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (format == ChatExportOptions.Format.PDF) "application/pdf" else "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }.onFailure { AppLogger.error("ChatExport", "[ChatExport] share failed: ${it.message}") }
}
