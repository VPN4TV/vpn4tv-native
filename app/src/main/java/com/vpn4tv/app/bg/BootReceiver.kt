package com.vpn4tv.app.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.vpn4tv.app.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
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
                    // Wait for the network before auto-connecting. At
                    // BOOT_COMPLETED Wi-Fi is often not up yet; starting the VPN
                    // with no network makes Box.Start's DoH-over-HTTPS dns-direct
                    // lookup hang indefinitely on a minority of TVs — the
                    // "reinstall fixes / reboot brings it back" connect hang
                    // (manual connect works because the network is up by then).
                    // Poll up to ~25s; if the network never arrives, skip rather
                    // than start into a guaranteed hang (the user connects
                    // manually when they actually use the TV).
                    var waited = 0
                    while (!hasNetwork(context) && waited < 25_000) {
                        delay(1000); waited += 1000
                    }
                    if (hasNetwork(context)) {
                        Log.d("BootReceiver", "network ready after ${waited}ms — auto-connecting")
                        BoxService.start()
                    } else {
                        Log.w("BootReceiver", "no network ${waited}ms after boot — skipping auto-connect")
                    }
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

    private fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true // can't tell — don't block the connect
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
