package com.example.voiceemotionanalyzer.api

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class HumeEmotionAnalyzer(
    private val context: Context,
    private val apiKey: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.hume.ai/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(HumeApiService::class.java)

    suspend fun analyzeAudio(audioFile: File): Result<Map<String, Float>> = withContext(Dispatchers.IO) {
        try {
            // Create multipart request
            val requestFile = audioFile.asRequestBody("audio/wav".toMediaType())
            val body = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)
            
            // Request prosody model analysis
            val modelsJson = """{"prosody": {}}""".toRequestBody("application/json".toMediaType())

            // Submit job
            val jobResponse = api.submitJob(apiKey, body, modelsJson)
            if (!jobResponse.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to submit job: ${jobResponse.code()}"))
            }

            val jobId = jobResponse.body()?.job_id ?: return@withContext Result.failure(Exception("No job ID returned"))

            // Poll for completion
            var status: String
            var attempts = 0
            do {
                delay(1000)
                val statusResponse = api.getJobStatus(apiKey, jobId)
                status = statusResponse.body()?.state?.status ?: "UNKNOWN"
                attempts++
            } while (status == "QUEUED" || status == "IN_PROGRESS" && attempts < 30)

            if (status != "COMPLETED") {
                return@withContext Result.failure(Exception("Job failed or timed out: $status"))
            }

            // Get predictions
            val predictions = api.getJobPredictions(apiKey, jobId)
            if (!predictions.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to get predictions"))
            }

            // Parse emotions
            val emotions = mutableMapOf<String, Float>()
            predictions.body()?.forEach { pred ->
                pred.results.predictions?.forEach { prediction ->
                    prediction.models.prosody?.grouped_predictions?.forEach { group ->
                        group.predictions.forEach { emotionPred ->
                            emotionPred.emotions.forEach { emotion ->
                                val name = mapEmotionName(emotion.name)
                                emotions[name] = maxOf(emotions[name] ?: 0f, emotion.score)
                            }
                        }
                    }
                }
            }

            Result.success(emotions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mapEmotionName(humeName: String): String {
        return when (humeName.lowercase()) {
            "joy", "amusement", "excitement", "satisfaction", "contentment" -> "joy"
            "sadness", "disappointment", "distress", "grief" -> "sad"
            "anger", "annoyance", "contempt", "disgust" -> "anger"
            "fear", "anxiety", "horror", "nervousness" -> "fear"
            "surprise", "realization", "confusion" -> "surprise"
            else -> "neutral"
        }
    }
}
