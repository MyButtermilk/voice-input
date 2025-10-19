package org.futo.voiceinput.providers.soniox

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Defines the contract for a realtime speech to text client.
 */
interface RealtimeSttClient {
    suspend fun start(): Boolean
    fun sendAudio(frame: ByteArray)
    fun stopAndFinalize()
    fun dispose(reason: String = "unspecified")
    fun onPartial(listener: (RealtimePartial) -> Unit)
    fun onFinal(listener: (String) -> Unit)
    fun onError(listener: (String) -> Unit)
    suspend fun awaitFinalResult(): String
}

data class RealtimePartial(
    val finalText: String,
    val partialText: String
)

/**
 * Soniox websocket implementation of [RealtimeSttClient].
 */
class SonioxRealtimeClient(
    private val client: OkHttpClient,
    private val request: Request,
    private val sessionConfigJson: String,
    private val scope: CoroutineScope
) : RealtimeSttClient {
    private val partialListeners = CopyOnWriteArrayList<(RealtimePartial) -> Unit>()
    private val finalListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val errorListeners = CopyOnWriteArrayList<(String) -> Unit>()

    private var webSocket: WebSocket? = null
    private var isReady = false
    private var isSessionConfigured = false
    private val pendingFrames = ArrayDeque<ByteString>()
    private val startDeferred = CompletableDeferred<Boolean>()
    private val finalDeferred = CompletableDeferred<String>()
    private val finalBuilder = StringBuilder()
    private var lastPartial: String = ""
    private var awaitingSessionFinal = false
    private var sessionFinished = false
    private var finalEmitted = false
    private var closeJob: Job? = null

    override suspend fun start(): Boolean {
        if (startDeferred.isCompleted) {
            return startDeferred.await()
        }
        sessionFinished = false
        finalEmitted = false
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isReady = true
                Log.d("SRT", "onOpen: sending session config")
                // Log anonymized session config for debugging
                try {
                    val cfg = JSONObject(sessionConfigJson)
                    if (cfg.has("api_key")) {
                        val key = cfg.optString("api_key")
                        val masked = if (key.length > 8) key.take(4) + "***" + key.takeLast(4) else "***"
                        cfg.put("api_key", masked)
                    }
                    Log.d("SRT", "config=${cfg.toString()}")
                } catch (_: Exception) { }
                updateAwaitingSessionFinal(false, "onOpen")
                webSocket.send(sessionConfigJson)
                if (!startDeferred.isCompleted) {
                    startDeferred.complete(true)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("SRT", "onMessage: ${text.take(256)}")
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("SRT", "onClosing code=$code reason=$reason")
                completeFinalDeferred("onClosing", finalBuilder.toString())
                updateAwaitingSessionFinal(false, "onClosing code=$code reason=$reason")
                isReady = false
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("SRT", "onClosed code=$code reason=$reason")
                completeFinalDeferred("onClosed", finalBuilder.toString())
                updateAwaitingSessionFinal(false, "onClosed code=$code reason=$reason")
                isReady = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = t.message ?: "Unknown error"
                Log.e("SRT", "onFailure: $message", t)
                response?.let {
                    Log.e("SRT", "onFailure: responseCode=${it.code} body=${it.body?.string()?.take(256)}")
                }
                emitError(message)
                if (!startDeferred.isCompleted) {
                    startDeferred.complete(false)
                }
                completeFinalDeferred("onFailure", finalBuilder.toString())
                updateAwaitingSessionFinal(false, "onFailure message=$message")
                isReady = false
            }
        })

        return startDeferred.await()
    }

    override fun sendAudio(frame: ByteArray) {
        val bytes = frame.toByteString()
        if (!isReady) {
            pendingFrames.addLast(bytes)
        } else {
            webSocket?.send(bytes)
        }
    }

    override fun stopAndFinalize() {
        Log.d("SRT", "stopAndFinalize invoked isReady=$isReady awaitingSessionFinal=$awaitingSessionFinal finalCompleted=${finalDeferred.isCompleted} finalLen=${finalBuilder.length} partialLen=${lastPartial.length} pendingFrames=${pendingFrames.size} closeJobActive=${closeJob != null}")
        if (!isReady) {
            Log.d("SRT", "stopAndFinalize: socket not ready, completing with current finalBuilder")
            completeFinalDeferred("stopAndFinalize socket not ready", finalBuilder.toString())
            return
        }
        updateAwaitingSessionFinal(true, "stopAndFinalize requested")
        Log.d("SRT", "stopAndFinalize: sending trailing silence")
        val trailingSilence = ByteArray(3200)
        repeat(5) { webSocket?.send(trailingSilence.toByteString()) }
        Log.d("SRT", "stopAndFinalize: sending EOS frame (pendingFrames=${pendingFrames.size})")
        webSocket?.send(ByteString.EMPTY)
        Log.d("SRT", "stopAndFinalize: sending finalize control frame")
        webSocket?.send("{\"type\":\"finalize\"}")
        if (lastPartial.isEmpty() && finalBuilder.isNotEmpty()) {
            Log.d("SRT", "stopAndFinalize: promoting buffered finalBuilder to listeners")
            emitFinal(finalBuilder.toString())
        }
        if (closeJob == null) {
            closeJob = scope.launch(Dispatchers.IO) {
                val start = SystemClock.elapsedRealtime()
                try {
                    finalDeferred.await()
                    Log.d("SRT", "stopAndFinalize: finalDeferred completed after ${SystemClock.elapsedRealtime() - start} ms")
                } catch (ex: Exception) {
                    Log.w("SRT", "stopAndFinalize: wait for finalDeferred failed: ${ex.message}")
                }
            }
        } else {
            Log.d("SRT", "stopAndFinalize: closeJob already running")
        }
    }

    override fun dispose(reason: String) {
        Log.d("SRT", "dispose(reason=$reason): awaitingSessionFinal=$awaitingSessionFinal closeJobActive=${closeJob != null} finalCompleted=${finalDeferred.isCompleted} finalLen=${finalBuilder.length} partialLen=${lastPartial.length}")
        closeJob?.cancel()
        closeJob = null
        updateAwaitingSessionFinal(false, "dispose reason=$reason")
        try {
            webSocket?.close(1000, "")
        } catch (ex: Exception) {
            Log.w("SRT", "dispose(reason=$reason): error closing websocket: ${ex.message}")
        } finally {
            webSocket = null
        }
        if (!finalDeferred.isCompleted) {
            Log.d("SRT", "dispose(reason=$reason): completing finalDeferred with buffered text")
            completeFinalDeferred("dispose", finalBuilder.toString())
        }
    }

    override fun onPartial(listener: (RealtimePartial) -> Unit) {
        partialListeners.add(listener)
    }

    override fun onFinal(listener: (String) -> Unit) {
        finalListeners.add(listener)
    }

    override fun onError(listener: (String) -> Unit) {
        errorListeners.add(listener)
    }

    override suspend fun awaitFinalResult(): String = finalDeferred.await()

    private fun flushPendingFrames() {
        if (!isReady || !isSessionConfigured) return
        while (pendingFrames.isNotEmpty()) {
            webSocket?.send(pendingFrames.removeFirst())
        }
    }

    private fun handleMessage(raw: String) {
        try {
            val payload = unwrapPayload(JSONObject(raw))

            // Generic Soniox error fields (without nested error object)
            if (payload.has("error_code") || payload.has("error_message")) {
                val code = payload.optInt("error_code", -1)
                val msg = payload.optString("error_message", "Unknown error")
                Log.e("SRT", "error_code=${code} error_message=${msg}")
                emitError("${code}: ${msg}")
                handleFinishedIfNeeded(payload)
                return
            }

            if (payload.has("error") || payload.optString("type") == "error") {
                val msg = payload.optString("error_message", payload.optString("error", "Unknown error"))
                emitError(msg)
                handleFinishedIfNeeded(payload)
                return
            }

            payload.optJSONObject("error")?.let { errorObject ->
                val msg = errorObject.optString("message", errorObject.optString("error", "Unknown error"))
                emitError(msg)
                handleFinishedIfNeeded(payload)
                return
            }

            val messageType = payload.optString("type", "")
            // Ignore purely informational messages, but do not drop messages that may contain transcripts
            if (messageType.equals("info", true) && !containsAnyTextLike(payload)) {
                if (!isSessionConfigured) {
                    isSessionConfigured = true
                    flushPendingFrames()
                }
                handleFinishedIfNeeded(payload)
                return
            }

            if (processTokenPayload(payload)) {
                if (!isSessionConfigured) {
                    isSessionConfigured = true
                    flushPendingFrames()
                }
                handleFinishedIfNeeded(payload)
                return
            }

            // Soniox variants: explicit fields, event-based { e:"partial"|"final", d:{text:..} }, nested results/alternatives
            val event = payload.optString("e", "")
            if (event.equals("partial", true) || event.equals("non_final", true)) {
                payload.optJSONObject("d")?.optStringOrNull("text")?.let { txt ->
                    lastPartial = txt
                    emitPartial()
                    handleFinishedIfNeeded(payload)
                    return
                }
            } else if (event.equals("final", true) || event.equals("final_result", true)) {
                payload.optJSONObject("d")?.optStringOrNull("text")?.let { txt ->
                    val sanitized = sanitizeFinalText(txt)
                    if (sanitized.isNotEmpty()) {
                        finalBuilder.append(sanitized)
                    }
                    lastPartial = ""
                    emitPartial()
                    emitFinal(finalBuilder.toString())
                    handleFinishedIfNeeded(payload)
                    return
                }
            }

            val finalFromFields = payload.optJSONObject("final_result")?.optStringOrNull("text")
                ?: payload.optStringOrNull("final_text")
            val partialFromFields = payload.optJSONObject("partial_result")?.optStringOrNull("text")
                ?: payload.optStringOrNull("partial_text")

            var finalUpdated = false
            var partialUpdated = false

            if (!finalFromFields.isNullOrEmpty()) {
                val sanitizedFinal = sanitizeFinalText(finalFromFields)
                finalBuilder.setLength(0)
                finalBuilder.append(sanitizedFinal)
                finalUpdated = true
            }

            if (!partialFromFields.isNullOrEmpty()) {
                lastPartial = sanitizeFinalText(partialFromFields)
                partialUpdated = true
            }

            var simpleText = payload.optStringOrNull("text")
                ?: payload.optStringOrNull("transcript")
                ?: payload.optJSONObject("result")?.optStringOrNull("text")
                ?: payload.optJSONArray("alternatives")?.optJSONObject(0)?.let { alt ->
                    alt.optStringOrNull("text") ?: alt.optStringOrNull("transcript")
                }
                ?: payload.optJSONArray("results")?.optJSONObject(0)?.let { r0 ->
                    r0.optJSONArray("alternatives")?.optJSONObject(0)?.let { alt ->
                        alt.optStringOrNull("text") ?: alt.optStringOrNull("transcript")
                    }
                }

            if (simpleText == "<end>") {
                simpleText = null
            }

            if (!simpleText.isNullOrEmpty()) {
                val resultsIsFinal = payload.optJSONArray("results")?.optJSONObject(0)?.let { r0 ->
                    r0.optBoolean("is_final", false) || r0.optBoolean("final", false)
                } ?: false
                val isFinal = resultsIsFinal ||
                        payload.optBoolean("is_final", false) ||
                        payload.optBoolean("final", false) ||
                        payload.optString("type", "") == "final"
                val sanitizedSimple = sanitizeFinalText(simpleText)
                if (isFinal) {
                    if (sanitizedSimple.isNotEmpty()) {
                        finalBuilder.append(sanitizedSimple)
                    }
                    lastPartial = ""
                    finalUpdated = true
                    partialUpdated = true
                } else {
                    lastPartial = sanitizedSimple
                    partialUpdated = true
                }
            }

            if (finalUpdated && !partialUpdated) {
                lastPartial = ""
                partialUpdated = true
            }

            if (finalUpdated || partialUpdated) {
                if (!isSessionConfigured) {
                    isSessionConfigured = true
                    flushPendingFrames()
                }
                Log.d("SRT", "emitPartial: finalLen=${finalBuilder.length} partialLen=${lastPartial.length}")
                emitPartial()
                if (finalUpdated) {
                    Log.d("SRT", "emitFinal: textLen=${finalBuilder.length}")
                    emitFinal(finalBuilder.toString())
                }
            }

            handleFinishedIfNeeded(payload)

        } catch (_: Exception) {
            // Ignore malformed messages
        }
    }

    private fun processTokenPayload(payload: JSONObject): Boolean {
        val sources = mutableListOf<TokenSource>()
        collectTokenSources(payload, sources)

        if (sources.isEmpty()) {
            return false
        }

        var finalUpdated = false
        var replacedFinal = false
        var consumedToken = false
        var encounteredFinalMarker = false
        val partialBuilder = StringBuilder()

        for (source in sources) {
            if (source.handling == TokenHandling.ReplaceFinal && !replacedFinal) {
                finalBuilder.setLength(0)
                replacedFinal = true
            }
            val array = source.array
            for (i in 0 until array.length()) {
                val token = array.optJSONObject(i) ?: continue
                val text = token.optString("text", "")
                if (text == "<fin>") {
                    if (!encounteredFinalMarker) {
                        Log.d("SRT", "processTokenPayload: encountered <fin> token; scheduling finalize")
                    }
                    encounteredFinalMarker = true
                    consumedToken = true
                    continue
                }
                if (text.isEmpty() || text == "<end>") continue
                consumedToken = true
                val isFinal = when (source.handling) {
                    TokenHandling.Mixed -> token.optBoolean("is_final", false)
                    TokenHandling.ReplaceFinal -> true
                    TokenHandling.PartialOnly -> false
                }
                if (isFinal) {
                    finalBuilder.append(text)
                    finalUpdated = true
                } else {
                    partialBuilder.append(text)
                }
            }
        }

        if (!consumedToken && !finalUpdated && !replacedFinal) {
            return false
        }

        val previousPartial = lastPartial
        val newPartial = partialBuilder.toString()
        val partialChanged = newPartial != previousPartial

        lastPartial = newPartial

        if (!partialChanged && !finalUpdated && !replacedFinal && !encounteredFinalMarker) {
            return false
        }

        if (partialChanged || finalUpdated || replacedFinal) {
            emitPartial()
        }
        if (finalUpdated || replacedFinal) {
            emitFinal(finalBuilder.toString())
        }
        if (encounteredFinalMarker) {
            finalizeSession("fin token")
        }
        return true
    }

    private fun collectTokenSources(
        json: JSONObject,
        destination: MutableList<TokenSource>,
        depth: Int = 0
    ) {
        if (depth > 3) {
            return
        }

        json.optJSONArray("tokens")?.let {
            destination.add(TokenSource(it, TokenHandling.Mixed))
        }
        json.optJSONArray("final_tokens")?.let {
            destination.add(TokenSource(it, TokenHandling.ReplaceFinal))
        }
        json.optJSONArray("non_final_tokens")?.let {
            destination.add(TokenSource(it, TokenHandling.PartialOnly))
        }

        if (depth >= 3) return

        val nestedKeys = listOf("result", "data", "payload", "message")
        for (key in nestedKeys) {
            val nested = json.optJSONObject(key) ?: continue
            collectTokenSources(nested, destination, depth + 1)
        }

        val arrayKeys = listOf("alternatives", "results")
        for (key in arrayKeys) {
            val array = json.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                val nested = array.optJSONObject(i) ?: continue
                collectTokenSources(nested, destination, depth + 1)
            }
        }
    }

    private fun unwrapPayload(json: JSONObject): JSONObject {
        var current = json
        while (true) {
            val nested = current.optJSONObject("data")
                ?: current.optJSONObject("result")
                ?: current.optJSONObject("payload")
                ?: current.optJSONObject("message")
                ?: break
            current = nested
        }
        return current
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name)) optString(name, null)?.takeIf { it.isNotEmpty() } else null

    private enum class TokenHandling {
        Mixed,
        ReplaceFinal,
        PartialOnly
    }

    private data class TokenSource(
        val array: JSONArray,
        val handling: TokenHandling
    )

    private fun updateAwaitingSessionFinal(target: Boolean, reason: String) {
        if (awaitingSessionFinal != target) {
            Log.d("SRT", "awaitingSessionFinal $awaitingSessionFinal -> $target (reason=$reason) finalCompleted=${finalDeferred.isCompleted}")
        } else {
            Log.d("SRT", "awaitingSessionFinal remains $target (reason=$reason) finalCompleted=${finalDeferred.isCompleted}")
        }
        awaitingSessionFinal = target
    }

    private fun completeFinalDeferred(reason: String, text: String = finalBuilder.toString()) {
        val sanitized = sanitizeFinalText(text)
        if (!finalDeferred.isCompleted) {
            Log.d("SRT", "completeFinalDeferred(reason=$reason, textLen=${sanitized.length}) awaitingSessionFinal=$awaitingSessionFinal")
            finalDeferred.complete(sanitized)
        } else {
            Log.d("SRT", "completeFinalDeferred skipped (already completed) reason=$reason textLen=${sanitized.length}")
        }
    }

    private fun finalizeSession(reason: String) {
        if (sessionFinished) {
            Log.d("SRT", "finalizeSession skipped (already finished) reason=$reason")
            return
        }
        sessionFinished = true
        Log.d("SRT", "finalizeSession start reason=$reason finalEmitted=$finalEmitted finalLen=${finalBuilder.length}")
        if (lastPartial.isNotEmpty()) {
            Log.d("SRT", "finalizeSession reason=$reason: promoting remaining partial tail")
            finalBuilder.append(lastPartial)
            lastPartial = ""
            emitPartial()
        }
        val sanitized = sanitizeFinalText(finalBuilder.toString())
        if (sanitized != finalBuilder.toString()) {
            finalBuilder.setLength(0)
            finalBuilder.append(sanitized)
        }
        if (!finalEmitted) {
            emitFinal(finalBuilder.toString())
        }
        Log.d("SRT", "finalizeSession reason=$reason finalLen=${finalBuilder.length}")
        updateAwaitingSessionFinal(false, reason)
        completeFinalDeferred(reason, finalBuilder.toString())
        try {
            webSocket?.close(1000, reason)
        } catch (_: Exception) { }
    }

    private fun handleFinishedIfNeeded(payload: JSONObject) {
        if (payload.optBoolean("finished", false)) {
            finalizeSession("finished flag")
        }
    }

    private fun sanitizeFinalText(text: String): String {
        if (text.isEmpty()) return text
        val withoutMarkers = text.replace("<fin>", "").replace("<end>", "")
        return withoutMarkers.replace(Regex("\\s+$"), "").trimEnd()
    }

    private fun emitPartial() {
        // Only display composing text for non-final; final stays in finalBuilder
        val snapshot = RealtimePartial(finalBuilder.toString(), lastPartial)
        partialListeners.forEach { listener ->
            listener(snapshot)
        }
    }

    private fun emitFinal(text: String) {
        val sanitized = sanitizeFinalText(text)
        if (sanitized != finalBuilder.toString()) {
            finalBuilder.setLength(0)
            finalBuilder.append(sanitized)
        }
        finalEmitted = true
        finalListeners.forEach { listener -> listener(sanitized) }
    }

    private fun emitError(message: String) {
        errorListeners.forEach { listener -> listener(message) }
    }

    private fun containsAnyTextLike(json: JSONObject): Boolean {
        if (json.has("text") || json.has("transcript") || json.has("tokens") ||
            json.has("final_tokens") || json.has("non_final_tokens") ||
            json.has("final_result") || json.has("partial_result") ||
            json.has("alternatives") || json.has("results")) {
            return true
        }
        val data = json.optJSONObject("data")
        val result = json.optJSONObject("result")
        val payload = json.optJSONObject("payload")
        val message = json.optJSONObject("message")
        return listOfNotNull(data, result, payload, message).any { containsAnyTextLike(it) }
    }
}

