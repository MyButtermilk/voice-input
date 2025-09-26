package org.futo.voiceinput.providers.soniox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.futo.voiceinput.MagnitudeState

/**
 * Lightweight holder for realtime STT state used by the recognizer UI.
 */
class RealtimeSttViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val aggregator = TranscriptAggregator()

    private val _state = MutableStateFlow(RealtimeUiState())
    val state: StateFlow<RealtimeUiState> = _state.asStateFlow()

    init {
        scope.launch {
            aggregator.state.collectLatest { render ->
                _state.update { current ->
                    current.copy(
                        finalText = render.finalText,
                        partialText = render.partialTail,
                        hasOpenSegments = render.hasOpenSegments
                    )
                }
            }
        }
    }

    fun reset() {
        aggregator.clear()
        _state.value = RealtimeUiState()
    }

    fun updatePartial(update: RealtimePartial) {
        aggregator.onPartial(update.finalText, update.partialText)
        _state.update { current ->
            current.copy(
                isError = false,
                errorMessage = null
            )
        }
    }

    fun updateFinal(final: String) {
        aggregator.onFinal(final)
        _state.update { current ->
            current.copy(
                isError = false,
                errorMessage = null
            )
        }
    }

    fun updateMagnitude(magnitude: Float, state: MagnitudeState) {
        _state.update { current ->
            current.copy(
                magnitude = magnitude,
                magnitudeState = state,
                isRecording = true
            )
        }
    }

    fun markRecordingStopped() {
        _state.update { current ->
            current.copy(isRecording = false)
        }
    }

    fun setError(message: String) {
        _state.update { current ->
            current.copy(
                isError = true,
                errorMessage = message
            )
        }
    }

    fun promotePartialToFinal() {
        aggregator.promotePartialToFinal()
    }

    fun combinedText(): String {
        val snapshot = aggregator.state.value
        return (snapshot.finalText + snapshot.partialTail).trim()
    }

    fun finalText(): String = aggregator.state.value.finalText

    internal fun currentRenderState(): TranscriptRenderState = aggregator.state.value

    suspend fun awaitDrain(graceMs: Long = 800L, idleMs: Long = 250L) {
        aggregator.awaitDrain(graceMs = graceMs, idleMs = idleMs)
    }

    fun close() {
        scope.cancel()
    }
}

data class RealtimeUiState(
    val finalText: String = "",
    val partialText: String = "",
    val magnitude: Float = 0f,
    val magnitudeState: MagnitudeState = MagnitudeState.NOT_TALKED_YET,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val isRecording: Boolean = false,
    val hasOpenSegments: Boolean = false
)
