package com.vpn4tv.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * What the Baseline Profile gives: a cold start to the home screen without
 * ahead-of-time code (as a fresh install without a profile) and with the
 * profile (as Play installs it). On a device or emulator with Android 10+:
 *   ANDROID_SERIAL=… ./gradlew :baselineprofile:connectedPlayBenchmarkReleaseAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startWithoutProfile() = start(CompilationMode.None())

    @Test
    fun startWithProfile() = start(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun start(mode: CompilationMode) = rule.measureRepeated(
        packageName = "com.vpn4tv.hiddify",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.text(HOME)), 10_000)
    }

    private companion object {
        val HOME: Pattern = Pattern.compile("Главная|Dashboard")
    }
}
