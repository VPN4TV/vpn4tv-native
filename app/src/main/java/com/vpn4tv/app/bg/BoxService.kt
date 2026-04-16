package com.vpn4tv.app.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import com.vpn4tv.app.Application
import com.vpn4tv.app.R
import com.vpn4tv.app.ui.MainActivity
import com.vpn4tv.app.constant.Action
import com.vpn4tv.app.constant.Alert
import com.vpn4tv.app.constant.ServiceMode
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.ktx.hasPermission
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val PROFILE_UPDATE_INTERVAL = 15L * 60 * 1000 // 15 minutes in milliseconds
        private const val TAG = "BoxService"
        val globalStatus = MutableLiveData(Status.Stopped)
        val lastError = MutableLiveData<String?>(null)

        fun start() {
            val intent =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        Intent(Application.application, Settings.serviceClass())
                    }
                }
            ContextCompat.startForegroundService(Application.application, intent)
        }

        fun stop() {
            Application.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    Application.application.packageName,
                ),
            )
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status get() = globalStatus
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private lateinit var commandServer: CommandServer

    private var receiverRegistered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
                    }

                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            serviceUpdateIdleMode()
                        }
                    }
                }
            }
        }

    private fun startCommandServer() {
        Log.d(TAG, "startCommandServer()")
        val commandServer = CommandServer(this, platformInterface)
        commandServer.start()
        this.commandServer = commandServer
        Log.d(TAG, "commandServer started")
    }

    private var lastProfileName = ""

    private suspend fun startService() {
        Log.d(TAG, "startService() begin")
        try {
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            val selectedProfileId = Settings.selectedProfile
            Log.d(TAG, "selectedProfile=$selectedProfileId")
            if (selectedProfileId == -1L) {
                Log.w(TAG, "No profile selected")
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val profile = ProfileManager.get(selectedProfileId)
            if (profile == null) {
                Log.w(TAG, "Profile $selectedProfileId not found")
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            Log.d(TAG, "Profile: ${profile.name}, path=${profile.typed.path}")
            val configFile = File(profile.typed.path)

            // Auto-recovery for the "profile row exists but config JSON is
            // missing/empty" state. Real user report from 50016: migration
            // from the Flutter fork created the Profile row but the initial
            // subscription download failed (network hiccup / DNS / rate
            // limit), leaving an empty file and a permanent "EmptyConfiguration:
            // unknown" error because nothing retried on subsequent boots. If
            // this is a remote profile, pull the subscription inline before
            // failing so the user doesn't need to guess how to recover.
            val configMissing = !configFile.exists() || configFile.length() == 0L
            if (configMissing) {
                val remoteUrl = profile.typed.remoteURL
                if (remoteUrl.isEmpty()) {
                    Log.w(TAG, "Config file not found for local profile: ${profile.typed.path}")
                    stopAndAlert(Alert.EmptyConfiguration)
                    return
                }
                Log.i(TAG, "Config empty, fetching subscription inline: $remoteUrl")
                try {
                    val sub = com.vpn4tv.app.converter.HwidService.fetchSubscription(
                        service.applicationContext, remoteUrl,
                    )
                    val proxies = com.vpn4tv.app.converter.ProxyParser.parseSubscription(sub.body)
                    if (proxies.isEmpty()) {
                        stopAndAlert(Alert.EmptyConfiguration, "subscription returned no proxies")
                        return
                    }
                    val result = com.vpn4tv.app.converter.ConfigGenerator.generateFull(proxies)
                    com.vpn4tv.app.converter.ConfigGenerator.writeAll(profile.typed.path, result)
                    if (!sub.title.isNullOrBlank() && sub.title != profile.name) {
                        profile.name = sub.title
                    }
                    profile.typed.lastUpdated = java.util.Date()
                    ProfileManager.update(profile)
                    Log.i(TAG, "Inline recovery OK: ${proxies.size} proxies written to ${profile.typed.path}")
                } catch (e: Exception) {
                    Log.e(TAG, "Inline subscription fetch failed: ${e.message}", e)
                    stopAndAlert(Alert.CreateService, "subscription fetch failed: ${e.message}")
                    return
                }
            }

            var content = configFile.readText()
            if (content.isBlank()) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            // Inject the DNS servers that DnsProber found to actually work
            // on this ISP. The generated config has whatever defaults
            // ConfigGenerator wrote at subscription-fetch time, but the
            // user's ISP may block those. DnsProber runs at app start and
            // tells us which DoH + UDP server responded — we overwrite the
            // config's dns.servers with those.
            try {
                content = injectProbedDns(content)
            } catch (e: Exception) {
                Log.w(TAG, "DNS injection failed, using config defaults: ${e.message}")
            }

            // In proxy mode we swap the TUN inbound for a plain SOCKS5 listener
            // on 127.0.0.1:12334 and drop every route rule that only exists to
            // route TUN traffic. The generated profile always carries a TUN
            // inbound because most devices use it, so the mutation is done at
            // start time rather than at profile-save time — toggling modes
            // then only requires a reconnect, no subscription refetch.
            if (Settings.isProxyMode) {
                try {
                    content = rewriteConfigForProxyMode(content)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rewrite config for proxy mode: ${e.message}", e)
                    stopAndAlert(Alert.CreateService, "proxy-mode rewrite: ${e.message}")
                    return
                }
            } else if (Settings.bypassLan) {
                // VPN mode + default-on LAN bypass: inject a route rule that
                // keeps RFC1918 / link-local / ULA / multicast traffic on the
                // underlying network. Applied at start time so the toggle is
                // instant and existing profiles generated before this setting
                // existed also benefit without a subscription refetch.
                try {
                    content = injectBypassLanRule(content)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to inject bypass-lan rule: ${e.message}")
                    // Non-fatal — fall back to the original config.
                }
            }

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            DefaultNetworkMonitor.start()

            // Start xray bridge if the profile needs it (xhttp/splithttp outbounds)
            val xraySidecar = File(com.vpn4tv.app.converter.ConfigGenerator.xraySidecarPath(profile.typed.path))
            if (xraySidecar.exists()) {
                try {
                    Log.d(TAG, "Starting xray bridge from ${xraySidecar.name}")
                    com.vpn4tv.app.xray.XrayBridge.start(service.applicationContext, xraySidecar.readText())
                } catch (e: Exception) {
                    Log.e(TAG, "xray bridge failed to start: ${e.message}", e)
                    stopAndAlert(Alert.CreateService, "xray: ${e.message}")
                    return
                }
            }

            // Start outline bridge if the profile has Outline-prefix Shadowsocks endpoints
            val outlineSidecar = File(com.vpn4tv.app.converter.ConfigGenerator.outlineSidecarPath(profile.typed.path))
            if (outlineSidecar.exists()) {
                try {
                    Log.d(TAG, "Starting outline bridge from ${outlineSidecar.name}")
                    com.vpn4tv.app.outline.OutlineBridge.start(outlineSidecar.readText())
                } catch (e: Exception) {
                    Log.e(TAG, "outline bridge failed to start: ${e.message}", e)
                    com.vpn4tv.app.xray.XrayBridge.stop()
                    com.vpn4tv.app.outline.OutlineBridge.stop()
                    stopAndAlert(Alert.CreateService, "outline: ${e.message}")
                    return
                }
            }

            // Start wireproxy bridge if the profile has AmneziaWG endpoints
            val wgSidecar = File(com.vpn4tv.app.converter.ConfigGenerator.wgSidecarPath(profile.typed.path))
            if (wgSidecar.exists()) {
                try {
                    Log.d(TAG, "Starting wireproxy bridge from ${wgSidecar.name}")
                    com.vpn4tv.app.wireproxy.WgBridge.start(wgSidecar.readText())
                } catch (e: Exception) {
                    Log.e(TAG, "wireproxy bridge failed to start: ${e.message}", e)
                    com.vpn4tv.app.xray.XrayBridge.stop()
                    com.vpn4tv.app.outline.OutlineBridge.stop()
                    com.vpn4tv.app.wireproxy.WgBridge.stop()
                    stopAndAlert(Alert.CreateService, "wireproxy: ${e.message}")
                    return
                }
            }

            Log.d(TAG, "Starting sing-box with config ${content.length} bytes...")
            content.chunked(3000).forEachIndexed { idx, chunk ->
                Log.d(TAG, "singbox config[$idx]: $chunk")
            }
            try {
                Log.d(TAG, "Calling commandServer.startOrReloadService()")
                commandServer.startOrReloadService(
                    content,
                    OverrideOptions().apply {
                        autoRedirect = Settings.autoRedirect
                        if (Settings.perAppProxyEnabled) {
                            val appList = Settings.getEffectivePerAppProxyList()
                            if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                                includePackage =
                                    PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                            } else {
                                excludePackage =
                                    PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                            }
                        }
                    },
                )
                Log.d(TAG, "startOrReloadService completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "startOrReloadService FAILED: ${e.message}", e)
                lastError.postValue("startService: ${e.message}")
                stopAndAlert(Alert.CreateService, e.message)
                return
            }

            if (commandServer.needWIFIState()) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            // Restore saved server selection
            restoreSavedServer()

            lastError.postValue(null)
            status.postValue(Status.Started)

            // Run URLTest immediately so delays are available in UI
            Thread {
                try {
                    Thread.sleep(1500) // wait for connections to establish
                    val client = io.nekohasekai.libbox.Libbox.newStandaloneCommandClient()
                    client.urlTest("auto")
                    client.disconnect()
                    Log.d(TAG, "Initial URLTest triggered")
                } catch (e: Exception) {
                    Log.w(TAG, "Initial URLTest failed: ${e.message}")
                }
            }.start()
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_started)
            }
            notification.start()
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    override fun serviceStop() {
        notification.close()
        status.postValue(Status.Starting)
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        closeService()
    }

    override fun serviceReload() {
        runBlocking {
            serviceReload0()
        }
    }

    suspend fun serviceReload0() {
        val selectedProfileId = Settings.selectedProfile
        if (selectedProfileId == -1L) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val profile = ProfileManager.get(selectedProfileId)
        if (profile == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val content = File(profile.typed.path).readText()
        if (content.isBlank()) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        lastProfileName = profile.name
        try {
            commandServer.startOrReloadService(
                content,
                OverrideOptions().apply {
                    autoRedirect = Settings.autoRedirect
                    if (Settings.perAppProxyEnabled) {
                        val appList = Settings.getEffectivePerAppProxyList()
                        if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                            includePackage = PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                        } else {
                            excludePackage = PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                        }
                    }
                },
            )
        } catch (e: Exception) {
            stopAndAlert(Alert.CreateService, e.message)
            return
        }

        if (commandServer.needWIFIState()) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? {
        val status = SystemProxyStatus()
        if (service is VPNService) {
            status.available = service.systemProxyAvailable
            status.enabled = service.systemProxyEnabled
        }
        return status
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        serviceReload()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun serviceUpdateIdleMode() {
        if (Application.powerManager.isDeviceIdleMode) {
            commandServer.pause()
        } else {
            commandServer.wake()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        if (status.value != Status.Started) return
        status.value = Status.Stopping
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        GlobalScope.launch(Dispatchers.IO) {
            val pfd = fileDescriptor
            if (pfd != null) {
                pfd.close()
                fileDescriptor = null
            }
            DefaultNetworkMonitor.stop()
            closeService()
            commandServer.apply {
                close()
//                Seq.destroyRef(refnum)
            }
            com.vpn4tv.app.xray.XrayBridge.stop()
            com.vpn4tv.app.outline.OutlineBridge.stop()
            com.vpn4tv.app.wireproxy.WgBridge.stop()
            Settings.startedByUser = false
            withContext(Dispatchers.Main) {
                status.value = Status.Stopped
                service.stopSelf()
            }
        }
    }

    private fun closeService() {
        runCatching {
            commandServer.closeService()
        }.onFailure {
            commandServer.setError("android: close service: ${it.message}")
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        Log.e(TAG, "stopAndAlert: ${type.name} — $message")
        lastError.postValue("${type.name}: ${message ?: "unknown"}")
        Settings.startedByUser = false
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        DefaultNetworkMonitor.stop()
        if (::commandServer.isInitialized) {
            closeService()
            commandServer.close()
        }
        com.vpn4tv.app.xray.XrayBridge.stop()
        com.vpn4tv.app.outline.OutlineBridge.stop()
        com.vpn4tv.app.wireproxy.WgBridge.stop()
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, message)
            }
            status.value = Status.Stopped
            service.stopSelf()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        Log.d(TAG, "onStartCommand, status=${status.value}")
        if (status.value != Status.Stopped) return Service.START_NOT_STICKY
        status.value = Status.Starting

        // Android 14 gives a foreground service only 5 seconds between
        // startForegroundService() and startForeground(). Our previous
        // code deferred startForeground() into the coroutine below,
        // which could miss the deadline on a loaded device and cause
        // ForegroundServiceDidNotStartInTimeException (seen on Xiaomi
        // TV Box S 2nd Gen / Android 14, 50103). Call immediately with
        // a placeholder notification; startService() updates the text
        // once the profile name is known.
        notification.show("", R.string.status_starting)

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Action.SERVICE_CLOSE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        GlobalScope.launch(Dispatchers.IO) {
            Settings.startedByUser = true
            try {
                startCommandServer()
            } catch (e: Exception) {
                stopAndAlert(Alert.StartCommandServer, e.message)
                return@launch
            }
            startService()
        }
        return Service.START_NOT_STICKY
    }

    internal fun onBind(): IBinder = binder

    internal fun onDestroy() {
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun sendNotification(notification: Notification) {
        val builder =
            NotificationCompat.Builder(service, notification.identifier).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_menu)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).apply {
                        setAction(Action.OPEN_URL).setData(Uri.parse(notification.openURL))
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                    ServiceNotification.flags,
                ),
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        notification.identifier,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            Application.notification.notify(notification.typeID, builder.build())
        }
    }


    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message!!)
    }

    override fun triggerNativeCrash() {
        // Only used by diagnostic tooling. Intentionally a no-op.
    }

    private fun restoreSavedServer() {
        try {
            val saved = com.vpn4tv.app.ui.getSavedServer(Application.application) ?: return
            Log.d(TAG, "Restoring saved server: $saved")
            val client = io.nekohasekai.libbox.Libbox.newStandaloneCommandClient()
            client.selectOutbound("select", saved)
            client.disconnect()
            Log.d(TAG, "Restored server: $saved")

            // Verify it's reachable — urltest it after a delay
            Thread {
                try {
                    Thread.sleep(3000) // wait for connection to establish
                    val testClient = io.nekohasekai.libbox.Libbox.newStandaloneCommandClient()
                    testClient.urlTest("select")
                    testClient.disconnect()
                    // Check if server responded (will be reflected in next group update)
                } catch (e: Exception) {
                    Log.w(TAG, "Server test failed, resetting to auto: ${e.message}")
                    try {
                        val resetClient = io.nekohasekai.libbox.Libbox.newStandaloneCommandClient()
                        resetClient.selectOutbound("select", "auto")
                        resetClient.disconnect()
                        com.vpn4tv.app.ui.clearSavedServer(Application.application)
                    } catch (_: Exception) {}
                }
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore server: ${e.message}")
        }
    }

    /**
     * Replace the TUN inbound in the generated sing-box config with a plain
     * SOCKS5 listener on 127.0.0.1:12334 for proxy mode. Also strips the
     * auto-route/sniff bits and any route rule referencing `tun-in`, because
     * without a TUN those rules either no-op or fail validation.
     */
    private fun rewriteConfigForProxyMode(jsonText: String): String {
        val root = org.json.JSONObject(jsonText)

        // Swap inbounds[] for a single SOCKS5 listener on loopback. Sniffing
        // is NOT set on the inbound — sing-box 1.13+ removed legacy inbound
        // fields; the generated route already has an `{action: "sniff"}`
        // rule at the top that applies to every inbound.
        val newInbounds = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("type", "socks")
                put("tag", "socks-in")
                put("listen", "127.0.0.1")
                put("listen_port", ServiceMode.PROXY_PORT)
            })
        }
        root.put("inbounds", newInbounds)

        // Drop route rules that only make sense with a TUN inbound.
        root.optJSONObject("route")?.let { route ->
            val oldRules = route.optJSONArray("rules") ?: return@let
            val keptRules = org.json.JSONArray()
            for (i in 0 until oldRules.length()) {
                val rule = oldRules.getJSONObject(i)
                // Rules that target or reference the tun inbound by tag
                // become dangling in proxy mode; drop them.
                val inbound = rule.optString("inbound", "")
                if (inbound == "tun-in") continue
                keptRules.put(rule)
            }
            route.put("rules", keptRules)
        }

        return root.toString()
    }

    /**
     * Insert `{ip_is_private: true, outbound: "direct"}` into the route rules
     * so that LAN traffic (RFC1918, link-local, ULA, multicast) stays on the
     * underlying network. The rule is placed *after* the leading sniff and
     * hijack-dns rules so DNS still goes through the tunnel, but *before*
     * any fallback UDP-direct rule so LAN UDP is handled here explicitly.
     * Idempotent: checks for an existing ip_is_private rule and returns the
     * config unchanged if one is already present.
     */
    private fun injectBypassLanRule(jsonText: String): String {
        val root = org.json.JSONObject(jsonText)
        val route = root.optJSONObject("route") ?: return jsonText
        val oldRules = route.optJSONArray("rules") ?: return jsonText

        // Already there? No-op.
        for (i in 0 until oldRules.length()) {
            if (oldRules.getJSONObject(i).optBoolean("ip_is_private", false)) {
                return jsonText
            }
        }

        val bypassRule = org.json.JSONObject().apply {
            put("ip_is_private", true)
            put("outbound", "direct")
        }

        // Rebuild rules as [leading action prelude] + [bypass] + [rest].
        // The prelude contains action-only rules (sniff, hijack-dns) that
        // set up state for subsequent matching; bypass goes immediately
        // after them so LAN traffic is diverted before any outbound rules
        // can route it through a proxy.
        val rebuilt = org.json.JSONArray()
        var placed = false
        for (i in 0 until oldRules.length()) {
            val rule = oldRules.getJSONObject(i)
            if (!placed) {
                val action = rule.optString("action", "")
                val isPrelude = action == "sniff" || action == "hijack-dns"
                if (!isPrelude) {
                    rebuilt.put(bypassRule)
                    placed = true
                }
            }
            rebuilt.put(rule)
        }
        if (!placed) rebuilt.put(bypassRule)
        route.put("rules", rebuilt)

        return root.toString()
    }

    /**
     * Replace the DNS servers in the config with the ones that
     * [DnsProber] found to actually work on this ISP. If DnsProber
     * hasn't finished yet (unlikely — it runs at app start), keep the
     * config's original servers.
     */
    private fun injectProbedDns(jsonText: String): String {
        val probed = com.vpn4tv.app.utils.DnsProber.result
        if (probed == null) {
            Log.w(TAG, "DNS probe not ready yet, keeping config defaults")
            return jsonText
        }
        Log.i(TAG, "DNS probe result: doh=${probed.dohUrl} udp=${probed.udpServer}")
        val root = org.json.JSONObject(jsonText)
        val dns = root.optJSONObject("dns") ?: return jsonText
        val servers = dns.optJSONArray("servers") ?: return jsonText

        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            val tag = server.optString("tag", "")
            when (tag) {
                "dns-remote" -> {
                    // DoH server that goes through the VPN tunnel.
                    val dohHost = com.vpn4tv.app.utils.DnsProber.dohServer()
                    server.put("type", "https")
                    server.put("server", dohHost)
                    Log.i(TAG, "DNS: dns-remote → https $dohHost (probed)")
                }
                "dns-direct" -> {
                    // Plain UDP for resolving dns-remote's hostname and
                    // for the "any outbound" catch-all rule.
                    val udp = com.vpn4tv.app.utils.DnsProber.udpServer()
                    server.put("type", "udp")
                    server.put("server", udp)
                    Log.i(TAG, "DNS: dns-direct → udp $udp (probed)")
                }
            }
        }

        return root.toString()
    }
}
