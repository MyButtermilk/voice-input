package org.futo.voiceinput.providers.soniox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.futo.voiceinput.MagnitudeState
import org.futo.voiceinput.ml.RunState
import org.futo.voiceinput.recognizer.RecognizerControl
import org.futo.voiceinput.recognizer.RecognizerUiCallbacks
import org.futo.voiceinput.settings.LANGUAGE_TOGGLES
import org.futo.voiceinput.settings.PERSONAL_DICTIONARY
import org.futo.voiceinput.settings.SONIOX_API_KEY
import org.futo.voiceinput.settings.SONIOX_CONTEXT_VOCAB
import org.futo.voiceinput.settings.VAD_FINALIZE_MS
import org.futo.voiceinput.settings.VAD_SILENCE_MS
import org.futo.voiceinput.settings.VAD_SPEECH_MS
import org.futo.voiceinput.settings.getSettingBlocking
import org.futo.voiceinput.smartturn.MagnitudeUpdate
import org.futo.voiceinput.smartturn.SpeechTerminationFactory
import org.futo.voiceinput.smartturn.SpeechTerminationObserver
import org.futo.voiceinput.smartturn.SpeechTerminationStrategy
import org.futo.voiceinput.smartturn.SmartTurnConfig
import org.futo.voiceinput.smartturn.TerminationSource
import org.json.JSONArray
import org.json.JSONObject
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
    private var terminationStrategy: SpeechTerminationStrategy? = null

    private val httpClient = OkHttpClient()
    private var sttClient: SonioxRealtimeClient? = null
    private val viewModel = RealtimeSttViewModel()
    private var finalizeJob: Job? = null
    private var autoFinalizeJob: Job? = null

    override fun isCurrentlyRecording(): Boolean = isRecording

    override fun finishRecognizerIfRecording() {
        if (isRecording) finish()
    }

    override fun reset() {
        stopRecordingInternal()
        disposeClient()
        isVADPaused = false
        viewModel.reset()
        terminationStrategy = null
    }

    override fun create() {
        lifecycleScope.launch {
            ui.onLoading()
            start()
        }
    }

    override fun cancelRecognizer() {
        reset()
        ui.onCancelled()
    }

    override fun permissionResultGranted() {
        lifecycleScope.launch { start() }
    }

    override fun permissionResultRejected() {
        ui.onPermissionRejected()
    }

    override fun pauseVAD(v: Boolean) {
        isVADPaused = v
        terminationStrategy?.setPaused(v)
    }

    override fun forceLanguage(lang: String?) {
        forcedLanguage = lang
    }

    private suspend fun start() {
        if (!hasPermission()) {
            withContext(Dispatchers.Main) { ui.onNeedPermission() }
            return
        }
        viewModel.reset()
        openRealtimeSession()
    }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openRealtimeSession() {
        val apiKey = context.getSettingBlocking(SONIOX_API_KEY.key, SONIOX_API_KEY.default)
        if (apiKey.isBlank()) {
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(context, "Soniox API key not set", Toast.LENGTH_LONG).show()
                ui.onRealtimeError("Soniox API key not set")
                ui.onFinished("")
            }
            return
        }

        val languages = context.getSettingBlocking(LANGUAGE_TOGGLES.key, LANGUAGE_TOGGLES.default)
        val personalDict = context.getSettingBlocking(PERSONAL_DICTIONARY.key, PERSONAL_DICTIONARY.default)
        val customVocab = context.getSettingBlocking(SONIOX_CONTEXT_VOCAB.key, SONIOX_CONTEXT_VOCAB.default)

        val combinedContext = sequenceOf(personalDict, customVocab)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")

        val config = JSONObject().apply {
            put("api_key", apiKey)
            put("model", "stt-rt-preview")
            put("audio_format", "pcm_s16le")
            put("sample_rate", 16000)
            put("num_channels", 1)
            if (combinedContext.isNotEmpty()) put("context", combinedContext)
            put("enable_language_identification", true)
            put("enable_endpoint_detection", true)
            // No language_hints: auto-detect languages
        }

        val request = Request.Builder()
            .url("wss://stt-rt.soniox.com/transcribe-websocket")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        disposeClient()
        val client = SonioxRealtimeClient(httpClient, request, config.toString(), lifecycleScope)
        attachClientListeners(client)
        sttClient = client

        lifecycleScope.launch(Dispatchers.IO) {
            val started = try {
                client.start()
            } catch (ex: Exception) {
                android.util.Log.e("SRT", "Failed to start realtime client", ex)
                false
            }
            if (!started) {
                viewModel.setError("Unable to connect to Soniox realtime")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to connect to Soniox realtime", Toast.LENGTH_LONG).show()
                    ui.onRealtimeError("Failed to connect to Soniox realtime")
                    ui.onFinished("")
                }
                disposeClient()
                return@launch
            }

            withContext(Dispatchers.Main) {
                ui.onDecodingStatus(RunState.StartedDecoding)
            }
            startRecordingLoop()
        }
    }

    private fun attachClientListeners(client: SonioxRealtimeClient) {
        client.onPartial { update ->
            android.util.Log.d("SRT", "partial: final='${update.finalText.take(64)}' partial='${update.partialText.take(64)}'")
            viewModel.updatePartial(update)
            if (update.partialText.isNotBlank()) {
                cancelAutoFinalize()
            } else if (update.finalText.isNotBlank()) {
                scheduleAutoFinalizeIfIdle()
            }
            lifecycleScope.launch(Dispatchers.Main) {
                // Update final results if they changed
                if (update.finalText.isNotEmpty()) {
                    ui.onRealtimeFinalResult(update.finalText)
                }
                // Always update partial results (this will trigger renderRealtimeUi)
                ui.onPartialResult(update.partialText)
            }
        }
        client.onFinal { final ->
            android.util.Log.d("SRT", "final: '${final.take(64)}'")
            viewModel.updateFinal(final)
            lifecycleScope.launch(Dispatchers.Main) {
                ui.onRealtimeFinalResult(final)
                ui.onPartialResult("") // Clear partial result
                // Do not close/commit here; wait for VAD-end or user stop
            }
        }
        client.onError { message ->
            android.util.Log.e("SRT", "Soniox realtime error: $message")
            viewModel.setError(message)
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(context, "Soniox realtime error: $message", Toast.LENGTH_LONG).show()
                ui.onRealtimeError(message)
            }
        }
    }

    private fun startRecordingLoop() {
        val client = sttClient ?: return
        if (isRecording) return

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
        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            recorder = null
            lifecycleScope.launch(Dispatchers.Main) { ui.onPermissionRejected() }
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                recorder?.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
            }
        } catch (_: Exception) {}

        focusAudio()
        recorder?.startRecording()
        lifecycleScope.launch(Dispatchers.Main) { ui.onRecordingStarted() }

        recorderJob = lifecycleScope.launch(Dispatchers.Default) {
            val speechMs = context.getSettingBlocking(VAD_SPEECH_MS.key, VAD_SPEECH_MS.default)
            val silenceMs = context.getSettingBlocking(VAD_SILENCE_MS.key, VAD_SILENCE_MS.default)
            val finalizeMs = context.getSettingBlocking(VAD_FINALIZE_MS.key, VAD_FINALIZE_MS.default)
            val termination = SpeechTerminationFactory.createStrategy(
                contextProvider = { context },
                config = SmartTurnConfig(
                    minSpeechMillis = speechMs.toLong().coerceAtLeast(200L),
                    silenceHoldMillis = silenceMs.toLong().coerceAtLeast(200L),
                    finalizeDelayMillis = finalizeMs.toLong().coerceAtLeast(250L)
                ),
                observer = object : SpeechTerminationObserver {
                    override fun onMagnitude(update: MagnitudeUpdate) {
                        viewModel.updateMagnitude(update.magnitude, update.state)
                        lifecycleScope.launch(Dispatchers.Main) {
                            ui.onUpdateMagnitude(update.magnitude, update.state)
                        }
                    }

                    override fun onEndingSoon(source: TerminationSource) {
                        // no additional action needed; magnitude handles state
                    }

                    override fun onFinalize(source: TerminationSource) {
                        android.util.Log.d("SonioxRealtime", "Finalize from $source (isRecording=$isRecording)")
                        lifecycleScope.launch(Dispatchers.Main) { finish() }
                    }
                }
            )
            terminationStrategy = termination.also { it.setPaused(isVADPaused) }

            val samples = ShortArray(1600)
            var sentFrames = 0
            var bytesSentTotal = 0L

            while (isRecording && recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val nRead = recorder?.read(samples, 0, 1600, AudioRecord.READ_BLOCKING) ?: break
                if (nRead <= 0) break

                terminationStrategy?.onShortFrame(samples, nRead)

                val bytes = ByteArray(nRead * 2)
                var j = 0
                for (i in 0 until nRead) {
                    val s = samples[i].toInt()
                    bytes[j++] = (s and 0xff).toByte()
                    bytes[j++] = ((s shr 8) and 0xff).toByte()
                }
                client.sendAudio(bytes)
                sentFrames += 1
                bytesSentTotal += bytes.size
                if (sentFrames % 20 == 0) {
                    android.util.Log.d("SRT", "audio: nRead=${nRead} bytesSentTotal=${bytesSentTotal}")
                }

                terminationStrategy?.onFloatFrame(samples.map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray(), nRead)
            }
        }
    }

    private fun cancelAutoFinalize() {
        autoFinalizeJob?.cancel()
        autoFinalizeJob = null
    }

    private fun scheduleAutoFinalizeIfIdle() {
        if (!isRecording) return
        if (finalizeJob != null) return
        if (autoFinalizeJob != null) return
        autoFinalizeJob = lifecycleScope.launch {
            delay(AUTO_FINALIZE_DELAY_MS)
            val snapshot = viewModel.state.value
            if (isRecording && finalizeJob == null && snapshot.partialText.isBlank()) {
                finish()
            }
        }
    }

    private fun finish() {
        android.util.Log.d("SonioxRealtime", "finish() invoked isRecording=$isRecording")
        if (!isRecording) return
        cancelAutoFinalize()
        isRecording = false

        val job = recorderJob
        recorderJob = null
        job?.cancel()
        recorder?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
            } catch (_: Exception) {}
        }
        recorder?.release()
        recorder = null
        unfocusAudio()
        viewModel.markRecordingStopped()
        terminationStrategy = null

        val client = sttClient
        // Do not switch UI to "Processing" for realtime; keep showing streaming UI
        if (client == null) {
            val finalText = viewModel.combinedText()
            lifecycleScope.launch(Dispatchers.Main) { ui.onFinished(finalText) }
            return
        }

        finalizeJob?.cancel()
        finalizeJob = null
        client.stopAndFinalize()
        android.util.Log.d("SRT", "stopAndFinalize sent")
        finalizeJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                withTimeout(30_000) { client.awaitFinalResult() }
            } catch (ex: Exception) {
                android.util.Log.w("SonioxRealtime", "awaitFinalResult failed: ${ex.message}")
                viewModel.combinedText()
            }
            val combined = viewModel.combinedText()
            val finalText = if (combined.length > result.length) combined else result
            android.util.Log.d("SonioxRealtime", "final text='${finalText}' (len=${finalText.length}) resultLen=${result.length} combinedLen=${combined.length}")
            viewModel.updateFinal(finalText)
            withContext(Dispatchers.Main) {
                ui.onRealtimeFinalResult(viewModel.finalText())
                ui.onFinished(finalText)
            }
            disposeClient(cancelFinalize = false)
            this@SonioxRealtimeRecognizer.finalizeJob = null
        }
    }

    private fun disposeClient(cancelFinalize: Boolean = true) {
        if (cancelFinalize) {
            finalizeJob?.cancel()
            finalizeJob = null
        }
        cancelAutoFinalize()
        sttClient?.dispose()
        sttClient = null
    }

    private companion object {
        private const val AUTO_FINALIZE_DELAY_MS = 200L
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
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            }
        } catch (_: Exception) {}
    }

    private fun stopRecordingInternal() {
        isRecording = false
        cancelAutoFinalize()
        recorderJob?.cancel()
        recorderJob = null
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        unfocusAudio()
    }
}
