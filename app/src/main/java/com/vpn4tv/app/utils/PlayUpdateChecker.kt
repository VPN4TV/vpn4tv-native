package com.vpn4tv.app.utils

import android.app.Activity
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Play Store in-app update checker with graceful no-Play fallback.
 *
 * Two update paths exist in VPN4TV:
 *
 *  1. [UpdateChecker] — polls our own sparkle-style appcast on
 *     `bell.a4e.ar` for direct-APK installs. Shows a banner on the
 *     HomeScreen, user taps to download.
 *
 *  2. [PlayUpdateChecker] (this class) — talks to Google Play via
 *     [com.google.android.play.core.appupdate.AppUpdateManager]. When a
 *     Play Store release ships with `inAppUpdatePriority >= 4` and the
 *     user's installed version is older, we surface the IMMEDIATE
 *     update flow — a full-screen blocking UI that refuses to let the
 *     user dismiss it until the update installs. Intended for hotfixes
 *     we don't want to wait out the normal auto-update window for
 *     (~24 h).
 *
 * Both paths coexist: Play users get (2) for critical releases, direct
 * users get (1) as an opt-in banner. Only one of them can fire on any
 * given install because a user who installed from Play Store is not
 * tracked by our appcast URL, and a user from `bell.a4e.ar` is not
 * registered with Play.
 *
 * **Important — safety on devices without Google Play Services**: a
 * significant fraction of our TV installs run on vendor boxes where
 * GMS is stripped or crippled (SberBox, OKKO SmartBox, some Chinese
 * TVs) and on direct-APK devices the install itself isn't known to
 * Play. Calling [AppUpdateManagerFactory.create] on those devices is
 * safe — the returned manager exists as a thin IPC wrapper. The call
 * that actually fails is `appUpdateInfo`, which returns a Task that
 * fires its failure listener with `InstallException` instead of
 * throwing. As long as we always attach `addOnFailureListener` and
 * wrap the whole thing in `try/catch`, we never crash the host
 * activity even on a completely GMS-less device.
 */
object PlayUpdateChecker {
    private const val TAG = "PlayUpdateChecker"
    private const val REQ_CODE = 0x5A7E // arbitrary, only used if we later
                                         // migrate to startUpdateFlowForResult

    /** Priority threshold at or above which we force the IMMEDIATE flow. */
    private const val IMMEDIATE_THRESHOLD = 4

    /**
     * Fire-and-forget check. Safe to call from `MainActivity.onCreate`
     * — never blocks, never throws on the caller, never touches UI on
     * the caller's thread beyond the Play-provided update prompt.
     */
    fun maybePromptImmediate(activity: Activity) {
        try {
            val manager = AppUpdateManagerFactory.create(activity)
            manager.appUpdateInfo
                .addOnSuccessListener { info ->
                    val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    val priority = info.updatePriority()
                    val immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    Log.d(
                        TAG,
                        "appUpdateInfo: available=$available priority=$priority immediateAllowed=$immediateAllowed",
                    )
                    if (!available || priority < IMMEDIATE_THRESHOLD || !immediateAllowed) return@addOnSuccessListener
                    try {
                        @Suppress("DEPRECATION") // startUpdateFlowForResult is still the supported path
                        manager.startUpdateFlowForResult(
                            info,
                            AppUpdateType.IMMEDIATE,
                            activity,
                            REQ_CODE,
                        )
                    } catch (e: Throwable) {
                        Log.w(TAG, "startUpdateFlowForResult failed: ${e.message}")
                    }
                }
                .addOnFailureListener { e ->
                    // Typical on non-GMS devices and direct-APK installs.
                    // Do NOT surface to the user — this is expected.
                    Log.d(TAG, "appUpdateInfo unavailable (ok on non-Play installs): ${e.message}")
                }
        } catch (e: Throwable) {
            // Manager construction itself shouldn't fail, but belt-and-
            // braces — if the Play Core stub throws for any reason, we
            // just log and move on.
            Log.w(TAG, "AppUpdateManager init failed: ${e.message}")
        }
    }
}
