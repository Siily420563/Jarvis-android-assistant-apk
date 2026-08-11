package com.example.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class JarvisAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("ALARM_LABEL") ?: "Wake Up"
        val hour = intent.getIntExtra("ALARM_HOUR", 7)
        val minute = intent.getIntExtra("ALARM_MINUTE", 0)

        val alarmIntent = Intent(context, JarvisAlarmActivity::class.java).apply {
            putExtra("ALARM_LABEL", label)
            putExtra("ALARM_HOUR", hour)
            putExtra("ALARM_MINUTE", minute)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(alarmIntent)
    }
}
