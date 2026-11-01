package com.vpn4tv.app.converter

import org.junit.Assert.assertEquals
import org.junit.Test

/** x-vpn4tv-ru-vpn: the extra names our server keeps on the VPN. */
class RuDirectHeaderTest {
    @Test
    fun namesAreCleanedUp() {
        assertEquals(listOf("a.ru", "b.su", ".c.ru"), HwidService.parseNameList(" A.ru , b.su,,.c.ru "))
        assertEquals(listOf("xn--p1ai"), HwidService.parseNameList("xn--p1ai"))
    }

    @Test
    fun anythingElseIsDropped() {
        assertEquals(emptyList<String>(), HwidService.parseNameList(null))
        assertEquals(emptyList<String>(), HwidService.parseNameList("  "))
        assertEquals(listOf("ok.ru"), HwidService.parseNameList("ok.ru,bad name.ru,\"q\".ru,a/b.ru,..,x..y.ru,${"a".repeat(254)}"))
        assertEquals(listOf("dup.ru"), HwidService.parseNameList("dup.ru,DUP.ru"))
        assertEquals(500, HwidService.parseNameList((1..600).joinToString(",") { "n$it.ru" }).size)
    }
}
