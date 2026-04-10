package com.vpn4tv.app.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vpn4tv.app.database.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.d("BootReceiver", "Boot/update: startedByUser=${Settings.startedByUser}")

        if (Settings.autoConnectOnBoot && Settings.startedByUser) {
            BoxService.start()
        }
    }
}
