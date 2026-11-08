package com.vpn4tv.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import com.vpn4tv.app.R
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val HOME = "home"
    const val PROFILES = "profiles"
    const val SERVERS = "servers"
    const val ADD_PROFILE = "add_profile"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val PER_APP_PROXY = "per_app_proxy"
    const val ABOUT = "about"
    const val LAN_IMPORT = "lan_import"
    const val MANUAL_IMPORT = "manual_import"
}

/** Phone and tablet: the primary destinations, in a Material 3 navigation bar or rail. */
private enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    Home(Routes.HOME, R.string.title_dashboard, Icons.Default.Home),
    Servers(Routes.SERVERS, R.string.nav_servers, Icons.AutoMirrored.Filled.List),
    Profiles(Routes.PROFILES, R.string.nav_profiles, Icons.Default.FolderOpen),
    Settings(Routes.SETTINGS, R.string.title_settings, Icons.Default.Settings),
}

// TV remotes double-fire DPAD_CENTER / BACK. The RESUMED-lifecycle guard is
// the standard navigation-compose idiom: the second event arrives while the
// first transition has already moved the current entry to STARTED, so the
// duplicate navigate/pop is dropped instead of corrupting the back stack
// ("Cannot transition entry that is not in the back stack", vc50326).
private fun NavController.navigateSafe(route: String, builder: androidx.navigation.NavOptionsBuilder.() -> Unit = {}) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        navigate(route, builder)
    }
}

private fun NavController.popBackStackSafe(): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        return popBackStack()
    }
    return false
}

/** A navigation-bar destination: one copy on the stack, Home under it, so Back leads Home. */
private fun NavController.openTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// The import count when the add screen was opened. Taken then, not at the
// screen's first frame: a link that opened the app may finish its import in
// between, and that one has to close the screen too.
private var importsBeforeAddProfile = 0

private fun markAddProfileOpened() {
    importsBeforeAddProfile = MainActivity.profileImported.value ?: 0
}

@Composable
fun AppNavigation(
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val navController = rememberNavController()
    val ready by MainActivity.profileReady.observeAsState(false)

    // Auto-navigate to add profile on first launch, but only if there are
    // genuinely no profiles on disk — not just because selectedProfile is
    // -1L (which can race with ensureDefaultProfile on cold start and with
    // post-reinstall state where profiles already exist on disk but the
    // Settings DB has been wiped).
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        val profiles = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.vpn4tv.app.database.ProfileManager.list()
        }
        if (profiles.isEmpty()) {
            // Not again when the activity is recreated (a turn, the theme) or
            // restored after the system dropped it while the user was in
            // Telegram: the restored screen
            // keeps its code and uuid, a new one would wait for another code.
            // A TV stays on Home, which is the add-key screen without a key
            // (Google's TV navigation: the first screen is also the last).
            if (!Device.isTv && navController.currentBackStackEntry?.destination?.route != Routes.ADD_PROFILE) {
                markAddProfileOpened()
                navController.navigate(Routes.ADD_PROFILE) {
                    popUpTo(Routes.HOME)
                }
            }
        } else if (com.vpn4tv.app.database.Settings.selectedProfile == -1L) {
            com.vpn4tv.app.database.Settings.selectedProfile = profiles.first().id
        }
        // The screens the launcher's shortcuts open, after the check above:
        // with no key at all, Add key is open already.
        MainActivity.routeRequests.receiveAsFlow().collect { route ->
            when (route) {
                Routes.SERVERS -> {
                    val noKeys = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.vpn4tv.app.database.ProfileManager.list().isEmpty()
                    }
                    if (!noKeys) {
                        if (Device.isTv) navController.navigate(Routes.SERVERS) { launchSingleTop = true }
                        else navController.openTab(Routes.SERVERS)
                    }
                }
                Routes.ADD_PROFILE -> if (navController.currentDestination?.route != Routes.ADD_PROFILE) {
                    markAddProfileOpened()
                    navController.navigate(Routes.ADD_PROFILE) { launchSingleTop = true }
                }
            }
        }
    }

    if (Device.isTv) {
        TvConnectFeedback()
        TvMessages(
            onRetryConnect = onConnect,
            // The one way to connect on a box without the system VPN window:
            // proxy mode on, and the connect the user asked for goes ahead.
            onProxyMode = {
                com.vpn4tv.app.database.Settings.serviceMode = com.vpn4tv.app.constant.ServiceMode.PROXY
                onConnect()
            },
        )
        Graph(navController, onConnect, onDisconnect)
        return
    }

    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // The bar stays under the manual-import dialog that opens over a tab.
    val showBar = route in Tab.entries.map { it.route } || route == Routes.MANUAL_IMPORT
    val snackbar = remember { SnackbarHostState() }
    PhoneMessages(
        snackbar,
        onRetryConnect = onConnect,
        onOpenSettings = { navController.openTab(Routes.SETTINGS) },
        onAddKey = { markAddProfileOpened(); navController.navigateSafe(Routes.ADD_PROFILE) },
    )
    ConnectFeedback()
    // Google's adaptive navigation: the bar at the bottom of a narrow window;
    // a rail at the side of one 600 dp wide and more that is not low (a tablet,
    // a foldable open), where the thumbs reach the edges. Turned on its side, a
    // phone is low: the bar stays. Off on the screens below the tabs.
    val navigation = if (showBar) {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    } else {
        NavigationSuiteType.None
    }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Tab.entries.forEach { tab ->
                item(
                    selected = route == tab.route,
                    onClick = { navController.openTab(tab.route) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        },
        layoutType = navigation,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            // Each screen pads for the status bar itself (its top app bar); the
            // bar pads for the navigation bar and leaves it consumed, the rail
            // only its side. Without either, the screen's own scaffold takes the
            // navigation-bar inset.
            contentWindowInsets = WindowInsets(0),
            // Above the navigation bar; without it, above the system's.
            snackbarHost = {
                SnackbarHost(snackbar, Modifier.navigationBarsPadding()) { data ->
                    // A long message keeps the full width, its action goes below.
                    Snackbar(data, actionOnNewLine = data.visuals.actionLabel != null && data.visuals.message.length > 40)
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                Graph(navController, onConnect, onDisconnect)
            }
        }
    }
}

