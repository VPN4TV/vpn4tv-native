package com.vpn4tv.app.bg

import android.content.Context
import android.util.Log
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings
import java.io.File

/**
 * The first start after Android restored the app's data from a backup or
 * from the old phone ([RestoreAgent] left a marker). The keys and settings
 * came back; this undoes what was true only of the old device. The new one
 * has its own installation ID already (hwid.xml is not backed up).
 */
object RestoredData {
    private const val TAG = "RestoredData"
    const val MARKER = "restored-from-backup"

    private fun marker(context: Context) = File(context.noBackupFilesDir, MARKER)

    /**
     * Application.onCreate, on the main thread, before anything reads these
     * settings (BootReceiver's auto-connect does at boot). True after a restore.
     */
    fun resetDeviceSettings(context: Context): Boolean {
        if (!marker(context).exists()) return false
        // Not connected here: no VPN permission yet, no auto-connect at boot.
        Settings.startedByUser = false
        // The notification permission and the offers of the Quick Settings
        // tile and the widget are this device's: ask and offer again.
        Settings.notificationPromptShown = false
        Settings.notificationPermissionRequested = false
        Settings.tileOffered = false
        Settings.tileAdded = false
        Settings.widgetOffered = false
        Log.i(TAG, "data restored from a backup: the old device's settings reset")
        return true
    }

    /**
     * Off the main thread, before the saved configs are read (SavedConfigUpgrade):
     * a profile keeps the absolute path of its config, and another user or
     * profile of the device has another files folder. The restored file is in
     * this one, under the same name. Then the marker goes.
     */
    suspend fun repointProfiles(context: Context) {
        val marker = marker(context)
        if (!marker.exists()) return
        try {
            val dir = File(context.filesDir, "profiles")
            val moved = ProfileManager.list().filter { profile ->
                val path = profile.typed.path
                if (path.isEmpty() || File(path).exists()) return@filter false
                val here = File(dir, File(path).name)
                if (!here.exists()) return@filter false
                profile.typed.path = here.absolutePath
                true
            }
            if (moved.isNotEmpty()) {
                ProfileManager.update(moved)
                Log.i(TAG, "${moved.size} restored profile(s) pointed at this device's files")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restored profiles not checked: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            marker.delete()
        }
    }
}
