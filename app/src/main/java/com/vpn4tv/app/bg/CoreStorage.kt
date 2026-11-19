package com.vpn4tv.app.bg

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The folder for the core's working files (cache.db): the app's folder on external storage when a file can be
 * created there, the internal files folder otherwise.
 *
 * External storage gets [PROBE_TIMEOUT_MS] to answer. Where it does not (crash host, November 2026: one TV box
 * on a Spreadtrum board with Android 10 and one Haier TV with Android 14, 49 reports in two weeks),
 * Context.getExternalFilesDir stays in access() on the app's folder for more than 40 s at every start of the
 * process. While it does, it holds the lock of the Context it was called on (ContextImpl.mSync), and every other
 * thread that asks that Context for its files, cache or preferences folder waits behind it. Called on the
 * Application, as it was through 5.6.4, that kept the home screen on "loading" (5.4.2, the startup watchdog's
 * reports: MainActivity's thread BLOCKED in ContextImpl.getPreferencesDir) and, from 5.6.0, kept the start of the
 * VPN waiting for the core until the connect watchdog ended the process at 45 s.
 *
 * So the question is asked on a thread of its own and on a Context of its own ([externalIfWritable]), and an
 * answer that is late means the internal folder, for good ([choose]): the switch that BoxService already makes
 * when the core may not open its cache file there ("use_internal"). The core then starts with an empty cache.db
 * once; the selected server is the app's own setting and stays.
 */
object CoreStorage {
    private const val TAG = "CoreStorage"

    /** A healthy device answers in milliseconds; the core's start is held for this long at most. */
    const val PROBE_TIMEOUT_MS = 10_000L

    /**
     * [probe] gives the external folder, or null when there is none to write to, and runs on its own thread. When
     * it has not answered within [timeoutMs], [onLate] is called (it remembers the switch) and [internal] is the
     * answer. The thread is left to itself: nothing waits for it, and what it answers later is not used.
     */
    fun choose(internal: File, timeoutMs: Long = PROBE_TIMEOUT_MS, onLate: () -> Unit, probe: () -> File?): File {
        val task = FutureTask(Callable { probe() })
        Thread(task, "external-storage-probe").apply { isDaemon = true }.start()
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS) ?: internal
        } catch (e: TimeoutException) {
            Log.w(TAG, "external storage did not answer in $timeoutMs ms: the core's files go to internal storage from now on")
            onLate()
            internal
        } catch (e: ExecutionException) {
            Log.w(TAG, "external storage not asked, using internal: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
            internal
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            internal
        }
    }

    /**
     * The app's folder on external storage if a file can be created in it, else null. The probe for [choose]:
     * every call in it may stay in the storage for as long as the storage likes.
     */
    fun externalIfWritable(context: Context): File? {
        // A Context of its own, with its own lock: the Application's stays free for getFilesDir, getCacheDir and
        // getSharedPreferences while this one waits. No call to another process is needed to make it.
        val own = context.createConfigurationContext(Configuration())
        val dir = own.getExternalFilesDir(null) ?: return null
        return try {
            dir.mkdirs()
            val test = File(dir, ".write_test_${System.currentTimeMillis()}")
            if (test.createNewFile() && test.delete()) dir else null
        } catch (e: Exception) {
            Log.w(TAG, "external storage not writable, using internal: ${e.message}")
            null
        }
    }
}
