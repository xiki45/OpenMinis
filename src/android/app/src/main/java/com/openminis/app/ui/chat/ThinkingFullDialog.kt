package com.openminis.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * [#467] "Show full thinking": the whole thinking text of a finished block
 * in a full-screen dialog (the style of the earlier over-100K viewer it
 * replaces), never expanded inside the chat list.
 *
 * The text is split by [ThinkingChunking] off the main thread and shown as
 * lazy items, a few chunks at first and a batch more each time the end of
 * what is shown scrolls into view. Each batch's first frame is timed; a slow
 * one switches to one chunk per tap of "Load more" ([ThinkingChunking.paceAfter]),
 * so a device that cannot keep up is never frozen by automatic loading.
 * "Copy All" copies the full text, whatever is on screen; the shown text is
 * selectable.
 *
 * [content] is the text when the dialog opened: the entry is only offered
 * once the block has finished streaming.
 */
@Composable
fun ThinkingFullDialog(content: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val thinkingBlue = Color(0xFF007AFF)
    val chunks by produceState<List<ThinkingChunking.Chunk>?>(null, content) {
        value = withContext(Dispatchers.Default) { ThinkingChunking.chunks(content) }
    }
    var revealed by remember(content) { mutableIntStateOf(0) }
    var pacing by remember(content) { mutableStateOf(ThinkingChunking.Pacing()) }
    // Bumped per batch; the watchdog below times that batch's first frame.
    var batchStartedAt by remember(content) { mutableStateOf(0L) }
    LaunchedEffect(chunks) {
        chunks?.let { revealed = ThinkingChunking.initialCount(it) }
    }
    LaunchedEffect(batchStartedAt) {
        if (batchStartedAt == 0L) return@LaunchedEffect
        withFrameNanos { }
        pacing = ThinkingChunking.paceAfter(pacing, System.currentTimeMillis() - batchStartedAt)
    }
    val reveal = {
        chunks?.let { c ->
            if (ThinkingChunking.hasMore(revealed, c.size)) {
                batchStartedAt = System.currentTimeMillis()
                revealed = ThinkingChunking.nextCount(revealed, c.size, pacing)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.thinking_full_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = thinkingBlue,
                        )
                        Text(
                            text = stringResource(
                                R.string.thinking_full_char_count,
                                NumberFormat.getIntegerInstance().format(content.length),
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    MinisTextButton(onClick = {
                        try {
                            clipboard.setText(AnnotatedString(ThinkingChunking.copyText(content)))
                            Toast.makeText(context, R.string.terminal_copied_all, Toast.LENGTH_SHORT).show()
                        } catch (e: RuntimeException) {
                            // A text past the Binder transaction limit cannot go to the clipboard.
                            Toast.makeText(context, R.string.thinking_full_copy_failed, Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text(text = stringResource(R.string.terminal_copy_all))
                    }
                    MinisTextButton(onClick = onDismiss) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                }
                HorizontalDivider()
                val list = chunks
                if (list == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    val state = rememberLazyListState()
                    SelectionContainer(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 32.dp,
                            ),
                        ) {
                            items(count = minOf(revealed, list.size), key = { it }) { i ->
                                Text(
                                    text = ThinkingChunking.chunkText(content, list, i).removeSuffix("\n"),
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                )
                            }
                            if (ThinkingChunking.hasMore(revealed, list.size)) {
                                item(key = "more") {
                                    // The end of what is shown came into view: the next batch
                                    // (iOS LazyRevealChunks footer onAppear).
                                    if (pacing.auto) LaunchedEffect(revealed) { reveal() }
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    ) {
                                        MinisTextButton(onClick = { reveal() }) {
                                            Text(text = stringResource(R.string.thinking_full_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

