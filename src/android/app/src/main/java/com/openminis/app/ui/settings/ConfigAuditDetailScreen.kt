package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.config.ConfigBridge
import com.openminis.app.config.audit.ConfigAuditActor
import com.openminis.app.config.audit.ConfigAuditEntry
import com.openminis.app.config.audit.ConfigAuditLog
import com.openminis.app.config.audit.ConfigAuditStatus
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * [T-config-readable] Logs → Config Changes → one record. Port of iOS
 * ConfigAuditDetailView (70f7871d7).
 *
 * Shows what the change meant in words (the readable description stored with
 * the row when it was requested), then the raw setting path and full old / new
 * JSON. Rows written before readable descriptions existed have no
 * description; they say so and show the raw change only — names are NOT
 * re-derived from today's data, which could have changed since. The
 * description and the raw change are separate sections with their own
 * headers, so no hairline is drawn inside a section here (iOS dec7c238a).
 */
@Composable
fun ConfigAuditDetailScreen(entryId: String, onBack: () -> Unit) {
    val log = remember { ConfigAuditLog.get() }
    val revision by log.revision.collectAsState()
    var entry by remember { mutableStateOf<ConfigAuditEntry?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(entryId, revision) {
        entry = withContext(Dispatchers.IO) { log.get(entryId) }
        loaded = true
    }
    LaunchedEffect(loaded, entry) { if (loaded && entry == null) onBack() }
    val localize = rememberReadableLocalizer()
    val scope = rememberCoroutineScope()
    var confirmRevert by remember { mutableStateOf(false) }
    var reverting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }

    SettingsScaffold(title = stringResource(R.string.config_audit_detail_title), onBack = onBack) {
        // A record that no longer exists (pruned past the 1000-row cap while
        // this page was open) closes the page rather than showing an empty one.
        val e = entry ?: return@SettingsScaffold

        SettingsSection {
            SettingsRow(
                title = stringResource(R.string.config_audit_detail_status),
                trailing = { ConfigAuditStatusBadge(e.status) },
            )
            SettingsValueRow(title = stringResource(R.string.config_audit_detail_changed_by), value = detailActorLabel(e.actor))
            SettingsValueRow(
                title = stringResource(R.string.config_audit_detail_requested),
                value = formatDetailTime(e.at),
                showDivider = e.confirmedAt != null,
            )
            e.confirmedAt?.let {
                SettingsValueRow(
                    title = stringResource(R.string.config_audit_detail_resolved),
                    value = formatDetailTime(it),
                    showDivider = false,
                )
            }
        }

        if (!e.caption.isNullOrEmpty()) {
            SettingsSection(header = stringResource(R.string.config_audit_detail_note)) {
                SettingsCardBlock {
                    SelectionContainer { Text(e.caption, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }

        SettingsSection(header = stringResource(R.string.config_audit_detail_what_changed)) {
            SettingsCardBlock {
                val readable = e.readable
                if (readable != null) {
                    SelectionContainer {
                        Text(readable.displayText(localize), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.config_audit_detail_predates),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SettingsSection(header = stringResource(R.string.config_audit_detail_raw)) {
            SettingsCardBlock {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RawBlock(stringResource(R.string.config_audit_detail_setting), e.key)
                    RawBlock(stringResource(R.string.config_audit_detail_before), prettyConfigJSON(e.oldValueJSON))
                    RawBlock(stringResource(R.string.config_audit_detail_after), prettyConfigJSON(e.newValueJSON))
                }
            }
        }

        when (e.status) {
            ConfigAuditStatus.APPLIED -> SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.config_audit_detail_revert),
                    icon = Icons.AutoMirrored.Filled.Undo,
                    iconColor = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { if (!reverting) confirmRevert = true },
                    showChevron = false,
                    showDivider = false,
                )
            }
            ConfigAuditStatus.REVERTED -> SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.config_audit_detail_reverted),
                    icon = Icons.AutoMirrored.Filled.Undo,
                    iconColor = Color(0xFF8E5CD9),
                    titleColor = Color(0xFF8E5CD9),
                    showDivider = false,
                )
            }
            else -> Unit
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }

    val e = entry
    if (confirmRevert && e != null) {
        val successTitle = stringResource(R.string.logs_config_revert_success_title)
        val successBody = stringResource(R.string.logs_config_revert_success_body)
        val failedTitle = stringResource(R.string.logs_config_revert_failed_title)
        val unknownErr = stringResource(R.string.logs_config_revert_unknown_error)
        AlertDialog(
            onDismissRequest = { confirmRevert = false },
            title = { Text(stringResource(R.string.logs_config_revert_dialog_title)) },
            text = { Text(configRevertMessage(e, localize)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    confirmRevert = false
                    reverting = true
                    scope.launch {
                        // The Logs screen cannot stack the approval dialog, so
                        // this dialog is the confirmation (same as the list).
                        val res = withContext(Dispatchers.IO) {
                            ConfigBridge.auditRevert(
                                id = e.id,
                                actorRaw = "user-revert",
                                sessionId = null,
                                skipConfirmation = true,
                            )
                        }
                        reverting = false
                        result = if (res.optBoolean("ok", false)) {
                            successTitle to successBody
                        } else {
                            failedTitle to res.optString("reason", unknownErr)
                        }
                    }
                }) {
                    Text(stringResource(R.string.logs_config_revert), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { confirmRevert = false }) {
                    Text(stringResource(R.string.logs_config_dialog_cancel))
                }
            },
        )
    }

    result?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                MinisTextButton(onClick = { result = null }) {
                    Text(stringResource(R.string.logs_config_dialog_ok))
                }
            },
        )
    }
}

@Composable
private fun RawBlock(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

/** Who made the change, in words (iOS ConfigAuditDetailView.actorLabel). */
@Composable
private fun detailActorLabel(actor: ConfigAuditActor): String = when (actor) {
    ConfigAuditActor.AGENT -> stringResource(R.string.config_audit_actor_assistant)
    ConfigAuditActor.USER -> stringResource(R.string.config_audit_actor_you)
    ConfigAuditActor.AGENT_REVERT -> stringResource(R.string.config_audit_actor_assistant_revert)
    ConfigAuditActor.USER_REVERT -> stringResource(R.string.config_audit_actor_you_revert)
}

private fun formatDetailTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(epochMs))
