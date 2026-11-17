package com.vpn4tv.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnEndedOfferTest {
    private val week = VpnEndedOffer.WEEK_MS
    private val now = 100 * week

    @Test
    fun `offered when never offered and nothing brings the VPN back`() {
        assertTrue(VpnEndedOffer.due(alwaysOn = false, restartOn = false, lastOfferedAt = 0L, now = now))
    }

    @Test
    fun `not when always-on or the restart already brings the VPN back`() {
        assertFalse(VpnEndedOffer.due(alwaysOn = true, restartOn = false, lastOfferedAt = 0L, now = now))
        assertFalse(VpnEndedOffer.due(alwaysOn = false, restartOn = true, lastOfferedAt = 0L, now = now))
    }

    @Test
    fun `once a week at most`() {
        assertFalse(VpnEndedOffer.due(alwaysOn = false, restartOn = false, lastOfferedAt = now - week + 1, now = now))
        assertTrue(VpnEndedOffer.due(alwaysOn = false, restartOn = false, lastOfferedAt = now - week, now = now))
        // A clock set back after an offer: offered again rather than never.
        assertTrue(VpnEndedOffer.due(alwaysOn = false, restartOn = false, lastOfferedAt = now + 1, now = now))
    }
}
