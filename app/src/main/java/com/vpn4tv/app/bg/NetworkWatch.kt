package com.vpn4tv.app.bg

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.vpn4tv.app.Application
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.utils.SupportNotes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device's networks through a VPN session, written down for support as
 * they change (NetworkReport has the words): what Android had before the tun
 * came up, every change in the set of its networks after, the interfaces the
 * core was given, and whether the VPN's own connections still have a route:
 * after each start and reload, after each change, once a minute, and when
 * the check after a start finds the tunnel dead.
 *
 * Why: the support case of 2026-11-21 (a Xiaomi TV on Android 12: "connected",
 * and every connection of the core "network is unreachable") and the ones
 * like it. The core leaves by a socket taken out of the VPN and nothing else,
 * so the answer is in what Android does with such a socket on that device,
 * and when it stops: whether a network goes away once the VPN is up, whether
 * a socket tied to the network by hand still gets through, whether the
 * interface that has the internet is one of Android's networks at all. One
 * line at a start does not tell these apart.
 *
 * Everything runs on a thread of its own: the lines cost calls to the system,
 * and Android's network callbacks otherwise come on the main thread. The
 * lines go to the system log and SupportNotes; the short ones to the core's
 * log as well (the Logs screen).
 */
object NetworkWatch {
    private const val TAG = "NetworkWatch"

    /** Android settles its networks around a new tun. */
    private const val SETTLE_MS = 2000L
    private const val NETWORKS_AFTER_MS = 700L
    private const val ROUTES_AFTER_MS = 1500L
    private const val ROUTES_EVERY_MS = 60_000L
    private const val ROUTES_AGAIN_MS = 5_000L
    private const val TROUBLE_GAP_MS = 30_000L

    /**
     * Where the lines go besides the system log and SupportNotes: the core's
     * log, while its command server runs (BoxService). The second argument:
     * the line tells of trouble.
     */
    @Volatile
    var journal: ((String, Boolean) -> Unit)? = null

    /** The last "interfaces given to the core" line, for the report. */
    @Volatile
    var given: String? = null
        private set

    private var thread: HandlerThread? = null

    @Volatile
    private var handler: Handler? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    // The watch thread's own, each the last line of its kind: the same again is not written.
    private var lastTold: String? = null
    private var lastNetworks: String? = null
    private var lastRoutes: String? = null
    private var lastRoutesAt = 0L
    private var lastGiven: String? = null
    private var lastTroubleAt = 0L

    private val networksPending = AtomicBoolean(false)
    private val routesPending = AtomicBoolean(false)
    private val protectRefused = AtomicInteger()

    // What each network's capabilities said last: a change of signal strength alone is not a change.
    private val seen = ConcurrentHashMap<Network, String>()

    /** At a start of the VPN, before the tun: the thread, the watch on Android's networks, the state before. */
    @Synchronized
    fun start() {
        stop()
        val thread = HandlerThread("network-watch").apply { start() }
        val handler = Handler(thread.looper)
        this.thread = thread
        this.handler = handler
        protectRefused.set(0)
        networksPending.set(false)
        routesPending.set(false)
        seen.clear()
        handler.post {
            lastTold = null
            lastNetworks = null
            lastRoutes = null
            lastRoutesAt = 0
            lastGiven = null
            lastTroubleAt = 0
            given = null
            safely("the state before the start") { before() }
        }
        register(handler)
        // Each minute: the routes can go while Android's networks stay as they were.
        handler.postDelayed(
            object : Runnable {
                override fun run() {
                    safely("the routes each minute") {
                        if (BoxService.globalStatus.value == Status.Started) routes(always = false)
                    }
                    // Not after a stop, nor into the next session's thread.
                    if (this@NetworkWatch.handler === handler) handler.postDelayed(this, ROUTES_EVERY_MS)
                }
            },
            ROUTES_EVERY_MS,
        )
    }

