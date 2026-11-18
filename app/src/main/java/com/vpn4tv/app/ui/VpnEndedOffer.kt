package com.vpn4tv.app.ui

import android.content.Context
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.utils.ExitReasons

/**
 * After Android ended the app while the VPN ran (ExitReasons.vpnEnded; support and crash group b9d6620551: the VPN
 * stayed off until the app was opened again), the way back, once a week at most (owner 2026-11-17): where Android
 * has its VPN settings, always-on VPN there; elsewhere (most TVs) the app's restart after a kill (StickyRestart).
 * Nothing when always-on runs the VPN or the restart is on already. Shown as a dialog on the phone and the TV.
 *
 * When TCL's own guard ended it (crash group fafb5b4664; owner 2026-11-18: «на tcl можно пояснялово сделать в таком
 * случае»), the dialog explains the guard's setting, with the restart offered beside it, or, with the restart on and
 * the VPN still off, the setting alone.
 */
object VpnEndedOffer {
    internal const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    /** Whether to offer at [now]: not when always-on runs the VPN, the restart is on, or an offer came this week. */
    internal fun due(alwaysOn: Boolean, restartOn: Boolean, lastOfferedAt: Long, now: Long): Boolean =
        !alwaysOn && !restartOn && weekPassed(lastOfferedAt, now)

    /**
     * After TCL's guard: the way out is in TCL's own app, so the explanation comes with the restart on too, unless
     * the restart brought the VPN back. Once a week at most, like the other offers.
     */
    internal fun dueTclGuard(restartOn: Boolean, vpnRunning: Boolean, lastOfferedAt: Long, now: Long): Boolean =
        !(restartOn && vpnRunning) && weekPassed(lastOfferedAt, now)

    private fun weekPassed(lastOfferedAt: Long, now: Long): Boolean =
        lastOfferedAt <= 0L || now - lastOfferedAt !in 0 until WEEK_MS

    /** While the app's screen is up and ExitReasons.vpnEnded is set. */
    fun check(context: Context) {
        val ended = ExitReasons.takeVpnEnded(context) ?: return
        val now = System.currentTimeMillis()
        if (ended.byTclGuard) {
            val running = BoxService.globalStatus.value.let { it == Status.Started || it == Status.Starting }
            if (!dueTclGuard(Settings.restartAfterKill, running, Settings.vpnEndedOfferedAt, now)) return
            Settings.vpnEndedOfferedAt = now
            AppMessages.post(if (Settings.restartAfterKill) AppMessage.TclGuardNotice else AppMessage.TclGuardOffer)
            return
        }
        if (!due(BoxService.alwaysOn.value != null, Settings.restartAfterKill, Settings.vpnEndedOfferedAt, now)) return
        Settings.vpnEndedOfferedAt = now
        AppMessages.post(if (hasVpnSettings(context)) AppMessage.AlwaysOnOffer else AppMessage.RestartOffer)
    }

    /** The dialog's button. */
    fun accept(context: Context, message: AppMessage) {
        when (message) {
            AppMessage.AlwaysOnOffer -> openVpnSettings(context)
            AppMessage.RestartOffer, AppMessage.TclGuardOffer -> {
                Settings.restartAfterKill = true
                BoxService.refreshRestartAfterKill()
            }
            else -> {}
        }
    }
}
