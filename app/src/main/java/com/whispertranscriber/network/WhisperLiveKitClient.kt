package com.whispertranscriber.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit

class WhisperLiveKitClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun connect(
        serverUrl: String,
        onPartial: (String) -> Unit,
        onReadyToStop: (TranscriptionResult) -> Unit = {}
    ): WhisperLiveKitSession = withContext(Dispatchers.IO) {
        val opened = CompletableDeferred<Unit>()
        val config = CompletableDeferred<Boolean>()
        val finalResult = CompletableDeferred<TranscriptionResult>()
        val latestText = StringBuilder()

        val request = Request.Builder()
            .url(toWebSocketUrl(serverUrl))
            .build()

        lateinit var socket: WebSocket
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JsonParser.parseString(text).asJsonObject
                    if (json.stringValue("type") == "config") {
                        config.complete(json.booleanValue("useAudioWorklet"))
                        return
                    }

                    val result = WhisperLiveKitResultParser.parse(text)
                    if (result.text.isNotBlank()) {
                        synchronized(latestText) {
                            latestText.clear()
                            latestText.append(result.text)
                        }
                        onPartial(result.text)
                    }

                    if (result.readyToStop && !finalResult.isCompleted) {
                        val textValue = synchronized(latestText) { latestText.toString() }
                        val transcriptionResult = TranscriptionResult(success = true, text = textValue.trim())
                        finalResult.complete(transcriptionResult)
                        onReadyToStop(transcriptionResult)
                        webSocket.close(1000, "done")
                    }
                } catch (e: Exception) {
                    if (!finalResult.isCompleted) {
                        finalResult.complete(TranscriptionResult(success = false, text = "", error = e.message))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.isCompleted) opened.completeExceptionally(t)
                if (!config.isCompleted) config.completeExceptionally(t)
                if (!finalResult.isCompleted) {
                    finalResult.complete(TranscriptionResult(success = false, text = "", error = t.message))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finalResult.isCompleted) {
                    val textValue = synchronized(latestText) { latestText.toString() }
                    finalResult.complete(TranscriptionResult(success = true, text = textValue.trim()))
                }
            }
        }

        socket = client.newWebSocket(request, listener)
        opened.await()
        val supportsPcm = withTimeout(5_000) { config.await() }
        if (!supportsPcm) {
            socket.close(1000, "pcm not enabled")
            throw IllegalStateException("WhisperLiveKit server is not configured for PCM WebSocket input")
        }

        WhisperLiveKitSession(socket, finalResult)
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun toWebSocketUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        val wsBase = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
            else -> "ws://$trimmed"
        }
        return if (wsBase.endsWith("/asr")) wsBase else "$wsBase/asr"
    }
}

class WhisperLiveKitSession(
    private val socket: WebSocket,
    private val finalResult: CompletableDeferred<TranscriptionResult>
) {
    fun sendPcm(bytes: ByteArray) {
        socket.send(bytes.toByteString())
    }

    suspend fun finish(): TranscriptionResult {
        socket.send(ByteArray(0).toByteString())
        return finalResult.await()
    }

    fun cancel() {
        socket.cancel()
    }
}

object WhisperLiveKitResultParser {
    fun parse(message: String): WhisperLiveKitMessage {
        val json = JsonParser.parseString(message).asJsonObject
        if (json.stringValue("type") == "ready_to_stop") {
            return WhisperLiveKitMessage(text = "", readyToStop = true)
        }

        val parts = mutableListOf<String>()
        appendLines(parts, json.arrayValue("lines"))
        listOf("buffer_transcription", "buffer_diarization", "buffer_translation").forEach { key ->
            val value = json.stringValue(key).trim()
            if (value.isNotBlank()) parts.add(value)
        }

        return WhisperLiveKitMessage(text = parts.joinToString(" ").trim(), readyToStop = false)
    }

    private fun appendLines(parts: MutableList<String>, lines: JsonArray?) {
        if (lines == null) return
        for (element in lines) {
            val text = element.asJsonObject.stringValue("text").trim()
            if (text.isNotBlank()) parts.add(text)
        }
    }
}

private fun JsonObject.stringValue(key: String): String =
    get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

private fun JsonObject.booleanValue(key: String): Boolean =
    get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: false

private fun JsonObject.arrayValue(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

data class WhisperLiveKitMessage(
    val text: String,
    val readyToStop: Boolean
)
