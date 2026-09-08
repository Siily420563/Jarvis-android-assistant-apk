package com.example.audio

import com.example.engine.TaskPlan
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global Bridge between Background Floating Bubble Service, In-App UI, and Task Execution.
 * Ensures the Floating Orb and the In-App Mic share the exact same execution, listening,
 * and state pipeline, with zero hallucinations or contention.
 */
object SaraVoiceBridge {

    // Shared execution request bus (triggers execution in MainViewModel)
    private val _voiceCommandRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val voiceCommandRequests: SharedFlow<String> = _voiceCommandRequests.asSharedFlow()

    // Shared stop request bus
    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

    // Shared response text for HUD and UI display
    private val _currentAssistantSpeech = MutableStateFlow("")
    val currentAssistantSpeech: StateFlow<String> = _currentAssistantSpeech.asStateFlow()

    // Active task plan state for HUD visualization
    private val _activeTaskPlan = MutableStateFlow<TaskPlan?>(null)
    val activeTaskPlan: StateFlow<TaskPlan?> = _activeTaskPlan.asStateFlow()

    fun requestVoiceCommand(command: String) {
        _voiceCommandRequests.tryEmit(command)
    }

    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }

    fun updateSpeech(speech: String) {
        _currentAssistantSpeech.value = speech
    }

    fun updatePlan(plan: TaskPlan?) {
        _activeTaskPlan.value = plan
    }
}
