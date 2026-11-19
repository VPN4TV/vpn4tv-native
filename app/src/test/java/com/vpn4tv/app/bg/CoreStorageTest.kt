package com.vpn4tv.app.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CoreStorageTest {
    private val internal = File("/data/user/0/com.vpn4tv.hiddify/files")
    private val external = File("/storage/emulated/0/Android/data/com.vpn4tv.hiddify/files")

    @Test
    fun theExternalFolderIsUsedWhenItAnswersInTime() {
        val late = AtomicInteger()
        val dir = CoreStorage.choose(internal, timeoutMs = 5_000, onLate = { late.incrementAndGet() }) { external }
        assertSame(external, dir)
        assertEquals(0, late.get())
    }

    @Test
    fun theProbeRunsOnAThreadOfItsOwn() {
        val asking = Thread.currentThread()
        var probing: Thread? = null
        CoreStorage.choose(internal, timeoutMs = 5_000, onLate = {}) { probing = Thread.currentThread(); external }
        assertTrue(probing != null && probing !== asking)
        assertEquals("external-storage-probe", probing!!.name)
        // It must not keep the process alive, nor be waited for.
        assertTrue(probing!!.isDaemon)
    }

    @Test
    fun noExternalFolderToWriteToMeansInternalAndNothingIsRemembered() {
        // Not there or read-only today may be there tomorrow: the next start asks again.
        val late = AtomicInteger()
        val dir = CoreStorage.choose(internal, timeoutMs = 5_000, onLate = { late.incrementAndGet() }) { null }
        assertSame(internal, dir)
        assertEquals(0, late.get())
    }

    @Test
    fun storageThatDoesNotAnswerMeansInternalForGoodAndTheStartGoesOn() {
        // getExternalFilesDir that never returns: the two devices of November 2026.
        val release = CountDownLatch(1)
        val late = AtomicInteger()
        val started = System.nanoTime()
        try {
            val dir = CoreStorage.choose(internal, timeoutMs = 200, onLate = { late.incrementAndGet() }) {
                release.await()
                external
            }
            val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertSame(internal, dir)
            assertEquals(1, late.get())
            assertTrue("waited $waitedMs ms", waitedMs in 200..5_000)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun anAnswerThatComesAfterTheLimitIsNotUsed() {
        val release = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val dir = CoreStorage.choose(internal, timeoutMs = 100, onLate = {}) {
            release.await()
            answered.countDown()
            external
        }
        release.countDown()
        assertTrue(answered.await(5, TimeUnit.SECONDS))
        assertSame(internal, dir)
    }

    @Test
    fun aProbeThatFailsMeansInternalAndNothingIsRemembered() {
        var late = false
        val dir = CoreStorage.choose(internal, timeoutMs = 5_000, onLate = { late = true }) {
            throw SecurityException("no access to external storage")
        }
        assertSame(internal, dir)
        assertFalse(late)
    }
}
