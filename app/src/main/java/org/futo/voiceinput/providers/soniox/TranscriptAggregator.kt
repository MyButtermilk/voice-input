package org.futo.voiceinput.providers.soniox

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TranscriptRenderState(
    val finalText: String,
    val partialTail: String,
    val hasOpenSegments: Boolean,
    val lastUpdatedAt: Long
)

internal class TranscriptAggregator {
    private val _state = MutableStateFlow(
        TranscriptRenderState(
            finalText = "",
            partialTail = "",
            hasOpenSegments = false,
            lastUpdatedAt = SystemClock.elapsedRealtime()
        )
    )
    val state: StateFlow<TranscriptRenderState> = _state.asStateFlow()

    private fun update(finalText: String, partialTail: String) {
        val now = SystemClock.elapsedRealtime()
        _state.value = TranscriptRenderState(
            finalText = finalText,
            partialTail = partialTail,
            hasOpenSegments = partialTail.isNotBlank(),
            lastUpdatedAt = now
        )
    }

    fun onPartial(finalText: String, partialTail: String) {
        update(finalText, partialTail)
    }

    fun onFinal(finalText: String) {
        update(finalText, "")
    }

    fun clear() {
        update("", "")
    }

    fun promotePartialToFinal() {
        val snapshot = _state.value
        if (snapshot.partialTail.isNotBlank()) {
            update(snapshot.finalText + snapshot.partialTail, "")
        }
    }

    suspend fun awaitDrain(graceMs: Long = 800L, idleMs: Long = 250L) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val snapshot = _state.value
            val now = SystemClock.elapsedRealtime()
            val idle = now - snapshot.lastUpdatedAt >= idleMs
            if (!snapshot.hasOpenSegments && idle) return
            if (now - start >= graceMs) return
            delay(30)
        }
    }
}
