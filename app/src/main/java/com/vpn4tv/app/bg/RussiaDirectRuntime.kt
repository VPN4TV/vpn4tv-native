package com.vpn4tv.app.bg

import android.content.Context
import android.util.Log
import com.vpn4tv.app.BuildConfig
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.converter.HwidService
import com.vpn4tv.app.database.Settings
import io.nekohasekai.libbox.Libbox
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What surrounds [RussiaDirect] at every start and reload of the service:
 * whether the block goes in, and what keeps a bad block from costing the user
 * the VPN.
 *
 * - Off when the user sends everything through the VPN and when our server
 *   turned it off for the profile (x-vpn4tv-ru-direct: 0); plain BitTorrent
 *   goes direct then too. Off for the rest of the session after the crash
 *   guard or a failed start: then the config is exactly the base.
 * - Crash guard. A start or reload with the block leaves a mark, written
 *   synchronously, until it has run 30 s or the service stops in order. A mark
 *   a later process finds means the process died with the block in (a Go
 *   panic, a kill, the connect watchdog): that session goes without it, and
 *   after two such sessions in a row it stays off until the app version or
 *   the lists change.
 * - Before a config with the block reaches the core it goes once through
 *   Libbox.checkConfig, cached by app version and the parts the block
 *   touches. That catches what box.New refuses (an old DNS rule field the
 *   block's DNS mode does not take, a file the core cannot read) without
 *   dropping the tunnel. The block is blamed only if the config without it
 *   passes the same check.
 * - A start or reload that fails with the block is retried without it
 *   (BoxService); the block then stays off for the session, and the files are
 *   copied anew if the core could not read them.
 * - After connect, a DNS query for a Russian name goes through the tunnel; if
 *   dns-ru does not answer while the proxy does, our DNS rule resolves through
 *   dns-direct for the session.
 */
object RussiaDirectRuntime {
    private const val TAG = "RussiaDirect"
    private const val PREFS = "ru_direct"
    private const val KEY_PENDING = "pending"
    private const val KEY_STRIKES = "strikes"
    private const val KEY_STRIKES_FOR = "strikes_for"
    private const val KEY_CHECKED = "checked"
    private const val MAX_STRIKES = 2
    private const val SURVIVE_MS = 30_000L

    /** The tun's DNS address (ConfigGenerator: 172.19.0.1/30), the one the system resolver asks. */
    private val TUN_DNS = byteArrayOf(172.toByte(), 19, 0, 2)
    private const val PROBE_NAME = "ya.ru"

    /** Why the block stays out until the next connect the user asks for, or null. */
    @Volatile
    private var sessionOffReason: String? = null

    /** dns-ru did not answer the self-check: our DNS rule uses dns-direct until the next connect. */
    @Volatile
    var dnsRuFallback = false

    private val dnsCheckStarted = AtomicBoolean(false)

    // Crash guard: whether the mark on disk is this process's own, and which
    // arming the 30 s timer belongs to.
    @Volatile
    private var armedHere = false
    private val armToken = AtomicInteger()

    // What checkConfig said in this process, by key: the problem, or null
    // when there is nothing to hold against the block. Only a pass is kept on
    // disk; a new process, maybe a new app version, asks again.
    private val checkVerdicts = mutableMapOf<String, String?>()

    class Prepared(
        /** What to start. */
        val content: String,
        /** Summary for the log line. */
        val state: String,
        /** Log it as a warning: the block is degraded or off for a reason worth a look. */
        val warn: Boolean,
        /** The crash guard is armed for this start. */
        val guarded: Boolean = false,
        /** dns-ru is in it, so its self-check is worth running. */
        val usesDnsRu: Boolean = false,
    ) {
        val line: String get() = "russia-direct: $state [lists ${RussiaDirectLists.REVISION}]"
    }

    /** A connect the user asked for: what the last session learned starts over. */
    fun newSession() {
        sessionOffReason = null
        dnsRuFallback = false
        dnsCheckStarted.set(false)
    }

    /** The config to start for [base], the config without the block. Never throws. */
    fun prepare(context: Context, base: String, profileId: Long): Prepared = try {
        prepare0(context, base, profileId)
    } catch (t: Throwable) {
        Log.e(TAG, "not applied: ${t.message}", t)
        try {
            disarm(context, survived = false)
        } catch (_: Throwable) {
        }
        Prepared(torrentsOnly(base, "error"), "off (error: ${t.javaClass.simpleName}: ${t.message})", warn = true)
    }

    /**
     * [base] with the plain-BitTorrent rule alone: what runs while the block is
     * off for safety (crash guard, a failed start or check). Torrents go direct
     * in every mode, and that rule alone cannot fail a start.
     */
    fun torrentsOnly(base: String, reason: String): String = try {
        RussiaDirect.apply(base, RussiaDirect.Options(offReason = reason)).json
    } catch (t: Throwable) {
        Log.e(TAG, "torrents rule not applied: ${t.message}", t)
        base
    }

    private fun prepare0(context: Context, base: String, profileId: Long): Prepared {
        admit(context)?.let { return Prepared(torrentsOnly(base, it), "off ($it)", warn = true) }
        sessionOffReason?.let { return Prepared(torrentsOnly(base, it), "off ($it)", warn = true) }
        val offReason = when {
            Settings.allThroughVpn -> "user"
            HwidService.ruDirectOff(context, profileId) -> "head"
            else -> null
        }
        val sets = if (offReason == null) RuleSetFiles.verified(context) else null
        val result = RussiaDirect.apply(
            base,
            RussiaDirect.Options(
                offReason = offReason,
                sets = sets,
                dnsRuFallback = dnsRuFallback,
                extraExceptions = HwidService.ruVpnExtra(context, profileId),
            ),
        )
        val warn = (result.applied && (sets == null || dnsRuFallback)) ||
            result.state.startsWith("off (legacy") || result.state.startsWith("off (error")
        if (!result.applied) {
            // The torrent rule alone cannot fail a start: no mark for it, and a
            // mark an earlier start with the block left is not this config's.
            disarm(context, survived = false)
            return Prepared(result.json, result.state, warn)
        }
        arm(context)
        checkProblem(context, result.json, base)?.let { problem ->
            disarm(context, survived = false)
            return Prepared(torrentsOnly(base, "check: $problem"), "off (check: $problem)", warn = true)
        }
        return Prepared(result.json, result.state, warn, guarded = true, usesDnsRu = result.usesDnsRu)
    }

    /** The core runs [prepared]: once it has run a while, the crash guard comes off. */
    fun onRunning(context: Context, prepared: Prepared) {
        if (!prepared.guarded) return
        val token = armToken.get()
        Thread {
            try {
                Thread.sleep(SURVIVE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            synchronized(this) {
                if (token == armToken.get() && armedHere && BoxService.globalStatus.value == Status.Started) {
                    disarm(context, survived = true)
                }
            }
        }.apply {
            name = "ru-direct-guard"
            isDaemon = true
        }.start()
    }

    /** The start or reload with the block failed, and the base started in its place. */
    fun rolledBack(context: Context, error: String?) {
        sessionOffReason = "session: the start with it failed"
        disarm(context, survived = false)
        // The core names the set it could not read; anything else is not the files' fault.
        if (error?.contains("rule-set") == true) RuleSetFiles.invalidate(context)
    }

    /** The service stopped, in order or on an error it reported: no crash to guard against. */
    fun onStopped(context: Context) = disarm(context, survived = false)

    /** Whether to start the dns-ru self-check now: once per session, in VPN mode, while dns-ru is in use. */
    fun claimDnsCheck(prepared: Prepared): Boolean =
        prepared.usesDnsRu && !dnsRuFallback && !Settings.isProxyMode && dnsCheckStarted.compareAndSet(false, true)

    /**
     * A DNS query for a Russian name through the tunnel, as an app's would go:
     * to the tun's DNS address, hijacked by the core and sent by our rule to
     * dns-ru. True on an answer with an address.
     */
    fun probeDnsRu(attempts: Int, stillWanted: () -> Boolean): Boolean {
        val query = HwidService.buildDnsQuery(PROBE_NAME)
        repeat(attempts) { i ->
            if (!stillWanted()) return false
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 5000
                    socket.send(DatagramPacket(query, query.size, InetAddress.getByAddress(TUN_DNS), 53))
                    val buffer = ByteArray(1500)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (HwidService.parseDnsAnswers(buffer.copyOf(packet.length)).isNotEmpty()) return true
                    Log.w(TAG, "dns-ru probe ${i + 1}/$attempts: no address in the answer")
                }
            } catch (e: Exception) {
                Log.w(TAG, "dns-ru probe ${i + 1}/$attempts: ${e.javaClass.simpleName}: ${e.message}")
            }
            if (i < attempts - 1) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun guardKey() = "${BuildConfig.VERSION_CODE}:${RussiaDirectLists.REVISION}"

    /** Consumes a mark an earlier process left. Null: the block may go in. */
    @Synchronized
    private fun admit(context: Context): String? {
        val prefs = prefs(context)
        val key = guardKey()
        var strikes = if (prefs.getString(KEY_STRIKES_FOR, null) == key) prefs.getInt(KEY_STRIKES, 0) else 0
        val pending = prefs.getString(KEY_PENDING, null)
        if (pending != null && !armedHere) {
            // An older version's mark says nothing about this one.
            val counts = pending == key
            if (counts) strikes++
            prefs.edit().remove(KEY_PENDING).putInt(KEY_STRIKES, strikes).putString(KEY_STRIKES_FOR, key).commit()
            if (counts) {
                val reason = "crash guard: the last start with it did not survive, $strikes of $MAX_STRIKES"
                Log.w(TAG, reason)
                sessionOffReason = reason
                return reason
            }
        }
        if (strikes >= MAX_STRIKES) return "crash guard: off until the next version or lists"
        return null
    }

    @Synchronized
    private fun arm(context: Context) {
        // A reload while armed: the mark is on disk already, only the 30 s start over.
        if (!armedHere) prefs(context).edit().putString(KEY_PENDING, guardKey()).commit()
        armedHere = true
        armToken.incrementAndGet()
    }

    @Synchronized
    private fun disarm(context: Context, survived: Boolean) {
        if (!armedHere) return
        armedHere = false
        armToken.incrementAndGet()
        val editor = prefs(context).edit().remove(KEY_PENDING)
        // Only a block that ran proves the earlier strikes were bad luck.
        if (survived) editor.remove(KEY_STRIKES).remove(KEY_STRIKES_FOR)
        editor.commit()
    }

    /**
     * Why checkConfig refuses [content], the config with the block, or null.
     * The block is blamed only if [base], the same config without it, passes:
     * a check that refuses both says nothing about the block, and the start
     * (with its retry without the block) decides as before. Null also when
     * the check is not available.
     */
    private fun checkProblem(context: Context, content: String, base: String): String? {
        val key = try {
            checkKey(content)
        } catch (e: Exception) {
            return null
        }
        val prefs = prefs(context)
        if (prefs.getString(KEY_CHECKED, null) == key) return null
        synchronized(checkVerdicts) {
            if (key in checkVerdicts) return checkVerdicts[key]
        }
        val verdict: String? = try {
            Libbox.checkConfig(content)
            prefs.edit().putString(KEY_CHECKED, key).apply()
            null
        } catch (e: Exception) {
            val problem = (e.message ?: e.javaClass.simpleName).take(200)
            val baseRefused = try {
                Libbox.checkConfig(base)
                false
            } catch (_: Throwable) {
                true
            }
            if (baseRefused) {
                Log.w(TAG, "checkConfig refuses the config without Russia direct too, leaving it to the start: $problem")
                null
            } else {
                Log.w(TAG, "checkConfig refused the config with Russia direct: $problem")
                problem
            }
        } catch (t: Throwable) {
            Log.w(TAG, "checkConfig not available: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        synchronized(checkVerdicts) { checkVerdicts[key] = verdict }
        return verdict
    }

    /**
     * App version and the sha256 of what the block touches: the DNS section
     * without pinned server addresses (they change with every resolution) and
     * the route. Bridge credentials, new per process, are outside both.
     */
    private fun checkKey(content: String): String {
        val root = JSONObject(content)
        val dns = JSONObject(root.optJSONObject("dns")?.toString() ?: "{}")
        dns.optJSONArray("servers")?.let { servers ->
            for (i in 0 until servers.length()) servers.optJSONObject(i)?.remove("predefined")
        }
        val material = JSONObject().put("dns", dns).put("route", root.optJSONObject("route") ?: JSONObject())
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(material.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "${BuildConfig.VERSION_CODE}:$sha256"
    }
}
