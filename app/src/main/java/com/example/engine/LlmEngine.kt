package com.example.engine

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.data.prefs.PreferencesManager
import com.example.debug.SystemLogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class LlmEngine(private val prefs: PreferencesManager) {

    @Volatile var lastErrorReason: String = ""
        private set

    var onHttp4xxError: ((String) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val providerBlockedUntil = mutableMapOf<String, Long>()
    private val recentPlanCache = mutableMapOf<String, Pair<Long, TaskPlan>>()
    private val cacheTtlMs = 15_000L

    data class ProviderAttemptResult(
        val ok: Boolean,
        val response: String? = null,
        val httpCode: Int = 0,
        val error: String = ""
    )

    private fun isProviderTemporarilyBlocked(name: String): Boolean {
        return (providerBlockedUntil[name] ?: 0L) > System.currentTimeMillis()
    }

    private fun blockProvider(name: String, ms: Long) {
        providerBlockedUntil[name] = System.currentTimeMillis() + ms
    }

    suspend fun planAndQuery(
        userInput: String,
        userMemoriesStr: String,
        activeAlarmsStr: String,
        screenContextStr: String = "",
        conversationHistoryStr: String = "",
        interruptedTaskState: InterruptedTaskState? = null
    ): Result<TaskPlan> = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = SaraSystemPrompt.buildSystemPrompt(
                persona = prefs.activePersona,
                assistantName = prefs.assistantName,
                userMemories = userMemoriesStr,
                activeAlarms = activeAlarmsStr,
                screenContext = screenContextStr,
                conversationHistory = conversationHistoryStr,
                interruptedTaskContext = interruptedTaskState?.summary() ?: ""
            )

            val cacheKey = "${prefs.activePersona.name}|${userInput.trim().lowercase()}|${screenContextStr.take(160)}"
            recentPlanCache[cacheKey]?.let { (ts, plan) ->
                if (System.currentTimeMillis() - ts < cacheTtlMs) {
                    return@withContext Result.success(
                        plan.copy(usedFallback = true, fallbackReason = "Served from short cache to save quota")
                    )
                }
            }

            if (!prefs.hasAnyApiKey()) {
                val localPlan = runLocalHeuristicPlanner(userInput, interruptedTaskState)
                return@withContext Result.success(
                    localPlan.copy(usedFallback = true, fallbackReason = "No API key configured")
                )
            }

            lastErrorReason = ""
            val providerOrder = buildProviderOrder()
            var responseJsonStr: String? = null
            var attempts = 0
            val maxCalls = prefs.maxLlmCallsPerCommand.coerceIn(1, 8)

            for (provider in providerOrder) {
                if (attempts >= maxCalls) break
                if (isProviderTemporarilyBlocked(provider)) continue

                attempts++
                val attempt = when (provider) {
                    "GEMINI" -> callGeminiSafe(systemPrompt, userInput)
                    "GROQ" -> callGroqSafe(systemPrompt, userInput)
                    "OPENAI" -> callOpenAiSafe(systemPrompt, userInput)
                    "OPENROUTER" -> callOpenRouterSafe(systemPrompt, userInput)
                    else -> ProviderAttemptResult(false, error = "Unknown provider")
                }

                if (attempt.ok && !attempt.response.isNullOrBlank()) {
                    responseJsonStr = attempt.response
                    SystemLogBus.i("LlmEngine", "Provider success: $provider in $attempts attempt(s)")
                    break
                }

                val code = attempt.httpCode
                val err = attempt.error.ifBlank { "No detail" }
                SystemLogBus.w("LlmEngine", "Provider failed: $provider code=$code error=$err")

                if (code in 400..499 && code != 429) {
                    blockProvider(provider, 10 * 60_000L)
                    onHttp4xxError?.invoke("$provider auth/model error: $err")
                } else if (code == 429 || code >= 500) {
                    blockProvider(provider, 30_000L)
                }
            }

            if (!responseJsonStr.isNullOrBlank()) {
                val cleanJson = cleanJsonOutput(responseJsonStr)
                val parsedPlan = TaskPlan.fromJsonString(cleanJson)
                if (parsedPlan != null) {
                    recentPlanCache[cacheKey] = System.currentTimeMillis() to parsedPlan
                    return@withContext Result.success(parsedPlan)
                }

                val conversationalPlan = TaskPlan(
                    originalQuery = userInput,
                    intentKey = "CONVERSATION",
                    steps = emptyList(),
                    speechResponseHinglish = cleanJson,
                    usedFallback = true,
                    fallbackReason = "Model returned non-JSON conversational output"
                )
                return@withContext Result.success(conversationalPlan)
            }

            val fallbackPlan = runLocalHeuristicPlanner(userInput, interruptedTaskState)
            val reason = lastErrorReason.ifBlank { "No provider returned usable response within call budget" }
            Result.success(fallbackPlan.copy(usedFallback = true, fallbackReason = reason))
        } catch (e: Exception) {
            lastErrorReason = e.message ?: e.javaClass.simpleName
            val fallbackPlan = runLocalHeuristicPlanner(userInput, interruptedTaskState)
            Result.success(fallbackPlan.copy(usedFallback = true, fallbackReason = "Engine exception: $lastErrorReason"))
        }
    }

    private fun buildProviderOrder(): List<String> {
        val preferred = prefs.preferredLlm.uppercase()
        val base = mutableListOf<String>()
        if (preferred in listOf("GEMINI", "GROQ", "OPENAI", "OPENROUTER")) base.add(preferred)
        base.addAll(listOf("GEMINI", "GROQ", "OPENAI", "OPENROUTER").filter { it !in base })
        return base
    }

    private fun callGeminiSafe(systemPrompt: String, userInput: String): ProviderAttemptResult {
        return try {
            val result = callGemini(systemPrompt, userInput)
            if (result.ok) result else result.copy(error = result.error.ifBlank { "Gemini failed" })
        } catch (e: Exception) {
            ProviderAttemptResult(false, httpCode = 500, error = e.message ?: "Gemini exception")
        }
    }

    private fun callGroqSafe(systemPrompt: String, userInput: String): ProviderAttemptResult {
        return try {
            val result = callGroq(systemPrompt, userInput)
            if (result.ok) result else result.copy(error = result.error.ifBlank { "Groq failed" })
        } catch (e: Exception) {
            ProviderAttemptResult(false, httpCode = 500, error = e.message ?: "Groq exception")
        }
    }

    private fun callOpenAiSafe(systemPrompt: String, userInput: String): ProviderAttemptResult {
        return try {
            val result = callOpenAi(systemPrompt, userInput)
            if (result.ok) result else result.copy(error = result.error.ifBlank { "OpenAI failed" })
        } catch (e: Exception) {
            ProviderAttemptResult(false, httpCode = 500, error = e.message ?: "OpenAI exception")
        }
    }

    private fun callOpenRouterSafe(systemPrompt: String, userInput: String): ProviderAttemptResult {
        return try {
            val result = callOpenRouter(systemPrompt, userInput)
            if (result.ok) result else result.copy(error = result.error.ifBlank { "OpenRouter failed" })
        } catch (e: Exception) {
            ProviderAttemptResult(false, httpCode = 500, error = e.message ?: "OpenRouter exception")
        }
    }

    private fun callGemini(systemPrompt: String, userInput: String): ProviderAttemptResult {
        val key = prefs.geminiApiKey
        if (key.isBlank()) return ProviderAttemptResult(false, httpCode = 401, error = "Gemini key missing")
        val model = prefs.geminiModel.ifBlank { "gemini-3.8-flash" }

        return try {
            val jsonBody = JSONObject().apply {
                if (systemPrompt.isNotBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    })
                }
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", userInput)))))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.15)
                    put("responseMimeType", "application/json")
                })
            }

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = "Gemini HTTP ${resp.code}: ${body.take(240)}"
                    lastErrorReason = msg
                    return ProviderAttemptResult(false, httpCode = resp.code, error = msg)
                }

                val root = JSONObject(body)
                val candidates = root.optJSONArray("candidates") ?: return ProviderAttemptResult(false, httpCode = 500, error = "Gemini empty candidates")
                if (candidates.length() == 0) return ProviderAttemptResult(false, httpCode = 500, error = "Gemini no candidate")
                val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                val text = parts.optJSONObject(0)?.optString("text", "") ?: ""
                if (text.isBlank()) return ProviderAttemptResult(false, httpCode = 500, error = "Gemini blank text")
                ProviderAttemptResult(true, response = text)
            }
        } catch (e: Exception) {
            val msg = "Gemini exception: ${e.message ?: e.javaClass.simpleName}"
            lastErrorReason = msg
            ProviderAttemptResult(false, httpCode = 500, error = msg)
        }
    }

    private fun callGroq(systemPrompt: String, userInput: String): ProviderAttemptResult {
        val key = prefs.groqApiKey
        if (key.isBlank()) return ProviderAttemptResult(false, httpCode = 401, error = "Groq key missing")
        val model = prefs.groqModel.ifBlank { "llama-3.3-70b-versatile" }

        return try {
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userInput))
                })
                put("response_format", JSONObject().put("type", "json_object"))
                put("temperature", 0.2)
            }

            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = "Groq HTTP ${resp.code}: ${body.take(240)}"
                    lastErrorReason = msg
                    return ProviderAttemptResult(false, httpCode = resp.code, error = msg)
                }

                val text = JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                ProviderAttemptResult(true, response = text)
            }
        } catch (e: Exception) {
            val msg = "Groq exception: ${e.message ?: e.javaClass.simpleName}"
            lastErrorReason = msg
            ProviderAttemptResult(false, httpCode = 500, error = msg)
        }
    }

    private fun callOpenAi(systemPrompt: String, userInput: String): ProviderAttemptResult {
        val key = prefs.openAiApiKey
        if (key.isBlank()) return ProviderAttemptResult(false, httpCode = 401, error = "OpenAI key missing")
        val model = prefs.openAiModel.ifBlank { "gpt-4.1-mini" }
        val baseUrl = prefs.openAiBaseUrl.trimEnd('/')

        return try {
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userInput))
                })
                put("response_format", JSONObject().put("type", "json_object"))
                put("temperature", 0.2)
            }

            val req = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = "OpenAI HTTP ${resp.code}: ${body.take(240)}"
                    lastErrorReason = msg
                    return ProviderAttemptResult(false, httpCode = resp.code, error = msg)
                }

                val text = JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                ProviderAttemptResult(true, response = text)
            }
        } catch (e: Exception) {
            val msg = "OpenAI exception: ${e.message ?: e.javaClass.simpleName}"
            lastErrorReason = msg
            ProviderAttemptResult(false, httpCode = 500, error = msg)
        }
    }

    private fun callOpenRouter(systemPrompt: String, userInput: String): ProviderAttemptResult {
        val key = prefs.openRouterApiKey
        if (key.isBlank()) return ProviderAttemptResult(false, httpCode = 401, error = "OpenRouter key missing")
        val model = prefs.openRouterModel.ifBlank { "anthropic/claude-sonnet-4.6" }

        return try {
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userInput))
                })
                put("response_format", JSONObject().put("type", "json_object"))
            }

            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = "OpenRouter HTTP ${resp.code}: ${body.take(240)}"
                    lastErrorReason = msg
                    return ProviderAttemptResult(false, httpCode = resp.code, error = msg)
                }

                val text = JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                ProviderAttemptResult(true, response = text)
            }
        } catch (e: Exception) {
            val msg = "OpenRouter exception: ${e.message ?: e.javaClass.simpleName}"
            lastErrorReason = msg
            ProviderAttemptResult(false, httpCode = 500, error = msg)
        }
    }

    suspend fun queryPlan(fullPrompt: String): String? = withContext(Dispatchers.IO) {
        val providerOrder = buildProviderOrder()
        var attempts = 0
        val maxCalls = prefs.maxLlmCallsPerCommand.coerceIn(1, 8)

        for (provider in providerOrder) {
            if (attempts >= maxCalls) break
            if (isProviderTemporarilyBlocked(provider)) continue
            attempts++

            val res = when (provider) {
                "GEMINI" -> callGeminiSafe("", fullPrompt)
                "GROQ" -> callGroqSafe("", fullPrompt)
                "OPENAI" -> callOpenAiSafe("", fullPrompt)
                "OPENROUTER" -> callOpenRouterSafe("", fullPrompt)
                else -> ProviderAttemptResult(false, error = "Unknown provider")
            }
            if (res.ok && !res.response.isNullOrBlank()) return@withContext res.response

            if (res.httpCode in 400..499 && res.httpCode != 429) blockProvider(provider, 10 * 60_000L)
            if (res.httpCode == 429 || res.httpCode >= 500) blockProvider(provider, 30_000L)
        }
        null
    }

    suspend fun queryGeminiVision(
        bitmap: Bitmap,
        targetDescription: String
    ): Pair<Float, Float>? = withContext(Dispatchers.IO) {
        val key = prefs.geminiApiKey
        if (key.isBlank()) return@withContext null

        try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val base64Img = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val prompt = """
                Look at this mobile app screenshot.
                Find element: "$targetDescription".
                Return ONLY JSON: {"x": int 0..1000, "y": int 0..1000}
                If not found return {"x": -1, "y": -1}.
            """.trimIndent()

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", base64Img)))
                })))
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }

            val model = prefs.geminiModel.ifBlank { "gemini-3.8-flash" }
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string().orEmpty()
                val text = JSONObject(body).getJSONArray("candidates")
                    .getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0)
                    .getString("text")

                val coordJson = JSONObject(cleanJsonOutput(text))
                val normX = coordJson.optDouble("x", -1.0)
                val normY = coordJson.optDouble("y", -1.0)
                if (normX < 0 || normY < 0) return@withContext null

                val actualX = (normX / 1000.0 * bitmap.width).toFloat()
                val actualY = (normY / 1000.0 * bitmap.height).toFloat()
                Pair(actualX, actualY)
            }
        } catch (e: Exception) {
            Log.e("LlmEngine", "Gemini vision error", e)
            null
        }
    }

    private fun cleanJsonOutput(raw: String): String {
        var str = raw.trim()
        if (str.startsWith("```json")) str = str.removePrefix("```json").trim()
        else if (str.startsWith("```")) str = str.removePrefix("```").trim()
        if (str.endsWith("```")) str = str.removeSuffix("```").trim()
        return str.trim()
    }

    private fun runLocalHeuristicPlanner(query: String, interruptedTask: InterruptedTaskState? = null): TaskPlan {
        val clean = query.trim().lowercase()

        if (interruptedTask != null && (clean == "continue" || clean == "resume" || clean.contains("aage"))) {
            return TaskPlan(
                originalQuery = query,
                intentKey = "RESUME_TASK",
                steps = interruptedTask.remainingSteps(),
                speechResponseHinglish = "Theek hai, paused task resume kar rahi hoon."
            )
        }

        if (clean.contains("whatsapp") && clean.contains("ko")) {
            val nameMatch = Regex("([a-zA-Z0-9\\u0900-\\u097F]+)\\s*ko").find(clean)
            val contact = nameMatch?.groupValues?.get(1)?.ifBlank { "mummy" } ?: "mummy"
            val msg = clean.substringAfter("ki ", "").ifBlank { "Hi" }
            return TaskPlan(
                originalQuery = query,
                intentKey = "WHATSAPP_SEND",
                steps = listOf(
                    TaskStep("s1", StepType.FIND_CONTACT, mapOf("name" to contact), "$contact ka contact dhoond rahe hain"),
                    TaskStep("s2", StepType.SEND_WHATSAPP, mapOf("contactName" to contact, "message" to msg, "autoSend" to "true"), "WhatsApp message bhej rahe hain")
                ),
                speechResponseHinglish = "$contact ko WhatsApp message bhej rahi hoon."
            )
        }

        if (clean.contains("youtube")) {
            val q = clean.replace("youtube", "").replace("play", "").replace("chalao", "").trim().ifBlank { "trending songs" }
            return TaskPlan(
                originalQuery = query,
                intentKey = "PLAY_YOUTUBE",
                steps = listOf(TaskStep("s1", StepType.OPEN_APP, mapOf("appName" to "YouTube", "query" to q), "YouTube open kar rahe hain")),
                speechResponseHinglish = "YouTube par $q chala rahi hoon."
            )
        }

        if (clean.contains("call")) {
            val who = clean.replace("call", "").replace("ko", "").trim().ifBlank { "contact" }
            return TaskPlan(
                originalQuery = query,
                intentKey = "CALL_PHONE",
                steps = listOf(
                    TaskStep("s1", StepType.FIND_CONTACT, mapOf("name" to who), "Contact dhoond rahe hain"),
                    TaskStep("s2", StepType.CALL_PHONE, mapOf("contactName" to who), "Call laga rahe hain")
                ),
                speechResponseHinglish = "$who ko call laga rahi hoon."
            )
        }

        if (clean.contains("back")) {
            return TaskPlan(
                originalQuery = query,
                intentKey = "GO_BACK",
                steps = listOf(TaskStep("s1", StepType.ACCESSIBILITY_GLOBAL, mapOf("action" to "BACK"), "Back ja rahe hain")),
                speechResponseHinglish = "Back ja rahi hoon."
            )
        }

        return TaskPlan(
            originalQuery = query,
            intentKey = "CONVERSATION",
            steps = emptyList(),
            speechResponseHinglish = "Command samajh liya, mujhe aur specific step batao.",
            usedFallback = true,
            fallbackReason = "Local heuristic mode"
        )
    }
}
