package com.example.voiceemotionanalyzer

data class EmotionPoint(
    val timeMs: Long,
    val emotion: String,
    val score: Float,
    val text: String,
    val confidence: Float = 1f,
    val emotions: Map<String, Float> = emptyMap() // All detected emotions with scores
) {
    companion object {
        val EMOTION_COLORS = mapOf(
            "joy" to 0xFF10B981.toInt(),
            "neutral" to 0xFFF59E0B.toInt(),
            "sad" to 0xFF6366F1.toInt(),
            "anger" to 0xFFEF4444.toInt(),
            "fear" to 0xFF8B5CF6.toInt(),
            "surprise" to 0xFFEC4899.toInt()
        )

        val EMOTION_LIGHT_COLORS = mapOf(
            "joy" to 0xFFD1FAE5.toInt(),
            "neutral" to 0xFFFEF3C7.toInt(),
            "sad" to 0xFFE0E7FF.toInt(),
            "anger" to 0xFFFEE2E2.toInt(),
            "fear" to 0xFFEDE9FE.toInt(),
            "surprise" to 0xFFFCE7F3.toInt()
        )

        fun getEmotionColor(emotion: String): Int {
            return EMOTION_COLORS[emotion.lowercase()] ?: EMOTION_COLORS["neutral"]!!
        }

        fun getEmotionLightColor(emotion: String): Int {
            return EMOTION_LIGHT_COLORS[emotion.lowercase()] ?: EMOTION_LIGHT_COLORS["neutral"]!!
        }
    }
}
