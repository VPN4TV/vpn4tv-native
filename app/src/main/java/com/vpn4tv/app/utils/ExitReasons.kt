package com.vpn4tv.app.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Why the app's process ended before (ApplicationExitInfo, Android 11+), and
 * why the system stopped a background key update (WorkInfo stop reason).
 *
 * Android 17 limits an app's memory by the device's RAM and ends a process
 * over it with REASON_OTHER and "MemoryLimiter:AnonSwap" in the description;
 * Android 16 puts jobs that run beside a foreground service (the VPN) under
 * the job quota. Neither shows in a crash report. At every start the exits
 * since the last start are written to logcat, and those worth a look go to
 * bell.a4e.ar/crash in one report (kind "exit"): the system ended the process
 * while it was on screen or ran the VPN (out of memory, the memory limiter,
 * too much CPU, frozen…), an ANR, or the memory limiter at any time. Crashes
 * have their own reports; exits the user, an update or the app itself caused
 * are left out. The report holds the reasons, the process's memory, the
 * system's description and the device's memory and ABI, and for an ANR the
 * thread stacks Android kept ([anrStacks]: class and method names); nothing
 * of the user's.
 * An end of the VPN by Android is kept for the screen ([vpnEnded]): it offers
 * the way back (ui/VpnEndedOffer).
 */
object ExitReasons {
    private const val TAG = "ExitReasons"
    private const val PREFS = "diagnostics"
    private const val KEY_SEEN = "exit_seen_at"
    private const val KEY_WORK_STOP = "key_update_stopped"
    private const val MAX_REPORTED = 5
    private const val KEY_VPN_ENDED = "vpn_ended_at"
    private const val KEY_VPN_ENDED_BY_GUARD = "vpn_ended_by_tcl_guard"
    private const val KEY_GUARD_NOTICE = "tcl_guard_notice"
    private const val KEY_GUARD_NOTICE_AT = "tcl_guard_notice_at"
    internal const val GUARD_NOTICE_EVERY_MS = 7L * 24 * 60 * 60 * 1000

    private val vpnEndedFlow = MutableStateFlow(false)

    /** What [takeVpnEnded] hands the screen. */
    class VpnEnd(
        /** TCL's own guard ended the process ([byTclGuard]): ui/VpnEndedOffer explains its setting. */
        val byTclGuard: Boolean,
    )

    /**
     * Android ended the app while the VPN ran since an earlier start, and the screen has not offered the way back
     * yet (ui/VpnEndedOffer takes it with [takeVpnEnded]).
     */
    val vpnEnded: StateFlow<Boolean> = vpnEndedFlow

    /** One exit as the report and logcat show it; [reason], [importance] as ApplicationExitInfo has them. */
    internal data class Exit(
        val timestamp: Long,
        val reason: Int,
        val status: Int,
        val importance: Int,
        val pssKb: Long,
        val rssKb: Long,
        val description: String?,
        /** The app ended itself on purpose ([markSelfRestart]). */
        val selfRestart: Boolean = false,
    )

    private val SELF_RESTART = "self-restart".toByteArray()

    /**
     * Before the app kills its own process (the connect watchdog, the storage
     * self-heal): Android records that as SIGNALED, like a kill by the system,
     * so the next start would report it. The note travels with the exit.
     */
    fun markSelfRestart(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { context.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(SELF_RESTART) }
    }

