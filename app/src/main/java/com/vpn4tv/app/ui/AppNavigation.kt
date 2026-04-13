package com.vpn4tv.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
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
                onNavigateProfiles = { navController.navigate(Routes.PROFILES) },
                onNavigateServers = { navController.navigate(Routes.SERVERS) },
                onAddProfile = { navController.navigate(Routes.ADD_PROFILE) },
                onNavigateLogs = { navController.navigate(Routes.LOGS) },
                onNavigateSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.PROFILES) {
            ProfilesScreen(
                onBack = { navController.popBackStack() },
                onAddViaTelegram = { navController.navigate(Routes.ADD_PROFILE) }
            )
        }
        composable(Routes.SERVERS) {
            ServersScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.LOGS) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onPerAppProxy = { navController.navigate(Routes.PER_APP_PROXY) }
            )
        }
        composable(Routes.PER_APP_PROXY) {
            PerAppProxyScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ADD_PROFILE) {
            AddProfileScreen(
                onBack = { navController.popBackStack() },
                onProfileAdded = {
                    navController.popBackStack(Routes.HOME, false)
                }
            )
        }
    }
}
