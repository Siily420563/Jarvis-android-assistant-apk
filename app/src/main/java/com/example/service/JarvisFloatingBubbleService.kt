package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
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
import com.example.ui.components.FloatingOrbCanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class JarvisFloatingBubbleService : Service() {

    companion object {
        const val ACTION_START_SESSION = "ACTION_START_SESSION"
        const val ACTION_STOP_SESSION = "ACTION_STOP_SESSION"
        private const val AUTO_SLEEP_TIMEOUT_MS = 45_000L // 45 seconds of silence -> auto-sleep
    }

    private var windowManager: WindowManager? = null
    private var bubbleContainer: FrameLayout? = null
    private var orbView: FloatingOrbCanvasView? = null

    private lateinit var prefs: PreferencesManager
    private lateinit var llmEngine: LlmEngine
    private lateinit var tts: JarvisSpeechSynthesizer
    private lateinit var db: JarvisDatabase
    private lateinit var executor: TaskExecutor
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var isSessionActive = false
    private var isCurrentlyListening = false
    private var isAsleep = false

    private val autoSleepRunnable = Runnable {
        enterSleepState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = PreferencesManager(this)
            llmEngine = LlmEngine(prefs)
            tts = JarvisSpeechSynthesizer(this)
            db = JarvisDatabase.getInstance(this)
            executor = TaskExecutor(this, db, llmEngine)

            startForegroundServiceNotification()
            setupFloatingOrb()
        } catch (e: Exception) {
            Log.e("SaraFloating", "Error in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SESSION -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_SESSION, null -> {
                wakeUpAndStartListening()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        try {
            val channelId = "sara_floating_orb_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "SARA Floating Orb",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps SARA floating voice orb ready for commands"
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }

            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("SARA Voice Orb Active")
                .setContentText("Tap the floating orb anytime to speak commands")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e("SaraFloating", "Error in startForeground", e)
        }
    }

    private fun setupFloatingOrb() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w("SaraFloating", "Overlay permission not granted. Cannot attach orb view.")
            return
        }

        try {
            val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
            windowManager = wm

            // Compact chat-head size (~64dp -> ~170px)
            val orbPx = (64 * resources.displayMetrics.density).toInt()

            val params = WindowManager.LayoutParams(
                orbPx,
                orbPx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 350
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
                        try {
                            wm.updateViewLayout(container, params)
                        } catch (e: Exception) {
                            Log.w("SaraFloating", "Update view layout exception: ${e.message}")
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 20 && diffY < 20) {
                            onOrbTapped()
                        }
                        true
                    }
                    else -> false
                }
            }

            wm.addView(container, params)
        } catch (e: Exception) {
            Log.e("SaraFloating", "Error adding floating orb view", e)
        }
    }

    private fun initSpeechRecognizer() {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            Log.w("SaraFloating", "RECORD_AUDIO permission missing.")
            return
        }

        try {
            if (speechRecognizer != null) {
                try { speechRecognizer?.destroy() } catch (e: Exception) {}
                speechRecognizer = null
            }

            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isCurrentlyListening = true
                            orbView?.isListening = true
                            resetAutoSleepTimer()
                        }

                        override fun onBeginningOfSpeech() {
                            isCurrentlyListening = true
                            orbView?.isListening = true
                            cancelAutoSleepTimer()
                        }

                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            isCurrentlyListening = false
                            orbView?.isListening = false
                            orbView?.isProcessing = true
                        }

                        override fun onError(error: Int) {
                            isCurrentlyListening = false
                            orbView?.isListening = false
                            com.example.audio.MicArbiter.release("orb")
                            Log.w("SaraFloating", "Speech error code: $error")
                            if (isSessionActive && !isAsleep) {
                                resetAutoSleepTimer()
                                mainHandler.postDelayed({
                                    if (isSessionActive && !isAsleep && !isCurrentlyListening) {
                                        startListeningLoop()
                                    }
                                }, 1000)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            isCurrentlyListening = false
                            orbView?.isListening = false
                            com.example.audio.MicArbiter.release("orb")
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                val query = matches[0]
                                handleUserVoiceCommand(query)
                            } else if (isSessionActive && !isAsleep) {
                                resetAutoSleepTimer()
                                mainHandler.postDelayed({
                                    if (isSessionActive && !isAsleep && !isCurrentlyListening) {
                                        startListeningLoop()
                                    }
                                }, 800)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            cancelAutoSleepTimer()
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
        if (isAsleep) {
            // Currently asleep -> wake up and resume listening
            wakeUpAndStartListening()
        } else {
            // Currently awake -> tapping again means "stop for now", not restart.
            // (Previously both branches called wakeUpAndStartListening(), which is why
            // tapping the orb never actually turned it off.)
            tts.stop()
            enterSleepState()
        }
    }

    private fun wakeUpAndStartListening() {
        isAsleep = false
        isSessionActive = true
        orbView?.isAsleep = false
        resetAutoSleepTimer()
        startListeningLoop()
    }

    private fun enterSleepState() {
        if (!isSessionActive) return
        isAsleep = true
        stopListeningLoop()
        orbView?.isAsleep = true
        orbView?.isListening = false
        orbView?.isProcessing = false
        Log.i("SaraFloating", "Orb entered idle sleep state to conserve battery")
    }

    private fun resetAutoSleepTimer() {
        cancelAutoSleepTimer()
        mainHandler.postDelayed(autoSleepRunnable, AUTO_SLEEP_TIMEOUT_MS)
    }

    private fun cancelAutoSleepTimer() {
        mainHandler.removeCallbacks(autoSleepRunnable)
    }

    private fun startListeningLoop() {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) return

        if (speechRecognizer == null) {
            initSpeechRecognizer()
        }

        if (!com.example.audio.MicArbiter.acquire("orb")) {
            // The in-app mic already owns the session right now - back off and
            // try again shortly instead of starting a competing recognizer
            // (this backing-off, instead of blind-retrying, is what actually
            // breaks the on/off loop).
            mainHandler.postDelayed({
                if (isSessionActive && !isAsleep && !isCurrentlyListening) {
                    startListeningLoop()
                }
            }, 1500)
            return
        }

        val recognizer = speechRecognizer ?: run {
            com.example.audio.MicArbiter.release("orb")
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // en-IN as the PRIMARY language gives back Romanized ("Hinglish") text
                // even for Hindi speech, instead of Devanagari script.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "hi-IN", "en-US"))
            }
            recognizer.startListening(intent)
            orbView?.isListening = true
        } catch (e: Exception) {
            com.example.audio.MicArbiter.release("orb")
            Log.e("SaraFloating", "Error starting listening", e)
        }
    }

    private fun stopListeningLoop() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {}
        com.example.audio.MicArbiter.release("orb")
        isCurrentlyListening = false
        orbView?.isListening = false
    }

    private fun handleUserVoiceCommand(query: String) {
        orbView?.isProcessing = true
        cancelAutoSleepTimer()

        scope.launch {
            try {
                db.jarvisDao().insertLog(InteractionLog(text = query, isUser = true))

                val fastPath = FastPathClassifier.classify(query, prefs.activePersona, prefs.assistantName, context = this@JarvisFloatingBubbleService)
                if (fastPath is FastPathResult.Handled) {
                    if (fastPath.plan.intentKey == "STOP_COMMAND") {
                        tts.stop()
                        executor.interruptCurrentExecution()
                        speakAndResumeSession(fastPath.immediateReplyHinglish)
                        return@launch
                    }
                    if (fastPath.switchPersona != null) {
                        prefs.activePersona = fastPath.switchPersona
                        orbView?.persona = fastPath.switchPersona
                    }
                    speakAndResumeSession(fastPath.immediateReplyHinglish) {
                        if (fastPath.plan.steps.isNotEmpty()) {
                            scope.launch {
                                executor.executePlan(
                                    plan = fastPath.plan,
                                    onStepUpdated = { },
                                    onSpeak = { speakAndResumeSession(it) }
                                )
                            }
                        }
                    }
                    return@launch
                }

                val memories = db.jarvisDao().getMemoriesList().joinToString("\n") { "- ${it.fact}" }
                val alarms = db.jarvisDao().getActiveAlarmsList().joinToString("\n") { "- ${it.hour}:${it.minute} (${it.label})" }
                val screenContext = JarvisAccessibilityService.instance?.getScreenHierarchySummary() ?: ""

                val recentLogs = db.jarvisDao().getRecentLogs(8).reversed()
                val historyStr = recentLogs.joinToString("\n") { if (it.isUser) "User: ${it.text}" else "SARA: ${it.text}" }

                val result = llmEngine.planAndQuery(query, memories, alarms, screenContext, historyStr, null)
                orbView?.isProcessing = false

                result.onSuccess { plan ->
                    val displayText = if (plan.usedFallback) {
                        "⚠️ [Fallback mode: ${plan.fallbackReason}]\n${plan.speechResponseHinglish}"
                    } else plan.speechResponseHinglish
                    db.jarvisDao().insertLog(InteractionLog(text = displayText, isUser = false))

                    speakAndResumeSession(plan.speechResponseHinglish) {
                        if (plan.steps.isNotEmpty()) {
                            scope.launch {
                                executor.executePlan(
                                    plan = plan,
                                    onStepUpdated = { },
                                    onSpeak = { speakAndResumeSession(it) }
                                )
                            }
                        }
                    }
                }.onFailure { err ->
                    val errorMsg = err.message ?: "[Error: AI Service Unavailable]"
                    speakAndResumeSession(errorMsg)
                }
            } catch (e: Exception) {
                Log.e("SaraFloating", "Error executing voice command", e)
                val fallbackMsg = "[Error: Command execution failed]"
                speakAndResumeSession(fallbackMsg)
            } finally {
                orbView?.isProcessing = false
            }
        }
    }

    private fun speakAndResumeSession(text: String, onSpeechFinished: (() -> Unit)? = null) {
        tts.speak(text, apiKey = prefs.geminiApiKey, groqApiKey = prefs.groqApiKey) {
            onSpeechFinished?.invoke()
            if (isSessionActive && !isAsleep) {
                resetAutoSleepTimer()
                mainHandler.postDelayed({
                    if (isSessionActive && !isAsleep && !isCurrentlyListening) {
                        startListeningLoop()
                    }
                }, 600)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isSessionActive = false
        cancelAutoSleepTimer()
        stopListeningLoop()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {}
        bubbleContainer?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
        tts.shutdown()
    }
}
