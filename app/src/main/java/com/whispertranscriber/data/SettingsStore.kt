package com.whispertranscriber.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.whispertranscriber.network.WhisperServerDiscovery

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val whisperServerUrl: String = "",
    val whisperServerPort: Int = WhisperServerDiscovery.DEFAULT_PORT,
    val audioQuality: String = "medium",
    val ttsServerUrl: String = "",
    val ttsServerPort: Int = 8880,
    val ttsVoice: String = "af_heart",
    val ttsSpeed: Float = 1.0f
)

class SettingsStore(private val context: Context) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("whisper_server_url")
        private val KEY_SERVER_PORT = intPreferencesKey("whisper_server_port")
        private val KEY_AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        private val KEY_TTS_SERVER_URL = stringPreferencesKey("tts_server_url")
        private val KEY_TTS_SERVER_PORT = intPreferencesKey("tts_server_port")
        private val KEY_TTS_VOICE = stringPreferencesKey("tts_voice")
        private val KEY_TTS_SPEED = floatPreferencesKey("tts_speed")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            whisperServerUrl = prefs[KEY_SERVER_URL] ?: AppSettings().whisperServerUrl,
            whisperServerPort = prefs[KEY_SERVER_PORT] ?: AppSettings().whisperServerPort,
            audioQuality = prefs[KEY_AUDIO_QUALITY] ?: AppSettings().audioQuality,
            ttsServerUrl = prefs[KEY_TTS_SERVER_URL] ?: AppSettings().ttsServerUrl,
            ttsServerPort = prefs[KEY_TTS_SERVER_PORT] ?: AppSettings().ttsServerPort,
            ttsVoice = prefs[KEY_TTS_VOICE] ?: AppSettings().ttsVoice,
            ttsSpeed = prefs[KEY_TTS_SPEED] ?: AppSettings().ttsSpeed
        )
    }

    suspend fun updateServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url }
    }

    suspend fun updateServerPort(port: Int) {
        context.dataStore.edit { it[KEY_SERVER_PORT] = port }
    }

    suspend fun updateAudioQuality(quality: String) {
        context.dataStore.edit { it[KEY_AUDIO_QUALITY] = quality }
    }

    suspend fun updateTtsServerUrl(url: String) {
        context.dataStore.edit { it[KEY_TTS_SERVER_URL] = url }
    }

    suspend fun updateTtsServerPort(port: Int) {
        context.dataStore.edit { it[KEY_TTS_SERVER_PORT] = port }
    }

    suspend fun updateTtsVoice(voice: String) {
        context.dataStore.edit { it[KEY_TTS_VOICE] = voice }
    }

    suspend fun updateTtsSpeed(speed: Float) {
        context.dataStore.edit { it[KEY_TTS_SPEED] = speed }
    }
}
