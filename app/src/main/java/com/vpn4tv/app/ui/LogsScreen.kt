package com.vpn4tv.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.ui.theme.LocalStateColors
import com.vpn4tv.app.utils.CommandClient

private enum class LogLevel(@StringRes val label: Int, val priority: Int) {
    ALL(R.string.log_filter_all, 0),
    INFO(R.string.log_filter_info, 1),
    WARN(R.string.log_filter_warn, 2),
    ERROR(R.string.log_filter_error, 3);
}

private val errorPattern = Regex("\\bERROR\\b|\\bFATAL\\b")
private val warnPattern = Regex("\\bWARN\\b")
private val infoPattern = Regex("\\bINFO\\b")

private fun logLineLevel(line: String): Int = when {
    errorPattern.containsMatchIn(line) -> 3
    warnPattern.containsMatchIn(line) -> 2
    infoPattern.containsMatchIn(line) -> 1
    else -> 0 // DEBUG, TRACE
}

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var allLogs by remember { mutableStateOf(listOf<String>()) }
    // Default to WARN+: sing-box at INFO is noisy (several lines per
    // connection) and users opening Logs are almost always chasing a
    // problem, not reviewing steady-state traffic.
    var filterLevel by remember { mutableStateOf(LogLevel.WARN) }
    val listState = rememberLazyListState()
    val status by BoxService.globalStatus.observeAsState(Status.Stopped)

    val filteredLogs = remember(allLogs, filterLevel) {
        if (filterLevel == LogLevel.ALL) allLogs
        else allLogs.filter { logLineLevel(it) >= filterLevel.priority }
    }

    val commandClient = remember {
        CommandClient(
            scope,
            listOf(CommandClient.ConnectionType.Log),
            object : CommandClient.Handler {
                override fun appendLogs(messages: List<String>) {
                    allLogs = (allLogs + messages).takeLast(500)
                }
            }
        )
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) listState.animateScrollToItem(filteredLogs.size - 1)
    }

    DisposableEffect(Unit) {
        commandClient.connect()
        onDispose { commandClient.disconnect() }
    }

    val colors = MaterialTheme.colorScheme
    val state = LocalStateColors.current
    val context = LocalContext.current
    ScreenScaffold(
        title = stringResource(R.string.title_logs),
        onBack = onBack,
        actions = {
            // Phone: hand the log to Telegram, mail, Files… for support.
            if (!Device.isTv) {
                IconButton(onClick = { shareLogs(context, allLogs) }, enabled = allLogs.isNotEmpty()) {
                    Icon(Icons.Default.Share, stringResource(R.string.action_share_logs))
                }
            }
            IconButton(onClick = { allLogs = emptyList() }, modifier = Modifier.tvFocus(CircleShape)) {
                Icon(Icons.Default.Delete, stringResource(R.string.action_clear))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = if (Device.isTv) 0.dp else 16.dp)) {
            // Filter chips: the selected one carries a check mark, not only a colour.
            // TV: room inside the scrolling row for the focused chip's
            // enlarged outline, which the scroll would clip at the edges.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp, horizontal = if (Device.isTv) 8.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogLevel.entries.forEach { level ->
                    val selected = filterLevel == level
                    val checkIcon: @Composable () -> Unit = { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                    FilterChip(
                        selected = selected,
                        onClick = { filterLevel = level },
                        label = { Text(stringResource(level.label), fontSize = if (Device.isTv) 16.sp else 14.sp) },
                        leadingIcon = if (selected) checkIcon else null,
                        modifier = Modifier.tvFocus(RoundedCornerShape(8.dp)),
                    )
                }
            }
            if (filteredLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Connected: the filter hides what there is, or nothing came yet.
                    val empty = if (status == Status.Started) R.string.logs_none_at_level else R.string.logs_empty
                    Text(stringResource(empty), color = colors.onSurfaceVariant, fontSize = 16.sp)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(filteredLogs) { line ->
                        Text(
                            line,
                            fontSize = if (Device.isTv) 14.sp else 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when (logLineLevel(line)) {
                                3 -> state.danger
                                2 -> state.slow
                                0 -> colors.onSurfaceVariant
                                else -> colors.onSurface
                            },
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private val ansi = Regex("\u001B\\[[;\\d]*m")

/**
 * The log as a text file, offered to the system share sheet. All levels,
 * whatever the filter shows: support wants the lines around an error too.
 * The header names the build and the device id support asks for.
 */
private fun shareLogs(context: android.content.Context, lines: List<String>) {
    val dir = java.io.File(context.cacheDir, "logs").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    val file = java.io.File(dir, "vpn4tv-log-$stamp.txt")
    file.bufferedWriter().use { out ->
        out.write("VPN4TV ${com.vpn4tv.app.BuildConfig.VERSION_NAME} (${com.vpn4tv.app.BuildConfig.VERSION_CODE})\n")
        out.write("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n")
        out.write("ID ${com.vpn4tv.app.converter.HwidService.getHwid(context)}\n\n")
        lines.forEach { out.write(ansi.replace(it, "")); out.write("\n") }
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.cache", file)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(send, context.getString(R.string.action_share_logs))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
