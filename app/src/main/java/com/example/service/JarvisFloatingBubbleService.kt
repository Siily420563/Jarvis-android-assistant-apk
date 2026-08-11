package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.example.engine.ActionParser
import com.example.engine.LlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JarvisFloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var isExpanded = false

    private lateinit var prefs: PreferencesManager
    private lateinit var llmEngine: LlmEngine
    private lateinit var tts: JarvisSpeechSynthesizer
    private lateinit var db: JarvisDatabase
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        llmEngine = LlmEngine(prefs)
        tts = JarvisSpeechSynthesizer(this)
        db = JarvisDatabase.getInstance(this)

        startForegroundServiceNotification()
        setupFloatingBubble()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "jarvis_floating_bubble_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "J.A.R.V.I.S. Floating Arc Reactor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("J.A.R.V.I.S. Arc Reactor HUD")
            .setContentText("Neural logic overlay is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
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
            x = 100
            y = 300
        }

        val container = FrameLayout(this)
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = FrameLayout.LayoutParams(140, 140)
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
                    windowManager.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 10 && diffY < 10) {
                        toggleMiniTerminalModal()
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

    private fun toggleMiniTerminalModal() {
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
            setBackgroundColor(Color.parseColor("#060F1A"))
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S. OVERLAY TERMINAL"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        val input = EditText(this).apply {
            hint = "Command J.A.R.V.I.S...."
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0D1622"))
            setPadding(24, 24, 24, 24)
        }

        val statusText = TextView(this).apply {
            text = "Awaiting command, Boss..."
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 12f
            setPadding(0, 16, 0, 16)
        }

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val sendBtn = TextView(this).apply {
            text = "EXECUTE"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 14f
            setPadding(32, 16, 16, 16)
            setOnClickListener {
                val cmd = input.text.toString().trim()
                if (cmd.isNotBlank()) {
                    statusText.text = "Processing neural logic..."
                    scope.launch {
                        db.jarvisDao().insertLog(InteractionLog(text = cmd, isUser = true))
                        val memories = db.jarvisDao().getMemoriesList().joinToString("\n") { "- ${it.fact}" }
                        val alarms = db.jarvisDao().getActiveAlarmsList().joinToString("\n") { "- ${it.hour}:${it.minute} (${it.label})" }

                        val result = llmEngine.queryJarvis(cmd, memories, alarms)
                        result.onSuccess { rawResponse ->
                            val cleanResponse = ActionParser.stripActionTags(rawResponse)
                            statusText.text = cleanResponse
                            tts.speak(cleanResponse)
                            db.jarvisDao().insertLog(InteractionLog(text = cleanResponse, isUser = false))

                            val actions = ActionParser.parseActions(rawResponse)
                            for (action in actions) {
                                when (action) {
                                    is com.example.engine.JarvisAction.RememberAction -> {
                                        db.jarvisDao().insertMemory(com.example.data.db.UserMemory(fact = action.fact, category = action.category))
                                    }
                                    is com.example.engine.JarvisAction.SetAlarmAction -> {
                                        db.jarvisDao().insertAlarm(com.example.data.db.JarvisAlarm(hour = action.hour, minute = action.minute, label = action.label))
                                        com.example.alarm.JarvisAlarmScheduler.scheduleAlarm(this@JarvisFloatingBubbleService, action.hour, action.minute, action.label)
                                    }
                                    is com.example.engine.JarvisAction.AccessibilityAction -> {
                                        val service = JarvisAccessibilityService.instance
                                        if (service != null) {
                                            when (action.actionName.uppercase()) {
                                                "HOME" -> service.performHome()
                                                "BACK" -> service.performBack()
                                                "SCROLL_DOWN" -> service.scrollDown()
                                                "SCROLL_UP" -> service.scrollUp()
                                                "TYPE" -> service.typeTextInFocusedInput(action.text)
                                            }
                                        }
                                    }
                                    is com.example.engine.JarvisAction.OpenAppAction -> {
                                        try {
                                            val intent = packageManager.getLaunchIntentForPackage(action.packageName)
                                            if (intent != null) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        } catch (e: Exception) { e.printStackTrace() }
                                    }
                                }
                            }
                        }.onFailure { err ->
                            statusText.text = err.message ?: "Execution failed, Boss."
                        }
                    }
                }
            }
        }

        val closeBtn = TextView(this).apply {
            text = "CLOSE"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 14f
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                try {
                    windowManager.removeView(layout)
                } catch (e: Exception) { e.printStackTrace() }
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
            e.printStackTrace()
            isExpanded = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { e.printStackTrace() }
        }
        tts.shutdown()
    }
}
