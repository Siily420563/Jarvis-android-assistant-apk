package com.example.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.example.persona.PersonaType

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sara_neural_prefs", Context.MODE_PRIVATE)

    var assistantName: String
        get() = prefs.getString("assistant_name", "SARA") ?: "SARA"
        set(value) = prefs.edit().putString("assistant_name", value.ifBlank { "SARA" }).apply()

    var activePersona: PersonaType
        get() {
            val name = prefs.getString("active_persona", PersonaType.GIRLFRIEND.name)
            return try {
                PersonaType.valueOf(name ?: PersonaType.GIRLFRIEND.name)
            } catch (e: Exception) {
                PersonaType.GIRLFRIEND
            }
        }
        set(value) = prefs.edit().putString("active_persona", value.name).apply()

    var geminiApiKey: String
        get() {
            val saved = prefs.getString("gemini_api_key", "") ?: ""
            if (saved.isNotBlank()) return saved
            return try {
                val field = com.example.BuildConfig::class.java.getField("GEMINI_API_KEY")
                val value = field.get(null) as? String ?: ""
                if (value.isNotBlank() && value != "null" && value != "MY_GEMINI_API_KEY") value else ""
            } catch (e: Throwable) {
                ""
            }
        }
        set(value) = prefs.edit().putString("gemini_api_key", value).apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-3.7-flash") ?: "gemini-3.7-flash"
        set(value) = prefs.edit().putString("gemini_model", value).apply()

    var groqApiKey: String
        get() = prefs.getString("groq_api_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_api_key", value).apply()

    var groqModel: String
        get() = prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
        set(value) = prefs.edit().putString("groq_model", value).apply()

    var openRouterApiKey: String
        get() = prefs.getString("open_router_key", "") ?: ""
        set(value) = prefs.edit().putString("open_router_key", value).apply()

    var openRouterModel: String
        get() = prefs.getString("open_router_model", "anthropic/claude-3.7-sonnet") ?: "anthropic/claude-3.7-sonnet"
        set(value) = prefs.edit().putString("open_router_model", value).apply()

    var preferredLlm: String
        get() = prefs.getString("preferred_llm", "AUTO") ?: "AUTO" // AUTO, GEMINI, GROQ, OPENROUTER
        set(value) = prefs.edit().putString("preferred_llm", value).apply()

    var isFloatingBubbleEnabled: Boolean
        get() = prefs.getBoolean("floating_bubble_enabled", false)
        set(value) = prefs.edit().putBoolean("floating_bubble_enabled", value).apply()

    var confirmRiskyActions: Boolean
        get() = prefs.getBoolean("confirm_risky_actions", true)
        set(value) = prefs.edit().putBoolean("confirm_risky_actions", value).apply()

    fun hasAnyApiKey(): Boolean {
        return geminiApiKey.isNotBlank() || groqApiKey.isNotBlank() || openRouterApiKey.isNotBlank()
    }
}
