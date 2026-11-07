package com.vpn4tv.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
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

/** Phone: the primary destinations go in a Material 3 navigation bar. */
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
            navController.navigate(Routes.ADD_PROFILE) {
                popUpTo(Routes.HOME)
            }
            return@LaunchedEffect
        }
        if (com.vpn4tv.app.database.Settings.selectedProfile == -1L) {
            com.vpn4tv.app.database.Settings.selectedProfile = profiles.first().id
        }
    }

    if (Device.isTv) {
        Graph(navController, onConnect, onDisconnect)
        return
    }

    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // The bar stays under the manual-import dialog that opens over a tab.
    val showBar = route in Tab.entries.map { it.route } || route == Routes.MANUAL_IMPORT
    Scaffold(
        // Each screen pads for the status bar itself (its top app bar); the
        // bar pads for the navigation bar. Without the bar, the screen's own
        // scaffold takes the navigation-bar inset.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = entry?.destination?.route == tab.route,
                            onClick = { navController.openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            Graph(navController, onConnect, onDisconnect)
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
                onAddProfile = { navController.navigateSafe(Routes.ADD_PROFILE) },
                onNavigateLogs = { navController.navigateSafe(Routes.LOGS) },
                onNavigateSettings = { navController.navigateSafe(Routes.SETTINGS) },
                onNavigateAbout = { navController.navigateSafe(Routes.ABOUT) },
                onManualImport = { navController.navigateSafe(Routes.MANUAL_IMPORT) },
            )
        }
        composable(Routes.PROFILES) {
            ProfilesScreen(
                onBack = tabBack,
                onAddViaTelegram = { navController.navigateSafe(Routes.ADD_PROFILE) },
                onPasteKey = { navController.navigateSafe(Routes.MANUAL_IMPORT) },
            )
        }
        composable(Routes.SERVERS) {
            ServersScreen(onBack = tabBack)
        }
        composable(Routes.LOGS) {
            LogsScreen(onBack = { navController.popBackStackSafe() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = tabBack,
                onPerAppProxy = { navController.navigateSafe(Routes.PER_APP_PROXY) },
                onLanImport = { navController.navigateSafe(Routes.LAN_IMPORT) },
                onManualImport = { navController.navigateSafe(Routes.MANUAL_IMPORT) },
                onLogs = { navController.navigateSafe(Routes.LOGS) },
                onAbout = { navController.navigateSafe(Routes.ABOUT) },
            )
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
