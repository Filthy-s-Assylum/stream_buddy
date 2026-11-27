package com.streambuddy.app.optimizer

import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.data.OptimizerMode
import com.streambuddy.app.data.PolicySource
import com.streambuddy.app.data.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizerCoreTest {

    private fun telemetry(
        atMs: Long,
        bufferSeconds: Double,
        throughputBps: Double,
        bitrateBps: Double,
        stalling: Boolean = false,
        isTorrent: Boolean = false,
        peerDownRateBps: Double = throughputBps,
    ) = PlaybackTelemetry(
        atMs = atMs,
        bufferSeconds = bufferSeconds,
        throughputBps = throughputBps,
        bitrateBps = bitrateBps,
        peerDownRateBps = peerDownRateBps,
        peerCount = if (isTorrent) 8 else 0,
        isTorrent = isTorrent,
        isAdaptive = !isTorrent,
        playing = true,
        stalling = stalling,
        positionMs = 10_000L,
        durationMs = 600_000L,
    )

    @Test
    fun `baseline rules flag low buffer with throughput deficit as high risk`() {
        val core = OptimizerCore()
        val eval = core.evaluate(
            telemetry(
                atMs = 5_000L,
                bufferSeconds = 3.0,
                throughputBps = 1_000_000.0,
                bitrateBps = 4_000_000.0,
                stalling = true,
            ),
            AppSettings(optimizerMode = OptimizerMode.RULES),
        )

        assertEquals(PolicySource.RULES, eval.decision.source)
        assertEquals(RiskLevel.HIGH, eval.decision.level)
        assertTrue(eval.baselineRisk >= 0.70)
    }

    @Test
    fun `baseline rules keep healthy stream low risk`() {
        val core = OptimizerCore()
        val eval = core.evaluate(
            telemetry(
                atMs = 5_000L,
                bufferSeconds = 35.0,
                throughputBps = 20_000_000.0,
                bitrateBps = 4_000_000.0,
            ),
            AppSettings(optimizerMode = OptimizerMode.RULES),
        )

        assertEquals(RiskLevel.LOW, eval.decision.level)
        assertTrue(eval.baselineRisk <= 0.25)
    }

    @Test
    fun `logistic model trains and roundtrips through encode and decode`() {
        val model = LogisticStallModel()
        val riskyInput = doubleArrayOf(0.1, 3.0, -0.8, 0.9, 0.2, 1.0)
        val healthyInput = doubleArrayOf(1.5, 0.2, 0.4, 0.0, 1.8, 0.1)

        repeat(60) {
            model.train(riskyInput, label = true)
            model.train(healthyInput, label = false)
        }

        assertTrue(model.trainedSamples >= 120)
        assertTrue(model.predict(riskyInput) > model.predict(healthyInput))
        assertTrue(model.isEligible(riskyInput) || model.isEligible(healthyInput))

        val decoded = LogisticStallModel.decode(model.encode())
        assertEquals(model.trainedSamples, decoded.trainedSamples)
        assertEquals(model.version, decoded.version)
        assertEquals(model.predict(riskyInput), decoded.predict(riskyInput), 1e-6)
    }

    @Test
    fun `adaptive mode falls back to rules until model has enough samples`() {
        val core = OptimizerCore()
        val eval = core.evaluate(
            telemetry(
                atMs = 5_000L,
                bufferSeconds = 3.0,
                throughputBps = 1_000_000.0,
                bitrateBps = 4_000_000.0,
            ),
            AppSettings(optimizerMode = OptimizerMode.ADAPTIVE),
        )
        assertFalse(eval.modelEligible)
        assertEquals(PolicySource.MODEL_FALLBACK, eval.decision.source)
    }
}
