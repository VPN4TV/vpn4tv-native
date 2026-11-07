package com.vpn4tv.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.constant.ServiceMode
import com.vpn4tv.app.database.Settings

@Composable
fun SettingsScreen(
    onBack: (() -> Unit)?,
    onPerAppProxy: () -> Unit = {},
    onLanImport: () -> Unit = {},
    onManualImport: () -> Unit = {},
    onLogs: () -> Unit = {},
    onAbout: () -> Unit = {},
) {
    var autoConnect by remember { mutableStateOf(Settings.autoConnectOnBoot) }
    var proxyMode by remember { mutableStateOf(Settings.isProxyMode) }
    var bypassLan by remember { mutableStateOf(Settings.bypassLan) }
    var fakeDns by remember { mutableStateOf(Settings.fakeDns) }
    var allThroughVpn by remember { mutableStateOf(Settings.allThroughVpn) }

    ScreenScaffold(title = stringResource(R.string.title_settings), onBack = onBack) { padding ->
        // verticalScroll: the list is longer than one screen; on short-height
        // TVs (Mi Box) Compose follows D-pad focus into view.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(vertical = if (Device.isTv) 8.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (Device.isTv) 8.dp else 0.dp),
        ) {
            SettingsLink(
                title = stringResource(R.string.title_per_app_proxy),
                subtitle = if (Settings.perAppProxyEnabled)
                    "${if (Settings.perAppProxyMode == Settings.PER_APP_PROXY_EXCLUDE) stringResource(R.string.mode_exclude) else stringResource(R.string.mode_include)}: ${Settings.perAppProxyList.size}"
                else stringResource(R.string.setting_disabled),
                onClick = onPerAppProxy,
            )

            SettingsToggle(
                title = stringResource(R.string.setting_auto_connect),
                subtitle = stringResource(R.string.setting_auto_connect_desc),
                checked = autoConnect,
                onCheckedChange = { autoConnect = it; Settings.autoConnectOnBoot = it },
            )

            // FakeDNS — DNS-blocking bypass. Takes effect on next connect
            // (config is regenerated at every service start).
            SettingsToggle(
                title = stringResource(R.string.setting_fake_dns),
                subtitle = stringResource(R.string.setting_fake_dns_desc),
                checked = fakeDns,
                onCheckedChange = { fakeDns = it; Settings.fakeDns = it },
            )

            // "Everything through VPN" — off: Russian sites and services go
            // direct ("Russia direct"). Applies at once, through a reload of
            // the running service.
            SettingsToggle(
                title = stringResource(R.string.setting_all_through_vpn),
                subtitle = stringResource(R.string.setting_all_through_vpn_desc),
                checked = allThroughVpn,
                onCheckedChange = {
                    allThroughVpn = it
                    Settings.allThroughVpn = it
                    com.vpn4tv.app.bg.BoxService.reloadIfRunning()
                },
            )
            // Plain BitTorrent goes direct in every mode; said right under the
            // toggle — a TV scrolls with the focus, so a note at the very
            // bottom might never come into view.
            Text(
                stringResource(R.string.settings_torrents_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // Bring-your-own-key, tucked in Settings so the Telegram funnel
            // stays the primary path. A phone held upright pastes the key; a
            // TV, tablet or phone on its side imports over the local network.
            if (currentKeyImport() == KeyImport.Paste) {
                SettingsLink(
                    title = stringResource(R.string.manual_import_title),
                    subtitle = stringResource(R.string.setting_manual_import_desc),
                    onClick = onManualImport,
                )
            } else {
                SettingsLink(
                    title = stringResource(R.string.lan_import_title),
                    subtitle = stringResource(R.string.setting_lan_import_desc),
                    onClick = onLanImport,
                )
            }

            // Proxy mode — sing-box as a local SOCKS5 listener instead of a
            // VPN tunnel, for devices without the system VPN permission dialog.
            SettingsToggle(
                title = stringResource(R.string.setting_proxy_mode),
                subtitle = stringResource(R.string.setting_proxy_mode_desc),
                checked = proxyMode,
                onCheckedChange = {
                    proxyMode = it
                    Settings.serviceMode = if (it) ServiceMode.PROXY else ServiceMode.VPN
                },
            )

            // LAN bypass only applies in VPN mode: in proxy mode sing-box never
            // sees LAN traffic in the first place.
            if (!proxyMode) {
                SettingsToggle(
                    title = stringResource(R.string.setting_bypass_lan),
                    subtitle = stringResource(R.string.setting_bypass_lan_desc),
                    checked = bypassLan,
                    onCheckedChange = { bypassLan = it; Settings.bypassLan = it },
                )
            }

            // Phone: logs and About live here (the navigation bar holds the
            // primary destinations); on TV they are icons on Home.
            if (!Device.isTv) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingsLink(title = stringResource(R.string.title_logs), subtitle = null, onClick = onLogs)
                SettingsLink(title = stringResource(R.string.title_about), subtitle = null, onClick = onAbout)
            }
        }
    }
}

/** A row that opens another screen or a dialog. */
@Composable
private fun SettingsLink(title: String, subtitle: String?, onClick: () -> Unit) {
    SettingsRow(title, subtitle, Modifier.clickable(onClick = onClick), onClick) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // The whole row toggles; the switch only shows the state (one focus stop on TV).
    SettingsRow(
        title, subtitle,
        Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        onClick = { onCheckedChange(!checked) },
    ) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Phone: a Material 3 list row. TV: a card-like row with the shared focus
 * mark, larger type for the 10-foot distance.
 */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    phoneModifier: Modifier,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    if (!Device.isTv) {
        val supporting: (@Composable () -> Unit)? = if (subtitle != null) { { Text(subtitle) } } else null
        ListItem(
            modifier = phoneModifier,
            headlineContent = { Text(title) },
            supportingContent = supporting,
            trailingContent = trailing,
        )
        return
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().tvFocus(RoundedCornerShape(12.dp), TV_ROW_SCALE),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 20.sp, color = colors.onSurface)
                if (subtitle != null) Text(subtitle, fontSize = 15.sp, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}
