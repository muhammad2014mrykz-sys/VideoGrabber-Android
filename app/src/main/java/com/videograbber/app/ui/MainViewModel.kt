package com.videograbber.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videograbber.app.core.DownloadBus
import com.videograbber.app.core.Downloader
import com.videograbber.app.core.LinkResolver
import com.videograbber.app.service.DownloadService
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class QualityOption(val label: String, val maxHeight: Int) // 0 = best

data class UiState(
    val url: String = "",
    val fetching: Boolean = false,
    val info: VideoInfo? = null,
    val platform: String = "",
    val qualities: List<QualityOption> = listOf(QualityOption("الأعلى تلقائياً", 0)),
    val selectedQuality: Int = 0,      // index into qualities
    val audioOnly: Boolean = false,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val download: StateFlow<DownloadBus.State> = DownloadBus.state

    fun onUrlChange(v: String) {
        _ui.value = _ui.value.copy(url = v)
    }

    fun setAudioOnly(v: Boolean) {
        _ui.value = _ui.value.copy(audioOnly = v)
    }

    fun selectQuality(index: Int) {
        _ui.value = _ui.value.copy(selectedQuality = index)
    }

    fun fetch() {
        // Clean the (often messy) pasted text into one real URL: strips hidden
        // RTL/zero-width chars, pulls the link out of promo text, drops tracking.
        val url = LinkResolver.clean(_ui.value.url) ?: _ui.value.url.trim()
        if (url.isEmpty()) return
        _ui.value = _ui.value.copy(url = url, fetching = true, error = null)
        viewModelScope.launch {
            try {
                val info = Downloader.getInfo(getApplication(), url)
                val heights = info.formats
                    ?.mapNotNull { fmt ->
                        val h: Int? = fmt.height
                        if (h != null && h > 0) h else null
                    }
                    ?.distinct()
                    ?.sortedDescending()
                    .orEmpty()
                val options = buildList {
                    add(QualityOption("الأعلى تلقائياً", 0))
                    heights.forEach { h ->
                        val tag = when {
                            h >= 2160 -> " (4K)"
                            h >= 1440 -> " (2K)"
                            else -> ""
                        }
                        add(QualityOption("${h}p$tag", h))
                    }
                }
                _ui.value = _ui.value.copy(
                    fetching = false,
                    info = info,
                    platform = platformFromUrl(url),
                    qualities = options,
                    selectedQuality = 0,
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    fetching = false,
                    error = friendlyError(e.message)
                )
            }
        }
    }

    fun startDownload() {
        val s = _ui.value
        val url = LinkResolver.clean(s.url) ?: s.url.trim()
        if (url.isEmpty()) return
        DownloadBus.update(DownloadBus.State.Preparing)
        val maxHeight = s.qualities.getOrNull(s.selectedQuality)?.maxHeight ?: 0
        val title = s.info?.title ?: "video"
        DownloadService.start(getApplication(), url, s.audioOnly, maxHeight, title)
    }

    fun cancel() {
        DownloadService.cancel(getApplication())
        DownloadBus.reset()
    }

    fun clearDownloadState() = DownloadBus.reset()

    private fun platformFromUrl(url: String): String {
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
            .removePrefix("www.").removePrefix("m.")
        return when {
            "youtu" in host -> "YouTube"
            "tiktok" in host -> "TikTok"
            "instagram" in host -> "Instagram"
            "facebook" in host || "fb." in host -> "Facebook"
            "twitter" in host || host.startsWith("x.") || "://x.com" in url -> "Twitter / X"
            "kwai" in host -> "Kwai"
            host.isNotEmpty() -> host
            else -> "—"
        }
    }

    private fun friendlyError(msg: String?): String {
        val m = (msg ?: "").lowercase()
        val hint = when {
            "private" in m || "login" in m || "sign in" in m ->
                "هذا المحتوى خاص أو يتطلب تسجيل دخول."
            "unsupported url" in m || "unable to extract" in m ->
                "الرابط غير مدعوم أو لا يحتوي فيديو مباشر."
            "unavailable" in m -> "الفيديو غير متاح."
            else -> "تعذّر جلب المعلومات — تأكد من الرابط والاتصال."
        }
        // Surface the real underlying error too, so problems are diagnosable.
        val detail = msg?.trim()?.takeIf { it.isNotEmpty() }?.take(300)
        return if (detail != null) "$hint\n\nالتفاصيل:\n$detail" else hint
    }
}
