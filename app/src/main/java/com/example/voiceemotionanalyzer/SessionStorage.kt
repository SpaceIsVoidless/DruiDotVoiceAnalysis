package com.example.voiceemotionanalyzer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages local persistence of emotion analysis sessions using SharedPreferences.
 * Each session is stored as a JSON string and can be retrieved for history viewing.
 */
class SessionStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("emotion_sessions", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SESSIONS = "saved_sessions"
        private const val MAX_SESSIONS = 20 // Keep last 20 sessions
    }

    /**
     * Save current session data to local storage
     */
    fun saveSession(
        emotionPoints: List<EmotionPoint>,
        emotionCounts: Map<String, Int>,
        sessionStartMs: Long
    ) {
        if (emotionPoints.isEmpty()) return

        val sessionJson = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("sessionStartMs", sessionStartMs)
            put("sampleCount", emotionPoints.size)

            // Dominant emotion
            val dominant = emotionCounts.maxByOrNull { it.value }
            put("dominantEmotion", dominant?.key ?: "neutral")
            put("dominantCount", dominant?.value ?: 0)

            // Average confidence
            val avgConf = emotionPoints.map { it.confidence }.average()
            put("avgConfidence", avgConf)

            // Duration
            val durationSec = if (sessionStartMs > 0)
                (System.currentTimeMillis() - sessionStartMs) / 1000
            else 0
            put("durationSeconds", durationSec)

            // Emotion distribution
            val countsJson = JSONObject()
            emotionCounts.forEach { (k, v) -> countsJson.put(k, v) }
            put("emotionCounts", countsJson)

            // Store the actual data points
            val pointsArray = JSONArray()
            emotionPoints.forEach { point ->
                val pointJson = JSONObject().apply {
                    put("timeMs", point.timeMs)
                    put("emotion", point.emotion)
                    put("score", point.score)
                    put("text", point.text)
                    put("confidence", point.confidence)
                }
                pointsArray.put(pointJson)
            }
            put("dataPoints", pointsArray)
        }

        // Load existing sessions
        val sessions = loadSessionsRaw()
        sessions.put(sessionJson)

        // Keep only last MAX_SESSIONS
        val trimmed = if (sessions.length() > MAX_SESSIONS) {
            val newArray = JSONArray()
            for (i in (sessions.length() - MAX_SESSIONS) until sessions.length()) {
                newArray.put(sessions.getJSONObject(i))
            }
            newArray
        } else sessions

        prefs.edit().putString(KEY_SESSIONS, trimmed.toString()).apply()
    }

    /**
     * Load all saved sessions as a list of summary data
     */
    fun loadSessions(): List<SessionSummary> {
        val sessions = loadSessionsRaw()
        val summaries = mutableListOf<SessionSummary>()

        for (i in 0 until sessions.length()) {
            try {
                val obj = sessions.getJSONObject(i)
                val counts = mutableMapOf<String, Int>()
                val countsJson = obj.optJSONObject("emotionCounts")
                if (countsJson != null) {
                    countsJson.keys().forEach { key ->
                        counts[key] = countsJson.getInt(key)
                    }
                }

                summaries.add(
                    SessionSummary(
                        timestamp = obj.getLong("timestamp"),
                        sampleCount = obj.getInt("sampleCount"),
                        dominantEmotion = obj.getString("dominantEmotion"),
                        avgConfidence = obj.getDouble("avgConfidence").toFloat(),
                        durationSeconds = obj.getLong("durationSeconds"),
                        emotionCounts = counts
                    )
                )
            } catch (e: Exception) {
                // Skip malformed entries
            }
        }

        return summaries.sortedByDescending { it.timestamp }
    }

    /**
     * Clear all saved sessions
     */
    fun clearAllSessions() {
        prefs.edit().remove(KEY_SESSIONS).apply()
    }

    private fun loadSessionsRaw(): JSONArray {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }
}

/**
 * Summary of a saved session for display in the history list.
 */
data class SessionSummary(
    val timestamp: Long,
    val sampleCount: Int,
    val dominantEmotion: String,
    val avgConfidence: Float,
    val durationSeconds: Long,
    val emotionCounts: Map<String, Int>
) {
    fun getFormattedDate(): String {
        val formatter = SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }

    fun getFormattedDuration(): String {
        val min = durationSeconds / 60
        val sec = durationSeconds % 60
        return String.format("%d:%02d", min, sec)
    }

    fun getDistributionSummary(): String {
        return emotionCounts
            .filter { it.value > 0 }
            .entries
            .sortedByDescending { it.value }
            .joinToString(" · ") { "${it.key.replaceFirstChar { c -> c.uppercase() }}: ${it.value}" }
    }
}
