package com.vpn4tv.app.utils

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitReasonsTest {
    private val fgs = RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    private val cached = RunningAppProcessInfo.IMPORTANCE_CACHED

    @Test
    fun theSystemEndingTheVpnOrTheScreenIsReported() {
        assertTrue(ExitReasons.worthReporting(ApplicationExitInfo.REASON_LOW_MEMORY, fgs, null))
        assertTrue(ExitReasons.worthReporting(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, RunningAppProcessInfo.IMPORTANCE_FOREGROUND, null))
        assertTrue(ExitReasons.worthReporting(ApplicationExitInfo.REASON_SIGNALED, fgs, null))
    }

    @Test
    fun theAppsOwnRestartIsNot() {
        // The watchdog's kill shows as SIGNALED with the VPN running.
        assertFalse(ExitReasons.worthReporting(ApplicationExitInfo.REASON_SIGNALED, fgs, null, selfRestart = true))
        assertFalse(ExitReasons.worthReporting(ApplicationExitInfo.REASON_OTHER, cached, "MemoryLimiter:AnonSwap", selfRestart = true))
    }

    @Test
    fun aCachedProcessEndingIsNormal() {
        assertFalse(ExitReasons.worthReporting(ApplicationExitInfo.REASON_LOW_MEMORY, cached, null))
        assertFalse(ExitReasons.worthReporting(ApplicationExitInfo.REASON_OTHER, cached, "remove task"))
    }

    @Test
    fun theMemoryLimiterAndAnrsAreAlwaysReported() {
        assertTrue(ExitReasons.worthReporting(ApplicationExitInfo.REASON_OTHER, cached, "MemoryLimiter:AnonSwap 512MB"))
        assertTrue(ExitReasons.worthReporting(ApplicationExitInfo.REASON_ANR, cached, null))
    }

    @Test
    fun crashesTheUserAndUpdatesAreNot() {
        assertFalse(ExitReasons.worthReporting(ApplicationExitInfo.REASON_CRASH, fgs, null))
        assertFalse(ExitReasons.worthReporting(ApplicationExitInfo.REASON_CRASH_NATIVE, fgs, null))
        assertFalse(ExitReasons.worthReporting(ApplicationExitInfo.REASON_USER_REQUESTED, fgs, null))
        assertFalse(ExitReasons.worthReporting(ApplicationExitInfo.REASON_EXIT_SELF, fgs, null))
        assertFalse(ExitReasons.worthReporting(16 /* PACKAGE_UPDATED */, fgs, null))
    }

    @Test
    fun theReportStartsWithTheNewestExit() {
        val exits = listOf(
            ExitReasons.Exit(1_762_600_000_000, ApplicationExitInfo.REASON_OTHER, 9, fgs, 150_000, 210_000, "MemoryLimiter:AnonSwap\nlimit"),
            ExitReasons.Exit(1_762_500_000_000, ApplicationExitInfo.REASON_LOW_MEMORY, 0, fgs, 90_000, 120_000, null),
        )
        val lines = ExitReasons.report(exits, "reason=QUOTA time=2026-11-08T10:00:00Z").trimEnd().lines()
        assertEquals("app-exit reason=OTHER importance=foreground-service(125)", lines[0])
        assertEquals(
            "exit time=2025-11-08T11:06:40Z reason=OTHER status=9 importance=foreground-service(125) " +
                "pss=150000kB rss=210000kB description=MemoryLimiter:AnonSwap limit",
            lines[1],
        )
        assertTrue(lines[2], lines[2].contains("reason=LOW_MEMORY") && !lines[2].contains("description"))
        assertEquals("key-update-stop reason=QUOTA time=2026-11-08T10:00:00Z", lines[3])
    }

    @Test
    fun namesForTheLog() {
        assertEquals("OTHER", ExitReasons.reasonName(ApplicationExitInfo.REASON_OTHER))
        assertEquals("FREEZER", ExitReasons.reasonName(14))
        assertEquals("REASON_99", ExitReasons.reasonName(99))
        assertEquals("cached(400)", ExitReasons.importanceName(cached))
        assertEquals("QUOTA", ExitReasons.workStopName(WorkInfo.STOP_REASON_QUOTA))
        assertEquals("STOP_REASON_777", ExitReasons.workStopName(777))
    }
}
