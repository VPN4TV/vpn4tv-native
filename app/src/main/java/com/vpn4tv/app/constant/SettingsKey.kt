package com.vpn4tv.app.constant

object SettingsKey {
    const val SELECTED_PROFILE = "selected_profile"
    const val XRAY_PORT_BASE = "xray_port_base"
    const val SERVICE_MODE = "service_mode"
    const val DYNAMIC_NOTIFICATION = "dynamic_notification"
    const val DISABLE_DEPRECATED_WARNINGS = "disable_deprecated_warnings"

    const val AUTO_REDIRECT = "auto_redirect"
    const val PER_APP_PROXY_ENABLED = "per_app_proxy_enabled"
    const val PER_APP_PROXY_MODE = "per_app_proxy_mode"
    const val PER_APP_PROXY_LIST = "per_app_proxy_list"
    const val PER_APP_PROXY_MANAGED_MODE = "per_app_proxy_managed_mode"
    const val PER_APP_PROXY_MANAGED_LIST = "per_app_proxy_managed_list"
    const val PER_APP_PROXY_PACKAGE_QUERY_MODE = "per_app_proxy_package_query_mode"

    const val AUTO_CONNECT_ON_BOOT = "auto_connect_on_boot"
    const val ALLOW_BYPASS = "allow_bypass"
    const val BYPASS_LAN = "bypass_lan"
    const val SYSTEM_PROXY_ENABLED = "system_proxy_enabled"
    // Key-versioned so a default flip re-defaults EVERY install (renaming
    // orphans the old persisted value → the new default applies to all):
    //  - "fake_dns"    5.1.0/5.1.1: default ON, but hung connect on some nets.
    //  - "fake_dns_v2" 5.1.3: default OFF, to escape the persisted-true hangs.
    //  - "fake_dns_v3" 5.1.9: default ON again. FakeDNS resolves the sniffed
    //    domain REMOTELY through the tunnel, so the ISP never sees the query —
    //    the strongest bypass for the 2026-07 RKN DNS purge. Safe now because
    //    fakeDnsAutoDisabled (post-connect health check) turns it off on nets
    //    where the fakeip path is broken — the protection 5.1.2 lacked.
    const val FAKE_DNS = "fake_dns_v3"

    const val PRIVILEGE_SETTINGS_ENABLED = "hide_settings_enabled"
    const val PRIVILEGE_SETTINGS_LIST = "hide_settings_list"
    const val PRIVILEGE_SETTINGS_INTERFACE_RENAME_ENABLED = "hide_settings_interface_rename_enabled"
    const val PRIVILEGE_SETTINGS_INTERFACE_PREFIX = "hide_settings_interface_prefix"

    // OOM killer
    const val OOM_KILLER_ENABLED = "oom_killer_enabled"
    const val OOM_KILLER_DISABLED = "oom_killer_disabled"
    const val OOM_MEMORY_LIMIT_MB = "oom_memory_limit_mb"

    // dashboard
    const val DASHBOARD_ITEM_ORDER = "dashboard_item_order"
    const val DASHBOARD_DISABLED_ITEMS = "dashboard_disabled_items"

    // cache
    const val STARTED_BY_USER = "started_by_user"
}
