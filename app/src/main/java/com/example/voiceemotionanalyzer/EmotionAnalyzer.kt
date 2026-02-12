package com.example.voiceemotionanalyzer

/**
 * Offline emotion analyzer using keyword-based sentiment analysis.
 * Used as fallback when Hume AI is unavailable.
 */
class EmotionAnalyzer {
    
    companion object {
        private val EMOTION_KEYWORDS = mapOf(
            "joy" to listOf(
                "happy", "joy", "great", "good", "love", "excellent", "amazing", 
                "excited", "wonderful", "fantastic", "awesome", "blessed", "grateful",
                "thrilled", "delighted", "cheerful", "pleased", "glad", "content"
            ),
            "sad" to listOf(
                "sad", "unhappy", "depressed", "down", "miserable", "heartbroken",
                "disappointed", "upset", "lonely", "hopeless", "grief", "sorrow",
                "melancholy", "gloomy", "dejected", "hurt"
            ),
            "anger" to listOf(
                "angry", "mad", "furious", "annoyed", "frustrated", "irritated",
                "hate", "terrible", "awful", "outraged", "livid", "enraged",
                "hostile", "bitter", "resentful", "pissed"
            ),
            "fear" to listOf(
                "scared", "afraid", "fearful", "terrified", "anxious", "worried",
                "nervous", "panic", "frightened", "horrified", "dread", "uneasy",
                "stressed", "paranoid", "threatened"
            ),
            "surprise" to listOf(
                "surprised", "shocked", "amazed", "astonished", "stunned", "wow",
                "unexpected", "unbelievable", "incredible", "speechless", "startled"
            )
        )

        private const val DEFAULT_POSITIVE_THRESHOLD = 0.3f
        private const val DEFAULT_NEGATIVE_THRESHOLD = -0.3f
    }

    fun analyze(text: String, timeMs: Long): EmotionPoint {
        val emotionScores = computeEmotionScores(text)
        val dominantEmotion = getDominantEmotion(emotionScores)
        val score = computeOverallScore(emotionScores)
        val confidence = computeConfidence(emotionScores)
        
        return EmotionPoint(
            timeMs = timeMs,
            emotion = dominantEmotion,
            score = score,
            text = text,
            confidence = confidence,
            emotions = emotionScores
        )
    }

    fun analyzeWithExternalEmotions(
        text: String,
        timeMs: Long,
        emotions: Map<String, Float>
    ): EmotionPoint {
        val dominantEmotion = emotions.maxByOrNull { it.value }?.key ?: "neutral"
        val score = computeOverallScoreFromEmotions(emotions)
        val confidence = emotions.values.maxOrNull() ?: 0.5f
        
        return EmotionPoint(
            timeMs = timeMs,
            emotion = dominantEmotion,
            score = score,
            text = text,
            confidence = confidence,
            emotions = emotions
        )
    }

    private fun computeEmotionScores(text: String): Map<String, Float> {
        val lower = text.lowercase()
        val words = lower.split(Regex("\\W+"))
        val scores = mutableMapOf<String, Float>()
        
        EMOTION_KEYWORDS.forEach { (emotion, keywords) ->
            val matchCount = keywords.count { keyword -> 
                words.contains(keyword) || lower.contains(keyword)
            }
            if (matchCount > 0) {
                scores[emotion] = (matchCount.toFloat() / keywords.size * 2).coerceAtMost(1f)
            }
        }
        
        // If no emotions detected, set neutral
        if (scores.isEmpty()) {
            scores["neutral"] = 0.5f
        }
        
        return scores
    }

    private fun getDominantEmotion(scores: Map<String, Float>): String {
        return scores.maxByOrNull { it.value }?.key ?: "neutral"
    }

    private fun computeOverallScore(emotionScores: Map<String, Float>): Float {
        val positiveScore = emotionScores["joy"] ?: 0f
        val negativeScore = maxOf(
            emotionScores["sad"] ?: 0f,
            emotionScores["anger"] ?: 0f,
            emotionScores["fear"] ?: 0f
        )
        return (positiveScore - negativeScore).coerceIn(-1f, 1f)
    }

    private fun computeOverallScoreFromEmotions(emotions: Map<String, Float>): Float {
        val positive = emotions["joy"] ?: 0f
        val negative = maxOf(
            emotions["sad"] ?: 0f,
            emotions["anger"] ?: 0f,
            emotions["fear"] ?: 0f
        )
        return (positive - negative).coerceIn(-1f, 1f)
    }

    private fun computeConfidence(scores: Map<String, Float>): Float {
        val maxScore = scores.values.maxOrNull() ?: 0f
        val avgScore = if (scores.isNotEmpty()) scores.values.average().toFloat() else 0f
        return ((maxScore + avgScore) / 2).coerceIn(0.1f, 1f)
    }
}
