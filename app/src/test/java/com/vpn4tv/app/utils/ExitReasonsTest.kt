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

    /** ART's thread dump as Android keeps it for an ANR's exit record, cut to what matters here. */
    private val anrDump = """
        ----- pid 12345 at 2026-11-18 20:59:39.123456789+0300 -----
        Cmd line: com.vpn4tv.hiddify
        ABI: 'arm'
        DALVIK THREADS (10):
        "Signal Catcher" daemon prio=10 tid=6 Runnable
          | group="system" sCount=0 ucsCount=0 flags=0 obj=0x12c401f8 self=0xe6b4ae00
          native: #00 pc 0038d6d3  /apex/com.android.art/lib/libart.so (art::DumpNativeStack+198)
          (no managed stack frames)

        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a3b3a8 self=0xe6b4a000
          | sysTid=12345 nice=0 cgrp=default sched=0/0 handle=0xf7a8c4a8
          | state=S schedstat=( 1 2 3 ) utm=1 stm=1 core=1 HZ=100
          | held mutexes=
          at zh3.b(r8-map-id-0123:12)
          - waiting to lock <0x0b5a2a57> (a java.lang.Object) held by thread 18
          at com.vpn4tv.app.bg.VPNService.onStartCommand(r8-map-id-0123:3)
          at android.app.ActivityThread.handleServiceArgs(ActivityThread.java:4680)

        "DefaultDispatcher-worker-1" daemon prio=5 tid=18 Native
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x12c80a40 self=0xd0a1e000
          native: #00 pc 0005f4ac  /apex/com.android.runtime/lib/bionic/libc.so (__ioctl+8)
          at io.nekohasekai.libbox.Libbox.newService(Native method)
          - locked <0x0b5a2a57> (a java.lang.Object)
          at zh3.a(r8-map-id-0123:40)

        "DefaultDispatcher-worker-2" daemon prio=5 tid=19 TimedWaiting
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x12c80b00 self=0xd0a1f000
          at jdk.internal.misc.Unsafe.park(Native method)
          - waiting on an unknown object
          at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:376)

        "DefaultDispatcher-worker-3" daemon prio=5 tid=20 TimedWaiting
          at jdk.internal.misc.Unsafe.park(Native method)
          - waiting on an unknown object
          at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:376)

        "OkHttp provider.example.com" daemon prio=5 tid=21 Native
          native: #00 pc 0005f4ac  /apex/com.android.runtime/lib/bionic/libc.so (recvfrom+8)
          at java.net.SocketInputStream.socketRead0(Native method)

        "binder:12345_1" prio=5 tid=13 Native
          native: #00 pc 000b7698  /apex/com.android.runtime/lib/bionic/libc.so (__ioctl+8)
          (no managed stack frames)

        "FinalizerDaemon" daemon prio=5 tid=11 Waiting
          at java.lang.Object.wait(Native method)

        "DefaultDispatch" prio=5 (not attached)
          | sysTid=25353 nice=0 cgrp=top-app
          native: #00 pc 0255751c  /data/app/~~a==/com.vpn4tv.hiddify-b==/lib/arm/libbox.so (???)

        ----- end 12345 -----
    """.trimIndent()

    @Test
    fun anAnrsDumpKeepsTheMainThreadThenWhoHoldsItsLockThenTheOtherJavaStacks() {
        assertEquals(
            """
            "main" prio=5 tid=1 Blocked
              at zh3.b(r8-map-id-0123:12)
              - waiting to lock <0x0b5a2a57> (a java.lang.Object) held by thread 18
              at com.vpn4tv.app.bg.VPNService.onStartCommand(r8-map-id-0123:3)
              at android.app.ActivityThread.handleServiceArgs(ActivityThread.java:4680)
            "DefaultDispatcher-worker-1" daemon prio=5 tid=18 Native
              native: #00 pc 0005f4ac  /apex/com.android.runtime/lib/bionic/libc.so (__ioctl+8)
              at io.nekohasekai.libbox.Libbox.newService(Native method)
              - locked <0x0b5a2a57> (a java.lang.Object)
              at zh3.a(r8-map-id-0123:40)
            "DefaultDispatcher-worker-2" daemon prio=5 tid=19 TimedWaiting (+1 with the same stack)
              at jdk.internal.misc.Unsafe.park(Native method)
              - waiting on an unknown object
              at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:376)
            "thread" daemon prio=5 tid=21 Native
              at java.net.SocketInputStream.socketRead0(Native method)
            """.trimIndent(),
            ExitReasons.anrStacks(anrDump.lineSequence()),
        )
    }

    @Test
    fun aDumpWithNoMainThreadSaysSoAndShowsNothingOfIt() {
        val dump = "some other format\nwith a line\n\n\"worker\" prio=5 tid=2 Native\n  at a.b(c:1)"
        assertEquals("(no main thread in the dump: 5 lines, 1 threads)", ExitReasons.anrStacks(dump.lineSequence()))
    }

    @Test
    fun aDumpWithoutStacksCountsWhereTheThreadsWaitInTheKernel() {
        // Android 14, the runtime did not answer the dump request in time (emulator, 2026-11-19).
        val dump = """
            Subject: Broadcast of Intent { act=android.intent.action.MY_PACKAGE_REPLACED flg=0x10400010 cmp=com.vpn4tv.hiddify/com.vpn4tv.app.bg.BootReceiver }
            RssHwmKb: 166208

            --- CriticalEventLog ---
            capacity: 20
            events <
              anr <
                process: "com.other.app"
                subject: "No response to onStartJob"
              >
            >

            ----- dumping pid: 26789 at 184812982
            libdebuggerd_client: failed to read status response from tombstoned: timeout reached?

            ----- Waiting Channels: pid 26789 at 2026-11-19 00:12:12.900312781+0000 -----
            Cmd line: com.vpn4tv.hiddify

            sysTid=26789     ptrace_stop
            sysTid=26798     do_sigtimedwait
            sysTid=26801     futex_wait
            sysTid=26802     futex_wait
            sysTid=26806     binder_ioctl_write_read
            sysTid=26812     futex_wait

            ----- end 26789 -----
        """.trimIndent()
        assertEquals(
            "(no main thread in the dump: 26 lines, 0 threads)\n" +
                "kernel wait channels: main=ptrace_stop, futex_wait x3, do_sigtimedwait x1, binder_ioctl_write_read x1",
            ExitReasons.anrStacks(dump.lineSequence()),
        )
    }

    @Test
    fun aDumpOfVeryManyThreadsStopsAddingThem() {
        val dump = buildString {
            append("\"main\" prio=5 tid=1 Native\n  at android.os.MessageQueue.nativePollOnce(Native method)\n\n")
            repeat(2000) { append("\"worker-$it\" prio=5 tid=${it + 2} Waiting\n  at pool.Worker$it.run(Worker.java:$it)\n\n") }
        }
        val kept = ExitReasons.anrStacks(dump.lineSequence())
        assertTrue(kept.startsWith("\"main\" prio=5 tid=1 Native\n  at android.os.MessageQueue.nativePollOnce(Native method)\n"))
        assertTrue(kept.length.toString(), kept.length < ExitReasons.ANR_TRACE_CHARS + 200)
        val leftOut = Regex("""\((\d+) more threads left out\)$""").find(kept)!!.groupValues[1].toInt()
        assertEquals(2000, leftOut + Regex("^\"worker-", RegexOption.MULTILINE).findAll(kept).count())
    }

    @Test
    fun theAnrsStacksEndTheReport() {
        val exits = listOf(ExitReasons.Exit(1_795_035_581_000, ApplicationExitInfo.REASON_ANR, 0, RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE, 67_374, 133_616, "bg anr"))
        val section = "anr-trace time=2026-11-18T20:59:41Z\n\"main\" prio=5 tid=1 Native\n  at android.os.MessageQueue.nativePollOnce(Native method)"
        val lines = ExitReasons.report(exits, null, "device ram=1843MB avail=210MB threshold=216MB low=false abi=armeabi-v7a", section).trimEnd().lines()
        assertEquals("app-exit reason=ANR importance=perceptible(230)", lines[0])
        assertEquals(
            "exit time=2026-11-18T20:59:41Z reason=ANR status=0 importance=perceptible(230) pss=67374kB rss=133616kB description=bg anr",
            lines[1],
        )
        assertEquals(
            listOf(
                "device ram=1843MB avail=210MB threshold=216MB low=false abi=armeabi-v7a",
                "anr-trace time=2026-11-18T20:59:41Z",
                "\"main\" prio=5 tid=1 Native",
                "  at android.os.MessageQueue.nativePollOnce(Native method)",
            ),
            lines.drop(2),
        )
    }
}
