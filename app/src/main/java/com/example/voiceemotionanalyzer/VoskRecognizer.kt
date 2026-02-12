package com.example.voiceemotionanalyzer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class VoskRecognizer(private val context: Context) {
    
    private var model: Model? = null
    private var speechService: SpeechService? = null
    var isInitialized = false
        private set
    
    interface VoskListener {
        fun onResult(text: String)
        fun onPartialResult(text: String)
        fun onError(error: String)
    }
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelName = "vosk-model-small-en-us-0.15"
            val modelDir = File(context.filesDir, modelName)
            
            // If model is not already unpacked to internal storage, copy from assets
            if (!modelDir.exists() || !File(modelDir, "am").exists()) {
                Log.d("VoskRecognizer", "Unpacking Vosk model from assets...")
                try {
                    unpackModelFromAssets(modelName, modelDir)
                } catch (e: Exception) {
                    Log.e("VoskRecognizer", "Failed to unpack model from assets", e)
                    return@withContext false
                }
            }
            
            model = Model(modelDir.absolutePath)
            isInitialized = true
            Log.d("VoskRecognizer", "Vosk initialized successfully")
            true
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "Failed to initialize Vosk", e)
            false
        }
    }
    
    /**
     * Recursively copies the Vosk model from the assets folder to internal storage.
     */
    private fun unpackModelFromAssets(assetPath: String, targetDir: File) {
        val assetManager = context.assets
        
        // Try to list contents - if it has children, it's a directory
        val children = assetManager.list(assetPath)
        if (children != null && children.isNotEmpty()) {
            // It's a directory - create it and recurse
            targetDir.mkdirs()
            for (child in children) {
                unpackModelFromAssets("$assetPath/$child", File(targetDir, child))
            }
        } else {
            // It's a file - copy it
            targetDir.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                FileOutputStream(targetDir).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
    
    fun startListening(listener: VoskListener) {
        if (!isInitialized) {
            listener.onError("Vosk not initialized")
            return
        }
        
        try {
            val rec = Recognizer(model, 16000f)
            speechService = SpeechService(rec, 16000f)
            
            speechService?.startListening(object : RecognitionListener {
                override fun onResult(hypothesis: String?) {
                    val text = parseVoskResult(hypothesis)
                    if (text.isNotBlank()) {
                        listener.onResult(text)
                    }
                }
                
                override fun onFinalResult(hypothesis: String?) {
                    val text = parseVoskResult(hypothesis)
                    if (text.isNotBlank()) {
                        listener.onResult(text)
                    }
                }
                
                override fun onPartialResult(hypothesis: String?) {
                    val text = parseVoskResult(hypothesis)
                    if (text.isNotBlank()) {
                        listener.onPartialResult(text)
                    }
                }
                
                override fun onError(exception: Exception?) {
                    listener.onError(exception?.message ?: "Unknown error")
                }
                
                override fun onTimeout() {
                    // Restart listening
                    speechService?.stop()
                    startListening(listener)
                }
            })
        } catch (e: Exception) {
            listener.onError("Failed to start: ${e.message}")
        }
    }
    
    fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }
    
    fun destroy() {
        stopListening()
        model?.close()
        model = null
        isInitialized = false
    }
    
    private fun parseVoskResult(hypothesis: String?): String {
        if (hypothesis.isNullOrBlank()) return ""
        
        try {
            val json = org.json.JSONObject(hypothesis)
            return json.optString("text", "")
        } catch (e: Exception) {
            return ""
        }
    }
}
