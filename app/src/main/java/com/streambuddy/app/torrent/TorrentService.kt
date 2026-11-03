package com.streambuddy.app.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.streambuddy.app.AppGraph
import com.streambuddy.app.MainActivity
import com.streambuddy.app.R
import com.streambuddy.app.core.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the swarm alive while the screen is off. The notification intentionally
 * contains no magnet, file path, peer address or tracker URL.
 */
class TorrentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(TorrentUiState()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        stateJob = scope.launch {
            val manager = AppGraph.containerOrNull()?.torrentManager ?: return@launch
            manager.state.collectLatest { state ->
                val notification = buildNotification(state)
                val managerService = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                managerService.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(state: TorrentUiState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (state.phase) {
            TorrentPhase.FETCHING_METADATA -> "Finding peers"
            TorrentPhase.BUFFERING -> "Buffering first pieces"
            TorrentPhase.PAUSED -> "Torrent paused"
            TorrentPhase.SEEDING -> "Seeding (opt-in)"
            TorrentPhase.ERROR -> "Torrent stopped"
            else -> "Streaming torrent"
        }
        val text = buildString {
            append(state.title.ifBlank { "Stream Buddy" })
            if (state.selectedFileSize > 0) {
                append(" · ").append(Fmt.percent(state.progress))
                if (state.downloadRateBps > 0) {
                    append(" · ").append(Fmt.rate(state.downloadRateBps.toDouble()))
                }
                if (state.peers > 0) append(" · ").append(state.peers).append(" peers")
            }
            if (!state.error.isNullOrBlank()) append(" · ").append(state.error)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(1_000, (state.progress * 1_000).toInt().coerceIn(0, 1_000), state.progress <= 0.0)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.torrent_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    description = "Progress for the torrent you are streaming"
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "sb_torrent"
        private const val NOTIFICATION_ID = 4103

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TorrentService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TorrentService::class.java)) }
        }
    }
}
