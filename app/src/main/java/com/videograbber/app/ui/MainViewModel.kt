package com.videograbber.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videograbber.app.core.DownloadBus
import com.videograbber.app.core.Downloader
import com.videograbber.app.core.LinkResolver
import com.videograbber.app.service.DownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class QualityOption(val label: String, val maxHeight: Int)

data class UiState(
    val url: String = "",
    val fetching: Boolean = false,
    val hasInfo: Boolean = false,
    val title: String? = null,
    val thumbnail: String? = null,
    val platform: String = "",
    /** Visible browser capture rather than automatic yt-dlp extraction. */
    val directStream: Boolean = false,
    val qualities: List<QualityOption> = listOf(QualityOption("Best (auto)", 0)),
    val selectedQuality: Int = 0,
    val audioOnly: Boolean = false,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val mutableUi = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = mutableUi.asStateFlow()
    val download: StateFlow<DownloadBus.State> = DownloadBus.state

    fun onUrlChange(value: String) {
        mutableUi.value = mutableUi.value.copy(url = value)
    }

    fun setAudioOnly(value: Boolean) {
        mutableUi.value = mutableUi.value.copy(audioOnly = value)
    }

    fun selectQuality(index: Int) {
        mutableUi.value = mutableUi.value.copy(selectedQuality = index)
    }

    fun fetch() {
        val url = LinkResolver.clean(mutableUi.value.url) ?: mutableUi.value.url.trim()
        if (url.isEmpty()) return
        mutableUi.value = mutableUi.value.copy(
            url = url,
            fetching = true,
            hasInfo = false,
            error = null,
        )

        viewModelScope.launch {
            try {
                val info = Downloader.getInfo(getApplication(), url)
                val qualities = buildList {
                    add(QualityOption("Best (auto)", 0))
                    info.heights.forEach { height ->
                        val tag = when {
                            height >= 2160 -> " (4K)"
                            height >= 1440 -> " (2K)"
                            else -> ""
                        }
                        add(QualityOption("${height}p$tag", height))
                    }
                }
                mutableUi.value = mutableUi.value.copy(
                    fetching = false,
                    hasInfo = true,
                    title = info.title,
                    thumbnail = info.thumbnail,
                    directStream = info.directStream,
                    platform = platformFromUrl(url),
                    qualities = qualities,
                    selectedQuality = 0,
                    error = Downloader.engineUpdateWarning?.let {
                        "The extractor update could not be checked. The bundled engine is being used."
                    },
                )
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                if (canUseBrowserCapture(url, message)) {
                    mutableUi.value = mutableUi.value.copy(
                        fetching = false,
                        hasInfo = true,
                        title = "Browser Capture",
                        thumbnail = null,
                        platform = platformFromUrl(url),
                        directStream = true,
                        qualities = listOf(QualityOption("Original stream", 0)),
                        selectedQuality = 0,
                        error = "Automatic extraction is unavailable for this link. " +
                            "Browser Capture can save a public direct MP4 after you play " +
                            "and confirm the exact video. DRM-protected media is not supported.",
                    )
                } else {
                    mutableUi.value = mutableUi.value.copy(
                        fetching = false,
                        hasInfo = false,
                        error = friendlyError(message),
                    )
                }
            }
        }
    }

    fun startDownload() {
        val state = mutableUi.value
        val url = LinkResolver.clean(state.url) ?: state.url.trim()
        if (url.isEmpty()) return
        DownloadBus.update(DownloadBus.State.Preparing)
        DownloadService.start(
            getApplication(),
            url,
            state.audioOnly,
            state.qualities.getOrNull(state.selectedQuality)?.maxHeight ?: 0,
            state.title ?: "Video",
        )
    }

    fun cancel() {
        DownloadService.cancel(getApplication())
        DownloadBus.reset()
    }

    private fun platformFromUrl(url: String): String {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            .removePrefix("www.")
            .removePrefix("m.")
        return when {
            "youtu" in host -> "YouTube"
            "tiktok" in host -> "TikTok"
            "likee" in host || "like-video" in host -> "Likee"
            "instagram" in host -> "Instagram"
            "facebook" in host || "fb." in host -> "Facebook"
            "twitter" in host || host.startsWith("x.") || "://x.com" in url -> "Twitter / X"
            "kwai" in host -> "Kwai"
            host.isNotEmpty() -> host
            else -> "Unknown"
        }
    }

    private fun canUseBrowserCapture(url: String, message: String): Boolean {
        val lower = message.lowercase()
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }
            .getOrDefault("")
        return "likee" in host ||
            "unsupported url" in lower ||
            "unable to extract" in lower ||
            "no video formats" in lower ||
            "requested format is not available" in lower
    }

    private fun friendlyError(message: String): String {
        val lower = message.lowercase()
        val hint = when {
            "private" in lower || "login" in lower || "sign in" in lower ->
                "This content is private or requires sign-in."
            "drm" in lower -> "This video is DRM-protected and cannot be downloaded."
            "unavailable" in lower -> "The video is unavailable."
            "network" in lower || "timed out" in lower ->
                "The network request failed. Check your connection and try again."
            else -> "Couldn't fetch video information."
        }
        val detail = message.trim().takeIf { it.isNotEmpty() }?.take(500)
        return if (detail == null) hint else "$hint\n\nDetails:\n$detail"
    }
}
