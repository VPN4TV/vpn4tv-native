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

    @Test
    fun theVpnEndedByAndroidIsRecognised() {
        // Group b9d6620551: the low-memory killer took the process that only the VPN kept.
        assertTrue(ExitReasons.endedVpn(ApplicationExitInfo.REASON_LOW_MEMORY, fgs, null))
        assertTrue(ExitReasons.endedVpn(ApplicationExitInfo.REASON_SIGNALED, fgs, null))
        assertTrue(ExitReasons.endedVpn(ApplicationExitInfo.REASON_OTHER, fgs, "MemoryLimiter:AnonSwap"))
    }

    @Test
    fun notTheUserACrashAnUpdateTheAppItselfOrAScreen() {
        assertFalse(ExitReasons.endedVpn(ApplicationExitInfo.REASON_USER_REQUESTED, fgs, null))
        assertFalse(ExitReasons.endedVpn(ApplicationExitInfo.REASON_CRASH_NATIVE, fgs, null))
        assertFalse(ExitReasons.endedVpn(ApplicationExitInfo.REASON_SIGNALED, fgs, null, selfRestart = true))
        // On screen: the user saw it go, and the VPN may not have run at all.
        assertFalse(ExitReasons.endedVpn(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_FOREGROUND, null))
        assertFalse(ExitReasons.endedVpn(ApplicationExitInfo.REASON_LOW_MEMORY, cached, null))
    }

    @Test
    fun theDeviceLineHasMemoryInMegabytesAndTheAbi() {
        val mb = 1024L * 1024L
        assertEquals("device ram=1843MB avail=210MB threshold=216MB low=true abi=armeabi-v7a",
            ExitReasons.deviceLine(1843 * mb, 210 * mb, 216 * mb, true, "armeabi-v7a"))
        assertEquals("device ram=0MB avail=0MB threshold=0MB low=false abi=?", ExitReasons.deviceLine(0, 0, 0, false, null))
    }

    @Test
    fun tclsGuardIsKnownByItsDescription() {
        // TCL Smart TV Pro, crash group fafb5b4664: reason=SIGNALED status=9 description=tguard-kill.
        assertTrue(ExitReasons.byTclGuard("tguard-kill"))
        assertTrue(ExitReasons.byTclGuard("TGUARD-KILL"))
        assertFalse(ExitReasons.byTclGuard("OneKeyClean"))
        assertFalse(ExitReasons.byTclGuard(null))
        // The VPN it ended counts like any other kill.
        assertTrue(ExitReasons.endedVpn(ApplicationExitInfo.REASON_SIGNALED, fgs, "tguard-kill"))
    }

    @Test
    fun theNoteForOurServerNamesTheKillAndItsTime() {
        // The group's latest kill, 2026-11-18T10:21:29Z. The server takes "tguard-kill" exactly.
        assertEquals("by=tguard-kill; at=2026-11-18T10:21:29Z", ExitReasons.guardNoticeValue(1_794_997_289_000L))
    }

    @Test
    fun theNoteGoesOutAtMostOnceAWeek() {
        val week = ExitReasons.GUARD_NOTICE_EVERY_MS
        val now = 1_795_000_000_000L
        assertTrue(ExitReasons.guardNoticeDue(0L, now))
        assertFalse(ExitReasons.guardNoticeDue(now - week + 1, now))
        assertTrue(ExitReasons.guardNoticeDue(now - week, now))
        // A clock set back.
        assertTrue(ExitReasons.guardNoticeDue(now + 1, now))
    }
}
