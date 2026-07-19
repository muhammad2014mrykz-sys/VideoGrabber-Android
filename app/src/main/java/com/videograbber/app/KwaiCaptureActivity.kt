package com.videograbber.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.videograbber.app.core.DownloadBus
import com.videograbber.app.core.KwaiExtractor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/**
 * Downloads a Kwai video by rendering the share page in a VISIBLE WebView.
 *
 * Kwai's real video is loaded by its own JS through a signed, browser-only API,
 * and a headless/background WebView won't autoplay — so we show the WebView
 * briefly, let it play the requested video, and intercept the CDN mp4 request
 * (filtered by the target userId so a recommendation-feed video is never
 * picked). The captured mp4 is then streamed to the Downloads library.
 */
class KwaiCaptureActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView

    private val JS_PLAY =
        "(function(){var v=document.querySelector('video');" +
            "if(v){v.muted=true;var p=v.play&&v.play();if(p&&p.catch)p.catch(function(){});}})();"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        status = TextView(this).apply {
            text = getString(com.videograbber.app.R.string.preparing)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6000000.toInt())
            textSize = 15f
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { gravity = Gravity.BOTTOM }
        )
        setContentView(root)

        val shareUrl = intent.getStringExtra(EXTRA_URL)
        if (shareUrl.isNullOrBlank()) {
            finish(); return
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = UA
        }

        lifecycleScope.launch { runCapture(shareUrl) }
    }

    private suspend fun runCapture(shareUrl: String) {
        try {
            setStatus("Preparing…")
            val meta = KwaiExtractor.probe(shareUrl)
            setStatus("Finding the video…")
            val mp4 = captureMp4(meta.canonicalUrl, meta.userId)
            setStatus("Downloading…")
            val saved = KwaiExtractor.streamAndSave(
                applicationContext, mp4, meta.canonicalUrl, meta.photoId
            ) { pct -> setStatus("Downloading… ${pct.toInt()}%") }
            DownloadBus.update(DownloadBus.State.Success(saved))
            toast("Saved to Library ✓")
        } catch (e: Exception) {
            toast("Kwai: ${e.message ?: "download failed"}")
        } finally {
            runCatching { web.stopLoading(); web.destroy() }
            finish()
        }
    }

    private suspend fun captureMp4(pageUrl: String, targetUserId: String?): String {
        val exact = CompletableDeferred<String>()
        val seen = Collections.synchronizedList(mutableListOf<String>())

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (KwaiExtractor.isKwaiMp4(url)) {
                    val fresh = synchronized(seen) {
                        if (url !in seen) { seen.add(url); true } else false
                    }
                    if (fresh && (targetUserId == null || KwaiExtractor.userIdOf(url) == targetUserId)) {
                        if (!exact.isCompleted) exact.complete(url)
                    }
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(JS_PLAY, null)
            }
        }
        web.loadUrl(pageUrl)

        val result = withTimeoutOrNull(45_000) {
            val nudger = launch {
                while (true) {
                    delay(1200)
                    web.evaluateJavascript(JS_PLAY, null)
                }
            }
            try {
                exact.await()
            } finally {
                nudger.cancel()
            }
        }

        return result
            ?: seen.firstOrNull { KwaiExtractor.userIdOf(it) == targetUserId }
            ?: seen.firstOrNull()
            ?: throw RuntimeException("couldn't capture the video — please try again")
    }

    private fun setStatus(text: String) = runOnUiThread { status.text = text }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        runCatching { web.destroy() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
