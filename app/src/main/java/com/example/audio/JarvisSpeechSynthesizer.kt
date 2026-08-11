package com.example.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.*

class JarvisSpeechSynthesizer(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.UK)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
            }
            tts?.setPitch(0.95f)
            tts?.setSpeechRate(1.0f)
            isInitialized = true
        } else {
            Log.e("JarvisSpeech", "TextToSpeech initialization failed")
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized || text.isBlank()) return
        
        // Strip XML action tags from speech playback
        val cleanText = text.replaceAllXmlTags()
        if (cleanText.isBlank()) return

        tts?.stop()
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_SPEECH_ID")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }

    private fun String.replaceAllXmlTags(): String {
        return this.replace(Regex("<action[^>]*/>"), "")
            .replace(Regex("<action[^>]*>.*?</action>"), "")
            .trim()
    }
}
