package com.videograbber.app.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes a downloaded file into the public Downloads folder via MediaStore
 * so it shows up in the Files app / gallery. Works without storage permission
 * on Android 10+ (scoped storage); falls back to a direct copy on older APIs.
 */
object MediaExport {

    suspend fun publishToDownloads(context: Context, file: File): String =
        withContext(Dispatchers.IO) {
            val mime = if (file.extension.equals("mp3", true)) "audio/mpeg"
            else "video/mp4"
            val displayName = file.name

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/VideoGrabber"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw IllegalStateException("MediaStore insert failed")

                resolver.openOutputStream(uri).use { out ->
                    file.inputStream().use { input -> input.copyTo(out!!) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                file.delete()
                "Downloads/VideoGrabber/$displayName"
            } else {
                // API < 29: keep the file in app-specific storage (no storage
                // permission needed). The in-app Library lists it and shares it
                // via FileProvider.
                file.absolutePath
            }
        }
}
