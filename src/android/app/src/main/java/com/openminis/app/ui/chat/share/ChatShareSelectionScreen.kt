package com.openminis.app.ui.chat.share

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.LocalMinisSelectionController
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.theme.LocalChatPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [T-multi-select-share] What the picker opens with: the chat's cells, its messages
 * when the picker opened (a snapshot: a reply still streaming is shared as it was
 * then), the round to preselect, and the header's title / model / date.
 */
internal class ChatShareRequest(
    val cells: List<ChatShareCell>,
    val messages: List<ChatMessage>,
    val initialSelection: Set<String>,
    val title: String,
    /** The concrete model the session resolves to, for the export header; null when none. */
    val modelName: String?,
    /** Loads when the first covered message was written (epoch ms), for the export header. */
    val loadDate: suspend (List<ChatMessage>) -> Long?,
)

/** Picker preferences that outlive one share (iOS @AppStorage "chatShare.*"). */
internal object ChatSharePrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("chat_share", Context.MODE_PRIVATE)
    fun hideTools(context: Context) = prefs(context).getBoolean("hideTools", false)
    fun setHideTools(context: Context, value: Boolean) = prefs(context).edit().putBoolean("hideTools", value).apply()
    fun hideThinking(context: Context) = prefs(context).getBoolean("hideThinking", false)
    fun setHideThinking(context: Context, value: Boolean) = prefs(context).edit().putBoolean("hideThinking", value).apply()
    fun format(context: Context): ChatExportOptions.Format =
        runCatching { ChatExportOptions.Format.valueOf(prefs(context).getString("format", null) ?: "IMAGE") }
            .getOrDefault(ChatExportOptions.Format.IMAGE)
    fun setFormat(context: Context, value: ChatExportOptions.Format) = prefs(context).edit().putString("format", value.name).apply()
}

/**
 * [T-multi-select-share] The share picker: the chat drawn exactly as the session draws
 * it (ChatShareCellContent — the same rows the export renders), one selectable row per
 * cell: a user message, each text block of a reply, each tool call, thinking, callback
 * cards — so a long tool log can be left out. A selected row gets a tint and a check
 * badge floating at its leading edge, vertically centred, so every row keeps the
 * chat's layout. Rows are display-only: none of the chat's long-press actions exist.
 *
 * Selecting: tap a row; drag down the leading edge to select or deselect a run; long-
 * press a row for "Select All Below" / "Deselect All Below"; Select All in the top
 * bar; floating chips for Select Below (from the first row on screen), Hide Tools,
 * Hide Thinking and Clear Selection. Then "Share (N)" opens the preview. Port of iOS
 * ChatShareSelectionView.
 */
