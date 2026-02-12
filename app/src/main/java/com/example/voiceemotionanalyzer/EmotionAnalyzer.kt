package com.example.voiceemotionanalyzer

/**
 * Enhanced offline emotion analyzer using NLP techniques:
 * - Keyword-based scoring with exact & substring matching
 * - Bigram / phrase patterns (e.g. "stressed out", "fed up")
 * - Negation handling ("not happy" → flips joy to sad)
 * - Intensity modifiers ("very", "extremely" → amplify scores)
 * - Punctuation-based hints
 * 
 * Used as primary offline engine and fallback when Hume AI is unavailable.
 */
class EmotionAnalyzer {
    
    companion object {
        // ---- Unigram keywords ----
        private val EMOTION_KEYWORDS = mapOf(
            "joy" to listOf(
                "happy", "joy", "great", "good", "love", "excellent", "amazing",
                "excited", "wonderful", "fantastic", "awesome", "blessed", "grateful",
                "thrilled", "delighted", "cheerful", "pleased", "glad", "content",
                "laugh", "smile", "celebrate", "proud", "ecstatic", "elated",
                "nice", "cool", "perfect", "beautiful", "lovely", "yeah", "yay",
                "fun", "enjoy", "fine", "okay", "thanks", "brilliant", "sweet",
                "like", "best", "better", "positive", "kind", "warm"
            ),
            "sad" to listOf(
                "sad", "unhappy", "depressed", "down", "miserable", "heartbroken",
                "disappointed", "upset", "lonely", "hopeless", "grief", "sorrow",
                "melancholy", "gloomy", "dejected", "hurt", "crying", "tears",
                "regret", "mourn", "despair", "broken", "miss", "lost",
                "alone", "sorry", "bad", "worse", "worst", "painful", "empty",
                "tired", "exhausted", "sick", "bored", "dull"
            ),
            "anger" to listOf(
                "angry", "mad", "furious", "annoyed", "frustrated", "irritated",
                "hate", "terrible", "awful", "outraged", "livid", "enraged",
                "hostile", "bitter", "resentful", "pissed", "rage", "disgusted",
                "infuriated", "indignant", "stupid", "dumb", "idiot", "ridiculous",
                "wrong", "unfair", "garbage", "trash", "ugly", "rubbish"
            ),
            "fear" to listOf(
                "scared", "afraid", "fearful", "terrified", "anxious", "worried",
                "nervous", "panic", "frightened", "horrified", "dread", "uneasy",
                "stressed", "paranoid", "threatened", "phobia", "alarmed", "tense",
                "insecure", "overwhelmed"
            ),
            "surprise" to listOf(
                "surprised", "shocked", "amazed", "astonished", "stunned", "wow",
                "unexpected", "unbelievable", "incredible", "speechless", "startled",
                "whoa", "omg", "really", "seriously", "gasped"
            )
        )

        // ---- Bigram / phrase patterns (higher signal) ----
        private val EMOTION_PHRASES = mapOf(
            "joy" to listOf(
                "so happy", "feel great", "really good", "over the moon", "on top of the world",
                "best day", "love it", "made my day", "can't stop smiling", "feel good",
                "pretty good", "doing well", "feeling good", "that's great", "sounds good",
                "thank you", "thanks", "nice one", "well done", "good job"
            ),
            "sad" to listOf(
                "so sad", "feel down", "really bad", "broke my heart", "miss you",
                "can't believe", "feel empty", "lost hope", "hurts so much", "not good",
                "feel bad", "don't like", "too bad", "that sucks", "feel terrible",
                "not well", "not okay", "no good"
            ),
            "anger" to listOf(
                "so angry", "fed up", "pissed off", "sick of", "drives me crazy",
                "can't stand", "had enough", "ticked off", "makes me mad",
                "shut up", "go away", "leave me alone", "what the hell",
                "are you kidding", "you're wrong"
            ),
            "fear" to listOf(
                "stressed out", "freaking out", "scared of", "worried about",
                "can't sleep", "losing my mind", "falling apart", "so nervous",
                "oh no", "what if", "i hope not", "be careful"
            ),
            "surprise" to listOf(
                "no way", "can't believe", "out of nowhere", "blew my mind",
                "didn't expect", "caught off guard", "what the", "oh my god",
                "are you serious", "wait what", "you're kidding"
            )
        )

        // Words that negate the following emotion word
        private val NEGATION_WORDS = setOf(
            "not", "no", "never", "don't", "doesn't", "didn't", "won't",
            "wouldn't", "can't", "cannot", "isn't", "aren't", "wasn't",
            "weren't", "hardly", "barely", "neither", "nor"
        )

        // Intensity modifiers — amplify the next keyword's weight
        private val INTENSITY_MODIFIERS = mapOf(
            "very" to 1.5f,
            "really" to 1.4f,
            "extremely" to 1.8f,
            "incredibly" to 1.7f,
            "so" to 1.3f,
            "absolutely" to 1.6f,
            "totally" to 1.4f,
            "quite" to 1.2f,
            "super" to 1.5f
        )

        // Map from negated source → target emotion
        // e.g. negated joy → sad, negated sad → joy
        private val NEGATION_FLIP = mapOf(
            "joy" to "sad",
            "sad" to "joy",
            "anger" to "joy",
            "fear" to "joy",
            "surprise" to "neutral"
        )
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
        val words = lower.split(Regex("\\W+")).filter { it.isNotBlank() }
        val scores = mutableMapOf<String, Float>()

        // Short text gets a weight boost since each word carries more signal
        val shortTextMultiplier = if (words.size <= 3) 2.0f else if (words.size <= 6) 1.4f else 1.0f
        
        // ---------- 1. Phrase matching (highest confidence) ----------
        EMOTION_PHRASES.forEach { (emotion, phrases) ->
            phrases.forEach { phrase ->
                if (lower.contains(phrase)) {
                    scores[emotion] = (scores[emotion] ?: 0f) + 0.6f * shortTextMultiplier
                }
            }
        }

        // ---------- 2. Keyword matching with negation & intensity ----------
        EMOTION_KEYWORDS.forEach { (emotion, keywords) ->
            keywords.forEach { keyword ->
                for (i in words.indices) {
                    val matched = words[i] == keyword ||
                        (keyword.length > 4 && words[i].startsWith(keyword.dropLast(1))) ||
                        (keyword.length > 4 && words[i].length > 4 && keyword.startsWith(words[i].dropLast(1)))
                    
                    if (matched) {
                        var weight = if (words[i] == keyword) 0.5f else 0.2f
                        weight *= shortTextMultiplier
                        
                        // Check for negation in previous 1–3 words
                        val isNegated = (maxOf(0, i - 3) until i).any { j ->
                            words[j] in NEGATION_WORDS
                        }
                        
                        // Check for intensity modifier in previous 1–2 words
                        val intensityMul = (maxOf(0, i - 2) until i).mapNotNull { j ->
                            INTENSITY_MODIFIERS[words[j]]
                        }.maxOrNull() ?: 1f
                        
                        weight *= intensityMul
                        
                        if (isNegated) {
                            // Flip the emotion
                            val flipped = NEGATION_FLIP[emotion] ?: "neutral"
                            scores[flipped] = (scores[flipped] ?: 0f) + weight
                        } else {
                            scores[emotion] = (scores[emotion] ?: 0f) + weight
                        }
                    }
                }
            }
        }
        
        // ---------- 3. Punctuation hints ----------
        val exclamationCount = text.count { it == '!' }
        if (exclamationCount > 0) {
            val boost = (exclamationCount * 0.1f).coerceAtMost(0.3f)
            scores["joy"] = (scores["joy"] ?: 0f) + boost
            scores["surprise"] = (scores["surprise"] ?: 0f) + boost * 0.5f
            scores["anger"] = (scores["anger"] ?: 0f) + boost * 0.5f
        }
        if (text.contains("?")) {
            scores["surprise"] = (scores["surprise"] ?: 0f) + 0.1f
        }
        // ALL CAPS words suggest strong emotion
        val capsWords = words.count { it.length > 2 && it == it.uppercase() && it != it.lowercase() }
        if (capsWords > 0) {
            val boost = (capsWords * 0.15f).coerceAtMost(0.3f)
            scores["anger"] = (scores["anger"] ?: 0f) + boost
            scores["surprise"] = (scores["surprise"] ?: 0f) + boost * 0.5f
        }
        
        // ---------- 4. Default to neutral ----------
        if (scores.isEmpty()) {
            scores["neutral"] = 0.3f
        }
        
        // Normalize to [0, 1]
        val maxScore = scores.values.maxOrNull() ?: 0.3f
        if (maxScore > 1f) {
            scores.replaceAll { _, v -> (v / maxScore).coerceIn(0f, 1f) }
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
        if (scores.size <= 1) return scores.values.firstOrNull() ?: 0.1f
        val sorted = scores.values.sortedDescending()
        val top = sorted[0]
        val second = sorted.getOrElse(1) { 0f }
        // Higher gap between top two → higher confidence
        val gap = (top - second).coerceIn(0f, 1f)
        return ((top * 0.6f) + (gap * 0.4f)).coerceIn(0.1f, 1f)
    }
}
