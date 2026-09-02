package com.example.engine

import org.json.JSONArray
import org.json.JSONObject

enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    RETRYING,
    FAILED,
    WAITING_CONFIRMATION
}

enum class StepType {
    OPEN_APP,
    FIND_CONTACT,
    CALL_PHONE,
    SEND_WHATSAPP,
    SEND_SMS,
    ACCESSIBILITY_TAP_TEXT,
    ACCESSIBILITY_TAP_COORDS,
    ACCESSIBILITY_TYPE,
    ACCESSIBILITY_GLOBAL,
    ACCESSIBILITY_SCROLL,
    VISION_INSPECT_AND_TAP,
    SET_ALARM,
    STORE_MEMORY,
    TOGGLE_TORCH,
    SEARCH_WEB,
    CONTROL_VOLUME,
    OPEN_QUICK_SETTING,
    MEDIA_CONTROL,
    NAVIGATE_TO,
    CHECK_BATTERY,
    OPEN_CAMERA,
    CONFIRM_RISKY_ACTION,
    ASK_DISAMBIGUATION
}

data class TaskStep(
    val id: String,
    val type: StepType,
    val params: Map<String, String>,
    val descriptionHinglish: String,
    var status: StepStatus = StepStatus.PENDING,
    var retryCount: Int = 0,
    var errorMessage: String? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("type", type.name)
        json.put("descriptionHinglish", descriptionHinglish)
        val paramsJson = JSONObject()
        params.forEach { (k, v) -> paramsJson.put(k, v) }
        json.put("params", paramsJson)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): TaskStep {
            val id = json.optString("id", "step_${System.currentTimeMillis()}")
            val typeStr = json.optString("type", "OPEN_APP")
            val type = try { StepType.valueOf(typeStr) } catch (e: Exception) { StepType.OPEN_APP }
            val desc = json.optString("descriptionHinglish", "Step executing...")
            val paramsObj = json.optJSONObject("params")
            val map = mutableMapOf<String, String>()
            if (paramsObj != null) {
                val keys = paramsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = paramsObj.optString(k, "")
                }
            }
            return TaskStep(id, type, map, desc)
        }
    }
}

data class TaskPlan(
    val originalQuery: String,
    val intentKey: String,
    val steps: List<TaskStep>,
    val speechResponseHinglish: String,
    val requiresRiskyConfirmation: Boolean = false,
    val confirmationPrompt: String = "",
    // NEW: set when this plan came from the crude local keyword-matcher instead of a real AI call,
    // so the UI can show *why* instead of silently looking like a normal smart reply.
    val usedFallback: Boolean = false,
    val fallbackReason: String = ""
) {
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("originalQuery", originalQuery)
        root.put("intentKey", intentKey)
        root.put("speechResponseHinglish", speechResponseHinglish)
        root.put("requiresRiskyConfirmation", requiresRiskyConfirmation)
        root.put("confirmationPrompt", confirmationPrompt)
        val stepsArray = JSONArray()
        steps.forEach { stepsArray.put(it.toJson()) }
        root.put("steps", stepsArray)
        return root.toString()
    }

    companion object {
        fun fromJsonString(jsonStr: String): TaskPlan? {
            return try {
                val root = JSONObject(jsonStr)
                val query = root.optString("originalQuery", "")
                val intentKey = root.optString("intentKey", "CUSTOM_TASK")
                val speech = root.optString("speechResponseHinglish", "")
                val risky = root.optBoolean("requiresRiskyConfirmation", false)
                val confirmPrompt = root.optString("confirmationPrompt", "")
                val stepsArray = root.optJSONArray("steps") ?: JSONArray()
                val list = mutableListOf<TaskStep>()
                for (i in 0 until stepsArray.length()) {
                    list.add(TaskStep.fromJson(stepsArray.getJSONObject(i)))
                }
                TaskPlan(query, intentKey, list, speech, risky, confirmPrompt)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Captures an interrupted or paused task so that when the user speaks a follow-up or
 * modification, SARA can merge the old task context with the new instruction.
 */
data class InterruptedTaskState(
    val plan: TaskPlan,
    val stoppedStepIndex: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun summary(): String {
        val remainingSteps = if (stoppedStepIndex < plan.steps.size) {
            plan.steps.drop(stoppedStepIndex).map { it.descriptionHinglish }
        } else emptyList()
        val stepInfo = if (remainingSteps.isNotEmpty()) {
            "Paused at step ${stoppedStepIndex + 1}/${plan.steps.size}: '${remainingSteps.first()}'"
        } else {
            "Completed steps before pause"
        }
        return "Task: '${plan.originalQuery}', $stepInfo. Intent: ${plan.intentKey}"
    }

    fun remainingSteps(): List<TaskStep> {
        return if (stoppedStepIndex < plan.steps.size) {
            plan.steps.drop(stoppedStepIndex)
        } else emptyList()
    }
}