@Composable
internal fun ChatShareSelectionDialog(request: ChatShareRequest, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ChatShareDialogSystemBars()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Nothing in here takes part in the chat's text selection.
            CompositionLocalProvider(LocalMinisSelectionController provides null) {
                ChatShareSelectionContent(request, onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatShareSelectionContent(request: ChatShareRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    var hideTools by remember { mutableStateOf(ChatSharePrefs.hideTools(context)) }
    var hideThinking by remember { mutableStateOf(ChatSharePrefs.hideThinking(context)) }
    fun isHidden(kind: ChatShareCell.Kind) =
        (hideTools && kind == ChatShareCell.Kind.TOOL) || (hideThinking && kind == ChatShareCell.Kind.THINKING)

    /** The rows on show — everything but the kinds folded away. */
    val cells = remember(request, hideTools, hideThinking) { request.cells.filter { !isHidden(it.kind) } }
    val byId = remember(request) {
        LinkedHashMap<String, ChatMessage>().also { m -> request.messages.forEach { m.putIfAbsent(it.id, it) } }
    }
    var selection by remember {
        mutableStateOf(request.initialSelection - request.cells.filter { isHidden(it.kind) }.map { it.id }.toSet())
    }
    fun deselectHidden() {
        selection = selection - request.cells.filter { isHidden(it.kind) }.map { it.id }.toSet()
    }
    var showPreview by remember { mutableStateOf(false) }
    // A fresh preview state per opening, held here rather than in the preview.
    var previewState by remember { mutableStateOf(ChatSharePreviewState()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clearancePx = with(density) { FLOATING_BAR_CLEARANCE.toPx() }

    // Open on the preselected round.
    LaunchedEffect(Unit) {
        val first = cells.indexOfFirst { it.id in selection }
        if (first > 0) {
            listState.scrollToItem(first)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == first }
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            // scrollToItem leaves the row just below the 56dp top padding; move it
            // to the centre of the whole list, as iOS scrollTo(anchor: .center).
            if (item != null) listState.scrollBy(-((viewport - item.size) / 2f - clearancePx).coerceAtLeast(0f))
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        val allSelected = cells.isNotEmpty() && selection.containsAll(cells.map { it.id })
        ChatShareTopBar(
            title = if (selection.isEmpty()) stringResource(R.string.chat_share_select_messages)
            else stringResource(R.string.chat_share_n_selected, selection.size),
            leading = stringResource(R.string.common_cancel),
            onLeading = onDismiss,
            trailing = stringResource(if (allSelected) R.string.chat_share_deselect_all else R.string.chat_share_select_all),
            onTrailing = { selection = if (allSelected) emptySet() else cells.map { it.id }.toSet() },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
          // The chat's own reading measure on a wide window (ChatScreen caps its list
          // at 900dp), so the rows look as they do in the chat.
          Box(
              modifier = Modifier
                  .fillMaxHeight()
                  .widthIn(max = CHAT_READING_MAX_WIDTH)
                  .fillMaxWidth()
                  .align(Alignment.TopCenter),
          ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = FLOATING_BAR_CLEARANCE, bottom = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(cells, key = { it.id }) { cell ->
                    val message = byId[cell.messageId] ?: return@items
                    ChatShareRow(
                        cell = cell,
                        message = message,
                        selected = cell.id in selection,
                        accent = accent,
                        onToggle = { selection = if (cell.id in selection) selection - cell.id else selection + cell.id },
                        onBelow = { on -> selection = ChatShareCells.setBelow(cell.id, cells, selection, on) },
                    )
                }
            }
            ChatShareDragStrip(
                cells = cells,
                listState = listState,
                selection = { selection },
                onSelection = { selection = it },
                modifier = Modifier.align(Alignment.CenterStart),
            )
          }
            ChatShareFloatingActions(
                hasSelection = selection.isNotEmpty(),
                hideTools = hideTools,
                hideThinking = hideThinking,
                onSelectBelow = {
                    // Everything from the first row on screen (under the floating
                    // buttons) down — "from what I am looking at". Anchoring on the
                    // first SELECTED row did nothing after scrolling up past it.
                    val info = listState.layoutInfo
                    val visible = info.visibleItemsInfo.filter {
                        val top = it.offset - info.viewportStartOffset
                        top + it.size > clearancePx && top < info.viewportEndOffset - info.viewportStartOffset
                    }
                    val first = visible.minOfOrNull { it.index } ?: if (cells.isEmpty()) null else 0
                    if (first != null && first in cells.indices) {
                        selection = ChatShareCells.setBelow(cells[first].id, cells, selection, true)
                    }
                },
                onToggleTools = {
                    hideTools = !hideTools
                    ChatSharePrefs.setHideTools(context, hideTools)
                    deselectHidden()
                },
                onToggleThinking = {
                    hideThinking = !hideThinking
                    ChatSharePrefs.setHideThinking(context, hideThinking)
                    deselectHidden()
                },
                onClear = { selection = emptySet() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        HorizontalDivider(color = LocalChatPalette.current.separator, thickness = 0.5.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            val enabled = selection.isNotEmpty()
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (enabled) accent else accent.copy(alpha = 0.35f))
                    .clickable(enabled = enabled) {
                        previewState = ChatSharePreviewState()
                        showPreview = true
                    },
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.chat_share_share_count, selection.size),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    if (showPreview) {
        val groups = remember(selection, request) { ChatShareCells.renderGroups(request.cells, selection) }
        ChatSharePreviewDialog(
            groups = groups,
            count = selection.size,
            request = request,
            state = previewState,
            onDismiss = { showPreview = false },
        )
    }
}

/** An inline navigation bar: text action, centred title, text action. */
@Composable
internal fun ChatShareTopBar(
    title: String,
    leading: String,
    onLeading: () -> Unit,
    trailing: String? = null,
    onTrailing: () -> Unit = {},
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp)) {
        Text(
            text = leading,
            color = accent,
            fontSize = 16.sp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onLeading)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).widthIn(max = 220.dp),
        )
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            when {
                trailingContent != null -> trailingContent()
                trailing != null -> Text(
                    text = trailing,
                    color = accent,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onTrailing)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatShareRow(
    cell: ChatShareCell,
    message: ChatMessage,
    selected: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    onBelow: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Selected: a tint plus a check badge floating at the leading edge (not a
            // column, so the row keeps the chat's layout).
            .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent),
    ) {
        ChatShareCellContent(cell, message, Modifier.padding(vertical = 4.dp))
        if (selected) {
            // Small, tucked into the 16dp side margin and centred on the row's
            // height, so it marks the row without covering the reply's text.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 1.dp)
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(accent),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(9.dp))
            }
        }
        // The row's own touch layer, above the chat content: the chat's taps and
        // long-presses (menus, detail sheets, links) never fire here.
        Box(
            modifier = Modifier
                .matchParentSize()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                    onLongClick = { menu = true },
                ),
        )
        MinisMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_share_select_all_below)) },
                onClick = { menu = false; onBelow(true) },
                leadingIcon = { Icon(Icons.Filled.VerticalAlignBottom, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_share_deselect_all_below)) },
                onClick = { menu = false; onBelow(false) },
                leadingIcon = { Icon(Icons.Outlined.VerticalAlignBottom, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/**
 * The leading strip: a drag here selects (or, when it starts on a selected row,
 * deselects) every row between its start and the finger, like Photos; dragging back
 * undoes, and the list scrolls when the finger nears the top or bottom.
 */
@Composable
private fun ChatShareDragStrip(
    cells: List<ChatShareCell>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selection: () -> Set<String>,
    onSelection: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val edge = with(density) { 60.dp.toPx() }
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .width(DRAG_STRIP_WIDTH)
            .fillMaxHeight()
            .pointerInput(cells) {
                fun indexAt(y: Float): Int? {
                    val info = listState.layoutInfo
                    return info.visibleItemsInfo.firstOrNull {
                        val top = it.offset - info.viewportStartOffset
                        y >= top && y < top + it.size
                    }?.index
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = indexAt(down.position.y) ?: return@awaitEachGesture
                    if (start !in cells.indices) return@awaitEachGesture
                    val base = selection()
                    val on = cells[start].id !in base
                    var current = start
                    var fingerY = down.position.y
                    fun apply() {
                        val lo = minOf(start, current)
                        val hi = maxOf(start, current)
                        val ids = (lo..hi).filter { it in cells.indices }.map { cells[it].id }
                        val next = if (on) base + ids else base - ids.toSet()
                        if (next != selection()) onSelection(next)
                    }
                    apply()
                    var autoScroll: Job? = null
                    autoScroll = scope.launch {
                        while (true) {
                            withFrameNanos { }
                            val height = size.height.toFloat()
                            val delta = when {
                                fingerY > height - edge -> 14f
                                fingerY < edge -> -14f
                                else -> 0f
                            }
                            if (delta != 0f) {
                                listState.scrollBy(delta)
                                indexAt(fingerY)?.let { if (it != current) { current = it; apply() } }
                            }
                        }
                    }
                    try {
                        drag(down.id) { change ->
                            fingerY = change.position.y
                            indexAt(fingerY)?.let { if (it != current) { current = it; apply() } }
                            change.consume()
                        }
                    } finally {
                        autoScroll?.cancel()
                    }
                }
            },
    )
}

/**
 * Actions floating over the top of the list, each its own compact floating chip (no
 * shared container), icon + short single-line label. The row is centred on its
 * current width, so it re-centres when "Clear Selection" comes and goes; a row wider
 * than the screen (long languages) scrolls sideways instead.
 *
 * The two visibility toggles share one visual rule: the icon shows the CURRENT state
 * — plain while the rows show, the same icon struck through while they are hidden —
 * and the label names the action.
 */
@Composable
private fun ChatShareFloatingActions(
    hasSelection: Boolean,
    hideTools: Boolean,
    hideThinking: Boolean,
    onSelectBelow: () -> Unit,
    onToggleTools: () -> Unit,
    onToggleThinking: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(top = 4.dp).height(46.dp)) {
        val fullWidth = maxWidth
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                // At least as wide as the screen, contents centred: fits → centred on
                // the row's own width; wider → scrolls from the leading edge.
                modifier = Modifier.widthIn(min = fullWidth).padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                ChatShareFloatingChip(stringResource(R.string.chat_share_select_below), Icons.Filled.VerticalAlignBottom, onClick = onSelectBelow)
                ChatShareFloatingChip(
                    stringResource(if (hideTools) R.string.chat_share_show_tools else R.string.chat_share_hide_tools),
                    Icons.Outlined.Visibility, struck = hideTools, onClick = onToggleTools,
                )
                ChatShareFloatingChip(
                    stringResource(if (hideThinking) R.string.chat_share_show_thinking else R.string.chat_share_hide_thinking),
                    Icons.Outlined.Psychology, struck = hideThinking, onClick = onToggleThinking,
                )
                AnimatedVisibility(
                    visible = hasSelection,
                    enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f),
                    exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.9f),
                ) {
                    ChatShareFloatingChip(stringResource(R.string.chat_share_clear_selection), Icons.Filled.Close, onClick = onClear)
                }
            }
        }
    }
}

