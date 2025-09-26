package org.futo.voiceinput.smartturn

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import org.futo.voiceinput.MagnitudeState

enum class TerminationSource {
    SMART_TURN
}

data class MagnitudeUpdate(
    val magnitude: Float,
    val state: MagnitudeState,
    val source: TerminationSource
)

interface SpeechTerminationObserver {
    fun onMagnitude(update: MagnitudeUpdate)
    fun onEndingSoon(source: TerminationSource)
    fun onFinalize(source: TerminationSource)
}

interface SpeechTerminationStrategy {
    fun reset()
    fun setPaused(paused: Boolean)
    fun onShortFrame(frame: ShortArray, length: Int)
    fun onFloatFrame(frame: FloatArray, length: Int)
    fun flush()
}

data class SmartTurnConfig(
    val minSamples: Int = 16_000,
    val evaluationIntervalSamples: Int = 8_000,
    val endingSoonThreshold: Float = 0.5f,
    val finalizeThreshold: Float = 0.5f,
    val minEndingSoonHits: Int = 1,
    val minFinalizeHits: Int = 1,
    val minRms: Float = 0.003f,
    val minSpeechMillis: Long = 400,
    val finalizeDelayMillis: Long = 250,
    val vadThreshold: Float = 0.5f,
    val silenceHoldMillis: Long = 200,
    val resetTimeoutMillis: Long = 5_000
)

