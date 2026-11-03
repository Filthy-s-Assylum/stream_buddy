package com.streambuddy.app.ai

/**
 * On-device prediction of what you will want to watch next.
 *
 * This is deliberately *not* a cloud model or an LLM: pre-play caching needs a decision in
 * milliseconds, offline, on a phone. What it is: a small logistic-regression ranker that starts
 * from hand-picked prior weights and keeps learning from the playbacks recorded on this device
 * (a few dozen gradient steps per session). Every number it produces can be explained, which
 * matters because the app then spends your bandwidth and storage on the guess.
 *
 * Nothing in this package touches the network or Android APIs, so it is unit-tested on the JVM.
 */

/** One completed (or abandoned) playback, recorded on this device. */
data class WatchEvent(
    val streamId: Long,
    val title: String,
    val startedAtMs: Long,
    /** How far into the stream playback got, in milliseconds. */
    val watchedMs: Long,
    /** Total duration, or `0` when the player never learned it (live streams). */
    val durationMs: Long,
    /** Playback reached (or was within a few seconds of) the end. */
    val completed: Boolean,
) {
    /** 0..1 completion ratio; live/unknown duration falls back to a partial-watch prior. */
    val completion: Double
        get() = when {
            durationMs > 0L -> (watchedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
            completed -> 1.0
            else -> 0.35
        }
}

/** A queued stream the predictor can rank. */
data class Candidate(
    val streamId: Long,
    val title: String,
    val addedAtMs: Long,
)

/**
 * Model inputs, in the fixed order of the weight vector.
 *
 * All values except [BIAS] are normalised to `0..1`, so a weight is directly comparable to the
 * "how much does this feature count" question a human would ask.
 */
enum class Feature(val label: String) {
    BIAS("learned interest"),
    RECENCY("recently watched"),
    FREQUENCY("watched often"),
    COMPLETION("usually finished"),
    TIME_OF_DAY("usual time of day"),
    WEEKDAY("usual weekday"),
    NEXT_EPISODE("next episode"),
    SAME_SERIES("same series"),
    QUEUE_AGE("recently queued"),
}

/** A single feature's pull on the prediction, with a human-readable explanation. */
data class Reason(
    val feature: Feature,
    val contribution: Double,
    val detail: String,
)

/** Ranked guess for one candidate. */
data class Prediction(
    val candidate: Candidate,
    val probability: Double,
    val reasons: List<Reason>,
) {
    val percent: Int get() = (probability * 100.0).toInt().coerceIn(0, 100)
}

/** A ranked candidate plus how much of it is worth pre-caching. */
data class PrefetchPick(
    val prediction: Prediction,
    val depthMs: Long,
)
