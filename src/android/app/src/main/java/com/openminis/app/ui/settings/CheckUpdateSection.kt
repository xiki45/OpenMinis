package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Badge
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.ClickableText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.UpdateAutoChecker
import com.openminis.app.data.UpdateChecker
import com.openminis.app.data.UpdatePolicy
import com.openminis.app.data.UpdateStateStore
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.i18n.uppercaseForDisplay

/**
 * Settings section that talks to [UpdateChecker] to surface a "Check for
 * Updates" affordance. Drop in anywhere — typically the bottom of an About
 * screen — and it owns its own state, dialogs, and download UI.
 *
 * The section is no-op visible: a button + transient status text. When an
 * update is found we open a modal AlertDialog showing the changelog and a
 * Download button; the dialog stays open through the download so the user
 * can watch progress.
 */
@Composable
fun CheckUpdateSection() {
    // [T-update-channels] Both distributions ship self-update today; the flag
    // exists so a store build can drop it without touching this code.
    if (!BuildConfig.SELF_UPDATE) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { UpdateStateStore.init(context) }
    // One shared badge state; the store computes it locally from the cache.
    val hasBadge by UpdateStateStore.hasUpdateBadge.collectAsState()
    val channel by UpdateStateStore.channel.collectAsState()
    val autoEnabled by UpdateStateStore.autoEnabled.collectAsState()
    var showChannelPicker by remember { mutableStateOf(false) }
    var confirmBeta by remember { mutableStateOf(false) }

    var checking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // When the GitHub API returns 403 / 451 we surface a dedicated row with a
    // tappable "Open GitHub Releases" link beneath the row, so users behind a
    // geo-block know what to do without hunting for the URL themselves.
    var showReleasesLink by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateChecker.CheckResult.UpdateAvailable?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var awaitingInstallPerm by remember { mutableStateOf(false) }

    // Resume the install flow on every ON_RESUME. There are two cases:
    //
    //  1. Composable state survived — `update` and `awaitingInstallPerm` are
    //     still set. We just need to flip awaitingInstallPerm off (so the
    //     dialog stops showing the "permission required" message) and, if a
    //     persisted APK is intact, fire the installer directly.
    //
    //  2. Activity recreate happened — every `remember{}` slot above is back
    //     to its default. The only thing that knows we were mid-flow is
    //     PendingUpdateStore. We rehydrate by calling resumablePendingFile()
    //     and, when permission is granted, fire the installer. We do NOT
    //     re-open the update dialog in this case because there's no
    //     CheckResult to populate it; the install intent is enough.
    //
    // Either way: if permission is still denied we leave the pending record
    // alone so the next resume can pick it up.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (!UpdateChecker.canInstall(context)) return@LifecycleEventObserver
            awaitingInstallPerm = false
            val pendingFile = UpdateChecker.resumablePendingFile(context) ?: return@LifecycleEventObserver
            val launched = UpdateChecker.installApk(context, pendingFile)
            if (launched) {
                // Dismiss any leftover dialog state; the system installer is
                // now in charge.
                update = null
                downloadProgress = null
                downloadError = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    SettingsSection(
        header = stringResource(R.string.check_update_section_header),
        footer = stringResource(
            R.string.check_update_current_version,
            UpdatePolicy.displayVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        ),
    ) {
        SettingsRow(
            icon = Icons.Outlined.SystemUpdate,
            iconColor = Color(0xFF007AFF),
            title = stringResource(
                if (checking) R.string.check_update_checking
                else R.string.check_update_check_button
            ),
            subtitle = statusMessage,
            // [T-update-channels] The About page's own badge layer.
            trailing = if (hasBadge && !checking) ({ UpdateBadgeDot() }) else null,
            onClick = if (checking) null else {
                {
                    checking = true
                    statusMessage = null
                    showReleasesLink = false
                    scope.launch {
                        // Manual: bypasses the throttle, writes the same cache.
                        when (val r = UpdateChecker.check(context)) {
                            is UpdateChecker.CheckResult.UpdateAvailable -> {
                                update = r
                                statusMessage = null
                            }
                            UpdateChecker.CheckResult.UpToDate ->
                                statusMessage = context.getString(R.string.check_update_up_to_date)
                            UpdateChecker.CheckResult.NoReleaseAvailable ->
                                statusMessage = context.getString(R.string.check_update_no_release)
                            is UpdateChecker.CheckResult.NoApkAsset ->
                                statusMessage = context.getString(R.string.check_update_no_apk_asset, r.tagName)
                            UpdateChecker.CheckResult.Forbidden -> {
                                statusMessage = context.getString(R.string.update_error_forbidden_with_link)
                                showReleasesLink = true
                            }
                            UpdateChecker.CheckResult.NetworkUnreachable ->
                                statusMessage = context.getString(R.string.update_error_network_unreachable)
                            is UpdateChecker.CheckResult.Error ->
                                statusMessage = context.getString(R.string.check_update_error, r.message)
                        }
                        checking = false
                    }
                }
            },
        )
        // [T-update-channels] Channel: stable (default) or beta (asks first).
        SettingsRow(
            icon = Icons.Outlined.Science,
            iconColor = Color(0xFFFF9500),
            title = stringResource(R.string.update_channel_title),
            subtitle = stringResource(
                if (channel == UpdatePolicy.Channel.BETA) R.string.update_channel_beta else R.string.update_channel_stable,
            ),
            showDivider = BuildConfig.AUTO_UPDATE_CHECK,
            onClick = { showChannelPicker = true },
        )
        // Automatic checks: a github-build setting; a store build never checks
        // on its own, so it has nothing to switch.
        if (BuildConfig.AUTO_UPDATE_CHECK) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Autorenew,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.update_auto_check_title),
                checked = autoEnabled,
                onCheckedChange = { on ->
                    UpdateStateStore.setAutoEnabled(context, on)
                    // Off -> on: check now if the cache has expired.
                    if (on) UpdateAutoChecker.maybeCheck(context, "switch-on")
                },
                showDivider = false,
            )
        }
        if (showReleasesLink) {
            val linkLabel = stringResource(R.string.update_error_open_releases)
            val annotated = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                ) {
                    append(linkLabel)
                }
                addStringAnnotation(
                    tag = "URL",
                    annotation = UpdateChecker.RELEASES_URL,
                    start = 0,
                    end = length,
                )
            }
            ClickableText(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                onClick = { offset ->
                    annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()
                        ?.let { ann ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(ann.item))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                },
            )
        }
    }

    if (showChannelPicker) {
        ChannelPickerDialog(
            current = channel,
            onPick = { picked ->
                showChannelPicker = false
                if (picked == UpdatePolicy.Channel.BETA && channel != UpdatePolicy.Channel.BETA) {
                    confirmBeta = true
                } else {
                    UpdateStateStore.setChannel(context, picked)
                    UpdateAutoChecker.maybeCheck(context, "channel")
                }
            },
            onDismiss = { showChannelPicker = false },
        )
    }
    if (confirmBeta) {
        AlertDialog(
            onDismissRequest = { confirmBeta = false },
            title = { Text(stringResource(R.string.update_channel_beta_warning_title)) },
            text = { Text(stringResource(R.string.update_channel_beta_warning_body)) },
            confirmButton = {
                MinisButton(onClick = {
                    confirmBeta = false
                    // Clears the cache; the check below then runs at once.
                    UpdateStateStore.setChannel(context, UpdatePolicy.Channel.BETA)
                    UpdateAutoChecker.maybeCheck(context, "channel")
                }) { Text(stringResource(R.string.update_channel_beta_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { confirmBeta = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    update?.let { u ->
        UpdateDialog(
            update = u,
            downloadProgress = downloadProgress,
            downloadError = downloadError,
            needsInstallPerm = awaitingInstallPerm,
            onSkip = {
                // "Skip this version": no badge for this build again.
                UpdateStateStore.skip(context, u.versionCode)
                update = null
            },
            onOpenRelease = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(u.releaseUrl.ifBlank { UpdateChecker.RELEASES_URL }))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                update = null
            },
            onDownload = {
                downloadError = null
                downloadProgress = 0f
                // A download clears the badge for this build; "Later" does not.
                UpdateStateStore.markDownloaded(context, u.versionCode)
                scope.launch {
                    val result = UpdateChecker.download(
                        context = context,
                        url = u.apkUrl,
                        versionName = u.versionName,
                        versionCode = u.versionCode,
                        expectedSha256 = u.sha256,
                    ) { p -> downloadProgress = p }
                    when (result) {
                        is UpdateChecker.DownloadResult.Success -> {
                            downloadProgress = null
                            // [T-update-channels] The archive itself must be this
                            // app and a newer build, whatever its name said.
                            if (UpdateChecker.archiveRefusal(context, result.file) != null) {
                                downloadError = context.getString(R.string.check_update_package_invalid)
                                result.file.delete()
                                com.openminis.app.data.PendingUpdateStore.clearPending(context)
                            } else if (UpdateChecker.canInstall(context)) {
                                val ok = UpdateChecker.installApk(context, result.file)
                                if (ok) {
                                    update = null
                                } else {
                                    downloadError = context.getString(R.string.check_update_install_launch_failed)
                                }
                            } else {
                                awaitingInstallPerm = true
                            }
                        }
                        is UpdateChecker.DownloadResult.Error -> {
                            downloadProgress = null
                            downloadError = result.message
                        }
                    }
                }
            },
            onOpenSettings = { UpdateChecker.openInstallPermissionSettings(context) },
            onDismiss = {
                if (downloadProgress == null) {
                    update = null
                    downloadError = null
                    awaitingInstallPerm = false
                }
            },
        )
    }
}

@Composable
private fun UpdateDialog(
    update: UpdateChecker.CheckResult.UpdateAvailable,
    downloadProgress: Float?,
    downloadError: String?,
    needsInstallPerm: Boolean,
    onSkip: () -> Unit,
    onOpenRelease: () -> Unit,
    onDownload: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.check_update_available_title))
                Text(
                    // [T-update-channels] "1.15 (build 29) → 1.16 (build 32)"
                    stringResource(
                        R.string.check_update_available_subtitle,
                        UpdatePolicy.displayVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        UpdatePolicy.displayVersion(update.versionName, update.versionCode),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (update.changelog.isNotBlank()) {
                    Text(
                        stringResource(R.string.check_update_changelog_header).uppercaseForDisplay(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = update.changelog,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                    )
                }
                Text(
                    stringResource(R.string.check_update_install_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.check_update_downloading, (downloadProgress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (downloadError != null) {
                    Text(
                        stringResource(R.string.check_update_download_failed, downloadError),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (needsInstallPerm) {
                    Text(
                        stringResource(R.string.check_update_install_perm_required),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        // [T-dialog-buttons] All three actions in ONE row we lay out ourselves:
        // M3's button area is a flow row that wrapped "Download & Install" onto
        // its own line on a Pixel 4a (~276dp of buttons in ~274dp). Skip and
        // Later on the left, the filled primary on the right; when a language
        // is too long for one row it wraps with the primary last, bottom-right,
        // never truncated (UpdateDialogButtonRow).
        confirmButton = {
            val compactText = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            UpdateDialogButtonRow(
                skip = {
                    MinisTextButton(onClick = onSkip, enabled = downloadProgress == null, contentPadding = compactText) {
                        Text(stringResource(R.string.check_update_skip_version), textAlign = TextAlign.Center)
                    }
                },
                // "Later" keeps the badge.
                later = {
                    MinisTextButton(onClick = onDismiss, enabled = downloadProgress == null, contentPadding = compactText) {
                        Text(stringResource(R.string.check_update_later), textAlign = TextAlign.Center)
                    }
                },
                primary = {
                    val compact = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    if (needsInstallPerm) {
                        MinisButton(onClick = onOpenSettings, contentPadding = compact) {
                            Text(stringResource(R.string.check_update_open_install_settings), textAlign = TextAlign.Center)
                        }
                    } else if (!BuildConfig.IN_APP_INSTALL) {
                        // [T-update-channels] A build that may not install APKs itself
                        // (a future Play build) only points at the release.
                        MinisButton(onClick = onOpenRelease, contentPadding = compact) {
                            Text(stringResource(R.string.check_update_open_release), textAlign = TextAlign.Center)
                        }
                    } else {
                        MinisButton(
                            onClick = onDownload,
                            enabled = downloadProgress == null,
                            contentPadding = compact,
                        ) {
                            if (downloadProgress != null && downloadProgress < 1f) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 1.5.dp,
                                    )
                                    Text(stringResource(R.string.check_update_downloading, (downloadProgress * 100).toInt()))
                                }
                            } else {
                                Text(stringResource(R.string.check_update_download_button), textAlign = TextAlign.Center)
                            }
                        }
                    }
                },
            )
        },
        dismissButton = null,
    )
}

/** [T-update-channels] The small, numberless update badge every layer shows. */
@Composable
internal fun UpdateBadgeDot() {
    Badge()
}

@Composable
private fun ChannelPickerDialog(
    current: UpdatePolicy.Channel,
    onPick: (UpdatePolicy.Channel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_channel_title)) },
        text = {
            Column {
                for (ch in UpdatePolicy.Channel.entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(ch) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ch == current, onClick = { onPick(ch) })
                        Column {
                            Text(
                                stringResource(
                                    if (ch == UpdatePolicy.Channel.BETA) R.string.update_channel_beta else R.string.update_channel_stable,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(
                                    if (ch == UpdatePolicy.Channel.BETA) R.string.update_channel_beta_desc
                                    else R.string.update_channel_stable_desc,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * [T-dialog-buttons] The update dialog's action row: [skip] and [later] on the
 * left, [primary] on the right, all in one row when they fit the dialog's
 * width. Otherwise it wraps, end-aligned like M3's own button area: the two
 * text buttons on a line above (each on its own line if even they don't fit
 * side by side) and [primary] always last, bottom-right. Every child is
 * measured with the full width as its limit, so a long label wraps inside
 * its button instead of being cut off or pushed out of the dialog.
 */
@Composable
internal fun UpdateDialogButtonRow(
    skip: @Composable () -> Unit,
    later: @Composable () -> Unit,
    primary: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = { skip(); later(); primary() },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val lineGap = 4.dp.roundToPx()
        val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val plan = UpdateDialogButtonPlan.of(natural, width, gap)
        val placeables = measurables.mapIndexed { i, m ->
            m.measure(Constraints(minWidth = 0, maxWidth = minOf(natural[i], width).coerceAtLeast(0)))
        }
        val lines = plan.lines
        val lineHeights = lines.map { line -> line.maxOf { placeables[it].height } }
        val height = lineHeights.sum() + lineGap * (lines.size - 1)
        layout(width, height) {
            var y = 0
            lines.forEachIndexed { li, line ->
                val h = lineHeights[li]
                if (plan.singleRow) {
                    // Secondaries from the start, primary at the end.
                    var x = 0
                    for (i in line.dropLast(1)) {
                        val p = placeables[i]
                        p.placeRelative(x, y + (h - p.height) / 2)
                        x += p.width + gap
                    }
                    val p = placeables[line.last()]
                    p.placeRelative(width - p.width, y + (h - p.height) / 2)
                } else {
                    // End-aligned, in order.
                    val total = line.sumOf { placeables[it].width } + gap * (line.size - 1)
                    var x = width - total
                    for (i in line) {
                        val p = placeables[i]
                        p.placeRelative(x, y + (h - p.height) / 2)
                        x += p.width + gap
                    }
                }
                y += h + lineGap
            }
        }
    }
}

/**
 * [T-dialog-buttons] Pure line breaking of the update dialog's buttons, so the
 * rule is testable on the JVM. [natural] = intrinsic widths of skip, later,
 * primary; indices into it per line.
 */
internal data class UpdateDialogButtonPlan(val singleRow: Boolean, val lines: List<List<Int>>) {
    companion object {
        fun of(natural: List<Int>, width: Int, gap: Int): UpdateDialogButtonPlan {
            val (skip, later, primary) = natural
            if (skip + later + primary + 2 * gap <= width) return UpdateDialogButtonPlan(true, listOf(listOf(0, 1, 2)))
            val secondaries = if (skip + later + gap <= width) listOf(listOf(0, 1)) else listOf(listOf(0), listOf(1))
            return UpdateDialogButtonPlan(false, secondaries + listOf(listOf(2)))
        }
    }
}
