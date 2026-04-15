package com.vpn4tv.app.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.lifecycle.MutableLiveData
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.converter.ConfigGenerator
import com.vpn4tv.app.converter.ProxyParser
import com.vpn4tv.app.database.Profile
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        val profileReady = MutableLiveData(false)
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            BoxService.start()
        } else {
            Toast.makeText(this, getString(R.string.error_vpn_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Load profiles in background
        GlobalScope.launch(Dispatchers.IO) {
            ensureDefaultProfile()
            profileReady.postValue(true)
            handleImportIntent(intent)
        }

        setContent {
            com.vpn4tv.app.ui.theme.VPN4TVTheme {
                AppNavigation(
                    onConnect = { connect() },
                    onDisconnect = { BoxService.stop() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GlobalScope.launch(Dispatchers.IO) { handleImportIntent(intent) }
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
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read intent file", e)
                return
            }
            else -> uri.toString()
        }
        try {
            val proxies = ProxyParser.parseSubscription(data)
            if (proxies.isEmpty()) {
                Log.w(TAG, "Intent URL parsed to zero proxies: ${data.take(40)}...")
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
            Log.d(TAG, "Imported profile via intent: $name (${proxies.size} proxies)")
        } catch (e: Exception) {
            Log.e(TAG, "Import intent failed", e)
        }
    }

    private fun connect() {
        if (profileReady.value != true || Settings.selectedProfile == -1L) {
            Toast.makeText(this, getString(R.string.error_no_subscription), Toast.LENGTH_SHORT).show()
            return
        }
        // Proxy mode runs sing-box as a local SOCKS5 listener inside a plain
        // foreground service — no VpnService binding, so no system permission
        // dialog. Skip VpnService.prepare entirely; asking for it on a
        // SberBox-class device would raise the dialog the vendor removed.
        if (Settings.isProxyMode) {
            BoxService.start()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            BoxService.start()
        }
    }

    private suspend fun ensureDefaultProfile() {
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
                    val name = names.getOrElse(i) { "Subscription" }
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
                    updateProfileConfig(active)
                }
            }
            // No profiles — user will be prompted to add via Telegram
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init profiles", e)
        }
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
            val sub = com.vpn4tv.app.converter.HwidService.fetchSubscription(
                applicationContext, profile.typed.remoteURL,
            )
            val proxies = ProxyParser.parseSubscription(sub.body)
            if (proxies.isEmpty()) {
                Log.w(TAG, "No proxies in subscription")
                return
            }
            val result = ConfigGenerator.generateFull(proxies)
            ConfigGenerator.writeAll(profile.typed.path, result)
            var nameChanged = false
            if (!sub.title.isNullOrBlank() && sub.title != profile.name) {
                profile.name = sub.title
                nameChanged = true
            }
            sub.updateIntervalHours?.let { hours ->
                profile.typed.autoUpdateInterval = (hours.coerceAtLeast(1) * 60)
                nameChanged = true
            }
            if (nameChanged) {
                kotlinx.coroutines.runBlocking { ProfileManager.update(profile) }
            }
            Log.d(TAG, "Config updated: ${proxies.size} proxies, xray=${result.xrayJson != null}, outline=${result.outlineJson != null}")
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
        "proxy", "default", "imported", "subscription",
    )
}
