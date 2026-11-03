package com.streambuddy.app.cache

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.streambuddy.app.MainActivity
import com.streambuddy.app.R
import com.streambuddy.app.data.AppDb
import com.streambuddy.app.data.SettingsRepository
import com.streambuddy.app.data.StreamDao
import com.streambuddy.app.data.StreamEntity
import com.streambuddy.app.stream.CacheState
import com.streambuddy.app.stream.DrmProtectedException
import com.streambuddy.app.stream.PlaylistParseException
import com.streambuddy.app.stream.PreCacheEngine
import com.streambuddy.app.stream.UnsupportedStreamException
import com.streambuddy.app.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Background pre-cache job for one queue entry.
 *
 * State is written to Room as it progresses (so the UI is live) and echoed to a low-priority
 * progress notification. Work is resumable: a retry continues from whatever is already cached.
 */
class PreCacheWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val streamId = inputData.getLong(KEY_STREAM_ID, NO_STREAM_ID)
        if (streamId == NO_STREAM_ID) return Result.failure()

        val dao = AppDb.get(applicationContext).streams()
        val stream = dao.findById(streamId) ?: return Result.failure()

        val settings = SettingsRepository.get(applicationContext).settings.value
        val requestedDepthMs = inputData.getLong(KEY_DEPTH_MS, NO_DEPTH_MS)
        val depthMs = when {
            requestedDepthMs > 0L -> requestedDepthMs
            stream.prefetchMillis > 0L -> stream.prefetchMillis
            else -> settings.prefetchMillis
        }

        dao.resetForCaching(streamId, depthMs, CacheState.CACHING.name, now())
        post(stream, progressNotification(stream, "Preparing…", percent = null, indeterminate = true))

        val latest = AtomicReference<PreCacheEngine.Progress?>(null)
        val engine = PreCacheEngine(applicationContext)

        return try {
            val outcome = coroutineScope {
                val reporter = launch {
                    while (isActive) {
                        delay(PROGRESS_INTERVAL_MS)
                        val progress = latest.getAndSet(null) ?: continue
                        dao.updateProgress(
                            id = streamId,
                            state = CacheState.CACHING.name,
                            cachedBytes = progress.cachedBytes,
                            totalBytes = progress.totalBytes,
                            cachedMillis = progress.cachedDurationMs,
                            totalMillis = progress.totalDurationMs,
                            prefetchMillis = depthMs,
                            error = null,
                            now = now(),
                        )
                        post(
                            stream,
                            progressNotification(
                                stream = stream,
                                text = progressText(progress),
                                percent = percentOf(progress),
                                indeterminate = false,
                            ),
                        )
                    }
                }
                try {
                    engine.prefetch(
                        url = stream.url,
                        kindHint = stream.streamKind,
                        depthMs = depthMs,
                        preferHighestQuality = settings.preferHighestQuality,
                        onProgress = { progress -> latest.set(progress) },
                    )
                } finally {
                    // Wait for the reporter to stop before the caller overwrites the row with the
                    // final state, otherwise a queued progress write could land after it.
                    withContext(NonCancellable) {
                        reporter.cancel()
                        reporter.join()
                    }
                }
            }

            dao.updateProgress(
                id = streamId,
                state = CacheState.READY.name,
                cachedBytes = outcome.cachedBytes,
                totalBytes = outcome.totalBytes,
                cachedMillis = outcome.cachedDurationMs,
                totalMillis = outcome.totalDurationMs,
                prefetchMillis = depthMs,
                error = null,
                now = now(),
            )
            post(
                stream,
                doneNotification(
                    stream = stream,
                    text = "Ready to play · ${Format.bytes(outcome.cachedBytes)} cached",
                    success = true,
                ),
            )
            Result.success()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                dao.updateState(streamId, CacheState.QUEUED.name, "Cancelled", now())
                NotificationManagerCompat.from(applicationContext).cancel(notificationId(streamId))
            }
            throw cancellation
        } catch (drm: DrmProtectedException) {
            val message = "${drm.scheme} protected — open the app to watch it"
            recordFailure(dao, streamId, depthMs, CacheState.UNSUPPORTED, message)
            post(stream, doneNotification(stream, message, success = false))
            Result.success() // retrying cannot help
        } catch (unsupported: UnsupportedStreamException) {
            val message = unsupported.message ?: "This stream cannot be pre-cached"
            recordFailure(dao, streamId, depthMs, CacheState.UNSUPPORTED, message)
            post(stream, doneNotification(stream, message, success = false))
            Result.success()
        } catch (parse: PlaylistParseException) {
            val message = parse.message ?: "The playlist could not be parsed"
            recordFailure(dao, streamId, depthMs, CacheState.FAILED, message)
            post(stream, doneNotification(stream, message, success = false))
            Result.failure()
        } catch (io: IOException) {
            val message = io.message ?: "Network error"
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                recordFailure(dao, streamId, depthMs, CacheState.QUEUED, message)
                post(stream, doneNotification(stream, "Will retry: $message", success = false))
                Result.retry()
            } else {
                recordFailure(dao, streamId, depthMs, CacheState.FAILED, message)
                post(stream, doneNotification(stream, message, success = false))
                Result.failure()
            }
        } catch (unexpected: Throwable) {
            val message = unexpected.message ?: unexpected.javaClass.simpleName
            recordFailure(dao, streamId, depthMs, CacheState.FAILED, message)
            post(stream, doneNotification(stream, message, success = false))
            Result.failure()
        }
    }

    private suspend fun recordFailure(
        dao: StreamDao,
        streamId: Long,
        depthMs: Long,
        state: CacheState,
        message: String,
    ) {
        val existing = dao.findById(streamId) ?: return
        val effectiveState = if (
            state == CacheState.FAILED && (existing.cachedBytes > 0L || existing.cachedMillis > 0L)
        ) {
            CacheState.PARTIAL
        } else {
            state
        }
        dao.updateProgress(
            id = streamId,
            state = effectiveState.name,
            cachedBytes = existing.cachedBytes,
            totalBytes = existing.totalBytes,
            cachedMillis = existing.cachedMillis,
            totalMillis = existing.totalMillis,
            prefetchMillis = depthMs,
            error = Format.shortMessage(message),
            now = now(),
        )
    }

    private fun progressText(progress: PreCacheEngine.Progress): String {
        val percent = percentOf(progress)
        return when {
            progress.totalDurationMs > 0L ->
                "Caching ${Format.duration(progress.cachedDurationMs)} of " +
                    "${Format.duration(progress.totalDurationMs)}" + (percent?.let { " · $it%" } ?: "")
            progress.cachedBytes > 0L -> "Cached ${Format.bytes(progress.cachedBytes)}"
            else -> "Preparing…"
        }
    }

    private fun percentOf(progress: PreCacheEngine.Progress): Int? = when {
        progress.totalDurationMs > 0L -> Format.percent(progress.cachedDurationMs, progress.totalDurationMs)
        progress.totalBytes > 0L -> Format.percent(progress.cachedBytes, progress.totalBytes)
        progress.segmentsTotal > 1 -> (progress.segmentsDone * 100) / progress.segmentsTotal
        else -> null
    }

    private fun progressNotification(
        stream: StreamEntity,
        text: String,
        percent: Int?,
        indeterminate: Boolean,
    ): Notification {
        ensureChannel()
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(stream.title)
            .setContentText(text)
            .setContentIntent(openAppIntent(stream.id))
            .addAction(
                0,
                applicationContext.getString(R.string.notification_action_cancel),
                cancelIntent(stream.id),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent ?: 0, indeterminate)
            .build()
    }

    private fun doneNotification(stream: StreamEntity, text: String, success: Boolean): Notification {
        ensureChannel()
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error,
            )
            .setContentTitle(stream.title)
            .setContentText(text)
            .setContentIntent(openAppIntent(stream.id))
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Tapping the notification opens the app on the player tab. */
    private fun openAppIntent(streamId: Long): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_STREAM_ID, streamId)
        return PendingIntent.getActivity(
            applicationContext,
            notificationId(streamId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(streamId: Long): PendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        notificationId(streamId),
        CancelPreCacheReceiver.intent(applicationContext, streamId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(stream: StreamEntity, notification: Notification) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(notificationId(stream.id), notification)
        } catch (_: SecurityException) {
            // Notification permission was revoked between the check and the call.
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pre-cache progress", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Shows how much of a stream has been cached for playback"
                    setShowBadge(false)
                },
        )
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val KEY_STREAM_ID = "stream_id"
        private const val KEY_DEPTH_MS = "depth_ms"
        private const val NO_STREAM_ID = -1L
        private const val NO_DEPTH_MS = 0L

        /** Intent extra read by [MainActivity] to jump to a stream after a notification tap. */
        const val EXTRA_OPEN_STREAM_ID = "open_stream_id"

        /** Stable per-stream notification id (fits in the positive int range). */
        fun notificationId(streamId: Long): Int = (streamId and 0x7FFFFFFFL).toInt()
        private const val MAX_ATTEMPTS = 3
        private const val PROGRESS_INTERVAL_MS = 700L
        private const val CHANNEL_ID = "precache_progress"

        /** Queues (or restarts) the pre-cache job for [streamId]. */
        fun enqueue(context: Context, streamId: Long, depthMs: Long = 0L) {
            val request = OneTimeWorkRequestBuilder<PreCacheWorker>()
                .setInputData(workDataOf(KEY_STREAM_ID to streamId, KEY_DEPTH_MS to depthMs))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(streamId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Cancels a running job for [streamId] (already-cached data is kept). */
        fun cancel(context: Context, streamId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(streamId))
        }

        private fun uniqueName(streamId: Long): String = "precache-$streamId"
    }
}
