package com.streambuddy.app.ai

/**
 * Turns a ranking into an amount of work.
 *
 * Pre-fetching everything the model likes would burn data and battery, so only the single best
 * guess gets a deep pre-play window; the next few get a cheap "warm start" so a wrong guess costs
 * little. Anything further down is left to stream on demand.
 */
object PrefetchPlan {

    /** Never pre-cache more than this for one pick, regardless of the user's setting. */
    const val MAX_DEPTH_MS: Long = 20L * 60_000L

    /** Enough to start playback and ride out a short stall. */
    const val WARM_START_MS: Long = 2L * 60_000L

    /** Below this probability the app does not spend bandwidth at all. */
    const val MIN_PROBABILITY = 0.55

    /** How many runner-ups get a warm start. */
    const val WARM_START_SLOTS = 2

    /** Cap on jobs started by one background prediction pass. */
    const val MAX_SCHEDULED_PER_RUN = 2

    /**
     * @param baseDepthMs the user's configured pre-play window.
     * @return the picks to pre-cache, best first; empty when nothing clears [MIN_PROBABILITY].
     */
    fun plan(
        ranked: List<Prediction>,
        baseDepthMs: Long,
        maxDepthMs: Long = MAX_DEPTH_MS,
    ): List<PrefetchPick> {
        if (ranked.isEmpty()) return emptyList()

        val picks = ArrayList<PrefetchPick>(1 + WARM_START_SLOTS)
        ranked.forEachIndexed { index, prediction ->
            if (prediction.probability < MIN_PROBABILITY) return@forEachIndexed
            val depth = when {
                index == 0 -> topDepth(prediction.probability, baseDepthMs, maxDepthMs)
                index <= WARM_START_SLOTS -> minOf(WARM_START_MS, baseDepthMs).coerceAtMost(maxDepthMs)
                else -> return@forEachIndexed
            }
            if (depth > 0L) picks += PrefetchPick(prediction, depth)
        }
        return picks
    }

    /** Short human summary, e.g. `Sitcom S01E11 (97%, 7 min)`. */
    fun summarize(picks: List<PrefetchPick>): String = if (picks.isEmpty()) {
        "Nothing scored high enough yet"
    } else {
        picks.joinToString(" · ") { pick ->
            "${pick.prediction.candidate.title} (${pick.prediction.percent}%, ${pick.depthMs / 60_000} min)"
        }
    }

    /**
     * Confident guesses get the full window (up to a 1.5x stretch), uncertain ones get less:
     * `base * (0.5 + probability)`, clamped to `2 min .. maxDepth`.
     */
    fun topDepth(probability: Double, baseDepthMs: Long, maxDepthMs: Long = MAX_DEPTH_MS): Long {
        if (baseDepthMs <= 0L) return 0L
        val scaled = (baseDepthMs * (0.5 + probability.coerceIn(0.0, 1.0))).toLong()
        val floor = minOf(WARM_START_MS, maxDepthMs)
        return scaled.coerceIn(floor, maxDepthMs)
    }
}
