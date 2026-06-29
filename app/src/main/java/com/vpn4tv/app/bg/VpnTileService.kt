package com.vpn4tv.app.bg

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    private val statusObserver = androidx.lifecycle.Observer<Status> { updateTileAsync() }

    override fun onStartListening() {
        super.onStartListening()
        BoxService.globalStatus.observeForever(statusObserver)
        updateTileAsync()
    }

    override fun onStopListening() {
        super.onStopListening()
        BoxService.globalStatus.removeObserver(statusObserver)
    }

    override fun onClick() {
        unlockAndRun { handleClickAsync() }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun handleClickAsync() {
        GlobalScope.launch(Dispatchers.IO) {
            if (!VpnConnectHelper.hasProfilesBlocking()) return@launch

            when (BoxService.globalStatus.value) {
                Status.Started -> BoxService.stop()
                else -> {
                    if (!VpnConnectHelper.ensureSelectedProfileBlocking()) return@launch
                    if (Settings.isProxyMode) {
                        BoxService.start()
                    } else {
                        val vpnIntent = VpnService.prepare(this@VpnTileService)
                        if (vpnIntent != null) {
                            val toggleIntent = Intent(
                                this@VpnTileService,
                                VpnToggleActivity::class.java,
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                action = Intent.ACTION_VIEW
                            }
                            val pendingIntent = PendingIntent.getActivity(
                                this@VpnTileService,
                                0,
                                toggleIntent,
                                PendingIntent.FLAG_IMMUTABLE,
                            )
                            withContext(Dispatchers.Main) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                    startActivityAndCollapse(pendingIntent)
                                } else {
                                    @Suppress("DEPRECATION")
                                    startActivityAndCollapse(toggleIntent)
                                }
                            }
                            return@launch
                        }
                        BoxService.start()
                    }
                }
            }
            updateTileAsync()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun updateTileAsync() {
        GlobalScope.launch(Dispatchers.IO) {
            val profileCount = VpnConnectHelper.hasProfilesBlocking()
            val status = BoxService.globalStatus.value
            withContext(Dispatchers.Main) {
                val tile = qsTile ?: return@withContext
                when {
                    !profileCount -> {
                        tile.state = Tile.STATE_UNAVAILABLE
                        tile.label = getString(com.vpn4tv.app.R.string.shortcut_add_subscription)
                    }
                    status == Status.Started -> {
                        tile.state = Tile.STATE_ACTIVE
                        tile.label = getString(com.vpn4tv.app.R.string.shortcut_disconnect)
                    }
                    else -> {
                        tile.state = Tile.STATE_INACTIVE
                        tile.label = getString(com.vpn4tv.app.R.string.shortcut_connect)
                    }
                }
                tile.updateTile()
            }
        }
    }
}
