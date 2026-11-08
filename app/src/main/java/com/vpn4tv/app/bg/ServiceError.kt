package com.vpn4tv.app.bg

import androidx.annotation.StringRes
import com.vpn4tv.app.R
import com.vpn4tv.app.constant.Alert
import com.vpn4tv.app.converter.SubscriptionUpdater

/**
 * Why the VPN is not running, for the line under the status on Home: what
 * happened and what to do, in the app's language (Google's UX writing: the
 * way out, not the fault). [detail] is the technical text, the core's or the
 * system's words, for support: the line keeps it under «Подробнее», and the
 * system log has it as before.
 */
data class ServiceError(val reason: Reason, val detail: String? = null) {

    enum class Reason(@StringRes val message: Int) {
        /** Anything else that ends a start: the core, a bridge, the command server. */
        StartFailed(R.string.error_start_failed),

        /** The start hung, or the core's files could not be opened: the app restarts itself. */
        Restarting(R.string.error_restarting),

        /** Android would not let the service run in the foreground (a start from the background, at boot). */
        SystemRefused(R.string.error_system_refused),

        /** The VPN permission is gone, mostly to another VPN app (a start at boot or from the notification). */
        NoVpnPermission(R.string.error_no_vpn_permission),

        /** No key, or a key without servers. */
        EmptyKey(R.string.error_empty_key),

        /** The key had to be downloaded before the start and could not be. */
        KeyDownloadFailed(R.string.error_key_download_failed),

        /** A refresh of the key failed (Home's refresh button, the key list). */
        KeyUpdateFailed(R.string.error_key_update_failed),

        /** The native part of the app did not load: reinstall from Google Play. */
        InstallBroken(R.string.error_install_broken),

        /** The system took the VPN away (onRevoke): another VPN app, or the VPN turned off in Android's settings. */
        Revoked(R.string.error_vpn_revoked),
    }

    companion object {
        /** A failed start or reload, by the alert it raised and its message. */
        fun of(type: Alert, message: String?, reason: Reason? = null) =
            ServiceError(reason ?: classify(type, message), technical(type, message))

        /** A failed refresh of the key. */
        fun keyUpdate(e: Throwable) = ServiceError(
            if (e is SubscriptionUpdater.EmptyKeyException) Reason.EmptyKey else Reason.KeyUpdateFailed,
            e.message ?: e.javaClass.simpleName,
        )

        /** The text support reads: the alert and the message as they came, as the line showed it before. */
        internal fun technical(type: Alert, message: String?) = "${type.name}: ${message ?: "unknown"}"

        /**
         * What the user is told. The app's own failures carry their reason
         * from where they happen; this reads the ones whose words come from
         * the core or from Android.
         */
        internal fun classify(type: Alert, message: String?): Reason {
            val m = message.orEmpty().lowercase()
            return when {
                type == Alert.EmptyConfiguration -> Reason.EmptyKey
                // VPNService.openTun: the permission was taken after the app was prepared.
                "missing vpn permission" in m || "not prepared or is revoked" in m -> Reason.NoVpnPermission
                // startForegroundService refused (IllegalStateException, and its Android 12
                // subclass ForegroundServiceStartNotAllowedException), or startForeground itself.
                type == Alert.StartService && ("not allowed" in m || "rejected by system" in m) -> Reason.SystemRefused
                else -> Reason.StartFailed
            }
        }
    }
}
