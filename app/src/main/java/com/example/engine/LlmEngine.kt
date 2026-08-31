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

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun planAndQuery(
        userInput: String,
        userMemoriesStr: String,
        activeAlarmsStr: String,
        screenContextStr: String = ""
    ): Result<TaskPlan> = withContext(Dispatchers.IO) {
        val systemPrompt = SaraSystemPrompt.buildSystemPrompt(
            persona = prefs.activePersona,
            assistantName = prefs.assistantName,
            userMemories = userMemoriesStr,
            activeAlarms = activeAlarmsStr,
            screenContext = screenContextStr
        )

        // Ensure at least one API key is present or fall back to local heuristics
        if (!prefs.hasAnyApiKey()) {
            val localPlan = runLocalHeuristicPlanner(userInput)
            return@withContext Result.success(localPlan)
        }

        val preferred = prefs.preferredLlm

        // 1. Check user preference first
        var responseJsonStr: String? = null
        if (preferred == "GEMINI" && prefs.geminiApiKey.isNotBlank()) {
            responseJsonStr = callGemini(systemPrompt, userInput)
        } else if (preferred == "GROQ" && prefs.groqApiKey.isNotBlank()) {
            responseJsonStr = callGroq(systemPrompt, userInput)
        } else if (preferred == "OPENROUTER" && prefs.openRouterApiKey.isNotBlank()) {
            responseJsonStr = callOpenRouter(systemPrompt, userInput)
        }

        // 2. Cascade Fallback (Primary: Gemini -> Groq -> OpenRouter)
        if (responseJsonStr == null && prefs.geminiApiKey.isNotBlank()) {
            Log.d("LlmEngine", "Attempting Primary: Gemini")
            responseJsonStr = callGemini(systemPrompt, userInput)
        }
        if (responseJsonStr == null && prefs.groqApiKey.isNotBlank()) {
            Log.d("LlmEngine", "Fallback to Groq")
            responseJsonStr = callGroq(systemPrompt, userInput)
        }
        if (responseJsonStr == null && prefs.openRouterApiKey.isNotBlank()) {
            Log.d("LlmEngine", "Fallback to OpenRouter")
            responseJsonStr = callOpenRouter(systemPrompt, userInput)
        }

        // Parse Plan
        if (!responseJsonStr.isNullOrBlank()) {
            val cleanJson = cleanJsonOutput(responseJsonStr)
            val parsedPlan = TaskPlan.fromJsonString(cleanJson)
            if (parsedPlan != null) {
                return@withContext Result.success(parsedPlan)
            }
        }

        // Fallback to local heuristic planner
        val fallbackPlan = runLocalHeuristicPlanner(userInput)
        Result.success(fallbackPlan)
    }

    private fun callGemini(systemPrompt: String, userInput: String): String? {
        val key = prefs.geminiApiKey
        if (key.isBlank()) return null
        try {
            val fullPrompt = "$systemPrompt\n\nUser Request: $userInput"
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", fullPrompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.3)
                    put("responseMimeType", "application/json")
                })
            }

            val model = prefs.geminiModel.ifBlank { "gemini-3.7-flash" }
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
                Log.e("LlmEngine", "Gemini error: ${response.code} body: ${response.body?.string()}")
            }
        } catch (e: Exception) {
            Log.e("LlmEngine", "Gemini exception", e)
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
                Log.e("LlmEngine", "Groq error: ${response.code} body: ${response.body?.string()}")
            }
        } catch (e: Exception) {
            Log.e("LlmEngine", "Groq exception", e)
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
            }
        } catch (e: Exception) {
            Log.e("LlmEngine", "OpenRouter exception", e)
        }
        return null
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
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
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

            val model = prefs.geminiModel.ifBlank { "gemini-3.7-flash" }
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

    private fun runLocalHeuristicPlanner(query: String): TaskPlan {
        val clean = query.lowercase()
        val persona = prefs.activePersona

        // Check WhatsApp message intent
        if (clean.contains("whatsapp") && (clean.contains("bolo") || clean.contains("send") || clean.contains("message") || clean.contains("bhejo"))) {
            var contactName = "Contact"
            val msgMatch = Regex("(?:ko|pe|par)\\s+whatsapp\\s+(?:pe|par)?\\s*(?:bolo|message karo|bhejo)?\\s*(.*)", RegexOption.IGNORE_CASE).find(clean)
            val extractedMessage = msgMatch?.groupValues?.get(1)?.ifBlank { "Hello" } ?: "Hello"

            val contactMatch = Regex("([a-zA-Z0-9]+)\\s+ko\\s+whatsapp", RegexOption.IGNORE_CASE).find(clean)
            if (contactMatch != null) {
                contactName = contactMatch.groupValues[1]
            }

            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> "Haanji! Main $contactName ko WhatsApp message bhej rahi hoon: '$extractedMessage' ❤️"
                com.example.persona.PersonaType.PROFESSIONAL -> "Sending WhatsApp message to $contactName: '$extractedMessage', Sir."
                com.example.persona.PersonaType.BOLD -> "$contactName ko WhatsApp pe message bhej diya. Done!"
            }

            val step1 = TaskStep("step_1", StepType.FIND_CONTACT, mapOf("name" to contactName), "$contactName ka number dhoond rahe hain...")
            val step2 = TaskStep("step_2", StepType.SEND_WHATSAPP, mapOf("contactName" to contactName, "message" to extractedMessage), "WhatsApp pe message bhej rahe hain...")
            return TaskPlan(query, "WHATSAPP_SEND", listOf(step1, step2), reply)
        }

        // Check Call intent
        if (clean.contains("call") || clean.contains("phone lagao")) {
            val contactMatch = Regex("([a-zA-Z0-9]+)\\s+ko\\s+call", RegexOption.IGNORE_CASE).find(clean)
            val contactName = contactMatch?.groupValues?.get(1) ?: "Contact"

            val reply = when (persona) {
                com.example.persona.PersonaType.GIRLFRIEND -> "Main $contactName ko call laga rahi hoon! ✨"
                com.example.persona.PersonaType.PROFESSIONAL -> "Initiating voice call to $contactName, Sir."
                com.example.persona.PersonaType.BOLD -> "$contactName ko call lagaya ja raha hai."
            }
            val step1 = TaskStep("step_1", StepType.FIND_CONTACT, mapOf("name" to contactName), "$contactName ka contact dhoond rahe hain...")
            val step2 = TaskStep("step_2", StepType.CALL_PHONE, mapOf("contactName" to contactName), "Call connect kar rahe hain...")
            return TaskPlan(query, "CALL_CONTACT", listOf(step1, step2), reply)
        }

        // Default conversational reply
        val reply = when (persona) {
            com.example.persona.PersonaType.GIRLFRIEND -> "Maine aapki baat sun li! Aur batao main aapke liye kya kar sakti hoon? 💕"
            com.example.persona.PersonaType.PROFESSIONAL -> "Command acknowledged, Sir. All sub-routines operational."
            com.example.persona.PersonaType.BOLD -> "Suna maine. Koi specific task ho toh batao, turant karte hain!"
        }
        return TaskPlan(query, "CONVERSATION", emptyList(), reply)
    }
}
