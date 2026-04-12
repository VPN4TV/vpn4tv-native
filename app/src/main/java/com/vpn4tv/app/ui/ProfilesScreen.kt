package com.vpn4tv.app.ui

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.converter.ConfigGenerator
import com.vpn4tv.app.converter.ProxyParser
import com.vpn4tv.app.database.Profile
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ProfilesScreen(onBack: () -> Unit, onAddViaTelegram: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var profiles by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var selectedId by remember { mutableStateOf(Settings.selectedProfile) }
    var isLoading by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val list = ProfileManager.list()
            val sel = Settings.selectedProfile
            withContext(Dispatchers.Main) {
                profiles = list
                selectedId = sel
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                stringResource(R.string.title_subscriptions),
                fontSize = 28.sp,
                color = Color.White,
                modifier = Modifier.weight(1f).padding(start = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Profile list
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(profiles, key = { it.id }) { profile ->
                ProfileItem(
                    profile = profile,
                    isSelected = profile.id == selectedId,
                    onSelect = {
                        scope.launch(Dispatchers.IO) {
                            Settings.selectedProfile = profile.id
                            withContext(Dispatchers.Main) { selectedId = profile.id }
                        }
                    },
                    onUpdate = {
                        scope.launch {
                            isLoading = true
                            withContext(Dispatchers.IO) { updateProfile(profile) }
                            isLoading = false
                            reload()
                        }
                    },
                    onDelete = {
                        scope.launch(Dispatchers.IO) {
                            ProfileManager.delete(profile)
                            runCatching { File(profile.typed.path).delete() }
                            if (selectedId == profile.id) Settings.selectedProfile = -1
                            withContext(Dispatchers.Main) { reload() }
                        }
                    }
                )
            }
        }

        // Add via Telegram button
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAddViaTelegram() }
                .focusable(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Add, stringResource(R.string.icon_add), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.action_add_telegram), fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun ProfileItem(
    profile: Profile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = when {
        isSelected -> Color.Green
        isFocused -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    Card(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(BorderStroke(2.dp, borderColor), shape = MaterialTheme.shapes.medium)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelected) {
                Icon(Icons.Default.Check, stringResource(R.string.selected), tint = Color.Green, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, fontSize = 18.sp, color = Color.White)
                if (profile.typed.type == TypedProfile.Type.Remote) {
                    val host = try {
                        java.net.URL(profile.typed.remoteURL).host
                    } catch (_: Exception) { "" }
                    if (host.isNotEmpty()) {
                        Text(host, fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }

            IconButton(onClick = onUpdate) {
                Icon(Icons.Default.Refresh, stringResource(R.string.action_update_subscription), tint = Color.White)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.action_clear), tint = Color.Red.copy(alpha = 0.7f))
            }
        }
    }
}

private fun updateProfile(profile: Profile) {
    try {
        val subContent = com.vpn4tv.app.converter.HwidService.downloadSubscription(com.vpn4tv.app.Application.application, profile.typed.remoteURL)
        val proxies = ProxyParser.parseSubscription(subContent)
        if (proxies.isEmpty()) return
        val result = ConfigGenerator.generateFull(proxies)
        File(profile.typed.path).writeText(result.singboxJson)
        val xraySidecar = File(ConfigGenerator.xraySidecarPath(profile.typed.path))
        if (result.xrayJson != null) xraySidecar.writeText(result.xrayJson)
        else if (xraySidecar.exists()) xraySidecar.delete()
        profile.typed.lastUpdated = java.util.Date()
        kotlinx.coroutines.runBlocking { ProfileManager.update(profile) }
        Log.d("Profiles", "Updated ${profile.name}: ${proxies.size} proxies")
    } catch (e: Exception) {
        Log.e("Profiles", "Update failed: ${e.message}")
    }
}
