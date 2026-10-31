package com.vpn4tv.app.bg

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * One start or reload of the core at a time, and one reload for a burst of
 * requests.
 *
 * Reload requests come from the refresh buttons, the refresh at launch, the
 * background worker and the FakeDNS and tun-guard self-checks, each through
 * the command server on its own thread. Unserialized, two of them ran
 * StartOrReloadService at once and the core marked a half-started instance as
 * started (crash group 9acc602755, 5.3.0).
 *
 * Every request takes a ticket. A start or reload notes the newest ticket
 * before it reads the profile from disk; once it has run, those requests are
 * served, since their callers wrote what they wanted applied before asking. A
 * request that is already served when its turn comes returns without
 * reloading.
 */
internal class ReloadGate(private val coalesceMillis: Long) {

    private val mutex = Mutex()
    private val issued = AtomicLong(0)

    @Volatile
    private var served = 0L

    /** A start: it reads the profile itself, so it serves every request made before it began. */
    suspend fun <T> start(block: suspend () -> T): T = mutex.withLock {
        val upTo = issued.get()
        try {
            block()
        } finally {
            serve(upTo)
        }
    }

    /**
     * Ask for a reload. Waits [coalesceMillis] first, so a burst arrives
     * before the first of it reads the profile; then runs [reload] unless a
     * start or reload that began after this request has run meanwhile.
     * Returns whether [reload] ran. Either way, a start or reload that read
     * the profile after this request has finished by the time it returns.
     */
    suspend fun reload(reload: suspend () -> Unit): Boolean {
        val ticket = issued.incrementAndGet()
        if (coalesceMillis > 0) delay(coalesceMillis)
        return mutex.withLock {
            if (served >= ticket) return@withLock false
            val upTo = issued.get()
            try {
                reload()
            } finally {
                serve(upTo)
            }
            true
        }
    }

    private fun serve(upTo: Long) {
        if (upTo > served) served = upTo
    }
}
