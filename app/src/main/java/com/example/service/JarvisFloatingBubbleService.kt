package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.JarvisSpeechSynthesizer
import com.example.data.db.JarvisDatabase
import com.example.data.prefs.PreferencesManager
import com.example.debug.SystemLogBus
import com.example.engine.TaskExecutor
import com.example.ui.components.ElementHighlightOverlay
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
    private var highlightOverlay: ElementHighlightOverlay? = null

    private lateinit var prefs: PreferencesManager
    private lateinit var llmEngine: com.example.engine.LlmEngine
    private lateinit var tts: JarvisSpeechSynthesizer
    private lateinit var db: JarvisDatabase
    private lateinit var executor: TaskExecutor
    private lateinit var agentLoop: com.example.brain.AgentLoop
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isSessionActive = false
    private var isAsleep = false
    private lateinit var voiceManager: com.example.audio.UnifiedVoiceSessionManager

    private val autoSleepRunnable = Runnable {
        enterSleepState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = PreferencesManager(this)
            llmEngine = com.example.engine.LlmEngine(prefs)
            tts = JarvisSpeechSynthesizer(this)
            db = JarvisDatabase.getInstance(this)
            executor = TaskExecutor(this, db, llmEngine)
            agentLoop = com.example.brain.AgentLoop(this, db, prefs, llmEngine)
            voiceManager = com.example.audio.UnifiedVoiceSessionManager.getInstance(this)

            startForegroundServiceNotification()
            setupFloatingOrb()
            setupVoiceStateObservation()
            setupFallbackCommandHandling()
            SystemLogBus.i("SaraFloating", "Floating service created - orb ready")
        } catch (e: Exception) {
            Log.e("SaraFloating", "Error in onCreate", e)
            SystemLogBus.e("SaraFloating", "onCreate failed: ${e.message}")
        }
    }

    private fun setupVoiceStateObservation() {
        scope.launch {
            voiceManager.isListening.collect { listening ->
                mainHandler.post {
                    orbView?.isListening = listening
                }
            }
        }
        scope.launch {
            voiceManager.isProcessing.collect { processing ->
                mainHandler.post {
                    orbView?.isProcessing = processing
                }
            }
        }
        scope.launch {
            voiceManager.isContinuousMode.collect { continuous ->
                mainHandler.post {
                    // Update orb sleep state to reflect continuous mode
                    if (continuous) {
                        orbView?.isAsleep = false
                        isAsleep = false
                    }
                }
            }
        }
    }

    /**
     * Fallback: When MainViewModel is dead (app in background), floating service itself
     * executes commands via its own executor so voice still works.
     * When ViewModel is alive, ViewModel handles commands - we skip to avoid double execution.
     */
    private fun setupFallbackCommandHandling() {
        scope.launch {
            com.example.audio.SaraVoiceBridge.voiceCommandRequests.collect { cmd ->
                // If ViewModel is active, let it handle (avoid double)
                if (com.example.ui.ViewModelActiveTracker.isViewModelActive) {
                    Log.d("SaraFloating", "ViewModel active - skipping fallback execution for: $cmd")
                    return@collect
                }
                Log.i("SaraFloating", "Fallback execution (VM dead): $cmd")
                SystemLogBus.i("SaraFloating", "Executing fallback: $cmd")
                orbView?.isProcessing = true
                voiceManager.setProcessing(true)
                cancelAutoSleepTimer()
                // Use TaskExecutor fallback: route via LlmEngine heuristic/local
                // For minimal duplication, reuse similar logic to MainViewModel but simplified
                // We will directly request via bridge? No, we execute here.
                // To avoid duplicating LLM logic, we delegate to a simple heuristic via executor
                // For now, handle via llmEngine planAndQuery and executor
                scope.launch(Dispatchers.IO) {
                    try {
                        val recent = try { db.jarvisDao().getRecentLogs(5).reversed().joinToString("\n") { if(it.isUser) "User:${it.text}" else "SARA:${it.text}" } } catch(_:Exception){""}
                        val memStr = try { db.jarvisDao().getMemoriesList().take(5).joinToString("\n") { "- ${it.fact}" } } catch(_:Exception){""}
                        val alarmStr = try { db.jarvisDao().getActiveAlarmsList().joinToString("\n") { "- ${it.hour}:${it.minute}" } } catch(_:Exception){""}
                        val planResult = llmEngine.planAndQuery(cmd, memStr, alarmStr, "", recent, null)
                        planResult.onSuccess { plan ->
                            launch(Dispatchers.Main) {
                                if (plan.requiresRiskyConfirmation) {
                                    val prompt = plan.confirmationPrompt.ifBlank { "Confirm?" }
                                    tts.speak(prompt, apiKey = prefs.geminiApiKey) {}
                                    voiceManager.setProcessing(false)
                                    orbView?.isProcessing = false
                                    return@launch
                                }
                                // Speak first
                                tts.speak(plan.speechResponseHinglish, apiKey = prefs.geminiApiKey) {
                                    if (isSessionActive && !isAsleep) voiceManager.resumeContinuousListeningAfterSpeech()
                                }
                                if (plan.steps.isNotEmpty()) {
                                    // Check accessibility
                                    if (plan.steps.any { it.type.name.startsWith("ACCESSIBILITY") || it.type.name == "VISION_INSPECT_AND_TAP" } && !JarvisAccessibilityService.isOnline) {
                                        val warn = "Accessibility service OFF hai"
                                        tts.speak(warn, apiKey = prefs.geminiApiKey) {}
                                        voiceManager.setProcessing(false)
                                        orbView?.isProcessing = false
                                        return@launch
                                    }
                                    launch(Dispatchers.IO) {
                                        executor.executePlan(plan, onStepUpdated = { }, onSpeak = { msg ->
                                            launch(Dispatchers.Main) { tts.speak(msg, apiKey = prefs.geminiApiKey) {} }
                                        })
                                        launch(Dispatchers.Main) {
                                            voiceManager.setProcessing(false)
                                            orbView?.isProcessing = false
                                            if (isSessionActive && !isAsleep) resetAutoSleepTimer()
                                        }
                                    }
                                } else {
                                    voiceManager.setProcessing(false)
                                    orbView?.isProcessing = false
                                    if (isSessionActive && !isAsleep) resetAutoSleepTimer()
                                }
                                // log
                                try { db.jarvisDao().insertLog(com.example.data.db.InteractionLog(text = plan.speechResponseHinglish, isUser = false)) } catch(_:Exception){}
                            }
                        }.onFailure { err ->
                            launch(Dispatchers.Main) {
                                val msg = "Error: ${err.message}"
                                tts.speak(msg, apiKey = prefs.geminiApiKey) {}
                                voiceManager.setProcessing(false)
                                orbView?.isProcessing = false
                            }
                        }
                        try { db.jarvisDao().insertLog(com.example.data.db.InteractionLog(text = cmd, isUser = true)) } catch(_:Exception){}
                    } catch (e: Exception) {
                        Log.e("SaraFloating", "Fallback execution failed", e)
                        launch(Dispatchers.Main) {
                            voiceManager.setProcessing(false)
                            orbView?.isProcessing = false
                        }
                    }
                }
            }
        }
        scope.launch {
            com.example.audio.SaraVoiceBridge.stopRequests.collect {
                Log.i("SaraFloating", "Stop request via bridge")
                tts.stop()
                voiceManager.setProcessing(false)
                orbView?.isProcessing = false
                executor.interruptCurrentExecution()
                agentLoop.cancel()
            }
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
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
            SystemLogBus.w("SaraFloating", "Overlay permission missing")
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

            // Attach element highlight overlay
            val highlightParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            val overlay = ElementHighlightOverlay(this)
            try {
                wm.addView(overlay, highlightParams)
                highlightOverlay = overlay
                JarvisAccessibilityService.onElementHighlighted = { rect ->
                    mainHandler.post {
                        highlightOverlay?.setHighlight(rect)
                    }
                }
            } catch (e: Exception) {
                Log.w("SaraFloating", "Unable to add highlight overlay: ${e.message}")
            }
            SystemLogBus.i("SaraFloating", "Orb view attached")
        } catch (e: Exception) {
            Log.e("SaraFloating", "Error adding floating orb view", e)
            SystemLogBus.e("SaraFloating", "Orb attach failed: ${e.message}")
        }
    }

    private fun onOrbTapped() {
        Log.i("SaraFloating", "Orb tapped processing=${voiceManager.isProcessing.value} asleep=$isAsleep")
        if (voiceManager.isProcessing.value) {
            // Emergency Stop: user tapped while processing/executing
            com.example.audio.SaraVoiceBridge.requestStop()
            voiceManager.setProcessing(false)
            tts.stop()
            executor.interruptCurrentExecution()
            agentLoop.cancel()
            speakAndResumeSession("Stopped. Batao agla kaam kya hai?")
            orbView?.isProcessing = false
            return
        }

        if (isAsleep) {
            // Currently asleep -> wake up and resume continuous listening
            SystemLogBus.i("SaraFloating", "Orb wakeup")
            wakeUpAndStartListening()
        } else {
            // If listening, pause; if not, start listening
            if (voiceManager.isListening.value) {
                tts.stop()
                voiceManager.pauseListening()
                orbView?.isListening = false
                SystemLogBus.i("SaraFloating", "Orb paused listening")
            } else if (voiceManager.isContinuousMode.value) {
                // Already continuous but not listening (gap) -> resume
                voiceManager.startContinuousSession()
                resetAutoSleepTimer()
            } else {
                tts.stop()
                enterSleepState()
            }
        }
    }

    private fun wakeUpAndStartListening() {
        // Check mic permission first
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            SystemLogBus.e("SaraFloating", "Mic permission missing - cannot wake")
            tts.speak("Mic permission chahiye!", apiKey = prefs.geminiApiKey) {}
            return
        }
        isAsleep = false
        isSessionActive = true
        orbView?.isAsleep = false
        resetAutoSleepTimer()
        voiceManager.startContinuousSession()
        SystemLogBus.i("SaraFloating", "Wakeup -> continuous listening")
    }

    private fun enterSleepState() {
        isAsleep = true
        voiceManager.pauseListening()
        orbView?.isAsleep = true
        orbView?.isListening = false
        orbView?.isProcessing = false
        Log.i("SaraFloating", "Orb entered idle sleep state to conserve battery")
        SystemLogBus.i("SaraFloating", "Orb sleep")
    }

    private fun resetAutoSleepTimer() {
        cancelAutoSleepTimer()
        mainHandler.postDelayed(autoSleepRunnable, AUTO_SLEEP_TIMEOUT_MS)
    }

    private fun cancelAutoSleepTimer() {
        mainHandler.removeCallbacks(autoSleepRunnable)
    }

    private fun speakAndResumeSession(text: String, onSpeechFinished: (() -> Unit)? = null) {
        tts.speak(text, apiKey = prefs.geminiApiKey, groqApiKey = prefs.groqApiKey) {
            onSpeechFinished?.invoke()
            if (isSessionActive && !isAsleep) {
                resetAutoSleepTimer()
                voiceManager.resumeContinuousListeningAfterSpeech()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isSessionActive = false
        cancelAutoSleepTimer()
        highlightOverlay?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            highlightOverlay = null
        }
        JarvisAccessibilityService.onElementHighlighted = null
        bubbleContainer?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
        tts.shutdown()
        SystemLogBus.i("SaraFloating", "Floating service destroyed")
    }
}
