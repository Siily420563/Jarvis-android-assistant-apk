package com.example.data.prefs

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("jarvis_neural_prefs", Context.MODE_PRIVATE)

    var groqApiKey: String
        get() = prefs.getString("groq_api_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_api_key", value).apply()

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_api_key", value).apply()

    var openRouterApiKey: String
        get() = prefs.getString("open_router_key", "") ?: ""
        set(value) = prefs.edit().putString("open_router_key", value).apply()

    var preferredLlm: String
        get() = prefs.getString("preferred_llm", "AUTO") ?: "AUTO" // AUTO, GROQ, GEMINI, OPENROUTER
        set(value) = prefs.edit().putString("preferred_llm", value).apply()

    var isFloatingBubbleEnabled: Boolean
        get() = prefs.getBoolean("floating_bubble_enabled", false)
        set(value) = prefs.edit().putBoolean("floating_bubble_enabled", value).apply()

    fun hasAnyApiKey(): Boolean {
        return groqApiKey.isNotBlank() || geminiApiKey.isNotBlank() || openRouterApiKey.isNotBlank()
    }
}
