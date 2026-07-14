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
object Downloader {

    private val initialized = AtomicBoolean(false)
    private val initMutex = Mutex()

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

    /** Best-effort keep yt-dlp current so new/changed sites keep working. */
    suspend fun updateEngine(context: Context) = withContext(Dispatchers.IO) {
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(context) }
    }

    /** Probe a URL for title, thumbnail and available formats. */
    suspend fun getInfo(context: Context, url: String): VideoInfo =
        withContext(Dispatchers.IO) {
            ensureInit(context)
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
            }
            YoutubeDL.getInstance().getInfo(request)
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
        ensureInit(context)

        val outDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "VideoGrabber"
        ).apply { mkdirs() }

        val before = outDir.listFiles()?.toSet().orEmpty()

        val request = YoutubeDLRequest(options.url).apply {
            addOption("--no-playlist")
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
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }
}
