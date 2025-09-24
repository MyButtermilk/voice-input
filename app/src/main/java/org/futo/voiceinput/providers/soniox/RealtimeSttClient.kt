package org.futo.voiceinput.providers.soniox

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
    fun dispose()
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
    private var closeJob: Job? = null

    override suspend fun start(): Boolean {
        if (startDeferred.isCompleted) {
            return startDeferred.await()
        }
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
                awaitingSessionFinal = false
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
                if (!finalDeferred.isCompleted) {
                    finalDeferred.complete(finalBuilder.toString())
                }
                awaitingSessionFinal = false
                isReady = false
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finalDeferred.isCompleted) {
                    finalDeferred.complete(finalBuilder.toString())
                }
                awaitingSessionFinal = false
                isReady = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = t.message ?: "Unknown error"
                Log.e("SRT", "onFailure: ${'$'}message", t)
                response?.let {
                    Log.e("SRT", "onFailure: responseCode=${it.code} body=${it.body?.string()?.take(256)}")
                }
                emitError(message)
                if (!startDeferred.isCompleted) {
                    startDeferred.complete(false)
                }
                if (!finalDeferred.isCompleted) {
                    finalDeferred.complete(finalBuilder.toString())
                }
                awaitingSessionFinal = false
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
        if (!isReady) {
            if (!finalDeferred.isCompleted) {
                finalDeferred.complete(finalBuilder.toString())
            }
            return
        }
        awaitingSessionFinal = true
        webSocket?.send(ByteString.EMPTY)
        if (lastPartial.isEmpty() && finalBuilder.isNotEmpty()) {
            emitFinal(finalBuilder.toString())
        }
        if (closeJob == null) {
            closeJob = scope.launch(Dispatchers.IO) {
                // Safety timeout in case server never closes the socket
                try {
                    finalDeferred.await()
                } catch (_: Exception) {
                    // ignored
                }
            }
        }
    }

    override fun dispose() {
        closeJob?.cancel()
        closeJob = null
        awaitingSessionFinal = false
        try {
            webSocket?.close(1000, "")
        } catch (_: Exception) {
        } finally {
            webSocket = null
        }
        if (!finalDeferred.isCompleted) {
            finalDeferred.complete(finalBuilder.toString())
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
                return
            }

            if (payload.has("error") || payload.optString("type") == "error") {
                val msg = payload.optString("error_message", payload.optString("error", "Unknown error"))
                emitError(msg)
                return
            }

            payload.optJSONObject("error")?.let { errorObject ->
                val msg = errorObject.optString("message", errorObject.optString("error", "Unknown error"))
                emitError(msg)
                return
            }

            val messageType = payload.optString("type", "")
            // Ignore purely informational messages, but do not drop messages that may contain transcripts
            if (messageType.equals("info", true) && !containsAnyTextLike(payload)) {
                if (!isSessionConfigured) {
                    isSessionConfigured = true
                    flushPendingFrames()
                }
                return
            }

            if (processTokenPayload(payload)) {
                if (!isSessionConfigured) {
                    isSessionConfigured = true
                    flushPendingFrames()
                }
                return
            }

            // Soniox variants: explicit fields, event-based { e:"partial"|"final", d:{text:..} }, nested results/alternatives
            val event = payload.optString("e", "")
            if (event.equals("partial", true) || event.equals("non_final", true)) {
                payload.optJSONObject("d")?.optStringOrNull("text")?.let { txt ->
                    lastPartial = txt
                    emitPartial()
                    return
                }
            } else if (event.equals("final", true) || event.equals("final_result", true)) {
                payload.optJSONObject("d")?.optStringOrNull("text")?.let { txt ->
                    finalBuilder.append(txt)
                    lastPartial = ""
                    emitPartial()
                    emitFinal(finalBuilder.toString())
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
                finalBuilder.setLength(0)
                finalBuilder.append(finalFromFields)
                finalUpdated = true
            }

            if (!partialFromFields.isNullOrEmpty()) {
                lastPartial = partialFromFields
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
                if (isFinal) {
                    finalBuilder.append(simpleText)
                    lastPartial = ""
                    finalUpdated = true
                    partialUpdated = true
                } else {
                    lastPartial = simpleText
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

        if (!partialChanged && !finalUpdated && !replacedFinal) {
            return false
        }

        emitPartial()
        if (finalUpdated || replacedFinal) {
            emitFinal(finalBuilder.toString())
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

    private fun emitPartial() {
        // Only display composing text for non-final; final stays in finalBuilder
        val snapshot = RealtimePartial(finalBuilder.toString(), lastPartial)
        partialListeners.forEach { listener ->
            listener(snapshot)
        }
    }



    private fun emitFinal(text: String) {
        finalListeners.forEach { listener -> listener(text) }
        if (awaitingSessionFinal && !finalDeferred.isCompleted) {
            finalDeferred.complete(text)
            awaitingSessionFinal = false
        }
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
