package com.vpn4tv.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.vpn4tv.app.Application
import com.vpn4tv.app.BuildConfig
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val description: String,
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val APPCAST_URL = "https://bell.a4e.ar/vpn4tv-native-appcast.xml"

    private fun isPlayInstall(): Boolean {
        return try {
            val pm = Application.application.packageManager
            val pkg = Application.application.packageName
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg)
            }
            installer == "com.android.vending"
        } catch (e: Exception) {
            false
        }
    }

    fun check(): UpdateInfo? {
        // Play-installed copies receive updates via Play Core AppUpdateManager;
        // the appcast channel is for direct-APK distribution (bell.a4e.ar).
        // Running both paths on a Play install surfaces a download dialog that
        // tries to open a URL the system can't handle on locked-down TVs
        // (SecurityException in openDownload, vc50303 cluster).
        if (isPlayInstall()) {
            Log.d(TAG, "Play-installed copy — skipping appcast check")
            return null
        }
        return try {
            val xml = URL(APPCAST_URL).readText()
            val update = parseAppcast(xml) ?: return null

            if (update.versionCode > BuildConfig.VERSION_CODE) {
                Log.d(TAG, "Update available: ${update.versionName} (${update.versionCode})")
                update
            } else {
                Log.d(TAG, "Up to date: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    private fun parseAppcast(xml: String): UpdateInfo? {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var versionName = ""
        var versionCode = 0
        var downloadUrl = ""
        var description = ""
        var inItem = false
        var currentTag = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "item") inItem = true
                    if (currentTag == "enclosure" && inItem) {
                        downloadUrl = parser.getAttributeValue(null, "url")
                            ?: parser.getAttributeValue("http://www.andymatuschak.org/appcast/", "url")
                            ?: ""
                        val vc = parser.getAttributeValue("http://www.andymatuschak.org/appcast/", "version")
                            ?: parser.getAttributeValue(null, "sparkle:version") ?: "0"
                        versionCode = vc.toIntOrNull() ?: 0
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inItem) {
                        when (currentTag) {
                            "sparkle:shortVersionString", "title" -> {
                                if (versionName.isEmpty()) versionName = parser.text.trim()
                            }
                            "description" -> description = parser.text.trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item") inItem = false
                    currentTag = ""
                }
            }
            parser.next()
        }

        return if (downloadUrl.isNotEmpty() && versionCode > 0) {
            UpdateInfo(versionName, versionCode, downloadUrl, description)
        } else null
    }

    fun openDownload(context: Context, update: UpdateInfo) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "openDownload failed (no browser?): ${e.message}")
        }
    }
}
