package com.vpn4tv.app.ui

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

// Settings and the "+" on Home both read keyImportFor: one answer for both.
class KeyImportTest {
    @Test
    fun portraitPastesTheKeyInTheDialog() {
        assertEquals(KeyImport.Paste, keyImportFor(Configuration.ORIENTATION_PORTRAIT))
    }

    @Test
    fun landscapeKeepsTheImportOverTheLocalNetwork() {
        assertEquals(KeyImport.LocalNetwork, keyImportFor(Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun anOrientationNotReportedKeepsTheImportOverTheLocalNetwork() {
        assertEquals(KeyImport.LocalNetwork, keyImportFor(Configuration.ORIENTATION_UNDEFINED))
        // ORIENTATION_SQUARE, deprecated: no system reports it any more.
        assertEquals(KeyImport.LocalNetwork, keyImportFor(3))
    }
}
