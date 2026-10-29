package com.openminis.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.i18n.uppercaseForDisplay
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitch

private const val MCP_NOTE_TRUNC = 200

/**
 * Bottom sheet showing all configured MCP servers with per-session
 * enable/disable toggles. Mirrors [SessionSkillsSheet]: [StandardChatSheet]
 * shell + shared [SettingsSection] / [SettingsRow] primitives. Each row shows
 * the server name + truncated note, with a Switch writing a session override
 * via [MCPRepository.setSessionOverride].
 *
 * [T-android-session-mcp-detail] Like the Skills sheet (and iOS
 * SessionMCPsView), the list has a search field (name + note) and Enable All /
 * Disable All acting on the filtered rows. Tapping a row opens a read-only
 * detail page inside the sheet: transport, URL or command + args, startup
 * timeout, OAuth state, the note, and the NAMES of headers and env vars. Their
 * values are never shown: they routinely hold API keys, and this sheet sits
 * over a chat that may be screen-shared. Long-pressing the URL copies it.
 * The list's state is held above the page switch, so it is unchanged on
 * return; system back pops the detail page via [StandardChatSheet]'s onBack.
 */
@Composable
fun SessionMcpsSheet(
    sessionId: String,
    mcpRepository: MCPRepository,
    onDismiss: () -> Unit,
) {
    val servers by mcpRepository.servers.collectAsState()

    // [T-mcp-review-fixes-android] Key the seed on `servers` so the override map
    // re-derives when the live server list arrives. A keyless remember{} seeds
    // once from a possibly-empty first-composition list and then never re-runs,
    // leaving every toggle stuck at its default (the review's stale-override race).
    val overrides = remember(servers) {
        mutableStateMapOf<String, Boolean>().apply {
            for (server in servers) {
                put(server.id, mcpRepository.isEnabledForSession(server.id, sessionId))
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredServers by remember(servers) {
        derivedStateOf { filterMcpServers(servers, searchQuery) }
    }

    // Held here, above the page switch, so returning from a detail page finds
    // the list exactly as it was left.
    val listScroll = rememberScrollState()
    var detailId by remember { mutableStateOf<String?>(null) }

    // A server removed while its page is open: fall back to the list.
    val detailServer = detailId?.let { id -> servers.find { it.id == id } }
    val detailGone = detailId != null && detailServer == null
    LaunchedEffect(detailGone) {
        if (detailGone) detailId = null
    }

    fun setAll(enabled: Boolean) {
        for (server in filteredServers) {
            overrides[server.id] = enabled
            mcpRepository.setSessionOverride(sessionId, server.id, enabled)
        }
    }

    StandardChatSheet(
        title = detailServer?.id ?: stringResource(R.string.session_mcps_title),
        onDismiss = onDismiss,
        // System back closes the detail page; on the list it closes the sheet.
        onBack = {
            if (detailId == null) false else {
                detailId = null
                true
            }
        },
        leadingAction = if (detailServer == null) null else {
            {
                IconButton(onClick = { detailId = null }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
            }
        },
    ) {
        if (detailServer != null) {
            SessionMcpDetail(detailServer)
            return@StandardChatSheet
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listScroll),
        ) {
            if (servers.isEmpty()) {
                EmptyMcpsCard()
            } else {
                DialogTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.mcps_search_placeholder),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                )
                // Same header row as the Skills sheet: section label on the
                // left, Enable All / Disable All (on the filtered rows) on the
                // right.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .padding(start = 32.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.mcp_section_servers).uppercaseForDisplay(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    MinisTextButton(
                        onClick = { setAll(true) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.session_skills_enable_all), fontSize = 12.sp)
                    }
                    MinisTextButton(
                        onClick = { setAll(false) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.session_skills_disable_all), fontSize = 12.sp)
                    }
                }

                if (filteredServers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mcps_search_no_match, searchQuery),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                    )
                } else {
                    SettingsSection(
                        footer = stringResource(R.string.session_mcps_footer),
                    ) {
                        filteredServers.forEachIndexed { index, server ->
                            val note = server.note?.trim().orEmpty()
                            val sub = note
                                .ifEmpty { server.transportSummary }
                                .let { if (it.length > MCP_NOTE_TRUNC) it.substring(0, MCP_NOTE_TRUNC) + "…" else it }
                            SettingsRow(
                                title = server.id,
                                subtitle = sub.takeIf { it.isNotBlank() },
                                onClick = { detailId = server.id },
                                showDivider = index < filteredServers.size - 1,
                                trailing = {
                                    SettingsSwitch(
                                        checked = overrides[server.id] ?: server.enabled,
                                        onCheckedChange = { enabled ->
                                            overrides[server.id] = enabled
                                            mcpRepository.setSessionOverride(sessionId, server.id, enabled)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

/** Servers whose name or note contains [query] (trimmed, case-insensitive), like iOS. */
internal fun filterMcpServers(
    servers: List<MCPRepository.MCPServerConfig>,
    query: String,
): List<MCPRepository.MCPServerConfig> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return servers
    return servers.filter { it.id.lowercase().contains(q) || it.note.orEmpty().lowercase().contains(q) }
}

/**
 * Header / env names for the detail page, sorted. Only the names: the values
 * are never handed to the UI.
 */
internal fun mcpSecretNames(map: Map<String, String>): List<String> = map.keys.sorted()

/** Read-only detail of one server. Nothing here writes to the config. */
@Composable
private fun SessionMcpDetail(server: MCPRepository.MCPServerConfig) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val authorized = remember(server.id, server.oauth) {
        server.oauth != null && com.openminis.app.mcp.oauth.MCPOAuthStore.isAuthorized(context, server.id)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsSection {
            McpDetailRow(
                label = stringResource(R.string.session_mcp_detail_transport),
                value = if (server.isStdio) {
                    stringResource(R.string.mcp_transport_stdio)
                } else {
                    stringResource(R.string.mcp_transport_http) + " / " + stringResource(R.string.mcp_transport_sse)
                },
            )
            if (server.isStdio) {
                McpDetailDivider()
                McpDetailRow(stringResource(R.string.mcp_form_command), server.command.orEmpty(), monospace = true)
                if (server.args.isNotEmpty()) {
                    McpDetailDivider()
                    McpDetailRow(stringResource(R.string.session_mcp_detail_args), server.args.joinToString(" "), monospace = true)
                }
                McpDetailDivider()
                McpDetailRow(
                    stringResource(R.string.mcp_form_startup_timeout),
                    server.startupTimeoutSeconds?.toString() ?: stringResource(R.string.mcp_form_startup_timeout_placeholder),
                )
            } else {
                val url = server.url.orEmpty()
                McpDetailDivider()
                // Long-press copies the URL, like a skill's Source row.
                McpDetailRow(
                    label = stringResource(R.string.mcp_form_url),
                    value = url,
                    monospace = true,
                    onLongClick = url.takeIf { it.isNotBlank() }?.let {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            copyMcpUrl(context, it)
                        }
                    },
                )
                if (server.oauth != null) {
                    McpDetailDivider()
                    McpDetailRow(
                        "OAuth",
                        stringResource(if (authorized) R.string.mcp_form_oauth_authorized else R.string.session_mcp_oauth_not_authorized),
                    )
                }
            }
        }

        val note = server.note?.trim().orEmpty()
        if (note.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.session_mcp_detail_note)) {
                McpDetailText(note)
            }
        }

        val headerNames = mcpSecretNames(server.headers)
        val envNames = mcpSecretNames(server.env)
        if (headerNames.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.session_mcp_detail_headers)) {
                McpDetailText(headerNames.joinToString("\n"), monospace = true)
            }
        }
        if (envNames.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.session_mcp_detail_env)) {
                McpDetailText(envNames.joinToString("\n"), monospace = true)
            }
        }
        if (headerNames.isNotEmpty() || envNames.isNotEmpty()) {
            Text(
                text = stringResource(R.string.session_mcp_detail_values_hidden),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
                lineHeight = 16.sp,
            )
        }
        Spacer(modifier = Modifier.padding(bottom = 24.dp))
    }
}

/** Label above a full-width value; selectable unless the row takes a long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun McpDetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        val text: @Composable () -> Unit = {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.let {
                    if (monospace) it.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp) else it
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // A SelectionContainer would take the long-press for itself.
        if (onLongClick != null) text() else SelectionContainer { text() }
    }
}

@Composable
private fun McpDetailText(text: String, monospace: Boolean = false) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.let {
                if (monospace) it.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp) else it
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun McpDetailDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

private fun copyMcpUrl(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
    // Android 13+ shows its own clipboard chip; the Toast covers < 13.
    Toast.makeText(context, context.getString(R.string.session_mcp_url_copied), Toast.LENGTH_SHORT).show()
}

@Composable
private fun EmptyMcpsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Extension,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Text(
                stringResource(R.string.session_mcps_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.session_mcps_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
