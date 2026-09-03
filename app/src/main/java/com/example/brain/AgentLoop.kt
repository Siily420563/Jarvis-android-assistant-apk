package com.example.brain

import android.content.Context
import android.util.Log
import com.example.actions.Action
import com.example.actions.ActionExecutor
import com.example.actions.ActionResult
import com.example.data.db.InteractionLog
import com.example.data.db.JarvisDatabase
import com.example.data.prefs.PreferencesManager
import com.example.engine.LlmEngine
import com.example.perception.ScreenState
import com.example.safety.SafetyPolicy
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AgentLoop(
    private val context: Context,
    private val db: JarvisDatabase,
    private val prefs: PreferencesManager,
    private val llmEngine: LlmEngine
) {
    private val executor = ActionExecutor(context, db)
    private val isCancelled = AtomicBoolean(false)

    fun cancel() {
        isCancelled.set(true)
        Log.i("JarvisAgentLoop", "AgentLoop emergency stop invoked")
    }

    suspend fun executeGoal(
        userGoal: String,
        maxSteps: Int = 25,
        onStepStarted: (stepIndex: Int, thought: String, action: Action) -> Unit = { _, _, _ -> },
        onStepExecuted: (stepIndex: Int, result: ActionResult) -> Unit = { _, _ -> },
        onConfirmationRequired: suspend (reason: String) -> Boolean = { true },
        onComplete: (summary: String, success: Boolean) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        val actionHistory = mutableListOf<String>()
        var consecutiveFailures = 0
        var lastActionSignature = ""

        // Fetch user memories and installed apps
        val memories = try {
            db.jarvisDao().getMemoriesList()
                .take(10)
                .joinToString("\n") { "- ${it.fact}" }
        } catch (e: Exception) { "" }

        val installedAppsSummary = try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                .take(20)
                .map { pm.getApplicationLabel(it).toString() }
            apps.joinToString(", ")
        } catch (e: Exception) { "" }

        var currentStep = 1
        var finalSummary = "Goal executed."
        var finalSuccess = true

        while (currentStep <= maxSteps && !isCancelled.get()) {
            val service = JarvisAccessibilityService.instance
            val screenState: ScreenState = service?.captureScreenState() ?: ScreenState()

            // 1. Build prompt for LLM
            val prompt = PromptBuilder.buildPrompt(
                userGoal = userGoal,
                screenState = screenState,
                actionHistory = actionHistory.takeLast(6),
                userMemories = memories,
                installedApps = installedAppsSummary,
                assistantName = prefs.assistantName
            )

            // 2. Query LLM
            val llmResponse = try {
                llmEngine.queryPlan(prompt)
            } catch (e: Exception) {
                Log.e("JarvisAgentLoop", "LLM query failed on step $currentStep", e)
                null
            }

            if (llmResponse.isNullOrBlank()) {
                finalSummary = "Unable to connect to AI brain. Please check internet or API key."
                finalSuccess = false
                break
            }

            // 3. Parse JSON step
            val step = AgentStep.fromJson(llmResponse)
            if (step == null) {
                Log.w("JarvisAgentLoop", "Could not parse JSON response: $llmResponse")
                actionHistory.add("Step $currentStep: Model returned invalid JSON formatting. Retrying with cleaner syntax.")
                currentStep++
                continue
            }

            onStepStarted(currentStep, step.thought, step.action)

            // 4. Check for terminal actions
            if (step.action is Action.Done) {
                finalSummary = step.action.summary
                finalSuccess = true
                break
            }
            if (step.action is Action.Fail) {
                finalSummary = step.action.reason
                finalSuccess = false
                break
            }

            // 5. Safety & Confirmation Policy Check
            val targetElement = (step.action as? Action.Tap)?.let { screenState.elementLookup[it.elementId] }
                ?: (step.action as? Action.Type)?.let { screenState.elementLookup[it.elementId] }

            val (requiresConfirm, reason) = SafetyPolicy.requiresConfirmation(
                action = step.action,
                currentPackage = screenState.packageName,
                targetElement = targetElement
            )

            if (requiresConfirm && prefs.confirmRiskyActions) {
                val confirmed = onConfirmationRequired(reason)
                if (!confirmed) {
                    finalSummary = "Action cancelled by user: $reason"
                    finalSuccess = false
                    break
                }
            }

            // 6. Anti-loop protection: if same action tried twice consecutively, force fallback
            val actionSig = step.action.javaClass.simpleName + "_" + (step.action as? Action.Tap)?.elementId
            if (actionSig == lastActionSignature) {
                consecutiveFailures++
                if (consecutiveFailures >= 2) {
                    Log.w("JarvisAgentLoop", "Detected action loop on $actionSig, breaking out")
                    actionHistory.add("Step $currentStep: Repeated action $actionSig had no effect. Trying alternative strategy or scrolling.")
                    executor.execute(Action.Scroll("DOWN"), screenState)
                    delay(800)
                    currentStep++
                    continue
                }
            } else {
                consecutiveFailures = 0
                lastActionSignature = actionSig
            }

            // 7. Execute Action
            val result = executor.execute(step.action, screenState)
            onStepExecuted(currentStep, result)

            // 8. Capture post-action screen state to verify expectation
            delay(1000)
            val newScreenState = service?.captureScreenState() ?: screenState

            val historyEntry = "Step $currentStep: Executed ${step.action.javaClass.simpleName} -> ${result.message}. Expected: '${step.expect}'. Current foreground: ${newScreenState.packageName}"
            actionHistory.add(historyEntry)

            // Log interaction
            try {
                db.jarvisDao().insertLog(
                    InteractionLog(
                        text = "Step $currentStep [${step.action.javaClass.simpleName}]: ${step.thought}",
                        isUser = false
                    )
                )
            } catch (e: Exception) {
                // Ignore DB logging errors
            }

            if (isCancelled.get()) {
                finalSummary = "Jarvis stopped by user."
                finalSuccess = false
                break
            }

            currentStep++
        }

        if (currentStep > maxSteps) {
            finalSummary = "Reached maximum step limit ($maxSteps). Last state: ${actionHistory.lastOrNull()}"
            finalSuccess = false
        }

        onComplete(finalSummary, finalSuccess)
    }
}
