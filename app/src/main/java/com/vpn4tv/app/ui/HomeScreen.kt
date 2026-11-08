package com.vpn4tv.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.preferKeepClear
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.bg.ServiceError
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.converter.SubscriptionUserInfo
import com.vpn4tv.app.database.Profile
import com.vpn4tv.app.ui.theme.LocalStateColors
import com.vpn4tv.app.ui.theme.TvBusyContainer
import androidx.tv.material3.Icon as TvIcon
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import com.vpn4tv.app.utils.CommandClient
import io.nekohasekai.libbox.OutboundGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vpn4tv.app.database.Settings as AppSettings

/** What both Home layouts show; the TV and phone layouts only arrange it differently. */
private class HomeModel(
    val status: Status,
    val ready: Boolean,
    val lastError: ServiceError?,
    /** Android's always-on VPN runs this one: no Disconnect, a way to Android's VPN settings instead. */
    val alwaysOn: BoxService.AlwaysOnVpn?,
    val activeServer: String?,
    val activeDelay: Int,
    val sessionBytes: Long,
    val profile: Profile?,
    val info: SubscriptionUserInfo?,
    val updating: Boolean,
    val onRefresh: () -> Unit,
    /** Not one key in the app: on TV, Home is the add-key screen then. */
    val noKeys: Boolean = false,
) {
    val expired: Boolean
        get() = info?.expireEpochSec?.let { it > 0 && it * 1000 < System.currentTimeMillis() } == true
    val ownSubscription: Boolean
        get() = profile?.typed?.remoteURL?.contains("api.vpn4tv.com") == true
}

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
    onManualImport: () -> Unit = {},
    onLanImport: () -> Unit = {},
) {
    val status by BoxService.globalStatus.observeAsState(Status.Stopped)
    val lastError by BoxService.lastError.observeAsState(null)
    val alwaysOn by BoxService.alwaysOn.observeAsState(null)
    val ready by MainActivity.profileReady.observeAsState(false)
    val refreshed by MainActivity.profileRefreshed.observeAsState(0)
    // A link or a file opened with the app became a key: read again.
    val imported by MainActivity.profileImported.observeAsState(0)
    val scope = rememberCoroutineScope()

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
                    val selector = newGroups.firstOrNull { it.type == "selector" } ?: return
                    var selected = selector.selected
                    // If selected is "auto", find urltest's selected
                    val urltest = newGroups.firstOrNull { it.type == "urltest" }
                    if (selected == "auto" && urltest != null) selected = urltest.selected
                    activeServer = selected
                    var delay = 0
                    for (group in listOfNotNull(selector, urltest)) {
                        val items = group.items
                        while (items.hasNext()) {
                            val item = items.next()
                            if (item.tag == selected) { delay = item.urlTestDelay.toInt(); break }
                        }
                        if (delay != 0) break
                    }
                    activeDelay = delay
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
    DisposableEffect(Unit) { onDispose { commandClient.disconnect() } }

    var activeProfile by remember { mutableStateOf<Profile?>(null) }
    var isUpdating by remember { mutableStateOf(false) }
    var subscriptionInfo by remember { mutableStateOf<SubscriptionUserInfo?>(null) }
    // Whether there is no key at all, once read here; until then what was seen
    // last (at launch, or by the Home before this one), so a TV opened without
    // a key shows the add-key content from its first frame.
    var noKeysRead by remember { mutableStateOf<Boolean?>(null) }
    // Bumped when the TV's Home added a key itself: read the key again.
    var reloads by remember { mutableIntStateOf(0) }

    // Refresh the displayed profile every time HomeScreen resumes — this
    // catches the "user just added a new subscription" case, where
    // AddProfileScreen bumps Settings.selectedProfile and pops back here.
    // Without the lifecycle observer the name stays stale until process
    // restart. A new observer is told ON_RESUME at once, so the refresh
    // at launch finishing ([refreshed]) re-reads it too.
    val homeLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(homeLifecycleOwner, ready, refreshed, imported, reloads) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && ready) {
                scope.launch(Dispatchers.IO) {
                    val id = AppSettings.selectedProfile
                    val profile = if (id != -1L) com.vpn4tv.app.database.ProfileManager.get(id) else null
                    activeProfile = profile
                    subscriptionInfo = if (id != -1L) {
                        com.vpn4tv.app.converter.HwidService.loadUserInfo(com.vpn4tv.app.Application.application, id)
                    } else null
                    val none = profile == null && com.vpn4tv.app.database.ProfileManager.list().isEmpty()
                    MainActivity.noKeysSeen = none
                    noKeysRead = none
                }
            }
        }
        homeLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { homeLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    val refresh: () -> Unit = refresh@{
        val p = activeProfile ?: return@refresh
        if (isUpdating) return@refresh
        isUpdating = true
        GlobalScope.launch(Dispatchers.IO) {
            var error: ServiceError? = null
            try {
                if (p.typed.remoteURL.isNotEmpty()) {
                    val outcome = com.vpn4tv.app.converter.SubscriptionUpdater.update(
                        com.vpn4tv.app.Application.application, p,
                    )
                    activeProfile = p
                    subscriptionInfo = outcome.userInfo
                    // New keys go live now, not at the next connect.
                    com.vpn4tv.app.converter.SubscriptionUpdater.applyToRunningService(p, outcome)
                }
            } catch (e: Exception) {
                error = ServiceError.keyUpdate(e)
            }
            // Push refresh failure into the shared lastError LiveData so the
            // existing red line under the connect button surfaces it — no
            // Toast, no separate banner. Clear on success.
            BoxService.lastError.postValue(error)
            withContext(Dispatchers.Main) { isUpdating = false }
        }
    }

    val model = HomeModel(
        status = status, ready = ready, lastError = lastError, alwaysOn = alwaysOn,
        activeServer = activeServer, activeDelay = activeDelay,
        sessionBytes = sessionUplink + sessionDownlink,
        profile = activeProfile, info = subscriptionInfo, updating = isUpdating,
        onRefresh = refresh,
        noKeys = ready && (noKeysRead ?: MainActivity.noKeysSeen),
    )
    val connectAction = {
        when (status) {
            Status.Stopped -> if (ready) onConnect()
            Status.Started -> onDisconnect()
            // Starting/Stopping: the button stays enabled so the focus stays
            // on it (a disabled control drops the focus, which jumped to the
            // refresh button and the next OK refreshed the key); it just does
            // nothing until the service settles.
            else -> {}
        }
    }

    if (Device.isTv) {
        TvHome(model, connectAction, onNavigateServers, onNavigateLogs, onNavigateProfiles,
            onNavigateSettings, onNavigateAbout, onAddProfile, onLanImport,
            onKeyAdded = { reloads++ })
    } else {
        PhoneHome(model, connectAction, onNavigateServers, onAddProfile)
    }
}

