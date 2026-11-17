package com.vpn4tv.app.ui

import android.content.Context
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.utils.ExitReasons

/**
 * After Android ended the app while the VPN ran (ExitReasons.vpnEnded; support and crash group b9d6620551: the VPN
 * stayed off until the app was opened again), the way back, once a week at most (owner 2026-11-17): where Android
 * has its VPN settings, always-on VPN there; elsewhere (most TVs) the app's restart after a kill (StickyRestart).
 * Nothing when always-on runs the VPN or the restart is on already. Shown as a dialog on the phone and the TV.
 */
object VpnEndedOffer {
    internal const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    /** Whether to offer at [now]: not when always-on runs the VPN, the restart is on, or an offer came this week. */
    internal fun due(alwaysOn: Boolean, restartOn: Boolean, lastOfferedAt: Long, now: Long): Boolean =
        !alwaysOn && !restartOn && (lastOfferedAt <= 0L || now - lastOfferedAt !in 0 until WEEK_MS)

    /** While the app's screen is up and ExitReasons.vpnEnded is set. */
    fun check(context: Context) {
        if (!ExitReasons.takeVpnEnded(context)) return
        val now = System.currentTimeMillis()
        if (!due(BoxService.alwaysOn.value != null, Settings.restartAfterKill, Settings.vpnEndedOfferedAt, now)) return
        Settings.vpnEndedOfferedAt = now
        AppMessages.post(if (hasVpnSettings(context)) AppMessage.AlwaysOnOffer else AppMessage.RestartOffer)
    }

    /** The dialog's button. */
    fun accept(context: Context, message: AppMessage) {
        when (message) {
            AppMessage.AlwaysOnOffer -> openVpnSettings(context)
            AppMessage.RestartOffer -> {
                Settings.restartAfterKill = true
                BoxService.refreshRestartAfterKill()
            }
            else -> {}
        }
    }
}
