package com.streambuddy.app.optimizer

import com.streambuddy.app.data.OptimizerMode
import com.streambuddy.app.data.PolicySource
import com.streambuddy.app.data.RiskLevel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One observation of playback health. It contains no URLs or titles; only
 * numeric delivery metrics needed by the optimizer.
 */
data class PlaybackTelemetry(
    val atMs: Long,
    val bufferSeconds: Double,
    val throughputBps: Double,
    val bitrateBps: Double,
    val peerDownRateBps: Double,
    val peerCount: Int,
    val isTorrent: Boolean,
    val isAdaptive: Boolean,
    val playing: Boolean,
    val stalling: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Raw and normalised features. The six model inputs are documented here because
 * they are the complete claim the on-device model makes: buffer level, bitrate
 * deficit, throughput trend, recent stalls, delivery ratio and jitter. There is
 * no hidden "speed boost" feature.
 */
data class RiskFeatures(
    val bufferSeconds: Double,
    val throughputBps: Double,
    val bitrateBps: Double,
    val throughputTrend: Double,
    val stallRate: Double,
    val deliveryRatio: Double,
    val jitter: Double,
    val isTorrent: Boolean,
    val hasSignal: Boolean,
) {
    fun modelInput(): DoubleArray = doubleArrayOf(
        (bufferSeconds / 30.0).coerceIn(0.0, 3.0),
        (bitrateBps / max(throughputBps, 1.0)).coerceIn(0.0, 4.0),
        throughputTrend.coerceIn(-1.0, 1.0),
        stallRate.coerceIn(0.0, 1.0),
        deliveryRatio.coerceIn(0.0, 2.0),
        jitter.coerceIn(0.0, 2.0),
    )
}

class FeatureExtractor {
    private var fast = 0.0
    private var slow = 0.0
    private var initialized = false
    private val throughputWindow = ArrayDeque<Double>()
    private val stallTicks = ArrayDeque<Long>()
    private var lastUpdateMs = 0L

    fun update(telemetry: PlaybackTelemetry): RiskFeatures {
        val throughput = telemetry.throughputBps.coerceAtLeast(0.0)
        if (!initialized && throughput > 0.0) {
            fast = throughput
            slow = throughput
            initialized = true
        } else {
            // Zero is a real observation (a disconnect), not a missing value.
            fast = if (initialized) fast * 0.65 + throughput * 0.35 else throughput
            slow = if (initialized) slow * 0.92 + throughput * 0.08 else throughput
        }
        if (throughput > 0.0) initialized = true

        throughputWindow.addLast(throughput)
        while (throughputWindow.size > 12) throughputWindow.removeFirst()

        if (telemetry.stalling) {
            stallTicks.addLast(telemetry.atMs)
        }
        val cutoff = telemetry.atMs - 60_000
        while (stallTicks.isNotEmpty() && stallTicks.first() < cutoff) stallTicks.removeFirst()

        val trend = if (slow > 1.0) (fast - slow) / slow else 0.0
        val mean = throughputWindow.average().takeIf { it > 0.0 } ?: 0.0
        val jitter = if (mean > 0.0) {
            throughputWindow.sumOf { abs(it - mean) } / throughputWindow.size / mean
        } else 0.0

        val delivery = if (telemetry.isTorrent) {
            telemetry.peerDownRateBps / max(telemetry.bitrateBps, 1.0)
        } else {
            telemetry.throughputBps / max(telemetry.bitrateBps, 1.0)
        }

        // A sample is meaningful once playback has moved or data is buffered.
        // This avoids labelling the first few hundred milliseconds as a stall.
        val hasSignal = telemetry.playing &&
            (telemetry.bufferSeconds > 0.25 || telemetry.positionMs > 0L || throughput > 0.0)

        return RiskFeatures(
            bufferSeconds = telemetry.bufferSeconds,
            throughputBps = telemetry.throughputBps,
            bitrateBps = telemetry.bitrateBps,
            throughputTrend = trend,
            stallRate = (stallTicks.size / 60.0).coerceIn(0.0, 1.0),
            deliveryRatio = delivery,
            jitter = jitter,
            isTorrent = telemetry.isTorrent,
            hasSignal = hasSignal,
        ).also { lastUpdateMs = telemetry.atMs }
    }

    fun reset() {
        fast = 0.0
        slow = 0.0
        initialized = false
        throughputWindow.clear()
        stallTicks.clear()
        lastUpdateMs = 0L
    }
}

/** Rule baseline. This is deliberately not called "AI" anywhere in the UI. */
object BaselineRules {
    fun score(features: RiskFeatures): Double {
        if (!features.hasSignal) return 0.0
        val throughput = features.throughputBps
        val deficit = if (throughput <= 1.0) {
            if (features.bitrateBps > 0.0) 3.0 else 0.0
        } else {
            features.bitrateBps / throughput
        }
        var risk = 0.0

        if (features.bufferSeconds < 5.0 && deficit > 1.05) risk = max(risk, 0.88)
        if (features.bufferSeconds < 12.0 && deficit > 0.95) risk = max(risk, 0.66)
        if (features.bufferSeconds < 20.0 && deficit > 1.25) risk = max(risk, 0.78)
        if (features.throughputTrend < -0.35) risk = max(risk, 0.62)
        if (features.stallRate > 0.20) risk = max(risk, 0.86)
        if (features.isTorrent && features.deliveryRatio < 0.55) risk = max(risk, 0.68)
        if (features.bufferSeconds > 25.0 && deficit < 0.8) risk = min(risk, 0.25)
        if (throughput <= 1.0 && features.bitrateBps > 0.0 && features.bufferSeconds < 8.0) {
            risk = max(risk, 0.80)
        }
        return risk.coerceIn(0.0, 1.0)
    }

    fun level(features: RiskFeatures): RiskLevel = levelFor(score(features))

    fun levelFor(score: Double): RiskLevel = when {
        score >= 0.70 -> RiskLevel.HIGH
        score >= 0.45 -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }
}

/**
 * Logistic regression trained on-device with SGD from labelled 8-second windows.
 * It starts untrained: until enough real labels exist the caller must use the
 * rules baseline. Weights are bounded to keep one unusual session from making
 * wild predictions.
 */
class LogisticStallModel(
    private var weights: DoubleArray = DoubleArray(FEATURE_COUNT),
    private var bias: Double = -1.2,
    var trainedSamples: Long = 0,
    var version: Int = 0,
) {
    fun inputSize(): Int = FEATURE_COUNT

    fun predict(input: DoubleArray): Double {
        require(input.size == FEATURE_COUNT)
        var z = bias
        for (i in 0 until FEATURE_COUNT) z += weights[i] * input[i]
        return sigmoid(z)
    }

    fun confidence(input: DoubleArray): Double {
        if (trainedSamples < MIN_LABELS) return 0.0
        val p = predict(input)
        val maturity = ((trainedSamples - MIN_LABELS) / 80.0).coerceIn(0.0, 1.0)
        val margin = (abs(p - 0.5) * 2.0).coerceIn(0.0, 1.0)
        return maturity * margin
    }

    fun isEligible(input: DoubleArray): Boolean =
        trainedSamples >= MIN_LABELS && confidence(input) >= MIN_CONFIDENCE

    /** Returns the pre-update log loss. */
    fun train(input: DoubleArray, label: Boolean): Double {
        require(input.size == FEATURE_COUNT)
        val y = if (label) 1.0 else 0.0
        val p = predict(input).coerceIn(1e-6, 1.0 - 1e-6)
        val loss = -(y * kotlin.math.ln(p) + (1.0 - y) * kotlin.math.ln(1.0 - p))
        val learningRate = (0.08 / (1.0 + trainedSamples / 400.0)).coerceAtLeast(0.005)
        // Stall windows are the minority class; weight them so the model does
        // not collapse to always predicting "healthy".
        val classWeight = if (label) 2.5 else 1.0
        val error = (p - y) * classWeight
        for (i in 0 until FEATURE_COUNT) {
            val gradient = error * input[i] + L2 * weights[i]
            weights[i] = (weights[i] - learningRate * gradient).coerceIn(-8.0, 8.0)
        }
        bias = (bias - learningRate * error).coerceIn(-10.0, 10.0)
        trainedSamples++
        version = (trainedSamples / 25).toInt()
        return loss
    }

    fun snapshot(): ModelSnapshot =
        ModelSnapshot(weights.copyOf(), bias, trainedSamples, version)

    fun encode(): String = buildString {
        appendLine("v=1")
        appendLine("samples=$trainedSamples")
        appendLine("version=$version")
        appendLine("bias=$bias")
        weights.forEachIndexed { index, value -> appendLine("w$index=$value") }
    }

    companion object {
        const val FEATURE_COUNT = 6
        const val MIN_LABELS = 40
        const val MIN_CONFIDENCE = 0.25
        private const val L2 = 0.003

        fun decode(raw: String?): LogisticStallModel {
            if (raw.isNullOrBlank()) return LogisticStallModel()
            val map = raw.lineSequence().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }.toMap()
            if (map["v"] != "1") return LogisticStallModel()
            val weights = DoubleArray(FEATURE_COUNT) { index ->
                map["w$index"]?.toDoubleOrNull()?.coerceIn(-8.0, 8.0) ?: 0.0
            }
            val bias = map["bias"]?.toDoubleOrNull()?.coerceIn(-10.0, 10.0) ?: -1.2
            val samples = map["samples"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            val version = map["version"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            return LogisticStallModel(weights, bias, samples, version)
        }

        fun fromSnapshot(snapshot: ModelSnapshot) = LogisticStallModel(
            weights = snapshot.weights.copyOf(),
            bias = snapshot.bias,
            trainedSamples = snapshot.trainedSamples,
            version = snapshot.version,
        )

        private fun sigmoid(z: Double): Double {
            if (z >= 0.0) {
                val e = kotlin.math.exp(-z)
                return 1.0 / (1.0 + e)
            }
            val e = kotlin.math.exp(z)
            return e / (1.0 + e)
        }
    }
}

data class ModelSnapshot(
    val weights: DoubleArray,
    val bias: Double,
    val trainedSamples: Long,
    val version: Int,
)

data class Confusion(
    var truePositive: Int = 0,
    var falsePositive: Int = 0,
    var trueNegative: Int = 0,
    var falseNegative: Int = 0,
) {
    val total: Int get() = truePositive + falsePositive + trueNegative + falseNegative
    val accuracy: Double get() = if (total == 0) 0.0 else (truePositive + trueNegative).toDouble() / total
    val precision: Double get() {
        val denominator = truePositive + falsePositive
        return if (denominator == 0) 0.0 else truePositive.toDouble() / denominator
    }
    val recall: Double get() {
        val denominator = truePositive + falseNegative
        return if (denominator == 0) 0.0 else truePositive.toDouble() / denominator
    }
    val f1: Double get() {
        val p = precision
        val r = recall
        return if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
    }
    val falsePositiveRate: Double get() {
        val denominator = falsePositive + trueNegative
        return if (denominator == 0) 0.0 else falsePositive.toDouble() / denominator
    }

    fun record(predictedStall: Boolean, actualStall: Boolean) {
        when {
            predictedStall && actualStall -> truePositive++
            predictedStall && !actualStall -> falsePositive++
            !predictedStall && actualStall -> falseNegative++
            else -> trueNegative++
        }
    }
}

data class OptimizationDecision(
    val level: RiskLevel,
    val riskScore: Double,
    val source: PolicySource,
    val modelProbability: Double,
    val modelConfidence: Double,
    val startBufferSeconds: Int,
    val bufferTargetSeconds: Int,
    val prefetchSeconds: Int,
    val maxVideoBitrateBps: Int?,
    val torrentAheadBytes: Long,
    val reason: String,
    val atMs: Long,
)

data class LabelledSample(
    val atMs: Long,
    val features: RiskFeatures,
    val baselineRisk: Double,
    val modelRisk: Double,
    val modelVersion: Int,
    val acted: Boolean,
    val label: Boolean,
)

data class Evaluation(
    val decision: OptimizationDecision,
    val features: RiskFeatures,
    val baselineRisk: Double,
    val modelProbability: Double,
    val modelConfidence: Double,
    val modelEligible: Boolean,
    val labelled: List<LabelledSample>,
    val modelSnapshot: ModelSnapshot?,
)

/**
 * Pure optimizer state machine: no Android references, so it can be tested on
 * the JVM with slow-network, disconnect and seek telemetry.
 */
class OptimizerCore(
    model: LogisticStallModel = LogisticStallModel(),
    private val labelHorizonMs: Long = 8_000,
) {
    var model: LogisticStallModel = model
        private set
    val baselineMetrics = Confusion()
    val modelMetrics = Confusion()
    private val extractor = FeatureExtractor()
    private val pending = ArrayDeque<Pending>()
    private val stallTicks = ArrayDeque<Long>()
    private var lastDecision: OptimizationDecision? = null
    private var lastActionAt = 0L
    private var snapshotChanged = false

    fun resetSession() {
        extractor.reset()
        pending.clear()
        stallTicks.clear()
        lastDecision = null
        lastActionAt = 0L
    }

    fun evaluate(
        telemetry: PlaybackTelemetry,
        settings: com.streambuddy.app.data.AppSettings,
        nowMs: Long = telemetry.atMs,
    ): Evaluation {
        if (telemetry.stalling) stallTicks.addLast(telemetry.atMs)
        val cutoff = nowMs - labelHorizonMs - 1_000
        while (stallTicks.isNotEmpty() && stallTicks.first() < cutoff) stallTicks.removeFirst()

        val features = extractor.update(telemetry)
        val baseline = BaselineRules.score(features)
        val input = features.modelInput()
        val modelProbability = model.predict(input)
        val confidence = model.confidence(input)
        val eligible = features.hasSignal && model.isEligible(input)
        val adaptiveMode = settings.optimizerMode == OptimizerMode.ADAPTIVE

        val source = when {
            !adaptiveMode -> PolicySource.RULES
            eligible -> PolicySource.MODEL
            else -> PolicySource.MODEL_FALLBACK
        }
        val risk = if (source == PolicySource.MODEL) modelProbability else baseline
        val level = BaselineRules.levelFor(risk)
        val decision = buildDecision(
            settings = settings,
            features = features,
            level = level,
            risk = risk,
            source = source,
            modelProbability = modelProbability,
            confidence = confidence,
            nowMs = nowMs,
        )

        // Do not flap quality/buffer controls faster than every 3 seconds. The
        // risk value still updates continuously, only the action is held.
        val held = lastDecision
        val effective = if (
            held != null &&
            held.level == decision.level &&
            held.source == decision.source &&
            nowMs - lastActionAt < ACTION_HOLD_MS
        ) {
            held.copy(riskScore = decision.riskScore, atMs = decision.atMs)
        } else {
            decision.also {
                lastActionAt = nowMs
            }
        }
        lastDecision = effective

        if (features.hasSignal) {
            pending.addLast(
                Pending(
                    atMs = nowMs,
                    features = features,
                    baselineRisk = baseline,
                    modelRisk = modelProbability,
                    modelVersion = model.version,
                    modelEligible = eligible,
                    acted = effective.source == PolicySource.MODEL,
                ),
            )
            while (pending.size > 20) pending.removeFirst()
        }

        val labelled = resolveLabels(nowMs)
        snapshotChanged = labelled.isNotEmpty()
        val snapshot = if (snapshotChanged) model.snapshot() else null
        return Evaluation(
            decision = effective,
            features = features,
            baselineRisk = baseline,
            modelProbability = modelProbability,
            modelConfidence = confidence,
            modelEligible = eligible,
            labelled = labelled,
            modelSnapshot = snapshot,
        )
    }

    fun resolveLabels(nowMs: Long): List<LabelledSample> {
        val resolved = mutableListOf<LabelledSample>()
        while (pending.isNotEmpty() && pending.first().atMs + labelHorizonMs <= nowMs) {
            val sample = pending.removeFirst()
            val label = stallTicks.any { it in sample.atMs..(sample.atMs + labelHorizonMs) }
            baselineMetrics.record(sample.baselineRisk >= 0.5, label)
            if (sample.modelEligible) modelMetrics.record(sample.modelRisk >= 0.5, label)
            model.train(sample.features.modelInput(), label)
            resolved += LabelledSample(
                atMs = sample.atMs,
                features = sample.features,
                baselineRisk = sample.baselineRisk,
                modelRisk = sample.modelRisk,
                modelVersion = sample.modelVersion,
                acted = sample.acted,
                label = label,
            )
        }
        return resolved
    }

    /** Removes pending samples without training on them (short session). */
    fun discardPending() {
        pending.clear()
        stallTicks.clear()
    }

    private fun buildDecision(
        settings: com.streambuddy.app.data.AppSettings,
        features: RiskFeatures,
        level: RiskLevel,
        risk: Double,
        source: PolicySource,
        modelProbability: Double,
        confidence: Double,
        nowMs: Long,
    ): OptimizationDecision {
        val userCap = settings.maxVideoBitrateKbps.takeIf { it > 0 }?.times(1_000)
        val target: Int
        val prefetch: Int
        var cap: Int? = null
        when (level) {
            RiskLevel.HIGH -> {
                target = max(settings.bufferTargetSeconds, 120)
                prefetch = max(settings.prefetchSeconds, 240)
                if (settings.optimizerMode == OptimizerMode.ADAPTIVE) {
                    val measuredCap = (features.throughputBps * 0.70).toInt().coerceAtLeast(250_000)
                    cap = minOfNotNull(userCap, measuredCap)
                }
            }
            RiskLevel.MEDIUM -> {
                target = max(settings.bufferTargetSeconds, 75)
                prefetch = max(settings.prefetchSeconds, 150)
                if (settings.optimizerMode == OptimizerMode.ADAPTIVE) {
                    val measuredCap = (features.throughputBps * 0.90).toInt().coerceAtLeast(350_000)
                    cap = minOfNotNull(userCap, measuredCap)
                }
            }
            RiskLevel.LOW -> {
                target = settings.bufferTargetSeconds
                prefetch = settings.prefetchSeconds
                cap = userCap
            }
        }

        val bitrate = features.bitrateBps.takeIf { it > 0.0 } ?: 2_000_000.0
        val aheadBytes = ((bitrate / 8.0) * prefetch)
            .toLong()
            .coerceIn(16L * 1024 * 1024, 512L * 1024 * 1024)

        val reason = buildReason(
            source = source,
            level = level,
            features = features,
            modelProbability = modelProbability,
            confidence = confidence,
            sampleCount = model.trainedSamples,
            isTorrent = features.isTorrent,
        )
        return OptimizationDecision(
            level = level,
            riskScore = risk,
            source = source,
            modelProbability = modelProbability,
            modelConfidence = confidence,
            startBufferSeconds = settings.startBufferSeconds,
            bufferTargetSeconds = target.coerceAtMost(180),
            prefetchSeconds = prefetch.coerceAtMost(600),
            maxVideoBitrateBps = cap,
            torrentAheadBytes = aheadBytes,
            reason = reason,
            atMs = nowMs,
        )
    }

    private fun buildReason(
        source: PolicySource,
        level: RiskLevel,
        features: RiskFeatures,
        modelProbability: Double,
        confidence: Double,
        sampleCount: Long,
        isTorrent: Boolean,
    ): String {
        val buffer = String.format(java.util.Locale.US, "%.1f s buffer", features.bufferSeconds)
        val deficit = if (features.throughputBps > 1.0) {
            String.format(
                java.util.Locale.US,
                "%.2f× bitrate demand",
                features.bitrateBps / features.throughputBps,
            )
        } else {
            "no measured throughput"
        }
        val detail = when {
            features.stallRate > 0.15 -> "$buffer, recent stalls"
            features.throughputTrend < -0.3 -> "$buffer, throughput falling"
            isTorrent && features.deliveryRatio < 0.7 -> "$buffer, peer delivery limited"
            else -> "$buffer, $deficit"
        }
        return when (source) {
            PolicySource.MODEL -> String.format(
                java.util.Locale.US,
                "Model %s risk %.2f (confidence %.2f): %s",
                level.name.lowercase(),
                modelProbability,
                confidence,
                detail,
            )
            PolicySource.MODEL_FALLBACK -> String.format(
                java.util.Locale.US,
                "Rules (model warming up, %d/%d labels; %.2f): %s",
                sampleCount,
                LogisticStallModel.MIN_LABELS,
                modelProbability,
                detail,
            )
            PolicySource.RULES -> "Rules: $detail"
        }
    }

    private fun minOfNotNull(a: Int?, b: Int): Int = if (a == null) b else minOf(a, b)

    private data class Pending(
        val atMs: Long,
        val features: RiskFeatures,
        val baselineRisk: Double,
        val modelRisk: Double,
        val modelVersion: Int,
        val modelEligible: Boolean,
        val acted: Boolean,
    )

    private companion object {
        const val ACTION_HOLD_MS = 3_000L
    }
}
