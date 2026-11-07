package com.vpn4tv.app.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

@Composable
fun PerAppProxyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var selectedApps by remember { mutableStateOf(Settings.perAppProxyList.toMutableSet()) }
    var enabled by remember { mutableStateOf(Settings.perAppProxyEnabled) }
    var mode by remember { mutableStateOf(Settings.perAppProxyMode) }
    var showSystem by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    fun saveAndBack() {
        val changed = enabled != Settings.perAppProxyEnabled ||
            mode != Settings.perAppProxyMode ||
            selectedApps != Settings.perAppProxyList
        Settings.perAppProxyEnabled = enabled
        Settings.perAppProxyMode = mode
        Settings.perAppProxyList = selectedApps
        // Auto-reconnect if VPN is running and settings changed
        if (changed && com.vpn4tv.app.bg.BoxService.globalStatus.value == com.vpn4tv.app.constant.Status.Started) {
            com.vpn4tv.app.bg.BoxService.stop()
            // Start again once the stop has ended: it may wait for a reload in
            // progress, and a start asked for before it ends is dropped.
            GlobalScope.launch(Dispatchers.Main) {
                withTimeoutOrNull(20_000) {
                    while (com.vpn4tv.app.bg.BoxService.globalStatus.value != com.vpn4tv.app.constant.Status.Stopped) delay(100)
                }
                if (com.vpn4tv.app.bg.BoxService.globalStatus.value == com.vpn4tv.app.constant.Status.Stopped) {
                    com.vpn4tv.app.bg.BoxService.start()
                } else {
                    android.util.Log.w("PerAppProxyScreen", "restart dropped: the stop has not ended in 20 s")
                }
            }
        }
        onBack()
    }

    BackHandler { saveAndBack() }

    // Load app list
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { info ->
                    AppEntry(
                        packageName = info.packageName,
                        label = info.loadLabel(pm).toString(),
                        isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedWith(compareBy<AppEntry> { it.isSystem }.thenBy { it.label.lowercase() })
            withContext(Dispatchers.Main) {
                apps = installed
                isLoading = false
            }
        }
    }

    // Always show important apps even when hiding system
    val alwaysShowPackages = setOf(
        "com.google.android.youtube",
        "com.google.android.youtube.tv",
        "com.google.android.youtube.tvmusic",
    )
    val filteredApps = remember(apps, showSystem, selectedApps) {
        val visible = if (showSystem) apps
            else apps.filter { !it.isSystem || it.packageName in alwaysShowPackages }

        visible.sortedWith(
            compareBy<AppEntry> { it.packageName !in selectedApps }  // selected first
                .thenBy { it.packageName !in alwaysShowPackages }     // YouTube first in each group
                .thenBy { it.label.lowercase() }                      // then alphabetical
        )
    }

    val colors = MaterialTheme.colorScheme
    val toggle: (String, Boolean) -> Unit = { pkg, on ->
        selectedApps = selectedApps.toMutableSet().apply { if (on) add(pkg) else remove(pkg) }
    }
    ScreenScaffold(title = stringResource(R.string.title_per_app_proxy), onBack = { saveAndBack() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = if (Device.isTv) 0.dp else 16.dp)) {
            // Enable toggle: the whole row toggles, one focus stop on TV.
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .toggleable(value = enabled, role = Role.Switch, onValueChange = { enabled = it })
                    .tvFocus(RoundedCornerShape(12.dp), TV_ROW_SCALE),
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.setting_per_app_enabled),
                        fontSize = if (Device.isTv) 20.sp else 16.sp, color = colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = null)
                }
            }

            if (enabled) {
                Spacer(Modifier.height(12.dp))
                // Mode: a Material 3 segmented button, one of two.
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(
                        Settings.PER_APP_PROXY_EXCLUDE to stringResource(R.string.mode_exclude),
                        Settings.PER_APP_PROXY_INCLUDE to stringResource(R.string.mode_include),
                    )
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = mode == value,
                            onClick = { mode = value },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            modifier = Modifier.tvFocus(RoundedCornerShape(20.dp), 1.05f),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (mode == Settings.PER_APP_PROXY_EXCLUDE) stringResource(R.string.mode_exclude_desc)
                    else stringResource(R.string.mode_include_desc),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )

                Row(
                    Modifier
                        .toggleable(value = showSystem, role = Role.Checkbox, onValueChange = { showSystem = it })
                        .tvFocus(RoundedCornerShape(8.dp), 1.05f)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = showSystem, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.show_system_apps), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                }
                Text(
                    String.format(stringResource(R.string.selected_count), selectedApps.size),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(if (Device.isTv) 4.dp else 0.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val checked = app.packageName in selectedApps
                            // The whole row toggles; the checkbox only shows the state.
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { toggle(app.packageName, it) })
                                    .tvFocus(RoundedCornerShape(10.dp), TV_ROW_SCALE)
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, fontSize = if (Device.isTv) 17.sp else 15.sp, color = colors.onSurface)
                                    Text(app.packageName, fontSize = if (Device.isTv) 13.sp else 12.sp, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
