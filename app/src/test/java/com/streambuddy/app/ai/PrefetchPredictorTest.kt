package com.streambuddy.app.ai

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchPredictorTest {

    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour
    private val zone: TimeZone = TimeZone.getTimeZone("UTC")

    /** 2027-01-15T00:00Z, a fixed "now" so the tests do not depend on the clock. */
    private val now = 1_800_000_000_000L

    private fun event(index: Int, title: String, atMs: Long, watchedMs: Long = 1_300_000L) =
        WatchEvent(
            streamId = index.toLong(),
            title = title,
            startedAtMs = atMs,
            watchedMs = watchedMs,
            durationMs = 1_400_000L,
            completed = true,
        )

    /** Ten consecutive nightly episodes of one sitcom. */
    private fun bingeHistory(): List<WatchEvent> = (0 until 10).map { index ->
        event(index, "Sitcom S01E${(index + 1).toString().padStart(2, '0')}", now - (10 - index) * day)
    }

    @Test
    fun ranksTheNextEpisodeAboveAnUnwatchedTitle() {
        val history = bingeHistory()
        val ranked = PrefetchPredictor.rank(
            candidates = listOf(
                Candidate(1L, "Unwatched Documentary", now - day),
                Candidate(2L, "Sitcom S01E11", now - 2 * day),
            ),
            history = history,
            model = PrefetchPredictor.ModelState.prior(),
            nowMs = now,
            zone = zone,
        )

        assertEquals("Sitcom S01E11", ranked.first().candidate.title)
        assertTrue(ranked.first().probability > ranked.last().probability)
    }

    @Test
    fun explainsItself() {
        val ranked = PrefetchPredictor.rank(
            candidates = listOf(Candidate(2L, "Sitcom S01E11", now)),
            history = bingeHistory(),
            model = PrefetchPredictor.ModelState.prior(),
            nowMs = now,
            zone = zone,
        )
        val reasons = ranked.single().reasons
        assertTrue(reasons.isNotEmpty())
        assertTrue(reasons.size <= 3)
        assertTrue(reasons.any { it.feature == Feature.NEXT_EPISODE })
        assertTrue(reasons.all { it.detail.isNotBlank() })
    }

    @Test
    fun aFreshInstallStillProducesScores() {
        val ranked = PrefetchPredictor.rank(
            candidates = listOf(Candidate(1L, "Anything", now)),
            history = emptyList(),
            model = PrefetchPredictor.ModelState.prior(),
            nowMs = now,
            zone = zone,
        )
        assertEquals(1, ranked.size)
        assertTrue(ranked.single().probability in 0.0..1.0)
        // Nothing has been watched, so no viewing-based claim is made. Queue recency is still a
        // real signal (it comes from the entry itself, not from history).
        val reasons = ranked.single().reasons
        assertTrue(
            reasons.none {
                it.feature == Feature.RECENCY || it.feature == Feature.FREQUENCY ||
                    it.feature == Feature.COMPLETION
            },
        )
    }

    @Test
    fun probabilitiesStayInRangeForExtremeScores() {
        val ranked = PrefetchPredictor.rank(
            candidates = listOf(Candidate(2L, "Sitcom S01E11", now)),
            history = bingeHistory(),
            model = PrefetchPredictor.ModelState.prior(),
            nowMs = now,
            zone = zone,
        )
        val probability = ranked.single().probability
        assertTrue(probability > 0.5)
        assertTrue("temperature should keep it below 1.0", probability < 1.0)
    }

    @Test
    fun tooLittleHistoryLeavesTheModelAlone() {
        val model = PrefetchPredictor.train(bingeHistory().take(3), zone = zone)
        assertEquals(0, model.trainedOn)
        assertFalse(model.hasLearnedAnything)
    }

    @Test
    fun trainingActuallyChangesTheWeights() {
        val trained = PrefetchPredictor.train(bingeHistory(), zone = zone)
        assertTrue(trained.hasLearnedAnything)
        assertEquals(PrefetchPredictor.PRIOR_WEIGHTS.size, trained.weights.size)
        assertTrue(trained.weights != PrefetchPredictor.PRIOR_WEIGHTS)
    }

    @Test
    fun trainingLeavesTheQueueAgeWeightAtItsPrior() {
        // QUEUE_AGE has no historical timestamp to learn from, so it must survive untouched.
        val trained = PrefetchPredictor.train(bingeHistory(), zone = zone)
        val index = Feature.QUEUE_AGE.ordinal
        assertEquals(PrefetchPredictor.PRIOR_WEIGHTS[index], trained.weights[index], 0.0001)
    }

    @Test
    fun aWrongGuessCostsLessThanARightOne() {
        val history = bingeHistory()
        val ranked = PrefetchPredictor.rank(
            candidates = listOf(
                Candidate(2L, "Sitcom S01E11", now),
                Candidate(3L, "Sitcom S01E09", now),
                Candidate(4L, "Something Else", now),
            ),
            history = history,
            model = PrefetchPredictor.ModelState.prior(),
            nowMs = now,
            zone = zone,
        )
        val picks = PrefetchPlan.plan(ranked, baseDepthMs = 5 * 60_000L)
        val top = picks.firstOrNull()
        assertTrue("the confident pick should be planned", top != null)
        assertEquals("Sitcom S01E11", top!!.prediction.candidate.title)
        picks.drop(1).forEach { runnerUp ->
            assertTrue(
                "runner-ups only get a warm start",
                runnerUp.depthMs <= PrefetchPlan.WARM_START_MS,
            )
            assertTrue(runnerUp.depthMs < top.depthMs)
        }
    }

    @Test
    fun depthFollowsConfidenceAndStaysBounded() {
        val base = 10 * 60_000L
        assertTrue(PrefetchPlan.topDepth(0.99, base) > PrefetchPlan.topDepth(0.55, base))
        assertTrue(PrefetchPlan.topDepth(0.99, base) <= PrefetchPlan.MAX_DEPTH_MS)
        assertEquals(PrefetchPlan.MAX_DEPTH_MS, PrefetchPlan.topDepth(1.0, 60 * 60_000L))
        // Even a certain-this-is-wrong pick never goes below the warm-start floor.
        assertEquals(PrefetchPlan.WARM_START_MS, PrefetchPlan.topDepth(0.0, 60_000L))
        assertTrue(PrefetchPlan.topDepth(0.0, base) < PrefetchPlan.topDepth(0.99, base))
        assertEquals(0L, PrefetchPlan.topDepth(0.9, 0L))
    }

    @Test
    fun planIsEmptyWhenNothingClearsTheBar() {
        val ranked = PrefetchPredictor.rank(
            candidates = listOf(Candidate(9L, "Never Heard Of It", now - 400 * day)),
            history = emptyList(),
            model = PrefetchPredictor.ModelState.prior(),
            nowMs = now,
            zone = zone,
        )
        assertTrue(PrefetchPlan.plan(ranked, baseDepthMs = 5 * 60_000L).isEmpty())
    }

    @Test
    fun completionIsClamped() {
        val overWatched = WatchEvent(1L, "x", now, watchedMs = 5_000_000L, durationMs = 1_000_000L, completed = true)
        val live = WatchEvent(2L, "x", now, watchedMs = 600_000L, durationMs = 0L, completed = false)
        assertEquals(1.0, overWatched.completion, 0.0001)
        assertTrue(live.completion in 0.0..1.0)
    }
}
