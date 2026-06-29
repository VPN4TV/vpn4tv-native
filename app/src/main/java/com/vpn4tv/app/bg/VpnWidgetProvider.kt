package com.vpn4tv.app.bg

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.vpn4tv.app.R
import com.vpn4tv.app.constant.Action
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.ui.MainActivity
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class VpnWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAllWidgets(context)
    }

    override fun onEnabled(context: Context) {
        updateAllWidgets(context)
    }

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, VpnWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            // Run DB query on IO to avoid blocking the calling thread (may be main)
            GlobalScope.launch(Dispatchers.IO) {
                val profileCount = if (VpnConnectHelper.hasProfilesBlocking()) 1 else 0
                for (id in ids) {
                    updateWidget(context, manager, id, profileCount)
                }
            }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            profileCount: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.vpn_widget)
            val status = BoxService.globalStatus.value

            val pendingIntent: PendingIntent
            if (profileCount == 0) {
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_no_profile)
                views.setContentDescription(
                    R.id.widget_icon,
                    context.getString(R.string.widget_no_connections),
                )
                pendingIntent = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else if (status == Status.Started) {
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_vpn_on)
                views.setContentDescription(
                    R.id.widget_icon,
                    context.getString(R.string.widget_vpn_connected),
                )
                pendingIntent = makeToggleIntent(context)
            } else {
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_vpn_off)
                views.setContentDescription(
                    R.id.widget_icon,
                    context.getString(R.string.widget_vpn_disconnected),
                )
                pendingIntent = makeToggleIntent(context)
            }

            views.setOnClickPendingIntent(R.id.widget_icon, pendingIntent)
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun makeToggleIntent(context: Context): PendingIntent {
            val intent = Intent(context, VpnWidgetReceiver::class.java).apply {
                setAction(Action.WIDGET_TOGGLE)
            }
            return PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
