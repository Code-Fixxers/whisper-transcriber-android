package com.whispertranscriber.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class KokoroTtsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun voices(serverUrl: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/v1/audio/voices")
            .header("Cache-Control", "no-cache")
            .build()
        val response = client.newCall(request).await()
        response.use {
            if (!it.isSuccessful) throw IOException("Voices HTTP ${it.code}")
            KokoroVoiceParser.parse(it.body?.string().orEmpty())
        }
    }

    suspend fun synthesizeWav(
        serverUrl: String,
        text: String,
        voice: String,
        speed: Float
    ): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/v1/audio/speech")
            .post(KokoroSpeechRequest.json(text, voice, speed).toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).await()
        response.use {
            if (!it.isSuccessful) throw IOException("Speech HTTP ${it.code}: ${it.body?.string().orEmpty()}")
            it.body?.bytes() ?: throw IOException("Empty speech response")
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

object KokoroVoiceParser {
    fun parse(jsonText: String): List<String> {
        val json = JsonParser.parseString(jsonText).asJsonObject
        return json.getAsJsonArray("voices")
            ?.mapNotNull { it.takeUnless { value -> value.isJsonNull }?.asString }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }
}

object KokoroSpeechRequest {
    fun json(text: String, voice: String, speed: Float): String {
        val request = JsonObject().apply {
            addProperty("model", "kokoro")
            addProperty("input", text)
            addProperty("voice", voice)
            addProperty("response_format", "wav")
            addProperty("speed", speed.coerceIn(0.25f, 4.0f))
            addProperty("stream", false)
        }
        return request.toString()
    }
}
