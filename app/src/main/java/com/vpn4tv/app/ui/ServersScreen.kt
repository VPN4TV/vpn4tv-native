package com.vpn4tv.app.ui

import android.content.Context
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.ui.theme.LocalStateColors
import com.vpn4tv.app.utils.CommandClient
import io.nekohasekai.libbox.OutboundGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Icon as TvIcon
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText

@Composable
fun ServersScreen(onBack: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var groups by remember { mutableStateOf<List<GroupData>>(emptyList()) }
    // The server each automatic group ("auto") is on now.
    var autoPicks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isTestRunning by remember { mutableStateOf(false) }
    // TV: the row in focus, whose country the background shows.
    var focusedTag by remember { mutableStateOf<String?>(null) }

    val commandClient = remember {
        CommandClient(
            scope,
            listOf(CommandClient.ConnectionType.Groups),
            object : CommandClient.Handler {
                override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
                    autoPicks = newGroups.filter { it.type == "urltest" }.associate { it.tag to it.selected }
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

    // The core's command server runs only with the VPN: connecting while the
    // VPN is off blocks the main thread for seconds (the screen also opens
    // from an app shortcut, VPN off). Without groups the screen asks to connect.
    val status by BoxService.globalStatus.observeAsState(Status.Stopped)
    LaunchedEffect(status) {
        if (status == Status.Started) {
            commandClient.connect()
        } else if (status == Status.Stopped) {
            commandClient.disconnect()
            groups = emptyList()
        }
    }
    DisposableEffect(Unit) { onDispose { commandClient.disconnect() } }

    val select: (GroupData, ServerItem) -> Unit = { group, server ->
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

    // TV: behind the list, the country of the row in focus (before any, of
    // the server in use); "auto" shows where it is connected now.
    if (Device.isTv) {
        val tag = focusedTag ?: groups.firstOrNull()?.selected
        val country = tag?.let { serverCountry(autoPicks[it] ?: it) }
        Box(Modifier.fillMaxSize()) {
            CountryBackdrop(country)
            ServersList(onBack, groups, isTestRunning, testAll, onFocused = { focusedTag = it }, onSelect = select)
        }
    } else {
        ServersList(onBack, groups, isTestRunning, testAll, onFocused = {}, onSelect = select)
    }
}

/** The list of servers under the screen's title; the refresh button tests them all. */
@Composable
private fun ServersList(
    onBack: (() -> Unit)?,
    groups: List<GroupData>,
    isTestRunning: Boolean,
    testAll: () -> Unit,
    onFocused: (String) -> Unit,
    onSelect: (GroupData, ServerItem) -> Unit,
) {
    ScreenScaffold(
        title = stringResource(R.string.title_servers),
        onBack = onBack,
        collapsingTopBar = true,
        actions = {
            // Nothing to test until the tunnel lists its servers.
            if (groups.isNotEmpty()) {
                val testing: @Composable () -> Unit = {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                if (Device.isTv) {
                    TvIconButton(onClick = testAll) {
                        if (isTestRunning) testing() else TvIcon(Icons.Default.Refresh, stringResource(R.string.action_test_all))
                    }
                } else {
                    IconButton(onClick = testAll) {
                        if (isTestRunning) testing() else Icon(Icons.Default.Refresh, stringResource(R.string.action_test_all))
                    }
                }
            }
        },
    ) { padding ->
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                val empty = stringResource(R.string.servers_empty)
                if (Device.isTv) {
                    TvText(empty, style = TvMaterialTheme.typography.bodyLarge, color = TvMaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                }
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
                        onFocused = { onFocused(server.tag) },
                        onClick = { onSelect(group, server) },
                    )
                }
            }
        }
    }
}

/**
 * The selected server carries a check mark; on TV the outline is the focus
 * only. For TalkBack a row is one of a set, as a radio button, on a phone and
 * on TV alike: the server, "selected" or not, what a press does.
 */
@Composable
private fun ServerRow(server: ServerItem, isSelected: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tv = Device.isTv
    val mark: @Composable () -> Unit = {
        Box(Modifier.width(32.dp)) {
            if (isSelected) {
                Icon(
                    // The row itself says "selected", the mark is only seen.
                    Icons.Default.Check, null,
                    tint = LocalStateColors.current.connected, modifier = Modifier.size(22.dp),
                )
            }
        }
    }
    if (tv) {
        // Compose for TV's list item: no container (the country behind shows
        // through), the grey plate and the outline in focus.
        val type = TvMaterialTheme.typography
        TvListItem(
            selected = isSelected,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFocused() }
                .semantics { role = Role.RadioButton },
            leadingContent = {
                if (isSelected) {
                    TvIcon(
                        Icons.Default.Check, null,
                        tint = LocalStateColors.current.connected, modifier = Modifier.size(22.dp),
                    )
                }
            },
            headlineContent = { TvText(server.tag, style = type.titleSmall) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvText(server.type, style = type.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
                    DelayText(server.delay, 16.sp)
                }
            },
        )
        return
    }
    ListItem(
        // One of the list, as a radio button: TalkBack says which server is
        // selected and what a tap does. No wider than a readable column.
        modifier = Modifier.fillMaxWidth().readableWidth(ContentWidth.list)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick),
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
    if (Device.isTv) TvText(text, style = TvMaterialTheme.typography.bodyMedium, color = color)
    else Text(text, fontSize = size, color = color)
}

/**
 * TV: the flag of a server's country, large and faint, behind the whole
 * screen; it changes as the focus moves from row to row (Google's TV app
 * checklist: "Change the background image as users browse through
 * content"). Nothing for a server whose name gives no country, or where the
 * font has no such flag. Only a picture: TalkBack skips it.
 */
@Composable
private fun CountryBackdrop(country: String?) {
    val flag = remember(country) { country?.let(::flagEmoji)?.takeIf(::canDrawEmoji) }
    Crossfade(targetState = flag, animationSpec = tween(durationMillis = 450), label = "countryBackdrop") { shown ->
        if (shown != null) {
            Box(
                Modifier.fillMaxSize().clearAndSetSemantics {},
                contentAlignment = Alignment.CenterEnd,
            ) {
                // In dp, whatever the font scale: a picture, not text to read.
                val size = with(LocalDensity.current) { BACKDROP_FLAG.toSp() }
                Text(
                    shown,
                    fontSize = size,
                    lineHeight = size,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.offset(x = BACKDROP_FLAG / 6)
                        .graphicsLayer { alpha = BACKDROP_ALPHA; compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // Its left side fades out: no edge drawn across the rows.
                            drawRect(
                                Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.Black),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }
        }
    }
}

private val BACKDROP_FLAG = 420.dp
private const val BACKDROP_ALPHA = 0.22f

/** Whether the device's fonts draw [emoji] as one picture (an old TV may lack a flag). */
private fun canDrawEmoji(emoji: String): Boolean =
    runCatching { android.graphics.Paint().hasGlyph(emoji) }.getOrDefault(false)

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
