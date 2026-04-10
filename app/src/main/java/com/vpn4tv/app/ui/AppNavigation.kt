package com.vpn4tv.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val HOME = "home"
    const val PROFILES = "profiles"
    const val SERVERS = "servers"
    const val ADD_PROFILE = "add_profile"
    const val LOGS = "logs"
    const val ABOUT = "about"
}

@Composable
fun AppNavigation(
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val navController = rememberNavController()
    val ready by MainActivity.profileReady.observeAsState(false)
    val hasProfile = ready && com.vpn4tv.app.database.Settings.selectedProfile != -1L

    // Auto-navigate to add profile on first launch
    LaunchedEffect(ready) {
        if (ready && !hasProfile) {
            navController.navigate(Routes.ADD_PROFILE) {
                popUpTo(Routes.HOME)
            }
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
