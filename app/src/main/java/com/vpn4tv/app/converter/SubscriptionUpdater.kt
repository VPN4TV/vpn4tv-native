package com.vpn4tv.app.converter

import android.content.Context
import android.util.Log
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.Profile
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings
import io.nekohasekai.libbox.Libbox
import java.io.File

/**
 * Refresh a profile from its subscription — one implementation for every path
 * that does it: the refresh buttons, the refresh at app launch, the background
 * updater.
 *
 * The background updater used to write the raw subscription body into the
 * profile and run it through Libbox.checkConfig first. Our subscriptions are
 * lists of links, not sing-box JSON, so the check failed on every run ("invalid
 * character 'v'") and no profile was ever refreshed in the background. The
 * buttons regenerated the config correctly but never applied it: new keys only
 * took effect after a reconnect.
 */
object SubscriptionUpdater {

    private const val TAG = "SubscriptionUpdater"

    class Outcome(
        /** The sing-box config on disk changed. */
        val configChanged: Boolean,
        /** A bridge sidecar changed. */
        val bridgesChanged: Boolean,
        val userInfo: SubscriptionUserInfo?,
    ) {
        val changed: Boolean get() = configChanged || bridgesChanged
    }

    /** Fetch, regenerate the config and its bridge sidecars, update the profile. */
    suspend fun update(context: Context, profile: Profile): Outcome {
        val sub = HwidService.fetchSubscription(context, profile.typed.remoteURL)
        val proxies = ProxyParser.parseSubscription(sub.body)
        if (proxies.isEmpty()) throw IllegalStateException("подписка пуста")
        val result = ConfigGenerator.generateFull(proxies)

        val path = profile.typed.path
        fun current(file: String): String? = File(file).takeIf { it.exists() }?.readText()
        val configChanged = current(path) != result.singboxJson
        val bridgesChanged =
            current(ConfigGenerator.xraySidecarPath(path)) != result.xrayJson ||
                current(ConfigGenerator.outlineSidecarPath(path)) != result.outlineJson ||
                current(ConfigGenerator.wgSidecarPath(path)) != result.wgJson ||
                current(ConfigGenerator.olcrtcSidecarPath(path)) != result.olcrtcJson ||
                current(ConfigGenerator.ttSidecarPath(path)) != result.ttJson
        if (configChanged || bridgesChanged) {
            ConfigGenerator.writeAll(path, result)
        }

        profile.typed.lastUpdated = java.util.Date()
        if (!sub.title.isNullOrBlank() && sub.title != profile.name) {
            profile.name = sub.title
        }
        sub.updateIntervalHours?.let { hours ->
            profile.typed.autoUpdateInterval = hours.coerceAtLeast(1) * 60
        }
        ProfileManager.update(profile)
        HwidService.saveUserInfo(context, profile.id, sub.userInfo)
        return Outcome(configChanged, bridgesChanged, sub.userInfo)
    }

    /**
     * Put a refreshed profile into effect: reload the service when this is the
     * profile it runs and something changed. The reload re-syncs the bridges
     * as well (BoxService.syncBridges), so changed links apply at once.
     * Blocking — call it off the main thread.
     */
    fun applyToRunningService(profile: Profile, outcome: Outcome): Boolean {
        if (!outcome.changed) return false
        if (profile.id != Settings.selectedProfile) return false
        if (BoxService.globalStatus.value != Status.Started) return false
        return try {
            val client = Libbox.newStandaloneCommandClient()
            try {
                client.serviceReload()
            } finally {
                client.disconnect()
            }
            Log.i(TAG, "reloaded with the refreshed ${profile.name}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "reload after refresh failed: ${e.message}")
            false
        }
    }
}
