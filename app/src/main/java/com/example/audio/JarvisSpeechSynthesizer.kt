package com.example.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.*

class JarvisSpeechSynthesizer(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Priority: Indian English / Hindi locale for authentic Hinglish pronunciation
            val localesToTry = listOf(
                Locale("en", "IN"),
                Locale("hi", "IN"),
                Locale.UK,
                Locale.US
            )

            var langSet = false
            for (loc in localesToTry) {
                val res = tts?.setLanguage(loc)
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    langSet = true
                    break
                }
            }

            if (!langSet) {
                tts?.language = Locale.getDefault()
            }

            // Set voice parameters for pleasant female tone
            tts?.setPitch(1.12f) // Slightly higher pitch for female voice tone
            tts?.setSpeechRate(1.02f)

            // Select a female voice if available in voice list
            try {
                val voices = tts?.voices
                if (voices != null) {
                    val femaleVoice = voices.firstOrNull {
                        (it.name.lowercase().contains("female") || it.name.lowercase().contains("woman") || it.name.lowercase().contains("india") || it.name.lowercase().contains("en-in") || it.name.lowercase().contains("hi-in")) &&
                                !it.isNetworkConnectionRequired
                    } ?: voices.firstOrNull { it.name.lowercase().contains("female") }
                    if (femaleVoice != null) {
                        tts?.voice = femaleVoice
                    }
                }
            } catch (e: Exception) {
                Log.w("SaraVoice", "Voice query error: ${e.message}")
            }

            isInitialized = true
            Log.i("SaraVoice", "SARA Female Voice Synthesizer Ready")
        } else {
            Log.e("SaraVoice", "TextToSpeech initialization failed")
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized || text.isBlank()) return

        val cleanText = cleanTextForSpeech(text)
        if (cleanText.isBlank()) return

        tts?.stop()

        val utteranceId = "SARA_SPEECH_${System.currentTimeMillis()}"
        if (onComplete != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) onComplete()
                }
                override fun onError(utteranceId: String?) {}
            })
        }

        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }

    private fun cleanTextForSpeech(raw: String): String {
        return raw.replace(Regex("<action[^>]*/>"), "")
            .replace(Regex("<action[^>]*>.*?</action>"), "")
            .replace(Regex("```[a-z]*"), "")
            .replace(Regex("```"), "")
            .replace(Regex("\\{[\\s\\S]*?\\}"), "") // Remove raw JSON blocks if any leaked
            .replace("*", "")
            .replace("#", "")
            .trim()
    }
}