    /** Application.onCreate, off the main thread. Never throws. */
    fun record(context: Context, bridges: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Found by an earlier process that never showed the screen.
            if (prefs.contains(KEY_VPN_ENDED)) vpnEndedFlow.value = true
            val firstRun = !prefs.contains(KEY_SEEN)
            val seen = prefs.getLong(KEY_SEEN, 0L)
            // Newest first.
            val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 16).filter { it.timestamp > seen }
            val exits = infos.map {
                Exit(
                    it.timestamp, it.reason, it.status, it.importance, it.pss, it.rss, it.description,
                    selfRestart = it.processStateSummary?.contentEquals(SELF_RESTART) == true,
                )
            }
            if (exits.isEmpty()) {
                // Nothing yet: what comes after this start is new.
                if (firstRun) prefs.edit().putLong(KEY_SEEN, System.currentTimeMillis()).apply()
                return
            }
            prefs.edit().putLong(KEY_SEEN, exits.maxOf { it.timestamp }).apply()
            exits.forEach { Log.i(TAG, line(it)) }
            // The first start with this code finds the history of older
            // versions: logged, not sent.
            if (firstRun) return
            val ended = exits.filter { endedVpn(it.reason, it.importance, it.description, it.selfRestart) }
            if (ended.isNotEmpty()) {
                val edit = prefs.edit().putLong(KEY_VPN_ENDED, exits.first().timestamp)
                ended.firstOrNull { byTclGuard(it.description) }?.let { guard ->
                    edit.putBoolean(KEY_VPN_ENDED_BY_GUARD, true)
                    val now = System.currentTimeMillis()
                    if (guardNoticeDue(prefs.getLong(KEY_GUARD_NOTICE_AT, 0L), now)) {
                        edit.putString(KEY_GUARD_NOTICE, guardNoticeValue(guard.timestamp))
                            .putLong(KEY_GUARD_NOTICE_AT, now)
                    }
                }
                edit.apply()
                vpnEndedFlow.value = true
            }
            val worth = exits.filter { worthReporting(it.reason, it.importance, it.description, it.selfRestart) }
                .take(MAX_REPORTED)
            if (worth.isEmpty()) return
            CrashUpload.upload(
                report(worth, prefs.getString(KEY_WORK_STOP, null), device(am), anrTrace(infos, worth)), bridges, "exit",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "exit reasons not read: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * A key update the system stopped before it ended ([stopReason] from
     * ListenableWorker.getStopReason): logged, and kept for the next exit report.
     */
    fun recordWorkStop(context: Context, stopReason: Int) {
        val text = "reason=${workStopName(stopReason)} time=${iso(System.currentTimeMillis())}"
        Log.w(TAG, "key update stopped by the system: $text")
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WORK_STOP, text).apply()
        }
    }

    /** Whether Android ended the VPN since the screen last asked ([vpnEnded]), and how; clears it. */
    fun takeVpnEnded(context: Context): VpnEnd? {
        val wasSet = vpnEndedFlow.value
        vpnEndedFlow.value = false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.contains(KEY_VPN_ENDED)
        val byGuard = prefs.getBoolean(KEY_VPN_ENDED_BY_GUARD, false)
        if (saved || byGuard) prefs.edit().remove(KEY_VPN_ENDED).remove(KEY_VPN_ENDED_BY_GUARD).apply()
        return if (wasSet || saved) VpnEnd(byGuard) else null
    }

    /**
     * TCL's own guard, not Android, ended the process: "tguard-kill" in the exit's description (TCL Smart TV Pro,
     * Android 11-12, crash group fafb5b4664; the VPN stayed off). The way out is a setting in TCL's security app
     * (support's answer: VPN4TV set to start automatically there); by another analysis (PiPup, TCL Google TV) the
     * guard also keeps a killed service from starting again without it.
     */
    internal fun byTclGuard(description: String?): Boolean = description?.contains("tguard", ignoreCase = true) == true

    /**
     * Owner 2026-11-18: a customer of our subscription also gets the way out from @VPN4TV_Bot. The next request to
     * our key server (converter/HwidService, never another provider's) carries this note until the server answered,
     * at most once a week ([GUARD_NOTICE_EVERY_MS]).
     */
    fun pendingGuardNotice(context: Context): String? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GUARD_NOTICE, null)
    }.getOrNull()

    /** Our key server answered a request that carried [notice]: it is not sent again. */
    fun guardNoticeDelivered(context: Context, notice: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_GUARD_NOTICE, null) == notice) prefs.edit().remove(KEY_GUARD_NOTICE).apply()
        }
    }

    /** A new note at most once a week; a clock set back counts as due. */
    internal fun guardNoticeDue(lastAt: Long, now: Long): Boolean =
        lastAt <= 0L || now - lastAt !in 0 until GUARD_NOTICE_EVERY_MS

    /**
     * The note: always "tguard-kill" whatever the firmware wrote ([byTclGuard] matched it; the server takes this
     * word exactly) and the kill's time, UTC.
     */
    internal fun guardNoticeValue(at: Long): String = "by=tguard-kill; at=${iso(at)}"

    /**
     * Android, not the user, an update, a crash or the app itself, ended the process while only the VPN kept it
     * (a foreground service, no screen): to free memory (the low-memory killer, the memory limiter) or with a kill
     * (a firmware task killer). The VPN then stays off until something starts it again: the app's restart after a
     * kill (StickyRestart) or the user. Android's always-on VPN does not (ui/VpnEndedOffer).
     */
    internal fun endedVpn(reason: Int, importance: Int, description: String?, selfRestart: Boolean = false): Boolean {
        if (selfRestart) return false
        if (importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) return false
        if (description?.contains("MemoryLimiter") == true) return true
        return when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, REASON_FREEZER -> true
            else -> false
        }
    }

    /**
     * The device for the report: its memory now (all, free, Android's low-memory line) and the app process's ABI;
     * the exit record has the process's memory only. Null when unknown.
     */
    private fun device(am: ActivityManager): String? = runCatching {
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val abi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && android.os.Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
        } else {
            Build.SUPPORTED_32_BIT_ABIS.firstOrNull()
        }
        deviceLine(info.totalMem, info.availMem, info.threshold, info.lowMemory, abi)
    }.getOrNull()

    internal fun deviceLine(total: Long, avail: Long, threshold: Long, low: Boolean, abi: String?): String =
        "device ram=${total / MB}MB avail=${avail / MB}MB threshold=${threshold / MB}MB low=$low abi=${abi ?: "?"}"

    private const val MB = 1024L * 1024L

    /**
     * Ended by the system while it mattered, or by the memory limiter. Crashes
     * report themselves; the app's own restarts ([selfRestart]) have their
     * report already (the watchdog's hang dumps).
     */
    internal fun worthReporting(reason: Int, importance: Int, description: String?, selfRestart: Boolean = false): Boolean {
        if (selfRestart) return false
        if (description?.contains("MemoryLimiter") == true) return true
        return when (reason) {
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> false
            // The app itself, the user, an update.
            ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED, ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            REASON_PACKAGE_STATE_CHANGE, REASON_PACKAGE_UPDATED -> false
            ApplicationExitInfo.REASON_ANR -> true
            // On screen or with a foreground service (the VPN) running.
            else -> importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        }
    }

    /**
     * The report: the newest exit's reason and importance first (the analyzer
     * groups by the first line), then one line per exit, then the last key
     * update the system stopped, if any, the device, and the thread stacks of
     * the newest ANR ([anrTrace]).
     */
    internal fun report(
        exits: List<Exit>,
        workStop: String?,
        device: String? = null,
        anrTrace: String? = null,
    ): String = buildString {
        val newest = exits.first()
        append("app-exit reason=").append(reasonName(newest.reason))
            .append(" importance=").append(importanceName(newest.importance)).append('\n')
        exits.forEach { append(line(it)).append('\n') }
        if (workStop != null) append("key-update-stop ").append(workStop).append('\n')
        if (device != null) append(device).append('\n')
        if (anrTrace != null) append(anrTrace).append('\n')
    }

    /**
     * The thread dump Android took at the newest reported ANR (ApplicationExitInfo.getTraceInputStream). An ANR's
     * exit record says only that there was one ("bg anr" on Android 11 TVs, crash group 4d0cfa9a95), not where the
     * main thread stood. The report's last section: "anr-trace time=…" and [anrStacks] of the dump; "none" when
     * Android kept no dump, "failed" when it could not be read.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun anrTrace(infos: List<ApplicationExitInfo>, reported: List<Exit>): String? {
        val anr = reported.firstOrNull { it.reason == ApplicationExitInfo.REASON_ANR } ?: return null
        val info = infos.firstOrNull { it.reason == anr.reason && it.timestamp == anr.timestamp } ?: return null
        val head = "anr-trace time=${iso(anr.timestamp)}"
        return try {
            val stream = info.traceInputStream ?: return "$head none"
            head + "\n" + stream.use { anrStacks(it.bufferedReader().lineSequence().take(ANR_DUMP_LINES)) }
        } catch (t: Throwable) {
            "$head failed: ${t.javaClass.simpleName}"
        }
    }

    /** No more of a dump is read: the app's own is under a thousand lines. */
    private const val ANR_DUMP_LINES = 20_000

    /** Lines kept of the main thread and of each thread that holds a lock it waits for. */
    private const val ANR_STACK_LINES = 60

    /** Java frames kept of every other thread. */
    private const val ANR_OTHER_LINES = 12
    private const val ANR_LINE_CHARS = 240

    /** Other threads are added up to this size. */
    internal const val ANR_TRACE_CHARS = 24_000

    private val ANR_TID = Regex("""\btid=(\d+)""")
    private val ANR_HELD_BY = Regex("""held by thread (\d+)""")

    /** "sysTid=26789     futex_wait": where a thread waits in the kernel, listed when the runtime gave no stacks. */
    private val ANR_WAIT_CHANNEL = Regex("""sysTid=\d+\s+(\w+)""")
    private val ANR_PLAIN_NAME = Regex("""[A-Za-z0-9 _#-]+""")

    /** The runtime's own threads, the same in every dump. */
    private val ANR_RUNTIME_THREADS =
        setOf("Signal Catcher", "ReferenceQueueDaemon", "FinalizerDaemon", "FinalizerWatchdogDaemon", "HeapTaskDaemon")

    /** One thread of the dump: "\"main\" prio=5 tid=1 Blocked", then its frames. */
    private class AnrThread(header: String) {
        val name: String = header.substring(1).substringBefore('"')

        /** After the name: "daemon prio=5 tid=18 TimedWaiting". */
        val rest: String = header.substring(1).substringAfter('"', "").trim().take(ANR_LINE_CHARS)
        val tid: String? = ANR_TID.find(rest)?.groupValues?.get(1)
        val lines = ArrayList<String>()
    }

    /**
     * What the report keeps of an ANR's thread dump (ART's text, as Android stores it for the exit record): the main
     * thread; the thread that holds the lock it waits for, and so on down that chain; then every other thread that
     * has Java frames, those frames only, the same stack once with a count. Left out: each thread's attribute lines,
     * the threads with native frames only (the core's own, binder) and the runtime's own. A thread's name that could
     * hold a host name (a dot, a slash, a colon: "OkHttp example.com") is replaced by "thread".
     *
     * Android 14 starts the dump with the ANR's subject (it is in the exit's description too) and its log of recent
     * events, which names other apps: none of that is kept. When the runtime did not answer in time, the dump has no
     * stacks, only where each thread waits in the kernel (seen on the emulator with the main thread stopped under
     * ptrace): then those places are counted, the main thread's first.
     */
    internal fun anrStacks(dump: Sequence<String>): String {
        val threads = ArrayList<AnrThread>()
        val waits = ArrayList<String>()
        var current: AnrThread? = null
        var count = 0
        for (raw in dump) {
            count++
            val line = raw.trim()
            if (line.isEmpty()) {
                current = null
                continue
            }
            if (line.startsWith("sysTid=")) {
                ANR_WAIT_CHANNEL.matchEntire(line)?.let { waits.add(it.groupValues[1].take(40)) }
                continue
            }
            if (raw.startsWith("\"")) {
                current = AnrThread(line).also { threads.add(it) }
                continue
            }
            val thread = current ?: continue
            // "| group=… sysTid=… state=…": the thread's attributes.
            if (line.startsWith("|") || thread.lines.size >= ANR_STACK_LINES) continue
            thread.lines.add(line.take(ANR_LINE_CHARS))
        }
        val main = threads.firstOrNull { it.name == "main" }
            ?: return "(no main thread in the dump: $count lines, ${threads.size} threads)" + waitChannels(waits)
        val chain = arrayListOf(main)
        while (chain.size < 4) {
            val holder = chain.last().lines.firstNotNullOfOrNull { ANR_HELD_BY.find(it)?.groupValues?.get(1) }
                ?.let { tid -> threads.firstOrNull { it.tid == tid } }
            if (holder == null || holder in chain) break
            chain.add(holder)
        }
        // The same Java stack (idle workers of one pool) once.
        val others = LinkedHashMap<List<String>, MutableList<AnrThread>>()
        for (thread in threads) {
            if (thread in chain || thread.name in ANR_RUNTIME_THREADS) continue
            val java = thread.lines.filter { it.startsWith("at ") || it.startsWith("- ") }.take(ANR_OTHER_LINES)
            if (java.none { it.startsWith("at ") }) continue
            others.getOrPut(java) { ArrayList() }.add(thread)
        }
        val text = StringBuilder()
        fun add(thread: AnrThread, lines: List<String>, alike: Int) {
            text.append('"').append(if (ANR_PLAIN_NAME.matches(thread.name)) thread.name.take(40) else "thread")
                .append("\" ").append(thread.rest)
            if (alike > 0) text.append(" (+").append(alike).append(" with the same stack)")
            text.append('\n')
            lines.forEach { text.append("  ").append(it).append('\n') }
        }
        chain.forEach { add(it, it.lines, 0) }
        var leftOut = 0
        for ((lines, alike) in others) {
            if (text.length >= ANR_TRACE_CHARS) leftOut += alike.size else add(alike.first(), lines, alike.size - 1)
        }
        if (leftOut > 0) text.append('(').append(leftOut).append(" more threads left out)\n")
        return text.toString().trimEnd()
    }

    /** The first thread listed is the main one (its id is the process's); the rest by how many wait in each place. */
    private fun waitChannels(waits: List<String>): String {
        if (waits.isEmpty()) return ""
        val others = waits.drop(1).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
        return "\nkernel wait channels: main=${waits.first()}" + others.joinToString("") { ", ${it.key} x${it.value}" }
    }

    internal fun line(exit: Exit): String = buildString {
        append("exit time=").append(iso(exit.timestamp))
        append(" reason=").append(reasonName(exit.reason))
        append(" status=").append(exit.status)
        append(" importance=").append(importanceName(exit.importance))
        append(" pss=").append(exit.pssKb).append("kB")
        append(" rss=").append(exit.rssKb).append("kB")
        exit.description?.takeIf { it.isNotBlank() }?.let {
            append(" description=").append(it.replace('\n', ' ').take(200))
        }
    }

    // ApplicationExitInfo constants newer than the compile-time platform of
    // some builds; the values are fixed.
    private const val REASON_FREEZER = 14
    private const val REASON_PACKAGE_STATE_CHANGE = 15
    private const val REASON_PACKAGE_UPDATED = 16

    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        REASON_FREEZER -> "FREEZER"
        REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    internal fun importanceName(importance: Int): String = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground-service"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "cached"
        else -> "gone"
    } + "($importance)"

    internal fun workStopName(reason: Int): String = when (reason) {
        WorkInfo.STOP_REASON_NOT_STOPPED -> "NOT_STOPPED"
        WorkInfo.STOP_REASON_UNKNOWN -> "UNKNOWN"
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "CANCELLED_BY_APP"
        WorkInfo.STOP_REASON_PREEMPT -> "PREEMPT"
        WorkInfo.STOP_REASON_TIMEOUT -> "TIMEOUT"
        WorkInfo.STOP_REASON_DEVICE_STATE -> "DEVICE_STATE"
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "CONSTRAINT_BATTERY_NOT_LOW"
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "CONSTRAINT_CHARGING"
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "CONSTRAINT_CONNECTIVITY"
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "CONSTRAINT_DEVICE_IDLE"
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "CONSTRAINT_STORAGE_NOT_LOW"
        WorkInfo.STOP_REASON_QUOTA -> "QUOTA"
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "BACKGROUND_RESTRICTION"
        WorkInfo.STOP_REASON_APP_STANDBY -> "APP_STANDBY"
        WorkInfo.STOP_REASON_USER -> "USER"
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "SYSTEM_PROCESSING"
        WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "ESTIMATED_APP_LAUNCH_TIME_CHANGED"
        else -> "STOP_REASON_$reason"
    }

    internal fun iso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
}
