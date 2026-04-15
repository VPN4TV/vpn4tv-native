package com.vpn4tv.app.database

import android.os.Build
import androidx.room.Room
import com.vpn4tv.app.Application
import com.vpn4tv.app.BuildConfig

import com.vpn4tv.app.bg.ProxyService
import com.vpn4tv.app.bg.VPNService
import com.vpn4tv.app.constant.Path
import com.vpn4tv.app.constant.ServiceMode
import com.vpn4tv.app.constant.SettingsKey
import com.vpn4tv.app.database.preference.KeyValueDatabase
import com.vpn4tv.app.database.preference.RoomPreferenceDataStore
import com.vpn4tv.app.ktx.boolean
import com.vpn4tv.app.ktx.int
import com.vpn4tv.app.ktx.long
import com.vpn4tv.app.ktx.string
import com.vpn4tv.app.ktx.stringSet
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object Settings {
    @OptIn(DelicateCoroutinesApi::class)
    private val instance by lazy {
        Application.application.getDatabasePath(Path.SETTINGS_DATABASE_PATH).parentFile?.mkdirs()
        Room.databaseBuilder(
            Application.application,
            KeyValueDatabase::class.java,
            Path.SETTINGS_DATABASE_PATH,
        ).allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .enableMultiInstanceInvalidation()
            .setQueryExecutor { GlobalScope.launch { it.run() } }
            .build()
    }
    val dataStore = RoomPreferenceDataStore(instance.keyValuePairDao())
    var selectedProfile by dataStore.long(SettingsKey.SELECTED_PROFILE) { -1L }

    /**
     * Base port for the local xray SOCKS5 bridge. Randomized once on first
     * launch and persisted so scanners cannot rely on a fixed well-known port.
     * Range: 20000–59000 (high unprivileged, avoids common services).
     *
     * WARNING: the underlying PreferenceProxy re-evaluates the default lambda
     * on every read when the key is missing from the DB, which would produce
     * a *different* port each time. Use [ensureXrayPortBase] early in app
     * startup to persist a stable value before anyone reads it.
     */
    var xrayPortBase by dataStore.int(SettingsKey.XRAY_PORT_BASE) { 0 }

    /** Persist a stable xrayPortBase on first launch. Safe to call repeatedly. */
    fun ensureXrayPortBase() {
        if (xrayPortBase == 0) {
            xrayPortBase = (20000..59000).random()
        }
    }
    /**
     * Active runtime mode. Persisted directly from the Settings UI — the user
     * toggles "VPN" (default, full TUN) vs "Proxy" (sing-box as a local
     * SOCKS5 listener on 127.0.0.1:12334, no VPN permission needed). Legacy
     * sing-box-for-android auto-derived this from the profile content; we
     * let the user decide because the Proxy mode is specifically for devices
     * where the VPN consent dialog was stripped by the vendor.
     */
    var serviceMode by dataStore.string(SettingsKey.SERVICE_MODE) { ServiceMode.VPN }

    val isProxyMode: Boolean get() = serviceMode == ServiceMode.PROXY
    var startedByUser by dataStore.boolean(SettingsKey.STARTED_BY_USER)
    var autoConnectOnBoot by dataStore.boolean(SettingsKey.AUTO_CONNECT_ON_BOOT) { true }

    var updateSource by dataStore.string(SettingsKey.UPDATE_SOURCE) { "github" }
    var checkUpdateEnabled by dataStore.boolean(SettingsKey.CHECK_UPDATE_ENABLED) { false }
    var updateCheckPrompted by dataStore.boolean(SettingsKey.UPDATE_CHECK_PROMPTED) { false }
    var updateTrack by dataStore.string(SettingsKey.UPDATE_TRACK) {
        val versionName = BuildConfig.VERSION_NAME.lowercase()
        if (versionName.contains("-alpha") ||
            versionName.contains("-beta") ||
            versionName.contains("-rc")
        ) {
            "beta"
        } else {
            "stable"
        }
    }
    var silentInstallEnabled by dataStore.boolean(SettingsKey.SILENT_INSTALL_ENABLED) { false }
    var silentInstallMethod by dataStore.string(SettingsKey.SILENT_INSTALL_METHOD) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "PACKAGE_INSTALLER"
        } else {
            "SHIZUKU"
        }
    }
    var fdroidMirrorUrl by dataStore.string(SettingsKey.FDROID_MIRROR_URL) { "https://f-droid.org/repo" }
    var fdroidCustomMirrors by dataStore.stringSet(SettingsKey.FDROID_CUSTOM_MIRRORS) { emptySet() }
    var autoUpdateEnabled by dataStore.boolean(SettingsKey.AUTO_UPDATE_ENABLED) { false }
    var dynamicNotification by dataStore.boolean(SettingsKey.DYNAMIC_NOTIFICATION) { true }
    var disableDeprecatedWarnings by dataStore.boolean(SettingsKey.DISABLE_DEPRECATED_WARNINGS) { false }

    const val PER_APP_PROXY_DISABLED = 0
    const val PER_APP_PROXY_EXCLUDE = 1
    const val PER_APP_PROXY_INCLUDE = 2

    var autoRedirect by dataStore.boolean(SettingsKey.AUTO_REDIRECT) { false }
    var perAppProxyEnabled by dataStore.boolean(SettingsKey.PER_APP_PROXY_ENABLED) { false }
    var perAppProxyMode by dataStore.int(SettingsKey.PER_APP_PROXY_MODE) { PER_APP_PROXY_EXCLUDE }
    var perAppProxyList by dataStore.stringSet(SettingsKey.PER_APP_PROXY_LIST) { emptySet() }
    var perAppProxyManagedMode by dataStore.boolean(SettingsKey.PER_APP_PROXY_MANAGED_MODE) { false }
    var perAppProxyManagedList by dataStore.stringSet(SettingsKey.PER_APP_PROXY_MANAGED_LIST) { emptySet() }

    const val PACKAGE_QUERY_MODE_SHIZUKU = "SHIZUKU"
    const val PACKAGE_QUERY_MODE_ROOT = "ROOT"
    var perAppProxyPackageQueryMode by dataStore.string(SettingsKey.PER_APP_PROXY_PACKAGE_QUERY_MODE) { PACKAGE_QUERY_MODE_SHIZUKU }

    fun getEffectivePerAppProxyMode(): Int = if (perAppProxyManagedMode) {
        PER_APP_PROXY_EXCLUDE
    } else {
        perAppProxyMode
    }

    fun getEffectivePerAppProxyList(): Set<String> = if (perAppProxyManagedMode) {
        perAppProxyManagedList
    } else {
        perAppProxyList
    }

    var allowBypass by dataStore.boolean(SettingsKey.ALLOW_BYPASS) { false }
    var systemProxyEnabled by dataStore.boolean(SettingsKey.SYSTEM_PROXY_ENABLED) { true }

    var privilegeSettingsEnabled by dataStore.boolean(SettingsKey.PRIVILEGE_SETTINGS_ENABLED) { false }
    var privilegeSettingsList by dataStore.stringSet(SettingsKey.PRIVILEGE_SETTINGS_LIST) { emptySet() }
    var privilegeSettingsInterfaceRenameEnabled by dataStore.boolean(
        SettingsKey.PRIVILEGE_SETTINGS_INTERFACE_RENAME_ENABLED,
    ) { false }
    var privilegeSettingsInterfacePrefix by dataStore.string(SettingsKey.PRIVILEGE_SETTINGS_INTERFACE_PREFIX) { "wlan" }

    var oomKillerEnabled by dataStore.boolean(SettingsKey.OOM_KILLER_ENABLED) { false }
    var oomKillerDisabled by dataStore.boolean(SettingsKey.OOM_KILLER_DISABLED) { true }
    var oomMemoryLimitMB by dataStore.int(SettingsKey.OOM_MEMORY_LIMIT_MB) { 50 }

    var dashboardItemOrder by dataStore.string(SettingsKey.DASHBOARD_ITEM_ORDER) { "" }
    var dashboardDisabledItems by dataStore.stringSet(SettingsKey.DASHBOARD_DISABLED_ITEMS) { emptySet() }

    var cachedUpdateInfo by dataStore.string(SettingsKey.CACHED_UPDATE_INFO) { "" }
    var cachedApkPath by dataStore.string(SettingsKey.CACHED_APK_PATH) { "" }
    var lastShownUpdateVersion by dataStore.int(SettingsKey.LAST_SHOWN_UPDATE_VERSION) { 0 }

    fun serviceClass(): Class<*> = when (serviceMode) {
        ServiceMode.PROXY -> ProxyService::class.java
        else -> VPNService::class.java
    }
}
