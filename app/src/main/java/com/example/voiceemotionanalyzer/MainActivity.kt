package com.example.voiceemotionanalyzer

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.voiceemotionanalyzer.databinding.ActivityMainBinding
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.analytics.FirebaseAnalytics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var binding: ActivityMainBinding
    private val adapter = EmotionAdapter()
    private val emotionPoints = mutableListOf<EmotionPoint>()
    private val handler = Handler(Looper.getMainLooper())
    private val bufferText = StringBuilder()
    private var lastConfidence = 1f
    private var isRecording = false
    private var sessionStartMs = 0L
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private var analytics: FirebaseAnalytics? = null
    private lateinit var analyzer: EmotionAnalyzer

    // Accumulated emotion counts for pie chart
    private val emotionCounts = mutableMapOf(
        "joy" to 0,
        "sad" to 0,
        "anger" to 0,
        "fear" to 0,
        "surprise" to 0,
        "neutral" to 0
    )

    private val processRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                processBuffer()
                handler.postDelayed(this, 4000)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            toggleRecording()
        } else {
            Snackbar.make(binding.rootLayout, R.string.permission_denied, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)

        // Initialize Firebase Analytics (optional)
        try {
            analytics = FirebaseAnalytics.getInstance(this)
        } catch (e: Exception) {
            // Firebase not configured, continue without it
        }

        // Initialize emotion analyzer (no Firebase dependency)
        analyzer = EmotionAnalyzer()

        // Setup RecyclerView
        binding.emotionRecycler.layoutManager = LinearLayoutManager(this)
        binding.emotionRecycler.adapter = adapter

        // Setup charts
        setupPieChart()
        setupLineChart()

        // Setup status card
        updateStatusCard(isOnline = true, isRecording = false)

        // Setup FAB
        binding.recordFab.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Setup speech recognizer
        setupSpeechRecognizer()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share -> {
                exportAndShare()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.no_mic, Toast.LENGTH_LONG).show()
            binding.recordFab.isEnabled = false
            updateStatusCard(isOnline = false, isRecording = false)
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (!::speechRecognizer.isInitialized) return
        isRecording = true
        sessionStartMs = System.currentTimeMillis()

        // Update FAB to show stop state
        binding.recordFab.text = getString(R.string.stop_recording)
        binding.recordFab.setIconResource(R.drawable.ic_stop)

        // Update status card
        updateStatusCard(isOnline = true, isRecording = true)

        analytics?.logEvent("recording_session_start", null)
        speechRecognizer.startListening(recognizerIntent)
        handler.postDelayed(processRunnable, 4000)
    }

    private fun stopRecording() {
        isRecording = false

        // Update FAB to show start state
        binding.recordFab.text = getString(R.string.start_recording)
        binding.recordFab.setIconResource(R.drawable.ic_mic)

        // Update status card
        updateStatusCard(isOnline = true, isRecording = false)

        handler.removeCallbacks(processRunnable)
        bufferText.clear()
        speechRecognizer.stopListening()
    }

    private fun updateStatusCard(isOnline: Boolean, isRecording: Boolean) {
        val statusIndicatorColor: Int
        val statusTextStr: String
        val modeTextStr: String
        val modeTextColor: Int

        when {
            isRecording -> {
                statusIndicatorColor = ContextCompat.getColor(this, R.color.emotion_anger)
                statusTextStr = getString(R.string.status_recording)
                modeTextStr = getString(R.string.mode_online)
                modeTextColor = ContextCompat.getColor(this, R.color.status_online)
            }
            isOnline -> {
                statusIndicatorColor = ContextCompat.getColor(this, R.color.emotion_joy)
                statusTextStr = getString(R.string.status_ready)
                modeTextStr = getString(R.string.mode_online)
                modeTextColor = ContextCompat.getColor(this, R.color.status_online)
            }
            else -> {
                statusIndicatorColor = ContextCompat.getColor(this, R.color.emotion_neutral)
                statusTextStr = getString(R.string.status_ready)
                modeTextStr = getString(R.string.mode_offline)
                modeTextColor = ContextCompat.getColor(this, R.color.text_secondary)
            }
        }

        binding.statusIndicator.backgroundTintList = ColorStateList.valueOf(statusIndicatorColor)
        binding.statusText.text = statusTextStr
        binding.modeText.text = modeTextStr
        binding.modeText.setTextColor(modeTextColor)
    }

    private fun processBuffer() {
        val text = bufferText.toString().trim()
        if (text.isBlank()) return
        val confidenceThreshold = 0.3f
        if (lastConfidence < confidenceThreshold) {
            Snackbar.make(binding.rootLayout, R.string.low_confidence, Snackbar.LENGTH_SHORT).show()
            bufferText.clear()
            return
        }
        val point = analyzer.analyze(text, System.currentTimeMillis())
        bufferText.clear()
        addEmotionPoint(point)
        analytics?.logEvent("chunk_analyzed", null)
    }

    private fun addEmotionPoint(point: EmotionPoint) {
        emotionPoints.add(0, point)
        adapter.submitList(emotionPoints.toList())

        // Update emotion counts for pie chart
        val emotion = point.emotion.lowercase()
        emotionCounts[emotion] = (emotionCounts[emotion] ?: 0) + 1

        // Update all UI components
        updatePieChart()
        updateLineChart()
        updateCurrentEmotionCard(point)
    }

    private fun setupPieChart() {
        binding.emotionPieChart.apply {
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 45f
            transparentCircleRadius = 50f
            setHoleColor(Color.TRANSPARENT)
            setTransparentCircleColor(Color.WHITE)
            setTransparentCircleAlpha(110)
            setDrawEntryLabels(false)
            legend.isEnabled = false
            setNoDataText(getString(R.string.no_data))
            setNoDataTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            animateY(800)
        }
    }

    private fun updatePieChart() {
        val entries = mutableListOf<PieEntry>()
        val colors = mutableListOf<Int>()

        // Only add emotions with counts > 0
        emotionCounts.forEach { (emotion, count) ->
            if (count > 0) {
                entries.add(PieEntry(count.toFloat(), emotion.replaceFirstChar { it.uppercase() }))
                colors.add(getEmotionColor(emotion))
            }
        }

        if (entries.isEmpty()) {
            binding.emotionPieChart.data = null
            binding.emotionPieChart.invalidate()
            return
        }

        val dataSet = PieDataSet(entries, "Emotions").apply {
            setColors(colors)
            sliceSpace = 2f
            valueTextSize = 12f
            valueTextColor = Color.WHITE
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return "${value.toInt()}"
                }
            }
        }

        binding.emotionPieChart.data = PieData(dataSet)
        binding.emotionPieChart.invalidate()
    }

    private fun setupLineChart() {
        binding.emotionChart.apply {
            description.isEnabled = false
            axisRight.isEnabled = false
            axisLeft.axisMinimum = -1.2f
            axisLeft.axisMaximum = 1.2f
            axisLeft.textColor = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
            axisLeft.gridColor = ContextCompat.getColor(this@MainActivity, R.color.card_stroke)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                textColor = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
                gridColor = ContextCompat.getColor(this@MainActivity, R.color.card_stroke)
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}s"
                    }
                }
            }
            legend.isEnabled = false
            setNoDataText(getString(R.string.no_data))
            setNoDataTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            animateX(500)
        }
    }

    private fun updateLineChart() {
        val entries = mutableListOf<Entry>()
        val colors = mutableListOf<Int>()

        emotionPoints.asReversed().forEach { point ->
            val x = ((point.timeMs - sessionStartMs) / 1000f).coerceAtLeast(0f)
            entries.add(Entry(x, point.score))
            colors.add(getEmotionColor(point.emotion))
        }

        if (entries.isEmpty()) {
            binding.emotionChart.data = null
            binding.emotionChart.invalidate()
            return
        }

        val dataSet = LineDataSet(entries, "Emotion").apply {
            lineWidth = 2f
            setDrawValues(false)
            setDrawCircles(true)
            circleRadius = 5f
            circleHoleRadius = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            color = ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_primary)
            setCircleColors(colors)
            setDrawFilled(true)
            fillColor = ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_primary)
            fillAlpha = 30
        }

        binding.emotionChart.data = LineData(dataSet)
        binding.emotionChart.invalidate()
    }

    private fun updateCurrentEmotionCard(point: EmotionPoint) {
        val emotion = point.emotion.lowercase()
        val emotionColor = getEmotionColor(emotion)
        val emotionLightColor = getEmotionLightColor(emotion)

        // Update card background
        binding.currentEmotionCard.setCardBackgroundColor(emotionLightColor)

        // Update emotion text
        binding.currentEmotionText.text = emotion.replaceFirstChar { it.uppercase() }
        binding.currentEmotionText.setTextColor(emotionColor)

        // Update confidence text
        val confidence = point.confidence ?: 0.5f
        val confidencePercent = (confidence * 100).toInt()
        binding.confidenceText.text = getString(R.string.confidence_label) + " $confidencePercent%"
    }

    private fun getEmotionColor(emotion: String): Int {
        return when (emotion.lowercase()) {
            "joy" -> ContextCompat.getColor(this, R.color.emotion_joy)
            "sad" -> ContextCompat.getColor(this, R.color.emotion_sad)
            "anger" -> ContextCompat.getColor(this, R.color.emotion_anger)
            "fear" -> ContextCompat.getColor(this, R.color.emotion_fear)
            "surprise" -> ContextCompat.getColor(this, R.color.emotion_surprise)
            "neutral" -> ContextCompat.getColor(this, R.color.emotion_neutral)
            else -> ContextCompat.getColor(this, R.color.emotion_neutral)
        }
    }

    private fun getEmotionLightColor(emotion: String): Int {
        return when (emotion.lowercase()) {
            "joy" -> ContextCompat.getColor(this, R.color.emotion_joy_light)
            "sad" -> ContextCompat.getColor(this, R.color.emotion_sad_light)
            "anger" -> ContextCompat.getColor(this, R.color.emotion_anger_light)
            "fear" -> ContextCompat.getColor(this, R.color.emotion_fear_light)
            "surprise" -> ContextCompat.getColor(this, R.color.emotion_surprise_light)
            "neutral" -> ContextCompat.getColor(this, R.color.emotion_neutral_light)
            else -> ContextCompat.getColor(this, R.color.emotion_neutral_light)
        }
    }

    private fun exportAndShare() {
        try {
            // Export line chart
            val chartBitmap = binding.emotionChart.chartBitmap
            val chartFile = File(cacheDir, "emotion_chart_${System.currentTimeMillis()}.png")
            FileOutputStream(chartFile).use { stream ->
                chartBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }

            // Export pie chart
            val pieBitmap = binding.emotionPieChart.chartBitmap
            val pieFile = File(cacheDir, "emotion_pie_${System.currentTimeMillis()}.png")
            FileOutputStream(pieFile).use { stream ->
                pieBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }

            // Export JSON data
            val jsonArray = JSONArray()
            emotionPoints.asReversed().forEach { point ->
                val obj = JSONObject()
                obj.put("timeMs", point.timeMs)
                obj.put("emotion", point.emotion)
                obj.put("score", point.score)
                obj.put("text", point.text)
                obj.put("confidence", point.confidence ?: 0.5f)
                jsonArray.put(obj)
            }
            val jsonFile = File(cacheDir, "emotion_data_${System.currentTimeMillis()}.json")
            jsonFile.writeText(jsonArray.toString())

            val chartUri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                chartFile
            )
            val pieUri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                pieFile
            )
            val jsonUri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                jsonFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    arrayListOf(chartUri, pieUri, jsonUri)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_export)))
        } catch (e: Exception) {
            Snackbar.make(binding.rootLayout, R.string.export_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    // RecognitionListener implementations
    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        if (isRecording) {
            handler.postDelayed({ speechRecognizer.startListening(recognizerIntent) }, 600)
        }
    }

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (text.isNotBlank()) {
            bufferText.clear()
            bufferText.append(text)
            updateConfidence(results)
            processBuffer()
        }
        if (isRecording) {
            speechRecognizer.startListening(recognizerIntent)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (text.isNotBlank()) {
            bufferText.clear()
            bufferText.append(text)
            updateConfidence(partialResults)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun updateConfidence(results: Bundle?) {
        val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        if (scores != null && scores.isNotEmpty()) {
            lastConfidence = scores.average().toFloat()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(processRunnable)
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        super.onDestroy()
    }
}
