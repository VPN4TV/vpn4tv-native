package com.vpn4tv.app.lan

import android.content.Context
import android.util.Log
import com.vpn4tv.app.converter.ConfigGenerator
import com.vpn4tv.app.converter.HwidService
import com.vpn4tv.app.converter.ProxyParser
import com.vpn4tv.app.database.Profile
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.database.TypedProfile
import com.vpn4tv.app.ui.isGenericProxyTag
import java.io.File

/**
 * Shared "text → profile" import used by the LAN configurator. Accepts the
 * same inputs as the Telegram flow's processConfigs: a subscription URL
 * (https://…), one or more direct proxy links (vless://, naive+https://, …),
 * or a wg-quick / .conf body. Fetches subscriptions through HwidService so
 * the DNS-fallback + mirror chain applies, then parses, generates, and
 * creates the profile, selecting it as active.
 *
 * Returns Result.success(profileName) or Result.failure with a user-facing
 * message (surfaced verbatim in the phone browser).
 */
object ProfileImporter {
    private const val TAG = "ProfileImporter"

    /** Suspends; does blocking network + disk I/O and touches the Room-backed
     *  ProfileManager (whose accessors are suspend). Call from a coroutine. */
    suspend fun importFromText(context: Context, raw: String): Result<String> {
        return try {
            val allContent = StringBuilder()
            var remoteUrl = ""
            var headerTitle: String? = null
            var headerUpdateHours: Int? = null

            // Treat each non-blank line independently: a URL is fetched, a
            // direct link is taken verbatim. Mirrors processConfigs.
            for (line in raw.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                if (line.startsWith("http://") || line.startsWith("https://")) {
                    val sub = HwidService.fetchSubscription(context, line)
                    allContent.appendLine(sub.body)
                    if (remoteUrl.isEmpty()) {
                        remoteUrl = line
                        headerTitle = sub.title
                        headerUpdateHours = sub.updateIntervalHours
                    }
                } else {
                    allContent.appendLine(line)
                }
            }

            val content = allContent.toString().trim()
            if (content.isEmpty()) return Result.failure(Exception("Пустой ключ"))

            val proxies = ProxyParser.parseSubscription(content)
            if (proxies.isEmpty()) {
                return Result.failure(Exception("Не найдено ни одного сервера в ключе"))
            }

            val profilesDir = File(context.filesDir, "profiles")
            profilesDir.mkdirs()
            val nextId = ProfileManager.nextFileID()
            val configPath = File(profilesDir, "$nextId.json").absolutePath

            val result = ConfigGenerator.generateFull(proxies)
            ConfigGenerator.writeAll(configPath, result)

            val inlineTitle = content.lines()
                .firstOrNull { it.startsWith("#profile-title:") }
                ?.substringAfter("#profile-title:")?.trim()
            val urlHost = if (remoteUrl.isNotEmpty()) {
                try { java.net.URL(remoteUrl).host } catch (_: Exception) { "" }
            } else ""
            val firstProxy = proxies.firstOrNull()
            val firstHost = firstProxy?.server?.trim().orEmpty()
            val firstTag = firstProxy?.tag?.trim().orEmpty()
            val baseName = when {
                !headerTitle.isNullOrBlank() -> headerTitle
                !inlineTitle.isNullOrBlank() -> inlineTitle
                urlHost.isNotEmpty() -> urlHost
                firstTag.isNotEmpty() && !isGenericProxyTag(firstTag) -> firstTag
                firstHost.isNotEmpty() && firstTag.isNotEmpty() -> "$firstTag ($firstHost)"
                firstHost.isNotEmpty() -> firstHost
                firstTag.isNotEmpty() -> firstTag
                else -> "Subscription"
            }
            val profileName = ProfileManager.uniqueName(baseName ?: "Subscription")

            val profile = ProfileManager.create(
                Profile(name = profileName, userOrder = ProfileManager.nextOrder()).apply {
                    typed.type = if (remoteUrl.isNotEmpty()) TypedProfile.Type.Remote else TypedProfile.Type.Local
                    typed.remoteURL = remoteUrl
                    typed.path = configPath
                    typed.autoUpdate = remoteUrl.isNotEmpty()
                    typed.autoUpdateInterval = headerUpdateHours?.let { (it.coerceAtLeast(1) * 60) } ?: 60
                    typed.lastUpdated = java.util.Date()
                }
            )
            Settings.selectedProfile = profile.id
            Log.d(TAG, "Imported ${profile.name}: ${proxies.size} proxies (selected)")
            Result.success(profileName)
        } catch (e: Exception) {
            Log.e(TAG, "import failed", e)
            Result.failure(e)
        }
    }
}
