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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val PROFILE_UPDATE_INTERVAL = 15L * 60 * 1000 // 15 minutes in milliseconds
        private const val TAG = "BoxService"

        // Reload requests this close together cost one reload.
        private const val RELOAD_COALESCE_MS = 1500L

        // How long a stop waits for a start or reload that is still running.
        private const val STOP_WAITS_FOR_RELOAD_MS = 15_000L

        // How long a start waits for the one-time upgrade of the saved configs.
        private const val CONFIG_UPGRADE_WAIT_MS = 5_000L
        val globalStatus = MutableLiveData(Status.Stopped)
        val lastError = MutableLiveData<String?>(null)

        // Connect attempts, for the watchdog. Static: a watchdog armed by an earlier
        // BoxService instance must see the newer attempts too.
        private val connectAttempt = java.util.concurrent.atomic.AtomicInteger()
        private val recentAttempts = java.util.ArrayDeque<Long>()
        @Volatile private var startStage = ""

        // Set by the post-connect FakeDNS health check when the proxy is
        // provably alive but the fakeip/domain path is broken on this
        // network/router. While set, the config pipeline strips FakeDNS, so the
        // auto-disable reload converges (no probe→reload loop). CLEARED at the
        // start of every fresh user-initiated connect (startService), so a flag
        // set on a previously-broken network or by a transient blip is re-tested
        // rather than permanently forfeiting the DNS-bypass. The auto-disable
        // reload uses serviceReload (not startService), so it doesn't clear the
        // flag it just set.
        @Volatile
        var fakeDnsAutoDisabled = false


        fun start() {
            val intent =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        Intent(Application.application, Settings.serviceClass())
                    }
                }
            // From the background (the per-app screen restarts the service after the
            // stop has ended, the user may have left the app) Android 12+ refuses
            // a foreground start in proxy mode: VPN consent exempts only VPNService.
            try {
                ContextCompat.startForegroundService(Application.application, intent)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "start refused: ${e.message}")
                lastError.postValue("StartService: ${e.message}")
            }
        }

        fun stop() {
            Application.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    Application.application.packageName,
                ),
            )
        }

        /**
         * Reload the running service after a setting the config is built
         * from changed. The same path as every other reload: the command
         * server's serviceReload, which waits for a start or reload in
         * progress and merges a burst of requests into one (ReloadGate). A
         * start still running gets it too: it may have read the setting
         * before the change.
         */
        @OptIn(DelicateCoroutinesApi::class)
        fun reloadIfRunning() {
            val now = globalStatus.value
            if (now != Status.Started && now != Status.Starting) return
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val client = Libbox.newStandaloneCommandClient()
                    try { client.serviceReload() } finally { client.disconnect() }
                } catch (e: Exception) {
                    Log.w(TAG, "reload after a settings change failed: ${e.message}")
                }
            }
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status get() = globalStatus
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private lateinit var commandServer: CommandServer

    // Starts and reloads run one at a time, and a burst of reload requests is
    // one reload (ReloadGate). A stop waits here too, for a limited time: the
    // core finishes a reload that is already inside it even when the service is
    // closed under it, and only the stop can close what that reload started.
    private val reloadGate = ReloadGate(RELOAD_COALESCE_MS)

    // Set while this service's command server is being closed. A reload that
    // was already inside serviceReload0 when the service stopped must not reach
    // the closed server: the core still starts an instance there, nothing ever
    // closes it, and it holds a tun and cache.db next to the next session's.
    @Volatile
    private var closing = false

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
        closing = false
        Log.d(TAG, "commandServer started")
    }

    private var lastProfileName = ""

    private suspend fun startService() = reloadGate.start { startService0() }

    private suspend fun startService0() {
        Log.d(TAG, "startService() begin")
        try {
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            // The saved configs are upgraded once after an update, off the
            // main thread (SavedConfigUpgrade): a start at boot right after an
            // update must read the upgraded file. Normally long done; a stuck
            // upgrade only costs this wait, then the file on disk is used as is.
            markStage("config-upgrade")
            if (!SavedConfigUpgrade.awaitDone(CONFIG_UPGRADE_WAIT_MS)) {
                Log.w(TAG, "saved config upgrade not done after ${CONFIG_UPGRADE_WAIT_MS}ms, starting with the file as it is")
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

            // Re-test FakeDNS on every fresh (user-initiated) connect: clear the
            // process-sticky auto-disable so a flag set on a previously-broken
            // network (or by a transient blip) doesn't permanently forfeit the
            // DNS-bypass. The post-connect health check re-evaluates and only
            // re-disables if the fakeip path is still genuinely broken here.
            // (The auto-disable reload goes through serviceReload, not
            // startService, so it does not clear the flag it just set.)
            fakeDnsAutoDisabled = false
            OutsideVpnGuard.ownerLookupBroken = false
            RussiaDirectRuntime.newSession()

            markStage("transforms")
            // The config without "Russia direct": what a start that fails with
            // it is retried with, and what 5.3.3 ran.
            val base = try {
                applyConfigTransforms(content)
            } catch (e: Exception) {
                Log.e(TAG, "config transform (proxy-mode) failed: ${e.message}", e)
                stopAndAlert(Alert.CreateService, "proxy-mode rewrite: ${e.message}")
                return
            }
            content = base

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            DefaultNetworkMonitor.start()

            // Record which bridges this session needs. consumeCrashLog reads
            // these on the next launch and logs xray/outline/wireproxy next to
            // any Go crash dump — lets us correlate residual races with the
            // bridge subset that was running (and feeds the planned server
            // upload).
            val xraySidecar = File(com.vpn4tv.app.converter.ConfigGenerator.xraySidecarPath(profile.typed.path))
            val outlineSidecar = File(com.vpn4tv.app.converter.ConfigGenerator.outlineSidecarPath(profile.typed.path))
            val wgSidecar = File(com.vpn4tv.app.converter.ConfigGenerator.wgSidecarPath(profile.typed.path))
            val olcrtcSidecar = File(com.vpn4tv.app.converter.ConfigGenerator.olcrtcSidecarPath(profile.typed.path))
            val ttSidecar = File(com.vpn4tv.app.converter.ConfigGenerator.ttSidecarPath(profile.typed.path))
            Application.application
                .getSharedPreferences("session", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("xray", xraySidecar.exists())
                .putBoolean("outline", outlineSidecar.exists())
                .putBoolean("wireproxy", wgSidecar.exists())
                .putBoolean("olcrtc", olcrtcSidecar.exists())
                .putBoolean("trusttunnel", ttSidecar.exists())
                .putLong("started_at", System.currentTimeMillis())
                .apply()

            // A fresh start brings every bridge up from scratch, as before: the
            // record of what runs is cleared, so syncBridges starts them all.
            synchronized(bridgeLock) { runningBridgeSidecars.clear() }
            markStage("bridges")
            syncBridges(profile.typed.path)?.let { error ->
                stopAndAlert(Alert.CreateService, error)
                return
            }

            markStage("russia-direct")
            var routed = RussiaDirectRuntime.prepare(Application.application, base, selectedProfileId)
            content = routed.content
            // Whether the running config actually carries FakeDNS — used to arm
            // the post-connect health check only when it's worth checking.
            val fakeDnsInConfig = content.contains("\"dns-fakeip\"")

            markStage("core")
            Log.d(TAG, "Starting sing-box with config ${content.length} bytes...")
            BridgeAuth.redact(content).chunked(3000).forEachIndexed { idx, chunk ->
                Log.d(TAG, "singbox config[$idx]: $chunk")
            }
            try {
                Log.d(TAG, "Calling commandServer.startOrReloadService()")
                commandServer.startOrReloadService(content, overrideOptions())
                Log.d(TAG, "startOrReloadService completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "startOrReloadService FAILED: ${e.message}", e)
                lastError.postValue("startService: ${e.message}")
                // Self-heal: external-storage cache-file EACCES on multi-user TV.
                // Set flag so next launch uses internal filesDir, then kill the
                // process — Android restarts it on the next user tap.
                val msg = e.message.orEmpty()
                if ((msg.contains("cache-file") || msg.contains("cache_file")) && msg.contains("permission denied")) {
                    Log.w(TAG, "Detected cache-file EACCES, switching to internal storage on next launch")
                    Application.application
                        .getSharedPreferences("storage", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("use_internal", true).commit()
                    stopAndAlert(Alert.CreateService, "${e.message}\n\nПерезапускаю с внутренним хранилищем...")
                    kotlinx.coroutines.delay(2000)
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return
                }
                // A start that fails with "Russia direct" is retried without
                // it; if that fails too, the block was not the cause and the
                // error is the one the user saw before.
                // Without it means with the torrents rule alone: torrents go direct in every mode.
                val fallback = RussiaDirectRuntime.torrentsOnly(base, "session")
                if (content == base || content == fallback || closing) {
                    stopAndAlert(Alert.CreateService, e.message)
                    return
                }
                Log.w(TAG, "start with Russia direct failed, retrying without it: ${e.message}")
                try {
                    commandServer.startOrReloadService(fallback, overrideOptions())
                } catch (e2: Exception) {
                    Log.e(TAG, "start without Russia direct failed too: ${e2.message}", e2)
                    lastError.postValue("startService: ${e2.message}")
                    stopAndAlert(Alert.CreateService, e2.message)
                    return
                }
                RussiaDirectRuntime.rolledBack(Application.application, e.message)
                routed = RussiaDirectRuntime.Prepared(fallback, "off (session: the start with it failed: ${e.message})", warn = true)
                content = fallback
                lastError.postValue(null)
            }
            logRussiaDirect(routed)

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
            markStage("restore")
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

            // FakeDNS reachability self-heal. FakeDNS is default-on and rewrites
            // DNS for the whole tunnel; on a network/router where the fakeip
            // path is broken it silently kills connectivity for every app. If
            // the running config actually carries FakeDNS, probe a known-good
            // endpoint (YouTube generate_204) THROUGH the tunnel; if it's
            // unreachable, auto-disable FakeDNS and reload. Only ever disables
            // (never enables), so the worst case is reverting to the pre-feature
            // config — it cannot make connectivity worse.
            if (fakeDnsInConfig && !fakeDnsAutoDisabled) {
                Thread { runFakeDnsHealthCheck() }.start()
            }
            // The tun guard rejects connections the system won't name an owner
            // for; make sure it names ours, or drop the rule for the session.
            if (OutsideVpnGuard.inConfig(content)) OutsideVpnGuard.verifyAfterStart()
            RussiaDirectRuntime.onRunning(Application.application, routed)
            if (RussiaDirectRuntime.claimDnsCheck(routed)) Thread { runDnsRuHealthCheck() }.start()
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    /**
     * Probe YouTube through the tunnel after connect; if unreachable while
     * FakeDNS is active, auto-disable FakeDNS and reload. Runs on its own
     * thread. One-shot per process via the fakeDnsAutoDisabled flag, so there
     * is no probe→reload→probe loop.
     */
    private fun runFakeDnsHealthCheck() {
        try {
            Thread.sleep(3000) // let the tunnel + bridges settle
            if (fakeDnsAutoDisabled || globalStatus.value != Status.Started) return

            // Test the fakeip/domain path first.
            if (probeReachableThroughTunnel(attempts = 3)) {
                Log.i(TAG, "FakeDNS health check OK — reachable through tunnel")
                return
            }
            if (fakeDnsAutoDisabled || globalStatus.value != Status.Started) return

            // DISCRIMINATOR: the domain probe failed — but is that because the
            // fakeip path is broken, or just because the proxy itself is down /
            // the wrong server is selected? Connect to a stable anycast IP
            // through the tunnel (no DNS, fakeip-independent). If THAT also
            // fails, the proxy is down — NOT a FakeDNS fault — so don't strip
            // FakeDNS (stripping wouldn't help and would silently forfeit the
            // DNS-bypass for the session). Only disable when the proxy is
            // provably alive but domain resolution via fakeip is broken.
            if (!probeProxyAliveByIp()) {
                Log.w(TAG, "FakeDNS health check: domain unreachable but proxy also down — not attributing to FakeDNS")
                return
            }

            Log.w(TAG, "FakeDNS health check FAILED — proxy alive but fakeip path broken; auto-disabling FakeDNS and reloading")
            fakeDnsAutoDisabled = true
            // Diagnostic breadcrumb (read by support / next-launch crash tag).
            Application.application
                .getSharedPreferences("session", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("fakeDns_auto_disabled", true).apply()
            if (globalStatus.value != Status.Started) return
            // Through the command server like every other reload: serviceReload
            // runs it after any start or reload in progress and merges it with
            // requests that come at the same time (ReloadGate). The server
            // itself does not order reloads.
            try {
                val client = io.nekohasekai.libbox.Libbox.newStandaloneCommandClient()
                try { client.serviceReload() } finally { client.disconnect() }
            } catch (e: Exception) {
                Log.w(TAG, "FakeDNS auto-disable reload failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "FakeDNS health check error: ${e.message}")
        }
    }

    /**
     * "Russia direct" resolves Russian names at Yandex over TCP 53, outside
     * the tunnel. Where that port is closed (hotels, offices, some networks
     * abroad) Russian names would stop resolving. Ask for one through the
     * tunnel after connect; if dns-ru does not answer while the proxy does,
     * our DNS rule resolves through dns-direct for the session, and reload.
     * Only ever falls back, once per session.
     */
    private fun runDnsRuHealthCheck() {
        try {
            Thread.sleep(4000) // let the tunnel + bridges settle
            fun wanted() = !RussiaDirectRuntime.dnsRuFallback && globalStatus.value == Status.Started
            // A second round a minute later when the first cannot tell: the
            // proxy was not up yet either (slow bridges, a start at boot).
            for (round in 1..2) {
                if (!wanted()) return
                if (RussiaDirectRuntime.probeDnsRu(attempts = 3) { wanted() }) {
                    Log.i(TAG, "dns-ru health check OK")
                    return
                }
                if (!wanted()) return
                if (probeProxyAliveByIp()) break
                Log.w(TAG, "dns-ru health check: no answer, but the proxy is down too — not blaming dns-ru (round $round of 2)")
                if (round == 2) return
                Thread.sleep(60_000)
            }
            Log.w(TAG, "dns-ru health check FAILED — Russian names resolve through dns-direct this session; reloading")
            RussiaDirectRuntime.dnsRuFallback = true
            if (globalStatus.value != Status.Started) return
            try {
                val client = io.nekohasekai.libbox.Libbox.newStandaloneCommandClient()
                try { client.serviceReload() } finally { client.disconnect() }
            } catch (e: Exception) {
                Log.w(TAG, "dns-ru fallback reload failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "dns-ru health check error: ${e.message}")
        }
    }

    /**
     * Proxy-liveness discriminator: TCP-connect to a stable anycast IP through
     * the tunnel. By IP, so no DNS / fakeip involved — it isolates "proxy is
     * forwarding" from "fakeip resolution works". Success on either IP = the
     * selected outbound is alive.
     */
    private fun probeProxyAliveByIp(): Boolean {
        for (ip in listOf("1.1.1.1", "8.8.8.8")) {
            if (globalStatus.value != Status.Started) return false
            var sock: java.net.Socket? = null
            try {
                sock = java.net.Socket()
                sock.connect(java.net.InetSocketAddress(ip, 443), 5000)
                if (sock.isConnected) return true
            } catch (e: Exception) {
                Log.w(TAG, "proxy-alive probe $ip failed: ${e.message}")
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }
        return false
    }

    /**
     * Hit YouTube's connectivity endpoint with a plain HttpURLConnection.
     * Deliberately NOT the libbox HTTP client: that one is used for
     * subscription fetching and dials on the underlying network (it would
     * bypass the tunnel and test nothing). Our app process's own sockets are
     * NOT protected, so with auto_route they traverse the TUN — the OS
     * resolves youtube.com through sing-box's DNS (→ fake IP under FakeDNS),
     * connects to the fake IP, the TUN captures it, sniff recovers the domain,
     * and the proxy resolves remotely. So this exercises the exact fakeip path
     * a real app uses. A transport failure (can't reach the fake IP) throws;
     * generate_204 returns 204 on success. Retries ride out the flaky first
     * seconds after a TV connects.
     */
    private fun probeReachableThroughTunnel(attempts: Int): Boolean {
        repeat(attempts) { i ->
            if (fakeDnsAutoDisabled || globalStatus.value != Status.Started) return false
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = (java.net.URL("https://www.youtube.com/generate_204").openConnection()
                    as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "VPN4TV-healthcheck")
                }
                val code = conn.responseCode
                // Any real HTTP response means the fakeip path reached YouTube.
                if (code in 200..399) return true
                Log.w(TAG, "tunnel probe attempt ${i + 1}/$attempts: HTTP $code")
            } catch (e: Exception) {
                Log.w(TAG, "tunnel probe attempt ${i + 1}/$attempts failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
            if (i < attempts - 1) try { Thread.sleep(3000) } catch (_: InterruptedException) { return false }
        }
        return false
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

    // Every reload request ends up here, on a command server thread: the
    // refresh buttons, the refresh at launch, the background worker, the
    // FakeDNS and tun-guard self-checks. They used to run side by side.
    override fun serviceReload() {
        runBlocking {
            if (!reloadGate.reload { serviceReload0() }) {
                Log.d(TAG, "serviceReload: already applied by a start or reload that began after the request")
            }
        }
    }

    /** Started, with a live command server: a reload has something to reload. */
    private fun reloadable(): Boolean =
        ::commandServer.isInitialized && !closing && globalStatus.value == Status.Started

    // A reload that fails after a stop has begun is not an error to show, and the
    // stop tears the service down itself.
    private suspend fun failReload(type: Alert, message: String? = null) {
        if (!reloadable()) {
            Log.w(TAG, "reload failed after a stop: ${type.name} — $message")
            return
        }
        stopAndAlert(type, message)
    }

    suspend fun serviceReload0() {
        // The request may have waited behind a start or another reload; the
        // service may be stopping or stopped by now.
        if (!reloadable()) {
            Log.w(TAG, "serviceReload skipped — service not running")
            return
        }
        val selectedProfileId = Settings.selectedProfile
        if (selectedProfileId == -1L) {
            failReload(Alert.EmptyConfiguration)
            return
        }

        val profile = ProfileManager.get(selectedProfileId)
        if (profile == null) {
            failReload(Alert.EmptyConfiguration)
            return
        }

        val rawContent = File(profile.typed.path).readText()
        if (rawContent.isBlank()) {
            failReload(Alert.EmptyConfiguration)
            return
        }
        val base = try {
            applyConfigTransforms(rawContent)
        } catch (e: Exception) {
            failReload(Alert.CreateService, "proxy-mode rewrite: ${e.message}")
            return
        }
        // Bail if the service is being torn down — a reload landing concurrently
        // with stopService()/closeService() would hit a closed command server.
        if (!reloadable()) {
            Log.w(TAG, "serviceReload skipped — service not running")
            return
        }
        lastProfileName = profile.name
        // Bridges first: the new config may aim at a bridge the old one did not
        // have, or at one whose link changed. Unchanged bridges keep running.
        syncBridges(profile.typed.path)?.let { error ->
            failReload(Alert.CreateService, error)
            return
        }
        // Restarting a bridge takes time; check once more right before the core.
        if (!reloadable()) {
            Log.w(TAG, "serviceReload skipped — service not running")
            return
        }
        var routed = RussiaDirectRuntime.prepare(Application.application, base, selectedProfileId)
        // The check inside may have taken a moment.
        if (!reloadable()) {
            RussiaDirectRuntime.onStopped(Application.application)
            Log.w(TAG, "serviceReload skipped — service not running")
            return
        }
        try {
            commandServer.startOrReloadService(routed.content, overrideOptions())
        } catch (e: Exception) {
            // As in startService: without "Russia direct" once more, unless the
            // block was not in or the service is going away.
            val fallback = RussiaDirectRuntime.torrentsOnly(base, "session")
            if (routed.content == base || routed.content == fallback || !reloadable()) {
                RussiaDirectRuntime.onStopped(Application.application)
                failReload(Alert.CreateService, e.message)
                return
            }
            Log.w(TAG, "reload with Russia direct failed, retrying without it: ${e.message}")
            try {
                commandServer.startOrReloadService(fallback, overrideOptions())
            } catch (e2: Exception) {
                Log.e(TAG, "reload without Russia direct failed too: ${e2.message}", e2)
                RussiaDirectRuntime.onStopped(Application.application)
                failReload(Alert.CreateService, e2.message)
                return
            }
            RussiaDirectRuntime.rolledBack(Application.application, e.message)
            routed = RussiaDirectRuntime.Prepared(fallback, "off (session: the reload with it failed: ${e.message})", warn = true)
        }
        logRussiaDirect(routed)
        RussiaDirectRuntime.onRunning(Application.application, routed)
        if (RussiaDirectRuntime.claimDnsCheck(routed)) Thread { runDnsRuHealthCheck() }.start()

        if (commandServer.needWIFIState()) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                failReload(Alert.RequestLocationPermission)
                return
            }
        }
    }

    // No SSH agent on Android; same answer as upstream SFA.
    override fun connectSSHAgent(): Int = -1

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
        if (!::commandServer.isInitialized) return
        if (Application.powerManager.isDeviceIdleMode) {
            commandServer.pause()
        } else {
            commandServer.wake()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        if (status.value != Status.Started) return
        closing = true
        status.value = Status.Stopping
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        GlobalScope.launch(Dispatchers.IO) {
            // Before the wait below: if the process dies meanwhile, boot must not
            // connect again what the user has just turned off.
            Settings.startedByUser = false
            // A start or reload already inside the core finishes there even when
            // the service is closed under it: a close while the core is closing
            // the old instance does nothing, and an instance closed during its
            // Start still brings up its tun. Nothing would close what it started,
            // and the device would keep a VPN with no tunnel behind it while the
            // app shows it stopped. Wait for it, for a limited time, then close
            // what it left. A reload that has not reached the core yet sees
            // `closing` and stops short.
            if (withTimeoutOrNull(STOP_WAITS_FOR_RELOAD_MS) { reloadGate.awaitIdle() } == null) {
                Log.w(TAG, "stop: a start or reload still runs after $STOP_WAITS_FOR_RELOAD_MS ms")
            }
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
            RussiaDirectRuntime.onStopped(Application.application)
            stopAllBridges()
            withContext(Dispatchers.Main) {
                // Again: a start that finished while this stop waited shows it.
                notification.close()
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

    /** Per-app routing for the tun, the same for a start, a reload and their retries. */
    private fun overrideOptions() = OverrideOptions().apply {
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
    }

    /**
     * One line for support after every start and reload: whether "Russia
     * direct" is in and why not. Into the core's log too (the Logs screen),
     * after the start, since a start clears that log.
     */
    private fun logRussiaDirect(prepared: RussiaDirectRuntime.Prepared) {
        val line = prepared.line
        if (prepared.warn) Log.w(TAG, line) else Log.i(TAG, line)
        try {
            // libbox log levels: 3 warn, 4 info.
            commandServer.writeMessage(if (prepared.warn) 3 else 4, (if (prepared.warn) "WARN " else "INFO ") + line)
        } catch (t: Throwable) {
            Log.w(TAG, "russia-direct line not written to the core log: ${t.message}")
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        closing = true
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
        RussiaDirectRuntime.onStopped(Application.application)
        stopAllBridges()
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

    /**
     * Connect-hang backstop. The root cause of the boot-only connect hang was a
     * Libbox first-touch race (onCreate's setup coroutine vs BootReceiver's
     * auto-connect both booting the gomobile/Go runtime concurrently — a 50329
     * regression), now fixed by the awaitLibboxReady() gate before
     * startCommandServer. This watchdog stays as the safety net for ANY residual
     * stall anywhere in the start sequence: armed in onStartCommand BEFORE the
     * start coroutine, so it also covers startCommandServer (the earlier
     * placement inside startService never could). If Status.Starting persists
     * 45s, snapshot the Java/Kotlin thread stacks (always works, no root —
     * pinpoints a Room/JNI/coroutine stall) AND the Go goroutines
     * (Libbox.debugGoroutineDump — pinpoints a sing-box-internal stall), upload
     * both (kind=hang-java / hang) so it's diagnosable from the field without
     * device access, then hard-reset — a wedged runtime can't be cleanly
     * reloaded, and a killed process beats an infinite spinner; next launch is
     * clean.
     */
    private fun armConnectWatchdog() {
        val attempt = connectAttempt.incrementAndGet()
        val armedAt = android.os.SystemClock.elapsedRealtime()
        synchronized(recentAttempts) {
            recentAttempts.addLast(armedAt)
            while (recentAttempts.size > 20) recentAttempts.removeFirst()
        }
        startStage = "armed"
        Thread {
            try { Thread.sleep(45_000) } catch (_: InterruptedException) { return@Thread }
            // A later connect re-armed the watchdog, so this Starting is not ours: that
            // attempt has its own watchdog. Without this check the oldest watchdog fired
            // 45 s after ITS connect and killed a newer attempt only seconds old — 13 of
            // the 24 hangs in the 2026-10-31 crash digest had more watchdogs still asleep.
            if (attempt != connectAttempt.get()) return@Thread
            if (globalStatus.value != Status.Starting) return@Thread // reached Started/Stopped
            Log.w(TAG, "Connect watchdog: stuck in Starting 45s — capturing stacks")
            val sp = Application.application.getSharedPreferences("session", Context.MODE_PRIVATE)
            val bridges = "xray=${if (sp.getBoolean("xray", false)) "Y" else "N"}" +
                " outline=${if (sp.getBoolean("outline", false)) "Y" else "N"}" +
                " wireproxy=${if (sp.getBoolean("wireproxy", false)) "Y" else "N"}"
            // 1) Java/Kotlin thread stacks — needs no root, always succeeds, and
            //    catches a hang in the Kotlin/JNI layer (Room write, the
            //    CommandServer.start JNI call, coroutine starvation) that a Go
            //    dump alone would miss. Upload it FIRST so it lands even if the
            //    Go dump below stalls on a wedged runtime.
            try {
                val sb = StringBuilder()
                for ((th, frames) in Thread.getAllStackTraces()) {
                    sb.append('"').append(th.name).append("\" ").append(th.state).append('\n')
                    for (f in frames) sb.append("\tat ").append(f).append('\n')
                    sb.append('\n')
                }
                sb.append(watchdogTrailer(attempt, armedAt))
                com.vpn4tv.app.utils.CrashUpload.upload(sb.toString(), bridges, "hang-java")
            } catch (t: Throwable) {
                Log.w(TAG, "java stack dump failed: ${t.message}")
            }
            // 2) Go goroutines — pinpoints a sing-box-internal stall; runs last
            //    because runtime.Stack(all=true) can itself block if the Go
            //    runtime is wedged.
            try {
                val dump = io.nekohasekai.libbox.Libbox.debugGoroutineDump().value
                com.vpn4tv.app.utils.CrashUpload.upload(dump, bridges, "hang")
            } catch (t: Throwable) {
                Log.w(TAG, "goroutine dump failed: ${t.message}")
            }
            lastError.postValue("Не удалось подключиться (таймаут). Повторите.")
            status.postValue(Status.Stopped)
            // Give the UI + the upload thread a moment, then hard-reset the
            // wedged Go runtime.
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }.apply { name = "connect-watchdog" }.start()
    }

    /**
     * One line after the thread list of the hang-java report (the analyzer's parser
     * skips it): which attempt, how long it ran, how old the process is, how many
     * connects in the last minute, and the last start stage it reached.
     */
    private fun watchdogTrailer(attempt: Int, armedAt: Long): String {
        val now = android.os.SystemClock.elapsedRealtime()
        val lastMinute = synchronized(recentAttempts) { recentAttempts.count { now - it < 60_000 } }
        val uptime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            now - android.os.Process.getStartElapsedRealtime()
        } else {
            -1L
        }
        return "# vpn4tv watchdog attempt=$attempt attemptMs=${now - armedAt} processUptimeMs=$uptime " +
            "attemptsLast60s=$lastMinute stage=$startStage\n"
    }

    private fun markStage(name: String) {
        startStage = "$name@${android.os.SystemClock.elapsedRealtime()}"
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        Log.d(TAG, "onStartCommand, status=${status.value}")
        if (status.value != Status.Stopped) {
            // A start asked for while a stop runs is dropped with the service,
            // but it came through startForegroundService, and the system takes
            // the app down if the service ends without entering the foreground.
            // The stop closes this notification when it ends.
            if (status.value == Status.Stopping) {
                notification.show(lastProfileName, R.string.status_stopping)
                // The status is shared by both service classes: a start of the
                // other one (the mode changed meanwhile) has no stop to close it.
                if (!closing) {
                    notification.close()
                    service.stopSelf()
                }
            }
            return Service.START_NOT_STICKY
        }
        status.value = Status.Starting

        // Android 14 gives a foreground service only 5 seconds between
        // startForegroundService() and startForeground(). Our previous
        // code deferred startForeground() into the coroutine below,
        // which could miss the deadline on a loaded device and cause
        // ForegroundServiceDidNotStartInTimeException (seen on Xiaomi
        // TV Box S 2nd Gen / Android 14, 50103). Call immediately with
        // a placeholder notification; startService() updates the text
        // once the profile name is known.
        //
        // show() returns false when the system rejects the promotion
        // (boot temp-allowlist expired before our >10s cold start finished,
        // or FGS-type policy denial on multi-user firmware — vc50326
        // ServiceNotification.show crash clusters, 37 reports). Stop
        // cleanly: an unpromoted service gets killed anyway, and crashing
        // tells the user nothing. Do NOT route through stopAndAlert — it
        // clears startedByUser and would disable boot auto-connect forever.
        if (!notification.show("", R.string.status_starting)) {
            lastError.postValue("StartService: foreground start rejected by system")
            status.value = Status.Stopped
            service.stopSelf()
            return Service.START_NOT_STICKY
        }

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

        // Arm the connect-hang watchdog HERE, before the async start runs — the
        // hang has been observed inside startCommandServer() (libbox
        // CommandServer.start), which executes before startService()/Box.Start,
        // so arming it inside startService() (as before) could never catch it.
        armConnectWatchdog()

        GlobalScope.launch(Dispatchers.IO) {
            Settings.startedByUser = true
            // Gate the first Libbox touch (CommandServer.start) on Application's
            // onCreate finishing its Libbox first-touch + setup. At boot both
            // coroutines race and concurrently first-touching the gomobile/Go
            // runtime deadlocks CommandServer.start on a minority of TVs — the
            // boot-only connect hang (50329 regression). The watchdog armed
            // above is the backstop if this ever times out.
            if (!Application.awaitLibboxReady(60_000)) {
                Log.e(TAG, "Libbox setup not ready after 60s — aborting start")
                stopAndAlert(Alert.StartCommandServer, "Libbox init timeout")
                return@launch
            }
            markStage("command-server")
            try {
                startCommandServer()
            } catch (t: Throwable) {
                // catch Throwable, not Exception: a broken native-lib install
                // throws NoClassDefFoundError/UnsatisfiedLinkError (Errors),
                // which Exception wouldn't catch → the app crashed here on
                // Play split-misdelivery devices. Recognise it, mark broken so
                // HomeScreen guides reinstall, and stop cleanly instead.
                if (com.vpn4tv.app.utils.NativeLib.isLoadFailure(t)) {
                    com.vpn4tv.app.utils.NativeLib.markBroken(Application.application)
                    stopAndAlert(Alert.StartCommandServer,
                        Application.application.getString(R.string.error_install_broken))
                } else {
                    stopAndAlert(Alert.StartCommandServer, t.message)
                }
                return@launch
            }
            markStage("profile")
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
     * Shared runtime config pipeline applied by BOTH startService and
     * serviceReload0 so a reload produces the same config as a fresh start
     * (previously serviceReload reloaded the raw file, silently dropping
     * proxy-mode / FakeDNS / probed-DNS / bypass-LAN — so e.g. a proxy-mode
     * user got the TUN config back on every auto-update reload).
     *
     * Order matters: probed-DNS first (rewrites servers), then FakeDNS strip
     * (removes the fakeip server/rules when off / proxy-mode / auto-disabled),
     * then proxy-mode rewrite OR bypass-LAN. Throws only on a fatal proxy-mode
     * rewrite failure; the caller stopAndAlerts.
     */
    // ── Bridges ────────────────────────────────────────────────────────────

    private val bridgeLock = Any()

    /**
     * The sidecar each running bridge was started from, by kind, as it reads
     * on disk (outline: after its dynamic keys were resolved and written back).
     * Lets a reload restart only the bridges whose sidecar changed.
     */
    private val runningBridgeSidecars = mutableMapOf<String, String>()

    private fun bridgeSidecars(profilePath: String) = listOf(
        "xray" to File(com.vpn4tv.app.converter.ConfigGenerator.xraySidecarPath(profilePath)),
        "outline" to File(com.vpn4tv.app.converter.ConfigGenerator.outlineSidecarPath(profilePath)),
        "wireproxy" to File(com.vpn4tv.app.converter.ConfigGenerator.wgSidecarPath(profilePath)),
        "olcrtc" to File(com.vpn4tv.app.converter.ConfigGenerator.olcrtcSidecarPath(profilePath)),
        "trusttunnel" to File(com.vpn4tv.app.converter.ConfigGenerator.ttSidecarPath(profilePath)),
    )

    /**
     * Bring the bridges in line with the profile's sidecars: start the new
     * ones, restart the changed ones, stop the ones the profile no longer has.
     * A bridge whose sidecar did not change keeps running — its connections,
     * and olcRTC's slow WebRTC session, survive a reload that is not about it.
     *
     * Returns null on success. On a failure every bridge is stopped and the
     * result is "kind: message", for the alert.
     */
    private fun syncBridges(profilePath: String): String? = synchronized(bridgeLock) {
        for ((kind, file) in bridgeSidecars(profilePath)) {
            val text = if (file.exists()) file.readText() else null
            if (text == runningBridgeSidecars[kind]) continue
            try {
                if (text == null) {
                    Log.d(TAG, "Stopping the $kind bridge: the profile no longer has it")
                    stopBridge(kind)
                    runningBridgeSidecars.remove(kind)
                } else {
                    Log.d(TAG, "Starting the $kind bridge from ${file.name}")
                    runningBridgeSidecars[kind] = startBridge(kind, file, text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "$kind bridge failed to start: ${e.message}", e)
                stopAllBridgesLocked()
                return "$kind: ${e.message}"
            }
        }
        null
    }

    /** Start one bridge (a start replaces a running one). Returns the sidecar text to remember. */
    private fun startBridge(kind: String, file: File, text: String): String = when (kind) {
        "xray" -> {
            com.vpn4tv.app.xray.XrayBridge.start(service.applicationContext, BridgeAuth.applyToXray(text))
            text
        }
        "outline" -> {
            // Dynamic keys (ssconf://) are fetched now, not at import: the
            // provider rotates servers behind them.
            val resolved = com.vpn4tv.app.outline.OutlineDynamicKeys.resolveAndPersist(file)
            // Credentials in memory only — resolveAndPersist writes the sidecar
            // back, and the pair must never land on disk. The DNS servers too:
            // the bridge resolves its server by name with them, out of the tun.
            com.vpn4tv.app.outline.OutlineBridge.start(BridgeDns.applyToEndpoints(BridgeAuth.applyToEndpoints(resolved)))
            resolved
        }
        "wireproxy" -> {
            com.vpn4tv.app.wireproxy.WgBridge.start(BridgeDns.applyToEndpoints(BridgeAuth.applyToEndpoints(text)))
            text
        }
        "olcrtc" -> {
            // Not in the 32-bit libbox: say so, rather than a bare start error.
            if (!com.vpn4tv.app.olcrtc.OlcrtcBridge.isAvailable()) {
                throw IllegalStateException("olcRTC is not available on this device (32-bit build)")
            }
            com.vpn4tv.app.olcrtc.OlcrtcBridge.start(BridgeAuth.applyToEndpoints(text))
            text
        }
        "trusttunnel" -> {
            // Upstream sockets go through VpnService.protect; in proxy mode
            // there is no VPN and nothing to protect against.
            val vpn = service as? android.net.VpnService
            com.vpn4tv.app.trusttunnel.TrustTunnelBridge.start(BridgeAuth.applyToEndpoints(text)) { fd ->
                vpn?.protect(fd) ?: true
            }
            text
        }
        else -> throw IllegalArgumentException("unknown bridge $kind")
    }

    private fun stopBridge(kind: String) {
        when (kind) {
            "xray" -> com.vpn4tv.app.xray.XrayBridge.stop()
            "outline" -> com.vpn4tv.app.outline.OutlineBridge.stop()
            "wireproxy" -> com.vpn4tv.app.wireproxy.WgBridge.stop()
            "olcrtc" -> com.vpn4tv.app.olcrtc.OlcrtcBridge.stop()
            "trusttunnel" -> com.vpn4tv.app.trusttunnel.TrustTunnelBridge.stop()
        }
    }

    private fun stopAllBridges() = synchronized(bridgeLock) { stopAllBridgesLocked() }

    private fun stopAllBridgesLocked() {
        com.vpn4tv.app.xray.XrayBridge.stop()
        com.vpn4tv.app.outline.OutlineBridge.stop()
        com.vpn4tv.app.wireproxy.WgBridge.stop()
        com.vpn4tv.app.olcrtc.OlcrtcBridge.stop()
        com.vpn4tv.app.trusttunnel.TrustTunnelBridge.stop()
        runningBridgeSidecars.clear()
    }

    private fun applyConfigTransforms(raw: String): String {
        var content = raw
        // Probed DNS: the generated config has ConfigGenerator's defaults, but
        // the ISP may block those; DnsProber found which DoH/UDP actually work.
        try {
            content = injectProbedDns(content)
        } catch (e: Exception) {
            Log.w(TAG, "DNS injection failed, using config defaults: ${e.message}")
        }
        // Pre-resolved hosts: resolve every outbound hostname through the
        // app-level DoH fallback chain (Quad9→AdGuard→Google→CF) and pin the
        // answers into the config, so urltest/connect never depend on any
        // single runtime resolver being unblocked (2026-07: RKN killed
        // Google+CF DoH the same day — dns-direct alone is a single point
        // of failure). sing-box itself has no DNS-server failover.
        try {
            content = injectResolvedHosts(content)
        } catch (e: Exception) {
            Log.w(TAG, "Hosts pre-resolution failed, keeping config as-is: ${e.message}")
        }
        // FakeDNS off-switch: user toggle off, proxy mode (no TUN to map fake
        // IPs back), or the post-connect health check auto-disabled it because
        // the fakeip path is broken on this network.
        if (!Settings.fakeDns || Settings.isProxyMode || fakeDnsAutoDisabled) {
            try {
                content = stripFakeDns(content)
            } catch (e: Exception) {
                Log.w(TAG, "FakeDNS strip failed, keeping config as-is: ${e.message}")
            }
        }
        if (Settings.isProxyMode) {
            // Swap TUN for a SOCKS5 listener; may throw → fatal (caller alerts).
            content = rewriteConfigForProxyMode(content)
        } else {
            if (Settings.bypassLan) {
                try {
                    content = injectBypassLanRule(content)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to inject bypass-lan rule: ${e.message}")
                }
            }
            // Apps outside the VPN must not get in through the tun; the rule
            // goes first, ahead of the sniff/hijack-dns prelude.
            content = OutsideVpnGuard.applyToSingbox(content)
        }
        // Bridge inbounds want a password now (see BridgeAuth). Both the start
        // and the reload path come through here, with the same per-process pair.
        content = BridgeAuth.applyToSingbox(content)
        return content
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
     * Insert a route rule sending private destinations (RFC1918, link-local,
     * ULA, multicast) to "direct", so LAN traffic stays on the underlying
     * network. The FakeDNS ranges are carved out: fc00::/18 lies inside ULA,
     * and an app may still hold a fake address from an earlier session after
     * FakeDNS was turned off and nothing maps it back. Sent direct it can
     * only fail on the LAN ("network is unreachable"); through the proxy the
     * server recovers the domain from the request.
     * The rule is placed *after* the leading sniff and hijack-dns rules so
     * DNS still goes through the tunnel, but *before* the UDP reject rule of
     * profiles made only of TCP-only bridges, so LAN UDP still goes direct.
     * Idempotent: checks for an existing ip_is_private rule (plain or inside
     * a logical rule) and returns the config unchanged if one is present.
     */
    private fun injectBypassLanRule(jsonText: String): String {
        val root = org.json.JSONObject(jsonText)
        val route = root.optJSONObject("route") ?: return jsonText
        val oldRules = route.optJSONArray("rules") ?: return jsonText

        // Already there? No-op.
        fun isPrivateRule(rule: org.json.JSONObject?): Boolean = rule != null &&
            (rule.optBoolean("ip_is_private", false) ||
                rule.optJSONArray("rules")?.let { sub -> (0 until sub.length()).any { isPrivateRule(sub.optJSONObject(it)) } } == true)
        for (i in 0 until oldRules.length()) {
            if (isPrivateRule(oldRules.getJSONObject(i))) return jsonText
        }

        val fakeRanges = org.json.JSONArray()
            .put(com.vpn4tv.app.converter.ConfigGenerator.FAKEIP_INET4_RANGE)
            .put(com.vpn4tv.app.converter.ConfigGenerator.FAKEIP_INET6_RANGE)
        val bypassRule = org.json.JSONObject().apply {
            put("type", "logical")
            put("mode", "and")
            put("rules", org.json.JSONArray().apply {
                put(org.json.JSONObject().put("ip_is_private", true))
                put(org.json.JSONObject().put("ip_cidr", fakeRanges).put("invert", true))
            })
            put("action", "route")
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
     * Removes everything ConfigGenerator emitted for FakeDNS: the fakeip
     * server, the HTTPS/SVCB-reject and A/AAAA-fakeip DNS rules, and the
     * whole experimental.cache_file block (it exists solely to persist
     * fakeip mappings — removing it entirely restores pre-feature behavior,
     * including not creating cache.db on multi-user TVs with broken
     * externals). Queries then fall through to dns-remote like before the
     * feature existed.
     *
     * COUPLED to ConfigGenerator.buildDns: the reject-rule predicate below
     * matches the exact query_type set generated there. Any new DNS reject
     * rule with a query_type must be excluded from this match.
     */
    private fun stripFakeDns(jsonText: String): String {
        val root = org.json.JSONObject(jsonText)
        val dns = root.optJSONObject("dns") ?: return jsonText
        dns.optJSONArray("servers")?.let { servers ->
            for (i in servers.length() - 1 downTo 0) {
                if (servers.getJSONObject(i).optString("tag") == "dns-fakeip") servers.remove(i)
            }
        }
        dns.optJSONArray("rules")?.let { rules ->
            for (i in rules.length() - 1 downTo 0) {
                val rule = rules.getJSONObject(i)
                val isFakeipRule = rule.optString("server") == "dns-fakeip"
                val queryTypes = rule.optJSONArray("query_type")?.let { qt ->
                    (0 until qt.length()).map { qt.optString(it) }.toSet()
                }
                val isHttpsReject = rule.optString("action") == "reject" &&
                    queryTypes == setOf("HTTPS", "SVCB")
                if (isFakeipRule || isHttpsReject) rules.remove(i)
            }
        }
        root.optJSONObject("experimental")?.let { experimental ->
            experimental.remove("cache_file")
            if (experimental.length() == 0) root.remove("experimental")
        }
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
        // dns-remote goes through the tunnel; where it can go through one of
        // our bridges, the fallback asks the same server over TCP instead of
        // UDP (ConfigGenerator.dnsOverTcp).
        val remoteOverTcp = com.vpn4tv.app.converter.ConfigGenerator.dnsOverTcp(root)

        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            val tag = server.optString("tag", "")
            when (tag) {
                "dns-remote" -> {
                    // DoH server that goes through the VPN tunnel. Same
                    // rule as dns-direct — prefer DoH, fall back to UDP
                    // only if nothing in the DoH chain was reachable.
                    if (com.vpn4tv.app.utils.DnsProber.dohWorks()) {
                        val dohHost = com.vpn4tv.app.utils.DnsProber.dohServer()
                        server.put("type", "https")
                        server.put("server", dohHost)
                        Log.i(TAG, "DNS: dns-remote → https $dohHost (probed)")
                    } else {
                        val udp = com.vpn4tv.app.utils.DnsProber.udpServer()
                        val type = if (remoteOverTcp) "tcp" else "udp"
                        server.put("type", type)
                        server.put("server", udp)
                        Log.i(TAG, "DNS: dns-remote → $type $udp (DoH unavailable, fallback)")
                    }
                }
                "dns-direct" -> {
                    // Prefer DoH over plain UDP: Russian TSPU per-domain-filters
                    // UDP DNS (narva.a4e.ar "empty result" cluster on vc50307).
                    // DoH inside TLS hides the queried name. Fall back to UDP
                    // only if no DoH endpoint was reachable during probe.
                    val dohIp = com.vpn4tv.app.utils.DnsProber.dohCapableIp()
                    if (dohIp != null) {
                        server.put("type", "https")
                        server.put("server", dohIp)
                        server.put("path", "/dns-query")
                        Log.i(TAG, "DNS: dns-direct → https $dohIp/dns-query (probed)")
                    } else {
                        val udp = com.vpn4tv.app.utils.DnsProber.udpServer()
                        server.put("type", "udp")
                        server.put("server", udp)
                        Log.i(TAG, "DNS: dns-direct → udp $udp (DoH unavailable, fallback)")
                    }
                }
            }
        }

        return root.toString()
    }

    /**
     * Pre-resolve every outbound server hostname through the app-level DoH
     * fallback chain ([HwidService.preResolveHosts]: Quad9 → AdGuard → Google
     * → Cloudflare, wireformat+JSON, cached 6h with stale fallback) and pin
     * the answers into the config as a sing-box `hosts` DNS server:
     *
     *   servers += {type: "hosts", tag: "dns-hosts", predefined: {host: [ips]}}
     *   rules   =  [{domain: [hosts...], server: "dns-hosts"}] + old rules
     *
     * The rule is scoped to exactly the resolved names and inserted FIRST, so
     * it wins over the `outbound: any → dns-direct` rule for those names while
     * everything else keeps today's path. If nothing resolves (total DoH
     * blackout, first run offline), the config is returned unchanged — the
     * runtime dns-direct path still applies.
     *
     * Runs on the service-start thread (network is fine here — the inline
     * subscription recovery above does HTTP on the same path).
     */
    private fun injectResolvedHosts(jsonText: String): String {
        val root = org.json.JSONObject(jsonText)
        val dns = root.optJSONObject("dns") ?: return jsonText
        val outbounds = root.optJSONArray("outbounds") ?: return jsonText

        val ipRe = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
        val hosts = mutableListOf<String>()
        for (i in 0 until outbounds.length()) {
            val server = outbounds.getJSONObject(i).optString("server", "")
            if (server.isBlank() || server.contains(':') || server.matches(ipRe)) continue
            if (server == "localhost") continue
            hosts.add(server)
        }
        if (hosts.isEmpty()) return jsonText

        val resolved = com.vpn4tv.app.converter.HwidService.preResolveHosts(
            com.vpn4tv.app.Application.application, hosts,
        )
        if (resolved.isEmpty()) {
            Log.w(TAG, "DNS: no outbound hostnames resolved via DoH chain, keeping config as-is")
            return jsonText
        }

        dns.optJSONArray("servers")?.put(org.json.JSONObject().apply {
            put("type", "hosts")
            put("tag", "dns-hosts")
            put("predefined", org.json.JSONObject().apply {
                resolved.forEach { (host, ips) -> put(host, org.json.JSONArray(ips)) }
            })
        }) ?: return jsonText

        val hostsRule = org.json.JSONObject().apply {
            put("domain", org.json.JSONArray(resolved.keys.toList()))
            put("server", "dns-hosts")
        }
        // org.json has no insert-at-index — rebuild with the hosts rule first.
        val oldRules = dns.optJSONArray("rules") ?: org.json.JSONArray()
        val newRules = org.json.JSONArray().put(hostsRule)
        for (i in 0 until oldRules.length()) newRules.put(oldRules.get(i))
        dns.put("rules", newRules)

        Log.i(TAG, "DNS: pinned ${resolved.size}/${hosts.distinct().size} outbound hostnames via DoH chain")
        return root.toString()
    }
}
