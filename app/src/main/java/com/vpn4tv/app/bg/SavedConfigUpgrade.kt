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
 * the 5.4.0 bridge-UDP fix): at the first launch of a new version code, or of
 * a new [SHAPE], every profile's config on disk is brought to the new
 * generator's shape ([ConfigUpgrade]), instead of transforming it at every
 * start.
 *
 * Application.onCreate starts it off the main thread; BoxService waits for it
 * (bounded) before it reads a profile, so the first connect after an update
 * already runs the upgraded file. A start that gave up waiting
 * ([startedWithoutIt]) read its file as it was: once the upgrade has
 * rewritten any file, the running service is reloaded.
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

    /**
     * Raised with every change of [ConfigUpgrade]: builds that share a version
     * code (test builds) run the new upgrade too. Recorded with the version
     * code as versionCode * 100 + SHAPE. 2: the xray bridge speaks VLESS with
     * UDP, NTP goes direct where UDP is refused.
     */
    private const val SHAPE = 2

    private val done = CountDownLatch(1)

    // Whether a start went on before the upgrade finished, and whether the
    // upgrade has finished and rewrote any file: the upgrade's end reloads
    // when both hold.
    private val reloadLock = Any()
    private var startWentOn = false
    private var finished = false
    private var rewrote = false

    /** False when the upgrade has not finished within [timeoutMs]. */
    fun awaitDone(timeoutMs: Long): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * A start gave up waiting ([awaitDone] false) and reads the file as it is.
     * Finished meanwhile: the start reads the upgraded files anyway, nothing
     * to reload.
     */
    fun startedWithoutIt() {
        synchronized(reloadLock) {
            if (!finished) startWentOn = true
        }
    }

    /** Once per process, from Application.onCreate, on a background thread. */
    suspend fun runIfVersionChanged() {
        var upgraded = 0
        try {
            val current = com.vpn4tv.app.BuildConfig.VERSION_CODE * 100 + SHAPE
            val last = com.vpn4tv.app.database.Settings.configUpgradeVersion
            if (last == current) return
            val profiles = com.vpn4tv.app.database.ProfileManager.list()
            val summary = upgradeFiles(profiles.map { it.typed.path }.filter { it.isNotEmpty() })
            upgraded = summary.upgraded
            Log.i(TAG, "version $last → $current: $summary")
            if (summary.failed == 0) {
                com.vpn4tv.app.database.Settings.configUpgradeVersion = current
            } else {
                Log.w(TAG, "${summary.failed} profile(s) failed; tried again at the next launch")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "saved config upgrade failed: ${t.message}", t)
        } finally {
            val reload = synchronized(reloadLock) {
                finished = true
                rewrote = upgraded > 0
                startWentOn && rewrote
            }
            done.countDown()
            if (reload) reloadForUpgrade()
        }
    }

    private fun reloadForUpgrade() {
        Log.i(TAG, "a start went on without the upgrade: reloading with the upgraded files")
        BoxService.reloadIfRunning()
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
