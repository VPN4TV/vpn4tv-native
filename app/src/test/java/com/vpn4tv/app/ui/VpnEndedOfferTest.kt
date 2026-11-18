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

    @Test
    fun afterTclsGuardTheSettingIsExplainedEvenWithTheRestartOn() {
        val now = 1_795_000_000_000L
        assertTrue(VpnEndedOffer.dueTclGuard(restartOn = false, vpnRunning = false, lastOfferedAt = 0L, now = now))
        // The restart is on, yet the VPN is off: the guard kept it from working.
        assertTrue(VpnEndedOffer.dueTclGuard(restartOn = true, vpnRunning = false, lastOfferedAt = 0L, now = now))
        // The restart brought the VPN back: nothing to say.
        assertFalse(VpnEndedOffer.dueTclGuard(restartOn = true, vpnRunning = true, lastOfferedAt = 0L, now = now))
        // The user connected by hand with the restart off: still worth the explanation.
        assertTrue(VpnEndedOffer.dueTclGuard(restartOn = false, vpnRunning = true, lastOfferedAt = 0L, now = now))
        // Once a week, like the other offers.
        assertFalse(VpnEndedOffer.dueTclGuard(restartOn = false, vpnRunning = false, lastOfferedAt = now - week + 1, now = now))
        assertTrue(VpnEndedOffer.dueTclGuard(restartOn = false, vpnRunning = false, lastOfferedAt = now - week, now = now))
    }
}
