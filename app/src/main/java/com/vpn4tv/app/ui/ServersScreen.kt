package com.vpn4tv.app.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.ui.theme.LocalStateColors
import com.vpn4tv.app.utils.CommandClient
import io.nekohasekai.libbox.OutboundGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ServersScreen(onBack: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var groups by remember { mutableStateOf<List<GroupData>>(emptyList()) }
    var isTestRunning by remember { mutableStateOf(false) }

    val commandClient = remember {
        CommandClient(
            scope,
            listOf(CommandClient.ConnectionType.Groups),
            object : CommandClient.Handler {
                override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
                    // Only show selector groups (skip internal urltest)
                    groups = newGroups
                        .filter { it.type == "selector" }
                        .map { group ->
                            val items = mutableListOf<ServerItem>()
                            val itemIter = group.items
                            while (itemIter.hasNext()) {
                                val item = itemIter.next()
                                items.add(ServerItem(tag = item.tag, type = item.type, delay = item.urlTestDelay.toInt()))
                            }
                            GroupData(tag = group.tag, type = group.type, selected = group.selected, items = items)
                        }
                }
            }
        )
    }

    DisposableEffect(Unit) {
        commandClient.connect()
        onDispose { commandClient.disconnect() }
    }

    val testAll: () -> Unit = {
        if (!isTestRunning) {
            isTestRunning = true
            scope.launch(Dispatchers.IO) {
                groups.filter { it.type == "urltest" || it.type == "selector" }.forEach { group ->
                    try {
                        val client = io.nekohasekai.libbox.Libbox.newStandaloneCommandClient()
                        client.urlTest(group.tag)
                        client.disconnect()
                    } catch (e: Exception) {
                        Log.w("Servers", "urlTest ${group.tag}: ${e.message}")
                    }
                }
                // Wait for results to propagate
                delay(2000)
                withContext(Dispatchers.Main) { isTestRunning = false }
            }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.title_servers),
        onBack = onBack,
        actions = {
            // Nothing to test until the tunnel lists its servers.
            if (groups.isNotEmpty()) {
                IconButton(onClick = testAll, modifier = Modifier.tvFocus(CircleShape)) {
                    if (isTestRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, stringResource(R.string.action_test_all))
                    }
                }
            }
        },
    ) { padding ->
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.servers_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 18.sp,
                )
            }
            return@ScreenScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = if (Device.isTv) PaddingValues(vertical = 8.dp) else padding,
            verticalArrangement = Arrangement.spacedBy(if (Device.isTv) 6.dp else 0.dp),
        ) {
            groups.forEach { group ->
                items(group.items, key = { "${group.tag}_${it.tag}" }) { server ->
                    ServerRow(
                        server = server,
                        isSelected = server.tag == group.selected,
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val client = io.nekohasekai.libbox.Libbox.newStandaloneCommandClient()
                                    client.selectOutbound(group.tag, server.tag)
                                    client.disconnect()
                                    // Remember selection
                                    saveSelectedServer(context, server.tag)
                                    Log.d("Servers", "Selected ${server.tag} in ${group.tag}")
                                } catch (e: Exception) {
                                    Log.e("Servers", "select failed: ${e.message}")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The selected server carries a check mark; on TV the outline is the focus only. */
@Composable
private fun ServerRow(server: ServerItem, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val mark: @Composable () -> Unit = {
        Box(Modifier.width(32.dp)) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check, stringResource(R.string.server_selected),
                    tint = LocalStateColors.current.connected, modifier = Modifier.size(22.dp),
                )
            }
        }
    }
    if (!Device.isTv) {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            leadingContent = mark,
            headlineContent = { Text(server.tag) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.type, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    DelayText(server.delay, 14.sp)
                }
            },
        )
        return
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().tvFocus(RoundedCornerShape(10.dp), TV_ROW_SCALE),
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            mark()
            Text(server.tag, fontSize = 18.sp, color = colors.onSurface, modifier = Modifier.weight(1f))
            Text(server.type, fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
            DelayText(server.delay, 16.sp)
        }
    }
}

@Composable
private fun DelayText(delay: Int, size: androidx.compose.ui.unit.TextUnit) {
    val state = LocalStateColors.current
    val text = when {
        delay == 0 -> "—"
        delay < 0 -> "✕"
        else -> "$delay ${stringResource(R.string.unit_ms)}"
    }
    val color = when {
        delay == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        delay < 0 -> state.danger
        delay < 300 -> state.fast
        delay < 600 -> state.medium
        else -> state.slow
    }
    Text(text, fontSize = size, color = color)
}

data class GroupData(
    val tag: String,
    val type: String,
    val selected: String,
    val items: List<ServerItem>
)

data class ServerItem(
    val tag: String,
    val type: String,
    val delay: Int,
)

private const val PREFS_NAME = "server_selection"
private const val LEGACY_KEY = "selected_server"

private fun selectedServerKey(): String {
    val pid = com.vpn4tv.app.database.Settings.selectedProfile
    return "selected_server_$pid"
}

/** One-shot migration: drop the pre-multi-profile key. Idempotent. */
fun migrateLegacySelectedServer(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.contains(LEGACY_KEY)) {
        prefs.edit().remove(LEGACY_KEY).apply()
    }
}

fun saveSelectedServer(context: Context, serverTag: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(selectedServerKey(), serverTag).apply()
}

fun getSavedServer(context: Context): String? {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(selectedServerKey(), null)
}

fun clearSavedServer(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().remove(selectedServerKey()).apply()
}
