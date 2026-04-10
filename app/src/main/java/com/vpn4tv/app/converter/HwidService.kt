package com.vpn4tv.app.converter

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object HwidService {
    private const val PREFS = "hwid"
    private const val KEY = "device_hwid"
    private var cached: String? = null

    fun getHwid(context: Context): String {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var hwid = prefs.getString(KEY, null)
        if (hwid.isNullOrEmpty()) {
            hwid = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY, hwid).apply()
        }
        cached = hwid
        return hwid
    }

    /**
     * Download subscription content with HWID and device headers.
     */
    fun downloadSubscription(context: Context, url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("x-hwid", getHwid(context))
        conn.setRequestProperty("x-device-os", "Android")
        conn.setRequestProperty("x-ver-os", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        conn.setRequestProperty("User-Agent", "VPN4TV-Native/0.1.0")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return conn.inputStream.bufferedReader().readText()
    }
}
