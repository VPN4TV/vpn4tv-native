package com.vpn4tv.app.bg

import android.util.Log
import com.vpn4tv.app.converter.ConfigGenerator
import com.vpn4tv.app.converter.ConfigUpgrade
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Rewrites the saved configs once after an update (the owner's decision for
 * the 5.4.0 bridge-UDP fix): at the first launch of a new version code every
 * profile's config on disk is brought to the new generator's shape
 * ([ConfigUpgrade]), instead of transforming it at every start.
 *
 * Application.onCreate starts it off the main thread; BoxService waits for it
 * (bounded) before it reads a profile, so the first connect after an update
 * already runs the upgraded file. A service that is somehow running meanwhile
 * is not restarted: the next start or reload reads the new file.
 *
 * Each file is replaced atomically (a temporary file in the same directory,
 * then renameTo), under [ConfigGenerator.fileLock] so a refresh writing the
 * same profile is never overwritten. The xray sidecar is rewritten with its
 * config (the bridge's inbound and sing-box's outbound to it change together):
 * the sidecar first, then the config. The pair is not one atomic step, but a
 * pair left half done is completed by the next run, which ConfigUpgrade does
 * one half at a time. A profile that fails is logged and the others go on;
 * the version code is recorded only when no file failed, so a failed write is
 * tried again at the next launch. Running it again is safe: an upgraded
 * config gives no changes.
 */
object SavedConfigUpgrade {
    private const val TAG = "ConfigUpgrade"
    private const val TEMP_SUFFIX = ".upgrade.tmp"

    private val done = CountDownLatch(1)

    /** False when the upgrade has not finished within [timeoutMs]. */
    fun awaitDone(timeoutMs: Long): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Once per process, from Application.onCreate, on a background thread. */
    suspend fun runIfVersionChanged() {
        try {
            val current = com.vpn4tv.app.BuildConfig.VERSION_CODE
            val last = com.vpn4tv.app.database.Settings.configUpgradeVersion
            if (last == current) return
            val profiles = com.vpn4tv.app.database.ProfileManager.list()
            val summary = upgradeFiles(profiles.map { it.typed.path }.filter { it.isNotEmpty() })
            Log.i(TAG, "version $last → $current: $summary")
            if (summary.failed == 0) {
                com.vpn4tv.app.database.Settings.configUpgradeVersion = current
            } else {
                Log.w(TAG, "${summary.failed} profile(s) failed; tried again at the next launch")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "saved config upgrade failed: ${t.message}", t)
        } finally {
            done.countDown()
        }
    }

    class Summary {
        var upgraded = 0
        var unchanged = 0
        var skipped = 0
        var failed = 0
        override fun toString() = "upgraded $upgraded, already in shape $unchanged, left alone $skipped, failed $failed"
    }

    enum class Outcome { UPGRADED, UNCHANGED, SKIPPED, FAILED }

    /** Every path on its own: a failure is logged and the next one goes on. */
    internal fun upgradeFiles(paths: List<String>): Summary {
        val summary = Summary()
        for (path in paths) {
            when (upgradeFile(path)) {
                Outcome.UPGRADED -> summary.upgraded++
                Outcome.UNCHANGED -> summary.unchanged++
                Outcome.SKIPPED -> summary.skipped++
                Outcome.FAILED -> summary.failed++
            }
        }
        return summary
    }

    internal fun upgradeFile(path: String): Outcome {
        val name = File(path).name
        return try {
            synchronized(ConfigGenerator.fileLock) {
                val file = File(path)
                if (!file.isFile) {
                    Log.i(TAG, "$name: no config file, left alone")
                    return Outcome.SKIPPED
                }
                val result = ConfigUpgrade.upgrade(file.readText(), sidecars(path))
                when {
                    result.skipped != null -> {
                        Log.i(TAG, "$name: left alone (${result.skipped})")
                        Outcome.SKIPPED
                    }
                    result.json == null && result.xray == null -> Outcome.UNCHANGED
                    else -> {
                        result.xray?.let { writeAtomically(File(ConfigGenerator.xraySidecarPath(path)), it) }
                        result.json?.let { writeAtomically(file, it) }
                        Log.i(TAG, "$name: ${result.changes.joinToString("; ")}")
                        Outcome.UPGRADED
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "$name: upgrade failed: ${e.message}", e)
            Outcome.FAILED
        }
    }

    private fun sidecars(path: String) = ConfigUpgrade.Sidecars(
        xray = readIfExists(ConfigGenerator.xraySidecarPath(path)),
        outline = readIfExists(ConfigGenerator.outlineSidecarPath(path)),
        wg = readIfExists(ConfigGenerator.wgSidecarPath(path)),
        olcrtc = readIfExists(ConfigGenerator.olcrtcSidecarPath(path)),
        tt = readIfExists(ConfigGenerator.ttSidecarPath(path)),
    )

    /** An unreadable sidecar counts as none: its bridge's outbounds are left alone. */
    private fun readIfExists(path: String): String? = try {
        File(path).takeIf { it.isFile }?.readText()
    } catch (e: Exception) {
        Log.w(TAG, "${File(path).name}: unreadable, its bridge is left alone: ${e.message}")
        null
    }

    /**
     * [text] replaces [target] whole or not at all: written and synced to a
     * temporary file in the same directory, then renamed over it (rename(2)
     * within one file system). No java.nio: minSdk 23.
     */
    internal fun writeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw java.io.IOException("rename to ${target.name} failed")
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }
}
