package com.example.voiceemotionanalyzer

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.voiceemotionanalyzer.databinding.ActivityMainBinding
import com.github.mikephil.charting.components.XAxis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
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
    private lateinit var analyzer: EmotionAnalyzer
    private var voskRecognizer: VoskRecognizer? = null
    private var isOnline = false
    private var useVosk = false
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    // Accumulated emotion counts for pie chart
    private val emotionCounts = mutableMapOf(
        "joy" to 0,
        "sad" to 0,
        "anger" to 0,
        "fear" to 0,
        "surprise" to 0,
        "neutral" to 0
    )

    // Session duration timer
    private val durationRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                updateSessionDuration()
                handler.postDelayed(this, 1000)
            }
        }
    }

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

        // Initialize emotion analyzer
        analyzer = EmotionAnalyzer()
        
        // Initialize TextToSpeech for demo mode
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        
        // Initialize Vosk for offline mode
        voskRecognizer = VoskRecognizer(this)
        coroutineScope.launch(Dispatchers.IO) {
            val voskReady = voskRecognizer?.initialize() ?: false
            launch(Dispatchers.Main) {
                if (voskReady) {
                    Toast.makeText(this@MainActivity, "Offline mode ready", Toast.LENGTH_SHORT).show()
                }
                // Re-evaluate speech recognizer setup now that Vosk may be ready
                setupSpeechRecognizer()
            }
        }

        // Setup RecyclerView
        binding.emotionRecycler.layoutManager = LinearLayoutManager(this)
        binding.emotionRecycler.adapter = adapter

        // Setup charts
        setupPieChart()
        setupLineChart()

        // Setup status card
        isOnline = NetworkUtil.isNetworkAvailable(this)
        updateStatusCard(isOnline = isOnline, isRecording = false)

        // Setup FAB
        binding.recordFab.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Setup speech recognizer
        setupSpeechRecognizer()

        // Register real-time network listener
        networkCallback = NetworkUtil.registerNetworkCallback(
            this,
            onAvailable = {
                runOnUiThread {
                    if (!isOnline) {
                        isOnline = true
                        updateStatusCard(isOnline = true, isRecording = isRecording)
                        Snackbar.make(binding.rootLayout, R.string.network_online, Snackbar.LENGTH_SHORT)
                            .setBackgroundTint(ContextCompat.getColor(this, R.color.status_online))
                            .setTextColor(Color.WHITE)
                            .show()
                        if (!isRecording) setupSpeechRecognizer()
                    }
                }
            },
            onLost = {
                runOnUiThread {
                    if (isOnline) {
                        isOnline = false
                        updateStatusCard(isOnline = false, isRecording = isRecording)
                        Snackbar.make(binding.rootLayout, R.string.network_offline, Snackbar.LENGTH_SHORT)
                            .setBackgroundTint(ContextCompat.getColor(this, R.color.status_offline))
                            .setTextColor(Color.WHITE)
                            .show()
                        if (!isRecording) setupSpeechRecognizer()
                    }
                }
            }
        )
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
            R.id.action_demo_mode -> {
                showDemoModeDialog()
                true
            }
            R.id.action_export_csv -> {
                exportCsv()
                true
            }
            R.id.action_reset -> {
                confirmResetSession()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupSpeechRecognizer() {
        isOnline = NetworkUtil.isNetworkAvailable(this)
        
        // Determine which recognizer to use
        useVosk = !isOnline && voskRecognizer?.isInitialized == true
        
        if (useVosk) {
            // Use Vosk for offline recognition
            Toast.makeText(this, "Using offline mode", Toast.LENGTH_SHORT).show()
        } else {
            try {
                // Try to create SpeechRecognizer - works on Bluestacks if Google app installed
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer.setRecognitionListener(this)
                recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    
                    // Bluestacks-specific settings for better compatibility
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                }
            } catch (e: Exception) {
                // If Google services not available, suggest installing Google app
                Toast.makeText(
                    this,
                    "Speech recognition unavailable. Install Google app from Play Store or use Demo Mode.",
                    Toast.LENGTH_LONG
                ).show()
                // Don't disable FAB - let user try Demo Mode instead
            }
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
        isRecording = true
        sessionStartMs = System.currentTimeMillis()

        // Update FAB to show stop state
        binding.recordFab.text = getString(R.string.stop_recording)
        binding.recordFab.setIconResource(R.drawable.ic_stop)

        // Update status card
        updateStatusCard(isOnline = isOnline, isRecording = true)

        // Show amplitude bar
        binding.amplitudeContainer.visibility = View.VISIBLE
        binding.amplitudeBar.progress = 0
        
        if (useVosk && voskRecognizer != null) {
            // Start Vosk offline recognition
            voskRecognizer?.startListening(object : VoskRecognizer.VoskListener {
                override fun onResult(text: String) {
                    runOnUiThread {
                        if (text.isNotBlank()) {
                            // Accumulate final results for richer analysis
                            if (bufferText.isNotEmpty()) bufferText.append(" ")
                            bufferText.append(text)
                            processBuffer()
                        }
                    }
                }
                
                override fun onPartialResult(text: String) {
                    // Partial results are transient — don't clear buffer
                }
                
                override fun onError(error: String) {
                    runOnUiThread {
                        Snackbar.make(binding.rootLayout, "Offline recognition error: $error", Snackbar.LENGTH_SHORT).show()
                    }
                }
            })
        } else if (::speechRecognizer.isInitialized) {
            // Start online recognition
            speechRecognizer.startListening(recognizerIntent)
        }
        
        handler.postDelayed(processRunnable, 4000)
        handler.postDelayed(durationRunnable, 1000)
    }

    private fun stopRecording() {
        isRecording = false

        // Update FAB to show start state
        binding.recordFab.text = getString(R.string.start_recording)
        binding.recordFab.setIconResource(R.drawable.ic_mic)

        // Update status card
        updateStatusCard(isOnline = isOnline, isRecording = false)

        // Hide amplitude bar
        binding.amplitudeContainer.visibility = View.GONE

        handler.removeCallbacks(processRunnable)
        handler.removeCallbacks(durationRunnable)
        bufferText.clear()
        
        if (useVosk) {
            voskRecognizer?.stopListening()
        } else if (::speechRecognizer.isInitialized) {
            speechRecognizer.stopListening()
        }
    }

    private fun updateStatusCard(isOnline: Boolean, isRecording: Boolean) {
        val statusIndicatorColor: Int
        val statusTextStr: String
        val modeTextStr: String
        val modeTextColor: Int

        when {
            isRecording -> {
                statusIndicatorColor = ContextCompat.getColor(this, R.color.status_recording)
                statusTextStr = getString(R.string.status_recording)
                modeTextStr = if (useVosk) getString(R.string.mode_offline) else getString(R.string.mode_online)
                modeTextColor = if (useVosk) ContextCompat.getColor(this, R.color.status_offline) else ContextCompat.getColor(this, R.color.status_online)
            }
            isOnline -> {
                statusIndicatorColor = ContextCompat.getColor(this, R.color.status_online)
                statusTextStr = getString(R.string.status_ready)
                modeTextStr = getString(R.string.mode_online)
                modeTextColor = ContextCompat.getColor(this, R.color.status_online)
            }
            else -> {
                statusIndicatorColor = ContextCompat.getColor(this, R.color.status_offline)
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

    private fun updateSessionDuration() {
        if (sessionStartMs == 0L) return
        val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000
        val minutes = elapsed / 60
        val seconds = elapsed % 60
        binding.statDurationValue.text = String.format("%d:%02d", minutes, seconds)
    }

    private fun processBuffer() {
        val text = bufferText.toString().trim()
        if (text.isBlank()) return
        
        // Skip confidence check for Vosk (it doesn't provide confidence)
        if (!useVosk) {
            val confidenceThreshold = 0.3f
            if (lastConfidence < confidenceThreshold) {
                Snackbar.make(binding.rootLayout, R.string.low_confidence, Snackbar.LENGTH_SHORT).show()
                bufferText.clear()
                return
            }
        }
        
        val currentTime = System.currentTimeMillis()
        
        // Analyze emotion from text
        val point = analyzer.analyze(text, currentTime)
        bufferText.clear()
        addEmotionPoint(point)
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
        updateSessionStats()
    }

    // ---------- Session Stats ----------

    private fun updateSessionStats() {
        if (emotionPoints.isEmpty()) {
            binding.statsCard.visibility = View.GONE
            return
        }
        binding.statsCard.visibility = View.VISIBLE

        // Total samples
        binding.statSamplesValue.text = emotionPoints.size.toString()

        // Dominant emotion
        val dominant = emotionCounts.maxByOrNull { it.value }
        if (dominant != null && dominant.value > 0) {
            val name = dominant.key.replaceFirstChar { it.uppercase() }
            binding.statDominantValue.text = name
            binding.statDominantValue.setTextColor(getEmotionColor(dominant.key))
        }

        // Average confidence
        val avgConf = emotionPoints.map { it.confidence }.average()
        binding.statConfidenceValue.text = "${(avgConf * 100).toInt()}%"

        // Duration
        updateSessionDuration()
    }

    // ---------- Reset Session ----------

    private fun confirmResetSession() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset Session")
            .setMessage("Clear all recorded data and start fresh?")
            .setPositiveButton("Reset") { _, _ -> resetSession() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetSession() {
        emotionPoints.clear()
        adapter.submitList(emptyList())
        emotionCounts.replaceAll { _, _ -> 0 }
        sessionStartMs = 0L

        // Reset charts
        binding.emotionPieChart.data = null
        binding.emotionPieChart.invalidate()
        binding.emotionChart.data = null
        binding.emotionChart.invalidate()

        // Reset current emotion card
        binding.currentEmotionText.text = "—"
        binding.currentEmotionText.setTextColor(ContextCompat.getColor(this, R.color.emotion_joy))
        binding.currentEmotionCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.emotion_joy_light))
        binding.confidenceText.text = getString(R.string.confidence_placeholder)

        // Hide stats
        binding.statsCard.visibility = View.GONE

        Snackbar.make(binding.rootLayout, "Session reset", Snackbar.LENGTH_SHORT).show()
    }

    // ---------- Demo Mode ----------

    private fun showDemoModeDialog() {
        val demoTexts = arrayOf(
            "😊 I'm extremely happy and excited today!",
            "😄 This is wonderful and amazing!",
            "😠 I'm so frustrated and angry right now!",
            "😡 This is terrible and makes me furious!",
            "😢 I feel really sad and depressed",
            "💔 I'm heartbroken and disappointed",
            "😲 Wow! That's incredible and surprising!",
            "🤯 I'm shocked and amazed!",
            "😨 I'm so scared and afraid",
            "😰 This is frightening and worrying",
            "😐 Everything is fine and normal",
            "🙂 Just another regular day"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.demo_mode_title)
            .setItems(demoTexts) { _, which ->
                val selectedText = demoTexts[which].substringAfter(" ") // Remove emoji prefix
                processDemoText(selectedText)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun processDemoText(text: String) {
        // Play the phrase using Text-to-Speech
        if (isTtsReady) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
        
        // Initialize session start time if not already recording
        if (sessionStartMs == 0L) {
            sessionStartMs = System.currentTimeMillis()
        }

        // Analyze the demo text directly
        val currentTime = System.currentTimeMillis()
        val point = analyzer.analyze(text, currentTime)
        
        // Add to UI
        addEmotionPoint(point)
        
        // Show feedback
        Snackbar.make(
            binding.rootLayout,
            "🎤 \"${text.take(35)}${if (text.length > 35) "..." else ""}\"",
            Snackbar.LENGTH_LONG
        ).show()
    }

    // ---------- CSV Export ----------

    private fun exportCsv() {
        if (emotionPoints.isEmpty()) {
            Snackbar.make(binding.rootLayout, "No data to export", Snackbar.LENGTH_SHORT).show()
            return
        }

        try {
            val sb = StringBuilder()
            sb.appendLine("timestamp,time_formatted,emotion,score,confidence,text")
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            emotionPoints.asReversed().forEach { p ->
                val time = formatter.format(java.util.Date(p.timeMs))
                val escapedText = p.text.replace("\"", "\"\"")
                sb.appendLine("${p.timeMs},\"$time\",${p.emotion},${p.score},${p.confidence},\"$escapedText\"")
            }

            val csvFile = File(cacheDir, "emotion_data_${System.currentTimeMillis()}.csv")
            csvFile.writeText(sb.toString())

            val uri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                csvFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Export CSV"))
        } catch (e: Exception) {
            Snackbar.make(binding.rootLayout, R.string.export_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    // ---------- Charts ----------

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
            legend.isEnabled = true
            legend.textColor = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
            legend.textSize = 11f
            legend.isWordWrapEnabled = true
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

        val dataSet = PieDataSet(entries, "").apply {
            setColors(colors)
            sliceSpace = 3f
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
            lineWidth = 2.5f
            setDrawValues(false)
            setDrawCircles(true)
            circleRadius = 5f
            circleHoleRadius = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            color = ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_primary)
            setCircleColors(colors)
            setDrawFilled(true)
            fillColor = ContextCompat.getColor(this@MainActivity, R.color.md_theme_light_primary)
            fillAlpha = 25
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
        val confidence = point.confidence
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
        if (emotionPoints.isEmpty()) {
            Snackbar.make(binding.rootLayout, "No data to export", Snackbar.LENGTH_SHORT).show()
            return
        }

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
                obj.put("confidence", point.confidence)
                jsonArray.put(obj)
            }
            val jsonFile = File(cacheDir, "emotion_data_${System.currentTimeMillis()}.json")
            jsonFile.writeText(jsonArray.toString(2))

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

    // ---------- RecognitionListener ----------

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) {
        // Update amplitude bar (rms is typically -2 to 10 dB)
        val normalized = ((rmsdB + 2f) / 12f * 100f).toInt().coerceIn(0, 100)
        binding.amplitudeBar.setProgressCompat(normalized, true)
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        val errorMsg = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error: $error"
        }
        
        android.util.Log.e("VoiceEmotion", "Speech recognition error: $errorMsg (code: $error)")
        
        // Handle specific errors
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // Normal - just restart recognition
                if (isRecording && ::speechRecognizer.isInitialized) {
                    handler.postDelayed({ 
                        if (isRecording) speechRecognizer.startListening(recognizerIntent) 
                    }, 500)
                }
            }
            SpeechRecognizer.ERROR_AUDIO -> {
                // Microphone access issue - common on emulators
                Toast.makeText(
                    this,
                    "⚠️ Microphone access issue. Check Bluestacks audio settings or use Demo Mode (⋮ menu)",
                    Toast.LENGTH_LONG
                ).show()
                if (isRecording) stopRecording()
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
                if (isRecording) stopRecording()
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                // Wait a bit longer and retry
                if (isRecording && ::speechRecognizer.isInitialized) {
                    handler.postDelayed({ 
                        if (isRecording) speechRecognizer.startListening(recognizerIntent) 
                    }, 1000)
                }
            }
            else -> {
                // For other errors, show message but keep trying
                Snackbar.make(binding.rootLayout, errorMsg, Snackbar.LENGTH_SHORT).show()
                if (isRecording && ::speechRecognizer.isInitialized) {
                    handler.postDelayed({ 
                        if (isRecording) speechRecognizer.startListening(recognizerIntent) 
                    }, 600)
                }
            }
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
        if (isRecording && ::speechRecognizer.isInitialized) {
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
        handler.removeCallbacks(durationRunnable)
        NetworkUtil.unregisterNetworkCallback(this, networkCallback)
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        voskRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        coroutineScope.cancel()
        super.onDestroy()
    }
}
