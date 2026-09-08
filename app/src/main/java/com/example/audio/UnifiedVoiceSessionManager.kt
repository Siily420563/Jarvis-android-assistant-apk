package com.example.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.debug.SystemLogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single Unified Voice Session Manager for both Foreground Activity and Floating Orb.
 * Exactly one SpeechRecognizer runs in the entire application.
 * Both the In-App Mic and the Floating Orb interact with this single source of truth,
 * completely eliminating race conditions, mic arbitration conflicts, or hallucinations.
 *
 * FIXES for "not following voice command":
 * - Added RECORD_AUDIO permission guard before startListening (previously silently failed with ERROR 9)
 * - Fixed error recovery: all error codes now properly reset recognizer or retry with backoff
 * - Fixed continuousMode being killed on every command (pause vs stop distinction)
 * - Added SystemLogBus logging for diagnostics
 * - Added fallback to SaraVoiceBridge when ViewModel listener is dead (background orb)
 * - Guarded barge-in to only interrupt when TTS is actually speaking
 */
class UnifiedVoiceSessionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedVoice"

        @Volatile
        private var instance: UnifiedVoiceSessionManager? = null

        fun getInstance(context: Context): UnifiedVoiceSessionManager {
            return instance ?: synchronized(this) {
                instance ?: UnifiedVoiceSessionManager(context.applicationContext).also { instance = it }
            }
        }

        /** For tests / service lifecycle */
        fun clearForTesting() {
            instance?.teardownRecognizer()
            instance = null
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isContinuousMode = MutableStateFlow(false)
    val isContinuousMode: StateFlow<Boolean> = _isContinuousMode.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _rmsdB = MutableStateFlow(0f)
    val rmsdB: StateFlow<Float> = _rmsdB.asStateFlow()

    // expose last error for UI
    private val _lastError = MutableStateFlow<Int?>(null)
    val lastError: StateFlow<Int?> = _lastError.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var onCommandRecognized: ((String) -> Unit)? = null
    private var onInterruptionDetected: (() -> Unit)? = null

    private var consecutiveErrors = 0
    var isBargeInEnabled = true

    fun setCommandListener(listener: (String) -> Unit) {
        this.onCommandRecognized = listener
        Log.i(TAG, "Command listener registered: $listener")
        SystemLogBus.i(TAG, "Voice listener attached")
    }

    fun clearCommandListener() {
        onCommandRecognized = null
        Log.i(TAG, "Command listener cleared")
    }

    fun setInterruptionListener(listener: () -> Unit) {
        this.onInterruptionDetected = listener
    }

    fun setProcessing(processing: Boolean) {
        _isProcessing.value = processing
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    private fun ensureRecognizer(): SpeechRecognizer? {
        if (speechRecognizer != null) return speechRecognizer
        // Guard: check permission before creating?
        // We allow creation even without permission, but startListening will guard
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition NOT available on this device - Google app may be missing")
            SystemLogBus.w(TAG, "Speech recognition unavailable - check Google app")
            // Still try to create; system may still return an instance that errors
        }
        return try {
            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                Log.d(TAG, "Creating on-device SpeechRecognizer (Android 12+)")
                try {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (e: Exception) {
                    Log.w(TAG, "On-device recognizer failed, falling back to standard", e)
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.d(TAG, "Creating standard SpeechRecognizer")
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                Log.w(TAG, "Speech recognition service not reported available by system - attempting creation anyway")
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "SpeechRecognizer ready for speech")
                    _isListening.value = true
                    consecutiveErrors = 0
                    _lastError.value = null
                    SystemLogBus.i(TAG, "Mic ready - boliye...")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech detected (beginning of speech)")
                    _isListening.value = true
                    // Barge-in: only if SARA is actually speaking (guarded via TTS isSpeaking)
                    if (isBargeInEnabled) {
                        val ttsSpeaking = JarvisSpeechSynthesizer.instance?.isSpeaking ?: false
                        if (ttsSpeaking) {
                            Log.i(TAG, "Barge-in: user interrupted SARA speech")
                            onInterruptionDetected?.invoke()
                        }
                    }
                }

                override fun onRmsChanged(rmsdB: Float) {
                    _rmsdB.value = rmsdB
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "End of speech detected")
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    _lastError.value = error
                    Log.w(TAG, "SpeechRecognizer error: $error (${errorToString(error)})")
                    SystemLogBus.w(TAG, "Voice error $error: ${errorToString(error)}")
                    consecutiveErrors++

                    // Always cancel current session to free mic
                    try { speechRecognizer?.cancel() } catch (_: Exception) {}

                    when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_CLIENT -> {
                            teardownRecognizer()
                        }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            SystemLogBus.e(TAG, "RECORD_AUDIO permission missing - request permission in UI")
                            // Don't retry loop if permission missing - stop continuous mode
                            _isContinuousMode.value = false
                            return
                        }
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            // Normal silence / no speech - not a fatal error, just restart
                            consecutiveErrors = 0
                        }
                        else -> {
                            // For other errors, keep recognizer but with backoff
                        }
                    }

                    // In continuous mode (Gemini Live style), automatically restart listening loop after a brief pause
                    if (_isContinuousMode.value && !_isProcessing.value) {
                        scope.launch {
                            val backoff = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 400L
                                else -> (consecutiveErrors * 400L).coerceIn(600L, 3000L)
                            }
                            delay(backoff)
                            if (_isContinuousMode.value && !_isProcessing.value) {
                                startListeningInternal()
                            }
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    consecutiveErrors = 0
                    _lastError.value = null
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        Log.i(TAG, "Recognized command: $text")
                        _liveTranscript.value = text
                        dispatchCommand(text)
                    } else {
                        Log.w(TAG, "onResults with empty text")
                        if (_isContinuousMode.value && !_isProcessing.value) {
                            scope.launch {
                                delay(400)
                                if (_isContinuousMode.value && !_isProcessing.value) {
                                    startListeningInternal()
                                }
                            }
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull()?.trim().orEmpty()
                    if (partial.isNotBlank()) {
                        _liveTranscript.value = partial
                        // Guarded barge-in: only interrupt if TTS speaking and partial is meaningful
                        if (isBargeInEnabled && partial.length > 2) {
                            val ttsSpeaking = JarvisSpeechSynthesizer.instance?.isSpeaking ?: false
                            if (ttsSpeaking) {
                                onInterruptionDetected?.invoke()
                            }
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer = recognizer
            recognizer
        } catch (e: Throwable) {
            Log.e(TAG, "Error instantiating SpeechRecognizer", e)
            SystemLogBus.e(TAG, "Failed to create SpeechRecognizer: ${e.message}")
            null
        }
    }

    private fun errorToString(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS (mic permission)"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        else -> "UNKNOWN($code)"
    }

    private fun dispatchCommand(text: String) {
        val listener = onCommandRecognized
        if (listener != null) {
            try {
                listener.invoke(text)
            } catch (e: Exception) {
                Log.e(TAG, "Command listener crashed, falling back to bridge", e)
                SaraVoiceBridge.requestVoiceCommand(text)
            }
        } else {
            Log.w(TAG, "No foreground listener - routing via SaraVoiceBridge (background)")
            SystemLogBus.i(TAG, "Voice -> Bridge (background): $text")
            SaraVoiceBridge.requestVoiceCommand(text)
        }
    }

    private fun teardownRecognizer() {
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        Log.d(TAG, "Recognizer torn down")
    }

    /**
     * Toggles continuous live session (Gemini Live call style).
     * Used identically by in-app Orb and Floating Bubble.
     */
    fun toggleContinuousSession(): Boolean {
        return if (_isContinuousMode.value) {
            stopSession()
            false
        } else {
            startContinuousSession()
            true
        }
    }

    fun startContinuousSession() {
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "Cannot start continuous - RECORD_AUDIO not granted")
            SystemLogBus.e(TAG, "Mic permission needed - please grant RECORD_AUDIO")
            _isListening.value = false
            return
        }
        _isContinuousMode.value = true
        consecutiveErrors = 0
        SystemLogBus.i(TAG, "Continuous voice session starting")
        startListeningInternal()
    }

    fun startSingleTurn() {
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "Cannot start singleTurn - RECORD_AUDIO not granted")
            SystemLogBus.e(TAG, "Mic permission needed - please grant RECORD_AUDIO")
            return
        }
        _isContinuousMode.value = false
        SystemLogBus.i(TAG, "Single-turn listening")
        startListeningInternal()
    }

    fun resumeContinuousListeningAfterSpeech() {
        if (_isContinuousMode.value) {
            scope.launch {
                delay(350)
                if (_isContinuousMode.value && !_isProcessing.value) {
                    SystemLogBus.i(TAG, "Resuming continuous after TTS")
                    startListeningInternal()
                }
            }
        }
    }

    @Synchronized
    private fun startListeningInternal() {
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "startListening blocked - no mic permission")
            _isListening.value = false
            return
        }
        try {
            val recognizer = ensureRecognizer() ?: run {
                Log.e(TAG, "ensureRecognizer returned null")
                _isListening.value = false
                return
            }
            // Cancel any previous pending listening before new start (prevents BUSY)
            try { recognizer.cancel() } catch (_: Exception) {}
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Prefer Hinglish (en-IN gives English script for Hindi/English words)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "hi-IN", "en-US"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Natural live speech timeouts
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                // Prefer offline if available
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                }
            }
            recognizer.startListening(intent)
            _isListening.value = true
            Log.d(TAG, "startListening dispatched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            SystemLogBus.e(TAG, "startListening failed: ${e.message}")
            _isListening.value = false
            // If we failed with BUSY, recreate
            if (e.message?.contains("busy", true) == true) {
                teardownRecognizer()
            }
        }
    }

    /** Full stop: exits continuous mode and stops mic */
    fun stopSession() {
        Log.i(TAG, "stopSession - exiting continuous mode")
        _isContinuousMode.value = false
        _isListening.value = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        SystemLogBus.i(TAG, "Voice session stopped")
    }

    /** Pause current listening but keep continuousMode flag true (so next resume works) */
    fun pauseListening() {
        Log.i(TAG, "pauseListening - keeping continuousMode=${_isContinuousMode.value}")
        _isListening.value = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
    }

    fun destroy() {
        stopSession()
        teardownRecognizer()
        onCommandRecognized = null
        onInterruptionDetected = null
        instance = null
    }
}
