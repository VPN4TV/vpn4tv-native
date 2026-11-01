package com.vpn4tv.app.bg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The check a rule-set file passes before the core may read it, on the files in assets/. */
class RuleSetFilesTest {
    // Gradle runs unit tests in the module directory.
    private fun asset(name: String) = File("src/main/assets/${RussiaDirectLists.ASSET_DIR}/$name")

    @Test
    fun theBundledFilesCheckOut() {
        for (set in RussiaDirectLists.RULE_SETS) {
            assertNull(set.fileName, RuleSetFiles.check(asset(set.fileName).readBytes(), set))
            assertNull(set.fileName, RuleSetFiles.problem(asset(set.fileName), set))
        }
    }

    @Test
    fun theConstantsMatchSourceJson() {
        val source = JSONObject(asset("SOURCE.json").readText())
        assertEquals(RussiaDirectLists.REVISION, source.getString("revision"))
        assertEquals(RussiaDirectLists.SRS_MAX_VERSION, source.getInt("srs_max_version"))
        val sets = source.getJSONArray("rule_sets")
        for (i in 0 until sets.length()) {
            val entry = sets.getJSONObject(i)
            val set = RussiaDirectLists.RULE_SETS.single { it.tag == entry.getString("tag") }
            assertEquals(set.fileName, entry.getString("file"))
            assertEquals(set.size, entry.getLong("size"))
            assertEquals(set.sha256, entry.getString("sha256"))
        }
    }

    @Test
    fun aDamagedFileIsRefused() {
        val set = RussiaDirectLists.GEOIP
        val good = asset(set.fileName).readBytes()
        val cases = mapOf(
            "truncated" to good.copyOf(4000),
            "longer" to good + byteArrayOf(0),
            "no signature" to good.copyOf().also { it[0] = 'X'.code.toByte() },
            "version 0" to good.copyOf().also { it[3] = 0 },
            "version above the core's" to good.copyOf().also { it[3] = (RussiaDirectLists.SRS_MAX_VERSION + 1).toByte() },
            "one bit flipped" to good.copyOf().also { it[good.size / 2] = (it[good.size / 2].toInt() xor 1).toByte() },
            "empty" to ByteArray(0),
        )
        for ((name, bytes) in cases) assertNotNull(name, RuleSetFiles.check(bytes, set))
    }

    @Test
    fun aMissingOrWrongSizedFileIsRefused() {
        val set = RussiaDirectLists.GEOSITE
        assertEquals("missing", RuleSetFiles.problem(File("does-not-exist.srs"), set))
        val other = asset(RussiaDirectLists.GEOIP.fileName)
        assertTrue(RuleSetFiles.problem(other, set)!!.startsWith("size"))
    }
}
