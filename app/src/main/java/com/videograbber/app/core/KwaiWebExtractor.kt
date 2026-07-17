package com.videograbber.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/**
 * Resolves the CORRECT Kwai video by rendering the share page in a headless
 * WebView.
 *
 * Kwai's international site (kwai.com) does NOT put the requested video's mp4
 * in the server-rendered HTML — that HTML is a recommendation FEED. The real
 * video is fetched client-side through a signed API (__NS_sig3) that can't be
 * reproduced outside a browser. So we let a real WebView run Kwai's own JS,
 * which fetches and plays the requested video, and we intercept the video CDN
 * request. Candidates are filtered by the target userId (encoded in the mp4
 * filename) so a preloaded feed video is never picked by mistake.
 */
object KwaiWebExtractor {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveMp4(
        context: Context,
        pageUrl: String,
        targetUserId: String?,
        timeoutMs: Long = 30_000,
    ): String = withContext(Dispatchers.Main) {
        val exact = CompletableDeferred<String>()          // completes on a userId match
        val seen = Collections.synchronizedList(mutableListOf<String>())

        fun consider(url: String) {
            if (!isKwaiMp4(url)) return
            synchronized(seen) { if (url !in seen) seen.add(url) else return }
            if (targetUserId == null || userIdOf(url) == targetUserId) {
                if (!exact.isCompleted) exact.complete(url)
            }
        }

        val web = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = UA
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?,
                ): WebResourceResponse? {
                    request?.url?.toString()?.let { consider(it) }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // nudge the player in case autoplay is throttled
                    view?.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');" +
                            "if(v){v.muted=true;v.play&&v.play();}})();", null
                    )
                }
            }
            loadUrl(pageUrl)
        }

        val result = withTimeoutOrNull(timeoutMs) {
            // keep nudging the player while we wait for the video's mp4 request
            val nudger = launch {
                while (true) {
                    delay(1500)
                    web.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');" +
                            "if(v){v.muted=true;v.play&&v.play();}})();", null
                    )
                }
            }
            try {
                exact.await()
            } finally {
                nudger.cancel()
            }
        }

        web.stopLoading()
        web.destroy()

        result
            ?: seen.firstOrNull { userIdOf(it) == targetUserId }   // exact if we have it
            ?: seen.firstOrNull()                                  // else best effort
            ?: throw RuntimeException(
                "تعذّر التقاط رابط فيديو كواي — تأكد أن الفيديو يفتح في متصفح الهاتف ثم أعد المحاولة."
            )
    }

    private fun isKwaiMp4(url: String): Boolean {
        val u = url.lowercase()
        return ".mp4" in u && ("kwai" in u || "oskwai" in u || "kwaidc" in u)
    }

    /** Kwai mp4 filenames base64-encode "{ts}_{userId}_{photoId}_..." — pull userId. */
    private fun userIdOf(url: String): String {
        val m = Regex("/B([A-Za-z0-9=]+?)_(?:sl|b_)").find(url) ?: return ""
        val b64 = m.groupValues[1]
        val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
        return try {
            val dec = String(Base64.decode(padded, Base64.DEFAULT))
            dec.split("_").getOrNull(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
