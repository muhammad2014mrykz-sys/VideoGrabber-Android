package com.videograbber.app.core

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DownloadItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val dateMillis: Long,
    val mime: String,
    val filePath: String? = null,   // set on API < 29 (app-dir file)
)

/** Lists / opens / shares / deletes the videos this app has downloaded. */
object DownloadsRepository {

    private const val FOLDER = "VideoGrabber"

    suspend fun list(context: Context): List<DownloadItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) queryMediaStore(context)
        else listAppDir(context)
    }

    private fun queryMediaStore(context: Context): List<DownloadItem> {
        val out = mutableListOf<DownloadItem>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.MIME_TYPE,
        )
        val sel = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%$FOLDER%")
        val sort = "${MediaStore.Downloads.DATE_ADDED} DESC"
        context.contentResolver.query(collection, proj, sel, args, sort)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out.add(
                    DownloadItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(iName) ?: "video",
                        sizeBytes = c.getLong(iSize),
                        dateMillis = c.getLong(iDate) * 1000L,
                        mime = c.getString(iMime) ?: "video/mp4",
                    )
                )
            }
        }
        return out
    }

    private fun listAppDir(context: Context): List<DownloadItem> {
        val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), FOLDER)
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            DownloadItem(
                uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f),
                name = f.name,
                sizeBytes = f.length(),
                dateMillis = f.lastModified(),
                mime = if (f.extension.equals("mp3", true)) "audio/mpeg" else "video/mp4",
                filePath = f.absolutePath,
            )
        }
    }

    fun open(context: Context, item: DownloadItem) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun share(context: Context, item: DownloadItem) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = item.mime
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    suspend fun delete(context: Context, item: DownloadItem): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (item.filePath != null) {
                    File(item.filePath).delete()
                } else {
                    context.contentResolver.delete(item.uri, null, null) > 0
                }
            } catch (e: Exception) {
                false
            }
        }

    suspend fun thumbnail(context: Context, item: DownloadItem): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(item.uri, Size(480, 480), null)
                } else if (item.filePath != null) {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(
                        item.filePath, MediaStore.Images.Thumbnails.MINI_KIND
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
}
