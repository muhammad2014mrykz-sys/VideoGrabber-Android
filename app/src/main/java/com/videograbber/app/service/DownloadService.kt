package com.videograbber.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.videograbber.app.DownloaderApp
import com.videograbber.app.R
import com.videograbber.app.core.DownloadBus
import com.videograbber.app.core.Downloader
import com.videograbber.app.core.MediaExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that performs a single download so it keeps running (and
 * shows a progress notification) even if the app is backgrounded.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var processId: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Downloader.cancel(processId)
            DownloadBus.update(DownloadBus.State.Idle)
            stop()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        val audioOnly = intent?.getBooleanExtra(EXTRA_AUDIO, false) ?: false
        val maxHeight = intent?.getIntExtra(EXTRA_HEIGHT, 0) ?: 0
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.notif_downloading)
        processId = "vg-" + System.currentTimeMillis()

        startForegroundWith(0, title)
        DownloadBus.update(DownloadBus.State.Preparing)

        job = scope.launch {
            try {
                val file: File = Downloader.download(
                    context = applicationContext,
                    options = Downloader.Options(url, audioOnly, maxHeight.takeIf { it > 0 }),
                    processId = processId,
                ) { percent, line ->
                    DownloadBus.update(DownloadBus.State.Running(percent, line))
                    updateNotification(percent.toInt(), title)
                }
                val saved = MediaExport.publishToDownloads(applicationContext, file)
                DownloadBus.update(DownloadBus.State.Success(saved))
            } catch (e: Exception) {
                DownloadBus.update(
                    DownloadBus.State.Error(e.message ?: getString(R.string.err_generic))
                )
            } finally {
                stop()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWith(percent: Int, title: String) {
        val notif = buildNotification(percent, title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(percent: Int, title: String) =
        NotificationCompat.Builder(this, DownloaderApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$percent%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(percent: Int, title: String) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIF_ID, buildNotification(percent, title))
        }
    }

    private fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 42
        const val ACTION_CANCEL = "cancel"
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_TITLE = "title"

        fun start(
            context: Context, url: String, audioOnly: Boolean,
            maxHeight: Int, title: String,
        ) {
            val i = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_AUDIO, audioOnly)
                putExtra(EXTRA_HEIGHT, maxHeight)
                putExtra(EXTRA_TITLE, title)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun cancel(context: Context) {
            val i = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(i)
        }
    }
}
