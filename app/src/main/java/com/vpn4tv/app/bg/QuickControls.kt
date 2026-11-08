package com.vpn4tv.app.bg

import android.content.Context
import android.os.Build
import android.util.Log
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.ui.AppShortcuts
import com.vpn4tv.app.ui.Device
import com.vpn4tv.app.widget.VpnWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Keeps the controls outside the app in step with the VPN: the Quick
 * Settings tile, the home-screen widget and the Connect/Disconnect shortcut.
 * Phones and tablets only; a TV has none of them.
 *
 * Every change (the VPN's state, always-on VPN, the server, the keys) asks
 * for one pass that reads the current state: a burst of changes costs one
 * pass, and a pass never shows a state older than the last change. The
 * first pass, at process start, also mends a tile or widget left showing a
 * VPN that died with the previous process.
 */
object QuickControls {
    private const val TAG = "QuickControls"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val passes = Channel<Unit>(Channel.CONFLATED)
    private var lastTrafficPass = 0L

    /** From Application.onCreate, on the main thread (LiveData observers). */
    fun init(context: Context) {
        if (Device.isTv) return
        val app = context.applicationContext
        scope.launch {
            for (pass in passes) {
                try {
                    sync(app)
                } catch (e: Exception) {
                    Log.w(TAG, "quick controls not updated: ${e.message}")
                }
            }
        }
        BoxService.globalStatus.observeForever { changed() }
        BoxService.alwaysOn.observeForever { changed() }
        ServiceNotification.currentServer.observeForever { changed() }
        // The tall widget's traffic: once a minute at most, not every second.
        ServiceNotification.currentTraffic.observeForever { traffic ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (traffic == null || now - lastTrafficPass >= 60_000L) {
                lastTrafficPass = now
                changed()
            }
        }
        // A key added, renamed or deleted: the widget names the key it connects with.
        ProfileManager.registerCallback { changed() }
    }

    /** Something the controls show may have changed. Any thread. */
    fun changed() {
        passes.trySend(Unit)
    }

    private suspend fun sync(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) VpnTileService.requestRedraw(context)
        VpnWidget.refresh(context)
        AppShortcuts.syncToggle(context)
    }
}
