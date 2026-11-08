package com.vpn4tv.app.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickConnectCardsTest {
    private val ours = listOf(
        "US39 · 960663", "US78 · 960663", "GB85 · 960663", "DE92 · 960663", "DE21 · 960663",
        "NL1 · 960663", "FI2 · 960663", "SE3 · 960663", "PL4 · 960663",
    )

    @Test
    fun autoFirstThenOneCardPerCountryInTheKeysOrder() {
        val cards = quickConnectCards(ours, emptySet())
        assertEquals(AUTO, cards.first().id)
        assertEquals(
            listOf("country:US", "country:GB", "country:DE", "country:NL", "country:FI", "country:SE"),
            cards.drop(1).map { it.id },
        )
        // The first server of each country is the one connected.
        assertEquals("US39 · 960663", cards[1].server)
        assertEquals("DE92 · 960663", cards[3].server)
    }

    @Test
    fun atMostSixBesidesAuto() {
        assertEquals(1 + MAX_QUICK_CARDS, quickConnectCards(ours, emptySet()).size)
    }

    @Test
    fun aRemovedCardStaysAwayAndTheNextCountryTakesItsPlace() {
        val cards = quickConnectCards(ours, setOf(AUTO, "country:GB"))
        assertTrue(cards.none { it.id == AUTO || it.id == "country:GB" })
        assertEquals("country:PL", cards.last().id)
    }

    @Test
    fun aServerWhoseNameGivesNoCountryIsACardOfItsOwn() {
        val cards = quickConnectCards(listOf("Fast server", "🇩🇪 Berlin", "Fast server"), emptySet())
        assertEquals(listOf(AUTO, "server:Fast server", "country:DE"), cards.map { it.id })
        assertEquals(null, cards[1].country)
    }

    @Test
    fun noServersNoCards() {
        assertTrue(quickConnectCards(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun theSelectorsServersWithoutTheGroups() {
        val config = """
            {"outbounds": [
              {"type": "urltest", "tag": "auto", "outbounds": ["US39", "DE21"]},
              {"type": "selector", "tag": "select", "outbounds": ["auto", "US39", "DE21"], "default": "auto"},
              {"type": "vless", "tag": "US39"},
              {"type": "vless", "tag": "DE21"},
              {"type": "direct", "tag": "direct"}
            ]}
        """.trimIndent()
        assertEquals(listOf("US39", "DE21"), selectorServers(config))
    }

    @Test
    fun aConfigWithoutASelectorOrBrokenGivesNothing() {
        assertTrue(selectorServers("""{"outbounds": [{"type": "vless", "tag": "x"}]}""").isEmpty())
        assertTrue(selectorServers("not json").isEmpty())
    }
}
