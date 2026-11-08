package com.vpn4tv.app.ui

import android.util.Log
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Icon as TvIcon
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText

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
            com.vpn4tv.app.bg.BoxService.lastError.postValue(err)
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
                TvButton(
                    onClick = onAddViaTelegram,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(32.dp),
                    focusedScale = 1.03f,
                    minHeight = 64.dp,
                    fillWidth = true,
                ) {
                    TvIcon(Icons.Default.Add, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    TvText(stringResource(R.string.action_add_telegram), style = TvMaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    // TV: a row is one control; its actions open here, the first in focus.
    actionsFor?.let { profile ->
        val firstAction = remember(profile) { FocusRequester() }
        LaunchedEffect(profile) {
            // Asked again for a moment: the dialog's window comes up after this.
            repeat(5) {
                kotlinx.coroutines.delay(100)
                if (runCatching { firstAction.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        TvDialog(
            onDismissRequest = { actionsFor = null },
            title = profile.name,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (profile.id != selectedId) {
                        DialogAction(stringResource(R.string.profile_action_select), Modifier.focusRequester(firstAction)) {
                            actionsFor = null; select(profile)
                        }
                    }
                    if (profile.typed.type == TypedProfile.Type.Remote) {
                        val focus = if (profile.id == selectedId) Modifier.focusRequester(firstAction) else Modifier
                        DialogAction(stringResource(R.string.profile_action_update), focus) { actionsFor = null; update(profile) }
                    }
                    val focus = if (profile.id == selectedId && profile.typed.type != TypedProfile.Type.Remote) {
                        Modifier.focusRequester(firstAction)
                    } else {
                        Modifier
                    }
                    DialogAction(stringResource(R.string.profile_action_delete), focus) { actionsFor = null; deleteAsked = profile }
                }
            },
        ) {
            TvTextButton(onClick = { actionsFor = null }) {
                TvText(stringResource(R.string.cancel))
            }
        }
    }

    deleteAsked?.let { profile ->
        if (Device.isTv) {
            // Cancel in focus: a stray OK keeps the key.
            val cancelFocus = remember(profile) { FocusRequester() }
            LaunchedEffect(profile) {
                repeat(5) {
                    kotlinx.coroutines.delay(100)
                    if (runCatching { cancelFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                }
            }
            TvDialog(
                onDismissRequest = { deleteAsked = null },
                title = stringResource(R.string.profile_delete_title),
                text = { TvText(stringResource(R.string.profile_delete_text, profile.name)) },
            ) {
                TvTextButton(onClick = { deleteAsked = null }, modifier = Modifier.focusRequester(cancelFocus)) {
                    TvText(stringResource(R.string.cancel))
                }
                TvTextButton(onClick = { deleteAsked = null; delete(profile) }) {
                    TvText(stringResource(R.string.profile_action_delete), color = TvMaterialTheme.colorScheme.error)
                }
            }
            return@let
        }
        AlertDialog(
            onDismissRequest = { deleteAsked = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_text, profile.name)) },
            confirmButton = {
                TextButton(onClick = { deleteAsked = null; delete(profile) }) {
                    Text(stringResource(R.string.profile_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAsked = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** TV: one of a key's actions in its dialog, the width of the dialog. */
@Composable
private fun DialogAction(label: String, modifier: Modifier, onClick: () -> Unit) {
    TvOutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.05f, fillWidth = true) {
        TvText(label)
    }
}

private fun Profile.host(): String =
    if (typed.type == TypedProfile.Type.Remote) runCatching { java.net.URL(typed.remoteURL).host }.getOrDefault("") else ""

/**
 * TV: the whole row is one focusable, Compose for TV's list item without a
 * container; the selection is a check mark, not a colour. TalkBack hears
 * "selected" from the row; OK opens the key's actions.
 */
@Composable
private fun TvProfileRow(profile: Profile, selected: Boolean, onClick: () -> Unit) {
    val host = profile.host()
    val supporting: (@Composable () -> Unit)? = if (host.isNotEmpty()) { { TvText(host) } } else null
    TvListItem(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            if (selected) {
                TvIcon(Icons.Default.Check, null, tint = LocalStateColors.current.connected, modifier = Modifier.size(24.dp))
            }
        },
        headlineContent = { TvText(profile.name) },
        supportingContent = supporting,
    )
}

/**
 * Phone: a Material 3 list row; trailing icon buttons are fine in a touch list.
 * For TalkBack the row is one of a set, as a radio button ("selected"), and the
 * buttons name the key they act on, not just "Delete" on every row.
 */
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
        modifier = Modifier.fillMaxWidth().readableWidth(ContentWidth.list)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        leadingContent = { SelectionMark(selected) },
        headlineContent = { Text(profile.name) },
        supportingContent = supporting,
        trailingContent = {
            Row {
                if (profile.typed.type == TypedProfile.Type.Remote) {
                    IconButton(onClick = onUpdate) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.action_update_key_named, profile.name))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, stringResource(R.string.action_delete_key_named, profile.name))
                }
            }
        },
    )
}

/** The check of the selected key: only seen, the row says "selected" itself. */
@Composable
private fun SelectionMark(selected: Boolean) {
    Box(Modifier.width(36.dp)) {
        if (selected) {
            Icon(
                Icons.Default.Check, null,
                tint = LocalStateColors.current.connected, modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Returns null on success, what Home says about the failure otherwise. */
private fun updateProfile(profile: Profile): com.vpn4tv.app.bg.ServiceError? {
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
        Log.e("Profiles", "Update failed: ${com.vpn4tv.app.utils.LogPrivacy.error(e)}")
        com.vpn4tv.app.bg.ServiceError.keyUpdate(e)
    }
}
