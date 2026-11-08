package com.vpn4tv.app.ui

import com.vpn4tv.app.R
import com.vpn4tv.app.bg.TileLook
import com.vpn4tv.app.constant.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The long-press menu of the app's icon, and the tile's look.
class AppShortcutsTest {

    @Test
    fun connectOrDisconnectComesFirstAsTheVpnStands() {
        assertEquals(
            listOf(AppShortcuts.ID_CONNECT, AppShortcuts.ID_SERVERS, AppShortcuts.ID_NEW_KEY),
            AppShortcuts.wantedIds(Status.Stopped, alwaysOn = false),
        )
        assertEquals(
            listOf(AppShortcuts.ID_DISCONNECT, AppShortcuts.ID_SERVERS, AppShortcuts.ID_NEW_KEY),
            AppShortcuts.wantedIds(Status.Started, alwaysOn = false),
        )
        // Between two states the menu keeps Connect: a tap then does nothing.
        assertEquals(AppShortcuts.ID_CONNECT, AppShortcuts.toggleId(Status.Starting, alwaysOn = false))
        assertEquals(AppShortcuts.ID_CONNECT, AppShortcuts.toggleId(Status.Stopping, alwaysOn = false))
    }

    @Test
    fun alwaysOnVpnHasNoDisconnect() {
        assertNull(AppShortcuts.toggleId(Status.Started, alwaysOn = true))
        assertEquals(listOf(AppShortcuts.ID_SERVERS, AppShortcuts.ID_NEW_KEY), AppShortcuts.wantedIds(Status.Started, alwaysOn = true))
    }

    @Test
    fun atMostFourShortcuts() {
        for (status in Status.values()) for (alwaysOn in listOf(false, true)) {
            assertTrue(AppShortcuts.wantedIds(status, alwaysOn).size <= 4)
        }
    }

    @Test
    fun everyQuickActionNamesItsShortcut() {
        assertEquals(AppShortcuts.ID_CONNECT, AppShortcuts.idFor(AppShortcuts.ACTION_CONNECT))
        assertEquals(AppShortcuts.ID_DISCONNECT, AppShortcuts.idFor(AppShortcuts.ACTION_DISCONNECT))
        assertEquals(AppShortcuts.ID_SERVERS, AppShortcuts.idFor(AppShortcuts.ACTION_SERVERS))
        assertEquals(AppShortcuts.ID_NEW_KEY, AppShortcuts.idFor(AppShortcuts.ACTION_ADD_KEY))
        assertNull(AppShortcuts.idFor("android.intent.action.VIEW"))
        assertNull(AppShortcuts.idFor(null))
    }

    @Test
    fun theTileIsLitWhileConnectingAndConnectedAndNamesTheServerOnlyThen() {
        assertEquals(TileLook(true, R.string.status_connected, "FR10T"), TileLook.of(Status.Started, "FR10T"))
        assertEquals(TileLook(true, R.string.status_connected, null), TileLook.of(Status.Started, " "))
        assertEquals(TileLook(true, R.string.status_connecting, null), TileLook.of(Status.Starting, "FR10T"))
        assertFalse(TileLook.of(Status.Stopping, null).lit)
        assertEquals(TileLook(false, R.string.status_disconnected, null), TileLook.of(Status.Stopped, "FR10T"))
    }
}
