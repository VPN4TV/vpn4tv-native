package com.vpn4tv.app.bg

import com.vpn4tv.app.bg.UsageMilestones.Counts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

// The days of use the tile and widget offers and the Play rating wait for.
class UsageMilestonesTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun aDayCountsOnceHoweverManyConnects() {
        var c = Counts()
        repeat(3) { c = UsageMilestones.connected(c, 100) }
        assertEquals(1, c.connectDays)
        assertEquals(1, c.cleanDays)
        c = UsageMilestones.connected(c, 101)
        assertEquals(2, c.connectDays)
        assertEquals(2, c.cleanDays)
    }

    @Test
    fun aFailedStartStartsTheCleanDaysAgainAndSpoilsItsDay() {
        var c = Counts()
        for (d in 1L..4L) c = UsageMilestones.connected(c, d)
        c = UsageMilestones.failed(c, 5)
        c = UsageMilestones.connected(c, 5)
        assertEquals(5, c.connectDays)
        assertEquals(0, c.cleanDays)
        c = UsageMilestones.connected(c, 6)
        assertEquals(1, c.cleanDays)
    }

    @Test
    fun theRatingWaitsForFiveCleanDaysAndHalfAYear() {
        var c = Counts()
        for (d in 1L..4L) c = UsageMilestones.connected(c, d)
        val now = 1_000L * day
        assertFalse(UsageMilestones.reviewDue(c, askedAt = 0, now = now))
        c = UsageMilestones.connected(c, 5)
        assertTrue(UsageMilestones.reviewDue(c, askedAt = 0, now = now))
        // Asked: five more clean days, and not within half a year.
        val asked = UsageMilestones.asked(c)
        assertFalse(UsageMilestones.reviewDue(asked, askedAt = now, now = now))
        var later = asked
        for (d in 6L..10L) later = UsageMilestones.connected(later, d)
        assertFalse(UsageMilestones.reviewDue(later, askedAt = now, now = now + 179 * day))
        assertTrue(UsageMilestones.reviewDue(later, askedAt = now, now = now + 180 * day))
    }

    @Test
    fun theDayIsTheLocalCalendarDay() {
        val moscow = TimeZone.getTimeZone("Europe/Moscow") // UTC+3
        // 2026-11-07 22:30 UTC is already 2026-11-08 in Moscow.
        val millis = java.util.GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(2026, java.util.Calendar.NOVEMBER, 7, 22, 30)
        }.timeInMillis
        assertEquals(UsageMilestones.localDay(millis, TimeZone.getTimeZone("UTC")) + 1, UsageMilestones.localDay(millis, moscow))
    }
}
