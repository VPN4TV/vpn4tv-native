package com.vpn4tv.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Russian lives in values-ru, the language without a region: Android 6 shows
 * values-ru-rRU only with the region Russia, and Russian with any other region
 * (Kazakhstan, Belarus, Ukraine) got the English screens.
 */
class RussianResourcesTest {
    // Unit tests run in the module's folder.
    private val res = File("src/main/res")

    @Test
    fun russianIsInTheLanguageFolder() {
        assertTrue(File(res, "values-ru/strings.xml").isFile)
        val regional = res.listFiles().orEmpty().map { it.name }.filter { it.startsWith("values-ru-") }
        assertTrue("Russian strings belong in values-ru, not $regional", regional.isEmpty())
    }

    @Test
    fun theRussianFileHasTheAppStrings() {
        val ru = File(res, "values-ru/strings.xml").readText()
        assertTrue(ru.contains("<string name=\"action_connect\">Подключить</string>"))
    }
}
