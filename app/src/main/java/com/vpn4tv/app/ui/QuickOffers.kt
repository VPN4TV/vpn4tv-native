package com.vpn4tv.app.ui

import android.app.Activity
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.UsageMilestones
import com.vpn4tv.app.bg.VpnTileService
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.widget.VpnWidgetReceiver

/**
 * The app offers its Quick Settings tile and its widget once each, after a
 * connect from the app (phones and tablets). The tile: Android 13+ asks with
 * its own dialog right after the first connect, as Google suggests ("in
 * context, such as when the user first interacts with a feature that your
 * tile facilitates"). The widget: a snackbar with "Add" once the VPN has
 * been connected on three different days ("when a user repeatedly accesses
 * a feature"); it never blocks anything, and Add opens the launcher's own
 * dialog for placing it.
 */
object QuickOffers {
    private const val TAG = "QuickOffers"

    fun tileDue(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Device.isTv &&
            !Settings.tileOffered && !Settings.tileAdded && UsageMilestones.load().connectDays >= 1

    /** Asked once: whatever the answer, never again. */
    fun requestTile(activity: Activity) {
        Settings.tileOffered = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val statusBar = activity.getSystemService(StatusBarManager::class.java) ?: return
            statusBar.requestAddTileService(
                VpnTileService.component(activity),
                activity.getString(R.string.tile_label),
                Icon.createWithResource(activity, R.drawable.ic_stat_vpn4tv),
                ContextCompat.getMainExecutor(activity),
            ) { result ->
                Log.i(TAG, "add tile: $result")
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                ) {
                    Settings.tileAdded = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tile not offered: ${e.message}")
        }
    }

    fun widgetDue(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || Device.isTv || Settings.widgetOffered) return false
        if (UsageMilestones.load().connectDays < UsageMilestones.CONNECT_DAYS_FOR_WIDGET) return false
        return try {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            manager.isRequestPinAppWidgetSupported && manager.getAppWidgetIds(widget(context)).isEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /** The launcher's dialog for placing the widget. */
    fun pinWidget(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            AppWidgetManager.getInstance(context)?.requestPinAppWidget(widget(context), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "widget not offered: ${e.message}")
        }
    }

    private fun widget(context: Context) = ComponentName(context, VpnWidgetReceiver::class.java)
}