// ---------------------------------------------------------------- TV

@Composable
private fun TvHome(
    m: HomeModel,
    onConnectButton: () -> Unit,
    onServers: () -> Unit,
    onLogs: () -> Unit,
    onProfiles: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onAddProfile: () -> Unit,
    onLanImport: () -> Unit,
    onKeyAdded: () -> Unit,
) {
    val connectFocus = remember { FocusRequester() }
    // No key: the add-key code in its place.
    val codeFocus = remember { FocusRequester() }
    // What opened another screen: the five toolbar icons, the server card,
    // the Telegram link. Kept with Home's back-stack entry.
    val openerFocus = remember { List(7) { FocusRequester() } }
    var openedFrom by rememberSaveable { mutableIntStateOf(-1) }
    fun opener(index: Int, open: () -> Unit): () -> Unit = { openedFrom = index; open() }
    var focusPlaced by remember { mutableStateOf(false) }
    // Only the control we want counts: the system itself may put the focus
    // on the first icon before our request lands (Android 9 boxes do).
    fun openerFocusModifier(index: Int) = Modifier.focusRequester(openerFocus[index])
        .onFocusChanged { if (it.hasFocus && openedFrom == index) focusPlaced = true }
    // A key added right here: Connect takes the focus, not the icon that
    // opened a screen before.
    var keysBefore by remember { mutableStateOf(m.noKeys) }
    // The TV navigation guide: something actionable is in focus from the
    // start, Connect (without a key, the code to send to the bot), and Back
    // lands on the control that opened the screen. Home is ready almost at
    // once now (the subscription refresh runs behind it), and a request made
    // during Home's enter transition is dropped, which left nothing in focus;
    // ask again until it holds, for a few seconds. The server card may be
    // gone by then (disconnected meanwhile): after a second, Connect instead.
    LaunchedEffect(m.ready, m.noKeys) {
        if (!m.ready) return@LaunchedEffect
        if (keysBefore != m.noKeys) {
            keysBefore = m.noKeys
            openedFrom = -1
            focusPlaced = false
        }
        val home = if (m.noKeys) codeFocus else connectFocus
        val target = openerFocus.getOrNull(openedFrom) ?: home
        repeat(15) { attempt ->
            kotlinx.coroutines.delay(200)
            if (focusPlaced) return@LaunchedEffect
            runCatching { (if (attempt < 5) target else home).requestFocus() }
        }
    }
    val colors = TvMaterialTheme.colorScheme
    val type = TvMaterialTheme.typography
    Column(
        Modifier.fillMaxSize().padding(horizontal = TvSafeArea.horizontal, vertical = TvSafeArea.vertical),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrokenInstallBanner()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TvText("VPN4TV", style = type.headlineSmall, fontWeight = FontWeight.Bold, color = colors.onBackground)
                if (m.profile != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TvText(
                            m.profile.name, style = type.bodyMedium, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        RefreshButton(m, size = 36.dp, iconSize = 20.dp)
                    }
                    SubscriptionLine(m, 14.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvToolbarIcon(Icons.Default.List, stringResource(R.string.nav_servers), openerFocusModifier(0), opener(0, onServers))
                TvToolbarIcon(Icons.Default.Description, stringResource(R.string.title_logs), openerFocusModifier(1), opener(1, onLogs))
                TvToolbarIcon(Icons.Default.FolderOpen, stringResource(R.string.nav_profiles), openerFocusModifier(2), opener(2, onProfiles))
                TvToolbarIcon(Icons.Default.Settings, stringResource(R.string.title_settings), openerFocusModifier(3), opener(3, onSettings))
                TvToolbarIcon(Icons.Default.Info, stringResource(R.string.title_about), openerFocusModifier(4), opener(4, onAbout))
            }
        }
        if (m.noKeys) {
            // No key at all (the first launch): Home is the add-key screen,
            // the first screen and the last before the TV's home screen
            // (Google's TV navigation, "Fixed start destination"), instead of
            // an add-key screen over a Home whose Connect could do nothing.
            // The icons above stay: Settings imports a key of one's own.
            // In the middle of the room below the icons; a large font that
            // makes it taller than that scrolls it with the focus.
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                AddKeyContent(
                    onProfileAdded = onKeyAdded,
                    onLanImport = onLanImport,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight),
                    verticalArrangement = Arrangement.Center,
                    codeFocus = codeFocus,
                    onCodeFocused = { if (openedFrom == -1) focusPlaced = true },
                )
            }
        } else {
            TvHomeCenter(m, onConnectButton, connectFocus, onServers = opener(5, onServers),
                serverCardModifier = openerFocusModifier(5), onConnectFocused = { focusPlaced = true })
            TvTextButton(onClick = opener(6, onAddProfile), modifier = openerFocusModifier(6), focusedScale = 1.05f) {
                TvText(stringResource(R.string.link_add_telegram), style = type.titleSmall)
            }
        }
    }
}

