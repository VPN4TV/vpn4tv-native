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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Icon as TvIcon
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text as TvText

data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * [onBack] null: shown beside the settings list on a wide window. Then there is
 * no Back arrow and Back is not taken: the changes apply when the pane closes
 * (another row picked, Settings left), as they do on Back otherwise.
 */
@Composable
fun PerAppProxyScreen(onBack: (() -> Unit)?) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var selectedApps by remember { mutableStateOf(Settings.perAppProxyList.toMutableSet()) }
    var enabled by remember { mutableStateOf(Settings.perAppProxyEnabled) }
    var mode by remember { mutableStateOf(Settings.perAppProxyMode) }
    var showSystem by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    fun save() {
        val changed = enabled != Settings.perAppProxyEnabled ||
            mode != Settings.perAppProxyMode ||
            selectedApps != Settings.perAppProxyList
        if (!changed) return
        Settings.perAppProxyEnabled = enabled
        Settings.perAppProxyMode = mode
        Settings.perAppProxyList = selectedApps
        // Auto-reconnect if VPN is running and settings changed
        if (com.vpn4tv.app.bg.BoxService.globalStatus.value == com.vpn4tv.app.constant.Status.Started) {
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
    }

    if (onBack != null) {
        BackHandler { save(); onBack() }
    } else {
        // In a pane: the latest values when it closes.
        val latestSave by rememberUpdatedState(::save)
        DisposableEffect(Unit) { onDispose { latestSave() } }
    }

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
    ScreenScaffold(
        title = stringResource(R.string.title_per_app_proxy),
        onBack = onBack?.let { back -> { save(); back() } },
        collapsingTopBar = true,
    ) { padding ->
        if (Device.isTv) {
            TvPerAppContent(
                enabled = enabled, onEnabled = { enabled = it },
                mode = mode, onMode = { mode = it },
                showSystem = showSystem, onShowSystem = { showSystem = it },
                selectedCount = selectedApps.size,
                isLoading = isLoading,
                apps = filteredApps,
                isChecked = { it in selectedApps },
                onToggle = toggle,
            )
            return@ScreenScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .readableWidth(ContentWidth.text),
        ) {
            // Enable toggle: the whole row toggles.
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .toggleable(value = enabled, role = Role.Switch, onValueChange = { enabled = it }),
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.setting_per_app_enabled),
                        fontSize = 16.sp, color = colors.onSurface,
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
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val checked = app.packageName in selectedApps
                            // The whole row toggles; the checkbox only shows the state.
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { toggle(app.packageName, it) })
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, fontSize = 15.sp, color = colors.onSurface)
                                    Text(app.packageName, fontSize = 12.sp, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * TV: the same screen in Compose for TV. Each row is one focus stop that
 * toggles (TalkBack: a switch or a check box with its state), rows without a
 * container (the grey plate and the outline in focus), the mode as two
 * segments of which one is chosen.
 */
@Composable
private fun TvPerAppContent(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    mode: Int,
    onMode: (Int) -> Unit,
    showSystem: Boolean,
    onShowSystem: (Boolean) -> Unit,
    selectedCount: Int,
    isLoading: Boolean,
    apps: List<AppEntry>,
    isChecked: (String) -> Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    val type = TvMaterialTheme.typography
    val colors = TvMaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        TvRow(
            onClick = { onEnabled(!enabled) },
            modifier = Modifier.fillMaxWidth().toggleState(Role.Switch, enabled),
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TvText(stringResource(R.string.setting_per_app_enabled), style = type.titleMedium, modifier = Modifier.weight(1f))
                TvSwitch(checked = enabled)
            }
        }
        if (!enabled) return@Column

        Spacer(Modifier.height(12.dp))
        val options = listOf(
            Settings.PER_APP_PROXY_EXCLUDE to stringResource(R.string.mode_exclude),
            Settings.PER_APP_PROXY_INCLUDE to stringResource(R.string.mode_include),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                val shape = if (index == 0) RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                    else RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                TvSegment(selected = mode == value, label = label, shape = shape, modifier = Modifier.weight(1f)) { onMode(value) }
            }
        }
        Spacer(Modifier.height(6.dp))
        TvText(
            stringResource(if (mode == Settings.PER_APP_PROXY_EXCLUDE) R.string.mode_exclude_desc else R.string.mode_include_desc),
            style = type.labelSmall, color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        TvRow(
            onClick = { onShowSystem(!showSystem) },
            modifier = Modifier.toggleState(Role.Checkbox, showSystem),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TvCheckbox(checked = showSystem)
                Spacer(Modifier.width(8.dp))
                TvText(stringResource(R.string.show_system_apps), style = type.bodyMedium)
            }
        }
        TvText(
            String.format(stringResource(R.string.selected_count), selectedCount),
            style = type.labelSmall, color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(apps, key = { it.packageName }) { app ->
                val checked = isChecked(app.packageName)
                TvRow(
                    onClick = { onToggle(app.packageName, !checked) },
                    modifier = Modifier.fillMaxWidth().toggleState(Role.Checkbox, checked),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvCheckbox(checked = checked)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            TvText(app.label, style = type.titleSmall)
                            TvText(app.packageName, style = type.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** A row that is a switch or a check box for TalkBack: its role and its state. */
private fun Modifier.toggleState(role: Role, on: Boolean): Modifier = semantics {
    this.role = role
    toggleableState = ToggleableState(on)
}

/** One of the two modes: filled and checked when chosen; for TalkBack one of a set. */
@Composable
private fun TvSegment(selected: Boolean, label: String, shape: Shape, modifier: Modifier, onClick: () -> Unit) {
    TvSurface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.semantics { role = Role.RadioButton },
        shape = SelectableSurfaceDefaults.shape(shape),
        colors = tvSegmentColors(),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = tvSegmentBorder(shape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                TvIcon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            TvText(label, style = TvMaterialTheme.typography.labelMedium)
        }
    }
}
