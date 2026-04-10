package com.vpn4tv.app.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.withContext

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
        Settings.perAppProxyEnabled = enabled
        Settings.perAppProxyMode = mode
        Settings.perAppProxyList = selectedApps
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

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { saveAndBack() }) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                stringResource(R.string.title_per_app_proxy),
                fontSize = 28.sp,
                color = Color.White,
                modifier = Modifier.weight(1f).padding(start = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Enable toggle
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { enabled = !enabled }
                .focusable(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.setting_per_app_enabled),
                    fontSize = 18.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))

            // Mode selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val excludeSelected = mode == Settings.PER_APP_PROXY_EXCLUDE
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { mode = Settings.PER_APP_PROXY_EXCLUDE }
                        .focusable(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (excludeSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        stringResource(R.string.mode_exclude),
                        fontSize = 14.sp,
                        color = if (excludeSelected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.padding(12.dp).align(Alignment.CenterHorizontally)
                    )
                }
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { mode = Settings.PER_APP_PROXY_INCLUDE }
                        .focusable(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (!excludeSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        stringResource(R.string.mode_include),
                        fontSize = 14.sp,
                        color = if (!excludeSelected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.padding(12.dp).align(Alignment.CenterHorizontally)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Mode description
            Text(
                if (mode == Settings.PER_APP_PROXY_EXCLUDE)
                    stringResource(R.string.mode_exclude_desc)
                else stringResource(R.string.mode_include_desc),
                fontSize = 12.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Show system apps toggle
            Row(
                modifier = Modifier
                    .clickable { showSystem = !showSystem }
                    .focusable()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.show_system_apps), fontSize = 14.sp, color = Color.Gray)
            }

            Text(
                String.format(stringResource(R.string.selected_count), selectedApps.size),
                fontSize = 13.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(4.dp))

            // App list
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val checked = app.packageName in selectedApps
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                                .clickable {
                                    selectedApps = selectedApps.toMutableSet().apply {
                                        if (checked) remove(app.packageName) else add(app.packageName)
                                    }
                                }
                                .focusable(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (checked)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                else Color.Transparent
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedApps = selectedApps.toMutableSet().apply {
                                            if (it) add(app.packageName) else remove(app.packageName)
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, fontSize = 15.sp, color = Color.White)
                                    Text(app.packageName, fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
