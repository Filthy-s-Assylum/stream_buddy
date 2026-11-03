package com.streambuddy.app.ai

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Logistic-regression ranker over [Feature]s, learned on the device.
 *
 * Read this before trusting it: the model has nine inputs, one weight per input, and it is fitted
 * with plain SGD on the playbacks this install recorded. It is not a neural network, it ships no
 * viewing data anywhere, and every prediction comes with the reasons that produced it (see
 * [Prediction.reasons]). That is the honest version of "AI caching" a phone can actually run in
 * the milliseconds between queueing a stream and deciding what to download.
 */
object PrefetchPredictor {

    /** Hand-tuned starting point, used until enough playbacks exist to learn from. */
    val PRIOR_WEIGHTS: List<Double> = listOf(
        -1.20, // BIAS
        1.80, // RECENCY
        0.70, // FREQUENCY
        0.80, // COMPLETION
        0.60, // TIME_OF_DAY
        0.30, // WEEKDAY
        2.20, // NEXT_EPISODE
        1.00, // SAME_SERIES
        0.90, // QUEUE_AGE
    )

    /** Below this many recorded playbacks the weights are still just the priors. */
    const val MIN_EVENTS_FOR_TRAINING = 8

    private const val EPOCHS = 40
    private const val LEARNING_RATE = 0.08
    private const val L2 = 0.01
    private const val MAX_EVENTS = 400
    private const val MAX_NEGATIVES_PER_EVENT = 2
    private const val RECENCY_HALF_LIFE_HOURS = 72.0
    private const val QUEUE_HALF_LIFE_DAYS = 14.0
    private const val NEUTRAL = 0.5
    private const val MIN_REASON_CONTRIBUTION = 0.05

    /**
     * Probability temperature. Nine features cannot honestly be 99 % sure, so the raw logistic
     * score is divided by this before the sigmoid, which pulls extremes toward 50 %.
     */
    private const val TEMPERATURE = 1.7
    private const val HOUR_MS = 3_600_000.0
    private const val TWO_PI = 2.0 * Math.PI

    /** Learned weights plus a record of what they were learned from. */
    data class ModelState(val weights: List<Double>, val trainedOn: Int, val updatedAtMs: Long) {
        val hasLearnedAnything: Boolean get() = trainedOn >= MIN_EVENTS_FOR_TRAINING

        companion object {
            fun prior(): ModelState = ModelState(PRIOR_WEIGHTS, 0, 0L)
        }
    }

    // region ranking

    /**
     * Scores [candidates] against [history]; higher probability means "more likely to be watched
     * next". Ties keep the caller's order.
     */
    fun rank(
        candidates: List<Candidate>,
        history: List<WatchEvent>,
        model: ModelState,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): List<Prediction> {
        val memory = Memory.of(history, zone)
        val weights = model.weights.toDoubleArray()
        return candidates
            .map { candidate -> predict(candidate, memory, weights, nowMs, zone) }
            .sortedWith(
                compareByDescending<Prediction> { it.probability }
                    .thenByDescending { it.candidate.addedAtMs },
            )
    }

    private fun predict(
        candidate: Candidate,
        memory: Memory,
        weights: DoubleArray,
        nowMs: Long,
        zone: TimeZone,
    ): Prediction {
        val parsed = SeriesMatcher.parse(candidate.title)
        val series = memory.forSeries(parsed.seriesKey)
        val values = features(
            series = series,
            parsed = parsed,
            lastWatch = memory.lastWatch,
            nowMs = nowMs,
            addedAtMs = candidate.addedAtMs,
            zone = zone,
        )

        var score = 0.0
        val reasons = ArrayList<Reason>(Feature.entries.size)
        for (feature in Feature.entries) {
            val value = values[feature.ordinal]
            val contribution = weights[feature.ordinal] * value
            score += contribution
            if (isInformative(feature, value, hasHistory = series != null)) {
                reasons += Reason(feature, contribution, describe(feature, value, series, parsed, nowMs))
            }
        }

        val top = reasons
            .filter { abs(it.contribution) >= MIN_REASON_CONTRIBUTION }
            .sortedByDescending { abs(it.contribution) }
            .take(3)
        return Prediction(candidate, sigmoid(score / TEMPERATURE), top)
    }

