package org.futo.voiceinput.providers.soniox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.hardware.SensorPrivacyManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.*
import android.widget.Toast
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
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
import org.futo.voiceinput.settings.getSetting
import org.futo.voiceinput.smartturn.MagnitudeUpdate
import org.futo.voiceinput.smartturn.SpeechTerminationFactory
import org.futo.voiceinput.smartturn.SpeechTerminationObserver
import org.futo.voiceinput.smartturn.SpeechTerminationStrategy
import org.futo.voiceinput.smartturn.SmartTurnConfig
import org.futo.voiceinput.smartturn.TerminationSource
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import org.json.JSONObject

class SonioxAsyncRecognizer(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val ui: RecognizerUiCallbacks
) : RecognizerControl {

    private var isRecording = false
    private var recorder: AudioRecord? = null
    private var floatSamples: FloatBuffer = FloatBuffer.allocate(16000 * 30)
    private var recorderJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var forcedLanguage: String? = null
    private var isVADPaused = false
    private var terminationStrategy: SpeechTerminationStrategy? = null

    override fun isCurrentlyRecording(): Boolean = isRecording

    override fun finishRecognizerIfRecording() {
        if (isRecording) finish()
    }

    override fun reset() {
        isVADPaused = false
        recorder?.stop()
        recorderJob?.cancel()
        isRecording = false
        floatSamples.clear()
        unfocusAudio()
        terminationStrategy = null
    }

    override fun create() {
        lifecycleScope.launch {
            ui.onLoading()
            withContext(Dispatchers.Default) {
                startRecording(0)
            }
        }
    }

    override fun cancelRecognizer() {
        reset()
        ui.onCancelled()
    }

    override fun permissionResultGranted() {
        lifecycleScope.launch { withContext(Dispatchers.Default) { startRecording(0) } }
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

    private fun focusAudio() {
        unfocusAudio()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                focusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .build()
                audioManager.requestAudioFocus(focusRequest!!)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unfocusAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest!!)
                }
                focusRequest = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hasPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun startRecording(numTries: Int) {
        if (!hasPermission()) {
            withContext(Dispatchers.Main) { ui.onNeedPermission() }
            return
        }

        floatSamples.clear()

        val format = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000)
            .build()

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
            recorder!!.release()
            recorder = null

            if (numTries > 32) throw IllegalStateException("AudioRecord could not be initialized in 32 tries")
            return startRecording(numTries + 1)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                recorder!!.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        recorder!!.startRecording()
        focusAudio()
        isRecording = true

        val canMicBeBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(SensorPrivacyManager::class.java) as SensorPrivacyManager)
                .supportsSensorToggle(SensorPrivacyManager.Sensors.MICROPHONE)
        } else false

        recorderJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                var hasTalked = false
                var anyNoiseAtAll = false
                var isMicBlocked = false

                val speechMs = context.getSetting(VAD_SPEECH_MS)
                val silenceMs = context.getSetting(VAD_SILENCE_MS)
                val finalizeMs = context.getSetting(VAD_FINALIZE_MS)
                val strategy = SpeechTerminationFactory.createStrategy(
                    contextProvider = { context },
                    config = SmartTurnConfig(
                        minSpeechMillis = speechMs.toLong().coerceAtLeast(200L),
                        silenceHoldMillis = silenceMs.toLong().coerceAtLeast(200L),
                        finalizeDelayMillis = finalizeMs.toLong().coerceAtLeast(250L)
                    ),
                    observer = object : SpeechTerminationObserver {
                        override fun onMagnitude(update: MagnitudeUpdate) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                ui.onUpdateMagnitude(update.magnitude, update.state)
                            }
                        }

                        override fun onEndingSoon(source: TerminationSource) {
                            // no-op; onMagnitude already updates state
                        }

                        override fun onFinalize(source: TerminationSource) {
                            android.util.Log.d("SonioxAsync", "Finalize from $source (isRecording=$isRecording)")
                            lifecycleScope.launch(Dispatchers.Main) {
                                finish()
                            }
                        }
                    }
                )
                terminationStrategy = strategy.also { it.setPaused(isVADPaused) }

                val samples = ShortArray(1600)

                withContext(Dispatchers.Main) { ui.onRecordingStarted() }

                while (isRecording && recorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    yield()
                    val nRead = recorder!!.read(samples, 0, 1600, AudioRecord.READ_BLOCKING)
                    if (nRead <= 0) break

                    if (!isRecording || recorder!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) break

                    terminationStrategy?.onShortFrame(samples, nRead)

                    if (floatSamples.remaining() < 1600) {
                        // stop if exceeded buffer (~30s)
                        withContext(Dispatchers.Main) { finish() }
                        break
                    }

                    val floatChunk = FloatArray(nRead)
                    for (i in 0 until nRead) {
                        floatChunk[i] = samples[i].toFloat() / Short.MAX_VALUE.toFloat()
                    }
                    floatSamples.put(floatChunk)
                    terminationStrategy?.onFloatFrame(floatChunk, floatChunk.size)

                    val startSoundPassed = (floatSamples.position() > 16000 * 0.6)
                    if (!startSoundPassed) {
                        terminationStrategy?.reset()
                        hasTalked = false
                    }

                    val rms = sqrt(samples.sumOf { ((it.toFloat() / Short.MAX_VALUE.toFloat()).pow(2)).toDouble() } / samples.size).toFloat()
                    if (startSoundPassed && (rms > 0.0001f)) anyNoiseAtAll = true
                    isMicBlocked = (!anyNoiseAtAll) && canMicBeBlocked
                    if (startSoundPassed && (rms > 0.01f)) hasTalked = true

                    val magnitude = (1.0f - 0.1f.pow(24.0f * rms))
                    val state = if (hasTalked) MagnitudeState.TALKING else MagnitudeState.NOT_TALKED_YET
                    withContext(Dispatchers.Main) { ui.onUpdateMagnitude(magnitude, state) }

                    // drain non-blocking reads
                    while (true) {
                        val nRead2 = recorder!!.read(samples, 0, 1600, AudioRecord.READ_NON_BLOCKING)
                        if (nRead2 > 0) {
                            terminationStrategy?.onShortFrame(samples, nRead2)
                            if (floatSamples.remaining() < nRead2) {
                                withContext(Dispatchers.Main) { finish() }
                                break
                            }
                            val floatChunk2 = FloatArray(nRead2)
                        for (i in 0 until nRead2) {
                            floatChunk2[i] = samples[i].toFloat() / Short.MAX_VALUE.toFloat()
                        }
                        floatSamples.put(floatChunk2)
                        terminationStrategy?.onFloatFrame(floatChunk2, floatChunk2.size)
                        } else break
                    }
                }
            }
        }
    }

    private fun finish() {
        if (!isRecording) return
        isRecording = false
        recorderJob?.cancel()
        recorder?.stop()
        unfocusAudio()
        ui.onProcessing()
        terminationStrategy = null
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runSoniox() }
        }
    }

    private fun writeWavFile(dest: File, floatArray: FloatArray) {
        val shorts = ShortArray(floatArray.size) { i ->
            val v = (floatArray[i].coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE).toInt()
            v.toShort()
        }
        val dataLen = shorts.size * 2
        val totalLen = 36 + dataLen
        val byteRate = 16000 * 2 * 1
        FileOutputStream(dest).use { out ->
            fun writeIntLE(v: Int) { out.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())) }
            fun writeShortLE(v: Int) { out.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())) }
            out.write("RIFF".toByteArray())
            writeIntLE(totalLen)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            writeIntLE(16) // PCM fmt chunk size
            writeShortLE(1) // AudioFormat PCM
            writeShortLE(1) // Channels 1
            writeIntLE(16000) // Sample rate
            writeIntLE(byteRate)
            writeShortLE(2) // block align
            writeShortLE(16) // bits per sample
            out.write("data".toByteArray())
            writeIntLE(dataLen)
            // PCM data
            val buf = ByteArray(dataLen)
            var j = 0
            for (s in shorts) {
                buf[j++] = (s.toInt() and 0xff).toByte()
                buf[j++] = ((s.toInt() shr 8) and 0xff).toByte()
            }
            out.write(buf)
        }
    }

    private fun renderTokensText(json: JSONObject): String {
        // Prefer final_tokens if available, else try tokens field
        val tokens = when {
            json.has("final_tokens") -> json.getJSONArray("final_tokens")
            json.has("tokens") -> json.getJSONArray("tokens")
            else -> null
        } ?: return json.optString("text", "")
        val sb = StringBuilder()
        for (i in 0 until tokens.length()) {
            val t = tokens.getJSONObject(i)
            sb.append(t.optString("text", ""))
        }
        return sb.toString()
    }

    private fun OkHttpClient.perform(request: Request): Response {
        return this.newCall(request).execute()
    }

    private suspend fun runSoniox() {
        ui.onDecodingStatus(RunState.StartedDecoding)
        val ex = try {
            val apiKey = context.getSetting(SONIOX_API_KEY)
            if (apiKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Soniox API key not set", Toast.LENGTH_LONG).show()
                }
                throw IllegalStateException("Soniox API key not set")
            }

            val floatArray = floatSamples.array().sliceArray(0 until floatSamples.position())
            val tmpFile = File.createTempFile("soniox_audio_", ".wav", context.cacheDir)
            writeWavFile(tmpFile, floatArray)

            val client = OkHttpClient()
            val authHeader = "Bearer $apiKey"

            // Upload file
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", tmpFile.name, tmpFile.asRequestBody("audio/wav".toMediaType()))
                .build()
            val uploadReq = Request.Builder()
                .url("https://api.soniox.com/v1/files")
                .addHeader("Authorization", authHeader)
                .post(multipart)
                .build()
            val uploadResp = client.perform(uploadReq)
            if (!uploadResp.isSuccessful) throw IOException("Upload failed: ${uploadResp.code}")
            val fileId = JSONObject(uploadResp.body!!.string()).getString("id")

            // Build transcription config
            val languages = context.getSetting(LANGUAGE_TOGGLES)
            val personalDict = context.getSetting(PERSONAL_DICTIONARY)
            val customVocab = context.getSetting(SONIOX_CONTEXT_VOCAB)
            val combinedContext = sequenceOf(personalDict, customVocab)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(separator = "\n")
            val config = JSONObject().apply {
                put("model", "stt-async-preview")
                if (combinedContext.isNotEmpty()) put("context", combinedContext)
                put("enable_language_identification", true)
                val hints = if (forcedLanguage != null) setOf(forcedLanguage!!) else languages
                put("language_hints", org.json.JSONArray(hints.toList()))
                put("file_id", fileId)
            }
            val transReq = Request.Builder()
                .url("https://api.soniox.com/v1/transcriptions")
                .addHeader("Authorization", authHeader)
                .post(config.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val transResp = client.perform(transReq)
            if (!transResp.isSuccessful) throw IOException("Create transcription failed: ${transResp.code}")
            val transcriptionId = JSONObject(transResp.body!!.string()).getString("id")

            // Poll status
            var status = "queued"
            var tries = 0
            while (status != "completed") {
                delay(1000)
                val stReq = Request.Builder()
                    .url("https://api.soniox.com/v1/transcriptions/$transcriptionId")
                    .addHeader("Authorization", authHeader)
                    .get()
                    .build()
                val stResp = client.perform(stReq)
                if (!stResp.isSuccessful) throw IOException("Status failed: ${stResp.code}")
                val stJson = JSONObject(stResp.body!!.string())
                status = stJson.getString("status")
                if (status == "error") throw IOException("Transcription error: ${stJson.optString("error_message")}")
                if (++tries > 300) throw IOException("Timeout waiting transcription")
            }

            // Fetch transcript
            val trReq = Request.Builder()
                .url("https://api.soniox.com/v1/transcriptions/$transcriptionId/transcript")
                .addHeader("Authorization", authHeader)
                .get()
                .build()
            val trResp = client.perform(trReq)
            if (!trResp.isSuccessful) throw IOException("Transcript failed: ${trResp.code}")
            val trJson = JSONObject(trResp.body!!.string())
            val text = renderTokensText(trJson)

            withContext(Dispatchers.Main) { ui.onFinished(text) }
            null
        } catch (e: Exception) {
            e
        }

        if (ex != null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Soniox error: ${ex.message ?: "Unknown"}", Toast.LENGTH_LONG).show()
                ui.onFinished("")
            }
        }
    }
}
