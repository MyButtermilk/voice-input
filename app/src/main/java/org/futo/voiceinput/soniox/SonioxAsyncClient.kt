package org.futo.voiceinput.soniox

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Minimal client for Soniox asynchronous transcription API.
 *
 * This client uploads audio data and then polls Soniox for the final
 * transcription result.
 */
class SonioxAsyncClient(
    private val apiKey: String,
    private val model: String = "en_v2",
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = DEFAULT_ASYNC_URL,
) {
    companion object {
        const val DEFAULT_ASYNC_URL =
            "https://api.soniox.com/transcribe/async"
    }

    /**
     * Submit audio for transcription. Returns the file id used for polling.
     */
    fun submitJob(audioData: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                "audio.wav",
                audioData.toRequestBody("application/octet-stream".toMediaType())
            )
            .addFormDataPart("model", model)
            .build()

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val json = JSONObject(response.body!!.string())
            return json.getString("file_id")
        }
    }

    /**
     * Poll Soniox for a result of a previously submitted job.
     */
    suspend fun waitForResult(fileId: String): String {
        val statusUrl = "$baseUrl/$fileId"
        while (true) {
            val request = Request.Builder()
                .url(statusUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val json = JSONObject(response.body!!.string())
                if (json.optString("status") == "done") {
                    return json.getString("text")
                }
            }
            delay(1000)
        }
    }
}