@Composable
private fun Graph(
    navController: androidx.navigation.NavHostController,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val tv = Device.isTv
    // Phone tabs are top-level: no Up arrow, the system Back leads Home.
    val tabBack: (() -> Unit)? = if (tv) { { navController.popBackStackSafe() } } else null
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onNavigateProfiles = { navController.navigateSafe(Routes.PROFILES) },
                onNavigateServers = { if (tv) navController.navigateSafe(Routes.SERVERS) else navController.openTab(Routes.SERVERS) },
                onAddProfile = { markAddProfileOpened(); navController.navigateSafe(Routes.ADD_PROFILE) },
                onNavigateLogs = { navController.navigateSafe(Routes.LOGS) },
                onNavigateSettings = { navController.navigateSafe(Routes.SETTINGS) },
                onNavigateAbout = { navController.navigateSafe(Routes.ABOUT) },
                onManualImport = { navController.navigateSafe(Routes.MANUAL_IMPORT) },
                // TV without a key: the fallback when the bot's server is out of reach.
                onLanImport = { navController.navigateSafe(Routes.LAN_IMPORT) },
            )
        }
        composable(Routes.PROFILES) {
            ProfilesScreen(
                onBack = tabBack,
                onAddViaTelegram = { markAddProfileOpened(); navController.navigateSafe(Routes.ADD_PROFILE) },
                onPasteKey = { navController.navigateSafe(Routes.MANUAL_IMPORT) },
            )
        }
        composable(Routes.SERVERS) {
            // The same as the launcher's Servers shortcut: the launcher ranks it by this.
            ReportShortcutUse(AppShortcuts.ID_SERVERS)
            ServersScreen(onBack = tabBack)
        }
        composable(Routes.LOGS) {
            LogsScreen(onBack = { navController.popBackStackSafe() })
        }
        composable(Routes.SETTINGS) {
            if (tv) {
                    SettingsScreen(
                    onBack = tabBack,
                    onPerAppProxy = { navController.navigateSafe(Routes.PER_APP_PROXY) },
                    onLanImport = { navController.navigateSafe(Routes.LAN_IMPORT) },
                    onManualImport = { navController.navigateSafe(Routes.MANUAL_IMPORT) },
                    onLogs = { navController.navigateSafe(Routes.LOGS) },
                    onAbout = { navController.navigateSafe(Routes.ABOUT) },
                )
            } else {
                PhoneSettings(navController)
            }
        }
        composable(Routes.PER_APP_PROXY) {
            PerAppProxyScreen(onBack = { navController.popBackStackSafe() })
        }
        composable(Routes.LAN_IMPORT) {
            LanImportScreen(
                onBack = { navController.popBackStackSafe() },
                onImported = { navController.popBackStack(Routes.HOME, false) }
            )
        }
        // Opened in portrait, from Home ("+") or Settings; turning the phone
        // afterwards leaves it open. A dialog destination: the screen under it
        // stays STARTED and resumes when it closes, so Home shows the new
        // profile as after the other imports.
        dialog(Routes.MANUAL_IMPORT) {
            ManualImportDialog(
                // Pops this dialog only: a second tap on Cancel finds nothing
                // to pop instead of taking Home with it.
                onDismiss = { navController.popBackStack(Routes.MANUAL_IMPORT, true) },
                // As the LAN import: not lifecycle-guarded, fires after a delay.
                onImported = { navController.popBackStack(Routes.HOME, false) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStackSafe() })
        }
        composable(Routes.ADD_PROFILE) {
            // A link or file opened with the app while this screen waits
            // (vpn4tv://, a key, a .conf) adds a profile as surely as the
            // bot's answer does: back to Home then too.
            val imported by MainActivity.profileImported.observeAsState(0)
            LaunchedEffect(imported) {
                if (imported != importsBeforeAddProfile) navController.popBackStack(Routes.HOME, false)
            }
            ReportShortcutUse(AppShortcuts.ID_NEW_KEY)
            AddProfileScreen(
                onBack = { navController.popBackStackSafe() },
                onProfileAdded = {
                    // Intentionally NOT lifecycle-guarded: fires from a
                    // background import callback, the entry may be STARTED.
                    navController.popBackStack(Routes.HOME, false)
                },
                onLanImport = { navController.navigateSafe(Routes.LAN_IMPORT) }
            )
        }
    }
}

