package com.vpn4tv.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// The log screen's pause and search, as on the iPhone: a pause holds the list
// still while lines keep coming in, the search takes any case and works
// together with the level filter.
class LogFeedTest {
    private fun lines(range: IntRange) = range.map { "line $it" }

    @Test
    fun keepsTheNewestLinesOnly() {
        val feed = LogFeed().append(lines(1..400)).append(lines(401..700))
        assertEquals(LOG_LINES_KEPT, feed.all.size)
        assertEquals("line 201", feed.all.first())
        assertEquals("line 700", feed.all.last())
    }

    @Test
    fun aPauseHoldsTheListWhileLinesKeepComing() {
        val paused = LogFeed().append(lines(1..3)).pause()
        val later = paused.append(lines(4..6))
        assertTrue(later.paused)
        assertEquals(lines(1..3), later.shown)
        assertEquals(lines(1..6), later.all)
    }

    @Test
    fun resumeShowsEverythingThatCameMeanwhile() {
        val resumed = LogFeed().append(lines(1..3)).pause().append(lines(4..600)).resume()
        assertFalse(resumed.paused)
        assertEquals(lines(101..600), resumed.shown)
    }

    @Test
    fun aSecondPauseKeepsTheFirstSnapshot() {
        val paused = LogFeed().append(lines(1..2)).pause().append(lines(3..4))
        assertSame(paused, paused.pause())
        assertEquals(lines(1..2), paused.pause().shown)
    }

    @Test
    fun searchIgnoresCaseAndKeepsTheLevelFilter() {
        val log = listOf(
            "12:00:01 INFO dns: exchanged example.com",
            "12:00:02 WARN dns: lookup Example.COM failed",
            "12:00:03 ERROR outbound/vless: dial example.org",
            "12:00:04 DEBUG router: match example.com",
        )
        assertEquals(log.subList(1, 3), linesAtLevel(log, 2))
        assertEquals(listOf(log[1]), linesWith(linesAtLevel(log, 2), "EXAMPLE.com"))
        assertEquals(listOf(log[0], log[1], log[3]), linesWith(linesAtLevel(log, 0), "example.com"))
        assertEquals(emptyList<String>(), linesWith(log, "telegram"))
    }

    @Test
    fun anEmptyQueryKeepsEveryLine() {
        val log = lines(1..5)
        assertSame(log, linesWith(log, ""))
        assertSame(log, linesAtLevel(log, 0))
    }

    @Test
    fun marksEveryMatchInAnyCase() {
        assertEquals(listOf(0..2, 8..10), matchesIn("DNS via dns", "dns"))
        assertEquals(listOf(0..1, 2..3), matchesIn("aaaa", "aa"))
        assertEquals(emptyList<IntRange>(), matchesIn("router", "dns"))
        assertEquals(emptyList<IntRange>(), matchesIn("router", ""))
    }
}
