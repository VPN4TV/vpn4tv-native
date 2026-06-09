package com.vpn4tv.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onNavigateProfiles = { navController.navigateSafe(Routes.PROFILES) },
                onNavigateServers = { navController.navigateSafe(Routes.SERVERS) },
                onAddProfile = { navController.navigateSafe(Routes.ADD_PROFILE) },
                onNavigateLogs = { navController.navigateSafe(Routes.LOGS) },
                onNavigateSettings = { navController.navigateSafe(Routes.SETTINGS) },
                onNavigateAbout = { navController.navigateSafe(Routes.ABOUT) },
            )
        }
        composable(Routes.PROFILES) {
            ProfilesScreen(
                onBack = { navController.popBackStackSafe() },
                onAddViaTelegram = { navController.navigateSafe(Routes.ADD_PROFILE) }
            )
        }
        composable(Routes.SERVERS) {
            ServersScreen(
                onBack = { navController.popBackStackSafe() }
            )
        }
        composable(Routes.LOGS) {
            LogsScreen(onBack = { navController.popBackStackSafe() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStackSafe() },
                onPerAppProxy = { navController.navigateSafe(Routes.PER_APP_PROXY) }
            )
        }
        composable(Routes.PER_APP_PROXY) {
            PerAppProxyScreen(onBack = { navController.popBackStackSafe() })
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
                }
            )
        }
    }
}
