package com.streambuddy.app.data

import android.content.Context
import androidx.core.content.edit
import com.streambuddy.app.ai.PrefetchPredictor
import com.streambuddy.app.ai.WatchEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Keeps the learned weights across process restarts.
 *
 * Weights are nine doubles; a preference string is enough, and it keeps the model out of the
 * database that the queue uses.
 */
class PredictorRepository private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<ModelStatus> = _state.asStateFlow()

    /** Fits the model on [events] (on a background thread) and stores the result. */
    suspend fun learn(events: List<WatchEvent>): PrefetchPredictor.ModelState =
        withContext(Dispatchers.Default) {
            if (events.size < PrefetchPredictor.MIN_EVENTS_FOR_TRAINING) {
                return@withContext _state.value.model
            }
            val trained = PrefetchPredictor.train(events, _state.value.model)
            write(trained)
            _state.value = ModelStatus(trained, events.size)
            trained
        }

    /** Records how many playbacks the model has seen, without refitting. */
    fun noteHistorySize(count: Int) {
        val current = _state.value
        if (current.historyCount != count) {
            _state.value = ModelStatus(current.model, count)
        }
    }

    /** Forgets everything learned and goes back to the hand-tuned priors. */
    fun reset() {
        prefs.edit { remove(KEY_WEIGHTS).remove(KEY_TRAINED_ON) }
        _state.value = ModelStatus(PrefetchPredictor.ModelState.prior(), 0)
    }

    private fun write(model: PrefetchPredictor.ModelState) {
        prefs.edit {
            putString(KEY_WEIGHTS, model.weights.joinToString(","))
            putInt(KEY_TRAINED_ON, model.trainedOn)
        }
    }

    private fun read(): ModelStatus {
        val weights = prefs.getString(KEY_WEIGHTS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.size == PrefetchPredictor.PRIOR_WEIGHTS.size }
        val trainedOn = prefs.getInt(KEY_TRAINED_ON, 0)
        val model = if (weights == null) {
            PrefetchPredictor.ModelState.prior()
        } else {
            PrefetchPredictor.ModelState(weights, trainedOn, 0L)
        }
        return ModelStatus(model, trainedOn)
    }

    companion object {
        private const val PREFS_NAME = "stream_buddy_predictor"
        private const val KEY_WEIGHTS = "weights"
        private const val KEY_TRAINED_ON = "trained_on"

        @Volatile
        private var instance: PredictorRepository? = null

        fun get(context: Context): PredictorRepository = instance ?: synchronized(this) {
            instance ?: PredictorRepository(context).also { instance = it }
        }
    }
}

/** Model plus the number of playbacks it has seen, for the Settings screen. */
data class ModelStatus(
    val model: PrefetchPredictor.ModelState,
    val historyCount: Int,
)
