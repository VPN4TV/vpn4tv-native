package com.vpn4tv.app.bg

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import com.vpn4tv.app.R
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.ui.AppShortcuts

/**
 * The VPN4TV tile in Quick Settings (phones and tablets, Android 7+): a tap
 * connects or disconnects, the tile shows the state and, connected, the
 * server (the same one, and under the same setting, as the notification).
 * Active mode: the app asks for a redraw when the VPN changes
 * ([QuickControls]) instead of being bound each time the panel opens.
 * Android drops that request while the tile already listens, and since
 * Android 15 a tap keeps the tile listening until the panel closes (before,
 * the tile's own update ended it), so a listening tile follows the VPN
 * itself: otherwise its own tap would leave it showing the old state.
 */
@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {
    private val redraw = Observer<Any?> { draw() }

    companion object {
        private const val TAG = "VpnTileService"

        fun component(context: Context) = ComponentName(context, VpnTileService::class.java)

        /** The VPN changed: the tile is drawn again (a no-op where it was never added). */
        fun requestRedraw(context: Context) {
            try {
                requestListeningState(context, component(context))
            } catch (e: Exception) {
                Log.w(TAG, "tile redraw not requested: ${e.message}")
            }
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Settings.tileAdded = true
        draw()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Settings.tileAdded = false
    }

    override fun onStartListening() {
        super.onStartListening()
        // Each draws at once with the current state, then on every change.
        BoxService.globalStatus.observeForever(redraw)
        ServiceNotification.currentServer.observeForever(redraw)
    }

    override fun onStopListening() {
        BoxService.globalStatus.removeObserver(redraw)
        ServiceNotification.currentServer.removeObserver(redraw)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        // What the user saw: lit means connected (connecting is drawn lit too).
        val offer = if (tile.state == Tile.STATE_ACTIVE) QuickToggle.Offer.Disconnect else QuickToggle.Offer.Connect
        when (QuickToggle.decide(offer, QuickToggle.facts(this))) {
            // The tile follows the start through QuickControls; a refused one
            // (proxy mode from the background on Android 12+) goes through the app.
            QuickToggle.Step.Start -> if (!BoxService.start()) openApp()
            // Google: an action that is unsafe on a locked device waits for the
            // unlock. Anyone holding the phone could turn the VPN off otherwise.
            QuickToggle.Step.Stop -> if (isLocked) unlockAndRun { BoxService.stop() } else BoxService.stop()
            QuickToggle.Step.OpenApp -> openApp()
            QuickToggle.Step.Redraw -> draw()
        }
    }

    /** The app's connect, as its button does it: the VPN permission dialog comes up at once. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = AppShortcuts.intent(this, AppShortcuts.ACTION_CONNECT)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "app not opened from the tile: ${e.message}")
        }
    }

    private fun draw() {
        val tile = qsTile ?: return
        val status = BoxService.globalStatus.value ?: Status.Stopped
        val look = TileLook.of(status, ServiceNotification.currentServer.value)
        tile.state = if (look.lit) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.contentDescription = getString(R.string.tile_label)
        val stateText = getString(look.state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = look.server ?: stateText
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = stateText
        tile.updateTile()
    }
}

/** How the tile looks for a state: lit, the state's words, and the server once connected. */
data class TileLook(val lit: Boolean, val state: Int, val server: String?) {
    companion object {
        fun of(status: Status, server: String?): TileLook = when (status) {
            Status.Started -> TileLook(true, R.string.status_connected, server?.takeIf { it.isNotBlank() })
            Status.Starting -> TileLook(true, R.string.status_connecting, null)
            Status.Stopping -> TileLook(false, R.string.status_disconnecting, null)
            Status.Stopped -> TileLook(false, R.string.status_disconnected, null)
        }
    }
}
