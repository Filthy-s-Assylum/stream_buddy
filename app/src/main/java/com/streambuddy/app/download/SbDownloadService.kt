package com.streambuddy.app.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.streambuddy.app.MainActivity
import com.streambuddy.app.R

/**
 * Media3's DownloadService owns the actual cache-writing loop. It is started
 * foreground when the user queues a download so Android does not kill the
 * transfer when the app is backgrounded.
 */
@OptIn(UnstableApi::class)
class SbDownloadService : DownloadService(
    Downloads.FOREGROUND_NOTIFICATION_ID,
    Downloads.FOREGROUND_UPDATE_MS,
    Downloads.CHANNEL_ID,
    R.string.download_channel_name,
    0,
) {

    override fun getDownloadManager() = Downloads.manager(this)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return DownloadNotificationHelper(this, Downloads.CHANNEL_ID).buildProgressNotification(
            this,
            R.drawable.ic_notification,
            contentIntent,
            null,
            downloads,
            notMetRequirements,
        )
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        val downloadManager = getDownloadManager()
        if (downloadManager.isIdle) {
            stopSelf()
        }
    }

    companion object {
        fun sendAddDownload(context: android.content.Context, request: androidx.media3.exoplayer.offline.DownloadRequest, foreground: Boolean) {
            DownloadService.sendAddDownload(context, SbDownloadService::class.java, request, foreground)
        }

        fun sendRemoveDownload(context: android.content.Context, id: String, foreground: Boolean) {
            DownloadService.sendRemoveDownload(context, SbDownloadService::class.java, id, foreground)
        }

        fun sendPauseDownloads(context: android.content.Context, foreground: Boolean) {
            DownloadService.sendPauseDownloads(context, SbDownloadService::class.java, foreground)
        }

        fun sendResumeDownloads(context: android.content.Context, foreground: Boolean) {
            DownloadService.sendResumeDownloads(context, SbDownloadService::class.java, foreground)
        }
    }
}
