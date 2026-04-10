package com.vpn4tv.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.utils.CommandClient
import io.nekohasekai.libbox.OutboundGroup
import kotlinx.coroutines.launch

private enum class LogLevel(val label: String, val priority: Int) {
    ALL("All", 0),
    INFO("Info+", 1),
    WARN("Warn+", 2),
    ERROR("Error", 3);
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
    var filterLevel by remember { mutableStateOf(LogLevel.ALL) }
    val listState = rememberLazyListState()

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
                    val updated = (allLogs + messages).takeLast(500)
                    allLogs = updated
                }
            }
        )
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    DisposableEffect(Unit) {
        commandClient.connect()
        onDispose { commandClient.disconnect() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                stringResource(R.string.title_logs),
                fontSize = 28.sp,
                color = Color.White,
                modifier = Modifier.weight(1f).padding(start = 16.dp)
            )
            IconButton(onClick = { allLogs = emptyList() }) {
                Icon(Icons.Default.Delete, stringResource(R.string.action_clear), tint = Color.White)
            }
        }

        // Filter chips
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LogLevel.entries.forEach { level ->
                val selected = filterLevel == level
                val bgColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surfaceVariant
                Card(
                    modifier = Modifier
                        .clickable { filterLevel = level }
                        .focusable(),
                    colors = CardDefaults.cardColors(containerColor = bgColor)
                ) {
                    Text(
                        level.label,
                        fontSize = 14.sp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (filteredLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.logs_empty), color = Color.Gray, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredLogs) { line ->
                    val level = logLineLevel(line)
                    val color = when (level) {
                        3 -> Color(0xFFEF5350)
                        2 -> Color(0xFFFFA726)
                        0 -> Color(0xFF666666)
                        else -> Color(0xFFCCCCCC)
                    }
                    Text(
                        line,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}
