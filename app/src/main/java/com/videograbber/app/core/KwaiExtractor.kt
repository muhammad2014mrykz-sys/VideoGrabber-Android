package com.videograbber.app.core

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Dedicated Kwai extractor.
 *
 * yt-dlp has no Kwai extractor and Kwai pages expose no og:video / JSON-LD /
 * <video> tag — the MP4 URL is buried in the page's JavaScript state — so the
 * generic extractor can't get it. This module fetches the share page with a
 * mobile User-Agent (following the k.kwai.com -> m.kwaiapps.com -> www.kwai.com
 * redirect chain), scrapes the direct MP4, and streams it.
 *
 * The MP4 URL is signed and expires after a few hours, so we always re-extract
 * immediately before downloading.
 */
object KwaiExtractor {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val HOSTS = listOf("kwai.com", "kwai.net", "kwaiapps.com", "kuaishou", "gifshow.com")

    fun isKwai(url: String): Boolean {
        val u = url.lowercase()
        return HOSTS.any { it in u }
    }

    data class KwaiVideo(
        val pageUrl: String,
        val title: String,
        val thumbnail: String?,
        val mp4Url: String,
    )

    suspend fun extract(url: String): KwaiVideo = withContext(Dispatchers.IO) {
        val (finalUrl, html) = fetchPage(url)
        val mp4 = findMp4(html) ?: throw RuntimeException(
            "لم أعثر على رابط الفيديو في صفحة كواي (قد يكون خاصاً أو محذوفاً)."
        )
        KwaiVideo(finalUrl, findTitle(html), findThumb(html), mp4)
    }

    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
    ): File = withContext(Dispatchers.IO) {
        val v = extract(url)                      // fresh, unexpired signed URL
        val outDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VideoGrabber"
        ).apply { mkdirs() }
        // Kwai's og:title is a generic tagline shared by all videos, so append
        // the numeric photo id to keep filenames unique.
        val id = Regex("/video/(\\d+)").find(v.pageUrl)?.groupValues?.get(1)
            ?: Regex("photoId=(\\d+)").find(v.pageUrl)?.groupValues?.get(1)
            ?: ""
        val base = safeName(v.title)
        val fname = if (id.isNotEmpty()) "${base}_$id.mp4" else "$base.mp4"
        val file = File(outDir, fname)
        streamTo(v.mp4Url, v.pageUrl, file, onProgress, isCancelled)
        file
    }

    // -- networking ------------------------------------------------------- //
    private fun open(urlStr: String, referer: String?): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true       // follows the https redirect chain
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        if (referer != null) conn.setRequestProperty("Referer", referer)
        return conn
    }

    private fun fetchPage(url: String): Pair<String, String> {
        val conn = open(url, null)
        try {
            val body = readBody(conn)
            return conn.url.toString() to body
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val gz = conn.contentEncoding?.contains("gzip", ignoreCase = true) == true
        val raw = (if (conn.responseCode >= 400) conn.errorStream else conn.inputStream)
            ?: return ""
        val stream = if (gz) GZIPInputStream(raw) else raw
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun streamTo(
        mp4Url: String, referer: String, file: File,
        onProgress: (Float, String) -> Unit, isCancelled: () -> Boolean,
    ) {
        val conn = open(mp4Url, referer)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("خطأ تحميل كواي (HTTP $code)")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(131072)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw RuntimeException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) done * 100f / total else 0f
                        onProgress(pct, "kwai")
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // -- scraping --------------------------------------------------------- //
    private fun unescape(s: String): String =
        s.replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\/", "/").replace("\\u0026", "&")

    private val MP4_PATTERNS = listOf(
        Regex("\"srcNoMark\":\"(https?[^\"]+?\\.mp4[^\"]*)\""),
        Regex("\"main_mv_urls?\":\\[\\{\"[^}]*?\"url\":\"(https?[^\"]+?\\.mp4[^\"]*)\""),
        Regex("\"photoUrl\":\"(https?[^\"]+?\\.mp4[^\"]*)\""),
        Regex("\"contentUrl\":\"(https?[^\"]+?\\.mp4[^\"]*)\""),
        // fallback: any mp4 on a kwai CDN host (avoids unrelated media)
        Regex("(https?://[a-z0-9.-]*kwai[a-z0-9.-]*/[^\"'\\s\\\\]+?\\.mp4[^\"'\\s\\\\]*)",
            RegexOption.IGNORE_CASE),
    )

    private fun findMp4(html: String): String? {
        val h = unescape(html)
        for (p in MP4_PATTERNS) p.find(h)?.let { return unescape(it.groupValues[1]) }
        return null
    }

    private fun findTitle(html: String): String {
        Regex("property=\"og:title\"\\s+content=\"([^\"]*)\"").find(html)?.let {
            val t = decodeHtml(it.groupValues[1]).trim()
            if (t.isNotEmpty()) return t
        }
        Regex("<title>([^<]*)</title>").find(html)?.let {
            val t = decodeHtml(it.groupValues[1]).trim()
            if (t.isNotEmpty()) return t
        }
        return "kwai_video"
    }

    private fun findThumb(html: String): String? {
        Regex("property=\"og:image\"\\s+content=\"([^\"]*)\"").find(html)?.let {
            return decodeHtml(it.groupValues[1])
        }
        return null
    }

    private fun decodeHtml(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trim()
        return (if (cleaned.isEmpty()) "kwai_video" else cleaned).take(120)
    }
}
