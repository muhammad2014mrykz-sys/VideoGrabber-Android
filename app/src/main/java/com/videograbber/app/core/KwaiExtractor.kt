package com.videograbber.app.core

import android.content.Context
import android.os.Environment
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Kwai extractor.
 *
 * yt-dlp can't do Kwai, and Kwai's international share page HTML is only a
 * recommendation feed — the requested video's mp4 is fetched client-side via a
 * signed, browser-only API. So the real video URL is captured by rendering the
 * page in a visible WebView (see KwaiCaptureActivity). This module resolves the
 * share link to its canonical page + ids, identifies the correct CDN mp4 (by
 * the userId encoded in the filename), and streams/saves it.
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

    data class KwaiMeta(
        val canonicalUrl: String,
        val photoId: String?,
        val userId: String?,
        val title: String,
        val thumbnail: String?,
    )

    /** Follow the short-link redirects (HTTP works) and read the ids + og tags. */
    suspend fun probe(url: String): KwaiMeta = withContext(Dispatchers.IO) {
        val conn = open(url, null)
        val (finalUrl, html) = try {
            conn.url.toString() to readBody(conn)
        } finally {
            conn.disconnect()
        }
        val photoId = Regex("[?&]photoId=(\\d+)").find(finalUrl)?.groupValues?.get(1)
            ?: Regex("/video/(\\d+)").find(finalUrl)?.groupValues?.get(1)
        val userId = Regex("[?&]userId=(\\d+)").find(finalUrl)?.groupValues?.get(1)
        val ogImage = Regex("property=\"og:image\"\\s+content=\"([^\"]*)\"")
            .find(html)?.groupValues?.get(1)?.let { decodeHtml(it) }
        KwaiMeta(
            canonicalUrl = finalUrl,
            photoId = photoId,
            userId = userId,
            title = "Kwai video" + (photoId?.let { " • $it" } ?: ""),
            thumbnail = ogImage,
        )
    }

    /** Does this look like a Kwai CDN video request? */
    fun isKwaiMp4(url: String): Boolean {
        val u = url.lowercase()
        return ".mp4" in u && ("kwai" in u || "oskwai" in u || "kwaidc" in u)
    }

    /** Kwai mp4 filenames base64-encode "{ts}_{userId}_{photoId}_..." — pull userId. */
    fun userIdOf(url: String): String {
        val m = Regex("/B([A-Za-z0-9=]+?)_(?:sl|b_)").find(url) ?: return ""
        val b64 = m.groupValues[1]
        val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
        return try {
            String(Base64.decode(padded, Base64.DEFAULT)).split("_").getOrNull(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** Stream a captured mp4 to storage and publish it to the Downloads library. */
    suspend fun streamAndSave(
        context: Context,
        mp4Url: String,
        referer: String,
        photoId: String?,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val outDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VideoGrabber"
        ).apply { mkdirs() }
        val tmp = File(outDir, "kwai_${photoId ?: System.currentTimeMillis()}.mp4")
        streamTo(mp4Url, referer, tmp, { pct, _ -> onProgress(pct) }) { false }
        MediaExport.publishToDownloads(context, tmp)
    }

    // -- networking helpers ---------------------------------------------- //
    private fun open(urlStr: String, referer: String?): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        if (referer != null) conn.setRequestProperty("Referer", referer)
        return conn
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
            if (code !in 200..299) throw RuntimeException("Kwai download error (HTTP $code)")
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
                        onProgress(if (total > 0) done * 100f / total else 0f, "kwai")
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeHtml(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
}
