package com.vpn4tv.app

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import com.vpn4tv.app.bg.AppChangeReceiver
import com.vpn4tv.app.bg.UpdateProfileWork
import com.vpn4tv.app.constant.Bugs
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.utils.AppLifecycleObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import com.vpn4tv.app.Application as BoxApplication

class Application : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()
        AppLifecycleObserver.register(this)

        // MUST run before any Room or Settings access: on first v5 boot we
        // wipe legacy Flutter state (including databases/) and that would
        // destroy a fresh Room DB created by Settings.ensureXrayPortBase().
        try {
            migrateLegacyProfiles()
        } catch (e: Throwable) {
            Log.e("Application", "migrateLegacyProfiles failed: ${e.message}", e)
        }

        // Early Settings/Room calls are wrapped individually — a
        // SQLiteCantOpenDatabaseException at this point (seen on 50016
        // with one user whose databases/ dir was in an unrecoverable
        // state) would otherwise kill the process via handleBindApplication
        // and the user would never reach the HomeScreen to retry.
        try {
            // Persist xray bridge port on first run so it stays stable across reads.
            Settings.ensureXrayPortBase()
        } catch (e: Throwable) {
            Log.e("Application", "Settings.ensureXrayPortBase failed: ${e.message}", e)
        }
        try {
            // Drop the global selectedServer key from older versions; per-profile keys take over.
            com.vpn4tv.app.ui.migrateLegacySelectedServer(this)
        } catch (e: Throwable) {
            Log.e("Application", "migrateLegacySelectedServer failed: ${e.message}", e)
        }

        // Probe DNS servers on a background thread so by the time the
        // user taps Connect, we know which DoH/UDP server works on their
        // ISP. Results feed into BoxService (config rewrite) and
        // HwidService (subscription fetch fallback).
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            com.vpn4tv.app.utils.DnsProber.probe()
        }

        // All storage init runs off main thread. mkdirs() + getExternalFilesDir()
        // can block on slow / broken storage; ANR reports on MY_PACKAGE_REPLACED
        // and SystemJobService broadcasts trace back to a slow Application.onCreate.
        // Sony BRAVIA VH2 skips external entirely (that device's external path
        // hangs File.mkdirs indefinitely — prior vc50303 boot-loop cluster).
        //
        // IMPORTANT: the FIRST Libbox.* call in the process triggers
        // go.Seq.<clinit> -> System.loadLibrary -> 70+ MB .so page-in plus
        // full Go runtime boot. On cheap TV flash that takes 10-20s; doing it
        // on the main thread was the root of the Application.onCreate /
        // BOOT_COMPLETED / setLocale ANR clusters on vc50326 (~40 reports/wk).
        // setLocale therefore runs HERE, first in this coroutine, not in
        // onCreate. Keep every first-touch of Libbox off the main thread.
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            // libbox 1.14+ expects BCP-47 tags ("ru-RU"), not POSIX ("ru_RU").
            // Catch Throwable (not just Exception) — on Play-split misdelivery
            // (Xiaomi TV Stick 4K, vc50317) the library load throws
            // UnsatisfiedLinkError which extends Error. App stays degraded.
            try {
                Libbox.setLocale(Locale.getDefault().toLanguageTag())
            } catch (e: Throwable) {
                Log.e("Application", "Libbox.setLocale failed (libgojni load?): ${e.javaClass.simpleName}: ${e.message}", e)
                try { Libbox.setLocale("en") } catch (_: Throwable) {}
            }
            val baseDir = filesDir.apply { mkdirs() }
            // External storage path is unreliable on multi-user Android TVs:
            // app installed under user 0 but process runs as user 10+, so
            // /storage/emulated/0/Android/data/<pkg>/... returns EACCES.
            // BRAVIA VH2 was the first known case; later we saw Haier
            // MatrixTV, Skyworth, Xiaomi MiBox S all hit it (vc50317-50318
            // CreateService cache.db permission denied cluster). Test write
            // access at startup; on failure fall back to internal filesDir.
            // Self-healing flag: BoxService sets it when sing-box CreateService
            // throws cache-file permission denied. On next launch we skip
            // external entirely. Spares us mass-migrating healthy users while
            // still recovering devices whose write_test passed but cache.db
            // open later EACCES'd (Haier multi-user firmware quirk).
            val storagePrefs = getSharedPreferences("storage", Context.MODE_PRIVATE)
            val forceInternal = storagePrefs.getBoolean("use_internal", false)
            val workingDir = if (Build.DEVICE == "BRAVIA_VH2" || forceInternal) {
                if (forceInternal) Log.i("Application", "Using internal storage (use_internal flag)")
                baseDir
            } else {
                val external = getExternalFilesDir(null)
                val writable = external?.let { dir ->
                    try {
                        dir.mkdirs()
                        val test = File(dir, ".write_test_${System.currentTimeMillis()}")
                        test.createNewFile() && test.delete()
                    } catch (e: Exception) {
                        Log.w("Application", "External storage not writable, using internal: ${e.message}")
                        false
                    }
                } ?: false
                if (writable) external!! else baseDir
            }
            val tempDir = cacheDir.apply { mkdirs() }
            workingDir.mkdirs()
            consumeCrashLog(tempDir)
            setupLibbox(baseDir, workingDir, tempDir)
            UpdateProfileWork.reconfigureUpdater()
        }

        // Off main thread: registerReceiver is a synchronous binder call into
        // system_server; at boot/wake AMS can be overloaded for seconds and
        // this transact showed up as the blocking frame in vc50326 onCreate
        // ANRs (cluster #23). Registration from a background thread is legal —
        // the 2-arg overload still dispatches onReceive on the main looper.
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            registerReceiver(
                AppChangeReceiver(),
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                },
            )
        }
    }

    fun reloadSetupOptions() {
        // No-op for libbox 1.13.x; setup options are set once at startup
    }

    /**
     * Diagnostic one-shot: if a previous process aborted inside the Go runtime,
     * debug.SetCrashOutput wrote a goroutine dump to go_crash.log (confirmed
     * the only writer — stderr is redirected to a separate CrashReport file).
     * Read it, log it with the bridge subset that was active, and move it aside
     * so the next launch boots clean.
     *
     * LOG-ONLY — we no longer rethrow it as an uncaught exception. The rethrow
     * (a) crashed the app on the launch AFTER any Go crash (the throw lived in
     * a GlobalScope coroutine with no handler → process death), inflicting a
     * second failure on the user; (b) doubled the crash-rate metric; and Play
     * stripped the synthetic stack frames anyway, so it yielded no readable
     * data. The full dump goes to logcat now; a server upload to bell.a4e.ar
     * (planned) will give untruncated off-device dumps + bridge correlation.
     */
    private fun consumeCrashLog(tempDir: File) {
        val crashFile = File(tempDir, "go_crash.log")
        if (!crashFile.exists() || crashFile.length() == 0L) return
        val content = try {
            crashFile.readText()
        } catch (e: Exception) {
            Log.e("Application", "consumeCrashLog read failed", e)
            return
        }
        // One-shot: move aside so the next launch doesn't re-read it.
        runCatching { crashFile.renameTo(File(tempDir, "go_crash_consumed.log")) }

        // Bridge subset that was active when sing-box ran (BoxService writes it),
        // plus whether the FakeDNS health check had auto-disabled — context for
        // the upcoming server upload and useful in logcat now.
        val sessionPrefs = getSharedPreferences("session", Context.MODE_PRIVATE)
        val bridges = buildString {
            append("xray=").append(if (sessionPrefs.getBoolean("xray", false)) "Y" else "N")
            append(" outline=").append(if (sessionPrefs.getBoolean("outline", false)) "Y" else "N")
            append(" wireproxy=").append(if (sessionPrefs.getBoolean("wireproxy", false)) "Y" else "N")
            append(" fakeDnsAutoDisabled=").append(sessionPrefs.getBoolean("fakeDns_auto_disabled", false))
        }
        Log.e("Application", "Previous Go crash dump (${content.length} bytes, $bridges):\n$content")
        // TODO: POST content + bridges + version/device to bell.a4e.ar for
        // untruncated off-device analysis, then this logcat dump can go.
    }

    private fun setupLibbox(baseDir: File, workingDir: File, tempDir: File) {
        // Play Vitals strips Throwable.getMessage() from error reports but
        // preserves stack frames verbatim. Inject the Go error text as a
        // synthetic StackTraceElement so it shows up at the top of the
        // report — "at <message>(Err.go)" — the only way to get diagnostic
        // detail past Vitals' PII filter (vc50312/50313 proxyerror cluster
        // otherwise arrives bare, no message, no cause).
        try {
            Libbox.setup(createSetupOptions(baseDir, workingDir, tempDir))
        } catch (t: Throwable) {
            // Native lib never loaded (Play split ABI misdelivery → go.Seq
            // class erroneous → NoClassDefFoundError on every later touch):
            // the failure is permanent for the install, so crash-looping every
            // launch adds no information. Rethrow ONCE per install+version so
            // Vitals still shows the failure (distinct-user counts stay exact,
            // a bad AAR release still spikes), then degrade on later launches.
            // vc50326: 12 reports / 3 users = exactly this loop.
            val nativeLoadFailure = generateSequence(t as Throwable?) { it.cause }
                .take(10)
                .any { it is UnsatisfiedLinkError || it is NoClassDefFoundError }
            if (nativeLoadFailure) {
                val prefs = getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
                val key = "native_load_failure_reported_${BuildConfig.VERSION_CODE}"
                if (prefs.getBoolean(key, false)) {
                    Log.e("Application", "Libbox unavailable (native lib load failed), skipping setup", t)
                    return
                }
                // commit(), not apply(): we are on Dispatchers.IO and the throw
                // below kills the process before an async write would land.
                prefs.edit().putBoolean(key, true).commit()
            }
            val rawMsg = t.message ?: t.javaClass.simpleName
            Log.e("Application", "Libbox.setup failed: $rawMsg", t)
            val safe = rawMsg.take(180).replace(Regex("[^A-Za-z0-9 ._:/=+-]"), "_")
            val wrapped = RuntimeException("Libbox.setup: $safe", t)
            wrapped.stackTrace = arrayOf(
                StackTraceElement("LibboxSetupMsg", safe, "Err.go", 1),
            ) + wrapped.stackTrace
            throw wrapped
        }
    }

    /**
     * Migrate subscription URLs from any Flutter-era hiddify install to the
     * native Room DB, then wipe every legacy state blob that lingers on disk.
     *
     * Why the broad sweep: users reported 2.3 → 5.0 upgrades booting into a
     * broken state where the app launched but nothing worked until they
     * cleared cache / reinstalled. The old code only touched
     * `app_flutter/db.sqlite` with the `profile_entries` table — that schema
     * is from late 4.x. Older Flutter builds shipped different layouts, and
     * other Flutter state (FlutterSharedPreferences, stale sing-box caches
     * under files/, abandoned databases/ entries) was never removed, so the
     * native code inherited conflicting state.
     *
     * On first v5 run we look for *any* legacy marker and, if present:
     *   1. harvest subscription URLs from every known legacy schema we can
     *      probe — tolerating missing tables / columns;
     *   2. wipe cacheDir, `app_flutter/`, Flutter shared_prefs, any legacy
     *      Room DBs under databases/, and stray files in filesDir.
     * This runs once, guarded by the `v5_migrated` flag.
     */
    private fun migrateLegacyProfiles() {
        val prefs = getSharedPreferences("migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("v5_migrated", false)) return
        try {
            val dataRoot = filesDir.parentFile ?: return

            // Any of these signals the user had a Flutter-era hiddify install
            // in this package, even if the DB itself is unreadable.
            val legacySignals = listOf(
                File(dataRoot, "app_flutter"),
                File(dataRoot, "shared_prefs/FlutterSharedPreferences.xml"),
            )
            val isUpgrade = legacySignals.any { it.exists() }

            // hiddify's drift DB has lived at a few names over its history.
            // From app_database era → db_v1 era it was always `db.sqlite`;
            // during the db_v2 coexistence era there was also a `db_v2.sqlite`
            // next to it; after commit fd59c8db (Oct 2025) v2 was renamed
            // back to `db.sqlite`. Every schema version (v1–v5) uses the
            // same `profile_entries` table with `name` and `url` columns —
            // `url` is NOT NULL in v1 and nullable in v2+, so the same query
            // works everywhere.
            val candidateDbPaths = listOf(
                "app_flutter/db.sqlite",
                "app_flutter/db_v2.sqlite",
            )
            val candidateTables = listOf("profile_entries")
            val harvested = linkedMapOf<String, String>() // url -> name (dedup by url)
            for (rel in candidateDbPaths) {
                val f = File(dataRoot, rel)
                if (!f.exists()) continue
                try {
                    val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                        f.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                    )
                    for (table in candidateTables) {
                        try {
                            db.rawQuery(
                                "SELECT name, url FROM $table WHERE url IS NOT NULL AND url != ''",
                                null,
                            ).use { c ->
                                while (c.moveToNext()) {
                                    val name = c.getString(0) ?: "Subscription"
                                    val url = c.getString(1) ?: continue
                                    if (url.startsWith("http")) harvested.putIfAbsent(url, name)
                                }
                            }
                        } catch (_: Exception) { /* table absent or shape differs */ }
                    }
                    db.close()
                } catch (e: Exception) {
                    Log.w("Migration", "unreadable legacy db ${f.path}: ${e.message}")
                }
            }

            if (isUpgrade) {
                Log.i("Migration", "legacy install detected, harvested ${harvested.size} URLs, wiping state")
                runCatching { File(dataRoot, "app_flutter").deleteRecursively() }
                // Do NOT delete the `databases/` directory itself — only its
                // files. One user on 50016 hit SQLiteCantOpenDatabaseException
                // from Application.onCreate because `deleteRecursively()` had
                // removed the parent dir and the subsequent Room open
                // couldn't recreate it on their device (likely a vendor FS
                // quirk — the usual mkdirs() path inside SQLiteOpenHelper is
                // a noop if the parent is already present and can fail
                // silently when it isn't). Delete only Flutter-era files by
                // name; 5.x's own profiles.db/settings.db do not exist yet
                // on the first boot after upgrade so nothing worth keeping is
                // lost, but the directory stays.
                val dbDir = File(dataRoot, "databases")
                runCatching {
                    dbDir.listFiles()?.forEach { it.delete() }
                }
                // Defence in depth: if something else deleted the dir out from
                // under us, recreate it so Settings.ensureXrayPortBase below
                // doesn't land on a missing parent.
                runCatching { dbDir.mkdirs() }

                runCatching { cacheDir.deleteRecursively(); cacheDir.mkdirs() }
                runCatching {
                    File(dataRoot, "shared_prefs").listFiles()?.forEach { f ->
                        if (f.name.startsWith("FlutterSharedPreferences") ||
                            f.name.contains("hiddify", ignoreCase = true)
                        ) f.delete()
                    }
                }
                // No native-app state exists yet on the first v5 boot, so it's
                // safe to nuke anything that happens to be in filesDir — those
                // are leftovers from Flutter sing-box-core directories.
                runCatching {
                    filesDir.listFiles()?.forEach { it.deleteRecursively() }
                }
            }

            val editor = prefs.edit().putBoolean("v5_migrated", true)
            if (harvested.isNotEmpty()) {
                editor.putString("v5_migrate_urls", harvested.keys.joinToString("\n"))
                editor.putString("v5_migrate_names", harvested.values.joinToString("\n"))
            }
            editor.apply()
        } catch (e: Exception) {
            Log.w("Migration", "migration failed: ${e.message}")
            prefs.edit().putBoolean("v5_migrated", true).apply()
        }
    }

    private fun createSetupOptions(baseDir: File, workingDir: File, tempDir: File): SetupOptions = SetupOptions().also {
        it.basePath = baseDir.path
        it.workingPath = workingDir.path
        it.tempPath = tempDir.path
        it.fixAndroidStack = Bugs.fixAndroidStack
        it.logMaxLines = 3000
        it.debug = BuildConfig.DEBUG
        // Diagnostic: Go runtime writes goroutine dump here on abort
        // (debug.SetCrashOutput). Kotlin logs it on next launch — see
        // consumeCrashLog().
        it.crashLogPath = File(tempDir, "go_crash.log").path
    }

    companion object {
        init {
            // SIGSYS seccomp workaround must be installed BEFORE libbox.so
            // loads. On Android < 12 seccomp blocks certain syscalls (arm32
            // syscall 422 = clock_gettime64, 434 = pidfd_open, etc.) that
            // Go 1.23+ runtime uses; the handler writes -ENOSYS into the
            // return register so Go's wrapper falls back. See golang/go#70508.
            try {
                System.loadLibrary("sigsys_handler")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("Application", "failed to load sigsys_handler", e)
            }
        }

        lateinit var application: BoxApplication
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
    }
}
