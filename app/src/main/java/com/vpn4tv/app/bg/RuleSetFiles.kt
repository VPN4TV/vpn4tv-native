package com.vpn4tv.app.bg

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * The rule-set files of "Russia direct". They ship in assets/rule-set/ and the
 * core reads them from the app's internal files dir (type local, absolute
 * path), so a start needs no network.
 *
 * A file is used only when it checks out against the constants generated
 * with it ([RussiaDirectLists]): the "SRS" signature, a format version the
 * core reads, the size and the sha256. A new APK with new files brings new
 * constants, so stale files fail the check and are copied again. A copy goes
 * through a temporary file and a rename: the core never sees half a file.
 * Plain java.io: API 23-25 have no java.nio.file. Internal storage only: the
 * external working dir is not readable on some multi-user TVs.
 */
object RuleSetFiles {
    private const val TAG = "RuleSetFiles"
    private const val TEMP_SUFFIX = ".tmp"

    private val lock = Any()

    // Only a success is remembered: an early failure must not hold the
    // suffix-only variant for the rest of the process.
    @Volatile
    private var verifiedSets: RussiaDirect.Sets? = null

    private fun dir(context: Context) = File(context.filesDir, RussiaDirectLists.ASSET_DIR)

    /**
     * Paths of the verified files, copied from the assets first when missing
     * or damaged. Null when that fails; the caller then routes by the
     * built-in suffixes alone. Never throws.
     */
    fun verified(context: Context): RussiaDirect.Sets? {
        verifiedSets?.let { sets ->
            if (File(sets.geosite).length() == RussiaDirectLists.GEOSITE.size &&
                File(sets.geoip).length() == RussiaDirectLists.GEOIP.size
            ) return sets
            verifiedSets = null
        }
        return synchronized(lock) {
            try {
                val dir = dir(context)
                val sets = checkedSets(dir) ?: run {
                    ensureLocked(context)
                    checkedSets(dir)
                }
                if (sets == null) {
                    val problems = RussiaDirectLists.RULE_SETS.joinToString("; ") { set ->
                        "${set.fileName}: ${problem(File(dir, set.fileName), set) ?: "ok"}"
                    }
                    Log.w(TAG, "rule sets unusable, Russian names go by suffixes only: $problems")
                }
                verifiedSets = sets
                sets
            } catch (t: Throwable) {
                Log.w(TAG, "rule sets unusable: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }
    }

    /** Copy whatever is missing or damaged. Called once at app start, off the main thread. Never throws. */
    fun ensure(context: Context): Boolean = synchronized(lock) {
        try {
            ensureLocked(context)
        } catch (t: Throwable) {
            Log.w(TAG, "ensure failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Drop the files: the core could not read them. The next start copies them again. */
    fun invalidate(context: Context) = synchronized(lock) {
        verifiedSets = null
        try {
            for (set in RussiaDirectLists.RULE_SETS) File(dir(context), set.fileName).delete()
        } catch (t: Throwable) {
            Log.w(TAG, "invalidate failed: ${t.message}")
        }
    }

    private fun checkedSets(dir: File): RussiaDirect.Sets? {
        if (RussiaDirectLists.RULE_SETS.any { problem(File(dir, it.fileName), it) != null }) return null
        return RussiaDirect.Sets(
            geosite = File(dir, RussiaDirectLists.GEOSITE.fileName).absolutePath,
            geoip = File(dir, RussiaDirectLists.GEOIP.fileName).absolutePath,
        )
    }

    private fun ensureLocked(context: Context): Boolean {
        val dir = dir(context)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        // A copy cut short by a crash or a full disk.
        dir.listFiles()?.filter { it.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
        var ok = true
        for (set in RussiaDirectLists.RULE_SETS) {
            val target = File(dir, set.fileName)
            if (problem(target, set) == null) continue
            try {
                copy(context, set, target)
                Log.i(TAG, "copied ${set.fileName} (${set.source})")
            } catch (e: Exception) {
                Log.w(TAG, "copy ${set.fileName} failed: ${e.message}")
                ok = false
            }
        }
        return ok
    }

    private fun copy(context: Context, set: RussiaDirectLists.RuleSetFile, target: File) {
        val temp = File(target.parentFile, "${set.fileName}.${android.os.Process.myPid()}$TEMP_SUFFIX")
        try {
            context.assets.open("${RussiaDirectLists.ASSET_DIR}/${set.fileName}").use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            problem(temp, set)?.let { throw IOException("bundled file: $it") }
            // rename(2) replaces the target in one step.
            if (!temp.renameTo(target)) throw IOException("rename to ${target.name} failed")
        } finally {
            temp.delete()
        }
    }

    /** Why [file] is not [set], or null when it is. */
    internal fun problem(file: File, set: RussiaDirectLists.RuleSetFile): String? {
        if (!file.isFile) return "missing"
        val length = file.length()
        if (length != set.size) return "size $length, expected ${set.size}"
        return check(file.readBytes(), set)
    }

    /** Why [bytes] are not [set], or null when they are. */
    internal fun check(bytes: ByteArray, set: RussiaDirectLists.RuleSetFile): String? {
        if (bytes.size.toLong() != set.size) return "size ${bytes.size}, expected ${set.size}"
        val magic = RussiaDirectLists.SRS_MAGIC.toByteArray(Charsets.US_ASCII)
        if (bytes.size <= magic.size || (magic.indices).any { bytes[it] != magic[it] }) return "no ${RussiaDirectLists.SRS_MAGIC} signature"
        val version = bytes[magic.size].toInt() and 0xff
        if (version < RussiaDirectLists.SRS_MIN_VERSION || version > RussiaDirectLists.SRS_MAX_VERSION) {
            return "format version $version, the core reads ${RussiaDirectLists.SRS_MIN_VERSION}..${RussiaDirectLists.SRS_MAX_VERSION}"
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (sha256 != set.sha256) return "sha256 ${sha256.take(12)}…, expected ${set.sha256.take(12)}…"
        return null
    }
}
