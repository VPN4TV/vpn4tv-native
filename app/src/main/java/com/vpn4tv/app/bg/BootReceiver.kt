package com.vpn4tv.app.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vpn4tv.app.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // goAsync + IO coroutine: Settings reads hit Room/SharedPreferences
        // and BoxService.start() runBlocks a DB read — at boot, when every
        // app's receiver fires at once and flash I/O is saturated, doing
        // that on the main thread burned the 10s broadcast budget
        // (vc50326 BOOT_COMPLETED ANR cluster, 16 reports). The broadcast
        // budget still applies to goAsync (extended to ~20s), but we no
        // longer block the process main thread that Application.onCreate
        // and service starts also need.
        val pending = goAsync()
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Log.d("BootReceiver", "Boot/update: startedByUser=${Settings.startedByUser}")
                if (Settings.autoConnectOnBoot && Settings.startedByUser) {
                    BoxService.start()
                }
            } catch (t: Throwable) {
                // A Room/SQLite failure at boot must not leave the
                // PendingResult unfinished (system holds the process alive
                // until finish()).
                Log.e("BootReceiver", "Boot auto-connect failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
