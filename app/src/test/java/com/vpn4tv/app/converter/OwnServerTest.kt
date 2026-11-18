package com.vpn4tv.app.converter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The note that TCL's guard ended the VPN goes to our key server only, never to another provider's. */
class OwnServerTest {
    @Test
    fun ourServerAndItsMirror() {
        assertTrue(HwidService.ownServer("https://api.vpn4tv.com/sub/abc"))
        assertTrue(HwidService.ownServer("https://API.VPN4TV.COM/sub/abc"))
        assertTrue(HwidService.ownServer("https://bell.a4e.ar/sub/abc"))
    }

    @Test
    fun anyoneElse() {
        assertFalse(HwidService.ownServer("https://example.com/sub/abc"))
        assertFalse(HwidService.ownServer("https://api.vpn4tv.com.example.com/sub"))
        assertFalse(HwidService.ownServer("https://example.com/api.vpn4tv.com/sub"))
        assertFalse(HwidService.ownServer("not a link"))
    }

    @Test
    fun theHeaderName() {
        assertEquals("x-vpn4tv-vpn-ended", HwidService.VPN_ENDED_HEADER)
    }
}
