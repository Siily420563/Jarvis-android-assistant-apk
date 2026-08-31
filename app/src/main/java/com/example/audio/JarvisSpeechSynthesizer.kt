package com.example.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

class JarvisSpeechSynthesizer(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingSpeechQueue = mutableListOf<Pair<String, (() -> Unit)?>>()

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e("SaraVoice", "Failed to construct TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ttsEngine = tts
            if (ttsEngine != null) {
                val localesToTry = listOf(
                    Locale("hi", "IN"),
                    Locale("en", "IN"),
                    Locale.UK,
                    Locale.US,
                    Locale.getDefault()
                )

                var langFound = false
                for (loc in localesToTry) {
                    val res = ttsEngine.isLanguageAvailable(loc)
                    if (res >= TextToSpeech.LANG_AVAILABLE) {
                        ttsEngine.language = loc
                        langFound = true
                        break
                    }
                }

                if (!langFound) {
                    ttsEngine.language = Locale.getDefault()
                }

                // Female melodic voice tuning
                ttsEngine.setPitch(1.12f)
                ttsEngine.setSpeechRate(1.05f)

                try {
                    val voices = ttsEngine.voices
                    if (!voices.isNullOrEmpty()) {
                        val femaleVoice = voices.firstOrNull {
                            val name = it.name.lowercase()
                            (name.contains("female") || name.contains("woman") || name.contains("hi-in") || name.contains("en-in")) &&
                                    !it.isNetworkConnectionRequired
                        } ?: voices.firstOrNull { it.name.lowercase().contains("female") }
                        if (femaleVoice != null) {
                            ttsEngine.voice = femaleVoice
                        }
                    }
                } catch (e: Throwable) {
                    Log.w("SaraVoice", "Voice query warning: ${e.message}")
                }
            }

            isInitialized = true
            Log.i("SaraVoice", "SARA Ultra-Fast Speech Engine Ready")

            // Flush pending queue
            mainHandler.post {
                synchronized(pendingSpeechQueue) {
                    for (item in pendingSpeechQueue) {
                        speakDirect(item.first, item.second)
                    }
                    pendingSpeechQueue.clear()
                }
            }
        } else {
            Log.e("SaraVoice", "TextToSpeech initialization failed with status $status")
        }
    }

    fun speak(text: String, apiKey: String? = null, onComplete: (() -> Unit)? = null) {
        val cleanText = cleanTextForSpeech(text)
        if (cleanText.isBlank()) {
            onComplete?.invoke()
            return
        }

        if (!isInitialized) {
            synchronized(pendingSpeechQueue) {
                // Keep only latest to avoid delayed old speech buildup
                pendingSpeechQueue.clear()
                pendingSpeechQueue.add(Pair(cleanText, onComplete))
            }
            return
        }

        mainHandler.post {
            speakDirect(cleanText, onComplete)
        }
    }

    private fun speakDirect(cleanText: String, onComplete: (() -> Unit)?) {
        try {
            val ttsEngine = tts
            if (ttsEngine == null) {
                onComplete?.invoke()
                return
            }

            val utteranceId = "SARA_SPEECH_${System.currentTimeMillis()}"

            ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        mainHandler.post { onComplete?.invoke() }
                    }
                }
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        mainHandler.post { onComplete?.invoke() }
                    }
                }
            })

            ttsEngine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            Log.e("SaraVoice", "Error speaking text", e)
            onComplete?.invoke()
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w("SaraVoice", "Error stopping TTS: ${e.message}")
        }
    }

    fun shutdown() {
        try {
            stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (e: Exception) {
            Log.w("SaraVoice", "Error shutting down TTS: ${e.message}")
        }
    }

    private fun cleanTextForSpeech(raw: String): String {
        return raw
            .replace(Regex("<action[^>]*/>"), "")
            .replace(Regex("<action[^>]*>.*?</action>"), "")
            .replace(Regex("```[a-zA-Z0-9]*"), "")
            .replace(Regex("```"), "")
            .replace(Regex("\\{[\\s\\S]*?\\}"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace("*", "")
            .replace("#", "")
            .replace("💕", "")
            .replace("❤️", "")
            .replace("✨", "")
            .replace("🔥", "")
            .replace("🎤", "")
            .replace("⚙️", "")
            .replace("🗣️", "")
            .replace("⚡", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
