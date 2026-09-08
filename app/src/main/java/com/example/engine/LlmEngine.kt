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

        if (interruptedTask != null && (clean == "continue" || clean == "resume" || clean.contains("aage") || clean.contains("bhej do") || clean.contains("kar do"))) {
            return TaskPlan(
                originalQuery = query,
                intentKey = "RESUME_TASK",
                steps = interruptedTask.remainingSteps(),
                speechResponseHinglish = "Theek hai, paused task resume kar rahi hoon."
            )
        }

        // Torch
        if (clean.contains("torch") || clean.contains("flashlight") || clean.contains("lumos")) {
            val off = clean.contains("off") || clean.contains("band") || clean.contains("nox")
            val state = if (off) "OFF" else "ON"
            return TaskPlan(
                originalQuery = query,
                intentKey = "TOGGLE_TORCH",
                steps = listOf(TaskStep("torch_1", StepType.TOGGLE_TORCH, mapOf("state" to state), "Torch toggle kar rahe hain")),
                speechResponseHinglish = if (off) "Torch off kar di." else "Torch on kar di."
            )
        }

        // Volume
        if (clean.contains("volume") || clean.contains("awaz") || clean.contains("aawaz") || clean.contains("sound")) {
            val direction = when {
                clean.contains("mute") || clean.contains("silent") -> "MUTE"
                clean.contains("kam") || clean.contains("down") || clean.contains("low") -> "DOWN"
                else -> "UP"
            }
            return TaskPlan(
                originalQuery = query,
                intentKey = "VOLUME_$direction",
                steps = listOf(TaskStep("vol_1", StepType.CONTROL_VOLUME, mapOf("direction" to direction, "stream" to "MEDIA"), "Volume adjust kar rahe hain")),
                speechResponseHinglish = when(direction) { "MUTE" -> "Mute kar diya." "DOWN" -> "Volume kam kar diya." else -> "Volume badha diya." }
            )
        }

        // WhatsApp - broader heuristic covering Hinglish variants
        if (clean.contains("whatsapp") || (clean.contains("whats app") ) || (clean.contains("msg") && (clean.contains("mummy") || clean.contains("papa") || clean.contains("mom") || clean.contains("bhej"))) ) {
            // Extract contact and message heuristically
            var contact = "Mummy"
            var message = "Hello"
            // Common pattern: "whatsapp pe mummy ko bolo ..." or "mummy ko whatsapp karo ..."
            val contactCandidates = listOf("mummy", "papa", "mom", "dad", "bhai", "didi", "friend", "boss", "sir")
            for (c in contactCandidates) if (clean.contains(c)) { contact = c.replaceFirstChar { it.uppercase() }; break }
            // If "ko" present, try to extract name before ko
            if (clean.contains(" ko ")) {
                val beforeKo = clean.substringBefore(" ko ").trim().split(" ").lastOrNull()
                if (!beforeKo.isNullOrBlank() && beforeKo.length > 2) contact = beforeKo.replaceFirstChar { it.uppercase() }
            }
            val boloIdx = clean.indexOf("bolo")
            val bhejoIdx = clean.indexOf("bhej")
            val msgStart = when {
                boloIdx != -1 -> boloIdx + 4
                bhejoIdx != -1 -> bhejoIdx + 4
                else -> -1
            }
            if (msgStart != -1 && msgStart < clean.length) {
                message = query.substring(msgStart).trim().ifBlank { "Hello" }
                // Clean up common prefixes
                message = message.removePrefix("ki ").removePrefix("ke ").trim()
            } else if (clean.contains("hello") || clean.contains("hi")) {
                message = "Hello"
            }
            // Safety: truncate long message
            if (message.length > 120) message = message.take(120)
            return TaskPlan(
                originalQuery = query,
                intentKey = "WHATSAPP_${contact.uppercase()}",
                steps = listOf(
                    TaskStep("wa_1", StepType.FIND_CONTACT, mapOf("name" to contact), "$contact dhoond rahe hain"),
                    TaskStep("wa_2", StepType.SEND_WHATSAPP, mapOf("contactName" to contact, "message" to message, "autoSend" to "true"), "WhatsApp message bhej rahe hain")
                ),
                speechResponseHinglish = "$contact ko WhatsApp pe message bhej rahi hoon: $message"
            )
        }

        // Generic open app
        if (clean.startsWith("open ") || clean.endsWith(" kholo") || clean.endsWith(" khol do") || clean.endsWith(" open karo") || clean.startsWith("launch ")) {
            val appMap = mapOf(
                "youtube" to "YouTube", "whatsapp" to "WhatsApp", "instagram" to "Instagram",
                "chrome" to "Chrome", "camera" to "Camera", "settings" to "Settings",
                "play store" to "Play Store", "maps" to "Maps", "photos" to "Photos",
                "gmail" to "Gmail", "spotify" to "Spotify", "telegram" to "Telegram",
                "calculator" to "Calculator", "clock" to "Clock", "phone" to "Phone"
            )
            for ((k, v) in appMap) if (clean.contains(k)) {
                return TaskPlan(
                    originalQuery = query,
                    intentKey = "OPEN_${k.uppercase()}",
                    steps = listOf(TaskStep("open_1", StepType.OPEN_APP, mapOf("appName" to v), "$v open kar rahe hain")),
                    speechResponseHinglish = "$v open kar diya!"
                )
            }
            // Fallback generic open
            val appName = clean.replace("open", "").replace("kholo", "").replace("khol do", "").replace("launch", "").trim().ifBlank { "YouTube" }
            return TaskPlan(
                originalQuery = query,
                intentKey = "OPEN_APP",
                steps = listOf(TaskStep("open_1", StepType.OPEN_APP, mapOf("appName" to appName), "$appName open kar rahe hain")),
                speechResponseHinglish = "$appName open kar rahi hoon."
            )
        }

        // Navigation
        if (clean.contains("navigate") || clean.contains("navigation") || clean.contains("rasta") || (clean.contains("chalo") && clean.contains("maps"))) {
            var dest = clean.replace("navigate to", "").replace("navigation", "").replace("rasta dikhao", "").replace("chalo", "").replace("maps pe", "").trim()
            if (dest.isBlank()) dest = "India Gate"
            return TaskPlan(
                originalQuery = query,
                intentKey = "NAVIGATE_TO",
                steps = listOf(TaskStep("nav_1", StepType.NAVIGATE_TO, mapOf("destination" to dest), "$dest navigation chalu kar rahe hain")),
                speechResponseHinglish = "$dest ka navigation chalu kar diya."
            )
        }

        // Alarm
        if (clean.contains("alarm") || clean.contains("jagana") || clean.contains("uthana")) {
            val (h, m) = parseTimeFromQuery(clean)
            val timeStr = String.format(java.util.Locale.getDefault(), "%02d:%02d", h, m)
            return TaskPlan(
                originalQuery = query,
                intentKey = "SET_ALARM_$timeStr",
                steps = listOf(TaskStep("alarm_1", StepType.SET_ALARM, mapOf("hour" to h.toString(), "minute" to m.toString(), "label" to "Alarm"), "$timeStr ka alarm laga rahe hain")),
                speechResponseHinglish = "$timeStr ka alarm set kar diya!"
            )
        }

        // Scroll
        if (clean.contains("scroll")) {
            val dir = if (clean.contains("up") || clean.contains("upar")) "UP" else "DOWN"
            return TaskPlan(
                originalQuery = query,
                intentKey = "SCROLL_$dir",
                steps = listOf(TaskStep("scroll_1", StepType.ACCESSIBILITY_SCROLL, mapOf("direction" to dir), "Scroll $dir kar rahe hain")),
                speechResponseHinglish = if (dir == "UP") "Upar scroll kar diya." else "Neeche scroll kar diya."
            )
        }

        if (clean.contains("back") || clean == "peeche jao") {
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

        if (clean.contains("call") || clean.contains("phone karo")) {
            var who = clean.replace("call", "").replace("phone karo", "").replace("ko", "").replace("lagao", "").trim()
            if (who.isBlank()) who = "contact"
            // pick last word as name
            who = who.split(" ").lastOrNull()?.ifBlank { "contact" } ?: "contact"
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

        if (clean.contains("search") || clean.contains("google karo") || clean.contains("dhundo")) {
            val q = clean.replace("search", "").replace("google karo", "").replace("dhundo", "").trim().ifBlank { query }
            return TaskPlan(
                originalQuery = query,
                intentKey = "SEARCH_WEB",
                steps = listOf(TaskStep("search_1", StepType.SEARCH_WEB, mapOf("query" to q), "Web search kar rahe hain")),
                speechResponseHinglish = "$q ke liye search kar rahi hoon."
            )
        }

        if (clean in listOf("hi", "hello", "hey", "hi sara", "hello sara", "kya kr rhi h", "kya kar rahi ho", "kaise ho", "how are you")) {
            return TaskPlan(
                originalQuery = query,
                intentKey = "CONVERSATION",
                steps = emptyList(),
                speechResponseHinglish = "Main yahan hoon, batao kya kaam karna hai?",
                usedFallback = true,
                fallbackReason = "Local conversation"
            )
        }

        // Battery check
        if (clean.contains("battery") || clean.contains("charge")) {
            return TaskPlan(
                originalQuery = query,
                intentKey = "CHECK_BATTERY",
                steps = listOf(TaskStep("bat_1", StepType.CHECK_BATTERY, emptyMap(), "Battery check kar rahe hain")),
                speechResponseHinglish = "Battery status check kar rahi hoon."
            )
        }

        return TaskPlan(
            originalQuery = query,
            intentKey = "CONVERSATION",
            steps = emptyList(),
            speechResponseHinglish = "Samajh gayi! Lekin is command ke liye internet ya API key chahiye. Settings me API key add kariye ya thoda specific task bolo jaise 'YouTube kholo' ya 'Torch on karo'.",
            usedFallback = true,
            fallbackReason = "Local heuristic - no matching task"
        )
    }

    private fun parseTimeFromQuery(text: String): Pair<Int, Int> {
        val clean = text.lowercase()
        val isPm = clean.contains("pm") || clean.contains("sham") || clean.contains("raat") || clean.contains("dopahar")
        val isAm = clean.contains("am") || clean.contains("subah")

        val timeRegex = Regex("(\\d{1,2}):(\\d{2})")
        val match = timeRegex.find(clean)
        if (match != null) {
            var h = match.groupValues[1].toIntOrNull() ?: 7
            val m = match.groupValues[2].toIntOrNull() ?: 0
            if (isPm && h < 12) h += 12
            if (isAm && h == 12) h = 0
            return Pair(h.coerceIn(0, 23), m.coerceIn(0, 59))
        }

        val digits = Regex("\\d+").findAll(clean).map { it.value.toInt() }.toList()
        if (digits.isNotEmpty()) {
            var h = digits[0]
            val m = if (digits.size > 1) digits[1] else 0
            if (isPm && h < 12) h += 12
            if (isAm && h == 12) h = 0
            return Pair(h.coerceIn(0, 23), m.coerceIn(0, 59))
        }

        return Pair(7, 0)
    }
}
