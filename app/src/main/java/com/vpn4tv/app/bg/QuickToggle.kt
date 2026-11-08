package com.vpn4tv.app.bg

import android.content.Context
import android.net.VpnService
import android.util.Log
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.converter.HwidService
import com.vpn4tv.app.database.Settings

/**
 * Connect and disconnect from outside the app's screen: the Quick Settings
 * tile and the home-screen widget (phones and tablets). A tap is weighed
 * against the VPN's real state, not against what the control showed: a
 * control left showing an old state (the process was killed meanwhile) only
 * catches up instead of doing the opposite of what the user saw.
 */
object QuickToggle {
    private const val TAG = "QuickToggle"

    /** What the control offered when it was tapped. */
    enum class Offer { Connect, Disconnect }

    enum class Step {
        /** Start the service from here, no screen needed. */
        Start,
        Stop,

        /**
         * The app's own connect path, the same as its button: Android's VPN
         * permission dialog, a refresh of an expired key, no key yet; with
         * always-on VPN, Home's word on who turns it off.
         */
        OpenApp,

        /** The control showed an old state, or the VPN is between two: only draw it anew. */
        Redraw,
    }

    data class Facts(
        val status: Status,
        /** Android's always-on VPN runs this one: Android, not the app, turns it off. */
        val alwaysOn: Boolean,
        val hasKey: Boolean,
        val keyExpired: Boolean,
        val proxyMode: Boolean,
        /** VpnService.prepare gave no intent: the VPN permission is there. Null: prepare threw. */
        val vpnPermission: Boolean?,
    )

    fun decide(offer: Offer, facts: Facts): Step = when (facts.status) {
        Status.Starting, Status.Stopping -> Step.Redraw
        Status.Started -> when {
            offer == Offer.Connect -> Step.Redraw
            facts.alwaysOn -> Step.OpenApp
            else -> Step.Stop
        }
        Status.Stopped -> when {
            offer == Offer.Disconnect -> Step.Redraw
            !facts.hasKey || facts.keyExpired -> Step.OpenApp
            // No permission to ask for in proxy mode; Android may still refuse
            // the start from the background (the caller opens the app then).
            facts.proxyMode -> Step.Start
            facts.vpnPermission == true -> Step.Start
            else -> Step.OpenApp
        }
    }

    /**
     * The state a tap is weighed against. Settings and the key's saved expiry
     * only (no profile database), so the tile can call it on its click.
     */
    fun facts(context: Context): Facts {
        val profileId = Settings.selectedProfile
        val expiry = if (profileId != -1L) HwidService.loadUserInfo(context, profileId)?.expireEpochSec else null
        val proxyMode = Settings.isProxyMode
        return Facts(
            status = BoxService.globalStatus.value ?: Status.Stopped,
            alwaysOn = BoxService.alwaysOn.value != null,
            hasKey = profileId != -1L,
            keyExpired = expiry != null && expiry > 0 && expiry * 1000 < System.currentTimeMillis(),
            proxyMode = proxyMode,
            vpnPermission = if (proxyMode) null else vpnPermission(context),
        )
    }

    /** Some vendor TV firmware throws from prepare (no VPN dialog there): unknown. */
    private fun vpnPermission(context: Context): Boolean? = try {
        VpnService.prepare(context) == null
    } catch (e: Exception) {
        Log.w(TAG, "VpnService.prepare failed: ${e.message}")
        null
    }
}
