package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.SoulBodyLimitCheck
import com.openminis.app.agent.SoulStore
import com.openminis.app.agent.SystemPromptProseStore
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-system-prompt-prose] Settings page for the editable static prose of the
 * agent's system prompt.
 *
 * Scope is deliberately narrow: only the paragraph-level guidance (runtime
 * notes, shared-directory listing, minis:// scheme, file-creation rules,
 * tool-call style, tone-and-style, Android framework commands, environment
 * variables, scheduled tasks) can be edited here. The SOUL identity block,
 * the tool bullet list, skill/MCP fragments and the runtime-context suffix
 * are assembled by the app and are not editable.
 *
 * Layout mirrors [SoulSettingsScreen]: one section with a monospace editor, a
 * language-aware length counter, a "Reset to Default" that deletes the
 * override file, and an unsaved-changes guard on the way out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var body by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // [T-system-prompt-prose] What was loaded from disk, kept so "has the user
    // changed anything" is a value comparison rather than a boolean set by
    // every onValueChange (type a character, delete it, and a flag would
    // still report dirty). Null until the initial load completes, so the
    // screen never opens already dirty.
    var baseline by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val initial = withContext(Dispatchers.IO) {
            SystemPromptProseStore.loadRaw(context) ?: SystemPromptProseStore.DEFAULT_CONTENT
        }
        body = initial
        baseline = initial
        loaded = true
    }

    // Language-aware length check used by both the counter and the Save
    // button's enabled state. Delegates to the SOUL rule (CJK ratio 0.3 →
    // 1600 chars, else 1000 words) so both editors agree.
    val overLimit by remember(body) { derivedStateOf { SystemPromptProseStore.isOverLimit(body) } }
    val isDirty = loaded && baseline != null && body != baseline

    val save: () -> Unit = {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SystemPromptProseStore.save(context, body) }
                onBack()
            } catch (t: Throwable) {
                saveError = t.message ?: "save failed"
            }
        }
        Unit
    }

    // Leaving with unsaved edits asks first. One lambda feeds both the app
    // bar's back arrow and the system back gesture so they cannot disagree.
    val attemptBack: () -> Unit = {
        if (isDirty) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = isDirty) { showDiscardDialog = true }

    SettingsScaffold(
        title = stringResource(R.string.system_prompt_settings_title),
        onBack = attemptBack,
        actions = {
            MinisTextButton(
                onClick = save,
                enabled = loaded && isDirty && !overLimit.isOverLimit,
            ) { Text(stringResource(R.string.system_prompt_save)) }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.system_prompt_section_prose),
            footer = stringResource(R.string.system_prompt_prose_footer),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    placeholder = { Text(stringResource(R.string.system_prompt_prose_placeholder)) },
                )
                Spacer(Modifier.height(6.dp))
                val warnColor: Color =
                    if (overLimit.isOverLimit) Color(0xFFFF3B30)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                val indicatorText: String = when (val c = overLimit) {
                    is SoulBodyLimitCheck.Ok -> systemPromptCountText(body)
                    is SoulBodyLimitCheck.OverLimitChinese -> stringResource(
                        R.string.system_prompt_over_limit_chinese,
                        c.chars,
                        c.cap,
                        SoulStore.ENGLISH_WORD_LIMIT,
                    )
                    is SoulBodyLimitCheck.OverLimitEnglish -> stringResource(
                        R.string.system_prompt_over_limit_english,
                        c.words,
                        c.cap,
                        SoulStore.CHINESE_CHAR_LIMIT,
                    )
                }
                Text(
                    text = indicatorText,
                    fontSize = 12.sp,
                    color = warnColor,
                )
            }
        }

        SettingsSection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MinisOutlinedButton(
                    onClick = { showRestoreDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.system_prompt_restore_default)) }
            }
        }
    }

    // Unsaved-changes guard. Dismissing by tapping outside keeps the edits —
    // the conservative reading of an ambiguous gesture.
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.system_prompt_discard_confirm_title)) },
            text = { Text(stringResource(R.string.system_prompt_discard_confirm_body)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text(stringResource(R.string.system_prompt_discard_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.system_prompt_discard_cancel))
                }
            },
        )
    }

    // [T-system-prompt-prose] Restore deletes the override file so the shipped
    // default prose resumes, then reloads the editor from DEFAULT_CONTENT.
    // Baseline is moved with it: disk no longer holds an override, so the
    // screen should read clean afterwards and leaving must not prompt.
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(R.string.system_prompt_restore_confirm_title)) },
            text = { Text(stringResource(R.string.system_prompt_restore_confirm_body)) },
            confirmButton = {
                MinisButton(onClick = {
                    showRestoreDialog = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                SystemPromptProseStore.reset(context)
                            }
                            body = SystemPromptProseStore.DEFAULT_CONTENT
                            baseline = SystemPromptProseStore.DEFAULT_CONTENT
                        } catch (t: Throwable) {
                            saveError = t.message ?: "reset failed"
                        }
                    }
                }) { Text(stringResource(R.string.system_prompt_restore_default)) }
            },
            dismissButton = {
                MinisOutlinedButton(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(R.string.system_prompt_cancel))
                }
            },
        )
    }

    saveError?.let { err ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.system_prompt_save_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { saveError = null }) {
                    Text(stringResource(R.string.system_prompt_ok))
                }
            },
        )
    }
}

/**
 * [T-system-prompt-prose] Live length counter. Picks the unit whose cap
 * applies under the same CJK-ratio rule [SoulStore.isOverLimit] uses, so
 * N / cap always reads against the limit that will actually reject the
 * text. Mirrors [soulBodyCountTextAndroid].
 */
@Composable
private fun systemPromptCountText(body: String): String {
    val trimmed = body.trim()
    var cjk = 0
    var total = 0
    var i = 0
    while (i < trimmed.length) {
        val cp = trimmed.codePointAt(i)
        total += 1
        val isCJK =
            cp in 0x4E00..0x9FFF ||
                cp in 0x3400..0x4DBF ||
                cp in 0x3040..0x309F ||
                cp in 0x30A0..0x30FF ||
                cp in 0xAC00..0xD7AF
        if (isCJK) cjk += 1
        i += Character.charCount(cp)
    }
    val ratio = if (total > 0) cjk.toDouble() / total else 0.0
    return if (ratio > SoulStore.CJK_RATIO_THRESHOLD) {
        val chars = trimmed.codePointCount(0, trimmed.length)
        stringResource(R.string.system_prompt_count_chars, chars, SoulStore.CHINESE_CHAR_LIMIT)
    } else {
        val words = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
        stringResource(R.string.system_prompt_count_words, words, SoulStore.ENGLISH_WORD_LIMIT)
    }
}
