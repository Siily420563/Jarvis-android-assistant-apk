package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.alarm.JarvisAlarmScheduler
import com.example.audio.JarvisSpeechSynthesizer
import com.example.data.db.InteractionLog
import com.example.data.db.JarvisAlarm
import com.example.data.db.JarvisDatabase
import com.example.data.db.MacroCache
import com.example.data.db.UserMemory
import com.example.data.prefs.PreferencesManager
import com.example.engine.FastPathClassifier
import com.example.engine.FastPathResult
import com.example.engine.LlmEngine
import com.example.engine.TaskExecutor
import com.example.engine.TaskPlan
import com.example.persona.PersonaType
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisFloatingBubbleService
import com.example.util.DeviceActionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = JarvisDatabase.getInstance(application)
    val prefs = PreferencesManager(application)
    val llmEngine = LlmEngine(prefs)
    val tts = JarvisSpeechSynthesizer(application)
    val executor = TaskExecutor(application, db, llmEngine)

    private var speechRecognizer: SpeechRecognizer? = null

    val interactionLogs = db.jarvisDao().getAllInteractionLogs()
    val userMemories = db.jarvisDao().getAllMemories()
    val jarvisAlarms = db.jarvisDao().getAllAlarms()
    val cachedMacros = db.jarvisDao().getAllMacros()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _saraResponse = MutableStateFlow("SARA voice assistant active. Awaiting your command in Hinglish!")
    val saraResponse: StateFlow<String> = _saraResponse.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _activePersona = MutableStateFlow(prefs.activePersona)
    val activePersona: StateFlow<PersonaType> = _activePersona.asStateFlow()

    private val _currentTaskPlan = MutableStateFlow<TaskPlan?>(null)
    val currentTaskPlan: StateFlow<TaskPlan?> = _currentTaskPlan.asStateFlow()

    private val _isAccessibilityOnline = MutableStateFlow(false)
    val isAccessibilityOnline: StateFlow<Boolean> = _isAccessibilityOnline.asStateFlow()

    private val _isOverlayAuthorized = MutableStateFlow(false)
    val isOverlayAuthorized: StateFlow<Boolean> = _isOverlayAuthorized.asStateFlow()

    private val _isBatteryExempted = MutableStateFlow(false)
    val isBatteryExempted: StateFlow<Boolean> = _isBatteryExempted.asStateFlow()

    private val _isMicGranted = MutableStateFlow(false)
    val isMicGranted: StateFlow<Boolean> = _isMicGranted.asStateFlow()

    private val _isPhoneContactsGranted = MutableStateFlow(false)
    val isPhoneContactsGranted: StateFlow<Boolean> = _isPhoneContactsGranted.asStateFlow()

    private val _hasAnyKey = MutableStateFlow(prefs.hasAnyApiKey())
    val hasAnyKey: StateFlow<Boolean> = _hasAnyKey.asStateFlow()

    // Confirmation for risky actions (Payments / Deletions)
    private val _pendingRiskyPlan = MutableStateFlow<TaskPlan?>(null)
    val pendingRiskyPlan: StateFlow<TaskPlan?> = _pendingRiskyPlan.asStateFlow()

    init {
        checkSystemPermissionsStatus()
        setInitialGreeting()
    }

    private fun setInitialGreeting() {
        val greeting = when (prefs.activePersona) {
            PersonaType.GIRLFRIEND -> "Arey suno na! Main SARA hoon, aapki AI assistant. Batao aaj kya karun aapke liye? 💕"
            PersonaType.PROFESSIONAL -> "SARA Voice Core initialized, Sir. Ready to execute multi-step automations."
            PersonaType.BOLD -> "SARA is ready! Sidha bolo kya kaam karwana hai."
        }
        _saraResponse.value = greeting
    }

    fun checkSystemPermissionsStatus() {
        val context = getApplication<Application>()
        _isAccessibilityOnline.value = JarvisAccessibilityService.isOnline
        _isOverlayAuthorized.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
        _isBatteryExempted.value = DeviceActionHelper.isBatteryOptimizationIgnored(context)
        _isMicGranted.value = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        _isPhoneContactsGranted.value = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        _hasAnyKey.value = prefs.hasAnyApiKey()
        _activePersona.value = prefs.activePersona
    }

    private var isContinuousListeningMode = false
    private var singleTurnCallback: ((String) -> Unit)? = null

    private fun getOrCreateSpeechRecognizer(): SpeechRecognizer? {
        if (speechRecognizer != null) return speechRecognizer
        val context = getApplication<Application>()
        return try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                        _saraResponse.value = if (isContinuousListeningMode) "Continuous Voice Active: Sun rahi hoon... (Boliye!) 🎤" else "Sun rahi hoon... Boliye! 🎤"
                    }

                    override fun onBeginningOfSpeech() {
                        _isListening.value = true
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        _isListening.value = false
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        Log.e("MainViewModel", "Speech recognition error code: $error")
                        val isGf = prefs.activePersona == PersonaType.GIRLFRIEND
                        val hint = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                if (isGf) "Aapki awaaz nahi aayi! Quick command tap karo ya type karke batao 💕" else "Awaaz detect nahi hui. Neeche commands tap karein ya text type karein."
                            SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT ->
                                "Microphone stream active nahi hai. Neeche commands tap karein ya text likhein!"
                            else ->
                                "Voice standby mode. Quick chip tap karein ya direct message type karein!"
                        }
                        _saraResponse.value = hint

                        if (isContinuousListeningMode) {
                            viewModelScope.launch {
                                kotlinx.coroutines.delay(1200)
                                if (isContinuousListeningMode) {
                                    startListeningInternal()
                                }
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val query = matches[0]
                            _recognizedText.value = query
                            
                            val callback = singleTurnCallback
                            singleTurnCallback = null
                            if (callback != null) {
                                callback(query)
                            } else {
                                executeUserCommand(query)
                            }
                        } else {
                            if (isContinuousListeningMode) {
                                viewModelScope.launch {
                                    kotlinx.coroutines.delay(800)
                                    if (isContinuousListeningMode) {
                                        startListeningInternal()
                                    }
                                }
                            }
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
                speechRecognizer = recognizer
                recognizer
            } else null
        } catch (e: Throwable) {
            Log.w("MainViewModel", "SpeechRecognizer not available: ${e.message}")
            null
        }
    }

    private fun startListeningInternal() {
        val recognizer = getOrCreateSpeechRecognizer()
        if (recognizer != null) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "hi-IN", "en-US"))
            }
            try {
                recognizer.startListening(intent)
                _isListening.value = true
            } catch (e: Exception) {
                _isListening.value = false
            }
        }
    }

    fun startSingleTurnMic(onTextRecognized: (String) -> Unit) {
        isContinuousListeningMode = false
        singleTurnCallback = onTextRecognized
        startListeningInternal()
    }

    fun toggleContinuousOrbMode() {
        if (isContinuousListeningMode) {
            isContinuousListeningMode = false
            singleTurnCallback = null
            stopListening()
            _saraResponse.value = "Continuous Voice Mode off ho gaya. Tap Orb to reactivate! ✨"
        } else {
            isContinuousListeningMode = true
            singleTurnCallback = null
            startListeningInternal()
            _saraResponse.value = "Continuous Voice Mode Active! Bolte rahiye, SARA sun rahi hai... 🎤"
        }
    }

    fun startListening() {
        isContinuousListeningMode = false
        singleTurnCallback = null
        startListeningInternal()
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }

    private fun speakAndPromptNext(text: String, onSpeechFinished: (() -> Unit)? = null) {
        tts.speak(text, apiKey = prefs.geminiApiKey) {
            onSpeechFinished?.invoke()
            if (isContinuousListeningMode) {
                viewModelScope.launch {
                    kotlinx.coroutines.delay(800)
                    if (isContinuousListeningMode) {
                        startListeningInternal()
                    }
                }
            }
        }
    }

    fun executeUserCommand(query: String) {
        if (query.isBlank()) return
        _recognizedText.value = query
        _pendingRiskyPlan.value = null

        viewModelScope.launch {
            // Save User Interaction
            db.jarvisDao().insertLog(InteractionLog(text = query, isUser = true))

            // 1. Check FastPath Classifier for instant response
            val fastPath = FastPathClassifier.classify(query, prefs.activePersona, prefs.assistantName)
            if (fastPath is FastPathResult.Handled) {
                if (fastPath.switchPersona != null) {
                    setPersona(fastPath.switchPersona)
                }
                _saraResponse.value = fastPath.immediateReplyHinglish
                speakAndPromptNext(fastPath.immediateReplyHinglish)
                db.jarvisDao().insertLog(InteractionLog(text = fastPath.immediateReplyHinglish, isUser = false))

                if (fastPath.plan.steps.isNotEmpty()) {
                    _currentTaskPlan.value = fastPath.plan
                    executor.executePlan(
                        plan = fastPath.plan,
                        onStepUpdated = { _currentTaskPlan.value = it },
                        onSpeak = { speakAndPromptNext(it) }
                    )
                }
                return@launch
            }

            // 2. Check Macro Cache for instant repeat execution
            val normalizedKey = query.trim().uppercase().replace(" ", "_")
            val cachedMacro = db.jarvisDao().findMacroByIntent(normalizedKey)
            if (cachedMacro != null) {
                val cachedPlan = TaskPlan.fromJsonString(cachedMacro.taskGraphJson)
                if (cachedPlan != null) {
                    val response = cachedPlan.speechResponseHinglish.ifBlank { "Task execute kar rahe hain..." }
                    _saraResponse.value = response
                    _currentTaskPlan.value = cachedPlan
                    speakAndPromptNext(response)
                    executor.executePlan(
                        plan = cachedPlan,
                        onStepUpdated = { _currentTaskPlan.value = it },
                        onSpeak = { speakAndPromptNext(it) }
                    )
                    return@launch
                }
            }

            // 3. Fallback to Planner + LLM Cascade Engine
            _isProcessing.value = true

            val memoriesList = db.jarvisDao().getMemoriesList()
            val alarmsList = db.jarvisDao().getActiveAlarmsList()
            val memoriesStr = memoriesList.joinToString("\n") { "- ${it.fact} [${it.category}]" }
            val alarmsStr = alarmsList.joinToString("\n") { "- ${it.hour}:${it.minute} (${it.label})" }
            val screenContext = JarvisAccessibilityService.instance?.getScreenHierarchySummary() ?: ""

            val planResult = llmEngine.planAndQuery(query, memoriesStr, alarmsStr, screenContext)
            _isProcessing.value = false

            planResult.onSuccess { plan ->
                _currentTaskPlan.value = plan
                _saraResponse.value = plan.speechResponseHinglish

                db.jarvisDao().insertLog(InteractionLog(text = plan.speechResponseHinglish, isUser = false))

                if (plan.requiresRiskyConfirmation) {
                    _pendingRiskyPlan.value = plan
                    speakAndPromptNext(plan.confirmationPrompt)
                } else {
                    speakAndPromptNext(plan.speechResponseHinglish)
                    if (plan.steps.isNotEmpty()) {
                        executor.executePlan(
                            plan = plan,
                            onStepUpdated = { _currentTaskPlan.value = it },
                            onSpeak = { speakAndPromptNext(it) }
                        )
                    }
                }
            }.onFailure { err ->
                val errorHinglish = "Kuch issue hua: ${err.message ?: "Connection error"}. Kya aap dobara bol sakte hain?"
                _saraResponse.value = errorHinglish
                speakAndPromptNext(errorHinglish)
            }
        }
    }

    fun confirmPendingRiskyPlan(confirmed: Boolean) {
        val plan = _pendingRiskyPlan.value ?: return
        _pendingRiskyPlan.value = null
        if (confirmed) {
            viewModelScope.launch {
                executor.proceedExecution(
                    plan = plan,
                    onStepUpdated = { _currentTaskPlan.value = it },
                    onSpeak = { tts.speak(it) }
                )
            }
        } else {
            val cancelReply = when (prefs.activePersona) {
                PersonaType.GIRLFRIEND -> "Theek hai, maine cancel kar diya! Chinta mat karo ❤️"
                PersonaType.PROFESSIONAL -> "Operation cancelled as requested, Sir."
                PersonaType.BOLD -> "Cancel kar diya. Safe side!"
            }
            _saraResponse.value = cancelReply
            tts.speak(cancelReply)
        }
    }

    fun setPersona(persona: PersonaType) {
        prefs.activePersona = persona
        _activePersona.value = persona
    }

    fun saveSettings(
        geminiKey: String,
        groqKey: String,
        openRouterKey: String,
        preferredLlm: String,
        assistantName: String,
        persona: PersonaType,
        geminiModel: String = "gemini-3.7-flash",
        groqModel: String = "llama-3.3-70b-versatile",
        openRouterModel: String = "anthropic/claude-3.7-sonnet"
    ) {
        prefs.geminiApiKey = geminiKey.trim()
        prefs.groqApiKey = groqKey.trim()
        prefs.openRouterApiKey = openRouterKey.trim()
        prefs.geminiModel = geminiModel.trim().ifBlank { "gemini-3.7-flash" }
        prefs.groqModel = groqModel.trim().ifBlank { "llama-3.3-70b-versatile" }
        prefs.openRouterModel = openRouterModel.trim().ifBlank { "anthropic/claude-3.7-sonnet" }
        prefs.preferredLlm = preferredLlm
        prefs.assistantName = assistantName.trim().ifBlank { "SARA" }
        prefs.activePersona = persona
        _activePersona.value = persona
        _hasAnyKey.value = prefs.hasAnyApiKey()

        val reply = "Settings update ho gayi hain! SARA is configured with the latest smart models."
        _saraResponse.value = reply
        tts.speak(reply)
    }

    fun toggleFloatingBubble(context: Context) {
        val newState = !prefs.isFloatingBubbleEnabled
        prefs.isFloatingBubbleEnabled = newState
        val intent = Intent(context, JarvisFloatingBubbleService::class.java)

        if (newState) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
        checkSystemPermissionsStatus()
    }

    fun requestBatteryOptimization(context: Context) {
        DeviceActionHelper.requestIgnoreBatteryOptimization(context)
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

    fun runMacroDirect(macro: MacroCache) {
        val plan = TaskPlan.fromJsonString(macro.taskGraphJson) ?: return
        _recognizedText.value = macro.taskDescription
        _saraResponse.value = "Executing cached macro: ${macro.taskDescription}"
        _currentTaskPlan.value = plan
        viewModelScope.launch {
            executor.executePlan(
                plan = plan,
                onStepUpdated = { _currentTaskPlan.value = it },
                onSpeak = { tts.speak(it) }
            )
        }
    }

    fun deleteMacro(macro: MacroCache) {
        viewModelScope.launch {
            db.jarvisDao().deleteMacro(macro)
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        tts.shutdown()
    }
}
