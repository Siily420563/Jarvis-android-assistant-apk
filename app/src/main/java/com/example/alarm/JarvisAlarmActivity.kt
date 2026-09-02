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
import com.example.data.prefs.PreferencesManager
import com.example.persona.PersonaType
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SaraPink
import com.example.ui.theme.SaraRose
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class JarvisAlarmActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private lateinit var tts: JarvisSpeechSynthesizer
    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = JarvisSpeechSynthesizer(this)
        prefs = PreferencesManager(this)

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
                    persona = prefs.activePersona,
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

            val todayDate = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
            val memoryText = if (memories.isNotEmpty()) {
                "Aapke kuch notes: " + memories.take(2).joinToString("; ") { it.fact }
            } else ""

            val briefingText = when (prefs.activePersona) {
                PersonaType.GIRLFRIEND -> "Good morning jaan! Aaj hai $todayDate. $memoryText Utho ab jaldi, main aapka wait kar rahi hoon! 💕"
                PersonaType.PROFESSIONAL -> "Good morning, Sir. Today is $todayDate. $memoryText All device systems are fully operational."
                PersonaType.BOLD -> "Good morning! $todayDate ho chuka hai. Uth jao fatfat, aur snooze mat dabana!"
            }

            tts.speak(briefingText, apiKey = prefs.geminiApiKey, groqApiKey = prefs.groqApiKey) {
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

    override fun onStop() {
        super.onStop()
        ringtone?.stop()
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
    persona: PersonaType,
    onDismissAndBriefing: () -> Unit,
    onSnooze: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "SARA ALARM PROTOCOL",
                color = SaraPink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .size((170 * scale).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF111827))
                    .border(3.dp, SaraPink, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(CircleShape)
                        .background(SaraPink.copy(alpha = 0.3f))
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
                color = SaraRose,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onDismissAndBriefing,
                colors = ButtonDefaults.buttonColors(containerColor = SaraPink),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "DISMISS & HINGLISH BRIEFING",
                    color = Color.White,
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
                    color = SaraPink,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
