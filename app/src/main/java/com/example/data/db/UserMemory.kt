package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_memories")
data class UserMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val category: String = "General", // Personal, Preference, Location, Task, General
    val timestamp: Long = System.currentTimeMillis()
)
