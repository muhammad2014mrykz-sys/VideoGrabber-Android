package com.videograbber.app.core

/**
 * Cleans arbitrary pasted/shared text into ONE downloadable URL.
 *
 * Real-world share text (especially copied from Arabic / RTL apps like Kwai)
 * arrives as a promo template with the link buried inside, tracking params
 * appended, and — critically — invisible bidi/zero-width control characters
 * glued into the URL. Those invisibles make a link "look correct" while being
 * silently broken, which is a common reason downloads fail.
 *
 * yt-dlp itself follows short-link redirects, so we only need to hand it a
 * clean URL; we do not resolve redirects here.
 */
object LinkResolver {

    // Zero-width, bidi embedding/override, isolates, BOM, soft hyphen, ALM.
    private val INVISIBLE = Regex(
        "[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u2060\\uFEFF\\u00AD\\u061C]"
    )

    private val URL = Regex("https?://[^\\s<>\"'\\\\]+", RegexOption.IGNORE_CASE)

    // A scheme-less domain+path (e.g. "k.kwai.com/p/XXX"); requires a path so
    // bare emails/domains are ignored. Not preceded by a word char or '@'.
    private val BARE = Regex(
        "(?<![\\w@.])((?:[a-z0-9-]+\\.)+[a-z]{2,}/[^\\s<>\"'\\\\]+)",
        RegexOption.IGNORE_CASE
    )

    // Latin + Arabic punctuation that clings to the end of a pasted link.
    private val TRAILING = ".,،؛:;!?؟)]}\"'»«".toCharArray()

    // Query keys that are pure share/tracking noise (safe to drop).
    private val TRACKING = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "igshid", "igsh", "gclid", "mibextid", "si", "feature",
        "share_uid", "share_device_id", "share_id", "share_item_type",
        "share_item_info", "sharepage", "fromshare", "is_from_webapp",
        "is_copy_url", "sender_device", "cc", "gid", "kpn", "subbiz",
        "apptype", "timestamp", "language",
    )

    /** Return the first clean, downloadable URL in [raw], or null if none. */
    fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = INVISIBLE.replace(raw, "")

        URL.find(text)?.let { return normalize(it.value) }
        BARE.find(text)?.let { return normalize("https://" + it.value) }
        return null
    }

    private fun normalize(rawUrl: String): String {
        var url = INVISIBLE.replace(rawUrl, "").trim()
        // strip trailing punctuation and unmatched closing brackets
        while (url.isNotEmpty() && url.last() in TRAILING) {
            url = url.dropLast(1)
        }
        return stripTracking(url)
    }

    private fun stripTracking(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val base = url.substring(0, q)
        val rest = url.substring(q + 1)
        val fragment = rest.substringAfter('#', "")
        val query = rest.substringBefore('#')
        val kept = query.split('&').filter { part ->
            val key = part.substringBefore('=').lowercase()
            key.isNotEmpty() && key !in TRACKING && !key.startsWith("utm_")
        }
        val rebuilt = if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
        return if (fragment.isNotEmpty()) "$rebuilt#$fragment" else rebuilt
    }
}
