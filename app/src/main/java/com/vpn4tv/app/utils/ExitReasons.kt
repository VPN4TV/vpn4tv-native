package com.vpn4tv.app.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.WorkInfo
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
 * are left out. The report holds the reasons, the process's memory and the
 * system's description, nothing of the user's.
 */
object ExitReasons {
    private const val TAG = "ExitReasons"
    private const val PREFS = "diagnostics"
    private const val KEY_SEEN = "exit_seen_at"
    private const val KEY_WORK_STOP = "key_update_stopped"
    private const val MAX_REPORTED = 5

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
            val firstRun = !prefs.contains(KEY_SEEN)
            val seen = prefs.getLong(KEY_SEEN, 0L)
            // Newest first.
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                .filter { it.timestamp > seen }
                .map {
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
            val worth = exits.filter { worthReporting(it.reason, it.importance, it.description, it.selfRestart) }
                .take(MAX_REPORTED)
            if (worth.isEmpty()) return
            CrashUpload.upload(report(worth, prefs.getString(KEY_WORK_STOP, null)), bridges, "exit")
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
     * update the system stopped, if any.
     */
    internal fun report(exits: List<Exit>, workStop: String?): String = buildString {
        val newest = exits.first()
        append("app-exit reason=").append(reasonName(newest.reason))
            .append(" importance=").append(importanceName(newest.importance)).append('\n')
        exits.forEach { append(line(it)).append('\n') }
        if (workStop != null) append("key-update-stop ").append(workStop).append('\n')
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
