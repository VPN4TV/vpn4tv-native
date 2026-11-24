package com.vpn4tv.app.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.vpn4tv.app.Application
import com.vpn4tv.app.BuildConfig
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.bg.DefaultNetworkMonitor
import com.vpn4tv.app.bg.NetworkReport
import com.vpn4tv.app.bg.NetworkWatch
import com.vpn4tv.app.bg.OutsideVpnGuard
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.converter.HwidService
import com.vpn4tv.app.database.Settings
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * "Send to support" on a TV's Logs screen: the log as the screen has it, the
 * app's own notes (SupportNotes) and the state of the device's networks go to
 * bell.a4e.ar/crash as a report of the kind "user-report", under a short code
 * the customer gives to support. The same contract as the Apple apps'
 * ReportUploader.sendUserReport: the code in X-Report and in the body, the
 * body in "===== name =====" parts (vpn4tv.json, app.txt, log.txt), which the
 * analyzer on bell reads; keys, links' paths, UUIDs and the installation ID
 * taken out, and nothing the person typed.
 *
 * Why: a TV has no Share, and a photo of its Logs screen shows the newest
 * lines only (support case of 2026-11-21: four days of photos, and how the
 * session began was never seen).
 *
 * The report must leave a device whose tunnel carries nothing, which is the
 * case it is for: it goes past the VPN first, by the network the core goes out
 * by, then the app's usual way. When neither works it is kept and goes by
 * itself after the next stop or start of the VPN and at the next launch; the
 * code is shown either way.
 */
object SupportReport {
    private const val TAG = "SupportReport"
    private const val KIND = "user-report"

    /** Without 0/O and 1/I: read off a TV screen. The Apple apps' alphabet. */
    internal const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    internal const val CODE_LENGTH = 6
    internal const val MAX_LOG_CHARS = 400_000

    private const val DIR = "support-reports"
    private const val KEPT_FILES = 3
    private const val KEPT_MS = 7L * 24 * 3600 * 1000
    private const val ATTEMPT_MS = 20_000L

    /** [Saved]: no way to the receiver now, the report waits on the device. [Failed]: it could not be kept either. */
    enum class Outcome { Sent, Saved, Failed }

    data class Result(val code: String, val outcome: Outcome)

