package com.vpn4tv.app.ui

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.converter.SubscriptionUserInfo
import com.vpn4tv.app.database.Profile
import com.vpn4tv.app.ui.theme.LocalStateColors
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
    val lastError: String?,
    val activeServer: String?,
    val activeDelay: Int,
    val sessionBytes: Long,
    val profile: Profile?,
    val info: SubscriptionUserInfo?,
    val updating: Boolean,
    val onRefresh: () -> Unit,
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
) {
    val status by BoxService.globalStatus.observeAsState(Status.Stopped)
    val lastError by BoxService.lastError.observeAsState(null)
    val ready by MainActivity.profileReady.observeAsState(false)
    val refreshed by MainActivity.profileRefreshed.observeAsState(0)
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

    // Refresh the displayed profile every time HomeScreen resumes — this
    // catches the "user just added a new subscription" case, where
    // AddProfileScreen bumps Settings.selectedProfile and pops back here.
    // Without the lifecycle observer the name stays stale until process
    // restart. A new observer is told ON_RESUME at once, so the refresh
    // at launch finishing ([refreshed]) re-reads it too.
    val homeLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(homeLifecycleOwner, ready, refreshed) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && ready) {
                scope.launch(Dispatchers.IO) {
                    val id = AppSettings.selectedProfile
                    activeProfile = if (id != -1L) com.vpn4tv.app.database.ProfileManager.get(id) else null
                    subscriptionInfo = if (id != -1L) {
                        com.vpn4tv.app.converter.HwidService.loadUserInfo(com.vpn4tv.app.Application.application, id)
                    } else null
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
            var errorMsg: String? = null
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
                errorMsg = e.message ?: e.javaClass.simpleName
            }
            // Push refresh failure into the shared lastError LiveData so the
            // existing red line under the connect button surfaces it — no
            // Toast, no separate banner. Clear on success.
            BoxService.lastError.postValue(errorMsg?.let { com.vpn4tv.app.Application.application.getString(R.string.subscription_update_failed, it) })
            withContext(Dispatchers.Main) { isUpdating = false }
        }
    }

    val model = HomeModel(
        status = status, ready = ready, lastError = lastError,
        activeServer = activeServer, activeDelay = activeDelay,
        sessionBytes = sessionUplink + sessionDownlink,
        profile = activeProfile, info = subscriptionInfo, updating = isUpdating,
        onRefresh = refresh,
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
            onNavigateSettings, onNavigateAbout, onAddProfile)
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
) {
    val connectFocus = remember { FocusRequester() }
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
    // The TV navigation guide: something actionable is in focus from the
    // start, Connect, and Back lands on the control that opened the screen.
    // Home is ready almost at once now (the subscription refresh runs behind
    // it), and a request made during Home's enter transition is dropped,
    // which left nothing in focus; ask again until it holds, for a few
    // seconds. The server card may be gone by then (disconnected meanwhile):
    // after a second, Connect instead.
    LaunchedEffect(m.ready) {
        if (!m.ready) return@LaunchedEffect
        val target = openerFocus.getOrNull(openedFrom) ?: connectFocus
        repeat(15) { attempt ->
            kotlinx.coroutines.delay(200)
            if (focusPlaced) return@LaunchedEffect
            runCatching { (if (attempt < 5) target else connectFocus).requestFocus() }
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = TvSafeArea.horizontal, vertical = TvSafeArea.vertical),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrokenInstallBanner()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("VPN4TV", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                if (m.profile != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.profile.name, fontSize = 16.sp, color = colors.onSurfaceVariant,
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
                TvIconButton(Icons.Default.List, stringResource(R.string.nav_servers), openerFocusModifier(0), opener(0, onServers))
                TvIconButton(Icons.Default.Description, stringResource(R.string.title_logs), openerFocusModifier(1), opener(1, onLogs))
                TvIconButton(Icons.Default.FolderOpen, stringResource(R.string.nav_profiles), openerFocusModifier(2), opener(2, onProfiles))
                TvIconButton(Icons.Default.Settings, stringResource(R.string.title_settings), openerFocusModifier(3), opener(3, onSettings))
                TvIconButton(Icons.Default.Info, stringResource(R.string.title_about), openerFocusModifier(4), opener(4, onAbout))
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusText(m, 32.sp)
            ErrorLine(m, 14.sp)
            Spacer(Modifier.height(32.dp))
            ConnectButton(
                m, onConnectButton,
                Modifier.width(300.dp).height(80.dp).focusRequester(connectFocus)
                    .onFocusChanged { if (it.hasFocus) focusPlaced = true }.tvFocus(CircleShape),
                24.sp,
            )
            Spacer(Modifier.height(32.dp))
            ProxyModeCard(m)
            ServerCard(m, opener(5, onServers), 18.sp, openerFocusModifier(5))
            SessionLine(m, 16.sp)
        }
        TextButton(
            onClick = opener(6, onAddProfile),
            modifier = openerFocusModifier(6).tvFocus(CircleShape, 1.05f),
        ) {
            Text(stringResource(R.string.link_add_telegram), fontSize = 18.sp)
        }
    }
}

/** A TV toolbar icon: 48 dp, the shared focus mark, its name for TalkBack. */
@Composable
private fun TvIconButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).tvFocus(CircleShape)) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(26.dp))
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
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrokenInstallBanner()
            if (m.profile != null) {
                Box(Modifier.fillMaxWidth()) { SubscriptionLine(m, 14.sp) }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusText(m, 28.sp)
                ErrorLine(m, 14.sp)
                Spacer(Modifier.height(32.dp))
                ConnectButton(m, onConnectButton, Modifier.fillMaxWidth(0.75f).height(56.dp), 18.sp)
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

// ---------------------------------------------------------------- shared parts

@Composable
private fun RefreshButton(m: HomeModel, size: Dp, iconSize: Dp) {
    IconButton(onClick = m.onRefresh, modifier = Modifier.size(size).tvFocus(CircleShape)) {
        if (m.updating) {
            CircularProgressIndicator(modifier = Modifier.size(iconSize - 4.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.Refresh, stringResource(R.string.action_update_subscription),
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
    val info = m.info ?: return
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
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        fontSize = size,
        color = if (m.expired) LocalStateColors.current.danger else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusText(m: HomeModel, size: TextUnit) {
    val state = LocalStateColors.current
    Text(
        text = when {
            !m.ready -> stringResource(R.string.status_loading)
            m.status == Status.Started -> stringResource(R.string.status_connected)
            m.status == Status.Starting -> stringResource(R.string.status_connecting)
            m.status == Status.Stopping -> stringResource(R.string.status_disconnecting)
            else -> stringResource(R.string.status_disconnected)
        },
        fontSize = size,
        fontWeight = FontWeight.Bold,
        color = when {
            !m.ready -> MaterialTheme.colorScheme.onSurfaceVariant
            m.status == Status.Started -> state.connected
            m.status == Status.Starting || m.status == Status.Stopping -> state.busy
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** Why the last start failed, under the status. */
@Composable
private fun ErrorLine(m: HomeModel, size: TextUnit) {
    if (m.status != Status.Stopped || m.lastError == null) return
    Spacer(Modifier.height(8.dp))
    Text(
        m.lastError,
        fontSize = size,
        color = LocalStateColors.current.danger,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

@Composable
private fun ConnectButton(m: HomeModel, onClick: () -> Unit, modifier: Modifier, size: TextUnit) {
    val colors = MaterialTheme.colorScheme
    val busy = !m.ready || m.status == Status.Starting || m.status == Status.Stopping
    Button(
        onClick = onClick,
        modifier = modifier,
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
        Text(
            text = when {
                !m.ready -> stringResource(R.string.status_loading)
                m.status == Status.Stopped -> stringResource(R.string.action_connect)
                m.status == Status.Started -> stringResource(R.string.action_disconnect)
                m.status == Status.Starting -> stringResource(R.string.status_connecting)
                m.status == Status.Stopping -> stringResource(R.string.status_disconnecting)
                else -> ""
            },
            fontSize = size,
        )
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
    Card(colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(stringResource(R.string.proxy_mode_card_title), fontSize = 14.sp, color = colors.onSecondaryContainer)
            Text(
                "SOCKS5 127.0.0.1:${com.vpn4tv.app.constant.ServiceMode.PROXY_PORT}",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onSecondaryContainer,
            )
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
    val state = LocalStateColors.current
    Card(
        onClick = onClick,
        modifier = modifier.tvFocus(RoundedCornerShape(12.dp), 1.05f),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.label_server) + " ", fontSize = size, color = colors.onSurfaceVariant)
            Text(m.activeServer, fontSize = size, fontWeight = FontWeight.Bold, color = colors.onSurface)
            if (m.activeDelay > 0) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "${m.activeDelay} ${stringResource(R.string.unit_ms)}",
                    fontSize = size,
                    color = when {
                        m.activeDelay < 300 -> state.fast
                        m.activeDelay < 600 -> state.medium
                        else -> state.slow
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = size * 1.25f, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionLine(m: HomeModel, size: TextUnit) {
    if (m.status != Status.Started || m.sessionBytes <= 0) return
    Spacer(Modifier.height(8.dp))
    Text(
        "${stringResource(R.string.session_traffic)}: ${formatBytesLocalized(m.sessionBytes)}",
        fontSize = size,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Broken-install banner: the native lib never loaded (Play per-ABI split
 * misdelivery on some TV firmware). Persistent + proactive — guides the user
 * to reinstall from Google Play, the sanctioned update channel; web fallback
 * for devices where the Play app can't resolve market://.
 */
@Composable
private fun BrokenInstallBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val broken = remember { com.vpn4tv.app.utils.NativeLib.isBroken(context) }
    if (!broken) return
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.install_broken_banner), fontSize = 14.sp, color = colors.onErrorContainer)
            Button(
                onClick = {
                    val pkg = context.packageName
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=$pkg"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                },
                modifier = Modifier.padding(top = 10.dp).tvFocus(CircleShape),
            ) {
                Text(stringResource(R.string.install_broken_button))
            }
        }
    }
}
