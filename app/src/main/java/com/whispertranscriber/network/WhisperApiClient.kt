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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WhisperApiClient {

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val client: OkHttpClient = run {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun transcribe(
        serverUrl: String,
        audioData: ByteArray,
        fileName: String = "audio.wav"
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val url = serverUrl.trimEnd('/') + "/v1/audio/transcriptions"
        val audioBody = audioData.toRequestBody("audio/wav".toMediaType())

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, audioBody)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(multipartBody)
            .build()

        val response = client.newCall(request).await()

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
