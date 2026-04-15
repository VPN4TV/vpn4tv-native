package com.vpn4tv.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.constant.ServiceMode
import com.vpn4tv.app.database.Settings

@Composable
fun SettingsScreen(onBack: () -> Unit, onPerAppProxy: () -> Unit = {}) {
    var autoConnect by remember { mutableStateOf(Settings.autoConnectOnBoot) }
    var proxyMode by remember { mutableStateOf(Settings.isProxyMode) }
    var bypassLan by remember { mutableStateOf(Settings.bypassLan) }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                stringResource(R.string.title_settings),
                fontSize = 28.sp,
                color = Color.White,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Per-app proxy
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onPerAppProxy() }
                .focusable(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.title_per_app_proxy), fontSize = 18.sp, color = Color.White)
                    Text(
                        if (Settings.perAppProxyEnabled)
                            "${if (Settings.perAppProxyMode == Settings.PER_APP_PROXY_EXCLUDE) stringResource(R.string.mode_exclude) else stringResource(R.string.mode_include)}: ${Settings.perAppProxyList.size} apps"
                        else stringResource(R.string.setting_disabled),
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
                Text("›", fontSize = 20.sp, color = Color.Gray)
            }
        }

        // Proxy mode — exposes sing-box as a local SOCKS5 listener on
        // 127.0.0.1:12334 instead of creating a VPN tunnel. For devices
        // where the system VPN permission dialog is missing.
        SettingsToggle(
            title = stringResource(R.string.setting_proxy_mode),
            subtitle = stringResource(R.string.setting_proxy_mode_desc),
            checked = proxyMode,
            onCheckedChange = {
                proxyMode = it
                Settings.serviceMode = if (it) ServiceMode.PROXY else ServiceMode.VPN
            }
        )

        // LAN bypass only applies in VPN mode. In proxy mode sing-box never
        // sees LAN traffic in the first place — apps route to 127.0.0.1:12334
        // explicitly — so the toggle would be a no-op and just adds noise.
        if (!proxyMode) {
            SettingsToggle(
                title = stringResource(R.string.setting_bypass_lan),
                subtitle = stringResource(R.string.setting_bypass_lan_desc),
                checked = bypassLan,
                onCheckedChange = {
                    bypassLan = it
                    Settings.bypassLan = it
                }
            )
        }

        // Auto-connect on boot
        SettingsToggle(
            title = stringResource(R.string.setting_auto_connect),
            subtitle = stringResource(R.string.setting_auto_connect_desc),
            checked = autoConnect,
            onCheckedChange = {
                autoConnect = it
                Settings.autoConnectOnBoot = it
            }
        )
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onCheckedChange(!checked) }
            .focusable(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, color = Color.White)
                Text(subtitle, fontSize = 13.sp, color = Color.Gray)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
