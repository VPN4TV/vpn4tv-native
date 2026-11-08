package com.vpn4tv.app.ui

import java.util.Locale

/**
 * The country a server's name points at, as an ISO 3166 code: a flag in the
 * name ("🇳🇱 Amsterdam"), or the country code it starts with, as our own
 * servers are named ("US39 · 960663", "DE21N · 960663"), "NL-2", "UK 1",
 * "USA-3". Null for "auto" and every other name that gives no country.
 */
internal fun serverCountry(tag: String): String? {
    flagCountry(tag)?.let { return it }
    val name = tag.trim()
    LEADING_ALPHA2.find(name)?.groupValues?.get(1)?.let { lead ->
        val code = ALIASES[lead] ?: lead
        return code.takeIf { it in alpha2 && it !in NOT_COUNTRIES }
    }
    LEADING_ALPHA3.find(name)?.groupValues?.get(1)?.let { lead -> return alpha3[lead] }
    return null
}

/** The flag emoji of an ISO 3166 code: its two letters as regional indicator symbols. */
internal fun flagEmoji(country: String): String {
    val cc = country.uppercase(Locale.ROOT)
    return String(intArrayOf(REGIONAL_A + (cc[0] - 'A'), REGIONAL_A + (cc[1] - 'A')), 0, 2)
}

/** The first flag in [text] that is a country's. Code points one by one: String.codePoints() needs Android 7. */
private fun flagCountry(text: String): String? {
    var previous = -1
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        val letter = cp - REGIONAL_A
        if (letter !in 0..25) { previous = -1; continue }
        if (previous < 0) { previous = letter; continue }
        val code = "${'A' + previous}${'A' + letter}"
        if (code in alpha2) return code
        previous = -1
    }
    return null
}

private const val REGIONAL_A = 0x1F1E6

/** Two capitals that end there or before a digit, a space or a separator: "US39", "DE21N", "NL-2", "GB". */
private val LEADING_ALPHA2 = Regex("^([A-Z]{2})(?=[0-9\\s\\-_.·|,:/()\\[\\]]|$)")
private val LEADING_ALPHA3 = Regex("^([A-Z]{3})(?=[0-9\\s\\-_.·|,:/()\\[\\]]|$)")

/** Not ISO, but what server lists write. */
private val ALIASES = mapOf("UK" to "GB")

/** Two letters that start a server's name as a protocol more often than as a country: "SS-1" is Shadowsocks. */
private val NOT_COUNTRIES = setOf("SS")

private val alpha2: Set<String> by lazy { Locale.getISOCountries().toSet() }

private val alpha3: Map<String, String> by lazy {
    Locale.getISOCountries().mapNotNull { code ->
        runCatching { Locale("", code).isO3Country }.getOrNull()?.takeIf { it.length == 3 }?.let { it to code }
    }.toMap()
}
