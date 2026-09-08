package com.example.audio

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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

    private var speechRecognizer: SpeechRecognizer? = null
    private var onCommandRecognized: ((String) -> Unit)? = null
    private var onInterruptionDetected: (() -> Unit)? = null

    private var consecutiveErrors = 0
    private var isBargeInEnabled = true

    fun setCommandListener(listener: (String) -> Unit) {
        this.onCommandRecognized = listener
    }

    fun setInterruptionListener(listener: () -> Unit) {
        this.onInterruptionDetected = listener
    }

    fun setProcessing(processing: Boolean) {
        _isProcessing.value = processing
    }

    @Synchronized
    private fun ensureRecognizer(): SpeechRecognizer? {
        if (speechRecognizer != null) return speechRecognizer
        return try {
            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                Log.d(TAG, "Creating on-device SpeechRecognizer (Android 12+)")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.d(TAG, "Creating standard SpeechRecognizer")
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                Log.w(TAG, "Speech recognition service not reported available by system")
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "SpeechRecognizer ready for speech")
                    _isListening.value = true
                    consecutiveErrors = 0
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech detected (beginning of speech)")
                    _isListening.value = true
                    // Barge-in: if SARA is speaking while user begins talking, cut SARA off immediately
                    if (isBargeInEnabled) {
                        onInterruptionDetected?.invoke()
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
                    Log.w(TAG, "SpeechRecognizer error: $error")
                    consecutiveErrors++

                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                        teardownRecognizer()
                    }

                    // In continuous mode (Gemini Live style), automatically restart listening loop after a brief pause
                    if (_isContinuousMode.value && !_isProcessing.value) {
                        scope.launch {
                            val backoff = (consecutiveErrors * 400L).coerceIn(600L, 2500L)
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
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        Log.i(TAG, "Recognized command: $text")
                        _liveTranscript.value = text
                        onCommandRecognized?.invoke(text)
                    } else if (_isContinuousMode.value && !_isProcessing.value) {
                        // Restart listening in continuous mode
                        scope.launch {
                            delay(400)
                            if (_isContinuousMode.value && !_isProcessing.value) {
                                startListeningInternal()
                            }
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull()?.trim().orEmpty()
                    if (partial.isNotBlank()) {
                        _liveTranscript.value = partial
                        if (isBargeInEnabled) {
                            onInterruptionDetected?.invoke()
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer = recognizer
            recognizer
        } catch (e: Throwable) {
            Log.e(TAG, "Error instantiating SpeechRecognizer", e)
            null
        }
    }

    private fun teardownRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
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
        _isContinuousMode.value = true
        consecutiveErrors = 0
        startListeningInternal()
    }

    fun startSingleTurn() {
        _isContinuousMode.value = false
        startListeningInternal()
    }

    fun resumeContinuousListeningAfterSpeech() {
        if (_isContinuousMode.value) {
            scope.launch {
                delay(300)
                if (_isContinuousMode.value && !_isProcessing.value) {
                    startListeningInternal()
                }
            }
        }
    }

    @Synchronized
    private fun startListeningInternal() {
        try {
            val recognizer = ensureRecognizer() ?: return
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Prefer Hinglish (en-IN gives English script for Hindi/English words)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "hi-IN", "en-US"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Natural live speech timeouts
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600L)
            }
            recognizer.startListening(intent)
            _isListening.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            _isListening.value = false
        }
    }

    fun stopSession() {
        _isContinuousMode.value = false
        _isListening.value = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
    }

    fun pauseListening() {
        _isListening.value = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
    }
}