/** TV Home with a key: the status, Connect, the server and the session's traffic. */
@Composable
private fun ColumnScope.TvHomeCenter(
    m: HomeModel,
    onConnectButton: () -> Unit,
    connectFocus: FocusRequester,
    onServers: () -> Unit,
    serverCardModifier: Modifier,
    onConnectFocused: () -> Unit,
) {
    // Centred while it fits; at the largest font with the proxy card and the
    // error's details open it scrolls, and the remote's focus brings the
    // controls into view, instead of the bottom being cut off.
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A picture-in-picture window (a video kept playing while the
            // server is changed) is placed off the status and Connect
            // (Android 13+: keep-clear areas).
            StatusText(m, 32.sp, Modifier.preferKeepClear())
            ErrorLine(m, 14.sp)
            Spacer(Modifier.height(32.dp))
            // At least 300 × 80, larger with a large font (the TV
            // accessibility guide: components still fit at larger text scales).
            ConnectButton(
                m, onConnectButton,
                Modifier.preferKeepClear()
                    .focusRequester(connectFocus)
                    .onFocusChanged { if (it.hasFocus) onConnectFocused() },
                24.sp,
            )
            Spacer(Modifier.height(32.dp))
            ProxyModeCard(m)
            ServerCard(m, onServers, 18.sp, serverCardModifier)
            SessionLine(m, 16.sp)
        }
    }
}

