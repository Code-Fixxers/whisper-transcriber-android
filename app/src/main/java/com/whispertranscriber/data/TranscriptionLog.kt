package com.whispertranscriber.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val durationMs: Long,
    val success: Boolean,
    val text: String,
    val error: String? = null
)

class TranscriptionLog(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("transcription_log", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 100
    }

    fun addEntry(durationMs: Long, success: Boolean, text: String, error: String? = null) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            durationMs = durationMs,
            success = success,
            text = text,
            error = error
        )
        val entries = getEntries().toMutableList()
        entries.add(0, entry)
        if (entries.size > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size).clear()
        }
        saveEntries(entries)
    }

    fun getEntries(): List<LogEntry> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                LogEntry(
                    timestamp = obj.getString("timestamp"),
                    durationMs = obj.getLong("durationMs"),
                    success = obj.getBoolean("success"),
                    text = obj.getString("text"),
                    error = if (obj.has("error")) obj.getString("error") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun saveEntries(entries: List<LogEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("durationMs", entry.durationMs)
                put("success", entry.success)
                put("text", entry.text)
                entry.error?.let { put("error", it) }
            })
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
