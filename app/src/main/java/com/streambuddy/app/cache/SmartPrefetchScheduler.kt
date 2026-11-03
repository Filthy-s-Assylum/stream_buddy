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
import com.streambuddy.app.ai.PrefetchPlan
import com.streambuddy.app.data.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * Periodically asks the predictor what to cache next and starts those pre-cache jobs.
 *
 * It runs on an unmetered network while charging, so the "guessing" half of the feature never
 * competes with the user's metered data. The jobs it starts are ordinary [PreCacheWorker] jobs:
 * they show up in the queue with progress and can be cancelled like any other.
 */
class SmartPrefetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository.get(applicationContext).settings.value
        if (!settings.smartPrefetch) return Result.success()

        val picks = SmartPrefetch.plan(applicationContext, settings.prefetchMillis)
        var started = 0
        for (pick in picks) {
            val streamId = pick.prediction.candidate.streamId
            if (streamId <= 0L) continue
            if (SmartPrefetch.isAlreadyCached(applicationContext, streamId)) continue
            PreCacheWorker.enqueue(applicationContext, streamId, pick.depthMs)
            started++
            if (started >= PrefetchPlan.MAX_SCHEDULED_PER_RUN) break
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
