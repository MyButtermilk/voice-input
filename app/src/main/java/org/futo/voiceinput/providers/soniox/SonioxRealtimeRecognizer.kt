package org.futo.voiceinput.providers.soniox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.*
import android.os.Build
import androidx.lifecycle.LifecycleCoroutineScope
import com.konovalov.vad.Vad
import com.konovalov.vad.config.FrameSize
import com.konovalov.vad.config.Mode
import com.konovalov.vad.config.Model
import com.konovalov.vad.config.SampleRate
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.IOException
import org.futo.voiceinput.MagnitudeState
import org.futo.voiceinput.ml.RunState
import org.futo.voiceinput.recognizer.RecognizerControl
import org.futo.voiceinput.recognizer.RecognizerUiCallbacks
import org.futo.voiceinput.settings.LANGUAGE_TOGGLES
import org.futo.voiceinput.settings.PERSONAL_DICTIONARY
import org.futo.voiceinput.settings.SONIOX_API_KEY
import org.futo.voiceinput.settings.getSetting
import org.futo.voiceinput.settings.getSettingBlocking
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class SonioxRealtimeRecognizer(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val ui: RecognizerUiCallbacks
) : RecognizerControl {
    private var isRecording = false
    private var recorder: AudioRecord? = null
    private var recorderJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var isVADPaused = false
    private var forcedLanguage: String? = null

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var wsReady = false
    private var wsClosed = false
    private var finalText = StringBuilder()
    private var partialText = ""
    private var finishContinuation: CompletableJob? = null

    override fun isCurrentlyRecording(): Boolean = isRecording
    override fun finishRecognizerIfRecording() { if (isRecording) finish() }
    override fun reset() { stopRecordingInternal(); closeWs(); isVADPaused = false }
    override fun cancelRecognizer() { reset(); ui.onCancelled() }
    override fun permissionResultGranted() { lifecycleScope.launch { start() } }
    override fun permissionResultRejected() { ui.onPermissionRejected() }
    override fun pauseVAD(v: Boolean) { isVADPaused = v }
    override fun forceLanguage(lang: String?) { forcedLanguage = lang }

    override fun create() { lifecycleScope.launch { ui.onLoading(); start() } }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private suspend fun start() {
        if (!hasPermission()) { withContext(Dispatchers.Main) { ui.onNeedPermission() }; return }
        openWsAndThenRecord()
    }

    private fun openWsAndThenRecord() {
        val apiKey = context.getSettingBlocking(SONIOX_API_KEY.key, SONIOX_API_KEY.default)
        val languages = context.getSettingBlocking(LANGUAGE_TOGGLES.key, LANGUAGE_TOGGLES.default)
        val personalDict = context.getSettingBlocking(PERSONAL_DICTIONARY.key, PERSONAL_DICTIONARY.default)
        if (apiKey.isBlank()) {
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(context, "Soniox API key not set", Toast.LENGTH_LONG).show()
            }
            ui.onFinished("")
            return
        }
        val hints = if (forcedLanguage != null) setOf(forcedLanguage!!) else languages
        val config = JSONObject().apply {
            // Keep api_key in config for compatibility with Soniox examples,
            // but also send standard Bearer header on the WebSocket request.
            put("api_key", apiKey)
            put("model", "stt-rt-preview")
            put("audio_format", "pcm_s16le")
            put("sample_rate_hz", 16000)
            if (!personalDict.isNullOrBlank()) put("context", personalDict)
            put("enable_language_identification", true)
            put("language_hints", JSONArray(hints.toList()))
        }
        val req = Request.Builder()
            .url("wss://stt-rt.soniox.com/transcribe-websocket")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        wsClosed = false
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsReady = true
                webSocket.send(config.toString())
                lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Connected to Soniox realtime", Toast.LENGTH_SHORT).show()
                }
                lifecycleScope.launch(Dispatchers.Main) { ui.onDecodingStatus(RunState.StartedDecoding) }
                startRecordingLoop()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // ignore binary messages
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                wsClosed = true
                webSocket.close(code, reason)
                finishContinuation?.complete()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsClosed = true
                finishContinuation?.complete()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsClosed = true
                finishContinuation?.completeExceptionally(t)
                lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Soniox realtime error: ${t.message ?: "Unknown"}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Handle explicit server error early
            if (json.has("error") || json.optString("type") == "error") {
                val msg = json.optString("error")
                lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Soniox realtime error: ${msg.ifBlank { "Unknown" }}", Toast.LENGTH_LONG).show()
                }
                return
            }

            // 1) Token-style responses (preferred): { tokens: [{ text, is_final }, ...] }
            if (json.has("tokens") || json.has("final_tokens")) {
                val tokens = when {
                    json.has("final_tokens") -> json.getJSONArray("final_tokens")
                    else -> json.getJSONArray("tokens")
                }
                val sbNonFinal = StringBuilder()
                for (i in 0 until tokens.length()) {
                    val t = tokens.getJSONObject(i)
                    val tokenText = t.optString("text", "")
                    if (t.optBoolean("is_final", false)) {
                        finalText.append(tokenText)
                    } else {
                        sbNonFinal.append(tokenText)
                    }
                }
                partialText = sbNonFinal.toString()
                val current = (finalText.toString() + partialText)
                lifecycleScope.launch(Dispatchers.Main) { ui.onPartialResult(current) }
                return
            }

            // 2) Simple text responses, optionally flagged final
            val simpleText = json.optString("text", json.optString("transcript", ""))
            if (simpleText.isNotEmpty()) {
                val isFinal = json.optBoolean("is_final", false) ||
                        json.optBoolean("final", false) ||
                        (json.optString("type", "") == "final")
                if (isFinal) {
                    finalText.append(simpleText)
                    partialText = ""
                } else {
                    partialText = simpleText
                }
                val current = (finalText.toString() + partialText)
                lifecycleScope.launch(Dispatchers.Main) { ui.onPartialResult(current) }
                return
            }

            // If message didn't match known shapes, ignore quietly
        } catch (_: Exception) {
            // ignore malformed
        }
    }

    private fun startRecordingLoop() {
        if (wsClosed || webSocket == null) return
        isRecording = true
        val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorderBuffer = maxOf(bufferSize * 2, 1600 * 8)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recorderBuffer
        )
        if (recorder!!.state != AudioRecord.STATE_INITIALIZED) {
            recorder!!.release(); recorder = null
            lifecycleScope.launch(Dispatchers.Main) { ui.onPermissionRejected() }
            return
        }
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) recorder!!.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER) } catch (_: Exception) {}
        focusAudio()
        recorder!!.startRecording()
        lifecycleScope.launch(Dispatchers.Main) { ui.onRecordingStarted() }

        recorderJob = lifecycleScope.launch(Dispatchers.Default) {
            val speechMs = context.getSettingBlocking(org.futo.voiceinput.settings.VAD_SPEECH_MS.key, org.futo.voiceinput.settings.VAD_SPEECH_MS.default)
            val silenceMs = context.getSettingBlocking(org.futo.voiceinput.settings.VAD_SILENCE_MS.key, org.futo.voiceinput.settings.VAD_SILENCE_MS.default)
            val endSoonMs = context.getSettingBlocking(org.futo.voiceinput.settings.VAD_END_SOON_MS.key, org.futo.voiceinput.settings.VAD_END_SOON_MS.default)
            val finalizeMs = context.getSettingBlocking(org.futo.voiceinput.settings.VAD_FINALIZE_MS.key, org.futo.voiceinput.settings.VAD_FINALIZE_MS.default)
            fun msToFrames(ms: Int): Int = (ms + 29) / 30
            val endSoonFrames = msToFrames(endSoonMs)
            val finalizeFrames = msToFrames(finalizeMs)
            val vad = Vad.builder().setModel(Model.WEB_RTC_GMM).setMode(Mode.VERY_AGGRESSIVE).setFrameSize(FrameSize.FRAME_SIZE_480).setSampleRate(SampleRate.SAMPLE_RATE_16K).setSpeechDurationMs(speechMs).setSilenceDurationMs(silenceMs).build()
            val vadSampleBuffer = java.nio.ShortBuffer.allocate(480)
            var numConsecutiveNonSpeech = 0
            var numConsecutiveSpeech = 0
            var hasTalked = false
            var anyNoiseAtAll = false
            val samples = ShortArray(1600)
            while (isRecording && recorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val nRead = recorder!!.read(samples, 0, 1600, AudioRecord.READ_BLOCKING)
                if (nRead <= 0) break
                // VAD
                if (!isVADPaused) {
                    var remaining = nRead; var offset = 0
                    while (remaining > 0) {
                        if (!vadSampleBuffer.hasRemaining()) {
                            val isSpeech = vad.isSpeech(vadSampleBuffer.array())
                            vadSampleBuffer.clear(); vadSampleBuffer.rewind()
                            if (!isSpeech) { numConsecutiveNonSpeech++; numConsecutiveSpeech = 0 } else { numConsecutiveNonSpeech = 0; numConsecutiveSpeech++ }
                        }
                        val toRead = min(min(remaining, 480), vadSampleBuffer.remaining())
                        for (i in 0 until toRead) { vadSampleBuffer.put(samples[offset]); offset++; remaining-- }
                    }
                } else { numConsecutiveNonSpeech = 0 }
                // Magnitude UI
                val rms = sqrt(samples.sumOf { ((it.toFloat() / Short.MAX_VALUE.toFloat()).pow(2)).toDouble() } / samples.size).toFloat()
                if (rms > 0.0001f) anyNoiseAtAll = true
                if ((rms > 0.01) || (numConsecutiveSpeech > 8)) hasTalked = true
                val magnitude = (1.0f - 0.1f.pow(24.0f * rms))
                val state = if (hasTalked && (numConsecutiveNonSpeech > endSoonFrames)) MagnitudeState.ENDING_SOON_VAD else if (hasTalked) MagnitudeState.TALKING else if (!anyNoiseAtAll) MagnitudeState.MIC_MAY_BE_BLOCKED else MagnitudeState.NOT_TALKED_YET
                withContext(Dispatchers.Main) { ui.onUpdateMagnitude(magnitude, state) }

                // Send audio to WS as little-endian PCM16
                val bytes = ByteArray(nRead * 2)
                var j = 0
                for (i in 0 until nRead) {
                    val s = samples[i].toInt()
                    bytes[j++] = (s and 0xff).toByte()
                    bytes[j++] = ((s shr 8) and 0xff).toByte()
                }
                // Send audio frame as binary
                webSocket?.send(bytes.toByteString())

                // Auto-finalize with VAD when silent long enough
                if (hasTalked && (numConsecutiveNonSpeech > finalizeFrames)) {
                    withContext(Dispatchers.Main) { finish() }
                    break
                }
            }
        }
    }

    private fun finish() {
        if (!isRecording) return
        isRecording = false
        recorderJob?.cancel()
        try { recorder?.stop() } catch (_: Exception) {}
        unfocusAudio()
        lifecycleScope.launch(Dispatchers.Main) { ui.onProcessing() }
        // Send empty frame to signal finalize, then wait for server to close or complete
        finishContinuation = Job()
        webSocket?.send(ByteString.EMPTY)
        lifecycleScope.launch(Dispatchers.IO) {
            try { withTimeout(10000) { finishContinuation?.join() } } catch (_: Exception) {}
            val text = (finalText.toString() + partialText).trim()
            withContext(Dispatchers.Main) { ui.onFinished(text) }
            closeWs()
        }
    }

    private fun focusAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).build()
                audioManager.requestAudioFocus(focusRequest!!)
            }
        } catch (_: Exception) {}
    }
    private fun unfocusAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest!!)
                focusRequest = null
            }
        } catch (_: Exception) {}
    }
    private fun stopRecordingInternal() {
        isRecording = false
        recorderJob?.cancel(); recorderJob = null
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        unfocusAudio()
    }
    private fun closeWs() {
        try { webSocket?.close(1000, "") } catch (_: Exception) {}
        webSocket = null
    }
}
