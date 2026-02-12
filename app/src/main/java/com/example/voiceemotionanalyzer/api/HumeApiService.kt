package com.example.voiceemotionanalyzer.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface HumeApiService {

    @Multipart
    @POST("v0/batch/jobs")
    suspend fun submitJob(
        @Header("X-Hume-Api-Key") apiKey: String,
        @Part file: MultipartBody.Part,
        @Part("models") models: RequestBody
    ): Response<JobResponse>

    @GET("v0/batch/jobs/{job_id}")
    suspend fun getJobStatus(
        @Header("X-Hume-Api-Key") apiKey: String,
        @Path("job_id") jobId: String
    ): Response<JobStatusResponse>

    @GET("v0/batch/jobs/{job_id}/predictions")
    suspend fun getJobPredictions(
        @Header("X-Hume-Api-Key") apiKey: String,
        @Path("job_id") jobId: String
    ): Response<List<PredictionResponse>>
}

data class JobResponse(
    val job_id: String
)

data class JobStatusResponse(
    val job_id: String,
    val state: JobState
)

data class JobState(
    val status: String // "QUEUED", "IN_PROGRESS", "COMPLETED", "FAILED"
)

data class PredictionResponse(
    val source: Source,
    val results: Results
)

data class Source(
    val type: String,
    val filename: String? = null
)

data class Results(
    val predictions: List<Prediction>? = null,
    val errors: List<String>? = null
)

data class Prediction(
    val models: Models
)

data class Models(
    val prosody: Prosody? = null,
    val language: Language? = null
)

data class Prosody(
    val grouped_predictions: List<GroupedPrediction>
)

data class Language(
    val grouped_predictions: List<GroupedPrediction>
)

data class GroupedPrediction(
    val id: String,
    val predictions: List<EmotionPrediction>
)

data class EmotionPrediction(
    val text: String? = null,
    val time: TimeRange? = null,
    val emotions: List<EmotionScore>
)

data class TimeRange(
    val begin: Float,
    val end: Float
)

data class EmotionScore(
    val name: String,
    val score: Float
)
