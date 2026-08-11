package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.alarm.JarvisAlarmScheduler
import com.example.audio.JarvisSpeechSynthesizer
import com.example.data.db.InteractionLog
import com.example.data.db.JarvisAlarm
import com.example.data.db.JarvisDatabase
import com.example.data.db.UserMemory
import com.example.data.prefs.PreferencesManager
import com.example.engine.ActionParser
import com.example.engine.JarvisAction
import com.example.engine.LlmEngine
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisFloatingBubbleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = JarvisDatabase.getInstance(application)
    val prefs = PreferencesManager(application)
    private val llmEngine = LlmEngine(prefs)
    val tts = JarvisSpeechSynthesizer(application)

    private val speechRecognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(application)) {
            SpeechRecognizer.createSpeechRecognizer(application)
        } else null

    val interactionLogs = db.jarvisDao().getAllInteractionLogs()
    val userMemories = db.jarvisDao().getAllMemories()
    val jarvisAlarms = db.jarvisDao().getAllAlarms()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _jarvisResponse = MutableStateFlow("J.A.R.V.I.S. Neural Core initialized, Boss. Awaiting your command.")
    val jarvisResponse: StateFlow<String> = _jarvisResponse.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isAccessibilityOnline = MutableStateFlow(false)
    val isAccessibilityOnline: StateFlow<Boolean> = _isAccessibilityOnline.asStateFlow()

    private val _isOverlayAuthorized = MutableStateFlow(false)
    val isOverlayAuthorized: StateFlow<Boolean> = _isOverlayAuthorized.asStateFlow()

    private val _hasAnyKey = MutableStateFlow(prefs.hasAnyApiKey())
    val hasAnyKey: StateFlow<Boolean> = _hasAnyKey.asStateFlow()

    init {
        checkSystemPermissionsStatus()
        setupSpeechRecognizer()
    }

    fun checkSystemPermissionsStatus() {
        val context = getApplication<Application>()
        _isAccessibilityOnline.value = JarvisAccessibilityService.isOnline
        _isOverlayAuthorized.value = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
        _hasAnyKey.value = prefs.hasAnyApiKey()
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                _isListening.value = false
            }

            override fun onError(error: Int) {
                _isListening.value = false
                Log.e("MainViewModel", "Speech recognition error code: $error")
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val query = matches[0]
                    _recognizedText.value = query
                    executeCommand(query)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    _recognizedText.value = matches[0]
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun startListening() {
        if (!prefs.hasAnyApiKey()) {
            _jarvisResponse.value = "Boss, please configure at least one API key in the Brain tab before sending commands."
            tts.speak(_jarvisResponse.value)
            return
        }

        if (speechRecognizer != null) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN") // Native English & Hinglish support
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            }
            speechRecognizer.startListening(intent)
            _isListening.value = true
        } else {
            _jarvisResponse.value = "Speech recognition service unavailable on this device, Boss. Please use typed terminal input."
            tts.speak(_jarvisResponse.value)
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }

    fun executeCommand(userQuery: String) {
        if (userQuery.isBlank()) return
        _recognizedText.value = userQuery

        if (!prefs.hasAnyApiKey()) {
            _jarvisResponse.value = "Boss, please enter an API key in the Brain tab to activate J.A.R.V.I.S."
            tts.speak(_jarvisResponse.value)
            return
        }

        _isProcessing.value = true
        viewModelScope.launch {
            // Save User Log
            db.jarvisDao().insertLog(InteractionLog(text = userQuery, isUser = true))

            // Fetch Memories and Alarms Context
            val memoriesList = db.jarvisDao().getMemoriesList()
            val alarmsList = db.jarvisDao().getActiveAlarmsList()

            val memoriesStr = memoriesList.joinToString("\n") { "- ${it.fact} [${it.category}]" }
            val alarmsStr = alarmsList.joinToString("\n") { "- ${it.hour}:${it.minute} (${it.label})" }

            // Query LLM Cascade Engine
            val result = llmEngine.queryJarvis(userQuery, memoriesStr, alarmsStr)
            _isProcessing.value = false

            result.onSuccess { rawResponse ->
                val cleanText = ActionParser.stripActionTags(rawResponse)
                _jarvisResponse.value = cleanText

                // Speak response
                tts.speak(cleanText)

                // Save Jarvis Log
                db.jarvisDao().insertLog(InteractionLog(text = cleanText, isUser = false))

                // Parse and execute Action Tags
                val actions = ActionParser.parseActions(rawResponse)
                executeParsedActions(actions)

            }.onFailure { err ->
                val errText = err.message ?: "Neural logic pipeline failure, Boss."
                _jarvisResponse.value = errText
                tts.speak(errText)
            }
        }
    }

    private fun executeParsedActions(actions: List<JarvisAction>) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            for (action in actions) {
                when (action) {
                    is JarvisAction.RememberAction -> {
                        db.jarvisDao().insertMemory(
                            UserMemory(fact = action.fact, category = action.category)
                        )
                    }
                    is JarvisAction.SetAlarmAction -> {
                        val alarmId = (db.jarvisDao().insertAlarm(
                            JarvisAlarm(hour = action.hour, minute = action.minute, label = action.label)
                        )).toInt()

                        JarvisAlarmScheduler.scheduleAlarm(
                            context,
                            action.hour,
                            action.minute,
                            action.label,
                            alarmId
                        )
                    }
                    is JarvisAction.AccessibilityAction -> {
                        val service = JarvisAccessibilityService.instance
                        if (service != null) {
                            when (action.actionName.uppercase()) {
                                "HOME" -> service.performHome()
                                "BACK" -> service.performBack()
                                "SCROLL_DOWN" -> service.scrollDown()
                                "SCROLL_UP" -> service.scrollUp()
                                "TYPE" -> service.typeTextInFocusedInput(action.text)
                            }
                        }
                    }
                    is JarvisAction.OpenAppAction -> {
                        try {
                            val pm = context.packageManager
                            val intent = pm.getLaunchIntentForPackage(action.packageName)
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    fun saveApiKeys(groq: String, gemini: String, openRouter: String, preferredLlm: String) {
        prefs.groqApiKey = groq.trim()
        prefs.geminiApiKey = gemini.trim()
        prefs.openRouterApiKey = openRouter.trim()
        prefs.preferredLlm = preferredLlm
        _hasAnyKey.value = prefs.hasAnyApiKey()
        _jarvisResponse.value = "Neural credentials updated successfully, Sir."
        tts.speak(_jarvisResponse.value)
    }

    fun addManualMemory(fact: String, category: String) {
        if (fact.isBlank()) return
        viewModelScope.launch {
            db.jarvisDao().insertMemory(UserMemory(fact = fact, category = category))
        }
    }

    fun deleteMemory(memory: UserMemory) {
        viewModelScope.launch {
            db.jarvisDao().deleteMemory(memory)
        }
    }

    fun toggleAlarmActive(alarm: JarvisAlarm) {
        viewModelScope.launch {
            val updated = alarm.copy(isActive = !alarm.isActive)
            db.jarvisDao().updateAlarm(updated)
            val context = getApplication<Application>()
            if (updated.isActive) {
                JarvisAlarmScheduler.scheduleAlarm(context, updated.hour, updated.minute, updated.label, updated.id.toInt())
            } else {
                JarvisAlarmScheduler.cancelAlarm(context, updated.hour, updated.minute, updated.id.toInt())
            }
        }
    }

    fun addManualAlarm(hour: Int, minute: Int, label: String) {
        viewModelScope.launch {
            val alarm = JarvisAlarm(hour = hour, minute = minute, label = label, isActive = true)
            val id = db.jarvisDao().insertAlarm(alarm).toInt()
            val context = getApplication<Application>()
            JarvisAlarmScheduler.scheduleAlarm(context, hour, minute, label, id)
        }
    }

    fun deleteAlarm(alarm: JarvisAlarm) {
        viewModelScope.launch {
            db.jarvisDao().deleteAlarm(alarm)
            val context = getApplication<Application>()
            JarvisAlarmScheduler.cancelAlarm(context, alarm.hour, alarm.minute, alarm.id.toInt())
        }
    }

    fun toggleFloatingBubble(context: Context) {
        val newState = !prefs.isFloatingBubbleEnabled
        prefs.isFloatingBubbleEnabled = newState
        val intent = Intent(context, JarvisFloatingBubbleService::class.java)

        if (newState) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
        checkSystemPermissionsStatus()
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        tts.shutdown()
    }
}
