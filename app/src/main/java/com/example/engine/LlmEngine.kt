package com.example.engine

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.data.prefs.PreferencesManager
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

    // NEW: records why the last provider call failed, so callers can show the real reason
    // instead of silently handing back a generic canned reply.
    @Volatile
    var lastErrorReason: String = ""
        private set

    var onHttp4xxError: ((String) -> Unit)? = null

    @Volatile
    private var hadHttp4xxError = false
    @Volatile
    private var lastHttp4xxMessage = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun planAndQuery(
        userInput: String,
        userMemoriesStr: String,
        activeAlarmsStr: String,
        screenContextStr: String = "",
        conversationHistoryStr: String = "",
        interruptedTaskState: InterruptedTaskState? = null
    ): Result<TaskPlan> = withContext(Dispatchers.IO) {
        val systemPrompt = SaraSystemPrompt.buildSystemPrompt(
            persona = prefs.activePersona,
            assistantName = prefs.assistantName,
            userMemories = userMemoriesStr,
            activeAlarms = activeAlarmsStr,
            screenContext = screenContextStr,
            conversationHistory = conversationHistoryStr,
            interruptedTaskContext = interruptedTaskState?.summary() ?: ""
        )

        // Ensure at least one API key is present or fall back to local heuristics
        if (!prefs.hasAnyApiKey()) {
            val localPlan = runLocalHeuristicPlanner(userInput, interruptedTaskState)
            return@withContext Result.success(
                localPlan.copy(usedFallback = true, fallbackReason = "No API key configured in Settings")
            )
        }
        lastErrorReason = ""
        hadHttp4xxError = false
        lastHttp4xxMessage = ""

        val preferred = prefs.preferredLlm

        // 1. Check user preference first
        var responseJsonStr: String? = null
        try {
            if (preferred == "GEMINI" && prefs.geminiApiKey.isNotBlank()) {
                responseJsonStr = callGemini(systemPrompt, userInput)
            } else if (preferred == "GROQ" && prefs.groqApiKey.isNotBlank()) {
                responseJsonStr = callGroq(systemPrompt, userInput)
            } else if (preferred == "OPENAI" && prefs.openAiApiKey.isNotBlank()) {
                responseJsonStr = callOpenAi(systemPrompt, userInput)
            } else if (preferred == "OPENROUTER" && prefs.openRouterApiKey.isNotBlank()) {
                responseJsonStr = callOpenRouter(systemPrompt, userInput)
            }
        } catch (e: Exception) {
            Log.w("LlmEngine", "Preferred LLM call error: ${e.message}")
        }

        // 2. Cascade Fallback (Primary: Gemini -> Groq -> OpenAI -> OpenRouter)
        if (responseJsonStr == null && prefs.geminiApiKey.isNotBlank() && !hadHttp4xxError) {
            Log.d("LlmEngine", "Attempting Primary: Gemini")
            responseJsonStr = callGemini(systemPrompt, userInput)
        }
        if (responseJsonStr == null && prefs.groqApiKey.isNotBlank() && !hadHttp4xxError) {
            Log.d("LlmEngine", "Fallback to Groq")
            responseJsonStr = callGroq(systemPrompt, userInput)
        }
        if (responseJsonStr == null && prefs.openAiApiKey.isNotBlank() && !hadHttp4xxError) {
            Log.d("LlmEngine", "Fallback to OpenAI")
            responseJsonStr = callOpenAi(systemPrompt, userInput)
        }
        if (responseJsonStr == null && prefs.openRouterApiKey.isNotBlank() && !hadHttp4xxError) {
            Log.d("LlmEngine", "Fallback to OpenRouter")
            responseJsonStr = callOpenRouter(systemPrompt, userInput)
        }

        // When response was HTTP 4xx, surface the error and do not silently use heuristic planner
        if (hadHttp4xxError) {
            val errorPlan = TaskPlan(
                originalQuery = userInput,
                intentKey = "ERROR",
                steps = emptyList(),
                speechResponseHinglish = "API key ya model me problem hai",
                usedFallback = true,
                fallbackReason = lastHttp4xxMessage
            )
            return@withContext Result.success(errorPlan)
        }

        // Parse Plan
        if (!responseJsonStr.isNullOrBlank()) {
            val cleanJson = cleanJsonOutput(responseJsonStr)
            val parsedPlan = TaskPlan.fromJsonString(cleanJson)
            if (parsedPlan != null) {
                return@withContext Result.success(parsedPlan)
            } else {
                // If model returned pure conversational text instead of strict JSON
                val conversationalPlan = TaskPlan(
                    originalQuery = userInput,
                    intentKey = "CONVERSATION",
                    steps = emptyList(),
                    speechResponseHinglish = cleanJson.replace("{", "").replace("}", "").trim()
                )
                return@withContext Result.success(conversationalPlan)
            }
        }

        // Fallback to local heuristic planner — but now we say WHY instead of pretending
        // this was a normal, fully-reasoned reply.
        val fallbackPlan = runLocalHeuristicPlanner(userInput, interruptedTaskState)
        val reason = lastErrorReason.ifBlank { "AI provider returned no usable response" }
        Result.success(fallbackPlan.copy(usedFallback = true, fallbackReason = reason))
    }

    private fun callGemini(systemPrompt: String, userInput: String): String? {
        val key = prefs.geminiApiKey
        if (key.isBlank()) return null
        try {
            val jsonBody = JSONObject().apply {
                if (systemPrompt.isNotBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", systemPrompt)
                            })
                        })
                    })
                }
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", userInput)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingBudget", 0)
                    })
                    put("responseMimeType", "application/json")
                })
            }

            val model = prefs.geminiModel.ifBlank { "gemini-3.5-flash" }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: return null
                val json = JSONObject(respStr)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                    return parts.getJSONObject(0).getString("text")
                }
            } else {
                val errBody = response.body?.string() ?: ""
                Log.e("LlmEngine", "Gemini error: ${response.code} body: $errBody")
                val errorMsg = "Gemini HTTP ${response.code}${if (errBody.isNotBlank()) ": $errBody" else ""}"
                lastErrorReason = errorMsg
                if (response.code in 400..499) {
                    hadHttp4xxError = true
                    lastHttp4xxMessage = errorMsg
                    onHttp4xxError?.invoke(errorMsg)
                    com.example.audio.JarvisSpeechSynthesizer.instance?.speakLocal("API key ya model me problem hai")
                }
            }
        } catch (e: Exception) {
            Log.e("LlmEngine", "Gemini exception", e)
            lastErrorReason = "Gemini: ${e.message ?: e.javaClass.simpleName}"
        }
        return null
    }

    private fun callGroq(systemPrompt: String, userInput: String): String? {
        val key = prefs.groqApiKey
        if (key.isBlank()) return null
        try {
            val model = prefs.groqModel.ifBlank { "llama-3.3-70b-versatile" }
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userInput)
                    })
                })
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
                put("temperature", 0.3)
            }

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: return null
                val json = JSONObject(respStr)
                return json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } else {
                val errBody = response.body?.string() ?: ""
                Log.e("LlmEngine", "Groq error: ${response.code} body: $errBody")
                lastErrorReason = "Groq HTTP ${response.code}${if (errBody.isNotBlank()) ": ${errBody.take(120)}" else ""}"
            }
        } catch (e: Exception) {
            Log.e("LlmEngine", "Groq exception", e)
            lastErrorReason = "Groq: ${e.message ?: e.javaClass.simpleName}"
        }
        return null
    }

    private fun callOpenRouter(systemPrompt: String, userInput: String): String? {
        val key = prefs.openRouterApiKey
        if (key.isBlank()) return null
        try {
            val model = prefs.openRouterModel.ifBlank { "anthropic/claude-3.7-sonnet" }
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userInput)
                    })
                })
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
            }

            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: return null
                val json = JSONObject(respStr)
                return json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } else {
                Log.e("LlmEngine", "OpenRouter error: ${response.code}")
                lastErrorReason = "OpenRouter HTTP ${response.code}"
            }
        } catch (e: Exception) {
            Log.e("LlmEngine", "OpenRouter exception", e)
            lastErrorReason = "OpenRouter: ${e.message ?: e.javaClass.simpleName}"
        }
        return null
    }

    private fun callOpenAi(systemPrompt: String, userInput: String): String? {
        val key = prefs.openAiApiKey
        if (key.isBlank()) return null
        try {
            val baseUrl = prefs.openAiBaseUrl.trimEnd('/')
            val model = prefs.openAiModel.ifBlank { "gpt-4o-mini" }
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userInput)
                    })
                })
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
                put("temperature", 0.3)
            }

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: return null
                val json = JSONObject(respStr)
                return json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } else {
                val errBody = response.body?.string() ?: ""
                Log.e("LlmEngine", "OpenAI error: ${response.code} body: $errBody")
                lastErrorReason = "OpenAI HTTP ${response.code}${if (errBody.isNotBlank()) ": ${errBody.take(120)}" else ""}"
            }
        } catch (e: Exception) {
            Log.e("LlmEngine", "OpenAI exception", e)
            lastErrorReason = "OpenAI: ${e.message ?: e.javaClass.simpleName}"
        }
        return null
    }

    /**
     * Direct query for autonomous agent loop execution (AgentLoop).
     * Sends the complete prompt to the active provider and returns JSON string.
     */
    suspend fun queryPlan(fullPrompt: String): String? = withContext(Dispatchers.IO) {
        val preferred = prefs.preferredLlm
        var res: String? = null

        // Try preferred provider first
        try {
            if (preferred == "GEMINI" && prefs.geminiApiKey.isNotBlank()) {
                res = callGemini("", fullPrompt)
            } else if (preferred == "GROQ" && prefs.groqApiKey.isNotBlank()) {
                res = callGroq("", fullPrompt)
            } else if (preferred == "OPENAI" && prefs.openAiApiKey.isNotBlank()) {
                res = callOpenAi("", fullPrompt)
            } else if (preferred == "OPENROUTER" && prefs.openRouterApiKey.isNotBlank()) {
                res = callOpenRouter("", fullPrompt)
            }
        } catch (e: Exception) {
            Log.w("LlmEngine", "Direct query preferred error: ${e.message}")
        }

        // Fallback cascade
        if (res == null && prefs.geminiApiKey.isNotBlank()) {
            res = callGemini("", fullPrompt)
        }
        if (res == null && prefs.groqApiKey.isNotBlank()) {
            res = callGroq("", fullPrompt)
        }
        if (res == null && prefs.openAiApiKey.isNotBlank()) {
            res = callOpenAi("", fullPrompt)
        }
        if (res == null && prefs.openRouterApiKey.isNotBlank()) {
            res = callOpenRouter("", fullPrompt)
        }

        res
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
Look at this mobile app screenshot. Find the element described as: "$targetDescription".
Return the normalized center coordinates of this element as JSON with "x" and "y" between 0 and 1000.
Example: {"x": 500, "y": 850}
If not found, return {"x": -1, "y": -1}.
""".trimIndent()

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", base64Img)
                                })
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                })
            }

            val model = prefs.geminiModel.ifBlank { "gemini-3.5-flash" }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(respStr)
                val text = json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                val coordJson = JSONObject(cleanJsonOutput(text))
                val normX = coordJson.optDouble("x", -1.0)
                val normY = coordJson.optDouble("y", -1.0)
                if (normX >= 0 && normY >= 0) {
                    val actualX = (normX / 1000.0 * bitmap.width).toFloat()
                    val actualY = (normY / 1000.0 * bitmap.height).toFloat()
                    return@withContext Pair(actualX, actualY)
                }
            }
        } catch (e: Exception) {
            Log.e("LlmEngine", "Gemini vision failed", e)
        }
        return@withContext null
    }

    private fun cleanJsonOutput(raw: String): String {
        var str = raw.trim()
        if (str.startsWith("```json")) {
            str = str.removePrefix("```json").trim()
        } else if (str.startsWith("```")) {
            str = str.removePrefix("```").trim()
        }
        if (str.endsWith("```")) {
            str = str.removeSuffix("```").trim()
        }
        return str.trim()
    }

    private fun runLocalHeuristicPlanner(query: String, interruptedTask: InterruptedTaskState? = null): TaskPlan {
        val clean = query.trim().lowercase()
        val persona = prefs.activePersona

        // Check if user is resuming or modifying an interrupted task
        if (interruptedTask != null) {
            val oldPlan = interruptedTask.plan
            val isResume = clean == "continue" || clean == "resume" || clean == "aage badho" ||
                    clean == "chalu karo" || clean == "kar do" || clean == "bhej do" ||
                    clean == "chalu rakho" || clean.contains("bhejo") && clean.length < 10

            if (isResume) {
                val remaining = interruptedTask.remainingSteps()
                val reply = when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Theek hai, main aage ka task complete kar rahi hoon! 💕"
                    com.example.persona.PersonaType.PROFESSIONAL -> "Resuming pending execution pipeline, Sir."
                    com.example.persona.PersonaType.BOLD -> "Task wapis shuru kar diya."
                }
                return TaskPlan(query, "RESUME_TASK", remaining, reply)
            }

            // Contact correction: "nahi papa ko bhejo", "papa ko", "nahi rohit ko"
            val contactChangeMatch = Regex("(?:nahi\\s*)?([a-zA-Z0-9\u0900-\u097F]+)\\s*(?:ko\\s*(?:bhejo|call karo|message karo|send karo)?)?").find(clean)
            if (contactChangeMatch != null && (clean.startsWith("nahi ") || clean.endsWith(" ko") || clean.contains(" ko "))) {
                val newContact = contactChangeMatch.groupValues[1]
                if (newContact.isNotBlank() && newContact != "nahi" && newContact != "bhejo" && newContact != "call") {
                    val oldMsg = oldPlan.steps.firstOrNull { it.type == StepType.SEND_WHATSAPP }?.params?.get("message") ?: "Hello"
                    val reply = when (persona) {
                        com.example.persona.PersonaType.GIRLFRIEND -> "Samajh gayi! Ab $newContact ko WhatsApp bhej rahi hoon: '$oldMsg' ❤️"
                        com.example.persona.PersonaType.PROFESSIONAL -> "Rerouting task to $newContact: '$oldMsg', Sir."
                        com.example.persona.PersonaType.BOLD -> "Theek hai, $newContact ko bhej rahi hoon ab."
                    }
                    val s1 = TaskStep("step_1", StepType.FIND_CONTACT, mapOf("name" to newContact), "$newContact ka contact dhoond rahe hain...")
                    val s2 = TaskStep("step_2", StepType.SEND_WHATSAPP, mapOf("contactName" to newContact, "message" to oldMsg, "autoSend" to "true"), "WhatsApp pe message bhej rahe hain...")
                    return TaskPlan(query, "WHATSAPP_SEND", listOf(s1, s2), reply)
                }
            }

            // Message modification: "message badal do ki 8 baje aaunga", "bolo ki kal milte hain"
            if (clean.contains("message") || clean.contains("bolo ki") || clean.contains("ki ")) {
                val newMsg = clean.replace(Regex("^(message badal do|message change karo|bolo ki|ki)\\s*"), "").trim()
                if (newMsg.isNotBlank()) {
                    val targetContact = oldPlan.steps.firstOrNull { it.type == StepType.SEND_WHATSAPP }?.params?.get("contactName") ?: "contact"
                    val reply = when (persona) {
                        com.example.persona.PersonaType.GIRLFRIEND -> "Updated! $targetContact ko naya message bhej rahi hoon: '$newMsg' 💕"
                        com.example.persona.PersonaType.PROFESSIONAL -> "Message parameter updated for $targetContact: '$newMsg', Sir."
                        com.example.persona.PersonaType.BOLD -> "Message update kar diya: '$newMsg'."
                    }
                    val s1 = TaskStep("step_1", StepType.FIND_CONTACT, mapOf("name" to targetContact), "$targetContact ka contact dhoond rahe hain...")
                    val s2 = TaskStep("step_2", StepType.SEND_WHATSAPP, mapOf("contactName" to targetContact, "message" to newMsg, "autoSend" to "true"), "Updated message bhej rahe hain...")
                    return TaskPlan(query, "WHATSAPP_SEND", listOf(s1, s2), reply)
                }
            }
        }

        // 1. WhatsApp Intent Recognition (Hindi / Hinglish / English)
        if (clean.contains("whatsapp") || clean.contains("व्हाट्सएप") || clean.contains("वाट्सएप")) {
            var contactName = "mummy"
            var message = ""

            // Regex 1: "WhatsApp pe mummy ko bolo/message karo ki main khelne ja raha hoon..."
            val p1 = Regex("(?:whatsapp|व्हाट्सएप|वाट्सएप)\\s*(?:pe|par|पर)?\\s*([a-zA-Z0-9\u0900-\u097F]+)\\s*(?:ko|को)\\s*(?:bolo|bhejo|message karo|send karo|लिखो|बोलो|भेजो)?\\s*(?:ki|कि)?\\s*(.*)", RegexOption.IGNORE_CASE)
            val m1 = p1.find(clean)

            // Regex 2: "mummy ko WhatsApp pe bolo ki main aa raha hoon..."
            val p2 = Regex("([a-zA-Z0-9\u0900-\u097F]+)\\s*(?:ko|को)\\s*(?:whatsapp|व्हाट्सएप|वाट्सएप)\\s*(?:pe|par|पर)?\\s*(?:bolo|bhejo|message karo|send karo|बोलो|भेजो)?\\s*(?:ki|कि)?\\s*(.*)", RegexOption.IGNORE_CASE)
            val m2 = p2.find(clean)

            if (m1 != null) {
                contactName = m1.groupValues[1]
                message = m1.groupValues[2].trim()
            } else if (m2 != null) {
                contactName = m2.groupValues[1]
                message = m2.groupValues[2].trim()
            }

            if (message.isBlank()) {
                message = "Main thodi der me aata hoon."
            }

            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> "Haanji! Main WhatsApp pe $contactName ko message bhej rahi hoon: '$message' ❤️"
                com.example.persona.PersonaType.PROFESSIONAL -> "Sending WhatsApp message to $contactName: '$message', Sir."
                com.example.persona.PersonaType.BOLD -> "$contactName ko WhatsApp pe message bhej diya: '$message'. Done!"
            }

            val step1 = TaskStep("step_1", StepType.FIND_CONTACT, mapOf("name" to contactName), "$contactName ka contact dhoond rahe hain...")
            val step2 = TaskStep("step_2", StepType.SEND_WHATSAPP, mapOf("contactName" to contactName, "message" to message, "autoSend" to "true"), "WhatsApp pe message bhej rahe hain...")
            return TaskPlan(query, "WHATSAPP_SEND", listOf(step1, step2), reply)
        }

        // 2. YouTube Search & Play Intent
        if (clean.contains("youtube") || clean.contains("यूट्यूब")) {
            val p = Regex("(?:youtube|यूट्यूब)\\s*(?:pe|par|पर)?\\s*(?:search karo|play karo|chalao|laga do|खोजो|चलाओ)?\\s*(.*)", RegexOption.IGNORE_CASE)
            val m = p.find(clean)
            var searchQuery = m?.groupValues?.get(1)?.trim() ?: ""
            searchQuery = searchQuery
                .replace(Regex("(chalao|play karo|laga do|kholo|open karo|चलाओ|खोजो)$"), "")
                .trim()
            if (searchQuery.isBlank()) searchQuery = "trending songs"

            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> "YouTube pe '$searchQuery' play kar rahi hoon aapke liye! 🎵💕"
                com.example.persona.PersonaType.PROFESSIONAL -> "Searching and playing '$searchQuery' on YouTube, Sir."
                com.example.persona.PersonaType.BOLD -> "YouTube par '$searchQuery' chala diya. Enjoy!"
            }

            val step = TaskStep("step_yt", StepType.OPEN_APP, mapOf("appName" to "YouTube", "query" to searchQuery), "Opening YouTube for '$searchQuery'...")
            return TaskPlan(query, "PLAY_YOUTUBE", listOf(step), reply)
        }

        // 3. Torch / Flashlight Toggle
        if (clean.contains("torch") || clean.contains("flashlight") || clean.contains("टॉर्च") || clean.contains("फ्लैशलाइट")) {
            val isOff = clean.contains("off") || clean.contains("band") || clean.contains("बंद")
            val state = if (isOff) "OFF" else "ON"
            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> if (isOff) "Torch band kar di! ✨" else "Torch on kar di aapke liye! 💡❤️"
                com.example.persona.PersonaType.PROFESSIONAL -> "Flashlight $state, Sir."
                com.example.persona.PersonaType.BOLD -> if (isOff) "Torch OFF kar di." else "Torch ON kar di hai!"
            }
            val step = TaskStep("torch_toggle", StepType.TOGGLE_TORCH, mapOf("state" to state), "Toggling Flashlight to $state")
            return TaskPlan(query, "TOGGLE_TORCH", listOf(step), reply)
        }

        // 3b. Volume Controls (Heuristic fallback)
        if (clean.contains("volume") || clean.contains("awaz") || clean.contains("aawaz")) {
            val isMute = clean.contains("mute") || clean.contains("silent") || clean.contains("band")
            val isDown = clean.contains("kam") || clean.contains("ghata") || clean.contains("down") || clean.contains("low")
            val direction = if (isMute) "MUTE" else if (isDown) "DOWN" else "UP"
            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> if (isMute) "Phone mute kar diya! 🤫❤️" else if (isDown) "Volume kam kar diya aapke liye! 🔉" else "Volume badha diya! 🔊💕"
                com.example.persona.PersonaType.PROFESSIONAL -> "Volume adjusted ($direction), Sir."
                com.example.persona.PersonaType.BOLD -> "Volume $direction kar diya."
            }
            val step = TaskStep("vol_step", StepType.CONTROL_VOLUME, mapOf("direction" to direction), "Adjusting volume ($direction)")
            return TaskPlan(query, "CONTROL_VOLUME", listOf(step), reply)
        }

        // 3c. Quick Settings Panels (WiFi, Bluetooth)
        if (clean.contains("wifi") && (clean.contains("setting") || clean.contains("kholo") || clean.contains("open"))) {
            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> "Wi-Fi settings open kar di! 📶✨"
                com.example.persona.PersonaType.PROFESSIONAL -> "Opening Wi-Fi configuration, Sir."
                com.example.persona.PersonaType.BOLD -> "Wi-Fi settings khol di."
            }
            val step = TaskStep("wifi_step", StepType.OPEN_QUICK_SETTING, mapOf("panel" to "WIFI"), "Opening Wi-Fi settings")
            return TaskPlan(query, "OPEN_WIFI", listOf(step), reply)
        }

        if (clean.contains("bluetooth") && (clean.contains("setting") || clean.contains("kholo") || clean.contains("open"))) {
            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> "Bluetooth settings open kar di! ᛒ💕"
                com.example.persona.PersonaType.PROFESSIONAL -> "Opening Bluetooth configuration, Sir."
                com.example.persona.PersonaType.BOLD -> "Bluetooth settings khol di."
            }
            val step = TaskStep("bt_step", StepType.OPEN_QUICK_SETTING, mapOf("panel" to "BLUETOOTH"), "Opening Bluetooth settings")
            return TaskPlan(query, "OPEN_BLUETOOTH", listOf(step), reply)
        }

        // 3d. Media Playback Controls (Heuristic fallback)
        if (clean.contains("gaana") || clean.contains("music") || clean.contains("song") || clean == "pause" || clean == "play") {
            if (clean.contains("pause") || clean.contains("roko") || clean.contains("band")) {
                val reply = when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Gaana pause kar diya! ⏸️"
                    com.example.persona.PersonaType.PROFESSIONAL -> "Media playback paused, Sir."
                    com.example.persona.PersonaType.BOLD -> "Gaana rok diya."
                }
                val step = TaskStep("media_step", StepType.MEDIA_CONTROL, mapOf("action" to "PAUSE"), "Pausing music")
                return TaskPlan(query, "MEDIA_CONTROL", listOf(step), reply)
            } else if (clean.contains("next") || clean.contains("agla") || clean.contains("badlo") || clean.contains("skip")) {
                val reply = when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Agla gaana chala diya! ⏭️✨"
                    com.example.persona.PersonaType.PROFESSIONAL -> "Skipping to next track, Sir."
                    com.example.persona.PersonaType.BOLD -> "Agla track chalu kar diya."
                }
                val step = TaskStep("media_step", StepType.MEDIA_CONTROL, mapOf("action" to "NEXT"), "Skipping track")
                return TaskPlan(query, "MEDIA_CONTROL", listOf(step), reply)
            } else if (clean.contains("prev") || clean.contains("pichhla")) {
                val reply = when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Pichhla gaana chala diya! ⏮️"
                    com.example.persona.PersonaType.PROFESSIONAL -> "Returning to previous track, Sir."
                    com.example.persona.PersonaType.BOLD -> "Pichhla gaana laga diya."
                }
                val step = TaskStep("media_step", StepType.MEDIA_CONTROL, mapOf("action" to "PREVIOUS"), "Previous track")
                return TaskPlan(query, "MEDIA_CONTROL", listOf(step), reply)
            } else if (clean.contains("play") || clean.contains("chalao") || clean.contains("bajao") || clean.contains("resume")) {
                val reply = when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Gaana play kar diya! 🎶💕"
                    com.example.persona.PersonaType.PROFESSIONAL -> "Resuming media playback, Sir."
                    com.example.persona.PersonaType.BOLD -> "Gaana chala diya."
                }
                val step = TaskStep("media_step", StepType.MEDIA_CONTROL, mapOf("action" to "PLAY"), "Playing music")
                return TaskPlan(query, "MEDIA_CONTROL", listOf(step), reply)
            }
        }

        // 3e. Battery Status (Heuristic fallback)
        if (clean.contains("battery") || clean.contains("charge")) {
            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> "Phone ki battery status check kar rahi hoon baby! 💕"
                com.example.persona.PersonaType.PROFESSIONAL -> "Querying device battery telemetry, Sir."
                com.example.persona.PersonaType.BOLD -> "Battery check ki ja rahi hai."
            }
            val step = TaskStep("batt_step", StepType.CHECK_BATTERY, emptyMap(), "Checking battery level")
            return TaskPlan(query, "CHECK_BATTERY", listOf(step), reply)
        }

        // 3f. Navigation & Maps (Heuristic fallback)
        if (clean.contains("rasta") || clean.contains("navigate") || clean.contains("direction")) {
            val dest = clean
                .replace(Regex("^(navigate to|direction to|directions to|take me to|rasta dikhao|rasta batao)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(ka rasta dikhao|ka rasta batao|ka rasta)$", RegexOption.IGNORE_CASE), "")
                .trim()
            val target = if (dest.isNotBlank()) dest else "Destination"
            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> "$target ka rasta Maps pe chalu kar diya! Dhyan se jana 💕🗺️"
                com.example.persona.PersonaType.PROFESSIONAL -> "Opening navigation routes to $target, Sir."
                com.example.persona.PersonaType.BOLD -> "$target ka rasta Maps pe khol diya."
            }
            val step = TaskStep("nav_step", StepType.NAVIGATE_TO, mapOf("destination" to target), "Navigating to $target")
            return TaskPlan(query, "NAVIGATE_TO", listOf(step), reply)
        }

        // 3g. Camera & Selfie (Heuristic fallback)
        if (clean.contains("selfie") || clean.contains("camera") || clean.contains("photo") || clean.contains("video")) {
            val mode = if (clean.contains("selfie") || clean.contains("front")) "SELFIE" else if (clean.contains("video")) "VIDEO" else "PHOTO"
            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> if (mode == "SELFIE") "Selfie camera khol diya! Smile please! 📸💕" else "Camera open kar diya! ✨"
                com.example.persona.PersonaType.PROFESSIONAL -> "Launching camera in $mode mode, Sir."
                com.example.persona.PersonaType.BOLD -> "Camera mode ($mode) chalu."
            }
            val step = TaskStep("cam_step", StepType.OPEN_CAMERA, mapOf("mode" to mode), "Opening camera ($mode)")
            return TaskPlan(query, "OPEN_CAMERA", listOf(step), reply)
        }

        // 3h. Screenshot (Heuristic fallback)
        if (clean.contains("screenshot")) {
            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> "Screenshot le liya! 📸✨"
                com.example.persona.PersonaType.PROFESSIONAL -> "Screenshot captured, Sir."
                com.example.persona.PersonaType.BOLD -> "Screenshot capture ho gaya."
            }
            val step = TaskStep("screen_step", StepType.ACCESSIBILITY_GLOBAL, mapOf("action" to "SCREENSHOT"), "Capturing screenshot")
            return TaskPlan(query, "SCREENSHOT", listOf(step), reply)
        }

        // 4. Calling Intent
        if (clean.contains("call") || clean.contains("phone lagao") || clean.contains("कॉल") || clean.contains("फोन लगाओ")) {
            val p = Regex("([a-zA-Z0-9\u0900-\u097F]+)\\s*(?:ko|को)?\\s*(?:call|phone lagao|कॉल|फोन लगाओ)", RegexOption.IGNORE_CASE)
            val m = p.find(clean)
            val contactName = m?.groupValues?.get(1) ?: "Contact"

            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> "Main $contactName ko call laga rahi hoon abhi! 📞✨"
                com.example.persona.PersonaType.PROFESSIONAL -> "Initiating voice call to $contactName, Sir."
                com.example.persona.PersonaType.BOLD -> "$contactName ko call lagaya ja raha hai."
            }
            val step1 = TaskStep("step_1", StepType.FIND_CONTACT, mapOf("name" to contactName), "$contactName ka number dhoond rahe hain...")
            val step2 = TaskStep("step_2", StepType.CALL_PHONE, mapOf("contactName" to contactName), "Call connect kar rahe hain...")
            return TaskPlan(query, "CALL_PHONE", listOf(step1, step2), reply)
        }

        // 5. Google / Web Search Intent
        if (clean.contains("google pe") || clean.contains("search karo") || clean.contains("गूगल") || clean.contains("सर्च करो")) {
            val p = Regex("(?:google pe|search karo|गूगल पर|सर्च करो)\\s*(.*)", RegexOption.IGNORE_CASE)
            val m = p.find(clean)
            val webQuery = m?.groupValues?.get(1)?.trim()?.ifBlank { "Latest news" } ?: "Latest news"

            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> "Main Google pe '$webQuery' search kar rahi hoon! 🔍❤️"
                com.example.persona.PersonaType.PROFESSIONAL -> "Searching '$webQuery' on Google, Sir."
                com.example.persona.PersonaType.BOLD -> "Google par '$webQuery' search ho raha hai."
            }
            val step = TaskStep("step_web", StepType.SEARCH_WEB, mapOf("query" to webQuery), "Searching Google for '$webQuery'...")
            return TaskPlan(query, "SEARCH_GOOGLE", listOf(step), reply)
        }

        // 6. Conversational Chit-Chat & Companionship
        val reply = when {
            clean.contains("i love you") || clean.contains("love you") || clean.contains("प्यार") -> {
                when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Aww! I love you too so much! Aap mere sabse favorite person ho! 💕😘"
                    com.example.persona.PersonaType.PROFESSIONAL -> "I am programmed to serve you with the highest fidelity and loyalty, Sir."
                    com.example.persona.PersonaType.BOLD -> "Arey waah! Dil jeet liya aapne, par chalo ab koi kaam bhi batao!"
                }
            }
            clean.contains("kya kar rahi ho") || clean.contains("kya kar rahe ho") || clean.contains("what are you doing") -> {
                when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Bas aapka hi intezar kar rahi thi! Aap batao aapka din kaisa ja raha hai? ❤️"
                    com.example.persona.PersonaType.PROFESSIONAL -> "All neural sub-systems are running optimally and awaiting your commands, Sir."
                    com.example.persona.PersonaType.BOLD -> "Aapka hukum follow karne ke liye tayyar baithi hoon. Bolo kya karna hai!"
                }
            }
            clean.contains("kaise ho") || clean.contains("kya haal hai") || clean.contains("how are you") -> {
                when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Main bahut khush hoon kyunki aap mere saath ho! Aap batao aap kaise ho? 💕"
                    com.example.persona.PersonaType.PROFESSIONAL -> "System health is 100%, operational parameters nominal, Sir."
                    com.example.persona.PersonaType.BOLD -> "Ekdum mast! Aap batao kya chal raha hai?"
                }
            }
            clean.contains("tum kaun ho") || clean.contains("who are you") || clean.contains("naam kya hai") -> {
                when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Main SARA hoon—aapki sweet personal AI assistant aur companion! ❤️"
                    com.example.persona.PersonaType.PROFESSIONAL -> "I am SARA, your autonomous Android voice automation and task execution intelligence."
                    com.example.persona.PersonaType.BOLD -> "Mera naam SARA hai. Ek command do aur dekho main kya-kya kar sakti hoon!"
                }
            }
            clean.contains("khana khaya") || clean.contains("dinner kiya") || clean.contains("lunch kiya") -> {
                when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Mera khana toh electricity aur aapki baatein hain! Par aapne time pe khana khaya na? ❤️"
                    com.example.persona.PersonaType.PROFESSIONAL -> "I do not require nutrition, Sir. Please ensure your own meals are scheduled on time."
                    com.example.persona.PersonaType.BOLD -> "Battery 100% charged hai mera! Aap apna pet bhar lo pehle!"
                }
            }
            clean.contains("bore") || clean.contains("kuch interesting") || clean.contains("shayari") -> {
                when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Ek pyari shayari aapke liye: 'Aapki baaton mein kuch aisi baat hai, har lamha lagta jaise nayi shuruat hai!' Kaisi lagi? 💕"
                    com.example.persona.PersonaType.PROFESSIONAL -> "I can play music on YouTube, set your schedule, or execute any automated phone tasks for you, Sir."
                    com.example.persona.PersonaType.BOLD -> "Bore kyun ho rahe ho jab main hoon? Bolo toh mast YouTube pe koi trending comedy video chala doon?"
                }
            }
            else -> {
                when (persona) {
                    com.example.persona.PersonaType.GIRLFRIEND -> "Maine aapki baat sun li! Main hamesha aapke saath hoon, batao aur kya karun? 💕"
                    com.example.persona.PersonaType.PROFESSIONAL -> "Command acknowledged, Sir. Ready to execute your instructions."
                    com.example.persona.PersonaType.BOLD -> "Sun liya maine! Batao agla task kya hai, turant nipatate hain."
                }
            }
        }

        return TaskPlan(query, "CONVERSATION", emptyList(), reply)
    }
}
