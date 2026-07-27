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
import java.util.concurrent.ConcurrentHashMap
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

    private enum class Profile {
        DEFAULT,
        YOUTUBE_RESILIENT,
        YOUTUBE_ANDROID_VR,
        TIKTOK_PRIMARY,
        TIKTOK_FALLBACK,
        INSTAGRAM_IOS,
    }

    private val initialized = AtomicBoolean(false)
    private val updateAttempted = AtomicBoolean(false)
    private val initMutex = Mutex()
    private val updateMutex = Mutex()
    private val activeProcesses = ConcurrentHashMap<String, String>()

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
            var lastError: Exception? = null
            for (profile in profilesFor(url)) {
                val request = buildInfoRequest(url, profile)
                try {
                    val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
                    return@withContext MediaInfo(
                        title = info.title ?: "Video",
                        thumbnail = info.thumbnail,
                        heights = info.formats
                            ?.mapNotNull { it.height.takeIf { height -> height > 0 } }
                            ?.distinct()
                            ?.sortedDescending()
                            .orEmpty(),
                        directStream = false,
                    )
                } catch (error: Exception) {
                    if (isProtectedContent(error.message.orEmpty())) throw error
                    lastError = error
                }
            }
            throw lastError ?: IllegalStateException("No extractor profile succeeded.")
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

        val outRoot = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "VideoGrabber",
        ).apply { mkdirs() }
        val jobDir = File(outRoot, processId.replace(Regex("[^A-Za-z0-9_-]"), "_"))
            .apply { mkdirs() }
        var lastError: Exception? = null

        profilesFor(options.url).forEachIndexed { index, profile ->
            jobDir.listFiles()?.forEach { partial ->
                if (partial.isFile) partial.delete()
            }
            val request = buildDownloadRequest(options, jobDir, profile)
            val actualProcessId = "$processId-$index"
            activeProcesses[processId] = actualProcessId
            try {
                YoutubeDL.getInstance().execute(request, actualProcessId) { progress, _, line ->
                    onProgress(progress.coerceIn(0f, 100f), line)
                }
                val output = jobDir.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") }
                    ?.maxByOrNull { it.lastModified() }
                    ?: throw IllegalStateException("The downloader produced no output file.")
                return@withContext output
            } catch (error: Exception) {
                if (isProtectedContent(error.message.orEmpty())) throw error
                lastError = error
                if (index < profilesFor(options.url).lastIndex) {
                    onProgress(0f, "Retrying with a compatible extractor profile...")
                }
            } finally {
                activeProcesses.remove(processId, actualProcessId)
            }
        }
        throw lastError ?: IllegalStateException("Download failed for every extractor profile.")
    }

    fun cancel(processId: String) {
        val actualProcessId = activeProcesses[processId] ?: processId
        runCatching { YoutubeDL.getInstance().destroyProcessById(actualProcessId) }
    }

    private fun buildInfoRequest(url: String, profile: Profile) =
        YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            applyCommonOptions(profile)
        }

    private fun buildDownloadRequest(
        options: Options,
        outputDir: File,
        profile: Profile,
    ) = YoutubeDLRequest(options.url).apply {
        addOption("--no-playlist")
        applyCommonOptions(profile)
        addOption("-o", File(outputDir, "%(title).100B [%(id)s].%(ext)s").absolutePath)
        addOption("--no-mtime")
        if (options.audioOnly) {
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--audio-quality", "0")
        } else {
            addOption("-f", compatibleVideoFormat(options.maxHeight))
            addOption("--merge-output-format", "mp4")
        }
    }

    private fun YoutubeDLRequest.applyCommonOptions(profile: Profile) {
        addOption("--ignore-config")
        addOption("--socket-timeout", "30")
        addOption("--retries", "10")
        addOption("--fragment-retries", "10")
        addOption("--file-access-retries", "5")
        addOption("--extractor-retries", "3")
        addOption("--concurrent-fragments", "4")

        when (profile) {
            Profile.YOUTUBE_RESILIENT -> {
                // android_vr stays usable without browser impersonation or a PO
                // token; web is also queried when EJS can expose higher formats.
                addOption("--extractor-args", "youtube:player_client=android_vr,web")
                addOption("--remote-components", "ejs:github")
            }
            Profile.YOUTUBE_ANDROID_VR ->
                addOption("--extractor-args", "youtube:player_client=android_vr")
            Profile.TIKTOK_PRIMARY ->
                addOption(
                    "--extractor-args",
                    "tiktok:app_info=7318518857994389254/trill/35.1.3/2023501030/1180;" +
                        "api_hostname=api16-normal-c-useast1a.tiktokv.com",
                )
            Profile.TIKTOK_FALLBACK ->
                addOption(
                    "--extractor-args",
                    "tiktok:app_info=7318518857994389254/trill/35.1.3/2023501030/1180;" +
                        "api_hostname=api22-normal-c-useast2a.tiktokv.com",
                )
            Profile.INSTAGRAM_IOS ->
                addOption("--extractor-args", "instagram:app_id=ios")
            Profile.DEFAULT -> Unit
        }
    }

    private fun profilesFor(url: String): List<Profile> {
        val lower = url.lowercase()
        return when {
            "youtu.be" in lower || "youtube.com" in lower ->
                listOf(Profile.YOUTUBE_RESILIENT, Profile.YOUTUBE_ANDROID_VR)
            "tiktok.com" in lower ->
                listOf(Profile.TIKTOK_PRIMARY, Profile.TIKTOK_FALLBACK)
            "instagram.com" in lower || "instagr.am" in lower ->
                listOf(Profile.DEFAULT, Profile.INSTAGRAM_IOS)
            else -> listOf(Profile.DEFAULT)
        }
    }

    private fun compatibleVideoFormat(maxHeight: Int?): String {
        val heightFilter = maxHeight?.takeIf { it > 0 }?.let { "[height<=?$it]" }.orEmpty()
        return "bestvideo$heightFilter[vcodec^=avc1]+bestaudio[acodec^=mp4a]/" +
            "bestvideo$heightFilter[ext=mp4]+bestaudio[ext=m4a]/" +
            "bestvideo$heightFilter+bestaudio/best$heightFilter/best"
    }

    fun isProtectedContent(message: String): Boolean {
        val lower = message.lowercase()
        return listOf(
            "members-only",
            "channel's members",
            "join this channel",
            "requires payment",
            "purchase this",
            "rent this",
            "drm protected",
            "drm-protected",
            "private video",
        ).any { it in lower }
    }
}
