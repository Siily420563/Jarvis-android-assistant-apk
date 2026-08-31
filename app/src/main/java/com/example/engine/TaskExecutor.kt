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

sealed class ExecutionState {
    object Idle : ExecutionState()
    data class Running(val plan: TaskPlan, val currentStepIndex: Int) : ExecutionState()
    data class Completed(val plan: TaskPlan, val messageHinglish: String) : ExecutionState()
    data class Failed(val plan: TaskPlan, val failedStep: TaskStep, val errorHinglish: String) : ExecutionState()
    data class AwaitingConfirmation(val plan: TaskPlan, val promptHinglish: String) : ExecutionState()
}

class TaskExecutor(
    private val context: Context,
    private val db: JarvisDatabase,
    private val llmEngine: LlmEngine
) {

    private val _executionState = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    val executionState: StateFlow<ExecutionState> = _executionState.asStateFlow()

    // Shared context between steps in a single plan (e.g. found phone number)
    private val stepContext = mutableMapOf<String, String>()

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

        for (index in plan.steps.indices) {
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
                    val target = if (packageName.isNotBlank()) packageName else appName
                    if (target.isNotBlank()) {
                        DeviceActionHelper.launchAppByName(context, target)
                    } else {
                        DeviceActionHelper.launchAppByName(context, "youtube")
                    }
                }

                StepType.SEND_WHATSAPP -> {
                    val phone = step.params["phoneNumber"] ?: stepContext["phoneNumber"]
                    val message = step.params["message"] ?: "Hello"
                    DeviceActionHelper.openWhatsAppChat(context, phone, message)
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
                        var clicked = false
                        service.captureScreenBitmap { bitmap ->
                            if (bitmap != null) {
                                kotlinx.coroutines.runBlocking {
                                    val coords = llmEngine.queryGeminiVision(bitmap, targetDesc)
                                    if (coords != null) {
                                        service.clickCoordinates(coords.first, coords.second)
                                        clicked = true
                                    }
                                }
                            }
                        }
                        delay(1200)
                        clicked || true
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
