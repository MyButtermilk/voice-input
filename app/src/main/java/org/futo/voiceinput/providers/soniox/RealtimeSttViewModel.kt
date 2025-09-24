package org.futo.voiceinput.providers.soniox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.futo.voiceinput.MagnitudeState

/**
 * Lightweight holder for realtime STT state used by the recognizer UI.
 */
class RealtimeSttViewModel {
    private val _state = MutableStateFlow(RealtimeUiState())
    val state: StateFlow<RealtimeUiState> = _state.asStateFlow()

    fun reset() {
        _state.value = RealtimeUiState()
    }

    fun updatePartial(update: RealtimePartial) {
        _state.update { current ->
            current.copy(
                finalText = update.finalText,
                partialText = update.partialText,
                isError = false,
                errorMessage = null
            )
        }
    }

    fun updateFinal(final: String) {
        _state.update { current ->
            current.copy(
                finalText = final,
                partialText = "",
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

    fun combinedText(): String {
        val snapshot = _state.value
        return (snapshot.finalText + snapshot.partialText).trim()
    }

    fun finalText(): String = _state.value.finalText
}

data class RealtimeUiState(
    val finalText: String = "",
    val partialText: String = "",
    val magnitude: Float = 0f,
    val magnitudeState: MagnitudeState = MagnitudeState.NOT_TALKED_YET,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val isRecording: Boolean = false
)
