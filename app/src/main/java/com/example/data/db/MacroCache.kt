package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "macro_cache")
data class MacroCache(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val intentKey: String, // e.g. "WHATSAPP_MOM_MESSAGE", "PLAY_SPOTIFY"
    val taskDescription: String,
    val taskGraphJson: String, // JSON serialized TaskPlan
    val executionCount: Int = 1,
    val lastExecuted: Long = System.currentTimeMillis()
)
