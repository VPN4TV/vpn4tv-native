package com.vpn4tv.app.utils

import android.content.Context

/**
 * Detects and records the "native library never loaded" condition.
 *
 * On some cheap Android TV firmware (Xiaomi TV Stick 4K and similar) Play's
 * per-ABI split delivery is broken: the base APK installs but the device never
 * receives its ABI split with libbox.so. The first Libbox.* touch then throws
 * UnsatisfiedLinkError inside go.Seq.<clinit>; ART marks go.Seq erroneous, so
 * every later libbox reference (startCommandServer, CommandClient.connect,
 * createSetupOptions) throws NoClassDefFoundError.
 *
 * Crucially these are *Error*s, not Exceptions — `catch (e: Exception)` lets
 * them through and the app crashes. We catch Throwable at each libbox touch
 * point, recognise this signature, and surface a "reinstall from bell.a4e.ar"
 * message instead of crashing (the direct universal APK carries both ABIs, so
 * it always has the native lib). It's a tiny cluster (~5 users/28d), not worth
 * shipping both ABIs to every storage-constrained TV — so we guide recovery.
 */
object NativeLib {
    private const val PREFS = "diagnostics"
    private const val KEY_BROKEN = "native_lib_broken"

    /** True if t (or anything in its cause chain) is a native-lib load failure. */
    fun isLoadFailure(t: Throwable?): Boolean {
        var cur = t
        var depth = 0
        while (cur != null && depth < 10) {
            if (cur is UnsatisfiedLinkError ||
                cur is NoClassDefFoundError ||
                cur is ExceptionInInitializerError
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    fun markBroken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BROKEN, true).apply()
    }

    fun isBroken(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BROKEN, false)
}
