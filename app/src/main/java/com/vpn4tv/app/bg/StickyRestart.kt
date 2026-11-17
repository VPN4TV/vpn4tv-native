package com.vpn4tv.app.bg

import android.content.Context
import android.util.Log
import com.vpn4tv.app.database.Settings

/**
 * "Restart the VPN after Android ends the app" (Settings.restartAfterKill): the service asks Android to start it
 * again when its process was ended while the VPN ran (START_STICKY; Android starts it with no intent). Offered
 * where Android's VPN settings have no always-on switch (most TVs; ui/VpnEndedOffer).
 *
 * Owner, 2026-11-17: after three crashes in a row within 60 minutes it switches itself off, so a device short of
 * memory does not end and restart the app in a loop. Every restart by Android follows an end of the process (the
 * low-memory killer, a firmware task killer or a crash), so the restarts are what is counted. The third one still
 * brings the VPN up; the service is no longer sticky after it.
 */
object StickyRestart {
    private const val TAG = "StickyRestart"
    private const val PREFS = "sticky_restart"
    private const val KEY_TIMES = "restart_times"

    internal const val WINDOW_MS = 60 * 60 * 1000L
    internal const val MAX_RESTARTS = 3

    /** The restarts that still count at [now]: those of the last hour, and this start when Android made it. */
    internal fun recent(times: List<Long>, now: Long, restartedBySystem: Boolean): List<Long> =
        times.filter { now - it in 0 until WINDOW_MS } + if (restartedBySystem) listOf(now) else emptyList()

    internal fun tripped(recent: List<Long>): Boolean = recent.size >= MAX_RESTARTS

    internal fun parse(saved: String?): List<Long> = saved.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }

    /**
     * Every start command: whether the service should ask to be started again after its process ends. Counts a
     * start Android made ([restartedBySystem]) and switches the setting off at the third within an hour.
     */
    fun sticky(context: Context, restartedBySystem: Boolean): Boolean {
        if (!Settings.restartAfterKill) return false
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val kept = recent(parse(prefs.getString(KEY_TIMES, null)), System.currentTimeMillis(), restartedBySystem)
            if (restartedBySystem) Log.i(TAG, "started again by Android after the process ended: ${kept.size} within the hour")
            if (tripped(kept)) {
                Settings.restartAfterKill = false
                prefs.edit().remove(KEY_TIMES).apply()
                Log.w(TAG, "$MAX_RESTARTS restarts within an hour: the restart after a kill is switched off")
                false
            } else {
                prefs.edit().putString(KEY_TIMES, kept.joinToString(",")).apply()
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restart state not read: ${t.message}")
            false
        }
    }
}
