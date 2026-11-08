package com.vpn4tv.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.constant.ServiceMode
import com.vpn4tv.app.database.Settings
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.tv.material3.Icon as TvIcon
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText

/**
 * The app's settings, in groups with headings (Google's settings pattern:
 * "Containment"; TalkBack moves between the headings): connection, routing,
 * the user's own key, notifications (phone), then the log and About (phone).
 * The second line says a setting's state or what it does, briefly ("Write for
 * settings"). [selected]: the route of the sub-screen shown beside the list on
 * a wide window, its row marked.
 */
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)?,
    selected: String? = null,
    onPerAppProxy: () -> Unit = {},
    onLanImport: () -> Unit = {},
    onManualImport: () -> Unit = {},
    onLogs: () -> Unit = {},
    onAbout: () -> Unit = {},
) {
    var autoConnect by remember { mutableStateOf(Settings.autoConnectOnBoot) }
    var proxyMode by remember { mutableStateOf(Settings.isProxyMode) }
    var bypassLan by remember { mutableStateOf(Settings.bypassLan) }
    var fakeDns by remember { mutableStateOf(Settings.fakeDns) }
    var allThroughVpn by remember { mutableStateOf(Settings.allThroughVpn) }
    var notificationServer by remember { mutableStateOf(Settings.dynamicNotification) }
    // Read again when a per-app pane beside the list closes (it saves then)
    // and on every return to the screen.
    var perApp by remember { mutableStateOf(PerAppState.read()) }
    LaunchedEffect(selected) { perApp = PerAppState.read() }
    val context = LocalContext.current
    // Phone: whether notifications can show, read again on return from
    // Android's settings or its permission dialog.
    var notificationsOn by remember { mutableStateOf(notificationsAllowed(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsOn = notificationsAllowed(context)
                perApp = PerAppState.read()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val allowNotifications = rememberNotificationPermissionRequest { notificationsOn = notificationsAllowed(context) }
    // TV: Back lands on the row that opened the screen (the TV navigation
    // guide), as on Home: per-app proxy (0) or the import over the network (1).
    // Asked again for a few seconds: a request during the enter transition is dropped.
    var openedFrom by rememberSaveable { mutableIntStateOf(-1) }
    val openerFocus = remember { List(2) { FocusRequester() } }
    var openerFocused by remember { mutableStateOf(false) }
    fun opener(index: Int, open: () -> Unit): () -> Unit = { openedFrom = index; open() }
    fun openerModifier(index: Int): Modifier =
        if (!Device.isTv) Modifier
        else Modifier.focusRequester(openerFocus[index])
            .onFocusChanged { if (it.hasFocus && openedFrom == index) openerFocused = true }
    LaunchedEffect(Unit) {
        val target = openerFocus.getOrNull(openedFrom)
        if (!Device.isTv || target == null) return@LaunchedEffect
        repeat(15) {
            kotlinx.coroutines.delay(200)
            if (openerFocused) return@LaunchedEffect
            runCatching { target.requestFocus() }
        }
    }

    ScreenScaffold(title = stringResource(R.string.title_settings), onBack = onBack, collapsingTopBar = true) { padding ->
        // verticalScroll: the list is longer than one screen; on short-height
        // TVs (Mi Box) Compose follows D-pad focus into view. Phone and
        // tablet: a column of readable width on a wide window.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(vertical = if (Device.isTv) 8.dp else 0.dp)
                .readableWidth(ContentWidth.text),
            verticalArrangement = Arrangement.spacedBy(if (Device.isTv) 8.dp else 0.dp),
        ) {
            SettingsHeader(stringResource(R.string.settings_group_connection), first = true)
            SettingsToggle(
                title = stringResource(R.string.setting_auto_connect),
                subtitle = stringResource(R.string.setting_auto_connect_desc),
                checked = autoConnect,
                onCheckedChange = { autoConnect = it; Settings.autoConnectOnBoot = it },
            )
            // Phone: Android's own always-on VPN, which can also block the
            // internet without the VPN. A TV often has no such page, so the
            // app's switch above stays for it.
            if (!Device.isTv) {
                SettingsLink(
                    title = stringResource(R.string.setting_always_on),
                    subtitle = stringResource(R.string.setting_always_on_desc),
                    onClick = { openVpnSettings(context) },
                    external = true,
                )
            }
            // Proxy mode — sing-box as a local SOCKS5 listener instead of a
            // VPN tunnel, for devices without the system VPN permission dialog.
            SettingsToggle(
                title = stringResource(R.string.setting_proxy_mode),
                subtitle = stringResource(R.string.setting_proxy_mode_desc),
                checked = proxyMode,
                onCheckedChange = {
                    proxyMode = it
                    Settings.serviceMode = if (it) ServiceMode.PROXY else ServiceMode.VPN
                },
            )
            // LAN bypass only applies in VPN mode: in proxy mode sing-box never
            // sees LAN traffic in the first place. Under the setting it depends
            // on, unavailable with the reason, not hidden (Google: "Dependency").
            SettingsToggle(
                title = stringResource(R.string.setting_bypass_lan),
                subtitle = stringResource(if (proxyMode) R.string.setting_bypass_lan_proxy else R.string.setting_bypass_lan_desc),
                checked = bypassLan,
                enabled = !proxyMode,
                onCheckedChange = { bypassLan = it; Settings.bypassLan = it },
            )

            SettingsHeader(stringResource(R.string.settings_group_routing))
            // "Everything through VPN" — off: Russian sites and services go
            // direct ("Russia direct"). Applies at once, through a reload of
            // the running service. The line under it says what is on now.
            // Plain BitTorrent goes direct in every mode; said right under the
            // toggle — a TV scrolls with the focus, so a note at the very
            // bottom might never come into view. TV: inside the toggle's row,
            // where TalkBack reads it with the toggle (a TV's TalkBack reaches
            // only what takes the focus).
            val torrentsNote = stringResource(R.string.settings_torrents_note)
            SettingsToggle(
                title = stringResource(R.string.setting_all_through_vpn),
                subtitle = stringResource(if (allThroughVpn) R.string.setting_all_through_vpn_on else R.string.setting_all_through_vpn_off),
                checked = allThroughVpn,
                onCheckedChange = {
                    allThroughVpn = it
                    Settings.allThroughVpn = it
                    com.vpn4tv.app.bg.BoxService.reloadIfRunning()
                },
                tvNote = torrentsNote,
            )
            if (!Device.isTv) {
                Text(
                    torrentsNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            SettingsLink(
                title = stringResource(R.string.title_per_app_proxy),
                subtitle = perApp.summary(),
                onClick = opener(0, onPerAppProxy),
                selected = selected == Routes.PER_APP_PROXY,
                tvModifier = openerModifier(0),
            )
            // FakeDNS — DNS-blocking bypass. Takes effect on next connect
            // (config is regenerated at every service start).
            SettingsToggle(
                title = stringResource(R.string.setting_fake_dns),
                subtitle = stringResource(R.string.setting_fake_dns_desc),
                checked = fakeDns,
                onCheckedChange = { fakeDns = it; Settings.fakeDns = it },
            )

            // Bring-your-own-key, tucked in Settings so the Telegram funnel
            // stays the primary path. A phone held upright pastes the key; a
            // TV, tablet or phone on its side imports over the local network.
            SettingsHeader(stringResource(R.string.settings_group_own_key))
            if (currentKeyImport() == KeyImport.Paste) {
                SettingsLink(
                    title = stringResource(R.string.manual_import_title),
                    subtitle = stringResource(R.string.setting_manual_import_desc),
                    onClick = onManualImport,
                )
            } else {
                SettingsLink(
                    title = stringResource(R.string.lan_import_title),
                    subtitle = stringResource(R.string.setting_lan_import_desc),
                    onClick = opener(1, onLanImport),
                    selected = selected == Routes.LAN_IMPORT,
                    tvModifier = openerModifier(1),
                )
            }

            // Phone: the notification while the VPN is on (Google: notification
            // options in the app's settings). Nobody sees it on a TV.
            if (!Device.isTv) {
                SettingsHeader(stringResource(R.string.settings_notifications))
                if (!notificationsOn) {
                    SettingsRow(
                        stringResource(R.string.setting_notifications_off),
                        stringResource(R.string.setting_notifications_off_desc),
                        Modifier,
                        onClick = allowNotifications,
                    ) {
                        FilledTonalButton(onClick = allowNotifications) { Text(stringResource(R.string.action_allow)) }
                    }
                }
                SettingsToggle(
                    title = stringResource(R.string.setting_notification_server),
                    subtitle = stringResource(R.string.setting_notification_server_desc),
                    checked = notificationServer,
                    onCheckedChange = {
                        notificationServer = it
                        Settings.dynamicNotification = it
                        com.vpn4tv.app.bg.ServiceNotification.refresh()
                    },
                )
                SettingsLink(
                    title = stringResource(R.string.setting_notification_system),
                    subtitle = stringResource(R.string.setting_notification_system_desc),
                    onClick = { openAppNotificationSettings(context) },
                    external = true,
                )
            }

            // Phone: logs and About live here (the navigation bar holds the
            // primary destinations); on TV they are icons on Home.
            if (!Device.isTv) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingsLink(
                    title = stringResource(R.string.title_logs), subtitle = null, onClick = onLogs,
                    selected = selected == Routes.LOGS,
                )
                SettingsLink(
                    title = stringResource(R.string.title_about), subtitle = null, onClick = onAbout,
                    selected = selected == Routes.ABOUT,
                )
            }
        }
    }
}

/** The per-app proxy as the Settings row shows it: off, or the mode and how many apps. */
private data class PerAppState(val enabled: Boolean, val exclude: Boolean, val count: Int) {
    @Composable
    fun summary(): String = when {
        !enabled -> stringResource(R.string.setting_disabled)
        exclude -> pluralStringResource(R.plurals.per_app_bypass_count, count, count)
        else -> pluralStringResource(R.plurals.per_app_only_count, count, count)
    }

    companion object {
        fun read() = PerAppState(
            enabled = Settings.perAppProxyEnabled,
            exclude = Settings.perAppProxyMode == Settings.PER_APP_PROXY_EXCLUDE,
            count = Settings.perAppProxyList.size,
        )
    }
}

/**
 * A row that opens another screen or a dialog; [external]: a page of Android's
 * settings; [selected]: its screen is open beside the list (a wide window).
 */
@Composable
private fun SettingsLink(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    external: Boolean = false,
    selected: Boolean = false,
    tvModifier: Modifier = Modifier,
) {
    val phoneModifier = if (selected) Modifier.selectable(selected = true, onClick = onClick)
        else Modifier.clickable(onClick = onClick)
    val icon = if (external) Icons.AutoMirrored.Filled.OpenInNew else Icons.AutoMirrored.Filled.KeyboardArrowRight
    SettingsRow(title, subtitle, phoneModifier, onClick, selected = selected, tvModifier = tvModifier) {
        if (Device.isTv) TvIcon(icon, null, tint = TvMaterialTheme.colorScheme.onSurfaceVariant)
        else Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The title of a group of rows, as Material lists have it; a heading for TalkBack. */
@Composable
private fun SettingsHeader(title: String, first: Boolean = false) {
    val tv = Device.isTv
    if (tv) {
        TvText(
            title,
            style = TvMaterialTheme.typography.labelMedium,
            color = TvMaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 4.dp, end = 16.dp, top = if (first) 0.dp else 8.dp, bottom = 4.dp)
                .semantics { heading() },
        )
        return
    }
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontSize = if (tv) 16.sp else MaterialTheme.typography.titleSmall.fontSize,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(
                start = if (tv) 4.dp else 16.dp, end = 16.dp,
                top = if (tv) (if (first) 0.dp else 8.dp) else 16.dp, bottom = 4.dp,
            )
            .semantics { heading() },
    )
}

/**
 * The whole row toggles; the switch only shows the state (one focus stop on
 * TV). For TalkBack the row is a switch on a phone and on TV alike: its name,
 * "on" or "off", and what a press does. [enabled] false: the setting does not
 * apply now, said in [subtitle].
 */
@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    tvNote: String? = null,
) {
    SettingsRow(
        title, subtitle,
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        toggled = checked,
        tvNote = tvNote,
    ) {
        if (Device.isTv) TvSwitch(checked = checked, enabled = enabled)
        else Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Phone: a Material 3 list row. TV: Compose for TV's row without a container
 * (the grey plate and the outline in focus), the TV type scale for the
 * 10-foot distance. A row that is not [enabled] is dimmed; on TV it still
 * takes the focus (Compose for TV keeps a disabled control focusable), so
 * TalkBack reads why it does not apply. [toggled]: the row is a switch in that
 * state (TV; the phone's [phoneModifier] says so itself).
 */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    phoneModifier: Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    tvModifier: Modifier = Modifier,
    toggled: Boolean? = null,
    tvNote: String? = null,
    trailing: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dim = 0.38f
    if (!Device.isTv) {
        val supporting: (@Composable () -> Unit)? = if (subtitle != null) { { Text(subtitle) } } else null
        ListItem(
            modifier = phoneModifier,
            headlineContent = { Text(title) },
            supportingContent = supporting,
            trailingContent = trailing,
            colors = ListItemDefaults.colors(
                containerColor = if (selected) colors.secondaryContainer else colors.surface,
                headlineColor = when {
                    !enabled -> colors.onSurface.copy(alpha = dim)
                    selected -> colors.onSecondaryContainer
                    else -> colors.onSurface
                },
                supportingColor = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = dim),
            ),
        )
        return
    }
    // TV: a switch row tells TalkBack its state ("on"/"off") and role; a plain
    // button row did not, though the switch was drawn.
    val state = if (toggled != null) {
        Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(toggled)
        }
    } else {
        Modifier
    }
    val type = TvMaterialTheme.typography
    val tv = TvMaterialTheme.colorScheme
    TvRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(tvModifier).then(state),
        enabled = enabled,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                TvText(title, style = type.titleMedium)
                if (subtitle != null) TvText(subtitle, style = type.bodySmall, color = tv.onSurfaceVariant)
                if (tvNote != null) {
                    TvText(tvNote, style = type.labelSmall, color = tv.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}
