package com.whispertranscriber.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val whisperServerUrl: String = "",
    val audioQuality: String = "medium"
)

class SettingsStore(private val context: Context) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("whisper_server_url")
        private val KEY_AUDIO_QUALITY = stringPreferencesKey("audio_quality")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            whisperServerUrl = prefs[KEY_SERVER_URL] ?: AppSettings().whisperServerUrl,
            audioQuality = prefs[KEY_AUDIO_QUALITY] ?: AppSettings().audioQuality
        )
    }

    suspend fun updateServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url }
    }

    suspend fun updateAudioQuality(quality: String) {
        context.dataStore.edit { it[KEY_AUDIO_QUALITY] = quality }
    }
}
