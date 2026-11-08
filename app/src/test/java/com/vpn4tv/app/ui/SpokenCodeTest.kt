package com.vpn4tv.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TalkBack says the bot's code digit by digit, the groups apart as on the screen. */
class SpokenCodeTest {
    @Test
    fun digitsOneByOneGroupsApart() {
        assertEquals("1, 2 3 4, 5 6 7, 8 9 0", spokenCode("1 234 567 890"))
    }

    @Test
    fun extraSpacesChangeNothing() {
        assertEquals("1, 2 3 4", spokenCode("  1   234 "))
        assertEquals("4 2", spokenCode("42"))
        assertEquals("", spokenCode(""))
    }
}
