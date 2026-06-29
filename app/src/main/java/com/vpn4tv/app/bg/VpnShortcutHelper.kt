package com.vpn4tv.app.bg

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.vpn4tv.app.R
import com.vpn4tv.app.constant.Action
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.ui.MainActivity

object VpnShortcutHelper {

    private const val SHORTCUT_ID = "vpn_toggle"

    fun updateShortcut(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return
        val profileCount = ProfileManager.profileCountBlocking()
        val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID).apply {
            setShortLabel(
                if (profileCount > 0) {
                    context.getString(R.string.shortcut_connect)
                } else {
                    context.getString(R.string.shortcut_add_subscription)
                },
            )
            setLongLabel(
                if (profileCount > 0) {
                    context.getString(R.string.shortcut_connect_long)
                } else {
                    context.getString(R.string.shortcut_add_subscription_long)
                },
            )
            setIcon(Icon.createWithResource(context, R.drawable.ic_widget_vpn_off))
            setIntent(
                Intent(context, MainActivity::class.java).apply {
                    action = Action.LAUNCH_TOGGLE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }.build()
        manager.dynamicShortcuts = listOf(shortcut)
    }
}
