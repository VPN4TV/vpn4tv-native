package com.vpn4tv.app.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickyRestartTest {
    private val hour = StickyRestart.WINDOW_MS
    private val now = 10 * hour

    @Test
    fun `a start the user made is not counted, a restart by Android is`() {
        assertEquals(emptyList<Long>(), StickyRestart.recent(emptyList(), now, restartedBySystem = false))
        assertEquals(listOf(now), StickyRestart.recent(emptyList(), now, restartedBySystem = true))
    }

    @Test
    fun `restarts older than an hour drop out`() {
        val kept = StickyRestart.recent(listOf(now - hour - 1, now - hour + 1), now, restartedBySystem = true)
        assertEquals(listOf(now - hour + 1, now), kept)
    }

    @Test
    fun `the third restart within an hour trips it, two do not`() {
        val two = StickyRestart.recent(listOf(now - 50 * 60_000L), now, restartedBySystem = true)
        assertFalse(StickyRestart.tripped(two))
        val three = StickyRestart.recent(listOf(now - 50 * 60_000L, now - 10 * 60_000L), now, restartedBySystem = true)
        assertTrue(StickyRestart.tripped(three))
    }

    @Test
    fun `three restarts spread over more than an hour do not trip it`() {
        val kept = StickyRestart.recent(listOf(now - 2 * hour, now - 30 * 60_000L), now, restartedBySystem = true)
        assertFalse(StickyRestart.tripped(kept))
    }

    @Test
    fun `a clock set back does not count future restarts`() {
        assertEquals(listOf(now), StickyRestart.recent(listOf(now + 5_000L), now, restartedBySystem = true))
    }

    @Test
    fun `the saved list survives junk`() {
        assertEquals(listOf(1L, 3L), StickyRestart.parse("1,x,,3"))
        assertEquals(emptyList<Long>(), StickyRestart.parse(null))
    }
}
