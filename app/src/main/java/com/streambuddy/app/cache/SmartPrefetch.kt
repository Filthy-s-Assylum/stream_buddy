package com.streambuddy.app.cache

import android.content.Context
import com.streambuddy.app.ai.Candidate
import com.streambuddy.app.ai.PrefetchPick
import com.streambuddy.app.ai.PrefetchPlan
import com.streambuddy.app.ai.PrefetchPredictor
import com.streambuddy.app.ai.Prediction
import com.streambuddy.app.ai.WatchEvent
import com.streambuddy.app.data.AppDb
import com.streambuddy.app.data.PredictorRepository
import com.streambuddy.app.data.SettingsRepository
import com.streambuddy.app.data.StreamEntity
import com.streambuddy.app.data.WatchEntity
import com.streambuddy.app.stream.CacheState
import com.streambuddy.app.stream.StreamKind

/**
 * The Android glue around [PrefetchPredictor]: reads history out of Room, ranks the queue, and
 * decides how deep to pre-cache each pick.
 *
 * Everything expensive (loading history, fitting) happens on a dispatcher supplied by the caller;
 * the predictor itself is pure maths and runs in microseconds for a queue-sized input.
 */
object SmartPrefetch {

    /** How much history the model is trained on. Older sessions add noise, not signal. */
    private const val HISTORY_LIMIT = 400

    /** Sessions older than this are deleted. */
    private const val HISTORY_RETENTION_DAYS = 120L

    /** Ranks the current queue. Empty when smart pre-caching is switched off. */
    suspend fun rankQueue(context: Context): List<Prediction> {
        if (!SettingsRepository.get(context).settings.value.smartPrefetch) return emptyList()
        val streams = AppDb.get(context).streams().allOnce()
            .filter { it.streamKind != StreamKind.TORRENT }
        if (streams.isEmpty()) return emptyList()
        val history = history(context)
        val model = PredictorRepository.get(context).state.value.model
        return PrefetchPredictor.rank(streams.map { it.toCandidate() }, history, model)
    }

    /**
     * The streams worth pre-caching right now, best first, each with its own depth derived from
     * the user's pre-play window.
     */
    suspend fun plan(context: Context, baseDepthMs: Long): List<PrefetchPick> =
        PrefetchPlan.plan(rankQueue(context), baseDepthMs)

    /** True when a stream is already cached or being cached, so there is nothing to schedule. */
    suspend fun isAlreadyCached(context: Context, streamId: Long): Boolean {
        val stream = AppDb.get(context).streams().findById(streamId) ?: return true
        return when (stream.cacheState) {
            CacheState.READY, CacheState.CACHING -> true
            CacheState.QUEUED, CacheState.PARTIAL, CacheState.FAILED, CacheState.UNSUPPORTED -> false
        }
    }

    /** Records one finished playback, prunes very old history, and refits the model. */
    suspend fun recordPlayback(context: Context, event: WatchEvent) {
        val db = AppDb.get(context)
        db.watchHistory().insert(WatchEntity.from(event))
        val cutoff = System.currentTimeMillis() - HISTORY_RETENTION_DAYS * 24L * 60L * 60L * 1000L
        db.watchHistory().deleteOlderThan(cutoff)
        refreshModel(context)
    }

    /** Refits the weights from stored history; cheap enough to run after every session. */
    suspend fun refreshModel(context: Context) {
        val dao = AppDb.get(context).watchHistory()
        val history = dao.recent(HISTORY_LIMIT).map { it.toEvent() }
        val repository = PredictorRepository.get(context)
        repository.learn(history)
        repository.noteHistorySize(history.size)
    }

    suspend fun history(context: Context): List<WatchEvent> =
        AppDb.get(context).watchHistory().recent(HISTORY_LIMIT).map { it.toEvent() }

    /** Forgets every recorded playback and drops the learned weights. */
    suspend fun clearHistory(context: Context) {
        AppDb.get(context).watchHistory().clear()
        PredictorRepository.get(context).reset()
    }

    private fun StreamEntity.toCandidate(): Candidate = Candidate(
        streamId = id,
        title = title,
        addedAtMs = addedAt,
    )
}
