package com.vpn4tv.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a Telegram bot or channel ([domain]) in the Telegram app through tg://, and its web
 * address only when no app takes that link. An https://telegram.me link went to the browser on
 * some phones, and the site timed out there (support chat 2026-11-16: the bot button was a dead
 * end). The desktop and Apple clients open tg:// first the same way. [start]: the bot's start
 * parameter. A QR code stays an https link: the camera of another phone opens it.
 */
fun openTelegram(context: Context, domain: String, start: String? = null) {
    val app = Uri.parse("tg://resolve?domain=$domain" + (start?.let { "&start=${Uri.encode(it)}" } ?: ""))
    val web = Uri.parse("https://telegram.me/$domain" + (start?.let { "?start=${Uri.encode(it)}" } ?: ""))
    fun open(uri: Uri) = context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    try {
        open(app)
    } catch (_: RuntimeException) {
        // No Telegram app (ActivityNotFoundException) or a broken handler: the web address.
        runCatching { open(web) }
    }
}
