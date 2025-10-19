package org.futo.voiceinput

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.voiceinput.ggml.DecodingMode
import org.futo.voiceinput.ml.RunState
import org.futo.voiceinput.ml.WhisperModelWrapper
import org.futo.voiceinput.settings.BEAM_SEARCH
import org.futo.voiceinput.settings.DISALLOW_SYMBOLS
import org.futo.voiceinput.settings.ENABLE_30S_LIMIT
import org.futo.voiceinput.settings.ENABLE_MULTILINGUAL
import org.futo.voiceinput.settings.ENGLISH_MODEL_INDEX
import org.futo.voiceinput.settings.LANGUAGE_TOGGLES
import org.futo.voiceinput.settings.MULTILINGUAL_MODEL_INDEX
import org.futo.voiceinput.settings.PERSONAL_DICTIONARY
import org.futo.voiceinput.settings.USE_LANGUAGE_SPECIFIC_MODELS
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
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.min

enum class MagnitudeState {
    NOT_TALKED_YET,
    MIC_MAY_BE_BLOCKED,
    TALKING,
    ENDING_SOON_VAD,
    ENDING_SOON_30S
}

abstract class AudioRecognizer {
    private var isRecording = false
    private var recorder: AudioRecord? = null

    fun isCurrentlyRecording(): Boolean {
        return isRecording
    }

    private var model: WhisperModelWrapper? = null

    private var floatSamples: FloatBuffer = FloatBuffer.allocate(16000 * 30)
    private var recorderJob: Job? = null
    private var modelJob: Job? = null
    private var loadModelJob: Job? = null

    private var canExpandSpace = true
    private fun expandSpaceIfAllowed(): Boolean {
        if(canExpandSpace) {
            // Allocate an extra 30 seconds
            val newSampleBuffer = FloatBuffer.allocate(floatSamples.capacity() + 16000 * 30)
            newSampleBuffer.put(floatSamples.array(), 0, floatSamples.capacity() - floatSamples.remaining())
            floatSamples = newSampleBuffer
            return true
        }
        return false
    }


    protected abstract val context: Context
    protected abstract val lifecycleScope: LifecycleCoroutineScope

    protected abstract fun cancelled()
    protected abstract fun finished(result: String)
    protected abstract fun languageDetected(result: String)
    protected abstract fun partialResult(result: String)
    protected abstract fun decodingStatus(status: RunState)

    protected abstract fun loading()
    protected abstract fun needPermission()
    protected abstract fun permissionRejected()

    protected abstract fun recordingStarted()
    protected abstract fun updateMagnitude(magnitude: Float, state: MagnitudeState)

    protected abstract fun processing()

    private var isVADPaused = false
    private var terminationStrategy: SpeechTerminationStrategy? = null
    fun pauseVAD(v: Boolean) {
        isVADPaused = v
        terminationStrategy?.setPaused(v)
    }

    fun finishRecognizerIfRecording() {
        if (isRecording) {
            finishRecognizer()
        }
    }

    protected fun finishRecognizer() {
        println("Finish called")
        onFinishRecording()
    }

    fun cancelRecognizer() {
        println("Cancelling recognition")
        reset()

        cancelled()
    }

    fun reset() {
        isVADPaused = false
        recorder?.stop()
        recorderJob?.cancel()
        modelJob?.cancel()
        isRecording = false

        floatSamples.clear()

        unfocusAudio()

        lifecycleScope.launch {
            modelJob?.join()
            model?.close()
            model = null
        }
    }

    protected fun openPermissionSettings() {
        val packageName = context.packageName
        val myAppSettings = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(
                "package:$packageName"
            )
        )
        myAppSettings.addCategory(Intent.CATEGORY_DEFAULT)
        myAppSettings.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(myAppSettings)