/** A TV toolbar icon: 48 dp, the shared focus mark, its name for TalkBack. */
@Composable
private fun TvToolbarIcon(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    TvIconButton(onClick = onClick, modifier = modifier, size = 48.dp) {
        TvIcon(icon, label, modifier = Modifier.size(26.dp))
    }
}

// ---------------------------------------------------------------- phone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneHome(
    m: HomeModel,
    onConnectButton: () -> Unit,
    onServers: () -> Unit,
    onAddProfile: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VPN4TV", fontWeight = FontWeight.Bold)
                        if (m.profile != null) {
                            Text(
                                m.profile.name, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = { if (m.profile != null) RefreshButton(m, size = 48.dp, iconSize = 24.dp) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // A foldable open like a book or as a tent: the whole column in one
        // half, the Connect button off the hinge.
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).offHinge()) {
            // The status and Connect in the middle, the key's line at the top,
            // "add a key" at the bottom. A window too low for all of it (a phone
            // on its side, connected) scrolls instead of cutting it off.
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    BrokenInstallBanner()
                    if (m.profile != null) SubscriptionLine(m, 14.sp)
                }
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StatusText(m, 28.sp, Modifier.preferKeepClear())
                    ErrorLine(m, 14.sp)
                    Spacer(Modifier.height(32.dp))
                    // Three quarters of a phone's width, no wider than 400 dp
                    // on a tablet (Google: no full-width stretch of buttons).
                    // wrapContentWidth first: a size after a fill can only
                    // narrow it once the fill's tight width is let go.
                    val buttonModifier = Modifier.fillMaxWidth(0.75f).wrapContentWidth()
                        .widthIn(max = ContentWidth.button).fillMaxWidth().height(56.dp)
                    if (m.status == Status.Started && m.alwaysOn != null) {
                        AlwaysOnControls(m.alwaysOn, buttonModifier)
                    } else {
                        ConnectButton(m, onConnectButton, buttonModifier.preferKeepClear(), 18.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                    ProxyModeCard(m)
                    ServerCard(m, onServers, 16.sp)
                    SessionLine(m, 14.sp)
                }
                TextButton(onClick = onAddProfile, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(stringResource(R.string.link_add_telegram), fontSize = 16.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shared parts

/**
 * Updates the key. For TalkBack it names the key, and on TV, where the key's
 * name and term above are text the focus never reaches, says the term too.
 */
@Composable
private fun RefreshButton(m: HomeModel, size: Dp, iconSize: Dp) {
    val name = m.profile?.name
    val label = if (name != null) stringResource(R.string.action_update_key_named, name)
        else stringResource(R.string.action_update_subscription)
    val term = if (Device.isTv) subscriptionText(m) else null
    if (Device.isTv) {
        TvIconButton(onClick = m.onRefresh, size = size, contentColor = TvMaterialTheme.colorScheme.onSurfaceVariant) {
            if (m.updating) {
                CircularProgressIndicator(modifier = Modifier.size(iconSize - 4.dp), strokeWidth = 2.dp)
            } else {
                TvIcon(Icons.Default.Refresh, if (term != null) "$label. $term" else label, modifier = Modifier.size(iconSize))
            }
        }
        return
    }
    IconButton(onClick = m.onRefresh, modifier = Modifier.size(size)) {
        if (m.updating) {
            CircularProgressIndicator(modifier = Modifier.size(iconSize - 4.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.Refresh, if (term != null) "$label. $term" else label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * Subscription traffic / expiry from the `subscription-userinfo` header. For
 * api.vpn4tv.com (our own, unlimited traffic) only the expiry date; for
 * third-party providers both. "Expired" in the danger colour regardless.
 */
@Composable
private fun SubscriptionLine(m: HomeModel, size: TextUnit) {
    val text = subscriptionText(m) ?: return
    val color = if (m.expired) LocalStateColors.current.danger else MaterialTheme.colorScheme.onSurfaceVariant
    if (Device.isTv) {
        TvText(text, style = TvMaterialTheme.typography.labelSmall, color = color)
        return
    }
    Text(text, fontSize = size, color = color)
}

/** The key's traffic and term as [SubscriptionLine] shows them, or null. */
@Composable
private fun subscriptionText(m: HomeModel): String? {
    val info = m.info ?: return null
    val parts = mutableListOf<String>()
    if (!m.ownSubscription && (info.download != null || info.upload != null)) {
        val used = formatBytesLocalized((info.download ?: 0) + (info.upload ?: 0))
        parts.add(if (info.total != null && info.total > 0) "$used / ${formatBytesLocalized(info.total)}" else used)
    }
    if (info.expireEpochSec != null && info.expireEpochSec > 0) {
        if (m.expired) {
            parts.add(stringResource(R.string.subscription_expired))
        } else {
            val sdf = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
            parts.add(stringResource(R.string.subscription_until, sdf.format(java.util.Date(info.expireEpochSec * 1000))))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun StatusText(m: HomeModel, size: TextUnit, modifier: Modifier = Modifier) {
    val state = LocalStateColors.current
    val text = when {
        !m.ready -> stringResource(R.string.status_loading)
        m.status == Status.Started -> stringResource(R.string.status_connected)
        m.status == Status.Starting -> stringResource(R.string.status_connecting)
        m.status == Status.Stopping -> stringResource(R.string.status_disconnecting)
        else -> stringResource(R.string.status_disconnected)
    }
    val color = when {
        !m.ready -> MaterialTheme.colorScheme.onSurfaceVariant
        m.status == Status.Started -> state.connected
        m.status == Status.Starting || m.status == Status.Stopping -> state.busy
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // TalkBack says "Connected" / "Disconnected" itself, politely, as the
    // state changes (Android 16 deprecates announceForAccessibility).
    val announced = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (Device.isTv) {
        TvText(text, style = TvMaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = color, modifier = announced)
    } else {
        Text(text, fontSize = size, fontWeight = FontWeight.Bold, color = color, modifier = announced)
    }
}

/**
 * Why the last start failed or the VPN stopped by itself, under the status:
 * what happened and what to do; the technical text for support under
 * «Подробнее» (selectable on a phone, to paste into a message).
 */
@Composable
private fun ErrorLine(m: HomeModel, size: TextUnit) {
    val error = m.lastError
    if (m.status != Status.Stopped || error == null) return
    var detailShown by remember(error) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    // Phone and tablet: lines of a readable length on a wide window. TV: as before.
    val lineWidth = if (Device.isTv) Modifier else Modifier.widthIn(max = 560.dp)
    Spacer(Modifier.height(8.dp))
    // TalkBack reads it out as it appears.
    val reasonModifier = Modifier.padding(horizontal = 32.dp).then(lineWidth)
        .semantics { liveRegion = LiveRegionMode.Polite }
    val detail = error.detail
    val detailLabel = stringResource(if (detailShown) R.string.action_hide_details else R.string.action_details)
    if (Device.isTv) {
        val type = TvMaterialTheme.typography
        TvText(
            stringResource(error.reason.message), style = type.bodySmall,
            color = LocalStateColors.current.danger, textAlign = TextAlign.Center, modifier = reasonModifier,
        )
        if (detail.isNullOrBlank()) return
        TvTextButton(onClick = { detailShown = !detailShown }) { TvText(detailLabel, style = type.bodySmall) }
        if (detailShown) {
            TvText(
                detail, style = type.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        return
    }
    Text(
        stringResource(error.reason.message),
        fontSize = size,
        color = LocalStateColors.current.danger,
        textAlign = TextAlign.Center,
        modifier = reasonModifier,
    )
    if (detail.isNullOrBlank()) return
    TextButton(onClick = { detailShown = !detailShown }) {
        Text(detailLabel, fontSize = size)
    }
    if (detailShown) {
        val text = @Composable {
            Text(
                detail,
                fontSize = size * 0.9f,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp).then(lineWidth),
            )
        }
        SelectionContainer { text() }
    }
}

/**
 * Always-on VPN (phone): Android starts and stops it, so instead of
 * Disconnect, which the system would undo or which would leave the phone
 * without internet under "block connections without VPN", a word on who is
 * in charge and the way to Android's VPN settings (Google's VPN guide).
 */
@Composable
private fun AlwaysOnControls(alwaysOn: BoxService.AlwaysOnVpn, buttonModifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Text(
        stringResource(if (alwaysOn.lockdown) R.string.always_on_note_lockdown else R.string.always_on_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp).widthIn(max = 560.dp),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = { openVpnSettings(context) }, modifier = buttonModifier) {
        Text(stringResource(R.string.action_vpn_settings), fontSize = 18.sp)
    }
}

@Composable
private fun ConnectButton(m: HomeModel, onClick: () -> Unit, modifier: Modifier, size: TextUnit) {
    val colors = MaterialTheme.colorScheme
    val busy = !m.ready || m.status == Status.Starting || m.status == Status.Stopping
    // TalkBack names what the button does ("… to connect the VPN") instead
    // of the generic "activate"; while busy it does nothing, no name then.
    val actionLabel = when {
        busy -> null
        m.status == Status.Stopped -> stringResource(R.string.a11y_connect_vpn)
        m.status == Status.Started -> stringResource(R.string.a11y_disconnect_vpn)
        else -> null
    }
    val label = when {
        !m.ready -> stringResource(R.string.status_loading)
        m.status == Status.Stopped -> stringResource(R.string.action_connect)
        m.status == Status.Started -> stringResource(R.string.action_disconnect)
        m.status == Status.Starting -> stringResource(R.string.status_connecting)
        m.status == Status.Stopping -> stringResource(R.string.status_disconnecting)
        else -> ""
    }
    if (Device.isTv) {
        val tv = TvMaterialTheme.colorScheme
        TvButton(
            onClick = onClick,
            // The label only; the button's own click stays the action.
            modifier = modifier.semantics { if (actionLabel != null) onClick(label = actionLabel, action = null) },
            containerColor = when {
                m.status == Status.Started -> tv.error
                busy -> TvBusyContainer
                else -> tv.primary
            },
            contentColor = when {
                m.status == Status.Started -> tv.onError
                busy -> tv.onSurface
                else -> tv.onPrimary
            },
            minWidth = 300.dp,
            minHeight = 80.dp,
        ) {
            TvText(label, style = TvMaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        }
        return
    }
    Button(
        onClick = onClick,
        // The label only; the button's own click stays the action.
        modifier = modifier.semantics { if (actionLabel != null) onClick(label = actionLabel, action = null) },
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                m.status == Status.Started -> colors.error
                busy -> colors.surfaceContainerHighest
                else -> colors.primary
            },
            contentColor = when {
                m.status == Status.Started -> colors.onError
                busy -> colors.onSurface
                else -> colors.onPrimary
            },
        ),
    ) {
        Text(text = label, fontSize = size)
    }
}

/**
 * Proxy-mode banner. When the user runs sing-box without a TUN (SberBox-class
 * devices), they need to know the loopback address SmartTube / other apps
 * should be pointed at.
 */
@Composable
private fun ProxyModeCard(m: HomeModel) {
    if (m.status != Status.Started || !AppSettings.isProxyMode) return
    val colors = MaterialTheme.colorScheme
    val address = "SOCKS5 127.0.0.1:${com.vpn4tv.app.constant.ServiceMode.PROXY_PORT}"
    if (Device.isTv) {
        val type = TvMaterialTheme.typography
        TvPanel(containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                TvText(stringResource(R.string.proxy_mode_card_title), style = type.labelSmall)
                TvText(address, style = type.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                TvText(stringResource(R.string.proxy_mode_card_hint), style = type.labelSmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        return
    }
    Card(colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(stringResource(R.string.proxy_mode_card_title), fontSize = 14.sp, color = colors.onSecondaryContainer)
            Text(address, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onSecondaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.proxy_mode_card_hint), fontSize = 13.sp, color = colors.onSecondaryContainer)
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** Active server, opens the server list. */
@Composable
private fun ServerCard(m: HomeModel, onClick: () -> Unit, size: TextUnit, modifier: Modifier = Modifier) {
    if (m.status != Status.Started || m.activeServer.isNullOrEmpty()) return
    val colors = MaterialTheme.colorScheme
    val chooseServer = stringResource(R.string.a11y_choose_server)
    val withLabel = modifier.semantics { onClick(label = chooseServer, action = null) }
    if (Device.isTv) {
        val type = TvMaterialTheme.typography
        TvCard(onClick = onClick, modifier = withLabel) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvText(stringResource(R.string.label_server) + " ", style = type.titleSmall, color = colors.onSurfaceVariant)
                TvText(m.activeServer, style = type.titleSmall, fontWeight = FontWeight.Bold, color = colors.onSurface)
                if (m.activeDelay > 0) {
                    Spacer(Modifier.width(12.dp))
                    TvText("${m.activeDelay} ${stringResource(R.string.unit_ms)}", style = type.titleSmall, color = delayColor(m.activeDelay))
                }
                Spacer(Modifier.width(8.dp))
                // Only a sign that the card opens the list: TalkBack skips it.
                TvText("›", style = type.titleLarge, color = colors.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        return
    }
    Card(
        onClick = onClick,
        modifier = withLabel,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.label_server) + " ", fontSize = size, color = colors.onSurfaceVariant)
            Text(m.activeServer, fontSize = size, fontWeight = FontWeight.Bold, color = colors.onSurface)
            if (m.activeDelay > 0) {
                Spacer(Modifier.width(12.dp))
                Text("${m.activeDelay} ${stringResource(R.string.unit_ms)}", fontSize = size, color = delayColor(m.activeDelay))
            }
            Spacer(Modifier.width(8.dp))
            // Only a sign that the card opens the list: TalkBack skips it.
            Text("›", fontSize = size * 1.25f, color = colors.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** How fast a server answered: green, yellow, orange. */
@Composable
private fun delayColor(delay: Int): Color {
    val state = LocalStateColors.current
    return when {
        delay < 300 -> state.fast
        delay < 600 -> state.medium
        else -> state.slow
    }
}

@Composable
private fun SessionLine(m: HomeModel, size: TextUnit) {
    if (m.status != Status.Started || m.sessionBytes <= 0) return
    Spacer(Modifier.height(8.dp))
    val text = "${stringResource(R.string.session_traffic)}: ${formatBytesLocalized(m.sessionBytes)}"
    if (Device.isTv) {
        TvText(text, style = TvMaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(text, fontSize = size, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Broken-install banner: the native lib never loaded (Play per-ABI split
 * misdelivery on some TV firmware). Persistent + proactive — guides the user
 * to reinstall from Google Play, the sanctioned update channel. A phone falls
 * back to Play's web page where the Play app can't resolve market://; a TV
 * opens no browser (Google's TV-WB) and says what to do instead.
 */
@Composable
private fun BrokenInstallBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val broken = remember { com.vpn4tv.app.utils.NativeLib.isBroken(context) }
    if (!broken) return
    var noPlay by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val reinstall: () -> Unit = {
        val pkg = context.packageName
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=$pkg"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            if (Device.isTv) {
                noPlay = true
            } else runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    if (Device.isTv) {
        val type = TvMaterialTheme.typography
        TvPanel(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            containerColor = colors.errorContainer, contentColor = colors.onErrorContainer,
        ) {
            Column(Modifier.padding(16.dp)) {
                TvText(stringResource(R.string.install_broken_banner), style = type.labelSmall)
                TvButton(onClick = reinstall, modifier = Modifier.padding(top = 10.dp)) {
                    TvText(stringResource(R.string.install_broken_button))
                }
                if (noPlay) {
                    TvText(
                        stringResource(R.string.install_broken_no_play), style = type.labelSmall,
                        modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.install_broken_banner), fontSize = 14.sp, color = colors.onErrorContainer)
            Button(onClick = reinstall, modifier = Modifier.padding(top = 10.dp)) {
                Text(stringResource(R.string.install_broken_button))
            }
            if (noPlay) {
                Text(
                    stringResource(R.string.install_broken_no_play),
                    fontSize = 14.sp, color = colors.onErrorContainer,
                    modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
