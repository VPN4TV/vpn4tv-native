package com.vpn4tv.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The country behind the TV's server list comes from the server's name. */
class ServerCountryTest {
    @Test
    fun ourServersStartWithTheCountryCode() {
        assertEquals("US", serverCountry("US39 · 960663"))
        assertEquals("GB", serverCountry("GB85 · 960663"))
        assertEquals("DE", serverCountry("DE21N · 960663"))
        assertEquals("AL", serverCountry("AL67 · 960663"))
    }

    @Test
    fun otherListsWriteItInOtherWays() {
        assertEquals("NL", serverCountry("🇳🇱 Amsterdam"))
        assertEquals("FI", serverCountry("Helsinki 🇫🇮 2"))
        assertEquals("NL", serverCountry("NL-2"))
        assertEquals("NL", serverCountry("NL"))
        assertEquals("GB", serverCountry("UK 1"))
        assertEquals("US", serverCountry("USA-3"))
        assertEquals("DE", serverCountry("DEU 1"))
    }

    @Test
    fun namesWithoutACountryGiveNone() {
        assertNull(serverCountry("auto"))
        assertNull(serverCountry("AUTO"))
        assertNull(serverCountry("vless"))
        assertNull(serverCountry("Server 1"))
        assertNull(serverCountry("SS-1"))
        assertNull(serverCountry("ZZ1"))
        assertNull(serverCountry("VPN 2"))
        assertNull(serverCountry(""))
        // A flag that is no country's (the EU's) is not one.
        assertNull(serverCountry("🇪🇺 Europe"))
    }

    @Test
    fun theFlagIsTheTwoRegionalLetters() {
        assertEquals("🇩🇪", flagEmoji("DE"))
        assertEquals("🇺🇸", flagEmoji("us"))
        assertEquals("DE", serverCountry(flagEmoji("DE")))
    }
}
