package com.vpn4tv.app.bg

import org.junit.Assert.assertEquals
import org.junit.Test

// "Подключено к серверу FR10T": the server the notification names is the one Home shows.
class ServiceNotificationTextTest {

    @Test
    fun onAutoTheServerIsTheAutoGroupsPick() {
        assertEquals("NL2", ServiceNotification.activeServer("auto", "NL2"))
        // Before the auto group has picked one, "auto" itself.
        assertEquals("auto", ServiceNotification.activeServer("auto", null))
        assertEquals("auto", ServiceNotification.activeServer("auto", ""))
    }

    @Test
    fun aServerChosenByHandIsShownAsChosen() {
        assertEquals("FR10T", ServiceNotification.activeServer("FR10T", "NL2"))
    }
}
