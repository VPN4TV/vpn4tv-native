package com.vpn4tv.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    val commandClient = remember {
        CommandClient(
            scope,
            listOf(CommandClient.ConnectionType.Log),
            object : CommandClient.Handler {
                override fun appendLogs(messages: List<String>) {
                    logs.addAll(messages)
                    // Keep max 500 lines
                    while (logs.size > 500) logs.removeAt(0)
                }
            }
        )
    }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
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
                stringResource(R.string.title_logs),
                fontSize = 28.sp,
                color = Color.White,
                modifier = Modifier.weight(1f).padding(start = 16.dp)
            )
            IconButton(onClick = { logs.clear() }) {
                Icon(Icons.Default.Delete, stringResource(R.string.action_clear), tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.logs_empty), color = Color.Gray, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(logs) { line ->
                    val color = when {
                        line.contains("ERROR") || line.contains("FATAL") -> Color(0xFFEF5350)
                        line.contains("WARN") -> Color(0xFFFFA726)
                        line.contains("DEBUG") || line.contains("TRACE") -> Color(0xFF666666)
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
