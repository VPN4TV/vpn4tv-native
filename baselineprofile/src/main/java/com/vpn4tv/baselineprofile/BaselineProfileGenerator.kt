package com.vpn4tv.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * What a user runs first: the cold start up to the home screen, then the
 * servers (scrolled), the keys and the settings, and home again, through the
 * navigation bar of a phone. On a TV the same code serves most of it (start,
 * home, the lists); the TV's own layouts are not walked.
 *
 * Works with or without a key on the device and in English or Russian.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startAndMainScreens() = rule.collect(
        packageName = PACKAGE,
        // Also a startup profile: R8 puts the classes of the start first in the dex.
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        // The launch screen stays until the keys are loaded.
        device.wait(Until.hasObject(By.text(HOME)), TIMEOUT_MS)
        open(SERVERS)
        device.findObject(By.scrollable(true))?.let {
            it.fling(Direction.DOWN)
            device.waitForIdle()
        }
        open(KEYS)
        open(SETTINGS)
        device.findObject(By.scrollable(true))?.let {
            it.fling(Direction.DOWN)
            device.waitForIdle()
        }
        open(HOME)
    }

    private fun MacrobenchmarkScope.open(tab: Pattern) {
        val item = device.wait(Until.findObject(By.text(tab)), TIMEOUT_MS) ?: return
        item.click()
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "com.vpn4tv.hiddify"
        const val TIMEOUT_MS = 10_000L
        // The navigation bar's labels in either language of the app.
        val HOME: Pattern = Pattern.compile("Главная|Dashboard")
        val SERVERS: Pattern = Pattern.compile("Серверы|Servers")
        val KEYS: Pattern = Pattern.compile("Ключи|Keys")
        val SETTINGS: Pattern = Pattern.compile("Настройки|Settings")
    }
}
