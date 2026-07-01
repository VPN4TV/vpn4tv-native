package com.vpn4tv.app.utils

/**
 * Result of an appcast update check. The data holder lives in `main` (HomeScreen
 * uses it as UI state), but the checker that PRODUCES it — UpdateChecker — is
 * flavor-specific: the real appcast+APK-download implementation ships only in
 * the `direct` source set; the `play` source set ships a no-op (Google Play
 * Device & Network Abuse policy forbids self-updating from outside Play).
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val description: String,
)
