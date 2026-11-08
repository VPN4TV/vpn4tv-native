package com.vpn4tv.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.Settings
import java.util.UUID

/**
 * The shortcuts on a long press of the app's icon (phones and tablets; TV
 * launchers have none): Connect or Disconnect, as the VPN stands, then
 * Servers and New key. Google: at most four, short labels up to 10
 * characters and long ones up to 25, and usage reported whenever the same
 * thing is done inside the app. All dynamic, so Connect comes first (a
 * launcher puts manifest shortcuts before dynamic ones).
 *
 * The same intents open the app from the Quick Settings tile and the widget.
 * MainActivity is exported, so a connect or disconnect carries a token only
 * the app knows: another app cannot turn the VPN off by starting it.
 */
object AppShortcuts {
    private const val TAG = "AppShortcuts"

    const val ACTION_CONNECT = "com.vpn4tv.action.CONNECT"
    const val ACTION_DISCONNECT = "com.vpn4tv.action.DISCONNECT"
    const val ACTION_SERVERS = "com.vpn4tv.action.SERVERS"
    const val ACTION_ADD_KEY = "com.vpn4tv.action.ADD_KEY"
    private const val EXTRA_TOKEN = "com.vpn4tv.extra.TOKEN"

    /** A connect to this server of the key [EXTRA_PROFILE] ("auto" too): a card of the TV home screen's channel. */
    const val EXTRA_SERVER = "com.vpn4tv.extra.SERVER"
    const val EXTRA_PROFILE = "com.vpn4tv.extra.PROFILE"

    const val ID_CONNECT = "connect"
    const val ID_DISCONNECT = "disconnect"
    const val ID_SERVERS = "servers"
    const val ID_NEW_KEY = "new_key"

    /** Which of Connect and Disconnect the menu offers; none under always-on VPN (Android turns it off). */
    fun toggleId(status: Status, alwaysOn: Boolean): String? = when {
        status != Status.Started -> ID_CONNECT
        alwaysOn -> null
        else -> ID_DISCONNECT
    }

    /** The menu, top first. */
    fun wantedIds(status: Status, alwaysOn: Boolean): List<String> =
        listOfNotNull(toggleId(status, alwaysOn), ID_SERVERS, ID_NEW_KEY)

    private fun supported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && !Device.isTv

    /**
     * The whole menu, while the app is in front (Android limits how often a
     * background app may change it). Only what differs is published again:
     * a new language changes the labels.
     */
    fun publish(context: Context) {
        if (!supported()) return
        try {
            val wanted = wantedIds(currentStatus(), BoxService.alwaysOn.value != null).map { build(context, it) }
            fun ShortcutInfoCompat.key() = listOf(id, shortLabel.toString(), longLabel?.toString(), rank.toString())
            val current = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.key() }.toSet()
            if (current != wanted.map { it.key() }.toSet()) ShortcutManagerCompat.setDynamicShortcuts(context, wanted)
        } catch (e: Exception) {
            // IllegalStateException while the user is locked, a rate limit, a launcher quirk.
            Log.w(TAG, "shortcuts not published: ${e.message}")
        }
    }

    /** The VPN changed state, possibly with the app in the background: only Connect and Disconnect swap. */
    fun syncToggle(context: Context) {
        if (!supported()) return
        try {
            val wanted = toggleId(currentStatus(), BoxService.alwaysOn.value != null)
            val current = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }
            val stale = listOf(ID_CONNECT, ID_DISCONNECT).filter { it in current && it != wanted }
            // Rank 0: at the top again. A new id, not an update: its meaning changed.
            // Pushed first: under the background rate limit (Android 7.1–10)
            // the push fails, and the old toggle stays rather than none (a
            // Connect while connected does nothing); publish() mends the menu
            // once the app is in front.
            val pushed = wanted == null || wanted in current ||
                ShortcutManagerCompat.pushDynamicShortcut(context, build(context, wanted))
            if (pushed && stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
        } catch (e: Exception) {
            Log.w(TAG, "connect shortcut not updated: ${e.message}")
        }
    }

    /** The user did in the app what a shortcut does: the launcher ranks shortcuts by it. */
    fun reportUsed(context: Context, id: String) {
        if (!supported()) return
        try {
            ShortcutManagerCompat.reportShortcutUsed(context.applicationContext, id)
        } catch (e: Exception) {
            Log.w(TAG, "shortcut use not reported: ${e.message}")
        }
    }

    /**
     * MainActivity with [action] and the token. Every quick action carries it:
     * Add a key also stops a running VPN (the bot's polls need the plain
     * network), so another app must not reach it through the exported activity.
     */
    fun intent(context: Context, action: String): Intent =
        Intent(action).setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_TOKEN, token())

    /** Connect to [server] of the key [profileId], with the token: a card of the TV home screen's channel. */
    fun connectIntent(context: Context, server: String, profileId: Long): Intent =
        intent(context, ACTION_CONNECT).putExtra(EXTRA_SERVER, server).putExtra(EXTRA_PROFILE, profileId)

    /** A quick action from the app's own shortcut, tile, widget or TV channel. */
    fun trusted(intent: Intent): Boolean {
        val given = intent.getStringExtra(EXTRA_TOKEN) ?: return false
        val own = Settings.quickActionToken
        return own.isNotEmpty() && given == own
    }

    /** The shortcut a quick action stands for, to report its use. */
    fun idFor(action: String?): String? = when (action) {
        ACTION_CONNECT -> ID_CONNECT
        ACTION_DISCONNECT -> ID_DISCONNECT
        ACTION_SERVERS -> ID_SERVERS
        ACTION_ADD_KEY -> ID_NEW_KEY
        else -> null
    }

    @Synchronized
    private fun token(): String {
        val saved = Settings.quickActionToken
        if (saved.isNotEmpty()) return saved
        val fresh = UUID.randomUUID().toString()
        Settings.quickActionToken = fresh
        return fresh
    }

    private fun currentStatus() = BoxService.globalStatus.value ?: Status.Stopped

    private fun build(context: Context, id: String): ShortcutInfoCompat {
        val (action, short, long, icon) = when (id) {
            ID_CONNECT -> Spec(ACTION_CONNECT, R.string.action_connect, R.string.shortcut_connect_long, R.drawable.ic_shortcut_power)
            ID_DISCONNECT -> Spec(ACTION_DISCONNECT, R.string.action_disconnect, R.string.shortcut_disconnect_long, R.drawable.ic_shortcut_power)
            ID_SERVERS -> Spec(ACTION_SERVERS, R.string.nav_servers, R.string.shortcut_servers_long, R.drawable.ic_shortcut_servers)
            else -> Spec(ACTION_ADD_KEY, R.string.shortcut_new_key, R.string.shortcut_new_key_long, R.drawable.ic_shortcut_add)
        }
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(short))
            .setLongLabel(context.getString(long))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(intent(context, action))
            .setRank(wantedRank(id))
            .build()
    }

    private fun wantedRank(id: String) = when (id) {
        ID_CONNECT, ID_DISCONNECT -> 0
        ID_SERVERS -> 1
        else -> 2
    }

    private data class Spec(val action: String, val short: Int, val long: Int, val icon: Int)
}
