package com.vpn4tv.app.tv

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vpn4tv.app.R
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.ui.AppShortcuts
import com.vpn4tv.app.ui.Device
import com.vpn4tv.app.ui.MainActivity
import com.vpn4tv.app.ui.flagEmoji
import com.vpn4tv.app.ui.serverCountry
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * A card of the "Quick connect" channel: [AUTO], one country (the first of its
 * servers in the key, [server]) or a server whose name gives no country. [id]
 * stays the same across updates of the key: the program's internal id, and
 * what the user removed from the home screen is remembered by it.
 */
internal data class QuickCard(val id: String, val server: String, val country: String?)

internal const val AUTO = "auto"

/** The most countries (or servers) the channel shows besides Auto, as the proposal asks: 5–6. */
internal const val MAX_QUICK_CARDS = 6

/**
 * The cards for a key's [servers] (in the key's order): Auto first, then one per
 * country, the first server of each, up to [max]; a server whose name gives no
 * country is a card of its own. None without servers; nothing the user
 * removed ([removed] ids) comes back.
 */
internal fun quickConnectCards(servers: List<String>, removed: Set<String>, max: Int = MAX_QUICK_CARDS): List<QuickCard> {
    if (servers.isEmpty()) return emptyList()
    val cards = mutableListOf<QuickCard>()
    if (AUTO !in removed) cards += QuickCard(AUTO, AUTO, null)
    val seen = mutableSetOf<String>()
    var shown = 0
    for (tag in servers) {
        if (shown >= max) break
        val country = serverCountry(tag)
        val id = if (country != null) "country:$country" else "server:$tag"
        if (!seen.add(id) || id in removed) continue
        cards += QuickCard(id, tag, country)
        shown++
    }
    return cards
}

/**
 * The servers of a saved sing-box config, in the order the Servers screen lists
 * them: the outbounds of the selector "select" (any selector in a config of the
 * user's own), without the groups ("auto"). Empty when there is none.
 */
internal fun selectorServers(configJson: String): List<String> {
    val outbounds = runCatching { JSONObject(configJson).optJSONArray("outbounds") }.getOrNull() ?: return emptyList()
    val groups = mutableSetOf<String>()
    var selector: JSONObject? = null
    for (i in 0 until outbounds.length()) {
        val outbound = outbounds.optJSONObject(i) ?: continue
        val type = outbound.optString("type")
        if (type != "selector" && type != "urltest") continue
        groups += outbound.optString("tag")
        if (type == "selector" && (selector == null || outbound.optString("tag") == "select")) selector = outbound
    }
    val list = selector?.optJSONArray("outbounds") ?: return emptyList()
    return (0 until list.length()).mapNotNull { list.optString(it).takeIf { tag -> tag.isNotEmpty() && tag !in groups } }
        .distinct()
}

/**
 * Android TV's home screen (the Android TV launcher, Android 8+): a channel,
 * "Quick connect", with Auto and the countries of the selected key; a card
 * opens the app and connects to that server (Home, as if started from the
 * launcher: Back leads out). The channel is the app's default one, shown
 * without asking. Not the app's name, no prices, no term of the key (Google's
 * TV checklist for channels). Google TV shows no app channels, and its Engage
 * SDK takes only apps with video content; nothing is written on a phone.
 *
 * Kept in step when the app goes to the background, after the keys are
 * loaded at launch and after a key update in the background (a job, as
 * Google asks for updates from the background); written only when what it
 * shows changed, as the provider may revoke an app that floods it. A card the
 * user removed from the home screen is not put back.
 */
object QuickConnectChannel {
    private const val TAG = "QuickConnectChannel"
    private const val CHANNEL_KEY = "quick_connect"
    private const val PREFS = "tv_channel"
    private const val KEY_REMOVED = "removed"
    private const val KEY_SIGNATURE = "signature"
    private const val WORK = "QuickConnectChannel"

    /** Bump when the card pictures change: the launcher caches every picture by its address. */
    private const val ART_VERSION = 4

    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Device.isTv &&
            runCatching { context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null }
                .getOrDefault(false)

    /** Brings the channel up to date in the background, once, whatever asked first. */
    fun requestSync(context: Context) {
        if (!supported(context)) return
        try {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWork>().build(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "sync not scheduled: ${e.message}")
        }
    }

