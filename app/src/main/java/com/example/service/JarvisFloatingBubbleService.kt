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
import com.example.ui.components.ElementHighlightOverlay
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
    private lateinit var llmEngine: LlmEngine
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
            llmEngine = LlmEngine(prefs)
            tts = JarvisSpeechSynthesizer(this)
            db = JarvisDatabase.getInstance(this)
            executor = TaskExecutor(this, db, llmEngine)
            agentLoop = com.example.brain.AgentLoop(this, db, prefs, llmEngine)
            voiceManager = com.example.audio.UnifiedVoiceSessionManager.getInstance(this)

            startForegroundServiceNotification()
            setupFloatingOrb()
            setupVoiceStateObservation()
        } catch (e: Exception) {
            Log.e("SaraFloating", "Error in onCreate", e)
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
        } catch (e: Exception) {
            Log.e("SaraFloating", "Error adding floating orb view", e)
        }
    }

    private fun onOrbTapped() {
        if (voiceManager.isProcessing.value) {
            // Emergency Stop: user tapped while processing/executing
            com.example.audio.SaraVoiceBridge.requestStop()
            voiceManager.setProcessing(false)
            tts.stop()
            speakAndResumeSession("Stopped.")
            return
        }

        if (isAsleep) {
            // Currently asleep -> wake up and resume continuous listening
            wakeUpAndStartListening()
        } else {
            tts.stop()
            enterSleepState()
        }
    }

    private fun wakeUpAndStartListening() {
        isAsleep = false
        isSessionActive = true
        orbView?.isAsleep = false
        resetAutoSleepTimer()
        voiceManager.startContinuousSession()
    }

    private fun enterSleepState() {
        isAsleep = true
        voiceManager.pauseListening()
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

    private fun handleUserVoiceCommand(query: String) {
        orbView?.isProcessing = true
        cancelAutoSleepTimer()

        // Route command to the centralized MainViewModel via SaraVoiceBridge so exactly one execution path runs
        com.example.audio.SaraVoiceBridge.requestVoiceCommand(query)
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
    }
}
