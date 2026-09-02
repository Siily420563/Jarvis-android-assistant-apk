package com.example.engine

import android.content.Context
import android.util.Log
import com.example.alarm.JarvisAlarmScheduler
import com.example.data.db.JarvisAlarm
import com.example.data.db.JarvisDatabase
import com.example.data.db.MacroCache
import com.example.data.db.UserMemory
import com.example.service.JarvisAccessibilityService
import com.example.util.DeviceActionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed class ExecutionState {
    object Idle : ExecutionState()
    data class Running(val plan: TaskPlan, val currentStepIndex: Int) : ExecutionState()
    data class Completed(val plan: TaskPlan, val messageHinglish: String) : ExecutionState()
    data class Failed(val plan: TaskPlan, val failedStep: TaskStep, val errorHinglish: String) : ExecutionState()
    data class AwaitingConfirmation(val plan: TaskPlan, val promptHinglish: String) : ExecutionState()
    data class Interrupted(val state: InterruptedTaskState, val messageHinglish: String) : ExecutionState()
}

class TaskExecutor(
    private val context: Context,
    private val db: JarvisDatabase,
    private val llmEngine: LlmEngine
) {

    private val _executionState = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    val executionState: StateFlow<ExecutionState> = _executionState.asStateFlow()

    @Volatile
    var currentlyExecutingPlan: TaskPlan? = null
        private set

    @Volatile
    var currentStepIndex: Int = 0
        private set

    // Shared context between steps in a single plan (e.g. found phone number)
    private val stepContext = mutableMapOf<String, String>()

    fun resetActivePlan() {
        currentlyExecutingPlan = null
        currentStepIndex = 0
        _executionState.value = ExecutionState.Idle
    }

    fun interruptCurrentExecution(): InterruptedTaskState? {
        val plan = currentlyExecutingPlan ?: return null
        val idx = currentStepIndex
        val state = InterruptedTaskState(plan, idx)
        _executionState.value = ExecutionState.Interrupted(state, "Task '${plan.originalQuery}' ruk gaya.")
        currentlyExecutingPlan = null
        currentStepIndex = 0
        return state
    }

    suspend fun executePlan(
        plan: TaskPlan,
        onStepUpdated: (TaskPlan) -> Unit,
        onSpeak: (String) -> Unit
    ) {
        stepContext.clear()

        // 1. Check if Risky Confirmation is required (Payments / Deletions)
        if (plan.requiresRiskyConfirmation && plan.confirmationPrompt.isNotBlank()) {
            _executionState.value = ExecutionState.AwaitingConfirmation(plan, plan.confirmationPrompt)
            onSpeak(plan.confirmationPrompt)
            return
        }

        proceedExecution(plan, onStepUpdated, onSpeak)
    }

    suspend fun proceedExecution(
        plan: TaskPlan,
        onStepUpdated: (TaskPlan) -> Unit,
        onSpeak: (String) -> Unit
    ) {
        if (plan.steps.isEmpty()) {
            // Pure conversational response
            _executionState.value = ExecutionState.Completed(plan, plan.speechResponseHinglish)
            onSpeak(plan.speechResponseHinglish)
            return
        }

        // Announce initial Hinglish speech response
        if (plan.speechResponseHinglish.isNotBlank()) {
            onSpeak(plan.speechResponseHinglish)
        }

        currentlyExecutingPlan = plan
        try {
            for (index in plan.steps.indices) {
                currentStepIndex = index
                val step = plan.steps[index]
                step.status = StepStatus.RUNNING
                _executionState.value = ExecutionState.Running(plan, index)
                onStepUpdated(plan)

                delay(350) // Natural pacing between automation steps

                var success = executeSingleStep(step)
                if (!success) {
                    // Retry once
                    step.status = StepStatus.RETRYING
                    step.retryCount = 1
                    onStepUpdated(plan)
                    delay(600)
                    success = executeSingleStep(step)
                }

                if (success) {
                    step.status = StepStatus.SUCCESS
                    onStepUpdated(plan)
                } else {
                    step.status = StepStatus.FAILED
                    step.errorMessage = "Failed after retry"
                    onStepUpdated(plan)

                    val errorHinglish = "Sorry, '${step.descriptionHinglish}' execute nahi ho paya. Kya main dobara try karun?"
                    _executionState.value = ExecutionState.Failed(plan, step, errorHinglish)
                    onSpeak(errorHinglish)
                    return
                }
            }

            // All steps succeeded -> Cache in Macro DB if multi-step!
            if (plan.steps.size >= 2 && plan.intentKey.isNotBlank()) {
                try {
                    val existing = db.jarvisDao().findMacroByIntent(plan.intentKey)
                    if (existing != null) {
                        db.jarvisDao().insertMacro(
                            existing.copy(
                                executionCount = existing.executionCount + 1,
                                lastExecuted = System.currentTimeMillis()
                            )
                        )
                    } else {
                        db.jarvisDao().insertMacro(
                            MacroCache(
                                intentKey = plan.intentKey,
                                taskDescription = plan.originalQuery,
                                taskGraphJson = plan.toJsonString(),
                                executionCount = 1
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("TaskExecutor", "Error caching macro", e)
                }
            }

            val successMsg = plan.speechResponseHinglish.ifBlank { "Kaam ho gaya! Sab steps successfully complete ho gaye. ✨" }
            _executionState.value = ExecutionState.Completed(plan, successMsg)
        } finally {
            currentlyExecutingPlan = null
            currentStepIndex = 0
        }
    }

    private suspend fun executeSingleStep(step: TaskStep): Boolean {
        return try {
            when (step.type) {
                StepType.FIND_CONTACT -> {
                    val name = step.params["name"] ?: ""
                    val contact = DeviceActionHelper.findContactByName(context, name)
                    if (contact != null) {
                        stepContext["phoneNumber"] = contact.phoneNumber
                        stepContext["contactName"] = contact.name
                        true
                    } else {
                        // Fallback: proceed even if contact lookup fails
                        stepContext["phoneNumber"] = ""
                        stepContext["contactName"] = name
                        true
                    }
                }

                StepType.OPEN_APP -> {
                    val packageName = step.params["packageName"] ?: ""
                    val appName = step.params["appName"] ?: ""
                    val query = step.params["query"] ?: ""
                    val target = if (packageName.isNotBlank()) packageName else appName

                    if (target.lowercase().contains("youtube") && query.isNotBlank()) {
                        DeviceActionHelper.searchAndPlayYouTube(context, query)
                    } else if (target.isNotBlank()) {
                        DeviceActionHelper.launchAppByName(context, target)
                    } else {
                        DeviceActionHelper.launchAppByName(context, "youtube")
                    }
                }

                StepType.SEND_WHATSAPP -> {
                    val phone = step.params["phoneNumber"] ?: stepContext["phoneNumber"]
                    val contactName = step.params["contactName"] ?: step.params["name"] ?: stepContext["contactName"] ?: ""
                    val message = step.params["message"] ?: "Hello"
                    val autoSend = step.params["autoSend"]?.toBoolean() ?: true

                    if (!phone.isNullOrBlank()) {
                        DeviceActionHelper.openWhatsAppChat(context, phone, message)
                        if (autoSend && JarvisAccessibilityService.isOnline) {
                            delay(1000)
                            val service = JarvisAccessibilityService.instance
                            if (service != null) {
                                // Attempt clicking the Send button in WhatsApp
                                val sent = service.clickNodeByText("Send") || 
                                           service.clickNodeByText("भेजें") || 
                                           service.clickNodeByText("send")
                                if (!sent) {
                                    // Try common send icon coordinates on standard screens if needed
                                    val nodes = service.dumpScreenHierarchy()
                                    val sendNode = nodes.firstOrNull { 
                                        it.contentDescription.contains("Send", ignoreCase = true) || 
                                        it.contentDescription.contains("भेजें", ignoreCase = true) ||
                                        it.viewId.contains("send", ignoreCase = true)
                                    }
                                    if (sendNode != null && !sendNode.bounds.isEmpty) {
                                        service.clickCoordinates(sendNode.bounds.centerX().toFloat(), sendNode.bounds.centerY().toFloat())
                                    }
                                }
                            }
                        }
                        true
                    } else {
                        // Contact not in address book -> Open WhatsApp and use accessibility automation to search and send.
                        // Every step below is now VERIFIED before we move to the next one — this is what was
                        // missing before, and is exactly why a message could end up typed into the wrong
                        // field (e.g. the search box, or a "Meta AI" entry) instead of the intended chat.
                        DeviceActionHelper.launchAppByName(context, "whatsapp")
                        val service = JarvisAccessibilityService.instance
                        if (service == null || contactName.isBlank()) {
                            return true
                        }

                        delay(1200)

                        // 1. Open search - if we can't even find the search entry, stop here.
                        val searchOpened = service.clickNodeByText("Search") ||
                                service.clickNodeByText("खोजें") ||
                                service.clickNodeByText("search")
                        if (!searchOpened) {
                            Log.e("TaskExecutor", "WhatsApp: could not open Search")
                            return false
                        }
                        delay(700)

                        // 2. Type contact name into search
                        service.inputText(contactName)
                        delay(900)

                        // 3. Click the matched contact result - CHECK that the click actually landed.
                        val contactClicked = service.clickNodeByText(contactName)
                        if (!contactClicked) {
                            Log.e("TaskExecutor", "WhatsApp: could not find/click contact '$contactName' in search results")
                            return false
                        }
                        delay(900)

                        // 3b. VERIFY we actually navigated into a chat screen (it must now have an
                        // editable message box) before we type anything. This is the check that was
                        // missing before - without it, a failed contact-click was silently ignored
                        // and the message got typed into whatever was still focused (the search bar).
                        var enteredChat = service.dumpScreenHierarchy().any { it.isEditable }
                        if (!enteredChat) {
                            delay(700)
                            enteredChat = service.dumpScreenHierarchy().any { it.isEditable }
                        }
                        if (!enteredChat) {
                            Log.e("TaskExecutor", "WhatsApp: did not land inside a chat for '$contactName' - aborting instead of guessing")
                            return false
                        }

                        // 4. Type the message - only now that we've verified we're in the right chat.
                        val typed = service.inputText(message)
                        if (!typed) {
                            Log.e("TaskExecutor", "WhatsApp: failed to type message for '$contactName'")
                            return false
                        }
                        delay(500)

                        // 5. Click Send
                        val sendClicked = service.clickNodeByText("Send") ||
                                service.clickNodeByText("भेजें") ||
                                service.clickNodeByText("send")
                        if (!sendClicked) {
                            Log.e("TaskExecutor", "WhatsApp: could not find Send button for '$contactName'")
                            return false
                        }
                        true
                    }
                }

                StepType.TOGGLE_TORCH -> {
                    val state = step.params["state"]?.uppercase() ?: "ON"
                    val enable = state != "OFF" && state != "FALSE"
                    DeviceActionHelper.setTorchMode(context, enable)
                }

                StepType.CONTROL_VOLUME -> {
                    val direction = step.params["direction"] ?: "UP"
                    val percentStr = step.params["percent"]
                    val streamType = step.params["stream"] ?: "MEDIA"
                    if (!percentStr.isNullOrBlank()) {
                        val percent = percentStr.toIntOrNull() ?: 50
                        DeviceActionHelper.setVolumePercent(context, percent, streamType)
                    } else {
                        DeviceActionHelper.adjustVolume(context, direction, streamType)
                    }
                }

                StepType.OPEN_QUICK_SETTING -> {
                    val panel = step.params["panel"] ?: step.params["type"] ?: "SETTINGS"
                    DeviceActionHelper.openQuickSettingPanel(context, panel)
                }

                StepType.MEDIA_CONTROL -> {
                    val action = step.params["action"] ?: "PLAY_PAUSE"
                    DeviceActionHelper.controlMediaPlayback(context, action)
                }

                StepType.NAVIGATE_TO -> {
                    val dest = step.params["destination"] ?: step.params["query"] ?: ""
                    if (dest.isNotBlank()) {
                        DeviceActionHelper.navigateToDestination(context, dest)
                    } else true
                }

                StepType.CHECK_BATTERY -> {
                    val status = DeviceActionHelper.getBatteryStatus(context)
                    stepContext["battery_pct"] = status.percentage.toString()
                    stepContext["battery_charging"] = status.isCharging.toString()
                    true
                }

                StepType.OPEN_CAMERA -> {
                    val mode = step.params["mode"] ?: "PHOTO"
                    DeviceActionHelper.launchCameraMode(context, mode)
                }

                StepType.SEARCH_WEB -> {
                    val query = step.params["query"] ?: ""
                    if (query.isNotBlank()) {
                        DeviceActionHelper.searchGoogle(context, query)
                    } else true
                }

                StepType.SEND_SMS -> {
                    val phone = step.params["phoneNumber"] ?: stepContext["phoneNumber"] ?: ""
                    val message = step.params["message"] ?: ""
                    if (phone.isNotBlank()) {
                        DeviceActionHelper.sendSmsDirect(context, phone, message)
                    } else true
                }

                StepType.CALL_PHONE -> {
                    val phone = step.params["phoneNumber"] ?: stepContext["phoneNumber"] ?: ""
                    if (phone.isNotBlank()) {
                        DeviceActionHelper.makePhoneCall(context, phone)
                    } else true
                }

                StepType.ACCESSIBILITY_TAP_TEXT -> {
                    val text = step.params["text"] ?: ""
                    val service = JarvisAccessibilityService.instance
                    if (service != null && text.isNotBlank()) {
                        service.clickNodeByText(text)
                    } else true
                }

                StepType.ACCESSIBILITY_TAP_COORDS -> {
                    val x = step.params["x"]?.toFloatOrNull() ?: 500f
                    val y = step.params["y"]?.toFloatOrNull() ?: 1000f
                    val service = JarvisAccessibilityService.instance
                    if (service != null) {
                        service.clickCoordinates(x, y)
                    } else true
                }

                StepType.ACCESSIBILITY_TYPE -> {
                    val text = step.params["text"] ?: ""
                    val targetHint = step.params["targetHint"]
                    val service = JarvisAccessibilityService.instance
                    if (service != null && text.isNotBlank()) {
                        service.inputText(text, targetHint)
                    } else true
                }

                StepType.ACCESSIBILITY_GLOBAL -> {
                    val action = step.params["action"]?.uppercase() ?: "HOME"
                    val service = JarvisAccessibilityService.instance
                    if (service != null) {
                        when (action) {
                            "HOME" -> service.performHome()
                            "BACK" -> service.performBack()
                            "RECENTS" -> service.performRecents()
                            "NOTIFICATIONS" -> service.performNotifications()
                            "QUICK_SETTINGS" -> service.performQuickSettings()
                            "SCREENSHOT" -> service.performScreenshot()
                            else -> service.performHome()
                        }
                    } else true
                }

                StepType.ACCESSIBILITY_SCROLL -> {
                    val dir = step.params["direction"]?.uppercase() ?: "DOWN"
                    val service = JarvisAccessibilityService.instance
                    if (service != null) {
                        if (dir == "UP") service.scrollUp() else service.scrollDown()
                    } else true
                }

                StepType.VISION_INSPECT_AND_TAP -> {
                    val service = JarvisAccessibilityService.instance
                    val targetDesc = step.params["description"] ?: "button"
                    if (service != null) {
                        // IMPORTANT: captureScreenBitmap's callback fires on the MAIN thread
                        // (it's registered with mainExecutor). We bridge it into a suspend
                        // call with suspendCancellableCoroutine instead of runBlocking, so we
                        // never block the UI thread while waiting for the screenshot or the
                        // Gemini Vision network call. withTimeoutOrNull guards against the
                        // screenshot callback never firing at all (e.g. FLAG_SECURE screens).
                        val bitmap = withTimeoutOrNull(8000) {
                            suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
                                service.captureScreenBitmap { bmp ->
                                    if (cont.isActive) cont.resume(bmp)
                                }
                            }
                        }

                        if (bitmap != null) {
                            val coords = llmEngine.queryGeminiVision(bitmap, targetDesc)
                            if (coords != null) {
                                service.clickCoordinates(coords.first, coords.second)
                                delay(600)
                                true
                            } else {
                                Log.e("TaskExecutor", "Vision fallback: Gemini could not locate '$targetDesc'")
                                false
                            }
                        } else {
                            Log.e("TaskExecutor", "Vision fallback: screenshot unavailable (timeout or secure screen)")
                            false
                        }
                    } else true
                }

                StepType.SET_ALARM -> {
                    val hour = step.params["hour"]?.toIntOrNull() ?: 7
                    val minute = step.params["minute"]?.toIntOrNull() ?: 0
                    val label = step.params["label"] ?: "Alarm"
                    val id = db.jarvisDao().insertAlarm(
                        JarvisAlarm(hour = hour, minute = minute, label = label, isActive = true)
                    ).toInt()
                    JarvisAlarmScheduler.scheduleAlarm(context, hour, minute, label, id)
                    true
                }

                StepType.STORE_MEMORY -> {
                    val fact = step.params["fact"] ?: ""
                    val category = step.params["category"] ?: "General"
                    if (fact.isNotBlank()) {
                        db.jarvisDao().insertMemory(UserMemory(fact = fact, category = category))
                    }
                    true
                }

                StepType.CONFIRM_RISKY_ACTION, StepType.ASK_DISAMBIGUATION -> true
            }
        } catch (e: Exception) {
            Log.e("TaskExecutor", "Error executing step ${step.id}", e)
            false
        }
    }
}
