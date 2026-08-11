package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "interaction_logs")
data class InteractionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
