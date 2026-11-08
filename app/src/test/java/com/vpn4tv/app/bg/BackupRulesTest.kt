package com.vpn4tv.app.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What Auto Backup takes (res/xml, res/xml-v28): the keys and settings, never
 * the installation ID or the core's cache; into the cloud only when the
 * backup is encrypted on the device (owner, 2026-11-08: a key link is a
 * credential), and nothing on Android 6 to 8, which cannot encrypt it.
 */
class BackupRulesTest {
    // Unit tests run in the module's folder.
    private val res = File("src/main/res")
    private val include = Regex("""<include\s+domain="([^"]+)"\s+path="([^"]+)"([^>]*)/>""")

    private fun includes(text: String) = include.findAll(text).map { it.groupValues[1] + ":" + it.groupValues[2] }.toList()
    private fun encryptedOnly(text: String) =
        include.findAll(text).all { "requireFlags=\"clientSideEncryption\"" in it.groupValues[3] }

    private val androidNine get() = File(res, "xml-v28/backup_rules.xml").readText()

    @Test
    fun theKeysGoTheDeviceIdStays() {
        val list = includes(androidNine)
        assertTrue(list.containsAll(listOf("database:profiles.db", "database:settings.db", "file:profiles")))
        // Everything not included stays: no whole domains, nothing with the ID or the core's cache.
        assertFalse(list.any { it.startsWith("external:") || it.startsWith("root:") || it == "sharedpref:." || it == "file:." })
        assertFalse(list.any { "hwid" in it || "cache.db" in it || "configuration.json" in it })
    }

    @Test
    fun onlyIntoABackupEncryptedOnTheDevice() {
        assertTrue(encryptedOnly(androidNine))
        val text = File(res, "xml/data_extraction_rules.xml").readText()
        val cloud = text.substringAfter("<cloud-backup").substringBefore("</cloud-backup>")
        assertTrue(cloud.startsWith(" disableIfNoEncryptionCapabilities=\"true\">"))
        assertTrue(encryptedOnly(cloud))
    }

    @Test
    fun nothingOnAndroidSixToEight() {
        val text = File(res, "xml/backup_rules.xml").readText()
        assertTrue(includes(text).isEmpty())
        for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
            assertTrue(text.contains("<exclude domain=\"$domain\" path=\".\" />"))
        }
    }

    @Test
    fun androidTwelveRulesAreTheSame() {
        val list = includes(androidNine)
        val text = File(res, "xml/data_extraction_rules.xml").readText()
        val cloud = includes(text.substringAfter("<cloud-backup").substringBefore("</cloud-backup>"))
        val transfer = includes(text.substringAfter("<device-transfer>").substringBefore("</device-transfer>"))
        assertEquals(list, cloud)
        assertEquals(list, transfer)
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
