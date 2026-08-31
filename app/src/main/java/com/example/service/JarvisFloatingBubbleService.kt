package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.audio.JarvisSpeechSynthesizer
import com.example.data.db.InteractionLog
import com.example.data.db.JarvisDatabase
import com.example.data.prefs.PreferencesManager
import com.example.engine.FastPathClassifier
import com.example.engine.FastPathResult
import com.example.engine.LlmEngine
import com.example.engine.StepStatus
import com.example.engine.TaskExecutor
import com.example.persona.PersonaType
import com.example.ui.components.FloatingOrbCanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JarvisFloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleContainer: FrameLayout? = null
    private var orbView: FloatingOrbCanvasView? = null
    private var hudView: View? = null
    private var isHudVisible = false

    private lateinit var prefs: PreferencesManager
    private lateinit var llmEngine: LlmEngine
    private lateinit var tts: JarvisSpeechSynthesizer
    private lateinit var db: JarvisDatabase
    private lateinit var executor: TaskExecutor
    private val scope = CoroutineScope(Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var isContinuousListening = false
    private var isCurrentlyListening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        llmEngine = LlmEngine(prefs)
        tts = JarvisSpeechSynthesizer(this)
        db = JarvisDatabase.getInstance(this)
        executor = TaskExecutor(this, db, llmEngine)

        startForegroundServiceNotification()
        setupFloatingBubble()
        initSpeechRecognizer()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "sara_floating_bubble_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SARA Floating Voice Orb",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SARA Voice Orb Active")
            .setContentText("Tap glowing orb for continuous hands-free voice automation")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1001, notification)
        }
    }

    private fun setupFloatingBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            220,
            220,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 400
        }

        val container = FrameLayout(this)
        val orb = FloatingOrbCanvasView(this).apply {
            persona = prefs.activePersona
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(orb)
        bubbleContainer = container
        orbView = orb

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try { windowManager.updateViewLayout(container, params) } catch (e: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 15 && diffY < 15) {
                        onOrbTapped()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            Log.e("SaraFloating", "Error adding floating bubble view", e)
        }
    }

    private fun initSpeechRecognizer() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isCurrentlyListening = true
                            orbView?.isListening = true
                            updateHudStatus("Sun rahi hoon... Boliye! 🎤")
                        }

                        override fun onBeginningOfSpeech() {
                            isCurrentlyListening = true
                            orbView?.isListening = true
                        }

                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            isCurrentlyListening = false
                            orbView?.isListening = false
                            updateHudStatus("Processing command... ⚡")
                        }

                        override fun onError(error: Int) {
                            isCurrentlyListening = false
                            orbView?.isListening = false
                            Log.w("SaraFloating", "Speech error code: $error")
                            if (isContinuousListening) {
                                // Retry listening after short pause
                                mainHandler.postDelayed({
                                    if (isContinuousListening) startListeningLoop()
                                }, 1200)
                            } else {
                                updateHudStatus("Standby mode. Tap orb to speak!")
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            isCurrentlyListening = false
                            orbView?.isListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                val query = matches[0]
                                handleUserVoiceCommand(query)
                            } else if (isContinuousListening) {
                                mainHandler.postDelayed({
                                    if (isContinuousListening) startListeningLoop()
                                }, 1000)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                updateHudUserText(matches[0])
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("SaraFloating", "SpeechRecognizer init failed", e)
        }
    }

    private fun onOrbTapped() {
        if (!isHudVisible) {
            showFloatingVoiceHud()
        }

        if (isContinuousListening) {
            // Stop Continuous Mode
            isContinuousListening = false
            stopListeningLoop()
            orbView?.isListening = false
            updateHudStatus("Continuous voice paused. Tap orb to activate.")
        } else {
            // Start Continuous Mode
            isContinuousListening = true
            startListeningLoop()
            updateHudStatus("Continuous voice active! Bolte rahiye...")
        }
    }

    private fun startListeningLoop() {
        if (speechRecognizer == null) initSpeechRecognizer()
        val recognizer = speechRecognizer ?: return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "hi-IN", "en-US"))
            }
            recognizer.startListening(intent)
            orbView?.isListening = true
        } catch (e: Exception) {
            Log.e("SaraFloating", "Error starting listening", e)
        }
    }

    private fun stopListeningLoop() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {}
        isCurrentlyListening = false
        orbView?.isListening = false
    }

    private var hudUserTextView: TextView? = null
    private var hudStatusTextView: TextView? = null

    private fun showFloatingVoiceHud() {
        if (isHudVisible) return
        isHudVisible = true

        val modalParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 120
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6090D16"))
            setPadding(40, 32, 40, 32)
        }

        val persona = prefs.activePersona
        val title = TextView(this).apply {
            text = "✨ SARA VOICE HUD (${persona.displayName}) • HANDS-FREE"
            setTextColor(when (persona) {
                PersonaType.GIRLFRIEND -> Color.parseColor("#EC4899")
                PersonaType.PROFESSIONAL -> Color.parseColor("#00F0FF")
                PersonaType.BOLD -> Color.parseColor("#F97316")
            })
            textSize = 12f
            setPadding(0, 0, 0, 8)
        }

        val userText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            setPadding(0, 4, 0, 4)
        }
        hudUserTextView = userText

        val statusText = TextView(this).apply {
            text = "Kahiye, main kya karun aapke liye?"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        hudStatusTextView = statusText

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val pauseBtn = TextView(this).apply {
            text = "PAUSE MIC"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 12f
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                isContinuousListening = false
                stopListeningLoop()
                updateHudStatus("Mic paused. Tap orb to resume.")
            }
        }

        val closeBtn = TextView(this).apply {
            text = "MINIMIZE HUD"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(24, 12, 12, 12)
            setOnClickListener {
                hideFloatingVoiceHud()
            }
        }

        buttonLayout.addView(pauseBtn)
        buttonLayout.addView(closeBtn)

        layout.addView(title)
        layout.addView(userText)
        layout.addView(statusText)
        layout.addView(buttonLayout)
        hudView = layout

        try {
            windowManager.addView(layout, modalParams)
        } catch (e: Exception) {
            isHudVisible = false
        }
    }

    private fun hideFloatingVoiceHud() {
        hudView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        hudView = null
        isHudVisible = false
    }

    private fun updateHudUserText(text: String) {
        mainHandler.post {
            hudUserTextView?.text = "🗣️ \"$text\""
        }
    }

    private fun updateHudStatus(text: String) {
        mainHandler.post {
            hudStatusTextView?.text = text
        }
    }

    private fun handleUserVoiceCommand(query: String) {
        updateHudUserText(query)
        updateHudStatus("Planning automation...")
        orbView?.isProcessing = true

        scope.launch {
            db.jarvisDao().insertLog(InteractionLog(text = query, isUser = true))

            val fastPath = FastPathClassifier.classify(query, prefs.activePersona, prefs.assistantName)
            if (fastPath is FastPathResult.Handled) {
                if (fastPath.switchPersona != null) {
                    prefs.activePersona = fastPath.switchPersona
                    orbView?.persona = fastPath.switchPersona
                }
                updateHudStatus(fastPath.immediateReplyHinglish)
                speakAndContinue(fastPath.immediateReplyHinglish) {
                    if (fastPath.plan.steps.isNotEmpty()) {
                        scope.launch {
                            executor.executePlan(
                                plan = fastPath.plan,
                                onStepUpdated = { p ->
                                    val running = p.steps.firstOrNull { it.status == StepStatus.RUNNING }
                                    if (running != null) updateHudStatus("⚙️ ${running.descriptionHinglish}")
                                },
                                onSpeak = { speakAndContinue(it) }
                            )
                        }
                    }
                }
                return@launch
            }

            val memories = db.jarvisDao().getMemoriesList().joinToString("\n") { "- ${it.fact}" }
            val alarms = db.jarvisDao().getActiveAlarmsList().joinToString("\n") { "- ${it.hour}:${it.minute} (${it.label})" }
            val screenContext = JarvisAccessibilityService.instance?.getScreenHierarchySummary() ?: ""

            val result = llmEngine.planAndQuery(query, memories, alarms, screenContext)
            orbView?.isProcessing = false

            result.onSuccess { plan ->
                updateHudStatus(plan.speechResponseHinglish)
                db.jarvisDao().insertLog(InteractionLog(text = plan.speechResponseHinglish, isUser = false))

                speakAndContinue(plan.speechResponseHinglish) {
                    if (plan.steps.isNotEmpty()) {
                        scope.launch {
                            executor.executePlan(
                                plan = plan,
                                onStepUpdated = { p ->
                                    val running = p.steps.firstOrNull { it.status == StepStatus.RUNNING }
                                    if (running != null) updateHudStatus("⚙️ ${running.descriptionHinglish}")
                                },
                                onSpeak = { speakAndContinue(it) }
                            )
                        }
                    }
                }
            }.onFailure { err ->
                val errorMsg = err.message ?: "Task execution error"
                updateHudStatus(errorMsg)
                speakAndContinue(errorMsg)
            }
        }
    }

    private fun speakAndContinue(text: String, onSpeechFinished: (() -> Unit)? = null) {
        tts.speak(text, apiKey = prefs.geminiApiKey) {
            onSpeechFinished?.invoke()
            if (isContinuousListening) {
                mainHandler.postDelayed({
                    if (isContinuousListening) startListeningLoop()
                }, 800)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isContinuousListening = false
        stopListeningLoop()
        speechRecognizer?.destroy()
        hideFloatingVoiceHud()
        bubbleContainer?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        tts.shutdown()
    }
}

