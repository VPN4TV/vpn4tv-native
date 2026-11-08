package com.vpn4tv.app.utils

import org.json.JSONObject

/**
 * What the logs may hold (Google's core app quality, Sensitive_Data_Logging:
 * no personal or sensitive user data in the system log or in an app's own
 * log). Keys, the links they come from and the sites the VPN carries stay
 * out of logcat, of the Logs screen and of the file Share sends.
 *
 * - The core's level is warn in release builds. At info it writes a line per
 *   connection with the site's name ("outbound connection to youtube.com:443"),
 *   per DNS answer, the app that opened it and the Wi-Fi network's name. The
 *   core gives its log readers every line whatever the level, with the level
 *   beside them, and the app cuts below it (CommandClient). Debug builds keep
 *   info, as before.
 * - The Xray bridge logs to the process's stdout (its access log, on by
 *   default, has a line per site): release builds run it at warning with the
 *   access log off, so a device that sends stdout to logcat gets no sites and
 *   the bridge formats no line per connection.
 * - The core's error lines still name the site that failed; the Logs screen
 *   takes the name out ([redactCoreLine]).
 * - A link in a line for logcat keeps its scheme and host ([link]).
 */
object LogPrivacy {
    /** The core's log level: info in debug builds, warn otherwise. */
    fun coreLevel(debug: Boolean): String = if (debug) "info" else "warn"

    /** The sing-box config with the log level of this build; the rest as it was. */
    fun applyCoreLevel(config: String, debug: Boolean): String {
        val root = JSONObject(config)
        val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
        val level = coreLevel(debug)
        if (log.optString("level") == level) return config
        log.put("level", level)
        return root.toString()
    }

    /**
     * The Xray config of a release build: errors and warnings only, no access
     * log (it is on by default and goes to stdout). Debug builds keep what the
     * generator wrote (debug).
     */
    fun applyXrayLog(config: String, debug: Boolean): String {
        if (debug) return config
        val root = JSONObject(config)
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
            put("access", "none")
            put("dnsLog", false)
        })
        return root.toString()
    }

    private const val HIDDEN = "…"

    // The site in the core's lines: "connection to <site>" (open, inbound,
    // outbound, packet), "to fake destination <name>:", DNS failures for a
    // name, and lookups (the core's and Go's "lookup <name> on <server>").
    // Info lines of debug builds besides: DNS answers ("exchanged A <name> …"),
    // the app a connection came from and the Wi-Fi network.
    private val connectionTo = Regex("""(connection to )\S+""")
    private val fakeDestination = Regex("""(to fake destination )[^\s:]+""")
    private val dnsFailure = Regex("""((?:exchange failed for|response rejected for) )[^\s:]+""")
    private val lookup = Regex("""\b(lookup (?:(?:failed|succeed|rejected|missed) for )?)[^\s:]+""")
    private val dnsAnswer = Regex("""\b((?:exchanged|cached|optimistic|refreshed|rejected) [A-Z0-9]+ )\S.*""")
    private val appOrNetwork = Regex("""((?:found package name|found process path|WIFI state changed): ).*""")

    /** A line of the core's log without the name or address of the site it is about. */
    fun redactCoreLine(line: String): String {
        if (!line.contains("connection to") && !line.contains("fake destination") &&
            !line.contains(" for ") && !line.contains("lookup ") && !line.contains("dns: ") &&
            !line.contains("found ") && !line.contains("WIFI ")
        ) return line
        var out = connectionTo.replace(line, "$1$HIDDEN")
        out = fakeDestination.replace(out, "$1$HIDDEN")
        out = dnsFailure.replace(out, "$1$HIDDEN")
        out = lookup.replace(out, "$1$HIDDEN")
        out = dnsAnswer.replace(out, "$1$HIDDEN")
        out = appOrNetwork.replace(out, "$1$HIDDEN")
        return out
    }

    // scheme://[user@]host[:port][path?query#fragment]
    private val anyLink = Regex("""\b([a-zA-Z][a-zA-Z0-9+.\-]*)://([^\s/?#@"'<>]*@)?([^\s/?#:"'<>]*)[^\s"'<>]*""")

    /**
     * [url] for a log line: http and https keep the host (which server was
     * asked), every other scheme only its name — a vless://, ss:// or
     * ssconf:// link is a key, server included.
     */
    fun link(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        val match = anyLink.find(url) ?: return HIDDEN
        return shorten(match)
    }

    /** [text] (an error message, say) with every link in it cut as [link] cuts it. */
    fun redactLinks(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        if (!text.contains("://")) return text
        return anyLink.replace(text) { shorten(it) }
    }

    // Exceptions that quote what they failed to read: Android's JSONException
    // ("… at character 12 of {the whole text}", "Value {text} of type …") and
    // URISyntaxException ("… at index 7: {the link}").
    private val jsonEcho = Regex("""( at character \d+ of ).*""", RegexOption.DOT_MATCHES_ALL)
    private val jsonValue = Regex("""^(Value ).*?( of type )""", RegexOption.DOT_MATCHES_ALL)
    private val uriEcho = Regex("""( at index \d+: ).*""", RegexOption.DOT_MATCHES_ALL)

    /**
     * A failure around a key or a config, for a log line: the exception's
     * type and its message without the text it quotes (links cut as [link]
     * cuts them, the JSON or link a parser quotes left out), at most 300
     * characters. Not the stack trace: printed whole, it has the raw message.
     */
    fun error(t: Throwable?): String {
        if (t == null) return ""
        var message = redactLinks(t.message)
        message = jsonEcho.replace(message, "$1$HIDDEN")
        message = jsonValue.replace(message, "$1$HIDDEN$2")
        message = uriEcho.replace(message, "$1$HIDDEN")
        return "${t.javaClass.simpleName}: ${message.take(300)}"
    }

    private fun shorten(match: MatchResult): String {
        val scheme = match.groupValues[1]
        val host = match.groupValues[3]
        return if (scheme.equals("http", true) || scheme.equals("https", true)) {
            "$scheme://$host/$HIDDEN"
        } else {
            "$scheme://$HIDDEN"
        }
    }
}