        cancelRecognizer()
    }

    private var focusRequest: AudioFocusRequest? = null
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
        }catch(e: Exception) {
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
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun tryLoadModelOrCancel(primaryModel: ModelData, secondaryModelP: ModelData?) {
        val secondaryModel = if(context.getSetting(USE_LANGUAGE_SPECIFIC_MODELS)) { secondaryModelP } else { null }
        try {
            model = WhisperModelWrapper(
                context,
                primaryModel,
                secondaryModel,
                context.getSetting(DISALLOW_SYMBOLS),
                context.getSetting(LANGUAGE_TOGGLES),
                onStatusUpdate = {
                    decodingStatus(it)
                },
                onPartialDecode = {
                    lifecycleScope.launch {
                        withContext(Dispatchers.Main) {
                            partialResult(it)
                        }
                    }
                }
            )
        } catch (e: IOException) {
            context.startModelDownloadActivity(
                listOf(primaryModel).let {
                    if (secondaryModel != null) it + secondaryModel
                    else it
                }
            )
            cancelRecognizer()
        }
    }

    private suspend fun loadModelInner() {
        try {
            val englishModelIdx = context.getSetting(ENGLISH_MODEL_INDEX)
            val multilingualModelIdx = context.getSetting(MULTILINGUAL_MODEL_INDEX)
            val languages = context.getSetting(LANGUAGE_TOGGLES)
            val isMultilingual = context.getSetting(ENABLE_MULTILINGUAL)

            if (forcedLanguage != null) {
                tryLoadModelOrCancel(
                    if (forcedLanguage == "en") {
                        ENGLISH_MODELS[englishModelIdx]
                    } else {
                        MULTILINGUAL_MODELS[multilingualModelIdx]
                    },

                    null
                )
            } else {
                if (isMultilingual) {
                    tryLoadModelOrCancel(
                        MULTILINGUAL_MODELS[multilingualModelIdx],
                        if (languages.contains("en")) {
                            ENGLISH_MODELS[englishModelIdx]
                        } else {
                            null
                        }
                    )
                } else {
                    tryLoadModelOrCancel(
                        ENGLISH_MODELS[englishModelIdx],
                        null
                    )
                }
            }
        } catch(e: OutOfMemoryError) {
            decodingStatus(RunState.OOMError)

            for(i in 0 until 2) {
                System.gc()
                System.runFinalization()
                delay(500L)
            }

            return loadModelInner()
        }
    }

    private fun loadModel() {
        if (model == null) {
            loadModelJob = lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    loadModelInner()
                }
            }
        }
    }

    private var forcedLanguage: String? = null
    fun forceLanguage(language: String?) {
        forcedLanguage = language
    }

    fun create() {
        loading()

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needPermission()
        } else {
            startRecording()
        }
    }

    fun permissionResultGranted() {
        startRecording()
    }

    fun permissionResultRejected() {
        permissionRejected()
    }

    private fun startRecording(numTries: Int = 0) {
        if (isRecording) {
            throw IllegalStateException("Start recording when already recording")
        }

        isVADPaused = false

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                16000 * 2 * 5
            )

            if(recorder!!.state == AudioRecord.STATE_UNINITIALIZED) {
                recorder!!.release()
                recorder = null

                println("Failed to initialize AudioRecord, retrying")

                if(numTries > 32) {
                    throw IllegalStateException("AudioRecord could not be initialized in 32 tries")
                }

                return startRecording(numTries + 1)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    recorder!!.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
                }
            } catch(e: Exception) {
                println("Failed to set preferred mic direction")
                e.printStackTrace()
            }

            recorder!!.startRecording()

            focusAudio()
            isRecording = true

            val canMicBeBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(SensorPrivacyManager::class.java) as SensorPrivacyManager).supportsSensorToggle(
                    SensorPrivacyManager.Sensors.MICROPHONE
                )
            } else {
                false
            }

            recorderJob = lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    canExpandSpace = context.getSetting(ENABLE_30S_LIMIT) == false

                    var hasTalked = false
                    var anyNoiseAtAll = false
                    var isMicBlocked = false

                    val speechMs = context.getSetting(VAD_SPEECH_MS)
                    val silenceMs = context.getSetting(VAD_SILENCE_MS)
                    val finalizeMs = context.getSetting(VAD_FINALIZE_MS)

                    val smartConfig = SmartTurnConfig(
                        minSpeechMillis = speechMs.toLong().coerceAtLeast(200L),
                        silenceHoldMillis = silenceMs.toLong().coerceAtLeast(200L),
                        finalizeDelayMillis = finalizeMs.toLong().coerceAtLeast(250L)
                    )
                    val observer = object : SpeechTerminationObserver {
                        override fun onMagnitude(update: MagnitudeUpdate) {
                            if (isVADPaused) return
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (isRecording) {
                                    updateMagnitude(update.magnitude, update.state)
                                }
                            }
                        }

                        override fun onEndingSoon(source: TerminationSource) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (isRecording) {
                                    updateMagnitude(1.0f, MagnitudeState.ENDING_SOON_VAD)
                                }
                            }
                        }

                        override fun onFinalize(source: TerminationSource) {
                            Log.d("SmartTurnObserver", "Finalize from $source (isRecording=$isRecording)")
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (isRecording) {
                                    finishRecognizer()
                                }
                            }
                        }
                    }

                    terminationStrategy = SpeechTerminationFactory.createStrategy(
                        contextProvider = { context },
                        config = smartConfig,
                        observer = observer
                    ).also { it.setPaused(isVADPaused) }

                    val samples = ShortArray(1600)

                    while(isRecording && recorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING){
                        yield()
                        val nRead = recorder!!.read(samples, 0, 1600, AudioRecord.READ_BLOCKING)

                        if(nRead <= 0) break
                        yield()

                        if(!isRecording || recorder!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) break

                        terminationStrategy?.onShortFrame(samples, nRead)

                        if(floatSamples.remaining() < 1600 && !expandSpaceIfAllowed()) {
                            withContext(Dispatchers.Main){ finishRecognizer() }
                            break
                        }

                        val floatChunk = FloatArray(nRead)
                        for (i in 0 until nRead) {
                            floatChunk[i] = samples[i].toFloat() / Short.MAX_VALUE.toFloat()
                        }
                        floatSamples.put(floatChunk)

                        val startSoundPassed = (floatSamples.position() > 16000 * 0.6)
                        if (!startSoundPassed) {
                            terminationStrategy?.reset()
                        }

                        val rms = sqrt(floatChunk.sumOf { (it * it).toDouble() } / floatChunk.size).toFloat()
                        if (startSoundPassed && rms > 0.01f) {
                            hasTalked = true
                        }
                        if (rms > 0.0001f) {
                            anyNoiseAtAll = true
                            isMicBlocked = false
                        }
                        if (!anyNoiseAtAll && canMicBeBlocked && floatSamples.position() > 2 * 16000) {
                            isMicBlocked = true
                        }
                        val magnitude = (1.0f - 0.1f.pow(24.0f * rms))
                        val state = when {
                            !canExpandSpace && floatSamples.remaining() < (16000 * 5) -> MagnitudeState.ENDING_SOON_30S
                            hasTalked -> MagnitudeState.TALKING
                            isMicBlocked -> MagnitudeState.MIC_MAY_BE_BLOCKED
                            else -> MagnitudeState.NOT_TALKED_YET
                        }
                        withContext(Dispatchers.Main) {
                            if (isRecording) {
                                updateMagnitude(magnitude, state)
                            }
                        }

                        terminationStrategy?.onFloatFrame(floatChunk, floatChunk.size)

                        while(true){
                            yield()
                            val nRead2 = recorder!!.read(samples, 0, 1600, AudioRecord.READ_NON_BLOCKING)
                            if(nRead2 > 0) {
                                terminationStrategy?.onShortFrame(samples, nRead2)
                                if(floatSamples.remaining() < nRead2 && !expandSpaceIfAllowed()){
                                    yield()
                                    withContext(Dispatchers.Main){ finishRecognizer() }
                                    break
                                }
                                val floatChunk2 = FloatArray(nRead2)
                                for (i in 0 until nRead2) {
                                    floatChunk2[i] = samples[i].toFloat() / Short.MAX_VALUE.toFloat()
                                }
                                floatSamples.put(floatChunk2)
                                terminationStrategy?.onFloatFrame(floatChunk2, floatChunk2.size)
                            } else {
                                break
                            }
                        }
                    }
                }
            }

            // We can only load model now, because the model loading may fail and need to cancel
            // everything we just did.
            // TODO: We could check if the model exists before doing all this work
            loadModel()

            recordingStarted()
        } catch(e: SecurityException){
            // It's possible we may have lost permission, so let's just ask for permission again
            needPermission()
        }
    }

    private suspend fun runModel(){
        if(loadModelJob != null && loadModelJob!!.isActive) {
            println("Model was not finished loading...")
            loadModelJob!!.join()
        }else if(model == null) {
            println("Model was null by the time runModel was called...")
            loadModel()
            loadModelJob!!.join()
        }

        val floatArray = floatSamples.array().sliceArray(0 until floatSamples.position())

        val words = context.getSetting(PERSONAL_DICTIONARY)
        val decodingMode = if(context.getSetting(BEAM_SEARCH)){ DecodingMode.BeamSearch5 } else { DecodingMode.Greedy }

        yield()
        val text = try {
            model!!.run(floatArray, words, forcedLanguage, decodingMode)
        } catch(e: OutOfMemoryError) {
            decodingStatus(RunState.OOMError)
            model!!.close()
            model = null
            loadModelJob = null

            for(i in 0 until 2) {
                System.gc()
                System.runFinalization()
                delay(500L)
            }

            loadModel()

            return runModel()
        }

        model!!.close()
        model = null

        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                finished(text)
            }
        }
    }

    private fun onFinishRecording() {
        if(!isRecording) {
            throw IllegalStateException("Should not call onFinishRecording when not recording")
        }

        isRecording = false

        recorderJob?.cancel()
        recorder?.stop()
        unfocusAudio()

        processing()

        modelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                runModel()
            }
        }
    }
}