    private fun features(
        series: SeriesMemory?,
        parsed: SeriesMatcher.Parsed,
        lastWatch: WatchEvent?,
        nowMs: Long,
        addedAtMs: Long,
        zone: TimeZone,
    ): DoubleArray = doubleArrayOf(
        1.0, // BIAS
        series?.recencyAt(nowMs) ?: 0.0,
        series?.frequency ?: 0.0,
        series?.completion ?: 0.0,
        series?.timeMatch(nowMs, zone) ?: NEUTRAL,
        series?.weekdayMatch(nowMs, zone) ?: NEUTRAL,
        if (parsed.hasEpisode && lastWatch != null && SeriesMatcher.isNextEpisode(parsed, parsedOf(lastWatch))) 1.0 else 0.0,
        if (lastWatch != null && parsedOf(lastWatch).seriesKey == parsed.seriesKey) 1.0 else 0.0,
        decay((nowMs - addedAtMs) / HOUR_MS, QUEUE_HALF_LIFE_DAYS * 24.0),
    )

    /**
     * Drops features that carry no information for this candidate (a stream with no history,
     * a time-of-day feature that fell back to neutral, the constant bias) so the UI only shows
     * reasons that actually moved the prediction.
     */
    private fun isInformative(feature: Feature, value: Double, hasHistory: Boolean): Boolean =
        when (feature) {
            Feature.BIAS -> false
            Feature.RECENCY, Feature.FREQUENCY, Feature.COMPLETION -> hasHistory
            Feature.TIME_OF_DAY, Feature.WEEKDAY -> hasHistory && value != NEUTRAL
            Feature.NEXT_EPISODE, Feature.SAME_SERIES -> value > 0.0
            Feature.QUEUE_AGE -> value >= 0.5
        }

    private fun describe(
        feature: Feature,
        value: Double,
        series: SeriesMemory?,
        parsed: SeriesMatcher.Parsed,
        nowMs: Long,
    ): String = when (feature) {
        Feature.BIAS -> "Overall prior"
        Feature.RECENCY -> series?.let { "Watched ${relative(nowMs - it.lastWatchedAtMs)}" }
            ?: "Never watched"
        Feature.FREQUENCY -> series?.let {
            "Watched ${it.watchCount} time${if (it.watchCount == 1) "" else "s"} before"
        } ?: "No history"
        Feature.COMPLETION -> series?.let { "Usually ${(it.completion * 100).toInt()}% completed" }
            ?: "No history"
        Feature.TIME_OF_DAY -> if (value == NEUTRAL) {
            "No time-of-day pattern yet"
        } else {
            "Matches when you watch ${parsed.seriesKey}"
        }
        Feature.WEEKDAY -> if (value == NEUTRAL) {
            "No weekday pattern yet"
        } else {
            "Matches your usual day for ${parsed.seriesKey}"
        }
        Feature.NEXT_EPISODE -> "Next episode after your last watch"
        Feature.SAME_SERIES -> "Same series as your last watch"
        Feature.QUEUE_AGE -> "Recently added to the queue"
    }

    // endregion

    // region learning

    /**
     * Fits the weights against [history] with temporally honest samples: every playback is scored
     * using only the events that preceded it, with same-night unwatched series as negatives.
     *
     * [Feature.QUEUE_AGE] needs a queue timestamp history does not store, so it is held at zero
     * while fitting and keeps its prior weight.
     */
    fun train(
        history: List<WatchEvent>,
        model: ModelState = ModelState.prior(),
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): ModelState {
        val events = history.sortedBy { it.startedAtMs }.takeLast(MAX_EVENTS)
        if (events.size < MIN_EVENTS_FOR_TRAINING) return model

        val weights = model.weights.toDoubleArray()
        val learnable = 0 until Feature.QUEUE_AGE.ordinal

        repeat(EPOCHS) {
            for (index in events.indices) {
                val event = events[index]
                val past = events.subList(0, index)
                if (past.isEmpty()) continue
                val memory = Memory.of(past, zone)

                val watchedKey = parsedOf(event).seriesKey
                val rows = ArrayList<TrainingRow>(1 + MAX_NEGATIVES_PER_EVENT)
                rows += trainingRow(event.title, event.startedAtMs, 1.0, memory, zone)
                memory.seriesKeys()
                    .asSequence()
                    .filter { it != watchedKey }
                    .take(MAX_NEGATIVES_PER_EVENT)
                    .forEach { key ->
                        rows += trainingRow(key, event.startedAtMs, 0.0, memory, zone)
                    }

                for (row in rows) {
                    val error = row.label - sigmoid(dot(weights, row.values))
                    for (featureIndex in learnable) {
                        val gradient = error * row.values[featureIndex] - L2 * weights[featureIndex]
                        weights[featureIndex] += LEARNING_RATE * gradient
                    }
                }
            }
        }

        return ModelState(weights.toList(), events.size, nowMs)
    }

    private class TrainingRow(val values: DoubleArray, val label: Double)

