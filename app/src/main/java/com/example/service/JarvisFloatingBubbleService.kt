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
import android.os.IBinder
import android.view.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.example.engine.TaskExecutor
import com.example.persona.PersonaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JarvisFloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var isExpanded = false

    private lateinit var prefs: PreferencesManager
    private lateinit var llmEngine: LlmEngine
    private lateinit var tts: JarvisSpeechSynthesizer
    private lateinit var db: JarvisDatabase
    private lateinit var executor: TaskExecutor
    private val scope = CoroutineScope(Dispatchers.Main)

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
    }

    private fun startForegroundServiceNotification() {
        val channelId = "sara_floating_bubble_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SARA Floating Voice Bubble",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SARA Voice HUD Active")
            .setContentText("Tap overlay or command SARA anytime in Hinglish")
            .setSmallIcon(R.mipmap.ic_launcher)
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
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 350
        }

        val container = FrameLayout(this)
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = FrameLayout.LayoutParams(130, 130)
            setBackgroundColor(Color.TRANSPARENT)
        }
        container.addView(icon)
        bubbleView = container

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
                    if (diffX < 12 && diffY < 12) {
                        showFloatingVoiceHud()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showFloatingVoiceHud() {
        if (isExpanded) return
        isExpanded = true

        val modalParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A")) // Deep modern slate
            setPadding(36, 32, 36, 32)
        }

        val persona = prefs.activePersona
        val title = TextView(this).apply {
            text = "✨ SARA VOICE HUD (${persona.displayName})"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 15f
            setPadding(0, 0, 0, 16)
        }

        val input = EditText(this).apply {
            hint = "Speak or type in Hinglish (e.g. 'Mom ko message karo')..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 20, 24, 20)
        }

        val statusText = TextView(this).apply {
            text = "Kahiye, main kya karun aapke liye?"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setPadding(0, 16, 0, 16)
        }

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val sendBtn = TextView(this).apply {
            text = "EXECUTE"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            setPadding(28, 16, 16, 16)
            setOnClickListener {
                val cmd = input.text.toString().trim()
                if (cmd.isNotBlank()) {
                    statusText.text = "Planning task in Hinglish..."
                    scope.launch {
                        db.jarvisDao().insertLog(InteractionLog(text = cmd, isUser = true))

                        // Check FastPath
                        val fastPath = FastPathClassifier.classify(cmd, prefs.activePersona, prefs.assistantName)
                        if (fastPath is FastPathResult.Handled) {
                            if (fastPath.switchPersona != null) {
                                prefs.activePersona = fastPath.switchPersona
                            }
                            statusText.text = fastPath.immediateReplyHinglish
                            tts.speak(fastPath.immediateReplyHinglish)
                            db.jarvisDao().insertLog(InteractionLog(text = fastPath.immediateReplyHinglish, isUser = false))

                            if (fastPath.plan.steps.isNotEmpty()) {
                                executor.executePlan(
                                    plan = fastPath.plan,
                                    onStepUpdated = { p ->
                                        val running = p.steps.firstOrNull { it.status == com.example.engine.StepStatus.RUNNING }
                                        if (running != null) statusText.text = running.descriptionHinglish
                                    },
                                    onSpeak = { tts.speak(it) }
                                )
                            }
                            return@launch
                        }

                        // LLM Fallback
                        val memories = db.jarvisDao().getMemoriesList().joinToString("\n") { "- ${it.fact}" }
                        val alarms = db.jarvisDao().getActiveAlarmsList().joinToString("\n") { "- ${it.hour}:${it.minute} (${it.label})" }
                        val screenContext = JarvisAccessibilityService.instance?.getScreenHierarchySummary() ?: ""

                        val result = llmEngine.planAndQuery(cmd, memories, alarms, screenContext)
                        result.onSuccess { plan ->
                            statusText.text = plan.speechResponseHinglish
                            tts.speak(plan.speechResponseHinglish)
                            db.jarvisDao().insertLog(InteractionLog(text = plan.speechResponseHinglish, isUser = false))

                            executor.executePlan(
                                plan = plan,
                                onStepUpdated = { p ->
                                    val running = p.steps.firstOrNull { it.status == com.example.engine.StepStatus.RUNNING }
                                    if (running != null) statusText.text = running.descriptionHinglish
                                },
                                onSpeak = { tts.speak(it) }
                            )
                        }.onFailure { err ->
                            statusText.text = err.message ?: "Task execution error"
                        }
                    }
                }
            }
        }

        val closeBtn = TextView(this).apply {
            text = "DISMISS"
            setTextColor(Color.parseColor("#F43F5E"))
            textSize = 14f
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                try {
                    windowManager.removeView(layout)
                } catch (e: Exception) {}
                isExpanded = false
            }
        }

        buttonLayout.addView(closeBtn)
        buttonLayout.addView(sendBtn)

        layout.addView(title)
        layout.addView(input)
        layout.addView(statusText)
        layout.addView(buttonLayout)

        try {
            windowManager.addView(layout, modalParams)
        } catch (e: Exception) {
            isExpanded = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        tts.shutdown()
    }
}
