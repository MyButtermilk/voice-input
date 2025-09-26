package org.futo.voiceinput.smartturn

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.FloatBuffer
import org.futo.voiceinput.AudioFeatureExtraction
import kotlin.math.sqrt

data class SmartTurnResult(
    val probability: Float,
    val isComplete: Boolean
)

object SmartTurnEngine {
    private const val TAG = "SmartTurnEngine"
    private const val MODEL_FILE_NAME = "smart-turn-v3.0.onnx"
    private const val SAMPLE_RATE = 16_000
    private const val MAX_SECONDS = 8
    private const val FEATURE_SIZE = 80
    private const val HOP_LENGTH = 160
    private const val NFFT = 400
    private const val PADDING_VALUE = 0.0

    private val lock = Any()
    private var session: OrtSession? = null
    private var environment: OrtEnvironment? = null
    private var featureExtractor: AudioFeatureExtraction? = null

    private fun ensureInitialized(context: Context) {
        if (session != null && featureExtractor != null && environment != null) {
            return
        }

        synchronized(lock) {
            if (session != null && featureExtractor != null && environment != null) {
                return
            }

            val modelFile = copyModelToInternal(context)
            val env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions()
            val ortSession = env.createSession(modelFile.absolutePath, sessionOptions)
            environment = env
            session = ortSession
            featureExtractor = AudioFeatureExtraction(
                featureSize = FEATURE_SIZE,
                samplingRate = SAMPLE_RATE,
                hopLength = HOP_LENGTH,
                chunkLength = MAX_SECONDS,
                nFFT = NFFT,
                paddingValue = PADDING_VALUE
            )
        }
    }

    private fun copyModelToInternal(context: Context): File {
        val dest = File(context.filesDir, MODEL_FILE_NAME)
        try {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Copying Smart Turn model to ${'$'}{dest.absolutePath}")
            }
            context.assets.open(MODEL_FILE_NAME).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (ex: IOException) {
            throw IOException("Unable to copy Smart Turn model", ex)
        }
        return dest
    }

    fun predictCompletion(context: Context, audio: FloatArray, validLength: Int): SmartTurnResult? {
        if (validLength <= 0) return null
        return try {
            ensureInitialized(context)
            val env = environment ?: return null
            val session = session ?: return null
            val extractor = featureExtractor ?: return null

            val truncated = truncateToLastSeconds(audio, validLength, SAMPLE_RATE * MAX_SECONDS)
            val activeSamples = validLength.coerceAtMost(truncated.size)
            if (activeSamples > 0) {
                var sumSquares = 0.0
                val startIndex = truncated.size - activeSamples
                for (i in startIndex until truncated.size) {
                    val sample = truncated[i].toDouble()
                    sumSquares += sample * sample
                }
                val meanSquare = sumSquares / activeSamples
                Log.d(TAG, "inputSamples=$activeSamples meanSquare=$meanSquare")
            } else {
                Log.d(TAG, "inputSamples=0 meanSquare=0.0")
            }

            val normalized = truncated.copyOf()
            if (activeSamples > 0) {
                val startIndex = normalized.size - activeSamples
                var sum = 0.0
                for (i in startIndex until normalized.size) {
                    sum += normalized[i].toDouble()
                }
                val mean = sum / activeSamples

                var varianceSum = 0.0
                for (i in startIndex until normalized.size) {
                    val centered = normalized[i] - mean.toFloat()
                    normalized[i] = centered
                    varianceSum += centered.toDouble() * centered.toDouble()
                }

                val variance = varianceSum / activeSamples
                val std = sqrt(variance).coerceAtLeast(1e-12)
                val invStd = (1.0 / std).toFloat()
                for (i in startIndex until normalized.size) {
                    normalized[i] *= invStd
                }
            }

            val doubleSamples = DoubleArray(normalized.size) { idx -> normalized[idx].toDouble() }
            val features = extractor.melSpectrogram(doubleSamples)
            val frames = features.size / FEATURE_SIZE
            val inputShape = longArrayOf(1, FEATURE_SIZE.toLong(), frames.toLong())
            val sess = session ?: return null
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), inputShape).use { inputTensor ->
                val outputs = sess.run(mapOf("input_features" to inputTensor))
                outputs.use {
                    val raw = outputs[0].value
                    val probability = when (raw) {
                        is FloatArray -> raw.first()
                        is Array<*> -> {
                            val first = raw.firstOrNull()
                            when (first) {
                                is FloatArray -> first.first()
                                is Float -> first
                                else -> null
                            }
                        }
                        else -> null
                    } ?: 0f
                    val isComplete = probability >= 0.5f
                    Log.d(TAG, "probability=$probability isComplete=$isComplete")
                    SmartTurnResult(probability, isComplete)
                }
            }
        } catch (ex: OrtException) {
            Log.e(TAG, "ONNX inference failed", ex)
            null
        } catch (ex: IOException) {
            Log.e(TAG, "Model load failed", ex)
            null
        }
    }

    private fun truncateToLastSeconds(buffer: FloatArray, validLength: Int, maxSamples: Int): FloatArray {
        val output = FloatArray(maxSamples)
        if (buffer.isEmpty() || maxSamples <= 0) {
            return output
        }

        val effectiveLength = validLength.coerceIn(0, buffer.size)
        if (effectiveLength == 0) {
            return output
        }

        val copyLength = effectiveLength.coerceAtMost(maxSamples)
        val copyStart = (buffer.size - copyLength).coerceAtLeast(0)
        val destStart = maxSamples - copyLength
        System.arraycopy(buffer, copyStart, output, destStart, copyLength)
        return output
    }
}

