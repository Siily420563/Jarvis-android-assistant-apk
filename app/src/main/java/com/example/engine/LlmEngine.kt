package com.example.engine

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
import java.util.concurrent.TimeUnit

class LlmEngine(private val prefs: PreferencesManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun queryJarvis(
        userInput: String,
        userMemoriesStr: String,
        activeAlarmsStr: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val systemPrompt = JarvisSystemPrompt.buildSystemPrompt(userMemoriesStr, activeAlarmsStr)

        // Ensure at least one API key exists
        if (!prefs.hasAnyApiKey()) {
            return@withContext Result.failure(
                IllegalStateException("NO_API_KEY: Boss, please configure at least one API key in the Brain Terminal.")
            )
        }

        // Tiered Execution Pipeline based on preferences & availability
        val preferred = prefs.preferredLlm

        if (preferred == "GROQ" && prefs.groqApiKey.isNotBlank()) {
            callGroq(systemPrompt, userInput)?.let { return@withContext Result.success(it) }
        } else if (preferred == "GEMINI" && prefs.geminiApiKey.isNotBlank()) {
            callGemini(systemPrompt, userInput)?.let { return@withContext Result.success(it) }
        } else if (preferred == "OPENROUTER" && prefs.openRouterApiKey.isNotBlank()) {
            callOpenRouter(systemPrompt, userInput)?.let { return@withContext Result.success(it) }
        }

        // Automatic Fallback Cascade
        // 1. Groq
        if (prefs.groqApiKey.isNotBlank()) {
            callGroq(systemPrompt, userInput)?.let { return@withContext Result.success(it) }
        }
        // 2. Gemini
        if (prefs.geminiApiKey.isNotBlank()) {
            callGemini(systemPrompt, userInput)?.let { return@withContext Result.success(it) }
        }
        // 3. OpenRouter
        if (prefs.openRouterApiKey.isNotBlank()) {
            callOpenRouter(systemPrompt, userInput)?.let { return@withContext Result.success(it) }
        }

        // 4. Rule-based Local Heuristics
        val offlineResponse = runLocalHeuristics(userInput)
        Result.success(offlineResponse)
    }

    private fun callGroq(systemPrompt: String, userInput: String): String? {
        val key = prefs.groqApiKey
        if (key.isBlank()) return null
        try {
            val jsonBody = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
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
                Log.e("LlmEngine", "Groq error: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("LlmEngine", "Groq exception", e)
        }
        return null
    }

    private fun callGemini(systemPrompt: String, userInput: String): String? {
        val key = prefs.geminiApiKey
        if (key.isBlank()) return null
        try {
            val combinedPrompt = "$systemPrompt\n\nUser command: $userInput"
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", combinedPrompt)
                            })
                        })
                    })
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key"
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
                Log.e("LlmEngine", "Gemini error: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("LlmEngine", "Gemini exception", e)
        }
        return null
    }

    private fun callOpenRouter(systemPrompt: String, userInput: String): String? {
        val key = prefs.openRouterApiKey
        if (key.isBlank()) return null
        try {
            val jsonBody = JSONObject().apply {
                put("model", "meta-llama/llama-3-8b-instruct:free")
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

    private fun runLocalHeuristics(query: String): String {
        val lower = query.lowercase()
        return when {
            lower.contains("alarm") || lower.contains("wake") || lower.contains("laga do") -> {
                val digits = Regex("\\d+").findAll(query).map { it.value.toInt() }.toList()
                val h = if (digits.isNotEmpty()) digits[0].coerceIn(0, 23) else 7
                val m = if (digits.size > 1) digits[1].coerceIn(0, 59) else 0
                "Right away, Boss. Setting an offline alarm for $h:${if (m < 10) "0$m" else m}. <action type=\"SET_ALARM\" hour=\"$h\" minute=\"$m\" label=\"Alarm\" />"
            }
            lower.contains("remember") || lower.contains("yaad") || lower.contains("keys") -> {
                "Cataloged in your offline memory index, Sir. <action type=\"REMEMBER\" fact=\"$query\" category=\"Location\" />"
            }
            lower.contains("youtube") -> {
                "Opening YouTube right away, Boss. <action type=\"OPEN_APP\" packageName=\"com.google.android.youtube\" url=\"https://www.youtube.com\" />"
            }
            lower.contains("home") -> {
                "Navigating to home screen, Boss. <action type=\"ACCESSIBILITY\" actionName=\"HOME\" />"
            }
            lower.contains("back") -> {
                "Navigating back, Sir. <action type=\"ACCESSIBILITY\" actionName=\"BACK\" />"
            }
            else -> {
                "Offline Neural Heuristics active, Boss. All local sub-routines are functioning within nominal parameters."
            }
        }
    }
}
