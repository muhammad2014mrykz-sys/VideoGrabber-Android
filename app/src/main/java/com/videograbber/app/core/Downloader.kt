package com.videograbber.app.core

import android.content.Context
import android.os.Environment
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UI-agnostic wrapper around youtubedl-android (bundled yt-dlp + ffmpeg).
 * Handles engine init, metadata probing, and downloading with progress.
 */
/** Platform-neutral probe result (works for both yt-dlp and the Kwai path). */
data class MediaInfo(
    val title: String,
    val thumbnail: String?,
    val heights: List<Int>,     // available video heights; empty = single/best only
    val directStream: Boolean,  // e.g. Kwai — custom downloader, no quality choice
)

object Downloader {

    private val initialized = AtomicBoolean(false)
    private val updated = AtomicBoolean(false)
    private val initMutex = Mutex()
    private val updateMutex = Mutex()
    private val cancelledIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Initialise the bundled python/yt-dlp/ffmpeg once. Idempotent. */
    suspend fun ensureInit(context: Context) = withContext(Dispatchers.IO) {
        if (initialized.get()) return@withContext
        initMutex.withLock {
            if (initialized.get()) return@withLock
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            initialized.set(true)
        }
    }

    /**
     * Init + (once per app process) update yt-dlp to the latest release.
     * The library's bundled yt-dlp is old; refreshing it is what keeps every
     * platform working. Best-effort: if offline, we proceed with what we have.
     * Concurrent callers wait here so the first probe uses the fresh engine.
     */
    suspend fun ensureReady(context: Context) = withContext(Dispatchers.IO) {
        ensureInit(context)
        if (updated.get()) return@withContext
        updateMutex.withLock {
            if (updated.get()) return@withLock
            runCatching { YoutubeDL.getInstance().updateYoutubeDL(context) }
            updated.set(true)
        }
    }

    /** Probe a URL for title, thumbnail and available formats. */
    suspend fun getInfo(context: Context, url: String): MediaInfo =
        withContext(Dispatchers.IO) {
            // Kwai: yt-dlp can't extract it — use the dedicated WebView path.
            if (KwaiExtractor.isKwai(url)) {
                val v = KwaiExtractor.probe(url)
                return@withContext MediaInfo(
                    title = v.title,
                    thumbnail = v.thumbnail,
                    heights = emptyList(),
                    directStream = true,
                )
            }
            ensureReady(context)
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                applyTikTokFix(url)
            }
            val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
            val heights = info.formats
                ?.mapNotNull { fmt -> val h: Int? = fmt.height; if (h != null && h > 0) h else null }
                ?.distinct()
                ?.sortedDescending()
                .orEmpty()
            MediaInfo(
                title = info.title ?: "video",
                thumbnail = info.thumbnail,
                heights = heights,
                directStream = false,
            )
        }

    data class Options(
        val url: String,
        val audioOnly: Boolean,
        /** null / 0 = best available; otherwise a max height cap (e.g. 1080). */
        val maxHeight: Int?,
    )

    /**
     * Download to the app's private movies dir and return the resulting file.
     * onProgress(percent 0..100, statusLine) is called continuously.
     */
    suspend fun download(
        context: Context,
        options: Options,
        processId: String,
        onProgress: (Float, String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        // Kwai: custom scraper + direct stream (yt-dlp can't handle it).
        if (KwaiExtractor.isKwai(options.url)) {
            cancelledIds.remove(processId)
            return@withContext KwaiExtractor.download(
                context, options.url, onProgress
            ) { cancelledIds.contains(processId) }
        }

        ensureReady(context)

        val outDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "VideoGrabber"
        ).apply { mkdirs() }

        val before = outDir.listFiles()?.toSet().orEmpty()

        val request = YoutubeDLRequest(options.url).apply {
            addOption("--no-playlist")
            applyTikTokFix(options.url)
            addOption("-o", File(outDir, "%(title).100B [%(id)s].%(ext)s").absolutePath)
            addOption("--no-mtime")
            addOption("-R", "10")               // retries
            if (options.audioOnly) {
                addOption("-x")                 // extract audio
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
            } else {
                val h = options.maxHeight
                val fmt = if (h != null && h > 0) {
                    "bestvideo[height<=?$h]+bestaudio/best[height<=?$h]/best"
                } else {
                    "bestvideo*+bestaudio/best"
                }
                addOption("-f", fmt)
                addOption("--merge-output-format", "mp4")
            }
        }

        YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
            onProgress(progress, line)
        }

        // The freshly created file is whatever appeared in the dir.
        val after = outDir.listFiles()?.toList().orEmpty()
        after.filter { it !in before }
            .maxByOrNull { it.lastModified() }
            ?: after.maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("no output file produced")
    }

    fun cancel(processId: String) {
        cancelledIds.add(processId)   // signals the Kwai stream to stop
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    /**
     * TikTok's WEB extractor now requires browser impersonation (curl_cffi),
     * which youtubedl-android doesn't provide — hence "Unable to extract
     * universal data / no impersonate target". Forcing an app-API hostname
     * makes yt-dlp use TikTok's mobile API path, which needs no impersonation.
     * Verified working. Harmless for non-TikTok URLs.
     */
    private fun YoutubeDLRequest.applyTikTokFix(url: String) {
        if ("tiktok" in url.lowercase()) {
            addOption("--extractor-args",
                "tiktok:api_hostname=api22-normal-c-useast2a.tiktokv.com")
        }
    }
}
