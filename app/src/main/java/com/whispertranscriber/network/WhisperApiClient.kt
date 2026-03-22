package com.whispertranscriber.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WhisperApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(
        serverUrl: String,
        apiKey: String,
        audioData: ByteArray,
        language: String,
        fileName: String = "audio.wav"
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val audioBody = audioData.toRequestBody("audio/wav".toMediaType())

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, audioBody)
            .addFormDataPart("model", "whisper-1")

        if (language.isNotBlank()) {
            multipartBuilder.addFormDataPart("language", language)
        }

        val requestBuilder = Request.Builder()
            .url(serverUrl)
            .post(multipartBuilder.build())

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        val response = client.newCall(requestBuilder.build()).await()

        response.use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return@withContext TranscriptionResult(
                    success = false,
                    text = "",
                    error = "HTTP ${resp.code}: $body"
                )
            }

            try {
                val json = JSONObject(body)
                val text = json.optString("text", "")
                TranscriptionResult(success = true, text = text.trim())
            } catch (e: Exception) {
                TranscriptionResult(success = false, text = "", error = "Parse error: ${e.message}")
            }
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        })
    }
}

data class TranscriptionResult(
    val success: Boolean,
    val text: String,
    val error: String? = null
)
