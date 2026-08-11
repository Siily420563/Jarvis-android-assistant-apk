package com.example.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface JarvisDao {
    // Interaction logs
    @Query("SELECT * FROM interaction_logs ORDER BY timestamp DESC LIMIT 100")
    fun getAllInteractionLogs(): Flow<List<InteractionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: InteractionLog): Long

    @Query("DELETE FROM interaction_logs")
    suspend fun clearLogs()

    // Memories
    @Query("SELECT * FROM user_memories ORDER BY timestamp DESC")
    fun getAllMemories(): Flow<List<UserMemory>>

    @Query("SELECT * FROM user_memories ORDER BY timestamp DESC")
    suspend fun getMemoriesList(): List<UserMemory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: UserMemory): Long

    @Delete
    suspend fun deleteMemory(memory: UserMemory)

    // Alarms
    @Query("SELECT * FROM jarvis_alarms ORDER BY hour ASC, minute ASC")
    fun getAllAlarms(): Flow<List<JarvisAlarm>>

    @Query("SELECT * FROM jarvis_alarms WHERE isActive = 1 ORDER BY hour ASC, minute ASC")
    suspend fun getActiveAlarmsList(): List<JarvisAlarm>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: JarvisAlarm): Long

    @Update
    suspend fun updateAlarm(alarm: JarvisAlarm)

    @Delete
    suspend fun deleteAlarm(alarm: JarvisAlarm)
}
