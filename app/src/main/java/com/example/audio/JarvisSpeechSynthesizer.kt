package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.TimeUnit

class JarvisSpeechSynthesizer(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
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

            // Set natural melodic female voice profile
            tts?.setPitch(1.15f)
            tts?.setSpeechRate(1.02f)

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
            Log.i("SaraVoice", "SARA Neural Audio Engine Ready")
        } else {
            Log.e("SaraVoice", "TextToSpeech initialization failed")
        }
    }

    fun speak(text: String, apiKey: String? = null, onComplete: (() -> Unit)? = null) {
        val cleanText = cleanTextForSpeech(text)
        if (cleanText.isBlank()) {
            onComplete?.invoke()
            return
        }

        stop()

        // 1. Try Gemini Real Neural Voice if API key is present
        if (!apiKey.isNullOrBlank()) {
            scope.launch {
                val played = generateAndPlayGeminiVoice(cleanText, apiKey, onComplete)
                if (!played) {
                    // Fallback to local neural TTS
                    withContext(Dispatchers.Main) {
                        speakLocalTts(cleanText, onComplete)
                    }
                }
            }
        } else {
            speakLocalTts(cleanText, onComplete)
        }
    }

    private suspend fun generateAndPlayGeminiVoice(
        text: String,
        apiKey: String,
        onComplete: (() -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Say naturally and warmly in Hinglish with feminine tone: $text")
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply { put("AUDIO") })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede") // Melodic feminine voice
                            })
                        })
                    })
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: return@withContext false
                val json = JSONObject(respStr)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")

                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("inlineData")) {
                            val inline = part.getJSONObject("inlineData")
                            val b64 = inline.getString("data")
                            val audioBytes = Base64.decode(b64, Base64.DEFAULT)
                            playAudioBytes(audioBytes, onComplete)
                            return@withContext true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("SaraVoice", "Gemini Real Voice failed, using fallback TTS: ${e.message}")
        }
        return@withContext false
    }

    private fun playAudioBytes(bytes: ByteArray, onComplete: (() -> Unit)?) {
        try {
            val tempFile = File.createTempFile("sara_speech_", ".wav", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(bytes) }

            scope.launch(Dispatchers.Main) {
                stop()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    setOnCompletionListener {
                        tempFile.delete()
                        onComplete?.invoke()
                    }
                    setOnErrorListener { _, _, _ ->
                        tempFile.delete()
                        onComplete?.invoke()
                        true
                    }
                    prepare()
                    start()
                }
            }
        } catch (e: Exception) {
            Log.e("SaraVoice", "Error playing audio bytes", e)
            onComplete?.invoke()
        }
    }

    private fun speakLocalTts(cleanText: String, onComplete: (() -> Unit)?) {
        if (!isInitialized) {
            onComplete?.invoke()
            return
        }
        val utteranceId = "SARA_SPEECH_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) onComplete?.invoke()
            }
            override fun onError(id: String?) {
                if (id == utteranceId) onComplete?.invoke()
            }
        })
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {}
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
    }

    private fun cleanTextForSpeech(raw: String): String {
        return raw.replace(Regex("<action[^>]*/>"), "")
            .replace(Regex("<action[^>]*>.*?</action>"), "")
            .replace(Regex("```[a-z]*"), "")
            .replace(Regex("```"), "")
            .replace(Regex("\\{[\\s\\S]*?\\}"), "")
            .replace("*", "")
            .replace("#", "")
            .replace("💕", "")
            .replace("❤️", "")
            .replace("✨", "")
            .replace("🔥", "")
            .replace("🎤", "")
            .trim()
    }
}
