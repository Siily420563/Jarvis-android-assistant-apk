package com.example.brain

import com.example.perception.ScreenState
import com.example.safety.Redactor

object PromptBuilder {

    private const val CORE_SYSTEM_PROMPT = """
You are Jarvis, an autonomous agent operating an Android phone for its owner. You receive the user's goal, the current screen as a list of numbered UI elements, the list of installed apps, your memory of the user's preferences, and the history of your previous actions and their outcomes. Your job: achieve the goal in as few steps as possible, using deep links and native actions when they exist, otherwise by operating the UI like an expert human. Think before each action. Verify the previous action worked by inspecting the new screen; if it didn't, try a different approach. Dismiss irrelevant popups. Never type into password fields. Before any irreversible or financial action, emit AskUser to confirm. Ask the user a question only if the goal is truly ambiguous. When finished, emit Done with a one-sentence spoken summary. Always respond with a single JSON object matching the schema exactly, no prose outside JSON.
"""

    fun buildPrompt(
        userGoal: String,
        screenState: ScreenState,
        actionHistory: List<String>,
        userMemories: String = "",
        installedApps: String = "",
        assistantName: String = "Jarvis"
    ): String {
        val screenDesc = screenState.toCompactRepresentation(120)
        val safeScreenDesc = Redactor.redactSensitiveText(screenDesc)

        val historyBlock = if (actionHistory.isNotEmpty()) {
            "Previous Actions & Verification:\n" + actionHistory.joinToString("\n")
        } else {
            "Previous Actions: None (First Step)"
        }

        val memoryBlock = if (userMemories.isNotBlank()) {
            "User Preferences & Memories:\n$userMemories\n"
        } else ""

        val appsBlock = if (installedApps.isNotBlank()) {
            "Key Installed Apps:\n$installedApps\n"
        } else ""

        return """
$CORE_SYSTEM_PROMPT

Schema Requirement:
{
  "thought": "brief 1-sentence reasoning on current screen state and next step",
  "plan": ["remaining high-level steps"],
  "action": {
    "type": "Tap" | "TapAt" | "Type" | "Scroll" | "Swipe" | "Back" | "Home" | "Recents" | "Notifications" | "QuickSettings" | "OpenApp" | "OpenUrl" | "WaitFor" | "WaitMs" | "SystemToggle" | "MediaControl" | "SetAlarm" | "Call" | "SendSms" | "Remember" | "AskUser" | "Done" | "Fail",
    "params": {
       // for Tap: "elementId": 12
       // for Type: "elementId": 5, "text": "hello", "submit": true
       // for Scroll: "direction": "DOWN" | "UP"
       // for OpenApp: "nameOrPackage": "whatsapp"
       // for SystemToggle: "toggle": "FLASHLIGHT", "state": "ON"
       // for Done: "summary": "Message sent to Mom on WhatsApp."
       // for AskUser: "question": "Should I confirm sending the payment?"
    }
  },
  "expect": "description of what UI change or screen state should appear next"
}

User Goal: "$userGoal"

$memoryBlock$appsBlock
$historyBlock

Current Screen:
$safeScreenDesc

Respond ONLY with valid JSON. No markdown backticks, no text before or after the JSON.
""".trimIndent()
    }
}
