package com.vpn4tv.app.ui

import android.content.Context
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.utils.ExitReasons

/**
 * After Android ended the app while the VPN ran (ExitReasons.vpnEnded; support and crash group b9d6620551: the VPN
 * stayed off until the app was opened again), the way back, once a week at most (owner 2026-11-17): the app's
 * restart after a kill (StickyRestart), on a phone and on a TV alike. Nothing when the restart is on already. Shown
 * as a dialog on the phone and the TV.
 *
 * Android's always-on VPN is not a way back, whatever its name says: Android starts it when its switch is turned on,
 * after the device starts and after the app is updated, and not after the app's process was ended (VpnManagerService
 * in Android 14; checked there with always-on on: after kill -9 and after a force-stop the VPN stayed off). 5.6.2 and
 * 5.6.3 offered it wherever Android has its VPN settings, so a phone got an offer that did nothing (owner
 * 2026-11-18: «Переделай как надо»).
 *
 * When TCL's own guard ended it (crash group fafb5b4664; owner 2026-11-18: «на tcl можно пояснялово сделать в таком
 * случае»), the dialog explains the guard's setting, with the restart offered beside it, or, with the restart on and
 * the VPN still off, the setting alone.
 */
object VpnEndedOffer {
    internal const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    /** Whether to offer at [now]: not when the restart is on or an offer came this week. */
    internal fun due(restartOn: Boolean, lastOfferedAt: Long, now: Long): Boolean =
        !restartOn && weekPassed(lastOfferedAt, now)

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
        if (!due(Settings.restartAfterKill, Settings.vpnEndedOfferedAt, now)) return
        Settings.vpnEndedOfferedAt = now
        AppMessages.post(AppMessage.RestartOffer)
    }

    /** The dialog's button. */
    fun accept(message: AppMessage) {
        when (message) {
            AppMessage.RestartOffer, AppMessage.TclGuardOffer -> {
                Settings.restartAfterKill = true
                BoxService.refreshRestartAfterKill()
            }
            else -> {}
        }
    }
}