class SmartTurnStrategy(
    private val contextProvider: () -> Context,
    private val config: SmartTurnConfig,
    private val observer: SpeechTerminationObserver
) : SpeechTerminationStrategy {

    private companion object {
        private const val TAG = "SmartTurnStrategy"
        private const val BUFFER_CAPACITY = 16_000 * 8
    }

    private val buffer = SmartTurnAudioBuffer(BUFFER_CAPACITY)
    private val sileroVad = SileroVad(contextProvider, config.resetTimeoutMillis)

    private var samplesSinceEval = 0
    private var paused = false
    private var consecutiveEndingSoon = 0
    private var consecutiveFinalize = 0
    private var hasSpeech = false
    private var speechDetectedAt: Long = 0L
    private var vadSpeechActive = false
    private var lastVadSpeechAt: Long = 0L
    private var lastVadProbability = 0f

    override fun reset() {
        buffer.clear()
        sileroVad.reset()
        samplesSinceEval = 0
        paused = false
        consecutiveEndingSoon = 0
        consecutiveFinalize = 0
        hasSpeech = false
        speechDetectedAt = 0L
        vadSpeechActive = false
        lastVadSpeechAt = 0L
        lastVadProbability = 0f
    }

    override fun setPaused(paused: Boolean) {
        this.paused = paused
        if (paused) {
            sileroVad.reset()
            consecutiveEndingSoon = 0
            consecutiveFinalize = 0
            hasSpeech = false
            speechDetectedAt = 0L
            vadSpeechActive = false
            lastVadSpeechAt = 0L
        }
    }

    override fun onShortFrame(frame: ShortArray, length: Int) {
        if (length <= 0) return
        val now = SystemClock.elapsedRealtime()
        val probability = sileroVad.accept(frame, length, config.vadThreshold)
        probability?.let { prob ->
            lastVadProbability = prob
            if (prob >= config.vadThreshold) {
                lastVadSpeechAt = now
                if (!vadSpeechActive) {
                    Log.d(TAG, "speechDetected probability=$prob source=silero")
                }
                vadSpeechActive = true
                if (!hasSpeech) {
                    hasSpeech = true
                    speechDetectedAt = now
                }
            } else if (vadSpeechActive && lastVadSpeechAt != 0L && now - lastVadSpeechAt >= config.silenceHoldMillis) {
                vadSpeechActive = false
            }
        }

        val frameRms = computeFrameRms(frame, length)
        val magnitude = (1.0 - 0.1.pow(24.0 * frameRms.toDouble())).toFloat()
        val state = when {
            vadSpeechActive -> MagnitudeState.TALKING
            hasSpeech -> MagnitudeState.ENDING_SOON_VAD
            frameRms < config.minRms -> MagnitudeState.NOT_TALKED_YET
            else -> MagnitudeState.TALKING
        }
        observer.onMagnitude(MagnitudeUpdate(magnitude, state, TerminationSource.SMART_TURN))
    }

    override fun onFloatFrame(frame: FloatArray, length: Int) {
        if (length <= 0) return
        buffer.append(frame, length)
        samplesSinceEval += length
        evaluateIfNeeded()
    }

    override fun flush() {
        evaluate(force = true)
    }

    private fun evaluateIfNeeded() {
        if (paused) return
        if (buffer.validSamples < config.minSamples) return
        if (samplesSinceEval < config.evaluationIntervalSamples) return
        evaluate(force = false)
    }

    private fun computeFrameRms(frame: ShortArray, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        val maxValue = Short.MAX_VALUE.toDouble()
        for (i in 0 until length) {
            val sample = frame[i] / maxValue
            sum += sample * sample
        }
        return sqrt(sum / length).toFloat()
    }

    private fun computeSnapshotRms(snapshot: FloatArray, validSamples: Int): Float {
        if (validSamples <= 0) return 0f
        val end = snapshot.size
        val start = (end - validSamples).coerceAtLeast(0)
        var sum = 0.0
        for (i in start until end) {
            val sample = snapshot[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / validSamples).toFloat()
    }

    private fun evaluate(force: Boolean) {
        if (paused) return
        val valid = buffer.validSamples
        if (valid < config.minSamples && !force) return

        val snapshot = buffer.snapshot()
        val context = contextProvider.invoke()
        val now = SystemClock.elapsedRealtime()
        val silenceElapsed = if (lastVadSpeechAt == 0L) Long.MAX_VALUE else now - lastVadSpeechAt
        val hasRecentSpeech = vadSpeechActive || silenceElapsed < config.silenceHoldMillis

        if (!force && hasRecentSpeech) {
            Log.d(TAG, "skip evaluation recentSpeech=$hasRecentSpeech silenceElapsed=$silenceElapsed")
            return
        }

        val elapsedSinceSpeech = if (speechDetectedAt == 0L) Long.MAX_VALUE else now - speechDetectedAt
        if (!hasSpeech && !force) {
            Log.d(TAG, "skip evaluation noSpeech probability=$lastVadProbability")
            return
        }

        samplesSinceEval = 0
        val rms = computeSnapshotRms(snapshot, valid)
        val result = SmartTurnEngine.predictCompletion(context, snapshot, valid) ?: return

        val magnitudeState = if (result.isComplete) MagnitudeState.ENDING_SOON_VAD else MagnitudeState.TALKING
        observer.onMagnitude(
            MagnitudeUpdate(result.probability, magnitudeState, TerminationSource.SMART_TURN)
        )

        if (result.isComplete) {
            consecutiveEndingSoon += 1
        } else {
            consecutiveEndingSoon = 0
        }

        if (result.probability >= config.finalizeThreshold && result.isComplete) {
            consecutiveFinalize += 1
        } else {
            consecutiveFinalize = 0
        }

        if (consecutiveEndingSoon >= config.minEndingSoonHits) {
            Log.d(TAG, "endingSoon probability=${result.probability} hits=$consecutiveEndingSoon force=$force")
            observer.onEndingSoon(TerminationSource.SMART_TURN)
        }

        val canFinalize = force ||
            (!hasRecentSpeech && (rms < config.minRms || silenceElapsed >= config.finalizeDelayMillis)) ||
            elapsedSinceSpeech >= (config.minSpeechMillis + config.finalizeDelayMillis)

        if (consecutiveFinalize >= config.minFinalizeHits && canFinalize) {
            Log.d(
                TAG,
                "finalize probability=${result.probability} hits=$consecutiveFinalize rms=$rms silence=$silenceElapsed elapsed=$elapsedSinceSpeech force=$force"
            )
            observer.onFinalize(TerminationSource.SMART_TURN)
            consecutiveFinalize = 0
            consecutiveEndingSoon = 0
            hasSpeech = false
            vadSpeechActive = false
            lastVadSpeechAt = 0L
            speechDetectedAt = 0L
            sileroVad.reset()
        } else if (consecutiveFinalize >= config.minFinalizeHits) {
            Log.d(
                TAG,
                "waitingFinalize probability=${result.probability} hits=$consecutiveFinalize rms=$rms silence=$silenceElapsed elapsed=$elapsedSinceSpeech force=$force"
            )
        }
    }
}

class SmartTurnAudioBuffer(private val capacity: Int) {
    private val buffer = FloatArray(capacity)
    private var size = 0
    private var writePos = 0

    val validSamples: Int
        get() = min(size, capacity)

    fun clear() {
        size = 0
        writePos = 0
    }

    fun append(frame: ShortArray, length: Int) {
        for (i in 0 until length) {
            appendSample(frame[i] / Short.MAX_VALUE.toFloat())
        }
    }

    fun append(frame: FloatArray, length: Int) {
        for (i in 0 until length) {
            appendSample(frame[i])
        }
    }

    private fun appendSample(value: Float) {
        buffer[writePos] = value
        writePos = (writePos + 1) % capacity
        if (size < capacity) {
            size++
        }
    }

    fun snapshot(): FloatArray {
        val valid = validSamples
        val output = FloatArray(capacity)
        if (valid == 0) return output

        if (valid < capacity) {
            System.arraycopy(buffer, 0, output, capacity - valid, valid)
        } else {
            val tail = writePos
            val head = capacity - tail
            System.arraycopy(buffer, tail, output, 0, head)
            if (tail > 0) {
                System.arraycopy(buffer, 0, output, head, tail)
            }
        }
        return output
    }
}

object SpeechTerminationFactory {
    fun createStrategy(
        contextProvider: () -> Context,
        config: SmartTurnConfig,
        observer: SpeechTerminationObserver
    ): SpeechTerminationStrategy {
        return SmartTurnStrategy(contextProvider, config, observer)
    }
}
