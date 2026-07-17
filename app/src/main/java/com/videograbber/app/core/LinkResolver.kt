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

    // Hosts that carry actual videos — preferred when several links are present.
    private val VIDEO_HOSTS = listOf(
        "kwai.com", "kwai.net", "kuaishou", "gifshow.com",
        "youtube.com", "youtu.be", "tiktok.com",
        "instagram.com", "instagr.am", "facebook.com", "fb.watch", "fb.com",
        "twitter.com", "x.com", "t.co", "snapchat.com",
        "pinterest.", "pin.it", "reddit.com", "redd.it",
        "twitch.tv", "dailymotion.com", "vimeo.com",
    )

    // App-store / app-download / attribution links that ride along in share
    // templates (e.g. "download the Kwai app") — never the video the user wants.
    private val BAD_HOSTS = listOf(
        "play.google.com", "apps.apple.com", "itunes.apple.com",
        "kwai-app", "onelink.me", "app.link", "adjust.com", "appsflyer",
        "s.kw.ai/app", "bit.ly/kwai",
    )

    // Path shapes that indicate a specific video rather than a homepage.
    private val VIDEO_PATH = Regex(
        "/(p|video|watch|reel|reels|status|shorts|v|embed|photo)/|/@",
        RegexOption.IGNORE_CASE
    )

    /**
     * Return the best downloadable video URL in [raw], or null if none.
     * Share text often contains a promo sentence + the video link + extra
     * links (app-store, "download our app"), so we extract ALL links and pick
     * the one most likely to be the actual video — not merely the first.
     */
    fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val candidates = extractAll(raw)
        if (candidates.isEmpty()) return null
        // Highest score wins; ties keep the earliest (extractAll preserves order).
        return candidates.maxByOrNull { score(it) }
    }

    private fun extractAll(raw: String): List<String> {
        val text = INVISIBLE.replace(raw, "")
        val urls = URL.findAll(text).map { normalize(it.value) }.toMutableList()
        if (urls.isEmpty()) {
            BARE.findAll(text).forEach { urls.add(normalize("https://" + it.value)) }
        }
        return urls.filter { it.length > "https://a.bc".length }.distinct()
    }

    private fun host(url: String): String =
        Regex("https?://([^/?#]+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase() ?: ""

    private fun score(url: String): Int {
        val h = host(url)
        var s = 0
        if (BAD_HOSTS.any { it in url.lowercase() }) s -= 100
        if (VIDEO_HOSTS.any { it in h }) s += 50
        if (VIDEO_PATH.containsMatchIn(url)) s += 40
        return s
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
