package com.vpn4tv.app.ui

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.database.Profile
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.database.TypedProfile
import com.vpn4tv.app.ui.theme.LocalStateColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ProfilesScreen(
    onBack: (() -> Unit)?,
    onAddViaTelegram: () -> Unit = {},
    onPasteKey: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var selectedId by remember { mutableStateOf(Settings.selectedProfile) }
    var isLoading by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Profile?>(null) }
    var deleteAsked by remember { mutableStateOf<Profile?>(null) }

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

    val select: (Profile) -> Unit = { profile ->
        scope.launch(Dispatchers.IO) {
            Settings.selectedProfile = profile.id
            withContext(Dispatchers.Main) { selectedId = profile.id }
        }
    }
    val update: (Profile) -> Unit = { profile ->
        scope.launch {
            isLoading = true
            val err = withContext(Dispatchers.IO) { updateProfile(profile) }
            isLoading = false
            reload()
            // Route through the shared lastError LiveData so HomeScreen shows
            // it as a red line the next time the user lands there.
            com.vpn4tv.app.bg.BoxService.lastError.postValue(err?.let { com.vpn4tv.app.Application.application.getString(R.string.subscription_update_failed, it) })
        }
    }
    val delete: (Profile) -> Unit = { profile ->
        scope.launch(Dispatchers.IO) {
            ProfileManager.delete(profile)
            runCatching { File(profile.typed.path).delete() }
            if (selectedId == profile.id) Settings.selectedProfile = -1
            withContext(Dispatchers.Main) { reload() }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.title_subscriptions),
        onBack = onBack,
        actions = {
            if (!Device.isTv) {
                IconButton(onClick = onPasteKey) {
                    Icon(Icons.Default.ContentPaste, stringResource(R.string.action_paste_key))
                }
            }
        },
        floatingActionButton = {
            if (!Device.isTv) {
                ExtendedFloatingActionButton(
                    onClick = onAddViaTelegram,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.action_add)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                modifier = Modifier.weight(1f),
                // Phone: room for the floating button under the last row.
                contentPadding = if (Device.isTv) PaddingValues(vertical = 8.dp) else PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(if (Device.isTv) 8.dp else 0.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    val selected = profile.id == selectedId
                    if (Device.isTv) {
                        TvProfileRow(profile, selected, onClick = { actionsFor = profile })
                    } else {
                        PhoneProfileRow(
                            profile, selected,
                            onSelect = { select(profile) },
                            onUpdate = { update(profile) },
                            onDelete = { deleteAsked = profile },
                        )
                    }
                }
            }
            if (Device.isTv) {
                // The screen's one primary action: a wide button, as the TV buttons guide asks.
                Button(
                    onClick = onAddViaTelegram,
                    modifier = Modifier.fillMaxWidth().height(64.dp).tvFocus(RoundedCornerShape(32.dp), 1.03f),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.action_add_telegram), fontSize = 20.sp)
                }
            }
        }
    }

    // TV: a row is one control; its actions open here.
    actionsFor?.let { profile ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(profile.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (profile.id != selectedId) {
                        DialogAction(stringResource(R.string.profile_action_select)) { actionsFor = null; select(profile) }
                    }
                    if (profile.typed.type == TypedProfile.Type.Remote) {
                        DialogAction(stringResource(R.string.profile_action_update)) { actionsFor = null; update(profile) }
                    }
                    DialogAction(stringResource(R.string.profile_action_delete)) { actionsFor = null; deleteAsked = profile }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionsFor = null }, modifier = Modifier.tvFocus(CircleShape)) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deleteAsked?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteAsked = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_text, profile.name)) },
            confirmButton = {
                TextButton(
                    onClick = { deleteAsked = null; delete(profile) },
                    modifier = Modifier.tvFocus(CircleShape),
                ) { Text(stringResource(R.string.profile_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteAsked = null }, modifier = Modifier.tvFocus(CircleShape)) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun DialogAction(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().tvFocus(CircleShape, 1.05f)) {
        Text(label, fontSize = 18.sp)
    }
}

private fun Profile.host(): String =
    if (typed.type == TypedProfile.Type.Remote) runCatching { java.net.URL(typed.remoteURL).host }.getOrDefault("") else ""

/** TV: the whole row is one focusable; the selection is a check mark, not a colour. */
@Composable
private fun TvProfileRow(profile: Profile, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().tvFocus(RoundedCornerShape(12.dp), TV_ROW_SCALE),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionMark(selected)
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontSize = 20.sp, color = colors.onSurface)
                val host = profile.host()
                if (host.isNotEmpty()) Text(host, fontSize = 14.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Phone: a Material 3 list row; trailing icon buttons are fine in a touch list. */
@Composable
private fun PhoneProfileRow(
    profile: Profile,
    selected: Boolean,
    onSelect: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    val host = profile.host()
    val supporting: (@Composable () -> Unit)? = if (host.isNotEmpty()) { { Text(host) } } else null
    ListItem(
        modifier = Modifier.clickable(onClick = onSelect),
        leadingContent = { SelectionMark(selected) },
        headlineContent = { Text(profile.name) },
        supportingContent = supporting,
        trailingContent = {
            Row {
                if (profile.typed.type == TypedProfile.Type.Remote) {
                    IconButton(onClick = onUpdate) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.action_update_subscription))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, stringResource(R.string.profile_action_delete))
                }
            }
        },
    )
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Box(Modifier.width(36.dp)) {
        if (selected) {
            Icon(
                Icons.Default.Check, stringResource(R.string.profile_selected),
                tint = LocalStateColors.current.connected, modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Returns null on success, error message on failure. */
private fun updateProfile(profile: Profile): String? {
    return try {
        val outcome = kotlinx.coroutines.runBlocking {
            com.vpn4tv.app.converter.SubscriptionUpdater.update(
                com.vpn4tv.app.Application.application, profile,
            )
        }
        // The profile the tunnel runs: its new keys go live now.
        com.vpn4tv.app.converter.SubscriptionUpdater.applyToRunningService(profile, outcome)
        Log.d("Profiles", "Updated ${profile.name}: config=${outcome.configChanged} bridges=${outcome.bridgesChanged}")
        null
    } catch (e: Exception) {
        Log.e("Profiles", "Update failed: ${e.message}", e)
        e.message ?: e.javaClass.simpleName
    }
}
