package com.vpn4tv.app.ui

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import android.content.Context
import com.vpn4tv.app.utils.CommandClient
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItem
import io.nekohasekai.libbox.StatusMessage
import kotlinx.coroutines.*

@Composable
fun ServersScreen(onBack: () -> Unit) {
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
                                items.add(ServerItem(
                                    tag = item.tag,
                                    type = item.type,
                                    delay = item.urlTestDelay.toInt()
                                ))
                            }
                            GroupData(
                                tag = group.tag,
                                type = group.type,
                                selected = group.selected,
                                items = items
                            )
                        }
                }
            }
        )
    }

    DisposableEffect(Unit) {
        commandClient.connect()
        onDispose { commandClient.disconnect() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                stringResource(R.string.title_servers),
                fontSize = 28.sp,
                color = Color.White,
                modifier = Modifier.weight(1f).padding(start = 16.dp)
            )
            IconButton(onClick = {
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
            }) {
                if (isTestRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, stringResource(R.string.action_test_all), tint = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.servers_empty), color = Color.Gray, fontSize = 18.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                groups.forEach { group ->
                    item {
                        Text(
                            "${group.tag} (${group.type})",
                            fontSize = 20.sp,
                            color = Color.White,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(group.items, key = { "${group.tag}_${it.tag}" }) { server ->
                        ServerCard(
                            server = server,
                            isSelected = server.tag == group.selected,
                            onClick = {
                                Log.d("Servers", "Clicked ${server.tag}")
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
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerCard(server: ServerItem, isSelected: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = when {
        isSelected -> Color.Green
        isFocused -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .border(BorderStroke(2.dp, borderColor), shape = MaterialTheme.shapes.small)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .focusable(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                server.tag,
                fontSize = 16.sp,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            Text(
                server.type,
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(end = 16.dp)
            )
            DelayChip(server.delay)
        }
    }
}

@Composable
private fun DelayChip(delay: Int) {
    val text = when {
        delay == 0 -> "—"
        delay < 0 -> "✕"
        else -> "${delay}ms"
    }
    val color = when {
        delay == 0 -> Color.Gray
        delay < 0 -> Color.Red
        delay < 300 -> Color.Green
        delay < 600 -> Color.Yellow
        else -> Color(0xFFFF6600) // orange
    }
    Text(text, fontSize = 14.sp, color = color)
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
private const val KEY_SELECTED_SERVER = "selected_server"

fun saveSelectedServer(context: Context, serverTag: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_SELECTED_SERVER, serverTag).apply()
}

fun getSavedServer(context: Context): String? {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_SELECTED_SERVER, null)
}

fun clearSavedServer(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().remove(KEY_SELECTED_SERVER).apply()
}
