package com.vpn4tv.app.bg

import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings

object VpnConnectHelper {

    /** Must run on a background thread — ProfileDatabase forbids main-thread access. */
    fun ensureSelectedProfileBlocking(): Boolean {
        val profiles = ProfileManager.listBlocking()
        if (profiles.isEmpty()) return false
        if (Settings.selectedProfile == -1L) {
            Settings.selectedProfile = profiles.first().id
        }
        return ProfileManager.getBlocking(Settings.selectedProfile) != null
    }

    fun hasProfilesBlocking(): Boolean = ProfileManager.listBlocking().isNotEmpty()
}