    internal fun newCode(random: java.util.Random): String =
        String(CharArray(CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] })

    /**
     * Blocking, up to a minute without a network: off the main thread.
     * [logLines]: the Logs screen's lines of every level, oldest first.
     */
    fun send(context: Context, logLines: List<String>): Result {
        val code = newCode(java.security.SecureRandom())
        val build: (List<String>) -> String
        val body = try {
            val about = describe(context, code, logLines.size)
            val state = state(context)
            val notes = SupportNotes.snapshot()
            val hwid = hwid(context)
            build = { more -> body(about, state + more, notes, logLines, hwid) }
            build(emptyList())
        } catch (t: Throwable) {
            Log.w(TAG, "report not built: ${t.message}")
            return Result(code, Outcome.Failed)
        }
        val why = ArrayList<String>()
        if (upload(body, code, why)) {
            // It went, but not by the first way: the next report says so.
            if (why.isNotEmpty()) SupportNotes.add("report $code went, after: ${why.joinToString("; ")}")
            return Result(code, Outcome.Sent)
        }
        // Kept with why it could not go at once: how the device's ways out failed is what support asks next.
        val line = "not sent at once: ${why.joinToString("; ")}"
        SupportNotes.add("report $code kept, $line")
        val kept = try {
            build(listOf(line))
        } catch (t: Throwable) {
            body
        }
        return Result(code, if (save(context, code, kept)) Outcome.Saved else Outcome.Failed)
    }

    // ---- what goes

    /** The whole report. [hwid]: the installation ID, taken out wherever a line has it. */
    internal fun body(context: Map<String, Any?>, state: List<String>, notes: List<String>, log: List<String>, hwid: String?): String {
        val json = JSONObject()
        context.forEach { (key, value) -> if (value != null) json.put(key, value) }
        val app = (state + "" + notes).map { scrub(it, hwid) ?: SECRET_LEFT_OUT }.joinToString("\n")
        val lines = log.mapNotNull { scrub(it, hwid) }.joinToString("\n").takeLast(MAX_LOG_CHARS)
        return part("vpn4tv.json", json.toString()) +
            part("app.txt", app) +
            part("log.txt", lines.ifEmpty { "(no log: the VPN is off or the log is empty)" })
    }

    internal const val SECRET_LEFT_OUT = "(a line with a secret left out)"

    internal fun part(name: String, text: String): String = "===== $name =====\n$text\n\n"

    private val secretLine = Regex(
        """"(password|uuid|private_key|pre_shared_key|secret|short_id|public_key|auth|token|psk|reserved|obfs-password|auth_str)"\s*:""",
        RegexOption.IGNORE_CASE,
    )
    private val keyUri = Regex(
        """\b(vless|vmess|trojan|ss|ssr|ssconf|hy2|hysteria2?|tuic|naive\+https|tt|wg|awg|olcrtc|vpn4tv)://\S+""",
        RegexOption.IGNORE_CASE,
    )
    private val linkPath = Regex("""\b(https?://[^/\s"']+)[^\s"']*""", RegexOption.IGNORE_CASE)
    private val uuid = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val ansi = Regex("\u001B\\[[0-9;]*m")

    /**
     * A line as it may leave the device: null when it carries a secret field;
     * keys, UUIDs, the paths of links (a key's link holds its token) and the
     * installation ID taken out. The sites are out already (LogPrivacy).
     */
    internal fun scrub(line: String, hwid: String?): String? {
        if (secretLine.containsMatchIn(line)) return null
        var text = ansi.replace(line, "")
        text = keyUri.replace(text) { "<${it.groupValues[1]} key>" }
        text = linkPath.replace(text) { "${it.groupValues[1]}/…" }
        text = uuid.replace(text, "<uuid>")
        if (hwid != null && hwid.length >= 6) text = text.replace(hwid, "<device id>")
        return text
    }

    private fun hwid(context: Context): String? = try {
        HwidService.getHwid(context)
    } catch (e: Exception) {
        null
    }

    /** vpn4tv.json: who sent it, from what. */
    private fun describe(context: Context, code: String, logLines: Int): Map<String, Any?> = linkedMapOf(
        "kind" to KIND,
        "client" to "android",
        "app" to BuildConfig.VERSION_NAME,
        "build" to BuildConfig.VERSION_CODE.toString(),
        "os" to "android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "core" to coreVersion(),
        "process" to "app",
        "code" to code,
        "tunnel" to (BoxService.globalStatus.value ?: Status.Stopped).name.lowercase(Locale.US),
        "logLines" to logLines,
        "tv" to context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
        "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
        // The firmware and its kernel: cases of one kind are told apart by them (a kernel before 5.7 does not let
        // an app tie a socket to an interface, see NetworkReport), and a TV's second user has networks of its own.
        "firmware" to Build.FINGERPRINT,
        "kernel" to kernel(),
        "user" to android.os.Process.myUid() / 100_000,
    )

    private fun kernel(): String? = try {
        android.system.Os.uname().release
    } catch (t: Throwable) {
        null
    }

    // A broken install has no native library: an Error, not an Exception.
    private fun coreVersion(): String? = try {
        io.nekohasekai.libbox.Libbox.version()?.value
    } catch (t: Throwable) {
        null
    }

    /** The device as it is at this moment, a line each; a line that cannot be read says so and the rest go on. */
    private fun state(context: Context): List<String> {
        val lines = ArrayList<String>()
        fun line(make: () -> String?) {
            try {
                make()?.let(lines::add)
            } catch (t: Throwable) {
                lines.add("(not read: ${t.javaClass.simpleName})")
            }
        }
        val now = System.currentTimeMillis()
        val status = BoxService.globalStatus.value ?: Status.Stopped
        line { "now: ${clock(now, TimeZone.getTimeZone("UTC"))} UTC, on the device ${clock(now, TimeZone.getDefault())} (${offset(now)})" }
        line {
            val app = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ", the app for ${span(SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())}"
            } else {
                ""
            }
            "up: the device for ${span(SystemClock.elapsedRealtime())}$app"
        }
        line {
            val alwaysOn = BoxService.alwaysOn.value?.let { if (it.lockdown) ", always-on with lockdown" else ", always-on" }.orEmpty()
            "vpn: ${status.name.lowercase(Locale.US)}, mode ${Settings.serviceMode}$alwaysOn"
        }
        line {
            BoxService.lastError.value?.let { error ->
                "last error: ${error.reason.name}" + error.detail?.let { ": ${LogPrivacy.redactLinks(it)}" }.orEmpty()
            }
        }
        line {
            // What the system gives this app itself: the VPN while it is up. "blocked": the system keeps
            // the app off the network (another app's always-on VPN with lockdown, Data Saver, a firewall).
            val connectivity = Application.connectivity
            val own = connectivity.activeNetwork?.let { NetworkReport.facts(it) }?.let(NetworkReport::describe) ?: "none"
            @Suppress("DEPRECATION")
            val state = connectivity.activeNetworkInfo?.detailedState?.name?.lowercase(Locale.US)
            val saver = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                null
            } else {
                when (connectivity.restrictBackgroundStatus) {
                    android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "off"
                    android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "on, the app exempt"
                    else -> "on"
                }
            }
            "the app's own default: $own" + state?.let { ", $it" }.orEmpty() + saver?.let { "; data saver $it" }.orEmpty()
        }
        // With the VPN on, the network the core was told; without it, the system's own default.
        // Some multi-user TVs refuse the question (DefaultNetworkListener): the lines below then say "none".
        val network = try {
            if (status == Status.Stopped) Application.connectivity.activeNetwork else DefaultNetworkMonitor.defaultNetwork
        } catch (t: Throwable) {
            null
        }
        line { NetworkReport.lineFor(network).first + if (status == Status.Stopped) " [the system's default]" else " [told to the core]" }
        line { NetworkReport.interfacesLine() }
        line { NetworkReport.routeCheck(network).first }
        line { NetworkWatch.given }
        line {
            val perApp = if (!Settings.perAppProxyEnabled) {
                "off"
            } else {
                val mode = if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) "include" else "exclude"
                "$mode(${Settings.getEffectivePerAppProxyList().size})"
            }
            "settings: fakeDns=${Settings.fakeDns}" + (if (BoxService.fakeDnsAutoDisabled) "(off for the session)" else "") +
                " perApp=$perApp allThroughVpn=${Settings.allThroughVpn} bypassLan=${Settings.bypassLan}" +
                " allowBypass=${Settings.allowBypass} autoConnect=${Settings.autoConnectOnBoot}" +
                " restartAfterKill=${Settings.restartAfterKill}" +
                (if (OutsideVpnGuard.ownerLookupBroken) " tunGuard=off(the system names no owners)" else "")
        }
        line {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@line null
            // The mode only: the name of a private DNS server can be a personal one.
            "private dns: ${android.provider.Settings.Global.getString(context.contentResolver, "private_dns_mode") ?: "not set"}"
        }
        return lines
    }

    private fun clock(at: Long, zone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }.format(Date(at))

    private fun offset(at: Long): String {
        val minutes = TimeZone.getDefault().getOffset(at) / 60_000
        return String.format(Locale.US, "UTC%+03d:%02d", minutes / 60, Math.abs(minutes % 60))
    }

    internal fun span(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes < 60) "${minutes} min" else "${minutes / 60} h ${minutes % 60} min"
    }

    // ---- how it goes

    /** [why]: gets a few words for each way that failed. */
    private fun upload(body: String, code: String, why: MutableList<String>? = null): Boolean {
        val bridges = try {
            Application.application.sessionBridges()
        } catch (e: Exception) {
            ""
        }
        fun attempt(way: String, open: (java.net.URL) -> java.net.URLConnection): Boolean {
            val reason = AtomicReference<String?>()
            if (within(ATTEMPT_MS) { CrashUpload.post(body, bridges, KIND, code, failed = { reason.set(it) }, open = open) }) return true
            why?.add("$way: ${reason.get()?.let(::withoutAddresses) ?: "no answer in ${ATTEMPT_MS / 1000} s"}")
            return false
        }
        // Past the VPN, by the network the core goes out by: a tunnel that
        // carries nothing must not hold the report that says so. The VPN's own
        // app may tie a socket to that network (as it may protect one).
        val vpnUp = BoxService.globalStatus.value != Status.Stopped
        if (vpnUp) {
            val network = DefaultNetworkMonitor.defaultNetwork
            if (network == null) {
                why?.add("past the VPN: the core was told no network")
            } else if (attempt("past the VPN") { network.openConnection(it) }) {
                return true
            }
        }
        // The app's own way out (through the VPN while it is up), with the key download's ways around a dead DNS.
        return attempt(if (vpnUp) "through the VPN" else "without the VPN") {
            HwidService.openConnectionWithDnsFallbackPublic(it.toString())
        }
    }

    private val address4 = Regex("""(?<![0-9.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9.])""")
    private val address6 = Regex("""(?<![0-9a-fA-F:])(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?:%[0-9a-zA-Z]+)?(?![0-9a-fA-F:])""")

    /**
     * An error's words without the addresses in them: "failed to connect to bell.a4e.ar/<address> (port 443) from /<address>".
     * A port after an address stays: in "<address>:443: connection refused" the ":443:" is not an IPv6 address
     * (one never ends in a single colon).
     */
    internal fun withoutAddresses(text: String): String = address6.replace(address4.replace(text, "<address>")) {
        if (it.value.endsWith(":") && !it.value.endsWith("::")) it.value else "<address>"
    }

    // A name that does not resolve can hold a connection well past its timeouts. An attempt that outlives
    // its time counts as failed; should it get through after all, the kept copy goes again under the same code.
    private fun within(ms: Long, attempt: () -> Boolean): Boolean {
        val done = AtomicBoolean(false)
        val thread = Thread {
            done.set(
                try {
                    attempt()
                } catch (t: Throwable) {
                    false
                },
            )
        }.apply { name = "support-report"; isDaemon = true; start() }
        thread.join(ms)
        return done.get()
    }

    // ---- kept until there is a way

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun save(context: Context, code: String, body: String): Boolean = try {
        val dir = dir(context).apply { mkdirs() }
        File(dir, "$code.txt").writeText(body)
        // The newest few: a person pressing the button over and over must not fill the storage.
        dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(KEPT_FILES).forEach { it.delete() }
        true
    } catch (e: Exception) {
        Log.w(TAG, "report not kept: ${e.message}")
        false
    }

    /** A few seconds from now, on a thread of its own: after a stop or a start the network takes a moment. */
    fun retryPendingLater(context: Context) {
        val app = context.applicationContext
        Thread {
            try {
                Thread.sleep(5000)
                retryPending(app)
            } catch (t: Throwable) {
                Log.w(TAG, "retry failed: ${t.message}")
            }
        }.apply { name = "support-report-retry"; isDaemon = true; start() }
    }

    /** Sends what [send] had to keep, oldest first; a report a week old is dropped. Blocking. */
    @Synchronized
    fun retryPending(context: Context) {
        val files = dir(context).listFiles { file -> file.isFile && file.name.endsWith(".txt") } ?: return
        for (file in files.sortedBy { it.lastModified() }) {
            if (System.currentTimeMillis() - file.lastModified() > KEPT_MS) {
                file.delete()
                continue
            }
            if (!upload(file.readText(), file.name.removeSuffix(".txt"))) return // no way now: the rest wait too
            file.delete()
            Log.i(TAG, "a kept report has gone")
        }
    }
}
