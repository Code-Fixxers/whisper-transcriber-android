package com.whispertranscriber.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AppUpdateClient(
    private val context: Context,
    private val manifestUrl: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersionCode: Int): UpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(manifestUrl)
            .header("Cache-Control", "no-cache")
            .build()

        val response = client.newCall(request).await()
        response.use {
            if (!it.isSuccessful) {
                return@withContext UpdateCheckResult.Failed("Manifest HTTP ${it.code}")
            }
            val body = it.body?.string().orEmpty()
            val manifest = UpdateManifest.parse(body)
            if (manifest.isNewerThan(currentVersionCode)) {
                UpdateCheckResult.Available(manifest)
            } else {
                UpdateCheckResult.UpToDate
            }
        }
    }

    suspend fun download(manifest: UpdateManifest): File = withContext(Dispatchers.IO) {
        val apkFile = File(context.cacheDir, "updates/app-${manifest.versionCode}.apk")
        apkFile.parentFile?.mkdirs()

        if (apkFile.exists() && UpdateVerifier.matches(apkFile, manifest)) {
            return@withContext apkFile
        }

        val request = Request.Builder()
            .url(manifest.apkUrl)
            .header("Cache-Control", "no-cache")
            .build()
        val response = client.newCall(request).await()
        response.use {
            if (!it.isSuccessful) throw IOException("APK HTTP ${it.code}")
            val bytes = it.body?.bytes() ?: throw IOException("Empty APK response")
            if (bytes.size.toLong() != manifest.sizeBytes) {
                throw IOException("APK size mismatch: expected ${manifest.sizeBytes}, got ${bytes.size}")
            }
            if (manifest.sha256 != null && !UpdateVerifier.matchesSha256(bytes, manifest.sha256)) {
                throw IOException("APK SHA-256 mismatch")
            }
            apkFile.writeBytes(bytes)
        }

        apkFile
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

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val commit: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String?
) {
    fun isNewerThan(currentVersionCode: Int): Boolean = versionCode > currentVersionCode

    companion object {
        fun parse(jsonText: String): UpdateManifest {
            val json = JsonParser.parseString(jsonText).asJsonObject
            return UpdateManifest(
                versionCode = json.requiredInt("versionCode"),
                versionName = json.requiredString("versionName"),
                commit = json.requiredString("commit"),
                apkUrl = json.requiredString("apkUrl"),
                sizeBytes = json.requiredLong("sizeBytes"),
                sha256 = json.optionalString("sha256").takeIf { it.isNotBlank() }
            )
        }
    }
}

private fun JsonObject.requiredString(key: String): String = get(key).asString
private fun JsonObject.requiredInt(key: String): Int = get(key).asInt
private fun JsonObject.requiredLong(key: String): Long = get(key).asLong
private fun JsonObject.optionalString(key: String): String =
    get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

sealed class UpdateCheckResult {
    data object UpToDate : UpdateCheckResult()
    data class Available(val manifest: UpdateManifest) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

object UpdateVerifier {
    fun matches(file: File, manifest: UpdateManifest): Boolean {
        if (!file.exists() || file.length() != manifest.sizeBytes) return false
        return manifest.sha256 == null || matchesSha256(file.readBytes(), manifest.sha256)
    }

    fun matchesSha256(bytes: ByteArray, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return digest.equals(expected, ignoreCase = true)
    }
}
