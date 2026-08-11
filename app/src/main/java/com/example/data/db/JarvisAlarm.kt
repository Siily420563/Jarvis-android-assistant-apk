package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "jarvis_alarms")
data class JarvisAlarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val label: String,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
