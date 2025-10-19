package org.futo.voiceinput.smartturn

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.min

class SileroVad(
    private val contextProvider: () -> Context,
    private val resetTimeoutMillis: Long
) {
    companion object {
        private const val TAG = "SileroVad"
        private const val MODEL_FILE_NAME = "silero_vad.onnx"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SIZE = 512
        private const val CONTEXT_SIZE = 64
        private const val STATE_SIZE = 2 * 1 * 128
    }

    private val pending = FloatArray(CHUNK_SIZE)
    private var pendingSize = 0
    private val contextBuffer = FloatArray(CONTEXT_SIZE)
    private val stateBuffer = FloatArray(STATE_SIZE)
    private val inputBuffer = FloatArray(CONTEXT_SIZE + CHUNK_SIZE)
    private var lastChunkAt = 0L
    private var lastProbability = 0f
    private var lastSpeechAt = 0L

    fun reset() {
        pendingSize = 0
        pending.fill(0f)
        contextBuffer.fill(0f)
        stateBuffer.fill(0f)
        inputBuffer.fill(0f)
        lastChunkAt = 0L
        lastProbability = 0f
        lastSpeechAt = 0L
    }

    fun accept(frame: ShortArray, length: Int, threshold: Float): Float? {
        if (length <= 0) return null
        val floats = FloatArray(length)
        val scale = 1f / Short.MAX_VALUE.toFloat()
        for (i in 0 until length) {
            floats[i] = frame[i] * scale
        }
        return accept(floats, length, threshold)
    }

    fun accept(frame: FloatArray, length: Int, threshold: Float): Float? {
        if (length <= 0) return null
        val context = contextProvider.invoke()
        var lastProb: Float? = null
        var index = 0
        while (index < length) {
            val needed = CHUNK_SIZE - pendingSize
            val toCopy = min(needed, length - index)
            System.arraycopy(frame, index, pending, pendingSize, toCopy)
            pendingSize += toCopy
            index += toCopy
            if (pendingSize == CHUNK_SIZE) {
                val now = SystemClock.elapsedRealtime()
                val prob = runChunk(context, pending, now)
                lastProb = prob
                lastProbability = prob
                if (prob >= threshold) {
                    lastSpeechAt = now
                }
                pendingSize = 0
            }
        }
        return lastProb
    }

    fun lastProbability(): Float = lastProbability

    fun lastSpeechAt(): Long = lastSpeechAt

    private fun runChunk(context: Context, chunk: FloatArray, timestamp: Long): Float {
        val session = SessionHolder.ensure(context)
        if (lastChunkAt != 0L && timestamp - lastChunkAt >= resetTimeoutMillis) {
            stateBuffer.fill(0f)
            contextBuffer.fill(0f)
        }
        lastChunkAt = timestamp

        System.arraycopy(contextBuffer, 0, inputBuffer, 0, CONTEXT_SIZE)
        System.arraycopy(chunk, 0, inputBuffer, CONTEXT_SIZE, CHUNK_SIZE)

        val env = session.environment
        val ortSession = session.session

        var probability = 0f
        try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(inputBuffer), longArrayOf(1, (CONTEXT_SIZE + CHUNK_SIZE).toLong())).use { inputTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(stateBuffer), longArrayOf(2, 1, 128)).use { stateTensor ->
                    OnnxTensor.createTensor(env, longArrayOf(SAMPLE_RATE.toLong())).use { srTensor ->
                        ortSession.run(mapOf(
                            "input" to inputTensor,
                            "state" to stateTensor,
                            "sr" to srTensor
                        )).use { outputs ->
                            probability = extractProbability(outputs[0].value)
                            updateState(outputs[1].value)
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Silero inference failed", ex)
            probability = 0f
        }

        System.arraycopy(inputBuffer, inputBuffer.size - CONTEXT_SIZE, contextBuffer, 0, CONTEXT_SIZE)
        return probability
    }

    private fun extractProbability(value: Any?): Float {
        return extractProbabilityRecursive(value) ?: 0f
    }

    private fun extractProbabilityRecursive(value: Any?): Float? {
        return when (value) {
            is FloatArray -> value.firstOrNull()
            is DoubleArray -> value.firstOrNull()?.toFloat()
            is Array<*> -> {
                for (element in value) {
                    val candidate = extractProbabilityRecursive(element)
                    if (candidate != null) return candidate
                }
                null
            }
            is Float -> value
            is Double -> value.toFloat()
            else -> null
        }
    }

    private fun updateState(value: Any?) {
        stateBuffer.fill(0f)
        if (value !is Array<*>) return
        fillStateRecursive(value, 0)
    }

    private fun fillStateRecursive(value: Array<*>, start: Int): Int {
        var offset = start
        for (element in value) {
            when (element) {
                is FloatArray -> {
                    val len = element.size.coerceAtMost(stateBuffer.size - offset)
                    System.arraycopy(element, 0, stateBuffer, offset, len)
                    offset += len
                }
                is Array<*> -> {
                    offset = fillStateRecursive(element, offset)
                }
            }
            if (offset >= stateBuffer.size) break
        }
        return offset
    }

    private data class Session(val environment: OrtEnvironment, val session: OrtSession)

    private object SessionHolder {
        private val lock = Any()
        private var environment: OrtEnvironment? = null
        private var session: OrtSession? = null

        fun ensure(context: Context): Session {
            var existing = session
            if (existing != null) {
                val env = environment ?: throw IllegalStateException("OrtEnvironment missing")
                return Session(env, existing!!)
            }
            synchronized(lock) {
                existing = session
                if (existing != null) {
                    val env = environment ?: throw IllegalStateException("OrtEnvironment missing")
                    return Session(env, existing!!)
                }
                val modelFile = copyModelToFiles(context)
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setInterOpNumThreads(1)
                    setIntraOpNumThreads(1)
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                }
                val createdSession = env.createSession(modelFile.absolutePath, options)
                environment = env
                session = createdSession
                return Session(env, createdSession)
            }
        }

        private fun copyModelToFiles(context: Context): File {
            val dest = File(context.filesDir, MODEL_FILE_NAME)
            if (dest.exists() && dest.length() > 0) {
                return dest
            }
            try {
                context.assets.open(MODEL_FILE_NAME).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (ex: IOException) {
                Log.e(TAG, "Failed to copy Silero VAD model", ex)
                throw ex
            }
            return dest
        }
    }
}
