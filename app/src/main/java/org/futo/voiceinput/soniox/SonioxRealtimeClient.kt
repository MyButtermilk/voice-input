package org.futo.voiceinput.soniox

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * Simple wrapper around the Soniox real-time WebSocket API.
 */
class SonioxRealtimeClient(
    private val apiKey: String,
    private val model: String = "stt-rt-preview",
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val DEFAULT_REALTIME_WS_URL =
            "wss://stt-rt.soniox.com/transcribe-websocket"
    }

    private var webSocket: WebSocket? = null

    fun start(
        onPartial: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val request = Request.Builder()
            .url(DEFAULT_REALTIME_WS_URL)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val init = JSONObject()
                init.put("api_key", apiKey)
                init.put("model", model)
                ws.send(init.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = JSONObject(text)
                val result = json.optString("text")
                if (result.isNotEmpty()) {
                    onPartial(result)
                }
                if (json.optBoolean("finished")) {
                    ws.close(1000, null)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onError(t)
            }
        })
    }

    fun sendAudio(data: ByteArray) {
        webSocket?.send(data.toByteString())
    }

    fun finalize() {
        webSocket?.send("{\"type\":\"finalize\"}")
    }

    fun stop() {
        webSocket?.close(1000, null)
    }
}

