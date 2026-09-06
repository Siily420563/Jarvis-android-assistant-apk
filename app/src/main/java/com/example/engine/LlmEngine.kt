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
    private val responseCache = mutableMapOf<String, Pair<Long, TaskPlan>>()
    private val cacheTtlMs = 20_000L

    data class ProviderAttemptResult(
        val ok: Boolean,
        val response: String? = null,
        val httpCode: Int = 0,
        val error: String = ""
    )

    private fun isBlocked(provider: String): Boolean =
        (providerBlockedUntil[provider] ?: 0L) > System.currentTimeMillis()

    private fun block(provider: String, ms: Long) {
        providerBlockedUntil[provider] = System.currentTimeMillis() + ms
    }

    private fun providerHasKey(provider: String): Boolean = when (provider) {
        "GEMINI" -> prefs.geminiApiKey.isNotBlank()
        "GROQ" -> prefs.groqApiKey.isNotBlank()
        "OPENAI" -> prefs.openAiApiKey.isNotBlank()
        "OPENROUTER" -> prefs.openRouterApiKey.isNotBlank()
        else -> false
    }

    private fun providerOrder(): List<String> {
        val preferred = prefs.preferredLlm.uppercase()
        val base = mutableListOf<String>()
        if (preferred in listOf("GEMINI", "GROQ", "OPENAI", "OPENROUTER")) base.add(preferred)
        base.addAll(listOf("GEMINI", "GROQ", "OPENAI", "OPENROUTER").filter { it !in base })
        return base.filter { providerHasKey(it) }
    }

    private fun normalizeGeminiModel(model: String): String {
        val m = model.trim()
        return if (m.isBlank()) "gemini-3.5-flash" else m
    }

    private fun normalizeGroqPrimaryModel(model: String): String {
        val m = model.trim()
        if (m.isBlank()) return "llama-3.1-8b-instant"
        if (m.contains("llama-3.1-8b-instant", true)) return "llama-3.1-8b-instant"
        if (m.contains("llama-3.3-70b-versatile", true)) return "llama-3.3-70b-versatile"
        return "llama-3.1-8b-instant"
    }

    private fun normalizeOpenRouterModel(model: String): String {
        val m = model.trim()
        if (m.isBlank()) return "openai/gpt-4o-mini"
        if (m.contains("claude-3.7-sonnet", true)) return "openai/gpt-4o-mini"
        return m
    }

    private fun shortError(msg: String): String {
        return msg
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\"user_id\"\\s*:\\s*\"[^\"]+\""), "\"user_id\":\"hidden\"")
            .take(180)
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
            val cleanInput = userInput.trim()
            if (cleanInput.isBlank()) {
                return@withContext Result.success(
                    TaskPlan(
                        originalQuery = userInput,
                        intentKey = "CONVERSATION",
                        steps = emptyList(),
                        speechResponseHinglish = "Bolo, main sun rahi hoon."
                    )
                )
            }

            val cacheKey = "${prefs.activePersona.name}|${cleanInput.lowercase()}|${screenContextStr.take(120)}"
            responseCache[cacheKey]?.let { (ts, cachedPlan) ->
                if (System.currentTimeMillis() - ts < cacheTtlMs) {
                    return@withContext Result.success(
                        cachedPlan.copy(
                            usedFallback = true,
                            fallbackReason = "Short cache hit"
                        )
                    )
                }
            }

            val systemPrompt = SaraSystemPrompt.buildSystemPrompt(
                persona = prefs.activePersona,
                assistantName = prefs.assistantName,
                userMemories = userMemoriesStr,
                activeAlarms = activeAlarmsStr,
                screenContext = screenContextStr,
                conversationHistory = conversationHistoryStr,
                interruptedTaskContext = interruptedTaskState?.summary() ?: ""
            )

            val activeProviders = providerOrder()
            if (activeProviders.isEmpty()) {
                val local = runLocalHeuristicPlanner(cleanInput, interruptedTaskState)
                return@withContext Result.success(local.copy(usedFallback = true, fallbackReason = "No API key"))
            }

            var responseJson: String? = null
            val maxCalls = prefs.maxLlmCallsPerCommand.coerceIn(1, 4)
            var attempts = 0
            val errors = mutableListOf<String>()

            for (provider in activeProviders) {
                if (attempts >= maxCalls) break
                if (isBlocked(provider)) continue
                attempts++

                val result = when (provider) {
                    "GEMINI" -> callGeminiSafe(systemPrompt, cleanInput)
                    "GROQ" -> callGroqSafe(systemPrompt, cleanInput)
                    "OPENAI" -> callOpenAiSafe(systemPrompt, cleanInput)
                    "OPENROUTER" -> callOpenRouterSafe(systemPrompt, cleanInput)
                    else -> ProviderAttemptResult(false, error = "Unknown provider")
                }

                if (result.ok && !result.response.isNullOrBlank()) {
                    responseJson = result.response
                    SystemLogBus.i("LlmEngine", "Provider success: $provider")
                    break
                }

                val compactErr = shortError(result.error.ifBlank { "empty response" })
                errors.add("$provider ${result.httpCode}: $compactErr")
                SystemLogBus.w("LlmEngine", "Provider failed: $provider ${result.httpCode}")

                if (result.httpCode in 400..499 && result.httpCode != 429) {
                    block(provider, 10 * 60_000L)
                    onHttp4xxError?.invoke("$provider config issue")
                } else if (result.httpCode == 429 || result.httpCode >= 500) {
                    block(provider, 60_000L)
                }
            }

            if (!responseJson.isNullOrBlank()) {
                val cleanJson = cleanJsonOutput(responseJson)
                val parsed = TaskPlan.fromJsonString(cleanJson)
                if (parsed != null) {
                    responseCache[cacheKey] = System.currentTimeMillis() to parsed
                    return@withContext Result.success(parsed)
                }

                return@withContext Result.success(
                    TaskPlan(
                        originalQuery = cleanInput,
                        intentKey = "CONVERSATION",
                        steps = emptyList(),
                        speechResponseHinglish = cleanJson,
                        usedFallback = true,
                        fallbackReason = "Model non-JSON output"
                    )
                )
            }

            lastErrorReason = errors.joinToString(" | ").take(200)
            val local = runLocalHeuristicPlanner(cleanInput, interruptedTaskState)
            return@withContext Result.success(
                local.copy(
                    usedFallback = true,
                    fallbackReason = "Provider unavailable"
                )
            )
        } catch (e: Exception) {
            lastErrorReason = e.message ?: "Engine exception"
            val local = runLocalHeuristicPlanner(userInput, interruptedTaskState)
            return@withContext Result.success(local.copy(usedFallback = true, fallbackReason = "Engine exception"))
        }
    }

    private fun callGeminiSafe(systemPrompt: String, userInput: String): ProviderAttemptResult {
        return try { callGemini(systemPrompt, userInput) }
        catch (e: Exception) { ProviderAttemptResult(false, httpCode = 500, error = "Gemini exception: ${e.message}") }
    }

    private fun callGroqSafe(systemPrompt: String, userInput: String): ProviderAttemptResult {
        return try { callGroq(systemPrompt, userInput) }
        catch (e: Exception) { ProviderAttemptResult(false, httpCode = 500, error = "Groq exception: ${e.message}") }
    }

    private fun callOpenAiSafe(systemPrompt: String, userInput: String): ProviderAttemptResult {
        return try { callOpenAi(systemPrompt, userInput) }
        catch (e: Exception) { ProviderAttemptResult(false, httpCode = 500, error = "OpenAI exception: ${e.message}") }
    }

    private fun callOpenRouterSafe(systemPrompt: String, userInput: String): ProviderAttemptResult {
        return try { callOpenRouter(systemPrompt, userInput) }
        catch (e: Exception) { ProviderAttemptResult(false, httpCode = 500, error = "OpenRouter exception: ${e.message}") }
    }

    private fun callGemini(systemPrompt: String, userInput: String): ProviderAttemptResult {
        val key = prefs.geminiApiKey
        val model = normalizeGeminiModel(prefs.geminiModel)

        val body = JSONObject().apply {
            if (systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
            }
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", userInput)))))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("responseMimeType", "application/json")
            })
        }

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return ProviderAttemptResult(false, httpCode = resp.code, error = "Gemini HTTP ${resp.code}: $raw")
            }
            val text = JSONObject(raw)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "")
                .orEmpty()

            if (text.isBlank()) return ProviderAttemptResult(false, httpCode = 500, error = "Gemini empty text")
            return ProviderAttemptResult(true, response = text)
        }
    }

    private fun callGroq(systemPrompt: String, userInput: String): ProviderAttemptResult {
        val key = prefs.groqApiKey
        val primary = normalizeGroqPrimaryModel(prefs.groqModel)
        val backup = "llama-3.1-8b-instant"

        fun doCall(model: String): ProviderAttemptResult {
            val body = JSONObject().apply {
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
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return ProviderAttemptResult(false, httpCode = resp.code, error = "Groq HTTP ${resp.code}: $raw")
                val text = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                return ProviderAttemptResult(true, response = text)
            }
        }

        val first = doCall(primary)
        if (first.ok) return first
        if ((first.httpCode == 404 || first.error.contains("model_not_found", true)) && primary != backup) {
            return doCall(backup)
        }
        return first
    }

    private fun callOpenAi(systemPrompt: String, userInput: String): ProviderAttemptResult {
        val key = prefs.openAiApiKey
        val model = prefs.openAiModel.ifBlank { "gpt-4.1-mini" }
        val base = prefs.openAiBaseUrl.trimEnd('/')

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userInput))
            })
            put("response_format", JSONObject().put("type", "json_object"))
            put("temperature", 0.2)
        }

        val req = Request.Builder()
            .url("$base/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return ProviderAttemptResult(false, httpCode = resp.code, error = "OpenAI HTTP ${resp.code}: $raw")
            val text = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            return ProviderAttemptResult(true, response = text)
        }
    }

    private fun callOpenRouter(systemPrompt: String, userInput: String): ProviderAttemptResult {
        val key = prefs.openRouterApiKey
        val primary = normalizeOpenRouterModel(prefs.openRouterModel)
        val backup = "openai/gpt-4o-mini"

        fun doCall(model: String): ProviderAttemptResult {
            val body = JSONObject().apply {
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
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return ProviderAttemptResult(false, httpCode = resp.code, error = "OpenRouter HTTP ${resp.code}: $raw")
                val text = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                return ProviderAttemptResult(true, response = text)
            }
        }

        val first = doCall(primary)
        if (first.ok) return first
        if ((first.httpCode == 404 || first.error.contains("No endpoints found", true)) && primary != backup) {
            return doCall(backup)
        }
        return first
    }

    suspend fun queryPlan(fullPrompt: String): String? = withContext(Dispatchers.IO) {
        val providers = providerOrder()
        if (providers.isEmpty()) return@withContext null
        val maxCalls = prefs.maxLlmCallsPerCommand.coerceIn(1, 4)
        var calls = 0

        for (provider in providers) {
            if (calls >= maxCalls) break
            if (isBlocked(provider)) continue
            calls++

            val res = when (provider) {
                "GEMINI" -> callGeminiSafe("", fullPrompt)
                "GROQ" -> callGroqSafe("", fullPrompt)
                "OPENAI" -> callOpenAiSafe("", fullPrompt)
                "OPENROUTER" -> callOpenRouterSafe("", fullPrompt)
                else -> ProviderAttemptResult(false, error = "Unknown")
            }

            if (res.ok && !res.response.isNullOrBlank()) return@withContext res.response

            if (res.httpCode in 400..499 && res.httpCode != 429) block(provider, 10 * 60_000L)
            else if (res.httpCode == 429 || res.httpCode >= 500) block(provider, 60_000L)
        }
        null
    }

    suspend fun queryGeminiVision(bitmap: Bitmap, targetDescription: String): Pair<Float, Float>? = withContext(Dispatchers.IO) {
        val key = prefs.geminiApiKey
        if (key.isBlank()) return@withContext null

        try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val prompt = """
                Find UI element: "$targetDescription".
                Return only JSON {"x":int 0..1000,"y":int 0..1000}.
                If not found return {"x":-1,"y":-1}.
            """.trimIndent()

            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", base64)))
                })))
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }

            val model = normalizeGeminiModel(prefs.geminiModel)
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val raw = resp.body?.string().orEmpty()
                val text = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                val coord = JSONObject(cleanJsonOutput(text))
                val nx = coord.optDouble("x", -1.0)
                val ny = coord.optDouble("y", -1.0)
                if (nx < 0 || ny < 0) return@withContext null
                Pair((nx / 1000.0 * bitmap.width).toFloat(), (ny / 1000.0 * bitmap.height).toFloat())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanJsonOutput(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```json")) s = s.removePrefix("```json").trim()
        else if (s.startsWith("```")) s = s.removePrefix("```").trim()
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        return s.trim()
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

        if (clean.contains("torch") || clean.contains("flashlight")) {
            val off = clean.contains("off") || clean.contains("band")
            val state = if (off) "OFF" else "ON"
            return TaskPlan(
                originalQuery = query,
                intentKey = "TOGGLE_TORCH",
                steps = listOf(TaskStep("torch_1", StepType.TOGGLE_TORCH, mapOf("state" to state), "Torch toggle kar rahe hain")),
                speechResponseHinglish = if (off) "Torch off kar di." else "Torch on kar di."
            )
        }

        if (clean.contains("back")) {
            return TaskPlan(
                originalQuery = query,
                intentKey = "GO_BACK",
                steps = listOf(TaskStep("back_1", StepType.ACCESSIBILITY_GLOBAL, mapOf("action" to "BACK"), "Back ja rahe hain")),
                speechResponseHinglish = "Back ja rahi hoon."
            )
        }

        if (clean.contains("home")) {
            return TaskPlan(
                originalQuery = query,
                intentKey = "GO_HOME",
                steps = listOf(TaskStep("home_1", StepType.ACCESSIBILITY_GLOBAL, mapOf("action" to "HOME"), "Home open kar rahe hain")),
                speechResponseHinglish = "Home open kar diya."
            )
        }

        if (clean.contains("play store") || clean.startsWith("install ") || clean.contains(" install karo")) {
            val appName = clean
                .replace("play store", "")
                .replace("install karo", "")
                .replace("install", "")
                .trim()
                .ifBlank { "facebook" }

            return TaskPlan(
                originalQuery = query,
                intentKey = "PLAYSTORE_INSTALL_FLOW",
                steps = listOf(
                    TaskStep("ps_1", StepType.OPEN_APP, mapOf("appName" to "Play Store"), "Play Store open kar rahe hain"),
                    TaskStep("ps_2", StepType.ACCESSIBILITY_TAP_TEXT, mapOf("text" to "Search"), "Search pe tap kar rahe hain"),
                    TaskStep("ps_3", StepType.ACCESSIBILITY_TYPE, mapOf("text" to appName, "targetHint" to "Search"), "App type kar rahe hain"),
                    TaskStep("ps_4", StepType.ACCESSIBILITY_TAP_TEXT, mapOf("text" to appName), "App result select kar rahe hain"),
                    TaskStep("ps_5", StepType.ACCESSIBILITY_TAP_TEXT, mapOf("text" to "Install"), "Install pe tap kar rahe hain")
                ),
                speechResponseHinglish = "$appName install flow start kar diya."
            )
        }

        if (clean.contains("youtube")) {
            val q = clean.replace("youtube", "").replace("play", "").replace("chalao", "").trim().ifBlank { "trending songs" }
            return TaskPlan(
                originalQuery = query,
                intentKey = "PLAY_YOUTUBE",
                steps = listOf(TaskStep("yt_1", StepType.OPEN_APP, mapOf("appName" to "YouTube", "query" to q), "YouTube open kar rahe hain")),
                speechResponseHinglish = "YouTube pe $q chala rahi hoon."
            )
        }

        if (clean.contains("call")) {
            val who = clean.replace("call", "").replace("ko", "").trim().ifBlank { "contact" }
            return TaskPlan(
                originalQuery = query,
                intentKey = "CALL_PHONE",
                steps = listOf(
                    TaskStep("c_1", StepType.FIND_CONTACT, mapOf("name" to who), "Contact dhoond rahe hain"),
                    TaskStep("c_2", StepType.CALL_PHONE, mapOf("contactName" to who), "Call laga rahe hain")
                ),
                speechResponseHinglish = "$who ko call laga rahi hoon."
            )
        }

        if (clean in listOf("hi", "hello", "kya kr rhi h", "kya kar rahi ho", "kaise ho")) {
            return TaskPlan(
                originalQuery = query,
                intentKey = "CONVERSATION",
                steps = emptyList(),
                speechResponseHinglish = "Main yahan hoon. Batao kya kaam karna hai?",
                usedFallback = true,
                fallbackReason = "Local conversation"
            )
        }

        return TaskPlan(
            originalQuery = query,
            intentKey = "CONVERSATION",
            steps = emptyList(),
            speechResponseHinglish = "Command samajh liya. Thoda specific bolo, main execute karti hoon.",
            usedFallback = true,
            fallbackReason = "Local heuristic"
        )
    }
}
