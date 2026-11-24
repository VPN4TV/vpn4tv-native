package com.vpn4tv.app.bg

import com.vpn4tv.app.outline.OutlineBridge
import org.json.JSONObject

/**
 * Whether a start can go on when some Outline dynamic keys could not be
 * fetched and their servers were left without a bridge
 * (OutlineDynamicKeys.dropUnresolved): the core still has their socks
 * outbounds, and every connection through one is refused.
 *
 * Until 5.6.8 the app said "Connected" then, with nothing working, when such a
 * server was the only one or the one chosen (support case of 2026-11-24). Now
 * the start stops with the reason instead. With another server to go through,
 * a start on "Auto" or on that server goes on, and the service fetches the
 * missing keys again through the tunnel.
 */
internal object OutlineKeyCheck {

    enum class Verdict {
        /** The chosen server has its bridge, or "Auto" has servers that do. */
        GO_ON,

        /** Every server of the profile is one left without a bridge. */
        NO_OTHER_SERVER,

        /** The chosen server is one of them, and others are there to choose. */
        PICK_ANOTHER,
    }

    /**
     * [config]: the core's config as it starts; [deadPorts]: the ports of the
     * endpoints left out; [saved]: the server the user chose (Servers screen),
     * null or "auto" for the automatic choice.
     */
    fun verdict(config: String, deadPorts: Set<Int>, saved: String?): Verdict {
        if (deadPorts.isEmpty()) return Verdict.GO_ON
        val outbounds = try {
            JSONObject(config).optJSONArray("outbounds")
        } catch (_: Exception) {
            null
        } ?: return Verdict.GO_ON
        val dead = HashSet<String>()
        var auto: List<String> = emptyList()
        var select: List<String> = emptyList()
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val tag = outbound.optString("tag")
            when (outbound.optString("type")) {
                "socks" -> if (outbound.optString("server") == OutlineBridge.SOCKS_HOST &&
                    outbound.optInt("server_port") in deadPorts
                ) dead.add(tag)
                "urltest" -> if (tag == "auto") auto = members(outbound)
                "selector" -> if (tag == "select") select = members(outbound)
            }
        }
        if (dead.isEmpty()) return Verdict.GO_ON
        val alive = auto.filter { it !in dead }
        val chosen = saved?.takeIf { it != "auto" && it in select } ?: "auto"
        return when {
            chosen == "auto" -> if (alive.isEmpty()) Verdict.NO_OTHER_SERVER else Verdict.GO_ON
            chosen in dead -> if (alive.isEmpty()) Verdict.NO_OTHER_SERVER else Verdict.PICK_ANOTHER
            else -> Verdict.GO_ON
        }
    }

    private fun members(group: JSONObject): List<String> {
        val list = group.optJSONArray("outbounds") ?: return emptyList()
        return (0 until list.length()).map { list.optString(it) }
    }
}
