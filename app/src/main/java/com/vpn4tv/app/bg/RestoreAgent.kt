package com.vpn4tv.app.bg

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Auto Backup runs as without an agent (android:fullBackupOnly): the rules in
 * res/xml decide what goes, the default onFullBackup and onRestoreFile apply
 * them. The agent is here for [onRestoreFinished] only: it marks the restore,
 * and the next start of the app ([RestoredData]) resets what describes the old
 * device. Android runs it in a restricted process (the base Application, not
 * ours), so it touches nothing but its own file.
 */
class RestoreAgent : BackupAgent() {
    // Key/value backup is not used.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) {}

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) {}

    override fun onRestoreFinished() {
        // noBackupFilesDir: never backed up itself, so a marker never comes back with the data.
        runCatching { File(noBackupFilesDir, RestoredData.MARKER).createNewFile() }
    }
}
