package com.vpn4tv.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.ui.theme.LocalStateColors
import com.vpn4tv.app.utils.CommandClient
import com.vpn4tv.app.utils.LogPrivacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.tv.material3.Icon as TvIcon
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText

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

/** The most lines the screen keeps; older ones drop off the top. */
internal const val LOG_LINES_KEPT = 500

/**
 * The lines of the screen. [all] is everything that came, the newest
 * [LOG_LINES_KEPT]: it keeps growing during a pause, and Share sends it.
 * [frozen] is what the list held when the pause began; while it is set the
 * list stands still, as on the iPhone, and Resume shows all that came since.
 */
internal class LogFeed(
    val all: List<String> = emptyList(),
    private val frozen: List<String>? = null,
) {
    val paused: Boolean get() = frozen != null
    val shown: List<String> get() = frozen ?: all

    fun append(lines: List<String>) = LogFeed((all + lines).takeLast(LOG_LINES_KEPT), frozen)
    fun pause() = if (paused) this else LogFeed(all, all)
    fun resume() = LogFeed(all)
}

/** The lines at [minPriority] or above; all of them for 0. */
internal fun linesAtLevel(lines: List<String>, minPriority: Int): List<String> =
    if (minPriority == 0) lines else lines.filter { logLineLevel(it) >= minPriority }

/** The lines that hold [query] in any case; all of them for an empty query. */
internal fun linesWith(lines: List<String>, query: String): List<String> =
    if (query.isEmpty()) lines else lines.filter { it.contains(query, ignoreCase = true) }

/** Where [query] occurs in [line], in any case, left to right: what the list marks. */
internal fun matchesIn(line: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val found = mutableListOf<IntRange>()
    var from = 0
    while (true) {
        val at = line.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        found.add(at until at + query.length)
        from = at + query.length
    }
    return found
}

