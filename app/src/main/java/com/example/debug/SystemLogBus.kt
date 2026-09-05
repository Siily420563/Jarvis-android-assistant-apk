package com.example.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel { INFO, WARN, ERROR }

data class SystemLogEntry(
    val ts: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String
)

object SystemLogBus {
    private const val MAX_LOGS = 300
    private val _logs = MutableStateFlow<List<SystemLogEntry>>(emptyList())
    val logs: StateFlow<List<SystemLogEntry>> = _logs.asStateFlow()

    fun i(tag: String, message: String) = append(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = append(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = append(LogLevel.ERROR, tag, message)

    fun clear() {
        _logs.value = emptyList()
    }

    private fun append(level: LogLevel, tag: String, message: String) {
        val next = _logs.value.toMutableList()
        next.add(SystemLogEntry(level = level, tag = tag, message = message))
        _logs.value = next.takeLast(MAX_LOGS)
    }
}
