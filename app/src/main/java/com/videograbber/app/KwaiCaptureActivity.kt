package com.videograbber.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
 * Kwai may replace an unavailable shared video with a recommendation feed.
 * The share redirect exposes the requested numeric userId/photoId, while every
 * Kwai CDN filename encodes its owner's userId. We only retain media whose
 * encoded owner matches the share target. A recommendation is therefore
 * rejected rather than silently saved as the requested video.
 */
class KwaiCaptureActivity : ComponentActivity() {

    private data class Candidate(val url: String, val seenAt: Long)

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var captureButton: Button
    private val candidates = Collections.synchronizedList(mutableListOf<Candidate>())

    @Volatile
    private var expectedUserId: String? = null

    @Volatile
    private var expectedPhotoId: String? = null

    @Volatile
    private var kwaiMode: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inputUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
        if (inputUrl == null) {
            finish()
            return
        }
        kwaiMode = KwaiExtractor.isKwai(inputUrl)
        updateExpectedIdentity(inputUrl)

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
                if (request?.isForMainFrame == true) {
                    updateExpectedIdentity(request.url?.toString().orEmpty())
                }
                rememberCandidate(request?.url?.toString().orEmpty())
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                updateExpectedIdentity(url.orEmpty())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                updateExpectedIdentity(url.orEmpty())
                setStatus(INSTRUCTIONS)
            }
        }
        webView.setDownloadListener { url, _, _, _, _ -> rememberCandidate(url) }
        webView.loadUrl(inputUrl)
    }

    private fun rememberCandidate(url: String) {
        if (!isDirectMp4(url)) return
        if (!matchesExpectedKwaiIdentity(url)) return
        synchronized(candidates) {
            candidates.removeAll { it.url == url }
            candidates += Candidate(url, System.currentTimeMillis())
            while (candidates.size > 30) candidates.removeAt(0)
        }
    }

    private fun captureVisibleVideo(originalUrl: String) {
        captureButton.isEnabled = false
        updateExpectedIdentity(webView.url.orEmpty())
        setStatus("Checking the video currently visible...")
        webView.evaluateJavascript(CURRENT_VIDEO_JS) { encoded ->
            val current = runCatching {
                JSONTokener(encoded).nextValue() as? String
            }.getOrNull().orEmpty()
            if (isDirectMp4(current)) rememberCandidate(current)

            val now = System.currentTimeMillis()
            val selected = when {
                isVerifiedCandidate(current) -> current
                else -> synchronized(candidates) {
                    candidates.lastOrNull {
                        now - it.seenAt <= 20_000 && isVerifiedCandidate(it.url)
                    }?.url
                }
            }

            if (selected == null) {
                captureButton.isEnabled = true
                val message = if (kwaiMode && (expectedUserId != null || expectedPhotoId != null)) {
                    "The target video is unavailable on this page. Kwai only " +
                        "returned recommendation videos, so nothing was downloaded."
                } else {
                    "No downloadable MP4 was detected. Tap Play on the exact " +
                        "video, wait a moment, then tap CAPTURE THIS VIDEO again."
                }
                setStatus(message)
                return@evaluateJavascript
            }
            downloadSelected(selected, originalUrl)
        }
    }

    private fun isVerifiedCandidate(url: String): Boolean {
        if (!isDirectMp4(url)) return false
        return matchesExpectedKwaiIdentity(url)
    }

    private fun matchesExpectedKwaiIdentity(url: String): Boolean {
        if (!kwaiMode) return true
        expectedUserId?.let { expected ->
            if (KwaiExtractor.userIdOf(url) != expected) return false
        }
        expectedPhotoId?.let { expected ->
            if (KwaiExtractor.photoIdOf(url) != expected) return false
        }
        return expectedUserId != null || expectedPhotoId != null
    }

    private fun downloadSelected(mediaUrl: String, originalUrl: String) {
        captureButton.isEnabled = false
        lifecycleScope.launch {
            try {
                if (!matchesExpectedKwaiIdentity(mediaUrl)) {
                    throw IllegalStateException(
                        "Kwai returned a recommendation instead of the target video",
                    )
                }
                setStatus("Downloading the verified video...")
                val pageUrl = webView.url?.takeIf { it.startsWith("http") } ?: originalUrl
                val cookies = CookieManager.getInstance().getCookie(mediaUrl)
                val userAgent = webView.settings.userAgentString
                val photoId = expectedPhotoId
                    ?: Regex("/video/(\\d+)").find(webView.url.orEmpty())
                        ?.groupValues?.getOrNull(1)
                val saved = KwaiExtractor.streamAndSave(
                    applicationContext,
                    mediaUrl,
                    pageUrl,
                    photoId,
                    cookies,
                    userAgent,
                ) { progress ->
                    setStatus("Downloading... ${progress.toInt().coerceIn(0, 100)}%")
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

    private fun updateExpectedIdentity(url: String) {
        if (url.isBlank()) return
        if (KwaiExtractor.isKwai(url)) kwaiMode = true
        if (!kwaiMode) return
        val pathIdentity = Regex("/photo/(\\d{8,})/(\\d{12,})").find(url)
        val userId = pathIdentity?.groupValues?.getOrNull(1)
            ?: Regex("[?&]userId=(\\d{8,})", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)
        val photoId = pathIdentity?.groupValues?.getOrNull(2)
            ?: Regex(
                "[?&](?:photoId|shareObjectId)=(\\d{12,})",
                RegexOption.IGNORE_CASE,
            ).find(url)?.groupValues?.getOrNull(1)
            ?: Regex("/(?:video|photo)/(\\d{12,})")
                .find(url)?.groupValues?.getOrNull(1)
        if (userId != null) expectedUserId = userId
        if (photoId != null) expectedPhotoId = photoId
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
                "2. Tap Play and wait 2-3 seconds.\n" +
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
