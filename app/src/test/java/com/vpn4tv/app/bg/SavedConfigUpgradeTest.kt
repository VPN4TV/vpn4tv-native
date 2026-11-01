package com.vpn4tv.app.bg

import com.vpn4tv.app.converter.ConfigGenerator
import com.vpn4tv.app.converter.ConfigUpgrade
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The upgrade over saved profile files: in place, atomic, once, and one
 * broken or foreign profile never stops the others. Files come from
 * resources/config-upgrade (the 5.3.x generator's output, made-up keys).
 */
class SavedConfigUpgradeTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("config-upgrade").toFile()
    }

    @After
    fun tearDown() {
        dir.walkBottomUp().forEach { it.setWritable(true) }
        dir.deleteRecursively()
    }

    private fun resource(path: String): String? = javaClass.getResource("/config-upgrade/$path")?.readText()

    /** A fixture as profile [id] in [into], with its sidecars, as the app lays them out. */
    private fun install(fixture: String, id: String, into: File = dir): File {
        into.mkdirs()
        val config = File(into, "$id.json")
        config.writeText(requireNotNull(resource("$fixture.json")))
        for (kind in listOf("xray", "outline", "wg", "olcrtc", "tt")) {
            resource("$fixture.json.$kind.json")?.let { File(into, "$id.json.$kind.json").writeText(it) }
        }
        return config
    }

    private fun sidecarsOf(fixture: String) = ConfigUpgrade.Sidecars(
        xray = resource("$fixture.json.xray.json"),
        outline = resource("$fixture.json.outline.json"),
        wg = resource("$fixture.json.wg.json"),
        olcrtc = resource("$fixture.json.olcrtc.json"),
        tt = resource("$fixture.json.tt.json"),
    )

    private fun tempFiles(root: File) = root.walk().filter { it.name.endsWith(".upgrade.tmp") }.toList()

    @Test
    fun upgradesInPlaceOnceAndLeavesTheRestAlone() {
        val outline = install("outline-only", "1")
        val olcrtc = install("olcrtc-only", "2")
        val native = install("native-only", "3")
        val foreignText = requireNotNull(javaClass.getResource("/russia-direct/foreign-singbox.json")).readText()
        val foreign = File(dir, "4.json").apply { writeText(foreignText) }
        val broken = File(dir, "5.json").apply { writeText("{\"outbounds\": [") }
        val missing = File(dir, "6.json")
        val untouched = listOf(native, foreign, broken).associateWith { it.readBytes() }
        val paths = listOf(outline, olcrtc, native, foreign, broken, missing).map { it.path }

        val first = SavedConfigUpgrade.upgradeFiles(paths)
        assertEquals(first.toString(), 2, first.upgraded)
        assertEquals(first.toString(), 4, first.skipped)
        assertEquals(first.toString(), 0, first.failed)
        assertEquals(ConfigUpgrade.upgrade(requireNotNull(resource("outline-only.json")), sidecarsOf("outline-only")).json, outline.readText())
        assertEquals(ConfigUpgrade.upgrade(requireNotNull(resource("olcrtc-only.json")), sidecarsOf("olcrtc-only")).json, olcrtc.readText())
        for ((file, bytes) in untouched) assertArrayEquals(file.name, bytes, file.readBytes())
        assertFalse(missing.exists())
        // The sidecars are not rewritten.
        assertEquals(resource("outline-only.json.outline.json"), File(dir, "1.json.outline.json").readText())
        assertTrue(tempFiles(dir).isEmpty())

        // Again (the next version, or a retry): nothing to do, nothing written.
        val upgradedBytes = listOf(outline, olcrtc).associateWith { it.readBytes() }
        val second = SavedConfigUpgrade.upgradeFiles(paths)
        assertEquals(second.toString(), 0, second.upgraded)
        assertEquals(second.toString(), 2, second.unchanged)
        assertEquals(second.toString(), 4, second.skipped)
        assertEquals(second.toString(), 0, second.failed)
        for ((file, bytes) in upgradedBytes + untouched) assertArrayEquals(file.name, bytes, file.readBytes())
    }

    @Test
    fun oneFailingProfileDoesNotStopTheOthers() {
        val locked = File(dir, "locked")
        val stuck = install("outline-only", "1", locked)
        val fine = install("awg-only", "2")
        val before = stuck.readBytes()
        locked.setWritable(false)
        // Running as a user the file system lets write anyway: nothing to check.
        assumeFalse(File(locked, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false) })

        val summary = SavedConfigUpgrade.upgradeFiles(listOf(stuck.path, fine.path))
        assertEquals(summary.toString(), 1, summary.failed)
        assertEquals(summary.toString(), 1, summary.upgraded)
        assertArrayEquals(before, stuck.readBytes())
        assertEquals(ConfigUpgrade.upgrade(requireNotNull(resource("awg-only.json")), sidecarsOf("awg-only")).json, fine.readText())
        assertTrue(tempFiles(dir).isEmpty())
    }

    @Test
    fun writeAtomicallyReplacesTheWholeFileOrNothing() {
        val target = File(dir, "7.json").apply { writeText("old") }
        SavedConfigUpgrade.writeAtomically(target, "new content")
        assertEquals("new content", target.readText())
        assertTrue(tempFiles(dir).isEmpty())

        // The rename cannot replace a directory: the old one stays, no temporary file is left.
        val blocker = File(dir, "8.json").apply { mkdirs() }
        val failed = runCatching { SavedConfigUpgrade.writeAtomically(blocker, "x") }
        assertTrue(failed.isFailure)
        assertTrue(blocker.isDirectory)
        assertTrue(tempFiles(dir).isEmpty())
    }

    @Test
    fun theUpgradeHoldsTheSameLockAsTheGeneratorsWrites() {
        val config = install("outline-only", "1")
        val held = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            synchronized(ConfigGenerator.fileLock) {
                held.countDown()
                Thread.sleep(300)
            }
        }
        holder.start()
        held.await()
        val started = System.nanoTime()
        assertEquals(SavedConfigUpgrade.Outcome.UPGRADED, SavedConfigUpgrade.upgradeFile(config.path))
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        holder.join()
        assertTrue("waited $waitedMs ms", waitedMs >= 200)
    }
}
