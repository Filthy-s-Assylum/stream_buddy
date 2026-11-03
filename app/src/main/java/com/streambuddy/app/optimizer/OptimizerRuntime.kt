package com.streambuddy.app.optimizer

import android.content.Context
import com.streambuddy.app.core.security.SecurePreferences
import com.streambuddy.app.data.AppDb
import com.streambuddy.app.data.OptimizerMode
import com.streambuddy.app.data.PolicySource
import com.streambuddy.app.data.PlaybackSessionEntity
import com.streambuddy.app.data.RiskSampleEntity
import com.streambuddy.app.data.RiskLevel
import com.streambuddy.app.data.SettingsStore
import com.streambuddy.app.data.EncryptedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RiskPoint(
    val atMs: Long,
    val bufferSeconds: Double,
    val throughputBps: Double,
    val bitrateBps: Double,
    val riskScore: Double,
    val source: PolicySource,
)

data class OptimizerUiState(
    val mode: OptimizerMode = OptimizerMode.RULES,
    val modelVersion: Int = 0,
    val trainedSamples: Long = 0,
    val eligible: Boolean = false,
    val lastRisk: Double = 0.0,
    val lastProbability: Double = 0.0,
    val lastConfidence: Double = 0.0,
    val lastLevel: RiskLevel = RiskLevel.LOW,
    val lastSource: PolicySource? = null,
    val lastReason: String = "Waiting for playback telemetry.",
    val baselineMetrics: Confusion = Confusion(),
    val modelMetrics: Confusion = Confusion(),
    val recent: List<RiskPoint> = emptyList(),
)

data class SessionOptimizerMetrics(
    val baseline: Confusion,
    val model: Confusion,
    val modelVersion: Int,
    val trainedSamples: Long,
)

/**
 * Android-side wrapper around the pure optimizer. It persists the encrypted
 * model and labelled windows, and exposes a StateFlow for the UI.
 */
class OptimizerRuntime(
    context: Context,
    private val settingsStore: SettingsStore,
    private val db: AppDb,
) {
    private val prefs = SecurePreferences(context.applicationContext, "stream_buddy_model")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var core = OptimizerCore(LogisticStallModel.decode(prefs.getString(MODEL_KEY)))
    private var sessionId: Long = 0
    private var lastSavedVersion = -1

    private val _state = MutableStateFlow(
        OptimizerUiState(
            mode = settingsStore.current().optimizerMode,
            modelVersion = core.model.version,
            trainedSamples = core.model.trainedSamples,
        ),
    )
    val state: StateFlow<OptimizerUiState> = _state.asStateFlow()

    fun setSession(id: Long) {
        sessionId = id
        core.resetSession()
    }

    fun evaluate(telemetry: PlaybackTelemetry): Evaluation {
        val settings = settingsStore.current()
        val evaluation = core.evaluate(telemetry, settings)
        val decision = evaluation.decision
        val recent = (_state.value.recent + RiskPoint(
            atMs = telemetry.atMs,
            bufferSeconds = telemetry.bufferSeconds,
            throughputBps = telemetry.throughputBps,
            bitrateBps = telemetry.bitrateBps,
            riskScore = decision.riskScore,
            source = decision.source,
        )).takeLast(120)

        _state.value = OptimizerUiState(
            mode = settings.optimizerMode,
            modelVersion = core.model.version,
            trainedSamples = core.model.trainedSamples,
            eligible = evaluation.modelEligible,
            lastRisk = decision.riskScore,
            lastProbability = evaluation.modelProbability,
            lastConfidence = evaluation.modelConfidence,
            lastLevel = decision.level,
            lastSource = decision.source,
            lastReason = decision.reason,
            baselineMetrics = core.baselineMetrics.copy(),
            modelMetrics = core.modelMetrics.copy(),
            recent = recent,
        )

        if (evaluation.labelled.isNotEmpty()) {
            persistLabels(evaluation.labelled)
            if (evaluation.modelSnapshot != null &&
                (core.model.version != lastSavedVersion || core.model.trainedSamples % 25L == 0L)
            ) {
                lastSavedVersion = core.model.version
                persistModel()
            }
        }
        return evaluation
    }

    fun metrics(): SessionOptimizerMetrics = SessionOptimizerMetrics(
        baseline = core.baselineMetrics.copy(),
        model = core.modelMetrics.copy(),
        modelVersion = core.model.version,
        trainedSamples = core.model.trainedSamples,
    )

    fun flush() {
        persistModel()
    }

    fun resetModel() {
        core = OptimizerCore()
        lastSavedVersion = -1
        prefs.remove(MODEL_KEY)
        _state.value = _state.value.copy(
            modelVersion = 0,
            trainedSamples = 0,
            eligible = false,
            baselineMetrics = Confusion(),
            modelMetrics = Confusion(),
            lastSource = null,
            lastReason = "Model reset. Rules baseline is active while new labels are collected.",
            recent = emptyList(),
        )
    }

    fun buildSession(
        title: String,
        sourceKind: String,
        mode: String,
        startedAt: Long,
        endedAt: Long,
        playbackMs: Long,
        stallCount: Int,
        stallMs: Long,
        avgThroughputBps: Long,
        avgBitrateBps: Long,
        bytesLoaded: Long,
        metrics: SessionOptimizerMetrics,
    ): PlaybackSessionEntity = PlaybackSessionEntity(
        title = EncryptedString(title),
        sourceKind = sourceKind,
        mode = mode,
        policy = settingsStore.current().optimizerMode.name,
        startedAt = startedAt,
        endedAt = endedAt,
        playbackMs = playbackMs,
        stallCount = stallCount,
        stallMs = stallMs,
        avgThroughputBps = avgThroughputBps,
        avgBitrateBps = avgBitrateBps,
        bytesLoaded = bytesLoaded,
        riskSamples = metrics.baseline.total,
        baselineTruePositive = metrics.baseline.truePositive,
        baselineFalsePositive = metrics.baseline.falsePositive,
        baselineTrueNegative = metrics.baseline.trueNegative,
        baselineFalseNegative = metrics.baseline.falseNegative,
        modelTruePositive = metrics.model.truePositive,
        modelFalsePositive = metrics.model.falsePositive,
        modelTrueNegative = metrics.model.trueNegative,
        modelFalseNegative = metrics.model.falseNegative,
        modelVersion = metrics.modelVersion,
    )

    fun saveSession(entity: PlaybackSessionEntity) {
        scope.launch { db.sessions().insert(entity) }
    }

    private fun persistModel() {
        if (core.model.trainedSamples > 0) {
            prefs.putString(MODEL_KEY, core.model.encode())
        }
    }

    private fun persistLabels(labels: List<LabelledSample>) {
        val rows = labels.map { sample ->
            val input = sample.features.modelInput()
            RiskSampleEntity(
                sessionId = sessionId,
                createdAt = sample.atMs,
                bufferFeature = input[0],
                deficitFeature = input[1],
                trendFeature = input[2],
                stallFeature = input[3],
                peerFeature = input[4],
                jitterFeature = input[5],
                baselineRisk = sample.baselineRisk,
                modelRisk = sample.modelRisk,
                modelVersion = sample.modelVersion,
                acted = sample.acted,
                label = if (sample.label) 1 else 0,
            )
        }
        scope.launch { db.riskSamples().insertAll(rows) }
    }

    private companion object {
        const val MODEL_KEY = "model_v1"
    }
}