    class SyncWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            sync(applicationContext)
            return Result.success()
        }
    }

    /** The user removed a card from the home screen: gone for good (Google: delete it, never put it back). */
    fun programRemoved(context: Context, programId: Long) {
        if (!supported(context) || programId <= 0) return
        try {
            val resolver = context.contentResolver
            val program = resolver.query(TvContractCompat.buildPreviewProgramUri(programId), null, null, null, null)
                ?.use { if (it.moveToFirst()) PreviewProgram.fromCursor(it) else null } ?: return
            // Only a card of this app's channel (the query sees no other app's programs).
            val channelId = findChannel(resolver)?.id ?: return
            if (program.channelId != channelId) return
            program.internalProviderId?.let { remember(context, it) }
            resolver.delete(TvContractCompat.buildPreviewProgramUri(programId), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "removed card not handled: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun sync(context: Context) {
        if (!supported(context)) return
        try {
            sync0(context)
        } catch (e: Exception) {
            // A box whose provider refuses (SecurityException), a launcher quirk.
            Log.w(TAG, "channel not updated: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun sync0(context: Context) {
        val resolver = context.contentResolver
        val profileId = Settings.selectedProfile
        val profile = if (profileId != -1L) ProfileManager.get(profileId) else null
        val servers = profile?.typed?.path?.takeIf { it.isNotEmpty() }
            ?.let { path -> runCatching { selectorServers(File(path).readText()) }.getOrDefault(emptyList()) }
            .orEmpty()

        var channel = findChannel(resolver)
        // Programs the user removed meanwhile (the launcher marks them unbrowsable).
        val existing = mutableMapOf<String, Long>()
        if (channel != null) {
            resolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channel.id), null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val program = PreviewProgram.fromCursor(cursor)
                    val id = program.internalProviderId ?: continue
                    if (!program.isBrowsable) {
                        remember(context, id)
                        resolver.delete(TvContractCompat.buildPreviewProgramUri(program.id), null, null)
                    } else {
                        existing[id] = program.id
                    }
                }
            }
        }
        val removed = prefs(context).getStringSet(KEY_REMOVED, emptySet()).orEmpty()
        val cards = quickConnectCards(servers, removed)
        if (channel == null) {
            // No key yet: no channel yet. The first one the app makes is its
            // default channel, on the home screen at once.
            if (cards.isEmpty()) return
            channel = createChannel(context) ?: return
        }
        val name = context.getString(R.string.channel_quick_connect)
        if (channel.displayName != name) {
            // The device's language changed (the user's doing).
            resolver.update(
                TvContractCompat.buildChannelUri(channel.id),
                Channel.Builder(channel).setDisplayName(name).build().toContentValues(), null, null,
            )
        }

        val signature = listOf(profileId, Locale.getDefault().toLanguageTag(), ART_VERSION, cards).toString()
        val prefs = prefs(context)
        if (prefs.getString(KEY_SIGNATURE, null) == signature && existing.keys == cards.map { it.id }.toSet()) return

        cards.forEachIndexed { index, card ->
            val values = program(context, channel.id, profileId, card, weight = cards.size - index).toContentValues()
            val id = existing[card.id]
            val programId = if (id != null) {
                resolver.update(TvContractCompat.buildPreviewProgramUri(id), values, null, null)
                id
            } else {
                resolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, values)?.let(ContentUris::parseId)
            }
            Log.d(TAG, "card ${card.id}: program $programId")
        }
        val wanted = cards.map { it.id }.toSet()
        existing.filterKeys { it !in wanted }.values.forEach {
            resolver.delete(TvContractCompat.buildPreviewProgramUri(it), null, null)
        }
        prefs.edit().putString(KEY_SIGNATURE, signature).apply()
        // Pictures of an older drawing, no card points at any longer.
        ChannelArtProvider.dir(context).listFiles()?.filter { !it.name.endsWith("-v$ART_VERSION.png") }?.forEach { it.delete() }
        Log.i(TAG, "channel: ${cards.size} card(s), shown on the home screen: ${channel.isBrowsable}")
    }

    /** This app's channel; the provider shows an app only its own. */
    private fun findChannel(resolver: ContentResolver): Channel? =
        resolver.query(TvContractCompat.Channels.CONTENT_URI, Channel.PROJECTION, null, null, null)?.use { cursor ->
            var found: Channel? = null
            while (cursor.moveToNext()) {
                val channel = Channel.fromCursor(cursor)
                if (channel.internalProviderId == CHANNEL_KEY) { found = channel; break }
            }
            found
        }

    private fun createChannel(context: Context): Channel? {
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(context.getString(R.string.channel_quick_connect))
            .setAppLinkIntentUri(Uri.parse(open.toUri(Intent.URI_INTENT_SCHEME)))
            .setInternalProviderId(CHANNEL_KEY)
            .build()
        val uri = context.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()) ?: return null
        val id = ContentUris.parseId(uri)
        ChannelLogoUtils.storeChannelLogo(context, id, logo(context))
        // The app's first channel: the default one, which the system shows without asking.
        TvContractCompat.requestChannelBrowsable(context, id)
        return context.contentResolver.query(TvContractCompat.buildChannelUri(id), Channel.PROJECTION, null, null, null)
            ?.use { if (it.moveToFirst()) Channel.fromCursor(it) else null }
    }

    private fun program(context: Context, channelId: Long, profileId: Long, card: QuickCard, weight: Int): PreviewProgram {
        val (title, description) = when {
            card.id == AUTO -> context.getString(R.string.channel_auto) to context.getString(R.string.channel_auto_desc)
            card.country != null -> countryName(context, card.country) to context.getString(R.string.channel_server, card.server)
            else -> card.server to context.getString(R.string.channel_server_desc)
        }
        // The app's own connect, with its token (AppShortcuts): another app
        // starting the activity with a server cannot connect it.
        val intent = AppShortcuts.connectIntent(context, card.server, profileId)
        return PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setTitle(title)
            .setDescription(description)
            .setPosterArtUri(art(context, card))
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntentUri(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)))
            .setInternalProviderId(card.id)
            .setWeight(weight)
            .setSearchable(false)
            .build()
    }

    /**
     * The country's name in the language of the channel's other words: the
     * device's when the app speaks it, English (the app's fallback) otherwise;
     * on a Spanish TV the cards said "Alemania" next to "Auto (best)".
     */
    private fun countryName(context: Context, country: String): String {
        val device = androidx.core.os.ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
        val language = if (device.language in APP_LANGUAGES) device else Locale.ENGLISH
        return Locale("", country).getDisplayCountry(language).ifEmpty { country }
    }

    /** The languages the app's strings are in (build.gradle.kts, localeFilters). */
    private val APP_LANGUAGES = setOf("en", "ru", "fa", "zh")

    private fun remember(context: Context, id: String) {
        val prefs = prefs(context)
        val removed = prefs.getStringSet(KEY_REMOVED, emptySet()).orEmpty()
        if (id !in removed) prefs.edit().putStringSet(KEY_REMOVED, removed + id).remove(KEY_SIGNATURE).apply()
    }

    // Not backed up (only the files backup_rules.xml lists are): the channel is this device's.
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- pictures

    /** The card's picture, made once on the device and served by [ChannelArtProvider]. */
    private fun art(context: Context, card: QuickCard): Uri {
        val name = when {
            card.id == AUTO -> "auto"
            card.country != null -> "country-${card.country.lowercase(Locale.ROOT)}"
            else -> "server"
        } + "-v$ART_VERSION.png"
        val file = File(ChannelArtProvider.dir(context), name)
        if (!file.isFile) {
            val bitmap = when {
                card.id == AUTO -> glyphCard(context, R.drawable.ic_shortcut_power_fg, BRAND)
                card.country != null -> countryCard(card.country)
                else -> glyphCard(context, R.drawable.ic_shortcut_servers_fg, CARD_BACKGROUND)
            }
            val tmp = File(file.parentFile, "$name.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(file)
            bitmap.recycle()
        }
        return ChannelArtProvider.uri(context, name)
    }

    /** The country's flag, large, on the TV screens' dark grey; its letters where the fonts have no such flag. */
    private fun countryCard(country: String): Bitmap {
        val bitmap = Bitmap.createBitmap(ART_WIDTH, ART_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(CARD_BACKGROUND)
        val flag = flagEmoji(country)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        if (paint.hasGlyph(flag)) {
            paint.textSize = ART_HEIGHT * 0.62f
        } else {
            paint.textSize = ART_HEIGHT * 0.42f
            paint.color = Color.WHITE
            paint.typeface = Typeface.DEFAULT_BOLD
        }
        val text = if (paint.hasGlyph(flag)) flag else country.uppercase(Locale.ROOT)
        val y = ART_HEIGHT / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, ART_WIDTH / 2f, y, paint)
        return bitmap
    }

    /** One of the app's glyphs, white, in the middle of a card of [background]. */
    private fun glyphCard(context: Context, glyph: Int, background: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(ART_WIDTH, ART_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val drawable = ContextCompat.getDrawable(context, glyph)?.mutate()
        if (drawable != null) {
            DrawableCompat.setTint(drawable, Color.WHITE)
            // The glyph fills a third of its 108 dp canvas: about half the card's height.
            val side = (ART_HEIGHT * 1.6f).toInt()
            val left = (ART_WIDTH - side) / 2
            val top = (ART_HEIGHT - side) / 2
            drawable.setBounds(left, top, left + side, top + side)
            drawable.draw(canvas)
        }
        return bitmap
    }

    /** The channel's logo: the app's mark on its purple, opaque (the launcher masks it round). */
    private fun logo(context: Context): Bitmap {
        val bitmap = Bitmap.createBitmap(LOGO_SIZE, LOGO_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BRAND)
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.let {
            it.setBounds(0, 0, LOGO_SIZE, LOGO_SIZE)
            it.draw(canvas)
        }
        return bitmap
    }

    // 512×288: the country card draws its flag as an emoji at 62 % of the
    // height, and Android 9 does not draw a colour emoji above about 256 px:
    // at 1024×576 (5.6.0 build 1) the cards came out empty. The logo is a
    // vector and stays sharp at 320.
    private const val ART_WIDTH = 512
    private const val ART_HEIGHT = 288
    private const val LOGO_SIZE = 320
    private val BRAND = Color.rgb(0x6A, 0x3D, 0xE8)
    private val CARD_BACKGROUND = Color.rgb(0x25, 0x25, 0x25)
}
