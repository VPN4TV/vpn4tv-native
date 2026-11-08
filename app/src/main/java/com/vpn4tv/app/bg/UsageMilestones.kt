package com.vpn4tv.app.bg

import com.vpn4tv.app.database.Settings
import java.util.TimeZone

/**
 * Days of use, for what waits for them: the offers of the Quick Settings
 * tile and of the home-screen widget, and the request for a rating in
 * Google Play. Counted from every start, whatever started it.
 */
object UsageMilestones {
    /** The rating is asked after this many days with a connect and no failed start in between. */
    const val CLEAN_DAYS_FOR_REVIEW = 5

    /** And not again within this time (Google Play has its own limit on top). */
    const val REVIEW_INTERVAL_MS = 180L * 24 * 60 * 60 * 1000

    /** The widget is offered once the VPN has been connected on this many different days. */
    const val CONNECT_DAYS_FOR_WIDGET = 3

    data class Counts(
        /** Days with at least one connect, ever. */
        val connectDays: Int = 0,
        val lastConnectDay: Long = -1,
        /** Days with a connect since the last failed start or the last rating request. */
        val cleanDays: Int = 0,
        /** A failed start marks its day too: a connect later that day does not make it clean. */
        val lastCleanDay: Long = -1,
    )

    fun connected(counts: Counts, today: Long): Counts {
        var c = counts
        if (c.lastConnectDay != today) c = c.copy(connectDays = c.connectDays + 1, lastConnectDay = today)
        if (c.lastCleanDay != today) c = c.copy(cleanDays = c.cleanDays + 1, lastCleanDay = today)
        return c
    }

    fun failed(counts: Counts, today: Long): Counts = counts.copy(cleanDays = 0, lastCleanDay = today)

    /** [askedAt]: when the rating was last asked for, 0 if never. */
    fun reviewDue(counts: Counts, askedAt: Long, now: Long): Boolean =
        counts.cleanDays >= CLEAN_DAYS_FOR_REVIEW && (askedAt == 0L || now - askedAt >= REVIEW_INTERVAL_MS)

    /** The rating was asked for: the clean days count from zero again. */
    fun asked(counts: Counts): Counts = counts.copy(cleanDays = 0)

    /** The local calendar day of [millis], as days since 1970-01-01. */
    fun localDay(millis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Math.floorDiv(millis + zone.getOffset(millis), 24L * 60 * 60 * 1000)

    fun record(outcome: BoxService.StartOutcome, now: Long = System.currentTimeMillis()) {
        val today = localDay(now)
        val counts = load()
        save(
            when (outcome) {
                BoxService.StartOutcome.Connected -> connected(counts, today)
                BoxService.StartOutcome.Failed -> failed(counts, today)
            },
        )
    }

    fun load(): Counts = Counts(
        connectDays = Settings.usageConnectDays,
        lastConnectDay = Settings.usageLastConnectDay,
        cleanDays = Settings.usageCleanDays,
        lastCleanDay = Settings.usageLastCleanDay,
    )

    fun save(counts: Counts) {
        Settings.usageConnectDays = counts.connectDays
        Settings.usageLastConnectDay = counts.lastConnectDay
        Settings.usageCleanDays = counts.cleanDays
        Settings.usageLastCleanDay = counts.lastCleanDay
    }
}
