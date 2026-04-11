package com.vpn4tv.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.utils.CommandClient
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vpn4tv.app.database.Settings as AppSettings

@Composable
fun HomeScreen(
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onNavigateProfiles: () -> Unit = {},
    onNavigateServers: () -> Unit = {},
    onAddProfile: () -> Unit = {},
    onNavigateLogs: () -> Unit = {},
    onNavigateSettings: () -> Unit = {},
    onNavigateAbout: () -> Unit = {},
) {
    val status by BoxService.globalStatus.observeAsState(Status.Stopped)
    val lastError by BoxService.lastError.observeAsState(null)
    val ready by MainActivity.profileReady.observeAsState(false)
    val connectFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Update check
    var updateAvailable by remember { mutableStateOf<com.vpn4tv.app.utils.UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            updateAvailable = com.vpn4tv.app.utils.UpdateChecker.check()
        }
    }

    // Track active server and traffic from CommandClient
    var activeServer by remember { mutableStateOf<String?>(null) }
    var activeDelay by remember { mutableStateOf(0) }
    var sessionUplink by remember { mutableStateOf(0L) }
    var sessionDownlink by remember { mutableStateOf(0L) }

    val commandClient = remember {
        CommandClient(
            scope,
            listOf(CommandClient.ConnectionType.Groups, CommandClient.ConnectionType.Status),
            object : CommandClient.Handler {
                override fun updateStatus(status: io.nekohasekai.libbox.StatusMessage) {
                    sessionUplink = status.uplinkTotal
                    sessionDownlink = status.downlinkTotal
                }
                override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
                    // Find selector group and get its selected item
                    val selector = newGroups.firstOrNull { it.type == "selector" }
                    if (selector != null) {
                        var selected = selector.selected
                        // If selected is "auto", find urltest's selected
                        if (selected == "auto") {
                            val urltest = newGroups.firstOrNull { it.type == "urltest" }
                            if (urltest != null) selected = urltest.selected
                        }
                        activeServer = selected
                        // Find delay
                        val items = selector.items
                        while (items.hasNext()) {
                            val item = items.next()
                            if (item.tag == selected) {
                                activeDelay = item.urlTestDelay.toInt()
                                break
                            }
                        }
                        // If we got "auto" selected, check urltest items
                        if (activeDelay == 0) {
                            val urltest = newGroups.firstOrNull { it.type == "urltest" }
                            if (urltest != null) {
                                val utItems = urltest.items
                                while (utItems.hasNext()) {
                                    val item = utItems.next()
                                    if (item.tag == selected) {
                                        activeDelay = item.urlTestDelay.toInt()
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    // Connect/disconnect CommandClient based on VPN status
    LaunchedEffect(status) {
        if (status == Status.Started) {
            commandClient.connect()
        } else if (status == Status.Stopped) {
            activeServer = null
            activeDelay = 0
            sessionUplink = 0
            sessionDownlink = 0
            commandClient.disconnect()
        }
    }

    DisposableEffect(Unit) {
        onDispose { commandClient.disconnect() }
    }

    LaunchedEffect(ready) {
        if (ready) {
            kotlinx.coroutines.delay(300) // wait for compose tree
            try { connectFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    val isTV = remember {
        context.packageManager.hasSystemFeature("android.software.leanback")
    }
    val screenPadding = if (isTV) 32.dp else 16.dp
    val titleSize = if (isTV) 28.sp else 20.sp
    val statusSize = if (isTV) 32.sp else 24.sp
    val buttonWidth = if (isTV) 300.dp else 240.dp
    val buttonHeight = if (isTV) 80.dp else 56.dp
    val buttonTextSize = if (isTV) 24.sp else 18.sp
    val iconSize = if (isTV) 24.dp else 20.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (isTV) Modifier.padding(screenPadding)
                  else Modifier.statusBarsPadding().navigationBarsPadding().padding(screenPadding)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar with profile info
        var activeProfile by remember { mutableStateOf<com.vpn4tv.app.database.Profile?>(null) }
        var isUpdating by remember { mutableStateOf(false) }

        LaunchedEffect(ready) {
            if (ready && AppSettings.selectedProfile != -1L) {
                withContext(Dispatchers.IO) {
                    activeProfile = com.vpn4tv.app.database.ProfileManager.get(AppSettings.selectedProfile)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("VPN4TV", fontSize = titleSize, fontWeight = FontWeight.Bold, color = Color.White)
                if (activeProfile != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            activeProfile?.name ?: "",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (!isUpdating && activeProfile != null) {
                                    isUpdating = true
                                    GlobalScope.launch(Dispatchers.IO) {
                                        try {
                                            val p = activeProfile!!
                                            if (p.typed.remoteURL.isNotEmpty()) {
                                                val sub = com.vpn4tv.app.converter.HwidService.downloadSubscription(com.vpn4tv.app.Application.application, p.typed.remoteURL)
                                                val proxies = com.vpn4tv.app.converter.ProxyParser.parseSubscription(sub)
                                                if (proxies.isNotEmpty()) {
                                                    val config = com.vpn4tv.app.converter.ConfigGenerator.generate(proxies)
                                                    java.io.File(p.typed.path).writeText(config)
                                                    p.typed.lastUpdated = java.util.Date()
                                                    com.vpn4tv.app.database.ProfileManager.update(p)
                                                }
                                            }
                                        } catch (_: Exception) {}
                                        withContext(Dispatchers.Main) {
                                            isUpdating = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            if (isUpdating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    stringResource(R.string.action_update_subscription),
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
            Row {
                IconButton(onClick = onNavigateServers) {
                    Icon(Icons.Default.List, stringResource(R.string.nav_servers), tint = Color.White, modifier = Modifier.size(iconSize))
                }
                IconButton(onClick = onNavigateLogs) {
                    Icon(Icons.Default.Description, stringResource(R.string.title_logs), tint = Color.White, modifier = Modifier.size(iconSize))
                }
                IconButton(onClick = onNavigateProfiles) {
                    Icon(Icons.Default.FolderOpen, stringResource(R.string.nav_profiles), tint = Color.White, modifier = Modifier.size(iconSize))
                }
                IconButton(onClick = onNavigateSettings) {
                    Icon(Icons.Default.Settings, stringResource(R.string.title_settings), tint = Color.White, modifier = Modifier.size(iconSize))
                }
                IconButton(onClick = onNavigateAbout) {
                    Icon(Icons.Default.Info, stringResource(R.string.title_about), tint = Color.White, modifier = Modifier.size(iconSize))
                }
            }
        }

        // Update banner
        if (updateAvailable != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        com.vpn4tv.app.utils.UpdateChecker.openDownload(context, updateAvailable!!)
                    }
                    .focusable(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${stringResource(R.string.update_available)}: ${updateAvailable!!.versionName}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Text("→", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Main content
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status indicator
            Text(
                text = when {
                    !ready -> stringResource(R.string.status_loading)
                    status == Status.Started -> stringResource(R.string.status_connected)
                    status == Status.Starting -> stringResource(R.string.status_connecting)
                    status == Status.Stopping -> stringResource(R.string.status_disconnecting)
                    else -> stringResource(R.string.status_disconnected)
                },
                fontSize = statusSize,
                fontWeight = FontWeight.Bold,
                color = when {
                    !ready -> Color.Gray
                    status == Status.Started -> Color.Green
                    status == Status.Starting || status == Status.Stopping -> Color.Yellow
                    else -> Color.Gray
                }
            )

            // Show last error if VPN failed to start
            if (status == Status.Stopped && lastError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    lastError ?: "",
                    fontSize = 12.sp,
                    color = Color.Red,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Connect button
            Button(
                onClick = {
                    when (status) {
                        Status.Stopped -> onConnect()
                        Status.Started -> onDisconnect()
                        else -> {}
                    }
                },
                modifier = Modifier
                    .width(buttonWidth)
                    .height(buttonHeight)
                    .focusRequester(connectFocus),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (status) {
                        Status.Started -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                ),
                enabled = ready && (status == Status.Stopped || status == Status.Started)
            ) {
                Text(
                    text = when {
                        !ready -> stringResource(R.string.status_loading)
                        status == Status.Stopped -> stringResource(R.string.action_connect)
                        status == Status.Started -> stringResource(R.string.action_disconnect)
                        status == Status.Starting -> stringResource(R.string.status_connecting)
                        status == Status.Stopping -> stringResource(R.string.status_disconnecting)
                        else -> ""
                    },
                    fontSize = buttonTextSize
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Active server info — clickable to open servers list
            if (status == Status.Started && activeServer != null) {
                Card(
                    modifier = Modifier
                        .clickable { onNavigateServers() }
                        .focusable(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.label_server), fontSize = 16.sp, color = Color.Gray)
                        Text(
                            activeServer ?: "",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (activeDelay > 0) {
                            Spacer(modifier = Modifier.width(12.dp))
                            val delayColor = when {
                                activeDelay < 300 -> Color.Green
                                activeDelay < 600 -> Color.Yellow
                                else -> Color(0xFFFF6600)
                            }
                            Text(
                                "${activeDelay}ms",
                                fontSize = 16.sp,
                                color = delayColor
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("›", fontSize = 20.sp, color = Color.Gray)
                    }
                }
            // Session traffic
            if (status == Status.Started && (sessionUplink + sessionDownlink) > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                val total = sessionUplink + sessionDownlink
                Text(
                    "${stringResource(R.string.session_traffic)}: ${formatBytes(total)}",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
            }

            if (!ready || AppSettings.selectedProfile == -1L) {
                TextButton(onClick = onAddProfile) {
                    Text(stringResource(R.string.link_add_telegram), color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)}KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))}MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))}GB"
}
