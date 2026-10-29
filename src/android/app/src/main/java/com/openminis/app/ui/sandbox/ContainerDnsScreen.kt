package com.openminis.app.ui.sandbox

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.components.SettingsSection
import com.openminis.app.ui.settings.SettingsScaffold

/**
 * [FEAT-261021-04, iOS c77be4fb6] Settings > Network > Shell Container DNS:
 * the Container Network section of the Rootfs page on a page of its own, so
 * the sandbox's DNS sits with the other network settings. One copy of the
 * DNS logic ([ContainerNetworkSection]); the Rootfs page keeps its section.
 */
@Composable
fun ContainerDnsScreen(onBack: () -> Unit) {
    SettingsScaffold(title = stringResource(R.string.network_container_dns), onBack = onBack) {
        SettingsSection(title = stringResource(R.string.rootfs_network_section)) {
            ContainerNetworkSection()
        }
    }
}