/** [onBack] null: shown beside the settings list on a wide window, no Back arrow. */
@Composable
fun LogsScreen(onBack: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf(LogFeed()) }
    // Default to WARN+: sing-box at INFO is noisy (several lines per
    // connection) and users opening Logs are almost always chasing a
    // problem, not reviewing steady-state traffic.
    var filterLevel by rememberSaveable { mutableStateOf(LogLevel.WARN) }
    // Phone only: typing with a TV remote is a chore, so TV has no search.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // What the list is filtered by: the query once typing stops for 300 ms,
    // as on the iPhone; an emptied query at once.
    var appliedQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) delay(300)
        appliedQuery = query
    }
    val listState = rememberLazyListState()
    val status by BoxService.globalStatus.observeAsState(Status.Stopped)

    val atLevel = remember(feed.shown, filterLevel) { linesAtLevel(feed.shown, filterLevel.priority) }
    val found = remember(atLevel, appliedQuery) { linesWith(atLevel, appliedQuery) }

    val commandClient = remember {
        CommandClient(
            scope,
            listOf(CommandClient.ConnectionType.Log),
            object : CommandClient.Handler {
                override fun appendLogs(messages: List<String>) {
                    // The core's error lines name the site that failed: the
                    // list and Share get them without it (LogPrivacy).
                    val lines = messages.map(LogPrivacy::redactCoreLine)
                    // Called on libbox's thread: the lines are added on the
                    // main one, where Pause and Clear change the feed too,
                    // so neither is undone by a batch arriving meanwhile.
                    scope.launch { feed = feed.append(lines) }
                }
            }
        )
    }

    // New lines are followed only while the list is at its end, as on the
    // iPhone: a drag up to read stops it, a drag back to the end (or Resume)
    // starts it again. A pause stops the scrolling as well.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> follow = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> follow = !listState.canScrollForward
            }
        }
    }
    LaunchedEffect(found.size, feed.paused) {
        if (!feed.paused && follow && found.isNotEmpty()) listState.animateScrollToItem(found.size - 1)
    }

    DisposableEffect(Unit) {
        commandClient.connect()
        onDispose { commandClient.disconnect() }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() {
        keyboard?.hide()
        searchOpen = false
        query = ""
        appliedQuery = ""
    }
    // Back closes the search first, then the screen.
    BackHandler(enabled = searchOpen) { closeSearch() }

    val colors = MaterialTheme.colorScheme
    val state = LocalStateColors.current
    val context = LocalContext.current
    ScreenScaffold(
        title = stringResource(R.string.title_logs),
        onBack = onBack,
        collapsingTopBar = true,
        actions = {
            if (!Device.isTv) {
                IconButton(onClick = { if (searchOpen) closeSearch() else searchOpen = true }) {
                    if (searchOpen) Icon(Icons.Default.SearchOff, stringResource(R.string.close_search))
                    else Icon(Icons.Default.Search, stringResource(R.string.search))
                }
            }
            // The same button in both states, so the TV focus stays on it.
            val pauseOrResume = {
                if (feed.paused) follow = true
                feed = if (feed.paused) feed.resume() else feed.pause()
            }
            val pauseIcon = if (feed.paused) Icons.Default.PlayArrow else Icons.Default.Pause
            val pauseLabel = stringResource(
                if (feed.paused) R.string.content_description_resume_logs else R.string.content_description_pause_logs,
            )
            if (Device.isTv) {
                TvIconButton(onClick = pauseOrResume) { TvIcon(pauseIcon, pauseLabel) }
            } else {
                IconButton(onClick = pauseOrResume) { Icon(pauseIcon, pauseLabel) }
            }
            // Phone: hand the log to Telegram, mail, Files… for support.
            // All the lines that came, whatever the list shows.
            if (!Device.isTv) {
                IconButton(onClick = { shareLogs(context, feed.all) }, enabled = feed.all.isNotEmpty()) {
                    Icon(Icons.Default.Share, stringResource(R.string.action_share_logs))
                }
            }
            // Clear ends a pause too, and empties the core's own buffer, which
            // otherwise comes back on the next visit (as Clear on the iPhone).
            val clear = {
                feed = LogFeed()
                follow = true
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        val client = io.nekohasekai.libbox.Libbox.newStandaloneCommandClient()
                        client.clearLogs()
                        client.disconnect()
                    }
                }
                Unit
            }
            if (Device.isTv) {
                TvIconButton(onClick = clear) { TvIcon(Icons.Default.Delete, stringResource(R.string.action_clear)) }
            } else {
                IconButton(onClick = clear) { Icon(Icons.Default.Delete, stringResource(R.string.action_clear)) }
            }
        },
    ) { padding ->
        // Phone: the list ends above the keyboard, so the newest matches stay
        // in sight while the query is typed.
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .padding(horizontal = if (Device.isTv) 0.dp else 16.dp),
        ) {
            if (searchOpen) {
                val searchFocus = remember { FocusRequester() }
                // Opened: the field takes the focus, so the keyboard is up.
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                val clearQuery: @Composable () -> Unit = {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, stringResource(R.string.content_description_clear_search))
                    }
                }
                val hideKeyboard = KeyboardActions(
                    onSearch = { appliedQuery = query; keyboard?.hide() },
                    onDone = { appliedQuery = query; keyboard?.hide() },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).focusRequester(searchFocus),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_logs_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (query.isEmpty()) null else clearQuery,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = hideKeyboard,
                )
            }
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
                    if (Device.isTv) {
                        val checkIcon: @Composable () -> Unit = { TvIcon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        TvFilterChip(
                            selected = selected,
                            onClick = { filterLevel = level },
                            leadingIcon = if (selected) checkIcon else null,
                        ) { TvText(stringResource(level.label)) }
                    } else {
                        val checkIcon: @Composable () -> Unit = { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        FilterChip(
                            selected = selected,
                            onClick = { filterLevel = level },
                            label = { Text(stringResource(level.label), fontSize = 14.sp) },
                            leadingIcon = if (selected) checkIcon else null,
                        )
                    }
                }
            }
            if (found.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val empty = when {
                        // The level shows lines, the query matches none of them.
                        appliedQuery.isNotEmpty() && atLevel.isNotEmpty() -> R.string.logs_nothing_found
                        // Connected: the filter hides what there is, or nothing came yet.
                        status == Status.Started -> R.string.logs_none_at_level
                        else -> R.string.logs_empty
                    }
                    if (Device.isTv) {
                        TvText(stringResource(empty), style = TvMaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    } else {
                        Text(stringResource(empty), color = colors.onSurfaceVariant, fontSize = 16.sp)
                    }
                }
            } else {
                val mark = SpanStyle(background = colors.primaryContainer, color = colors.onPrimaryContainer)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(found) { line ->
                        LogLine(
                            line,
                            appliedQuery,
                            mark,
                            // Phone: 12 sp, the smallest body text Google's
                            // accessibility guide allows.
                            fontSize = if (Device.isTv) 14.sp else 12.sp,
                            color = when (logLineLevel(line)) {
                                3 -> state.danger
                                2 -> state.slow
                                0 -> colors.onSurfaceVariant
                                else -> colors.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One line of the log; the matches of the search marked, as on the iPhone. */
@Composable
private fun LogLine(line: String, query: String, mark: SpanStyle, fontSize: TextUnit, color: Color) {
    val modifier = Modifier.padding(vertical = 1.dp)
    val matches = matchesIn(line, query)
    if (Device.isTv) {
        // TV: no search, so nothing to mark.
        TvText(line, modifier, color = color, style = TvMaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        return
    }
    if (matches.isEmpty()) {
        Text(line, modifier, color = color, fontSize = fontSize, fontFamily = FontFamily.Monospace)
    } else {
        val marked = buildAnnotatedString {
            append(line)
            matches.forEach { addStyle(mark, it.first, it.last + 1) }
        }
        Text(marked, modifier, color = color, fontSize = fontSize, fontFamily = FontFamily.Monospace)
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
