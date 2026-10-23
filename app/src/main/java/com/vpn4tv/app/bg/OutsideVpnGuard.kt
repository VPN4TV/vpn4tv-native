package com.vpn4tv.app.bg

import android.os.Build
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import com.vpn4tv.app.Application
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.Settings
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

/**
 * Keeps apps that are not routed into our VPN out of the tun.
 *
 * Any app can bind a socket to the tun interface: SO_BINDTODEVICE needs no
 * privilege since kernel 5.7, IP_UNICAST_IF needs none on any kernel. Packets
 * sent that way reach sing-box even from an app excluded in the per-app
 * settings or living in another user / work profile, sing-box proxies them,
 * and the app learns our server's IP (Habr 1089082, part 2).
 *
 * Android 10+ looks connection owners up with getConnectionOwnerUid, which
 * names only the owners this VPN applies to and returns INVALID_UID for the
 * rest. Those connections get the [OUTSIDE] package name, and the reject rule
 * for it goes first in the route. Android 9 and older read /proc and see every
 * uid, so there the excluded apps' uids are rejected by number.
 *
 * Include mode is left alone: the system resolver serves the included apps
 * under its own uid (DNS on Android 10, DNS-over-TLS on 11+), the VPN does not
 * apply to that uid, and the rule would cut their DNS.
 *
 * Safety valve: if a device's system answers INVALID_UID even for our own
 * socket, the rule would reject every app. [verifyAfterStart] checks that once
 * per connect and drops the rule for the session if so.
 */
object OutsideVpnGuard {
    private const val TAG = "OutsideVpnGuard"

    /** Not a valid package name, so no installed app can match it. */
    const val OUTSIDE = "!outside-vpn"

    /**
     * Set by [verifyAfterStart] when the system does not name the owner of our
     * own socket. While set the rule stays out of the config, so the reload
     * converges. Cleared by startService on every fresh connect; the reload
     * that drops the rule does not clear it.
     */
    @Volatile
    var ownerLookupBroken = false

    fun outsideOwner(): ConnectionOwner = ConnectionOwner().apply {
        userId = -1
        setAndroidPackageNames(PlatformInterfaceWrapper.StringArray(listOf(OUTSIDE).iterator()))
    }

    fun applyToSingbox(json: String): String {
        val rule = rule() ?: return json
        return try {
            val root = JSONObject(json)
            val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
            val old = route.optJSONArray("rules") ?: JSONArray()
            val rules = JSONArray().put(rule)
            for (i in 0 until old.length()) rules.put(old.get(i))
            route.put("rules", rules)
            root.toString()
        } catch (e: Exception) {
            Log.w(TAG, "not applied: ${e.message}")
            json
        }
    }

    /** Whether [json] carries the Android 10+ rule, i.e. [verifyAfterStart] is worth running. */
    fun inConfig(json: String): Boolean = json.contains("\"$OUTSIDE\"")

    /**
     * Off the main thread: make sure the system names the owner of our own
     * socket inside the tunnel; if it does not, drop the rule and reload.
     */
    fun verifyAfterStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        Thread {
            try {
                Thread.sleep(1000) // let the VPN network register
                if (ownerLookupWorks() || BoxService.globalStatus.value != Status.Started) return@Thread
                Log.w(TAG, "the system does not name our own socket's owner; dropping the rule for this session")
                ownerLookupBroken = true
                val client = Libbox.newStandaloneCommandClient()
                try { client.serviceReload() } finally { client.disconnect() }
            } catch (e: Exception) {
                Log.w(TAG, "self-check failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Connects a UDP socket through the tunnel (connect only picks the route,
     * nothing is sent) and asks the system whose it is. Broken means INVALID_UID
     * with never a real answer; an exception says nothing about the lookup the
     * rule depends on (sing-box treats it as "no owner", which rejects nothing).
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ownerLookupWorks(attempts: Int = 3): Boolean {
        var invalid = 0
        repeat(attempts) { attempt ->
            val uid = try {
                DatagramChannel.open().use { channel ->
                    channel.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), 53))
                    Application.connectivity.getConnectionOwnerUid(
                        OsConstants.IPPROTO_UDP,
                        channel.localAddress as InetSocketAddress,
                        channel.remoteAddress as InetSocketAddress,
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "self-check ${attempt + 1}: ${e.message}")
                null
            }
            when (uid) {
                null -> {}
                Process.INVALID_UID -> invalid++
                else -> {
                    Log.d(TAG, "self-check: owner lookup works (uid $uid)")
                    return true
                }
            }
            if (attempt < attempts - 1) Thread.sleep(1000)
        }
        return invalid == 0
    }

    private fun rule(): JSONObject? {
        val perApp = Settings.perAppProxyEnabled
        if (perApp && Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ownerLookupBroken) return null
            return JSONObject().put("package_name", JSONArray().put(OUTSIDE)).put("action", "reject")
        }
        if (!perApp) return null
        val pm = Application.packageManager
        val uids = (Settings.getEffectivePerAppProxyList() - Application.application.packageName)
            .mapNotNull { runCatching { pm.getApplicationInfo(it, 0).uid }.getOrNull() }
            .distinct()
        if (uids.isEmpty()) return null
        return JSONObject().put("user_id", JSONArray(uids)).put("action", "reject")
    }
}
