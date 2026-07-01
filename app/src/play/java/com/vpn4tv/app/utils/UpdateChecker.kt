package com.vpn4tv.app.utils

import android.content.Context

/**
 * Google Play build: NO self-update. Google Play's Device & Network Abuse
 * policy forbids a Play-distributed app from updating/installing itself from
 * any source other than Play, so this flavor ships a no-op — no appcast fetch,
 * no APK URL, no install intent. Play delivers updates via its own mechanism
 * (see PlayUpdateChecker). The real appcast updater lives in src/direct.
 */
object UpdateChecker {
    fun check(): UpdateInfo? = null

    @Suppress("UNUSED_PARAMETER")
    fun openDownload(context: Context, update: UpdateInfo) { /* no-op on Play */ }
}
