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

        Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_"))

        val baseDir = filesDir
        baseDir.mkdirs()
        val workingDir = getExternalFilesDir(null)
        val tempDir = cacheDir
        tempDir.mkdirs()
        workingDir?.mkdirs()

        // Migrate subscriptions from Flutter/hiddify fork on first run
        migrateLegacyProfiles()

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            if (workingDir != null) {
                setupLibbox(baseDir, workingDir, tempDir)
            }
            UpdateProfileWork.reconfigureUpdater()
        }

        registerReceiver(
            AppChangeReceiver(),
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
        )
    }

    fun reloadSetupOptions() {
        // No-op for libbox 1.13.x; setup options are set once at startup
    }

    private fun setupLibbox(baseDir: File, workingDir: File, tempDir: File) {
        Libbox.setup(createSetupOptions(baseDir, workingDir, tempDir))
    }

    /**
     * Migrate subscription URLs from Flutter/hiddify drift DB to Room DB.
     * Runs once — checks for legacy db.sqlite in app_flutter/ directory.
     */
    private fun migrateLegacyProfiles() {
        try {
            val prefs = getSharedPreferences("migration", Context.MODE_PRIVATE)
            if (prefs.getBoolean("v5_migrated", false)) return

            val legacyDbFile = File(filesDir.parentFile, "app_flutter/db.sqlite")
            if (!legacyDbFile.exists()) {
                // No legacy data — mark as migrated
                prefs.edit().putBoolean("v5_migrated", true).apply()
                return
            }

            Log.i("Migration", "Found legacy hiddify DB: ${legacyDbFile.path}")

            val legacyDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                legacyDbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )

            val cursor = legacyDb.rawQuery(
                "SELECT name, url FROM profile_entries WHERE type = 'remote' AND url IS NOT NULL AND url != ''",
                null
            )

            val profiles = mutableListOf<Pair<String, String>>() // name, url
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: "Subscription"
                val url = cursor.getString(1) ?: continue
                profiles.add(name to url)
            }
            cursor.close()
            legacyDb.close()

            if (profiles.isEmpty()) {
                Log.i("Migration", "No remote profiles to migrate")
                prefs.edit().putBoolean("v5_migrated", true).apply()
                return
            }

            Log.i("Migration", "Migrating ${profiles.size} profiles")

            // Create profiles in Room DB (on background thread in ensureDefaultProfile)
            val migratedUrls = profiles.map { it.second }
            prefs.edit()
                .putBoolean("v5_migrated", true)
                .putString("v5_migrate_urls", migratedUrls.joinToString("\n"))
                .putString("v5_migrate_names", profiles.map { it.first }.joinToString("\n"))
                .apply()

            // Clean up legacy Flutter data
            File(filesDir.parentFile, "app_flutter").deleteRecursively()
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()

            Log.i("Migration", "Legacy data cleaned, ${profiles.size} URLs saved for import")
        } catch (e: Exception) {
            Log.w("Migration", "Migration failed: ${e.message}")
            // Mark as migrated anyway to avoid retry loops
            getSharedPreferences("migration", Context.MODE_PRIVATE)
                .edit().putBoolean("v5_migrated", true).apply()
        }
    }

    private fun createSetupOptions(baseDir: File, workingDir: File, tempDir: File): SetupOptions = SetupOptions().also {
        it.basePath = baseDir.path
        it.workingPath = workingDir.path
        it.tempPath = tempDir.path
        it.fixAndroidStack = Bugs.fixAndroidStack
        it.logMaxLines = 3000
        it.debug = BuildConfig.DEBUG
    }

    companion object {
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