    @Synchronized
    fun stop() {
        callback?.let { runCatching { Application.connectivity.unregisterNetworkCallback(it) } }
        callback = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    // ---- what the rest of the app tells

    /**
     * The core was just told its default network: [network] (null: none),
     * with [link] and [index] as the caller read them. One line when it
     * differs from the last, then a look at the routes.
     */
    fun told(network: Network?, problem: String?, link: LinkProperties? = null, index: Int? = null) = run("the network line") {
        val facts = network?.let { NetworkReport.facts(it, link ?: Application.connectivity.getLinkProperties(it), index) }
        val key = (facts?.let(NetworkReport::describe) ?: "none") + (problem ?: "")
        if (key != lastTold) {
            lastTold = key
            val (line, trouble) = NetworkReport.lineFor(network, problem, facts)
            write(line, trouble)
            routesSoon()
        }
    }

    /**
     * The core has just read the device's interfaces (getInterfaces):
     * [described], each as "wlan0 (16) wifi", with their [indices]; [told]:
     * the default interface it was last told. A line when the list changes;
     * a warning in the Logs screen too when that default is not in it.
     */
    fun given(described: List<String>, indices: Set<Int>, told: Pair<String, Int>?) = run("the core's interfaces") {
        val (line, missing) = NetworkReport.givenLine(described, indices, told)
        given = line
        if (line != lastGiven) {
            lastGiven = line
            write(line, missing, toJournal = missing)
        }
    }

    /** Android would not take a socket of the core out of the VPN (VpnService.protect): such a connection loops into the tunnel. */
    fun protectRefused() {
        val count = protectRefused.incrementAndGet()
        if (count == 1 || count == 10 || count == 100 || count == 1000) {
            run("the protect line") {
                write("protect: Android would not take a socket of the core out of the VPN ($count so far)", true)
            }
        }
    }

    /** After a start or a reload, once Android has settled: the network, the interfaces, the routes. */
    fun afterStart() {
        val handler = handler
        if (handler != null) {
            handler.postDelayed({ safely("the lines after a start") { everything() } }, SETTLE_MS)
        } else {
            // No watch (it could not start): the lines all the same.
            Thread {
                safely("the lines after a start") {
                    Thread.sleep(SETTLE_MS)
                    everything()
                }
            }.apply { name = "network-report" }.start()
        }
    }

    /** The tunnel carries nothing (the check after a start): the same lines again, at most twice a minute. */
    fun tunnelDead() = run("the lines for a dead tunnel") {
        val now = SystemClock.elapsedRealtime()
        if (lastTroubleAt == 0L || now - lastTroubleAt >= TROUBLE_GAP_MS) {
            lastTroubleAt = now
            everything()
        }
    }

    // ---- the watch itself

    private fun before() {
        // Before the tun this app's own default is the system's, or another app's VPN.
        val network = Application.connectivity.activeNetwork
        write("before the start: " + NetworkReport.lineFor(network).first, false, toJournal = false)
        write("before the start: " + NetworkReport.interfacesLine(), false, toJournal = false)
        write("before the start: " + NetworkReport.routeCheck(network).first, false, toJournal = false)
    }

    private fun everything() {
        val (network, noNetwork) = DefaultNetworkMonitor.lineNow()
        write(network, noNetwork)
        write(NetworkReport.interfacesLine(), false, toJournal = false)
        routes(always = true)
    }

    /**
     * [always]: after a start and for a dead tunnel the line is written even
     * when nothing changed, unless it was written a moment ago. Otherwise a
     * line only when it differs from the last; while the VPN's own
     * connections have no route, the same line again goes to the Logs screen
     * alone, where the customer's photo shows the newest lines only: the
     * notes have it once.
     */
    private fun routes(always: Boolean) {
        val (line, noRoute) = NetworkReport.routeCheck()
        val now = SystemClock.elapsedRealtime()
        if (line != lastRoutes || (always && now - lastRoutesAt >= ROUTES_AGAIN_MS)) {
            lastRoutes = line
            lastRoutesAt = now
            write(line, noRoute)
        } else if (noRoute && now - lastRoutesAt >= ROUTES_AGAIN_MS) {
            lastRoutesAt = now
            journal?.invoke(line, true)
        }
    }

    private fun routesSoon() {
        val handler = handler ?: return
        if (routesPending.compareAndSet(false, true)) {
            handler.postDelayed({
                routesPending.set(false)
                safely("the routes after a change") { routes(always = false) }
            }, ROUTES_AFTER_MS)
        }
    }

    private fun networksSoon() {
        val handler = handler ?: return
        if (networksPending.compareAndSet(false, true)) {
            handler.postDelayed({
                networksPending.set(false)
                safely("the networks line") {
                    val line = NetworkReport.networksLine(NetworkReport.allNetworks().map { it.second })
                    if (line != lastNetworks) {
                        // The first line of a session is what was there before the tun: the notes' own.
                        val first = lastNetworks == null
                        lastNetworks = line
                        write(line, false, toJournal = !first)
                        if (!first) routesSoon()
                    }
                }
            }, NETWORKS_AFTER_MS)
        }
    }

    private class Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networksSoon()

        override fun onLost(network: Network) {
            seen.remove(network)
            networksSoon()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // Wi-Fi reports its signal strength here every few seconds: only what the lines show counts.
            val print = try {
                "${networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}" +
                    "${networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}" +
                    NetworkReport.marks(networkCapabilities).joinToString()
            } catch (t: Throwable) {
                return
            }
            if (seen.put(network, print) != print) networksSoon()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = networksSoon()
    }

    /**
     * Every network, the VPN's and the ones this app may not use too: the
     * callback only says that something changed, the line is read from
     * Android afterwards. Some multi-user TVs refuse the registration
     * (DefaultNetworkListener): the watch then goes without, and the lines
     * after a start, at a change of the default network and each minute stay.
     */
    private fun register(handler: Handler) {
        fun request(wide: Boolean): NetworkRequest = NetworkRequest.Builder().apply {
            removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            if (wide) {
                removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            }
        }.build()
        for (wide in listOf(true, false)) {
            val callback = Callback()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Application.connectivity.registerNetworkCallback(request(wide), callback, handler)
                } else {
                    Application.connectivity.registerNetworkCallback(request(wide), callback)
                }
                this.callback = callback
                return
            } catch (e: RuntimeException) {
                Log.w(TAG, "networks not watched (${if (wide) "every network" else "the app's networks"}): ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        handler.post { write("networks: Android would not let the app watch them, changes in between are not noted", false, toJournal = false) }
    }

    // ---- writing

    private fun write(line: String, trouble: Boolean, toJournal: Boolean = true) {
        try {
            if (trouble) Log.w(TAG, line) else Log.i(TAG, line)
            SupportNotes.add(line)
            if (toJournal) journal?.invoke(line, trouble)
        } catch (t: Throwable) {
            Log.w(TAG, "line not written: ${t.message}")
        }
    }

    /** On the watch thread; on the caller's when the watch is not running. Never throws. */
    private fun run(what: String, task: () -> Unit) {
        val handler = handler
        if (handler != null) handler.post { safely(what, task) } else safely(what, task)
    }

    private fun safely(what: String, task: () -> Unit) {
        try {
            task()
        } catch (t: Throwable) {
            Log.w(TAG, "$what: not written: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
