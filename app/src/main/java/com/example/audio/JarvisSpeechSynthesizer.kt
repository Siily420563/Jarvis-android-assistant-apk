package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import com.example.debug.SystemLogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class JarvisSpeechSynthesizer(private val context: Context) {
    companion object {
        @Volatile var instance: JarvisSpeechSynthesizer? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var localTts: TextToSpeech? = null
    @Volatile private var localReady = false
    @Volatile private var cloudCooldownUntil = 0L

    init {
        instance = this
        initLocal()
    }

    private fun initLocal() {
        localTts = TextToSpeech(context.applicationContext) { status ->
            localReady = status == TextToSpeech.SUCCESS
            if (localReady) {
                val hi = Locale("hi", "IN")
                val res = localTts?.setLanguage(hi)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    localTts?.setLanguage(Locale("en", "IN"))
                }
            }
        }
    }

    fun speakLocal(text: String, onComplete: (() -> Unit)? = null) {
        val clean = cleanText(text)
        if (clean.isBlank()) {
            mainHandler.post { onComplete?.invoke() }
            return
        }
        val tts = localTts
        if (!localReady || tts == null) {
            mainHandler.post { onComplete?.invoke() }
            return
        }

        val id = "local_${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { mainHandler.post { onComplete?.invoke() } }
            override fun onError(utteranceId: String?) { mainHandler.post { onComplete?.invoke() } }
        })

        val args = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, args, id)
    }

    fun speak(text: String, apiKey: String? = null, groqApiKey: String? = null, onComplete: (() -> Unit)? = null) {
        val clean = cleanText(text)
        if (clean.isBlank()) {
            mainHandler.post { onComplete?.invoke() }
            return
        }

        scope.launch {
            val canUseCloud = System.currentTimeMillis() > cloudCooldownUntil
            val cloudSpoken = if (canUseCloud) tryCloudOnce(clean, apiKey.orEmpty(), groqApiKey.orEmpty()) else false

            if (cloudSpoken) {
                mainHandler.post { onComplete?.invoke() }
            } else {
                if (canUseCloud) {
                    cloudCooldownUntil = System.currentTimeMillis() + 120_000L
                    SystemLogBus.w("SaraVoice", "Cloud TTS unavailable, local fallback for 120s")
                }
                mainHandler.post { speakLocal(clean, onComplete) }
            }
        }
    }

    fun speakStreamDelta(deltaText: String, apiKey: String? = null, groqApiKey: String? = null) {
        val clean = cleanText(deltaText)
        if (clean.length < 20) return
        speak(clean, apiKey, groqApiKey, null)
    }

    private fun tryCloudOnce(text: String, geminiKey: String, groqKey: String): Boolean {
        if (geminiKey.isNotBlank()) {
            val audio = fetchGeminiTts(text, geminiKey)
            if (audio != null && playAudio(audio.first, audio.second)) return true
        }
        if (groqKey.isNotBlank()) {
            val audio = fetchGroqTts(text, groqKey)
            if (audio != null && playAudio(audio.first, audio.second)) return true
        }
        return false
    }

    private fun fetchGeminiTts(text: String, apiKey: String): Pair<ByteArray, String>? {
        return try {
            val model = "gemini-3.1-flash-tts"
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore"))))
                })
            }

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                val parts = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: return null

                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.has("inlineData")) {
                        val inline = p.getJSONObject("inlineData")
                        val mime = inline.optString("mimeType", "audio/wav")
                        val data = inline.optString("data", "")
                        if (data.isNotBlank()) return Pair(Base64.decode(data, Base64.DEFAULT), mime)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w("SaraVoice", "Gemini TTS fail: ${e.message}")
            null
        }
    }

    private fun fetchGroqTts(text: String, apiKey: String): Pair<ByteArray, String>? {
        return try {
            val body = JSONObject().apply {
                put("model", "playai-tts")
                put("input", text)
                put("voice", "nova")
                put("response_format", "mp3")
            }

            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/speech")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.isEmpty()) null else Pair(bytes, "audio/mp3")
            }
        } catch (e: Exception) {
            Log.w("SaraVoice", "Groq TTS fail: ${e.message}")
            null
        }
    }

    private fun playAudio(bytes: ByteArray, mimeType: String): Boolean {
        return try {
            stopPlayback()
            val ext = if (mimeType.contains("mp3", true)) "mp3" else "wav"
            val file = File(context.cacheDir, "sara_tts_${System.currentTimeMillis()}.$ext")
            FileOutputStream(file).use { it.write(bytes) }

            val lock = Object()
            var ok = true

            mainHandler.post {
                try {
                    val mp = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener {
                            synchronized(lock) {
                                try { file.delete() } catch (_: Exception) {}
                                lock.notify()
                            }
                        }
                        setOnErrorListener { _, _, _ ->
                            synchronized(lock) {
                                ok = false
                                try { file.delete() } catch (_: Exception) {}
                                lock.notify()
                            }
                            true
                        }
                        prepare()
                        start()
                    }
                    mediaPlayer = mp
                } catch (_: Exception) {
                    synchronized(lock) {
                        ok = false
                        try { file.delete() } catch (_: Exception) {}
                        lock.notify()
                    }
                }
            }

            synchronized(lock) { lock.wait(12_000L) }
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun stopPlayback() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun stop() {
        stopPlayback()
    }

    fun shutdown() {
        stop()
        try { localTts?.stop() } catch (_: Exception) {}
        try { localTts?.shutdown() } catch (_: Exception) {}
        localTts = null
        localReady = false
        if (instance == this) instance = null
    }

    private fun cleanText(text: String): String {
        return text
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
