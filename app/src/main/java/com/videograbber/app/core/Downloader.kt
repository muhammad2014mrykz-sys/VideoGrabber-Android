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

data class MediaInfo(
    val title: String,
    val thumbnail: String?,
    val heights: List<Int>,
    /** True when the visible browser-capture workflow must be used. */
    val directStream: Boolean,
)

/** Stable wrapper around the maintained youtubedl-android distribution. */
object Downloader {

    private val initialized = AtomicBoolean(false)
    private val updateAttempted = AtomicBoolean(false)
    private val initMutex = Mutex()
    private val updateMutex = Mutex()

    @Volatile
    var engineUpdateWarning: String? = null
        private set

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
     * Update once per process. Failure is recorded rather than silently
     * discarded; the bundled extractor remains usable while offline.
     */
    suspend fun ensureReady(context: Context) = withContext(Dispatchers.IO) {
        ensureInit(context)
        if (updateAttempted.get()) return@withContext
        updateMutex.withLock {
            if (updateAttempted.get()) return@withLock
            val result = runCatching { YoutubeDL.getInstance().updateYoutubeDL(context) }
            engineUpdateWarning = result.exceptionOrNull()?.message
            updateAttempted.set(true)
        }
    }

    suspend fun getInfo(context: Context, url: String): MediaInfo =
        withContext(Dispatchers.IO) {
            if (KwaiExtractor.isKwai(url)) {
                return@withContext MediaInfo(
                    title = "Kwai Browser Capture",
                    thumbnail = null,
                    heights = emptyList(),
                    directStream = true,
                )
            }

            ensureReady(context)
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                applyCommonOptions(url)
            }
            val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
            MediaInfo(
                title = info.title ?: "Video",
                thumbnail = info.thumbnail,
                heights = info.formats
                    ?.mapNotNull { it.height.takeIf { height -> height > 0 } }
                    ?.distinct()
                    ?.sortedDescending()
                    .orEmpty(),
                directStream = false,
            )
        }

    data class Options(
        val url: String,
        val audioOnly: Boolean,
        val maxHeight: Int?,
    )

    suspend fun download(
        context: Context,
        options: Options,
        processId: String,
        onProgress: (Float, String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        require(!KwaiExtractor.isKwai(options.url)) {
            "Kwai must be downloaded with Browser Capture."
        }
        ensureReady(context)

        val outDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "VideoGrabber",
        ).apply { mkdirs() }
        val before = outDir.listFiles()?.associateBy { it.absolutePath }.orEmpty()

        val request = YoutubeDLRequest(options.url).apply {
            addOption("--no-playlist")
            applyCommonOptions(options.url)
            addOption("-o", File(outDir, "%(title).100B [%(id)s].%(ext)s").absolutePath)
            addOption("--no-mtime")
            if (options.audioOnly) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
            } else {
                val height = options.maxHeight
                val format = if (height != null && height > 0) {
                    "bestvideo[height<=?$height]+bestaudio/best[height<=?$height]/best"
                } else {
                    "bestvideo*+bestaudio/best"
                }
                addOption("-f", format)
                addOption("--merge-output-format", "mp4")
            }
        }

        YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
            onProgress(progress.coerceIn(0f, 100f), line)
        }

        val after = outDir.listFiles()?.filter { it.isFile }.orEmpty()
        after.filter { it.absolutePath !in before }
            .maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("The downloader produced no output file.")
    }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    private fun YoutubeDLRequest.applyCommonOptions(url: String) {
        addOption("--socket-timeout", "30")
        addOption("--retries", "10")
        addOption("--fragment-retries", "10")
        addOption("--file-access-retries", "5")
        addOption("--remote-components", "ejs:github")

        val lower = url.lowercase()
        if ("tiktok.com" in lower) {
            // Forces TikTok's mobile API, avoiding curl_cffi browser
            // impersonation, which is unavailable in youtubedl-android.
            addOption(
                "--extractor-args",
                "tiktok:app_info=7318518857994389254;" +
                    "api_hostname=api22-normal-c-useast2a.tiktokv.com",
            )
        }
    }
}
