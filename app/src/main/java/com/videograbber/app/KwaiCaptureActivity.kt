package com.videograbber.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.videograbber.app.core.DownloadBus
import com.videograbber.app.core.KwaiExtractor
import kotlinx.coroutines.launch
import org.json.JSONTokener
import java.util.Collections

/**
 * Visible browser capture for Kwai and unsupported public sites.
 *
 * Accuracy rule: the app never guesses which feed item is wanted. The user
 * first makes the desired video visible/playing and explicitly confirms it.
 * Only then is the current video source (or the most recently requested MP4)
 * downloaded. DRM/blob-only streams are rejected instead of saving the wrong
 * content.
 */
class KwaiCaptureActivity : ComponentActivity() {

    private data class Candidate(val url: String, val seenAt: Long)

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var captureButton: Button
    private val candidates = Collections.synchronizedList(mutableListOf<Candidate>())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inputUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
        if (inputUrl == null) {
            finish()
            return
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.userAgentString = MOBILE_UA
            webChromeClient = WebChromeClient()
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xEE101114.toInt())
        }
        statusView = TextView(this).apply {
            text = INSTRUCTIONS
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        captureButton = Button(this).apply {
            text = "CAPTURE THIS VIDEO"
            setOnClickListener { captureVisibleVideo(inputUrl) }
        }
        panel.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel.addView(captureButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val root = FrameLayout(this)
        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(
            panel,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            },
        )
        setContentView(root)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                rememberCandidate(request?.url?.toString().orEmpty())
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                setStatus(INSTRUCTIONS)
            }
        }
        webView.setDownloadListener { url, _, _, _, _ -> rememberCandidate(url) }
        webView.loadUrl(inputUrl)
    }

    private fun rememberCandidate(url: String) {
        if (!isDirectMp4(url)) return
        synchronized(candidates) {
            candidates.removeAll { it.url == url }
            candidates += Candidate(url, System.currentTimeMillis())
            while (candidates.size > 30) candidates.removeAt(0)
        }
    }

    private fun captureVisibleVideo(originalUrl: String) {
        captureButton.isEnabled = false
        setStatus("Checking the video currently visible…")
        webView.evaluateJavascript(CURRENT_VIDEO_JS) { encoded ->
            val current = runCatching {
                JSONTokener(encoded).nextValue() as? String
            }.getOrNull().orEmpty()
            if (isDirectMp4(current)) rememberCandidate(current)

            val now = System.currentTimeMillis()
            val selected = when {
                isDirectMp4(current) -> current
                current.startsWith("blob:", ignoreCase = true) ->
                    synchronized(candidates) {
                        candidates.lastOrNull { now - it.seenAt <= 20_000 }?.url
                    }
                else -> synchronized(candidates) {
                    candidates.lastOrNull { now - it.seenAt <= 20_000 }?.url
                }
            }

            if (selected == null) {
                captureButton.isEnabled = true
                setStatus(
                    "No downloadable MP4 was detected. Tap Play on the exact " +
                        "video, wait a moment, then tap CAPTURE THIS VIDEO again.",
                )
                return@evaluateJavascript
            }
            downloadSelected(selected, originalUrl)
        }
    }

    private fun downloadSelected(mediaUrl: String, referer: String) {
        captureButton.isEnabled = false
        lifecycleScope.launch {
            try {
                setStatus("Downloading the confirmed video…")
                val photoId = Regex("/video/(\\d+)").find(webView.url.orEmpty())
                    ?.groupValues?.getOrNull(1)
                val saved = KwaiExtractor.streamAndSave(
                    applicationContext,
                    mediaUrl,
                    referer,
                    photoId,
                ) { progress ->
                    setStatus("Downloading… ${progress.toInt().coerceIn(0, 100)}%")
                }
                DownloadBus.update(DownloadBus.State.Success(saved))
                Toast.makeText(
                    this@KwaiCaptureActivity,
                    "Saved to Library",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            } catch (error: Exception) {
                captureButton.isEnabled = true
                setStatus(
                    "Capture failed: ${error.message ?: "unknown error"}. " +
                        "Play the video and try again.",
                )
            }
        }
    }

    private fun isDirectMp4(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http") &&
            (".mp4" in lower || "video/mp4" in lower) &&
            !lower.startsWith("blob:")
    }

    private fun setStatus(message: String) = runOnUiThread { statusView.text = message }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val INSTRUCTIONS =
            "1. Make sure the exact video you want is visible.\n" +
                "2. Tap Play and wait 2–3 seconds.\n" +
                "3. Tap CAPTURE THIS VIDEO below."
        private const val CURRENT_VIDEO_JS =
            "(function(){" +
                "var a=Array.from(document.querySelectorAll('video'));" +
                "var v=a.find(function(x){return !x.paused&&x.readyState>0;})" +
                "||a.find(function(x){return x.readyState>0;})||a[0];" +
                "return v?(v.currentSrc||v.src||''):'';" +
                "})()"
    }
}
