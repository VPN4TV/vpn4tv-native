package com.vpn4tv.app.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.vpn4tv.app.R
import com.vpn4tv.app.constant.Action
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.ui.MainActivity
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class VpnWidgetReceiver : BroadcastReceiver() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Action.WIDGET_TOGGLE) return
        val pending = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val profileCount = ProfileManager.profileCountBlocking()
                if (profileCount > 0) {
                    val currentStatus = BoxService.globalStatus.value
                    when (currentStatus) {
                        Status.Started -> BoxService.stop()
                        else -> BoxService.start()
                    }
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.widget_no_connections),
                        Toast.LENGTH_SHORT,
                    ).show()
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
            } finally {
                VpnWidgetProvider.updateAllWidgets(context)
                pending.finish()
            }
        }
    }
}
