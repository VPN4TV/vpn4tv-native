package com.vpn4tv.app.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import com.vpn4tv.app.Application
import com.vpn4tv.app.ktx.unwrap
import com.vpn4tv.app.R
import com.vpn4tv.app.ui.MainActivity
import com.vpn4tv.app.constant.Action
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.utils.CommandClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServiceNotification(private val status: MutableLiveData<Status>, private val service: Service) :
    BroadcastReceiver(),
    CommandClient.Handler {
    companion object {
        private const val notificationId = 1
        private const val notificationChannel = "service"
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        fun checkPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return Application.notification.areNotificationsEnabled()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val commandClient =
        CommandClient(GlobalScope, CommandClient.ConnectionType.Status, this)
    private var receiverRegistered = false

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(service, notificationChannel).setShowWhen(false).setOngoing(true)
            .setContentTitle("VPN4TV").setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_menu)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    flags,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW).apply {
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        service.getText(R.string.stop),
                        PendingIntent.getBroadcast(
                            service,
                            0,
                            Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
                            flags,
                        ),
                    ).build(),
                )
            }
    }

    fun show(lastProfileName: String, @StringRes contentTextId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Application.notification.createNotificationChannel(
                NotificationChannel(
                    notificationChannel,
                    "Service Notifications",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        // Android 14+ requires startForeground() to carry the same
        // foregroundServiceType declared in the manifest, or the system
        // throws SecurityException in ActiveServices.validateForegroundServiceType
        // (seen on Xiaomi TV Box S 2nd Gen / A14, vc50307). VPNService is
        // declared "systemExempted" and ProxyService is declared "specialUse".
        val fgsType = if (Build.VERSION.SDK_INT >= 34) {
            when (service) {
                is VPNService -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                is ProxyService -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else -> 0
            }
        } else 0
        ServiceCompat.startForeground(
            service,
            notificationId,
            notificationBuilder
                .setContentTitle(lastProfileName.takeIf { it.isNotBlank() } ?: "VPN4TV")
                .setContentText(service.getString(contentTextId)).build(),
            fgsType,
        )
    }

    suspend fun start() {
        if (Settings.dynamicNotification && checkPermission()) {
            commandClient.connect()
            withContext(Dispatchers.Main) {
                registerReceiver()
            }
        }
    }

    private fun registerReceiver() {
        service.registerReceiver(
            this,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        receiverRegistered = true
    }

    override fun updateStatus(status: StatusMessage) {
        val content =
            Libbox.formatBytes(status.uplink).unwrap + "/s ↑\t" + Libbox.formatBytes(status.downlink).unwrap + "/s ↓"
        Application.notificationManager.notify(
            notificationId,
            notificationBuilder.setContentText(content).build(),
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        // CommandClient.connect() opens a socket to libbox's command server
        // and can stall for seconds if the server isn't ready, causing an
        // ANR because BroadcastReceiver.onReceive() must return within 10s
        // (SCREEN_ON broadcast ANR cluster, vc50303).
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                GlobalScope.launch(Dispatchers.IO) { commandClient.connect() }
            }

            Intent.ACTION_SCREEN_OFF -> {
                GlobalScope.launch(Dispatchers.IO) { commandClient.disconnect() }
            }
        }
    }

    fun close() {
        commandClient.disconnect()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (receiverRegistered) {
            service.unregisterReceiver(this)
            receiverRegistered = false
        }
    }
}