    private fun trainingRow(
        title: String,
        atMs: Long,
        label: Double,
        memory: Memory,
        zone: TimeZone,
    ): TrainingRow {
        val parsed = SeriesMatcher.parse(title)
        val values = features(
            series = memory.forSeries(parsed.seriesKey),
            parsed = parsed,
            lastWatch = memory.lastWatch,
            nowMs = atMs,
            addedAtMs = atMs,
            zone = zone,
        )
        values[Feature.QUEUE_AGE.ordinal] = 0.0
        return TrainingRow(values, label)
    }

    // endregion

    // region memory

    /** Aggregated behaviour for one series, computed from one specific set of events. */
    private class SeriesMemory(
        val watchCount: Int,
        val lastWatchedAtMs: Long,
        val completion: Double,
        private val hourVector: DoubleArray?,
        private val dayVector: DoubleArray?,
    ) {
        fun recencyAt(nowMs: Long): Double =
            decay((nowMs - lastWatchedAtMs) / HOUR_MS, RECENCY_HALF_LIFE_HOURS)

        val frequency: Double get() = 1.0 - exp(-watchCount / 3.0)

        fun timeMatch(nowMs: Long, zone: TimeZone): Double =
            circularMatch(hourVector, angleOf(nowMs, zone, hoursPerCycle = 24))

        fun weekdayMatch(nowMs: Long, zone: TimeZone): Double =
            circularMatch(dayVector, angleOf(nowMs, zone, hoursPerCycle = 24 * 7))
    }

    private class Memory(private val bySeries: Map<String, SeriesMemory>, val lastWatch: WatchEvent?) {
        fun forSeries(key: String): SeriesMemory? = bySeries[key]

        fun seriesKeys(): List<String> = bySeries.keys.toList()

        companion object {
            fun of(events: List<WatchEvent>, zone: TimeZone): Memory {
                val last = events.maxByOrNull { it.startedAtMs }
                val grouped = events.groupBy { parsedOf(it).seriesKey }
                val memories = grouped.mapValues { (_, seriesEvents) ->
                    val latest = seriesEvents.maxByOrNull { it.startedAtMs }
                    SeriesMemory(
                        watchCount = seriesEvents.size,
                        lastWatchedAtMs = latest?.startedAtMs ?: 0L,
                        completion = seriesEvents.map { it.completion }.average().takeIf { !it.isNaN() } ?: 0.0,
                        hourVector = circularMean(seriesEvents.map { angleOf(it.startedAtMs, zone, 24) }),
                        dayVector = circularMean(seriesEvents.map { angleOf(it.startedAtMs, zone, 24 * 7) }),
                    )
                }
                return Memory(memories, last)
            }
        }
    }

    private fun parsedOf(event: WatchEvent): SeriesMatcher.Parsed = SeriesMatcher.parse(event.title)

    /** Similarity of "now" to the mean direction of past watches, in `0..1`; 0.5 means no pattern. */
    private fun circularMatch(vector: DoubleArray?, nowAngle: Double): Double {
        if (vector == null) return NEUTRAL
        return ((cos(nowAngle - vector[0]) * vector[1] + 1.0) / 2.0).coerceIn(0.0, 1.0)
    }

    /** Mean direction (and concentration) of a set of angles; `null` when there is no clear pattern. */
    private fun circularMean(angles: List<Double>): DoubleArray? {
        if (angles.size < 2) return null
        val x = angles.sumOf { cos(it) } / angles.size
        val y = angles.sumOf { sin(it) } / angles.size
        val strength = sqrt(x * x + y * y)
        if (strength < 0.15) return null
        return doubleArrayOf(Math.atan2(y, x), strength)
    }

    private fun angleOf(timeMs: Long, zone: TimeZone, hoursPerCycle: Int): Double {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = timeMs }
        val hour = calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE) / 60.0
        val position = if (hoursPerCycle == 24) {
            hour
        } else {
            (calendar.get(Calendar.DAY_OF_WEEK) - 1) * 24 + hour
        }
        return TWO_PI * position / hoursPerCycle
    }

    private fun decay(elapsedHours: Double, halfLifeHours: Double): Double =
        if (elapsedHours <= 0.0) 1.0 else Math.pow(0.5, elapsedHours / halfLifeHours)

    private fun dot(weights: DoubleArray, values: DoubleArray): Double {
        var sum = 0.0
        for (index in values.indices) sum += weights[index] * values[index]
        return sum
    }

    private fun sigmoid(value: Double): Double = 1.0 / (1.0 + exp(-value))

    private fun relative(elapsedMs: Long): String {
        val minutes = elapsedMs / 60_000L
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "$minutes min ago"
            minutes < 60L * 24L -> "${minutes / 60} h ago"
            else -> "${minutes / (60L * 24L)} d ago"
        }
    }

    // endregion
}
