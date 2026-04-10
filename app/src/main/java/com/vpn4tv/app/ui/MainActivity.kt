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

        // Load profiles in background
        GlobalScope.launch(Dispatchers.IO) {
            ensureDefaultProfile()
            profileReady.postValue(true)
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

    private fun connect() {
        if (profileReady.value != true || Settings.selectedProfile == -1L) {
            Toast.makeText(this, getString(R.string.error_no_subscription), Toast.LENGTH_SHORT).show()
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
            val profiles = ProfileManager.list()
            if (profiles.isNotEmpty()) {
                if (Settings.selectedProfile == -1L) {
                    Settings.selectedProfile = profiles.first().id
                }
                // Update active profile config
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
            val subContent = com.vpn4tv.app.converter.HwidService.downloadSubscription(applicationContext, profile.typed.remoteURL)
            val proxies = ProxyParser.parseSubscription(subContent)
            if (proxies.isEmpty()) {
                Log.w(TAG, "No proxies in subscription")
                return
            }
            val config = ConfigGenerator.generate(proxies)
            File(profile.typed.path).writeText(config)
            Log.d(TAG, "Config updated: ${proxies.size} proxies")
        } catch (e: Exception) {
            Log.e(TAG, "Config update failed: ${e.message}")
        }
    }
}
