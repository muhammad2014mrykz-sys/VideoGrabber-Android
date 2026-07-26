package com.videograbber.app.core

/**
 * Extracts and cleans the most likely video URL from arbitrary shared text.
 *
 * Share sheets frequently include promotional text, several links, Arabic
 * punctuation and invisible bidi characters. Passing that text directly to an
 * extractor can select the wrong link or make a valid URL fail.
 */
object LinkResolver {

    private val invisible = Regex(
        "[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u2060\\uFEFF\\u00AD\\u061C]"
    )
    private val absoluteUrl = Regex("https?://[^\\s<>\"'\\\\]+", RegexOption.IGNORE_CASE)
    private val bareUrl = Regex(
        "(?<![\\w@.])((?:[a-z0-9-]+\\.)+[a-z]{2,}/[^\\s<>\"'\\\\]+)",
        RegexOption.IGNORE_CASE,
    )
    private val trailing = ".,،؛:;!?؟)]}\"'»«".toSet()
    private val trackingKeys = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "igshid", "igsh", "gclid", "mibextid", "si", "feature",
        "share_uid", "share_device_id", "share_id", "share_item_type",
        "share_item_info", "sharepage", "fromshare", "is_from_webapp",
        "is_copy_url", "sender_device", "cc", "gid", "kpn", "subbiz",
        "apptype", "timestamp", "language",
    )
    private val videoHosts = listOf(
        "kwai.com", "kwai.net", "kwaiapps.com", "kuaishou", "gifshow.com",
        "likee.video", "like-video.com",
        "youtube.com", "youtu.be", "tiktok.com",
        "instagram.com", "instagr.am", "facebook.com", "fb.watch", "fb.com",
        "twitter.com", "x.com", "t.co", "snapchat.com",
        "pinterest.", "pin.it", "reddit.com", "redd.it",
        "twitch.tv", "dailymotion.com", "vimeo.com",
    )
    private val badHosts = listOf(
        "play.google.com", "apps.apple.com", "itunes.apple.com",
        "kwai-app", "onelink.me", "app.link", "adjust.com", "appsflyer",
        "s.kw.ai/app", "bit.ly/kwai",
    )
    private val videoPath = Regex(
        "/(p|video|watch|reel|reels|status|shorts|v|embed|photo)/|/@",
        RegexOption.IGNORE_CASE,
    )

    fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return extractAll(raw).maxByOrNull(::score)
    }

    internal fun extractAll(raw: String): List<String> {
        val text = invisible.replace(raw, "")
        val urls = absoluteUrl.findAll(text).map { normalize(it.value) }.toMutableList()
        if (urls.isEmpty()) {
            bareUrl.findAll(text).forEach { urls += normalize("https://${it.groupValues[1]}") }
        }
        return urls.filter { it.length > "https://a.co".length }.distinct()
    }

    private fun score(url: String): Int {
        val lower = url.lowercase()
        val host = Regex("https?://([^/?#]+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase().orEmpty()
        var result = 0
        if (badHosts.any { it in lower }) result -= 100
        if (videoHosts.any { it in host }) result += 50
        if (videoPath.containsMatchIn(url)) result += 40
        return result
    }

    private fun normalize(raw: String): String {
        var url = invisible.replace(raw, "").trim()
        while (url.isNotEmpty() && url.last() in trailing) url = url.dropLast(1)
        return stripTracking(url)
    }

    private fun stripTracking(url: String): String {
        val question = url.indexOf('?')
        if (question < 0) return url
        val base = url.substring(0, question)
        val rest = url.substring(question + 1)
        val fragment = rest.substringAfter('#', "")
        val kept = rest.substringBefore('#').split('&').filter { part ->
            val key = part.substringBefore('=').lowercase()
            key.isNotEmpty() && key !in trackingKeys && !key.startsWith("utm_")
        }
        val rebuilt = if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
        return if (fragment.isEmpty()) rebuilt else "$rebuilt#$fragment"
    }
}
