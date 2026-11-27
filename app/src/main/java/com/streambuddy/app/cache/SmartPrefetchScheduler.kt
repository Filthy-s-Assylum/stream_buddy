package com.streambuddy.app.cache

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streambuddy.app.AppGraph
import com.streambuddy.app.ai.PrefetchPlan
import com.streambuddy.app.data.AppDb
import com.streambuddy.app.data.LibraryRepository
import com.streambuddy.app.data.SettingsStore
import com.streambuddy.app.download.Downloads
import com.streambuddy.app.net.NetworkGuard
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodically asks the predictor what to cache next and starts those pre-cache jobs.
 *
 * Respects [NetworkGuard] so background pre-caching never runs without an active
 * VPN when "Require VPN" is enabled, and supports both active [LibraryRepository]
 * entries (cached directly into [PlaybackCache]) and legacy queue rows.
 */
class SmartPrefetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore.get(applicationContext).current()
        if (!settings.smartPrefetch) return Result.success()
        if (NetworkGuard.isBlocked()) return Result.retry()

        var started = 0
        val container = AppGraph.containerOrNull()
        val library = container?.libraryRepository
            ?: LibraryRepository(AppDb.get(applicationContext).library())
        val items = runCatching { library.items.first() }.getOrDefault(emptyList())
        if (items.isNotEmpty()) {
            val ranked = SmartPrefetch.rankLibrary(applicationContext, items)
            val picks = PrefetchPlan.plan(ranked, settings.prefetchMillis)
            val preCacher = container?.libraryPreCacher
            for (pick in picks) {
                val item = items.firstOrNull { it.id == pick.prediction.candidate.streamId } ?: continue
                if (!Downloads.isHttpUri(item.sourceUri.orEmpty())) continue
                if (runCatching { Downloads.completed(applicationContext, item.sourceUri.orEmpty()) }.getOrNull() != null) {
                    continue
                }
                if (preCacher != null) {
                    if (preCacher.states.value[item.id]?.state == com.streambuddy.app.stream.CacheState.READY) {
                        continue
                    }
                    preCacher.precacheBlocking(item, pick.depthMs)
                }
                started++
                if (started >= PrefetchPlan.MAX_SCHEDULED_PER_RUN) break
            }
        }

        if (started < PrefetchPlan.MAX_SCHEDULED_PER_RUN) {
            val picks = SmartPrefetch.plan(applicationContext, settings.prefetchMillis)
            for (pick in picks) {
                val streamId = pick.prediction.candidate.streamId
                if (streamId <= 0L) continue
                if (SmartPrefetch.isAlreadyCached(applicationContext, streamId)) continue
                PreCacheWorker.enqueue(applicationContext, streamId, pick.depthMs)
                started++
                if (started >= PrefetchPlan.MAX_SCHEDULED_PER_RUN) break
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "smart-prefetch"

        /** Schedules the periodic prediction pass (idempotent). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmartPrefetchWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Runs one prediction pass right away. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmartPrefetchWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
