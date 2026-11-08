package com.vpn4tv.app.bg

import com.vpn4tv.app.bg.QuickToggle.Offer
import com.vpn4tv.app.bg.QuickToggle.Step
import com.vpn4tv.app.constant.Status
import org.junit.Assert.assertEquals
import org.junit.Test

// A tap on the tile or the widget, weighed against the VPN's real state.
class QuickToggleTest {
    private val ready = QuickToggle.Facts(
        status = Status.Stopped, alwaysOn = false, hasKey = true, keyExpired = false,
        proxyMode = false, vpnPermission = true,
    )

    @Test
    fun connectStartsWithoutTheAppWhenNothingHasToBeAsked() {
        assertEquals(Step.Start, QuickToggle.decide(Offer.Connect, ready))
        // Proxy mode asks for no permission.
        assertEquals(Step.Start, QuickToggle.decide(Offer.Connect, ready.copy(proxyMode = true, vpnPermission = null)))
    }

    @Test
    fun connectGoesThroughTheAppWhenSomethingHasToBeAsked() {
        // Android's VPN permission dialog.
        assertEquals(Step.OpenApp, QuickToggle.decide(Offer.Connect, ready.copy(vpnPermission = false)))
        // Firmware without the dialog: the app says what to do.
        assertEquals(Step.OpenApp, QuickToggle.decide(Offer.Connect, ready.copy(vpnPermission = null)))
        // No key yet, or an expired one the app refreshes first.
        assertEquals(Step.OpenApp, QuickToggle.decide(Offer.Connect, ready.copy(hasKey = false)))
        assertEquals(Step.OpenApp, QuickToggle.decide(Offer.Connect, ready.copy(keyExpired = true)))
    }

    @Test
    fun disconnectStopsAndAlwaysOnLeavesItToAndroid() {
        val running = ready.copy(status = Status.Started)
        assertEquals(Step.Stop, QuickToggle.decide(Offer.Disconnect, running))
        assertEquals(Step.OpenApp, QuickToggle.decide(Offer.Disconnect, running.copy(alwaysOn = true)))
    }

    @Test
    fun aControlShowingAnOldStateIsOnlyDrawnAnew() {
        // Lit, but the VPN died with the old process: no connect behind the user's back.
        assertEquals(Step.Redraw, QuickToggle.decide(Offer.Disconnect, ready))
        // Off, but the VPN runs (started from the app meanwhile): no disconnect.
        assertEquals(Step.Redraw, QuickToggle.decide(Offer.Connect, ready.copy(status = Status.Started)))
    }

    @Test
    fun betweenTwoStatesATapDoesNothing() {
        for (status in listOf(Status.Starting, Status.Stopping)) {
            for (offer in Offer.values()) {
                assertEquals(Step.Redraw, QuickToggle.decide(offer, ready.copy(status = status)))
            }
        }
    }
}
