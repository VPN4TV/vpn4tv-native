package com.vpn4tv.app.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.enableEdgeToEdge
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.converter.ConfigGenerator
import com.vpn4tv.app.converter.ProxyParser
import com.vpn4tv.app.database.Profile
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        /** The longest the launch screen waits for the keys. */
        private const val SPLASH_MAX_MS = 1000L
        val profileReady = MutableLiveData(false)
        /** Bumped when the refresh at launch has finished: the home screen re-reads the profile's name and expiry. */
        val profileRefreshed = MutableLiveData(0)
        /** Bumped when a link or file opened with the app became a profile: the add screen closes. */
        val profileImported = MutableLiveData(0)

        /**
         * Not one key in the app, as last seen: at launch, before [profileReady],
         * then by Home on every return. A TV without a key shows the add-key
         * content on Home from its first frame (HomeScreen).
         */
        @Volatile
        var noKeysSeen = false

        /** A screen a shortcut asked for (Routes.SERVERS, Routes.ADD_PROFILE): AppNavigation opens it. */
        val routeRequests = Channel<String>(capacity = 4)

        /**
         * A shortcut's, the tile's or the widget's request that waits for the
         * keys to load. Main thread only. Here, not in the activity: an activity
         * recreated meanwhile (turned at once) runs it instead of the old one.
         */
        private var pendingQuickAction: Intent? = null
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Settings.vpnPermissionSeen = result.resultCode == Activity.RESULT_OK
        if (result.resultCode == Activity.RESULT_OK) {
            BoxService.start()
        } else {
            AppMessages.post(AppMessage.VpnDenied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: the launch screen (Theme.VPN4TV.Starting, the
        // splash screen of Android 12+ on every version) hands over to AppTheme.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // First thing: a launch that never gets to the home screen gets its thread stacks reported.
        com.vpn4tv.app.utils.StartupWatchdog.arm { profileReady.value == true }
        // The launch screen stays until the keys are loaded, so Home opens with
        // Connect ready instead of "Loading…" under it; at most SPLASH_MAX_MS
        // (a key whose config has to be downloaded first keeps Home's loading
        // state instead). Asked before every frame; a recreated activity finds
        // the keys loaded and draws at once.
        val splashDeadline = SystemClock.uptimeMillis() + SPLASH_MAX_MS
        splash.setKeepOnScreenCondition {
            profileReady.value != true && SystemClock.uptimeMillis() < splashDeadline
        }
        // TV: landscape, as Google's TV checklist asks. The activity serves
        // phones and tablets too, so it is set here rather than in the manifest.
        if (Device.isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // Edge-to-edge with system bar icons that follow the light/dark theme
        // (enforced for targetSdk 35+ on Android 15+ anyway).
        enableEdgeToEdge()
        installTvBackGuard()

        // Fire-and-forget Play in-app update check. Will only actually
        // prompt when Play Store has a release with priority >= 4 and
        // the installed version is older. On non-GMS / direct-APK
        // installs this is a no-op by design (see PlayUpdateChecker).
        com.vpn4tv.app.utils.PlayUpdateChecker.maybePromptImmediate(this)

        // A shortcut, the tile or the widget asked for something (run once the
        // keys are loaded). Once per launch: not again when the activity is
        // recreated (a turn, a theme change) or reopened from Recents.
        if (savedInstanceState == null) pendingQuickAction = quickAction(intent)

        // Load profiles in background. A profile whose config is already on
        // disk can connect at once: its subscription is refreshed behind the
        // home screen, not before it: the screen used to say "loading" for as
        // long as the fetch took, minutes where api.vpn4tv.com is blocked.
        GlobalScope.launch(Dispatchers.IO) {
            val refresh = ensureDefaultProfile()
            noKeysSeen = runCatching { ProfileManager.list().isEmpty() }.getOrDefault(false)
            profileReady.postValue(true)
            withContext(Dispatchers.Main) { runPendingQuickAction() }
            // TV: the home screen's channel, in step with the keys as they are now.
            com.vpn4tv.app.tv.QuickConnectChannel.requestSync(applicationContext)
            if (refresh != null) launch {
                updateProfileConfig(refresh)
                profileRefreshed.postValue((profileRefreshed.value ?: 0) + 1)
                com.vpn4tv.app.tv.QuickConnectChannel.requestSync(applicationContext)
            }
            handleImportIntent(intent)
        }

        setContent {
            com.vpn4tv.app.ui.theme.VPN4TVTheme {
                AppNavigation(
                    // The launcher ranks the shortcuts by what the user does in the app too.
                    onConnect = { reportShortcutUse(AppShortcuts.ID_CONNECT); connect() },
                    onDisconnect = { reportShortcutUse(AppShortcuts.ID_DISCONNECT); BoxService.stop() },
                )
            }
        }
    }

    private var notificationsWereAllowed: Boolean? = null

    override fun onResume() {
        super.onResume()
        // Back from Android's settings, or from anywhere: notifications turned
        // on there show the running VPN's notification now (it is posted
        // anew), and a changed always-on VPN changes Home and the notification.
        val allowed = notificationsAllowed(this)
        if (allowed && notificationsWereAllowed == false) com.vpn4tv.app.bg.ServiceNotification.refresh()
        notificationsWereAllowed = allowed
        GlobalScope.launch(Dispatchers.IO) { com.vpn4tv.app.bg.VPNService.refreshAlwaysOn() }
        // In front: Android lets the app change its shortcuts freely now (a
        // new language, a backup restored onto a new phone without them).
        GlobalScope.launch(Dispatchers.IO) { AppShortcuts.publish(applicationContext) }
    }

    override fun onStop() {
        super.onStop()
        // Another key may have been picked: the widget names the key it connects with.
        com.vpn4tv.app.bg.QuickControls.changed()
        // TV: the home screen's channel shows the countries of the key picked now.
        com.vpn4tv.app.tv.QuickConnectChannel.requestSync(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        quickAction(intent)?.let { action ->
            if (profileReady.value == true) runQuickAction(action) else pendingQuickAction = action
        }
        GlobalScope.launch(Dispatchers.IO) { handleImportIntent(intent) }
    }

    /** One of the app's own actions (shortcuts, tile, widget), unless reopened from Recents. */
    private fun quickAction(intent: Intent?): Intent? {
        if (intent == null || AppShortcuts.idFor(intent.action) == null) return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return intent
    }

    private fun runPendingQuickAction() {
        if (isDestroyed) return
        val action = pendingQuickAction ?: return
        pendingQuickAction = null
        runQuickAction(action)
    }

    /**
     * Connect and disconnect take the button's own path; a quick action
     * without the app's token (another app starting this exported activity)
     * only opens Home.
     */
    private fun runQuickAction(intent: Intent) {
        val id = AppShortcuts.idFor(intent.action) ?: return
        if (!AppShortcuts.trusted(intent)) return
        when (intent.action) {
            AppShortcuts.ACTION_CONNECT -> {
                reportShortcutUse(id)
                val server = intent.getStringExtra(AppShortcuts.EXTRA_SERVER)
                if (server != null && !chooseServer(server, intent.getLongExtra(AppShortcuts.EXTRA_PROFILE, -1L))) return
                routeRequests.trySend(Routes.HOME)
                if (BoxService.globalStatus.value == com.vpn4tv.app.constant.Status.Stopped) connect()
            }
            AppShortcuts.ACTION_DISCONNECT -> {
                reportShortcutUse(id)
                routeRequests.trySend(Routes.HOME)
                // Always-on VPN: Android turns it off; Home says so.
                if (BoxService.globalStatus.value == com.vpn4tv.app.constant.Status.Started &&
                    BoxService.alwaysOn.value == null
                ) {
                    BoxService.stop()
                }
            }
            AppShortcuts.ACTION_SERVERS -> {
                reportShortcutUse(id)
                routeRequests.trySend(Routes.SERVERS)
            }
            AppShortcuts.ACTION_ADD_KEY -> {
                reportShortcutUse(id)
                routeRequests.trySend(Routes.ADD_PROFILE)
            }
        }
    }

    /**
     * A card of the TV home screen's channel: its server becomes the key's
     * choice, as on the Servers screen (now if connected, at the start
     * otherwise). A card of a key no longer selected only opens Home, and the
     * channel catches up. False: no connect then.
     */
    private fun chooseServer(server: String, profileId: Long): Boolean {
        if (profileId != Settings.selectedProfile) {
            com.vpn4tv.app.tv.QuickConnectChannel.requestSync(this)
            return false
        }
        saveSelectedServer(this, server)
        if (BoxService.globalStatus.value == com.vpn4tv.app.constant.Status.Started) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val client = io.nekohasekai.libbox.Libbox.newStandaloneCommandClient()
                    client.selectOutbound("select", server)
                    client.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "server from the channel not selected: ${e.message}")
                }
            }
        }
        return true
    }

    private fun reportShortcutUse(id: String) {
        GlobalScope.launch(Dispatchers.IO) { AppShortcuts.reportUsed(applicationContext, id) }
    }

    // TV remotes double-fire BACK easily (contact bounce + key repeat).
    // navigation-compose's internal PredictiveBackHandler launches a gesture
    // coroutine per BACK dispatch; a second dispatch before the first pop
    // recomposes captures an already-popped entry and crashes with
    // "Cannot transition entry that is not in the back stack"
    // (NavControllerImpl.prepareForTransition — vc50326, 12 reports).
    // The handler is internal to NavHost, so the dispatch itself is the
    // only place we can debounce: the key event on Android 12 and older (and
    // on any device without predictive back), [tvBackGuard] on a TV with
    // Android 13+, where the app's predictive back (the manifest) takes BACK
    // away from key events.
    private var lastBackEventTime = 0L

    /** The double-BACK debounce: true when this BACK comes too soon after the last one. */
    private fun duplicateBack(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackEventTime < 300) return true
        lastBackEventTime = now
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            if (duplicateBack()) return true // swallow the duplicate
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * TV, Android 13+: the app takes predictive back for phones (the manifest),
     * and then BACK reaches no key event. This callback, above the app's own,
     * gets every BACK of the activity's window first and does what the key
     * path did before: drops a second one within 300 ms and hands the rest to
     * the same dispatcher (the screens' handlers, NavHost's, else the system's
     * Back, which sends the app behind the launcher). Dialogs have windows and
     * callbacks of their own. The TV has no back gesture to preview.
     */
    private var tvBackGuard: android.window.OnBackInvokedCallback? = null

    private fun installTvBackGuard() {
        if (!Device.isTv || android.os.Build.VERSION.SDK_INT < 33) return
        val guard = android.window.OnBackInvokedCallback {
            if (!duplicateBack()) onBackPressedDispatcher.onBackPressed()
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, guard,
        )
        tvBackGuard = guard
    }

    override fun onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            tvBackGuard?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        tvBackGuard = null
        super.onDestroy()
    }

    /**
     * Handles a VIEW intent carrying a subscription URL (vpn://, wg://, vless://,
     * etc.) or a wg-quick / AmneziaWG .conf / .wg / .vpn file by running it
     * through ProxyParser and creating a new local profile. Used for
     * `adb shell am start -a android.intent.action.VIEW -d <URL>` in development
     * and for share-sheet imports from other apps.
     */
    private suspend fun handleImportIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val data: String = when (uri.scheme) {
            "file", "content" -> try {
                // Hard cap on import size: a real subscription / .conf /
                // .vpn file is at most a few hundred KB, so 2 MB is
                // already 10x worst case. Without this cap a user who
                // accidentally picked a video or log file blew up our
                // heap with OutOfMemoryError in handleImportIntent
                // (Vitals report 2026-04-15 on 50016).
                val maxBytes = 2 * 1024 * 1024
                contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(maxBytes + 1)
                    var read = 0
                    while (read < buf.size) {
                        val n = input.read(buf, read, buf.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read > maxBytes) {
                        Log.w(TAG, "Import file > 2 MB, refusing: $uri")
                        AppMessages.post(AppMessage.ImportTooLarge)
                        return
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } ?: return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read intent file", e)
                return
            }
            else -> uri.toString()
        }
        // vpn4tv:// wraps whatever the user would otherwise paste. Unwrap before
        // deciding: a subscription URL inside must stay updatable, not become a
        // one-off snapshot.
        val unwrapped = ProxyParser.decodeVpn4tvLink(data) ?: data
        if (unwrapped.trim().matches(Regex("^https?://\\S+$"))) {
            try {
                createProfile(Uri.parse(unwrapped.trim()).host ?: "VPN4TV", unwrapped.trim())
                profileImported.postValue((profileImported.value ?: 0) + 1)
                AppMessages.post(AppMessage.KeyAdded)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import subscription from link: ${e.javaClass.simpleName}: ${com.vpn4tv.app.utils.LogPrivacy.redactLinks(e.message)}")
            }
            return
        }
        try {
            val proxies = ProxyParser.parseSubscription(unwrapped)
            if (proxies.isEmpty()) {
                // Not the data itself: it is a key.
                Log.w(TAG, "Intent data parsed to zero proxies (${data.length} chars, ${com.vpn4tv.app.utils.LogPrivacy.link(data.trim())})")
                return
            }
            val profilesDir = File(filesDir, "profiles")
            profilesDir.mkdirs()
            val nextId = ProfileManager.nextFileID()
            val configPath = File(profilesDir, "$nextId.json").absolutePath
            val result = ConfigGenerator.generateFull(proxies)
            ConfigGenerator.writeAll(configPath, result)
            // Name a profile after its first proxy's tag — but fall back to
            // the server hostname when the tag is generic ("Server 1",
            // "vless", etc.) so multiple imports from different AmneziaVPN
            // templates stay distinguishable. Then dedupe via uniqueName
            // so a second import with the same base gets a " 2" suffix.
            val first = proxies.firstOrNull()
            val tag = first?.tag?.trim().orEmpty()
            val host = first?.server?.trim().orEmpty()
            val base = when {
                tag.isNotEmpty() && !isGenericProxyTag(tag) -> tag
                host.isNotEmpty() -> if (tag.isNotEmpty()) "$tag ($host)" else host
                tag.isNotEmpty() -> tag
                else -> "Imported"
            }
            val name = ProfileManager.uniqueName(base)
            val profile = ProfileManager.create(
                Profile(name = name, userOrder = ProfileManager.nextOrder()).apply {
                    typed.type = TypedProfile.Type.Local
                    typed.path = configPath
                }
            )
            Settings.selectedProfile = profile.id
            profileImported.postValue((profileImported.value ?: 0) + 1)
            Log.d(TAG, "Imported profile via intent: $name (${proxies.size} proxies)")
        } catch (e: Exception) {
            Log.e(TAG, "Import intent failed: ${com.vpn4tv.app.utils.LogPrivacy.error(e)}")
        }
    }

    private fun connect() {
        // A new attempt: the reason the last one ended no longer applies. A
        // start that fails says its own; one stopped here, a message.
        BoxService.lastError.value = null
        if (profileReady.value != true || Settings.selectedProfile == -1L) {
            AppMessages.post(AppMessage.NoKey)
            return
        }

        // If cached subscription-userinfo says the subscription expired,
        // refresh it BEFORE connecting. Users whose key expired used to hit
        // "server unavailable" errors repeatedly because the old config had
        // dead server addresses — they had to guess that "refresh" was the
        // fix. Now we do it automatically: fetch the subscription, write
        // fresh config, then proceed to connect as usual. The refresh is
        // async + says so, so the user knows something is happening.
        val profileId = Settings.selectedProfile
        val cachedInfo = com.vpn4tv.app.converter.HwidService.loadUserInfo(this, profileId)
        val expired = cachedInfo?.expireEpochSec?.let { it > 0 && it * 1000 < System.currentTimeMillis() } == true
        if (expired) {
            AppMessages.post(AppMessage.RefreshingExpiredKey)
            GlobalScope.launch(Dispatchers.IO) {
                val profile = ProfileManager.get(profileId) ?: return@launch
                if (profile.typed.remoteURL.isNotEmpty()) {
                    try {
                        updateProfileConfig(profile)
                    } catch (_: Exception) {}
                }
                withContext(Dispatchers.Main) { doConnect() }
            }
            return
        }

        doConnect()
    }

    private fun doConnect() {
        // Proxy mode runs sing-box as a local SOCKS5 listener inside a plain
        // foreground service — no VpnService binding, so no system permission
        // dialog. Skip VpnService.prepare entirely; asking for it on a
        // SberBox-class device would raise the dialog the vendor removed.
        if (Settings.isProxyMode) {
            BoxService.start()
            return
        }
        val intent = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            // Some vendor TV ROMs (TCL G08, SberBox-class) return a prepare
            // intent but ship no com.android.vpndialogs — prepare() itself
            // can also throw on the broken ones. Treat as "no VPN dialog".
            Log.w(TAG, "VpnService.prepare failed: ${e.message}")
            onNoVpnDialog()
            return
        }
        Settings.vpnPermissionSeen = intent == null
        if (intent != null) {
            try {
                vpnPermissionLauncher.launch(intent)
            } catch (e: RuntimeException) {
                // The consent activity (com.android.vpndialogs) is absent on
                // this ROM — launching the intent crashes. Guide the user to
                // Proxy mode instead of dying (Vitals: MainActivity.doConnect
                // ActivityNotFoundException, TCL G08 / Android 12). Some ROMs
                // fail inside the system process instead and the start throws
                // its NullPointerException (crash group 18f02415f4: LG MYSTERY
                // TV, Android 6).
                Log.w(TAG, "VPN consent dialog missing: ${e.javaClass.simpleName}: ${e.message}")
                onNoVpnDialog()
            }
        } else {
            BoxService.start()
        }
    }

    /** Device has no system VPN-permission dialog: can't bind VpnService.
     *  Point the user at Proxy mode (SOCKS5), which needs no consent: a phone
     *  opens Settings from the snackbar, a TV turns it on from the dialog. */
    private fun onNoVpnDialog() {
        AppMessages.post(AppMessage.NoVpnDialog)
    }

    /**
     * Returns the active profile when it still has to be refreshed from its
     * subscription and has a config on disk to connect with meanwhile. One
     * without a config is fetched here, before the home screen.
     */
    private suspend fun ensureDefaultProfile(): Profile? {
        try {
            // Check for migrated profiles from hiddify fork
            val migrationPrefs = applicationContext.getSharedPreferences("migration", android.content.Context.MODE_PRIVATE)
            val migrateUrls = migrationPrefs.getString("v5_migrate_urls", null)
            val migrateNames = migrationPrefs.getString("v5_migrate_names", null)
            if (migrateUrls != null && migrateNames != null) {
                val urls = migrateUrls.split("\n").filter { it.isNotBlank() }
                val names = migrateNames.split("\n")
                Log.d(TAG, "Migrating ${urls.size} profiles from hiddify fork")
                for (i in urls.indices) {
                    val name = names.getOrElse(i) { "Key" }
                    val url = urls[i]
                    createProfile(name, url)
                }
                migrationPrefs.edit().remove("v5_migrate_urls").remove("v5_migrate_names").apply()
                Log.d(TAG, "Migration complete")
            }

            val profiles = ProfileManager.list()
            if (profiles.isNotEmpty()) {
                if (Settings.selectedProfile == -1L) {
                    Settings.selectedProfile = profiles.first().id
                }
                val active = profiles.find { it.id == Settings.selectedProfile } ?: profiles.first()
                if (active.typed.remoteURL.isNotEmpty()) {
                    if (File(active.typed.path).length() > 0) return active
                    updateProfileConfig(active)
                }
            }
            // No profiles — user will be prompted to add via Telegram
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init profiles", e)
        }
        return null
    }

    private suspend fun createProfile(name: String, url: String) {
        val profilesDir = File(filesDir, "profiles")
        profilesDir.mkdirs()
        val nextId = ProfileManager.nextFileID()
        val configPath = File(profilesDir, "${nextId}.json").absolutePath

        val profile = ProfileManager.create(
            Profile(name = name, userOrder = ProfileManager.nextOrder()).apply {
                typed.type = TypedProfile.Type.Remote
                typed.remoteURL = url
                typed.path = configPath
                typed.autoUpdate = true
                typed.autoUpdateInterval = 60
            }
        )

        updateProfileConfig(profile)
        Settings.selectedProfile = profile.id
    }

    private fun updateProfileConfig(profile: Profile) {
        try {
            val outcome = kotlinx.coroutines.runBlocking {
                com.vpn4tv.app.converter.SubscriptionUpdater.update(applicationContext, profile)
            }
            Log.d(TAG, "Config updated: config=${outcome.configChanged} bridges=${outcome.bridgesChanged}")
            // Opening the app while connected: new keys go live now.
            com.vpn4tv.app.converter.SubscriptionUpdater.applyToRunningService(profile, outcome)
        } catch (e: Exception) {
            Log.e(TAG, "Config update failed: ${e.message}")
        }
    }
}

/**
 * A proxy tag is "generic" when it doesn't carry location/identity info —
 * e.g. AmneziaVPN's default "Server 1" labels, or bare protocol names.
 * Profile-naming falls back to the server hostname for these so multiple
 * imports don't all land on identical names.
 */
internal fun isGenericProxyTag(tag: String): Boolean {
    val normalized = tag.trim().lowercase()
    if (normalized.isEmpty()) return true
    if (normalized.matches(Regex("^server\\s*\\d*$"))) return true
    return normalized in setOf(
        "vless", "vmess", "trojan", "ss", "shadowsocks",
        "hysteria", "hysteria2", "hy2", "tuic",
        "wireguard", "wg", "awg",
        "proxy", "default", "imported", "subscription", "key",
    )
}
