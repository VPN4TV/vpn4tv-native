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
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.OutboundGroup
import com.vpn4tv.app.Application
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

        /** The notification of the running service, for changes made outside it. */
        @Volatile
        private var running: ServiceNotification? = null

        /**
         * The server the notification names, null without one: the Quick
         * Settings tile shows it too, under the same setting.
         */
        val currentServer = MutableLiveData<String?>(null)

        /**
         * The session's uplink and downlink totals in bytes, under the same
         * setting as [currentServer] (the core's status, once a second while
         * the screen is on): the tall widget's traffic line.
         */
        val currentTraffic = MutableLiveData<LongArray?>(null)

        /**
         * The "current server" setting changed, notifications were just
         * allowed, or always-on VPN changed: the notification shows it now,
         * not from the next connect.
         */
        fun refresh() {
            running?.restartServerUpdates()
        }

        /**
         * The server traffic goes through: the selector's pick, or the auto
         * group's when the selector is on auto. Home shows the same one.
         */
        fun activeServer(selectorPick: String, autoPick: String?): String =
            if (selectorPick == "auto" && !autoPick.isNullOrEmpty()) autoPick else selectorPick
    }

    // Server changes come in with the groups, which the core sends when a
    // pick changes; the status (once a second) only feeds the widget's
    // traffic line, the notification no longer shows the speed.
    @OptIn(DelicateCoroutinesApi::class)
    private val commandClient =
        CommandClient(GlobalScope, listOf(CommandClient.ConnectionType.Groups, CommandClient.ConnectionType.Status), this)
    private var receiverRegistered = false

    // The builder, the text and the closed flag change on the main thread, the
    // core's thread (groups) and IO; one lock keeps a late update from posting
    // the notification again after close() has removed it.
    private val lock = Any()
    private var closed = false
    private var title = "VPN4TV"
    @StringRes private var state = R.string.status_connecting
    private var server: String? = null

    private val stopAction by lazy {
        NotificationCompat.Action.Builder(
            0,
            service.getText(R.string.action_disconnect),
            PendingIntent.getBroadcast(
                service,
                0,
                Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
                flags,
            ),
        ).build()
    }

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(service, notificationChannel).setShowWhen(false).setOngoing(true)
            .setContentTitle("VPN4TV").setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_stat_vpn4tv)
            // The icon's purple: Android 12+ draws the icon and the action in it.
            .setColor(ContextCompat.getColor(service, R.color.brand))
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    /** The notification as it stands. Call with [lock] held. */
    private fun build(): android.app.Notification {
        // "Подключено к серверу FR10T": the server's own tag often has a "·"
        // in it, so not "Подключено · FR10T".
        val shownServer = server?.takeIf {
            it.isNotBlank() && state == R.string.status_connected && Settings.dynamicNotification
        }
        notificationBuilder
            .setContentTitle(title)
            .setContentText(
                if (shownServer != null) service.getString(R.string.notification_connected_server, shownServer)
                else service.getString(state),
            )
            .clearActions()
        // Always-on VPN: Android starts and stops it, so no Disconnect here
        // (Google's VPN guide); the app's screen leads to Android's VPN settings.
        if ((service as? VPNService)?.alwaysOnState() == null) notificationBuilder.addAction(stopAction)
        return notificationBuilder.build()
    }

    /**
     * Promote the service to foreground. Returns false when the system
     * rejected the promotion — the caller MUST stop the service in that
     * case, because running un-promoted gets the process killed (and
     * Android counts it against the app's background-start budget).
     *
     * Rejections seen in the field (vc50326, API 34 TV boxes):
     * - ForegroundServiceStartNotAllowedException: cold start took >10s
     *   (Go runtime page-in on cheap flash), the BOOT_COMPLETED temp
     *   allowlist expired before onStartCommand ran, and the system
     *   re-evaluated the grant at startForeground time.
     * - SecurityException from validateForegroundServiceType: same boxes,
     *   FGS-type permission policy rejects systemExempted for apps the
     *   multi-user firmware considers background-restricted.
     * Both are environmental, not bugs we can fix at this callsite —
     * crash-free degradation is the only option.
     */
    fun show(lastProfileName: String, @StringRes contentTextId: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The name and description of an existing channel can change, so
            // installs that have "Service Notifications" get the new ones too.
            Application.notification.createNotificationChannel(
                NotificationChannel(
                    notificationChannel,
                    service.getString(R.string.notification_channel_vpn),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = service.getString(R.string.notification_channel_vpn_desc) },
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
        return try {
            val notification = synchronized(lock) {
                closed = false
                title = lastProfileName.takeIf { it.isNotBlank() } ?: "VPN4TV"
                state = contentTextId
                if (contentTextId != R.string.status_connected) server = null
                build()
            }
            ServiceCompat.startForeground(service, notificationId, notification, fgsType)
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException extends
            // IllegalStateException; SecurityException is its sibling.
            // Both mean "the system refuses the promotion right now".
            Log.e("ServiceNotification", "startForeground rejected: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Connected: follow the server, when the setting asks for it. */
    suspend fun start() {
        running = this
        startServerUpdates()
    }

    // No live updates on TV: nobody sees the notification there, and a steady
    // update was a load on weak TV boxes (seen in the system log of a TCL set,
    // 2026-11-07). Nor without the permission: nothing would show.
    private fun serverWanted() =
        Settings.dynamicNotification && checkPermission() && !com.vpn4tv.app.ui.Device.isTv

    private suspend fun startServerUpdates() {
        if (!serverWanted() || synchronized(lock) { closed }) return
        commandClient.connect()
        withContext(Dispatchers.Main) {
            // A stop may have come meanwhile (a refresh racing it).
            if (synchronized(lock) { closed }) commandClient.disconnect() else registerReceiver()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun restartServerUpdates() {
        GlobalScope.launch(Dispatchers.IO) {
            commandClient.disconnect()
            withContext(Dispatchers.Main) { unregisterReceiver() }
            // Posted again at once: after the permission is granted the
            // notification shows only once it is posted anew, and with the
            // setting off the server goes from the text.
            synchronized(lock) {
                if (closed) return@launch
                if (!Settings.dynamicNotification) {
                    server = null
                    currentServer.postValue(null)
                    currentTraffic.postValue(null)
                }
                Application.notificationManager.notify(notificationId, build())
            }
            startServerUpdates()
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        service.registerReceiver(
            this,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        service.unregisterReceiver(this)
        receiverRegistered = false
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        val selector = newGroups.firstOrNull { it.type == "selector" } ?: return
        val pick = activeServer(selector.selected, newGroups.firstOrNull { it.type == "urltest" }?.selected)
        synchronized(lock) {
            if (closed || pick == server) return
            server = pick
            currentServer.postValue(pick)
            if (state != R.string.status_connected) return
            Application.notificationManager.notify(notificationId, build())
        }
    }

    override fun updateStatus(status: io.nekohasekai.libbox.StatusMessage) {
        synchronized(lock) {
            if (closed) return
            currentTraffic.postValue(longArrayOf(status.uplinkTotal, status.downlinkTotal))
        }
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
        if (running === this) running = null
        commandClient.disconnect()
        synchronized(lock) {
            closed = true
            server = null
            currentServer.postValue(null)
            currentTraffic.postValue(null)
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        unregisterReceiver()
    }
}
