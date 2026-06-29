package com.vpn4tv.app.bg

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.vpn4tv.app.R
import com.vpn4tv.app.constant.Status
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object VpnShortcutHelper {

    private const val SHORTCUT_ID = "vpn_toggle"

    /** Safe from any thread — DB work runs on [Dispatchers.IO]. */
    @OptIn(DelicateCoroutinesApi::class)
    fun updateShortcut(context: Context, vpnStatus: Status? = null) {
        val appContext = context.applicationContext
        GlobalScope.launch(Dispatchers.IO) {
            updateShortcutBlocking(appContext, vpnStatus)
        }
    }

    /**
     * Must run on a background thread.
     *
     * @param vpnStatus when non-null, used instead of [BoxService.globalStatus]
     *   so callers can pass the target state and avoid a race with LiveData updates.
     */
    fun updateShortcutBlocking(context: Context, vpnStatus: Status? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return
        val hasProfiles = VpnConnectHelper.hasProfilesBlocking()
        val status = vpnStatus ?: BoxService.globalStatus.value
        val isConnected = hasProfiles && status == Status.Started
        val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID).apply {
            setShortLabel(
                when {
                    isConnected -> context.getString(R.string.shortcut_disconnect)
                    hasProfiles -> context.getString(R.string.shortcut_connect)
                    else -> context.getString(R.string.shortcut_add_subscription)
                },
            )
            setLongLabel(
                when {
                    isConnected -> context.getString(R.string.shortcut_disconnect_long)
                    hasProfiles -> context.getString(R.string.shortcut_connect_long)
                    else -> context.getString(R.string.shortcut_add_subscription_long)
                },
            )
            setIcon(
                Icon.createWithResource(
                    context,
                    if (isConnected) R.drawable.ic_widget_vpn_on
                    else R.drawable.ic_widget_vpn_off,
                ),
            )
            setIntent(
                Intent(context, VpnToggleActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }.build()
        val shortcuts = listOf(shortcut)
        // dynamicShortcuts — entry in the long-press app menu.
        manager.dynamicShortcuts = shortcuts
        // updateShortcuts — also refreshes pinned copies on the home screen; many
        // launchers ignore dynamic-only updates for pinned shortcuts.
        manager.updateShortcuts(shortcuts)
    }
}
