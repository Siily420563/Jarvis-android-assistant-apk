package com.example.alarm

import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.JarvisSpeechSynthesizer
import com.example.data.db.JarvisDatabase
import com.example.ui.theme.MyApplicationTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class JarvisAlarmActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private lateinit var tts: JarvisSpeechSynthesizer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = JarvisSpeechSynthesizer(this)

        // Turn screen on and show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Play alarm ringtone
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val label = intent.getStringExtra("ALARM_LABEL") ?: "Wake Up"
        val hour = intent.getIntExtra("ALARM_HOUR", 7)
        val minute = intent.getIntExtra("ALARM_MINUTE", 0)

        setContent {
            MyApplicationTheme {
                AlarmScreenContent(
                    label = label,
                    timeStr = String.format(Locale.getDefault(), "%02d:%02d", hour, minute),
                    onDismissAndBriefing = { triggerBriefingAndFinish() },
                    onSnooze = { snoozeAndFinish(hour, minute, label) }
                )
            }
        }
    }

    private fun triggerBriefingAndFinish() {
        ringtone?.stop()
        lifecycleScope.launch {
            val db = JarvisDatabase.getInstance(this@JarvisAlarmActivity)
            val memories = withContext(Dispatchers.IO) {
                db.jarvisDao().getMemoriesList()
            }

            val todayDate = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
            val memoryText = if (memories.isNotEmpty()) {
                "Here are your cataloged neural notes, Boss: " + memories.take(3).joinToString("; ") { it.fact }
            } else {
                "No critical tasks logged in your neural memory for today, Sir."
            }

            val briefingText = "Good morning, Boss. Today is $todayDate. $memoryText All sub-systems are fully operational. Have a productive day, Sir."
            tts.speak(briefingText) {
                finish()
            }
        }
    }

    private fun snoozeAndFinish(hour: Int, minute: Int, label: String) {
        ringtone?.stop()
        val newMin = (minute + 5) % 60
        val newHour = if (minute + 5 >= 60) (hour + 1) % 24 else hour
        JarvisAlarmScheduler.scheduleAlarm(this, newHour, newMin, "$label (Snoozed)")
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ringtone?.stop()
        tts.shutdown()
    }
}

@Composable
fun AlarmScreenContent(
    label: String,
    timeStr: String,
    onDismissAndBriefing: () -> Unit,
    onSnooze: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF060F1A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "J.A.R.V.I.S. ALARM PROTOCOL",
                color = Color(0xFF00E5FF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Pulsing Arc Reactor Visualizer
            Box(
                modifier = Modifier
                    .size((180 * scale).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0D1622))
                    .border(3.dp, Color(0xFF00E5FF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00E5FF).copy(alpha = 0.3f))
                        .border(2.dp, Color.White, CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = timeStr,
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = label,
                color = Color(0xFF80D8FF),
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onDismissAndBriefing,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "DISMISS & MORNING BRIEFING",
                    color = Color(0xFF060F1A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onSnooze,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "SNOOZE (5 MINS)",
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
