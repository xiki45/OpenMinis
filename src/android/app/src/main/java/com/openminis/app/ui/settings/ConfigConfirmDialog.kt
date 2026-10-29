package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.confirm.ConfigConfirmationGate
import com.openminis.app.config.confirm.PendingConfigChange
import com.openminis.app.config.confirm.PendingConfigChangeItem
import com.openminis.app.ui.components.MinisTextButton

/**
 * Root-level confirmation dialog for every minis-config write.
 * Mirrors iOS `ConfigConfirmSheet`. Mounted in [com.openminis.app.MainActivity]
 * so it shows over any active screen. Bound to
 * [ConfigConfirmationGate.pending].
 *
 * Per-row toggles let the user reject individual items in a `set-batch`;
 * dismissing without buttons is disabled to force an explicit decision.
 */
@Composable
fun ConfigConfirmDialogHost() {
    val pending by ConfigConfirmationGate.pending.collectAsState()
    val change = pending ?: return
    ConfigConfirmDialog(change)
}

@Composable
private fun ConfigConfirmDialog(change: PendingConfigChange) {
    // Local copy so per-row toggles update immediately even though the
    // underlying [PendingConfigChange] is immutable.
    val workingItems = remember(change.id) {
        mutableStateListOf<PendingConfigChangeItem>().also { it.addAll(change.items) }
    }
    LaunchedEffect(change.id) {
        workingItems.clear()
        workingItems.addAll(change.items)
    }

    val approvedCount = workingItems.count { it.isApproved }
    val localize = rememberReadableLocalizer()
    // [T-config-readable] Batch summary: the readable sentences of the rows
    // still switched on, grouped by the record they touch. Updates live as
    // rows are toggled.
    val summaryLines = if (workingItems.size > 1) configSummaryLines(workingItems, localize) else emptyList()

    AlertDialog(
        onDismissRequest = { /* force explicit Apply / Cancel */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            // [T-config-confirm-i18n] iOS ConfigConfirmSheet keys "Confirm %lld
            // changes" / "Confirm change"; no plural variants there either.
            Text(
                if (workingItems.size > 1) {
                    stringResource(R.string.config_confirm_title_many, workingItems.size)
                } else {
                    stringResource(R.string.config_confirm_title_one)
                },
            )
        },
        text = {
            Column {
                if (!change.caption.isNullOrEmpty()) {
                    Text(
                        text = change.caption,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(workingItems, key = { it.id }) { item ->
                        ConfirmRow(
                            item = item,
                            localize = localize,
                            onToggle = { newValue ->
                                val idx = workingItems.indexOfFirst { it.id == item.id }
                                if (idx >= 0) {
                                    workingItems[idx] = workingItems[idx].copy(isApproved = newValue)
                                }
                            },
                        )
                    }
                    if (summaryLines.isNotEmpty()) {
                        item(key = "readable-summary") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.config_audit_summary_header),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                summaryLines.forEach { line ->
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(8.dp))
                                        Text(text = line, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val applyText = stringResource(
                if (approvedCount == 0) R.string.config_confirm_reject_all else R.string.config_confirm_apply,
            )
            MinisTextButton(
                onClick = { ConfigConfirmationGate.userApprove(workingItems.toList()) },
                enabled = workingItems.isNotEmpty(),
            ) {
                Text(applyText)
            }
        },
        dismissButton = {
            MinisTextButton(onClick = { ConfigConfirmationGate.userReject() }) {
                Text(stringResource(R.string.config_confirm_cancel))
            }
        },
    )
}

@Composable
private fun ConfirmRow(
    item: PendingConfigChangeItem,
    localize: (String) -> String?,
    onToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp)
            // A switched-off row fades, as on iOS (opacity 0.45).
            .alpha(if (item.isApproved) 1f else 0.45f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VerbBadge(item.verb)
            Spacer(Modifier.width(6.dp))
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            SettingsSwitch(
                checked = item.isApproved,
                onCheckedChange = onToggle,
            )
        }
        Text(
            text = item.path,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Top) {
            if (item.oldDisplay.isNotEmpty()) {
                Text(
                    text = item.oldDisplay,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            if (item.newDisplay.isNotEmpty()) {
                Text(
                    text = item.newDisplay,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = riskColor(item.risk),
                    maxLines = 2,
                )
            }
        }
        // [T-config-readable] What the raw values above mean, in words, set
        // apart from them by a hairline. Both appear only together, so a row
        // without readable text never shows a stray line.
        val readable = item.readable?.displayText(localize).orEmpty()
        if (readable.isNotEmpty()) {
            ConfigReadableDivider()
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 2.dp)) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp).height(14.dp).width(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = readable,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        when (item.risk) {
            ConfigRisk.DESTRUCTIVE -> RiskHint(
                text = stringResource(R.string.config_confirm_risk_destructive),
                color = MaterialTheme.colorScheme.error,
                icon = Icons.Filled.Warning,
            )
            // iOS: exclamationmark.circle for a sensitive change.
            ConfigRisk.SENSITIVE -> RiskHint(
                text = stringResource(R.string.config_confirm_risk_sensitive),
                color = Color(0xFFE65100),
                icon = Icons.Outlined.ErrorOutline,
            )
            else -> Unit
        }
    }
}

@Composable
private fun VerbBadge(verb: String) {
    val color = when (verb.lowercase()) {
        "remove", "delete" -> MaterialTheme.colorScheme.error
        "add", "create" -> Color(0xFF388E3C)
        "hide" -> MaterialTheme.colorScheme.outline
        "show" -> Color(0xFF1976D2)
        "revert" -> Color(0xFF8E5CD9)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(8.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = verb.uppercase(),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = Color.White,
        )
    }
}

@Composable
private fun RiskHint(text: String, color: Color, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.height(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun riskColor(risk: ConfigRisk): Color = when (risk) {
    ConfigRisk.DESTRUCTIVE -> MaterialTheme.colorScheme.error
    ConfigRisk.SENSITIVE -> Color(0xFFE65100)
    ConfigRisk.NORMAL -> MaterialTheme.colorScheme.onSurface
}
