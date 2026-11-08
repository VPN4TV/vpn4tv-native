package com.vpn4tv.app.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What Auto Backup takes (res/xml): the keys and settings, never the
 * installation ID or the core's cache, the same on every Android version.
 */
class BackupRulesTest {
    // Unit tests run in the module's folder.
    private val xml = File("src/main/res/xml")
    private val include = Regex("""<include\s+domain="([^"]+)"\s+path="([^"]+)"\s*/>""")

    private fun includes(text: String) = include.findAll(text).map { it.groupValues[1] + ":" + it.groupValues[2] }.toList()

    @Test
    fun theKeysGoTheDeviceIdStays() {
        val old = includes(File(xml, "backup_rules.xml").readText())
        assertTrue(old.containsAll(listOf("database:profiles.db", "database:settings.db", "file:profiles")))
        // Everything not included stays: no whole domains, nothing with the ID or the core's cache.
        assertFalse(old.any { it.startsWith("external:") || it.startsWith("root:") || it == "sharedpref:." || it == "file:." })
        assertFalse(old.any { "hwid" in it || "cache.db" in it || "configuration.json" in it })
    }

    @Test
    fun androidTwelveRulesAreTheSame() {
        val old = includes(File(xml, "backup_rules.xml").readText())
        val text = File(xml, "data_extraction_rules.xml").readText()
        val cloud = includes(text.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>"))
        val transfer = includes(text.substringAfter("<device-transfer>").substringBefore("</device-transfer>"))
        assertEquals(old, cloud)
        assertEquals(old, transfer)
    }

    @Test
    fun theManifestUsesThem() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        // An agent without fullBackupOnly would turn Auto Backup into key/value backup.
        assertTrue(manifest.contains("android:backupAgent=\".bg.RestoreAgent\""))
        assertTrue(manifest.contains("android:fullBackupOnly=\"true\""))
    }
}
