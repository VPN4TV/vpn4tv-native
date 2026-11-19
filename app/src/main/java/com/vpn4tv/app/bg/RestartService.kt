package com.vpn4tv.app.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Brings the VPN back after Android ends the app's process ("Restart the VPN after Android closes the app",
 * Settings.restartAfterKill): a started service with nothing to do but be created again by Android (START_STICKY)
 * and start the VPN then. It runs while the VPN runs with the setting on.
 *
 * The VPN's own service cannot ask for that restart itself, as it did in 5.6.2 and 5.6.3. When its process ends, the
 * tunnel goes with it, and Android's VPN code unbinds from the service (Vpn.interfaceRemoved). If that comes before
 * the activity manager has seen the process die, the unbind fails on the dead process, the service is taken off the
 * process's list (ActiveServices: "Exception when unbinding service", serviceProcessGoneLocked), and at the death of
 * the process no restart is scheduled for it: the service stays listed as started, with no process, for good.
 * Which comes first is a race. On the emulator, Android 14, the unbind came first in twelve kills of thirteen on
 * 2026-11-18, and both kills made with the VPN's service still sticky lost its restart; on the Mi Box, Android 9,
 * the death came first all three times. Nothing binds to this service, so the activity manager schedules its
 * restart whatever the order.
 *
 * The restart is known at [onCreate], by the service being created without this process having asked for it, and
 * not by a start command with no intent: Android 9 (through 11, by its code) takes a service that it has just
 * created again in the background for one of an idle app and stops it at once, before any start command ("Stopping
 * service due to app idle", seen on the Mi Box); only the creation still reaches the app. Android 12 and later let
 * such a service be.
 */
class RestartService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!askedForHere) bringBack()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** Off the main thread: the settings are read from storage, and the process has only just started. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun bringBack() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // The user turned the VPN or the setting off, and the process ended before this service was
                // stopped.
                if (!Settings.startedByUser || !Settings.restartAfterKill) {
                    stopSelf()
                    return@launch
                }
                // The third restart within an hour switches the setting off; it still brings the VPN up.
                if (!StickyRestart.sticky(this@RestartService, restartedBySystem = true)) stopSelf()
                if (BoxService.globalStatus.value == Status.Stopped) BoxService.start()
            } catch (t: Throwable) {
                Log.w(TAG, "the VPN was not started again: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    companion object {
        private const val TAG = "RestartService"

        /** This process has started the service ([sync]); one created without that was created by Android. */
        @Volatile
        private var askedForHere = false

        /**
         * While the VPN runs: this service runs with the setting on and not with it off. Called at every start of
         * the VPN (its service is in the foreground by then, so Android lets a service start) and when the setting
         * changes.
         */
        fun sync(context: Context) {
            val intent = Intent(context, RestartService::class.java)
            try {
                if (Settings.restartAfterKill) {
                    askedForHere = true
                    context.startService(intent)
                } else {
                    context.stopService(intent)
                }
            } catch (e: Exception) {
                // IllegalStateException: Android takes the app for one in the background.
                Log.w(TAG, "the restart after a kill is not armed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /** The VPN stopped: nothing to bring back. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RestartService::class.java)) }
        }
    }
}
