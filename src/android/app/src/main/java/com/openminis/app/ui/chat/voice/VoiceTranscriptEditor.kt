package com.openminis.app.ui.chat.voice

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * [T-android-voice-dblclick-select] The voice transcript's edit mode.
 *
 * It used to be a Compose `BasicTextField`. Compose text fields (1.9) count
 * clicks for the MOUSE only: on touch, a second tap just moves the caret, and
 * selecting a word takes a long press. So once the transcript was in edit
 * mode, double-tapping a word did nothing visible - unlike iOS, where the
 * editor is a UITextView and a double tap selects the word or phrase.
 *
 * A platform EditText behaves like the iOS editor: double tap selects the word
 * under the finger (the platform word iterator, which segments Chinese by
 * dictionary rather than one character at a time), followed by the system
 * selection handles and the copy / cut / paste menu. Long press and caret
 * placement are the platform's own as well.
 *
 * The caller keeps its String; [onValueChange] reports every edit. Focus and
 * the keyboard are taken when the editor appears and given back when it
 * leaves, as the Compose field did.
 */
@Composable
internal fun VoiceTranscriptEditor(
    value: String,
    onValueChange: (String) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f).toArgb()
    val maxHeightPx = with(LocalDensity.current) { maxHeight.roundToPx() }
    val latestOnChange = rememberUpdatedState(onValueChange)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                background = null
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                isVerticalScrollBarEnabled = true
                setText(value)
                setSelection(text.length)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val now = s?.toString().orEmpty()
                        if (tag != now) {
                            tag = now
                            latestOnChange.value(now)
                        }
                    }
                })
                tag = value
                post {
                    requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        },
        update = { edit ->
            edit.setTextColor(textColor)
            edit.highlightColor = highlight
            edit.maxHeight = maxHeightPx
            // An outside change (Clear, backspace button): replace the text,
            // keeping the caret where it was as far as the new text allows.
            if (edit.tag != value) {
                val caret = edit.selectionEnd.coerceIn(0, value.length)
                edit.tag = value
                edit.setText(value)
                edit.setSelection(caret)
            }
        },
        onRelease = { edit ->
            (edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(edit.windowToken, 0)
        },
    )
}