/**
 * The floating surface of the picker's chips and the preview's format switch: a
 * light see-through capsule with a hairline and a soft shadow (stronger in dark), the
 * weight of iOS's pre-26 material fallback for its glass buttons.
 */
internal fun Modifier.chatShareFloatingSurface(dark: Boolean, surface: Color, onSurface: Color): Modifier =
    this
        .shadow(
            elevation = 6.dp,
            shape = CircleShape,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.10f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.10f),
        )
        .clip(CircleShape)
        .background(surface.copy(alpha = 0.86f))
        .border(0.5.dp, onSurface.copy(alpha = 0.08f), CircleShape)

@Composable
private fun ChatShareFloatingChip(label: String, icon: ImageVector, struck: Boolean = false, onClick: () -> Unit) {
    val palette = LocalChatPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .chatShareFloatingSurface(palette.isDark, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        StruckIcon(icon, struck, MaterialTheme.colorScheme.primary, 14.dp)
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * An icon that can be drawn struck through — a gap cut through the glyph, then a
 * diagonal line, how SF Symbols' `.slash` variants look — so "eye" and "brain" get the
 * same strike (iOS floatingLabel(struck:)).
 */
@Composable
private fun StruckIcon(icon: ImageVector, struck: Boolean, tint: Color, size: Dp) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(size)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                if (!struck) return@drawWithContent
                val w = this.size.width
                val h = this.size.height
                val gap = w * 0.30f
                val line = w * 0.11f
                val length = w * 1.25f
                rotate(-45f) {
                    drawRoundRect(
                        color = Color.Black,
                        topLeft = Offset((w - gap) / 2f, (h - length) / 2f),
                        size = Size(gap, length),
                        cornerRadius = CornerRadius(gap / 2f),
                        blendMode = BlendMode.Clear,
                    )
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset((w - line) / 2f, (h - length * 0.8f) / 2f),
                        size = Size(line, length * 0.8f),
                        cornerRadius = CornerRadius(line / 2f),
                    )
                }
            },
    )
}