/**
 * Settings on a phone or tablet. A window under 840 dp: the list, and each
 * sub-screen opens over it, as before. A wider one (a tablet on its side, a
 * foldable open): the sub-screen opens beside the list (Google's settings
 * pattern, "Adaptive layout": list-detail; the list stays in view, the chosen
 * row marked). The pane picked survives a turn; a window that narrows while
 * one is open keeps showing it alone, Back returns to the list.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun PhoneSettings(navController: androidx.navigation.NavHostController) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val twoPanes = navigator.scaffoldDirective.maxHorizontalPartitions > 1
    val scope = rememberCoroutineScope()
    val shown = navigator.currentDestination
        ?.takeIf { it.pane == ListDetailPaneScaffoldRole.Detail }?.contentKey
    val open: (String) -> Unit = { route ->
        if (!twoPanes) navController.navigateSafe(route)
        else if (route != shown) scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, route) }
    }
    val list = @Composable {
        SettingsScreen(
            onBack = null,
            selected = if (twoPanes) shown else null,
            onPerAppProxy = { open(Routes.PER_APP_PROXY) },
            onLanImport = { open(Routes.LAN_IMPORT) },
            onManualImport = { navController.navigateSafe(Routes.MANUAL_IMPORT) },
            onLogs = { open(Routes.LOGS) },
            onAbout = { open(Routes.ABOUT) },
        )
    }
    // A phone: the plain list, exactly as before.
    if (!twoPanes && shown == null) {
        list()
        return
    }
    // Two panes: the sub-screens without their Back arrow, the list is beside
    // them. One (the window narrowed, a tablet turned upright): the arrow back
    // to the list.
    val back: (() -> Unit)? = if (twoPanes) null else { { scope.launch { navigator.navigateBack() } } }
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = { AnimatedPane { list() } },
        detailPane = {
            AnimatedPane {
                when (shown) {
                    Routes.PER_APP_PROXY -> PerAppProxyScreen(onBack = back)
                    Routes.LAN_IMPORT -> LanImportScreen(
                        onBack = back,
                        onImported = { navController.popBackStack(Routes.HOME, false) },
                    )
                    Routes.LOGS -> LogsScreen(onBack = back)
                    Routes.ABOUT -> AboutScreen(onBack = back)
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.settings_pick_detail),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

/** A screen a launcher shortcut also opens: its use is reported once per visit. */
@Composable
private fun ReportShortcutUse(id: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(id) {
        withContext(kotlinx.coroutines.Dispatchers.IO) { AppShortcuts.reportUsed(context.applicationContext, id) }
    }
}
