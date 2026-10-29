package com.openminis.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * [T-android-editor-keyboard-overlap] Geometry of "keep the caret above the
 * keyboard", kept pure so it can be unit-tested.
 */
internal object EditorCaret {

    /**
     * How much of an editor the keyboard covers, in px: the part of the
     * editor's box below the keyboard's top edge.
     *
     * Measured from where the editor really is in its window instead of
     * taking the whole IME inset (`imePadding()`), because an editor is not
     * always flush with the bottom of the window: a footer, the navigation
     * bar padding of a Scaffold, a sheet or a split window sit below it, and
     * padding by the full inset would then shrink it twice.
     *
     * Never more than leaves [minVisible] of the editor: if a device reports
     * an inset larger than the keyboard it shows (seen with Gboard in pan
     * mode), a few lines stay usable instead of the editor collapsing to
     * nothing.
     */
    fun imeOverlap(editorBottomInWindow: Float, windowHeight: Int, imeBottom: Int, editorHeight: Int, minVisible: Int): Int {
        if (imeBottom <= 0 || windowHeight <= 0 || editorHeight <= 0) return 0
        val keyboardTop = windowHeight - imeBottom
        val covered = (editorBottomInWindow - keyboardTop).toInt()
        return covered.coerceIn(0, (editorHeight - minVisible).coerceAtLeast(0))
    }

    /**
     * The area to keep on screen for a caret at [cursor] (text coordinates):
     * its line plus [margin] above and below, so the line being typed is not
     * glued to the keyboard or to the top bar.
     */
    fun revealRect(cursor: Rect, margin: Float): Rect =
        Rect(cursor.left, (cursor.top - margin).coerceAtLeast(0f), cursor.right, cursor.bottom + margin)

    /** [selection] clamped to a text of [length] characters. */
    fun clamp(selection: TextRange, length: Int): TextRange =
        TextRange(selection.start.coerceIn(0, length), selection.end.coerceIn(0, length))
}

/**
 * [T-android-editor-keyboard-overlap] A multi-line editor for a whole
 * document (a memory file, a persona, a SKILL.md) whose caret stays visible
 * above the keyboard.
 *
 * Why the plain fields did not manage it: the app draws edge to edge, so the
 * window is not resized when the keyboard opens. A text field that filled the
 * screen kept its full height, with its lower part under the keyboard; it
 * scrolled the caret into ITS OWN bounds, which included the covered part.
 * And inside a scrolling page, the scroll container keeps the focused FIELD
 * in view, not the caret: for a field taller than what is left above the
 * keyboard that says nothing about where the caret is.
 *
 * What this does instead:
 *  - the field is as tall as its text and scrolls in a container
 *    ([ownScroll]) that ends at the keyboard's top edge
 *    ([EditorCaret.imeOverlap]); or, with [ownScroll] = false, it sits in a
 *    page that already scrolls and pads for the keyboard;
 *  - whenever the caret moves, the keyboard moves, the text re-wraps or the
 *    visible area changes while the field has focus, the caret's line is
 *    brought into view (BringIntoViewRequester, so whichever container
 *    scrolls the field does the scrolling).
 *
 * [outlined] draws the outlined-field border around the editor.
 */
@Composable
fun DocumentEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    placeholder: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    outlined: Boolean = false,
    ownScroll: Boolean = true,
    minHeight: Dp = 0.dp,
) {
    // The caret position has to be known here, so the field is driven by a
    // TextFieldValue; callers keep their plain String.
    var held by remember { mutableStateOf(TextFieldValue(value)) }
    val field = if (held.text == value) held else held.copy(text = value, selection = EditorCaret.clamp(held.selection, value.length))

    val density = LocalDensity.current
    val requester = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(density)

    var editorBottom by remember { mutableFloatStateOf(0f) }
    var editorHeight by remember { mutableIntStateOf(0) }
    val windowHeight = LocalView.current.rootView.height
    val overlap = if (ownScroll) {
        EditorCaret.imeOverlap(editorBottom, windowHeight, imeBottom, editorHeight, with(density) { MIN_VISIBLE.roundToPx() })
    } else {
        0
    }
    val viewport = editorHeight - overlap

    val margin = with(density) { CARET_MARGIN.toPx() }
    LaunchedEffect(focused, field.selection, imeBottom, viewport, layout?.size) {
        if (!focused) return@LaunchedEffect
        val result = layout ?: return@LaunchedEffect
        val caret = field.selection.end.coerceIn(0, result.layoutInput.text.length)
        requester.bringIntoView(EditorCaret.revealRect(result.getCursorRect(caret), margin))
    }

    val border = if (outlined) {
        Modifier.border(
            width = if (focused) 2.dp else 1.dp,
            color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            shape = MaterialTheme.shapes.extraSmall,
        )
    } else {
        Modifier
    }
    val mergedStyle = LocalTextStyle.current.merge(textStyle)
    val textColor = if (mergedStyle.color.isSpecified()) mergedStyle.color else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .onGloballyPositioned {
                editorHeight = it.size.height
                editorBottom = it.positionInWindow().y + it.size.height
            },
    ) {
        val scroller = if (ownScroll) {
            // The border is drawn around what is visible, so its bottom
            // edge sits on top of the keyboard instead of under it.
            Modifier
                .fillMaxSize()
                .padding(bottom = with(density) { overlap.toDp() })
                .then(border)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth().then(border)
        }
        Box(modifier = scroller) {
            // With its own scroll the field fills at least the visible area,
            // so a tap below the last line still lands in it.
            val least = if (ownScroll) with(density) { viewport.coerceAtLeast(0).toDp() } else minHeight
            BasicTextField(
                value = field,
                onValueChange = {
                    held = it
                    if (it.text != value) onValueChange(it.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = least)
                    .padding(contentPadding)
                    // Inside the padding, so the requester's coordinates are the text's.
                    .bringIntoViewRequester(requester)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = mergedStyle.copy(color = textColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                onTextLayout = { layout = it },
            )
            if (value.isEmpty() && placeholder != null) {
                Text(
                    placeholder,
                    style = mergedStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(contentPadding),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.Color.isSpecified(): Boolean = this != androidx.compose.ui.graphics.Color.Unspecified

/** Space kept above and below the caret's line. */
private val CARET_MARGIN = 24.dp

/** The least of an editor that stays visible whatever inset the keyboard reports. */
private val MIN_VISIBLE = 96.dp