/** Height the floating buttons cover at the top of the list. */
private val FLOATING_BAR_CLEARANCE = 56.dp

/** ChatScreen's CHAT_MAX_CONTENT_WIDTH: the chat list's width cap on wide windows. */
private val CHAT_READING_MAX_WIDTH = 900.dp

/** Width of the leading strip that drag-selects. */
private val DRAG_STRIP_WIDTH = 36.dp

/**
 * [T7c-share-statusbar] A full-screen Compose Dialog runs in its OWN window, and
 * the system bars take their icon colour from the top window. Nothing set it
 * for the share dialogs, so they kept the default white icons, invisible over
 * the light top bar and bottom strip in the light app theme (Pixel 6 report).
 * Set the dialog window's status- and navigation-bar icons from the APP theme
 * the chrome is drawn in - never from the export's Appearance option, which
 * only themes the rendered image / PDF. Same fix as FilePreviewScreen
 * (95d4bc7a9) and WebPreviewBottomSheet. Call it inside the Dialog's content.
 */
@Composable
internal fun ChatShareDialogSystemBars() {
    val appDark = LocalChatPalette.current.isDark
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, appDark) {
        chatShareDialogWindow(view)?.let { w ->
            androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).apply {
                isAppearanceLightStatusBars = !appDark
                isAppearanceLightNavigationBars = !appDark
            }
        }
        onDispose { }
    }
}

private fun chatShareDialogWindow(view: android.view.View): android.view.Window? {
    var p: android.view.ViewParent? = view.parent
    while (p != null) {
        if (p is androidx.compose.ui.window.DialogWindowProvider) return p.window
        p = p.parent
    }
    return null
}
