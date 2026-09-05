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
import kotlinx.coroutines.withContext
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
    private var emergencyTts: TextToSpeech? = null
    @Volatile private var emergencyReady = false
    @Volatile private var cloudTtsCooldownUntil = 0L

    init {
        instance = this
        initEmergencyTts()
    }

    private fun initEmergencyTts() {
        emergencyTts = TextToSpeech(context.applicationContext) { status ->
            emergencyReady = status == TextToSpeech.SUCCESS
            if (emergencyReady) {
                val res = emergencyTts?.setLanguage(Locale("en", "IN"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    emergencyTts?.setLanguage(Locale.US)
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

        val tts = emergencyTts
        if (!emergencyReady || tts == null) {
            mainHandler.post { onComplete?.invoke() }
            return
        }

        val utteranceId = "local_${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { mainHandler.post { onComplete?.invoke() } }
            override fun onError(utteranceId: String?) { mainHandler.post { onComplete?.invoke() } }
        })
        val bundle = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId)
    }

    fun speak(
        text: String,
        apiKey: String? = null,
        groqApiKey: String? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val clean = cleanText(text)
        if (clean.isBlank()) {
            mainHandler.post { onComplete?.invoke() }
            return
        }

        launchSafe {
            val canTryCloud = System.currentTimeMillis() > cloudTtsCooldownUntil
            val spokenByCloud = if (canTryCloud) {
                speakCloudOnce(clean, apiKey.orEmpty(), groqApiKey.orEmpty())
            } else false

            if (!spokenByCloud) {
                // Avoid cloud spam when failing repeatedly
                if (canTryCloud) {
                    cloudTtsCooldownUntil = System.currentTimeMillis() + 120_000L
                    SystemLogBus.w("SaraVoice", "Cloud TTS unavailable, cooldown 120s, using local")
                }
                withContext(Dispatchers.Main) {
                    speakLocal(clean, onComplete)
                }
            } else {
                withContext(Dispatchers.Main) { onComplete?.invoke() }
            }
        }
    }

    fun speakStreamDelta(deltaText: String, apiKey: String? = null, groqApiKey: String? = null) {
        // lightweight streaming foundation: speak partial quickly via local if chunk large enough
        val clean = cleanText(deltaText)
        if (clean.length < 18) return
        speak(clean, apiKey, groqApiKey, onComplete = null)
    }

    private suspend fun speakCloudOnce(text: String, geminiKey: String, groqKey: String): Boolean {
        if (geminiKey.isNotBlank()) {
            val audio = fetchGeminiTts(text, geminiKey)
            if (audio != null) return playAudio(audio.first, audio.second)
        }
        if (groqKey.isNotBlank()) {
            val audio = fetchGroqTts(text, groqKey)
            if (audio != null) return playAudio(audio.first, audio.second)
        }
        return false
    }

    private suspend fun fetchGeminiTts(text: String, apiKey: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        try {
            val model = "gemini-3.1-flash-tts"
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().put("voiceConfig",
                        JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore"))))
                })
            }

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = JSONObject(resp.body?.string().orEmpty())
                val parts = root.optJSONArray("candidates")
                    ?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: return@withContext null

                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.has("inlineData")) {
                        val inline = p.getJSONObject("inlineData")
                        val mime = inline.optString("mimeType", "audio/wav")
                        val data = inline.optString("data", "")
                        if (data.isNotBlank()) return@withContext Pair(Base64.decode(data, Base64.DEFAULT), mime)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w("SaraVoice", "Gemini TTS fetch fail: ${e.message}")
            null
        }
    }

    private suspend fun fetchGroqTts(text: String, apiKey: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        try {
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
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                if (bytes.isEmpty()) null else Pair(bytes, "audio/mp3")
            }
        } catch (e: Exception) {
            Log.w("SaraVoice", "Groq TTS fetch fail: ${e.message}")
            null
        }
    }

    private suspend fun playAudio(bytes: ByteArray, mimeType: String): Boolean = withContext(Dispatchers.IO) {
        try {
            stopPlaybackOnly()
            val ext = if (mimeType.contains("mp3", true)) "mp3" else "wav"
            val tmp = File(context.cacheDir, "sara_tts_${System.currentTimeMillis()}.$ext")
            FileOutputStream(tmp).use { it.write(bytes) }

            val lock = Object()
            var ok = true

            withContext(Dispatchers.Main) {
                try {
                    val mp = MediaPlayer().apply {
                        setDataSource(tmp.absolutePath)
                        setOnCompletionListener {
                            synchronized(lock) {
                                try { tmp.delete() } catch (_: Exception) {}
                                lock.notify()
                            }
                        }
                        setOnErrorListener { _, _, _ ->
                            synchronized(lock) {
                                ok = false
                                try { tmp.delete() } catch (_: Exception) {}
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
                        try { tmp.delete() } catch (_: Exception) {}
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

    private fun stopPlaybackOnly() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun stop() {
        stopPlaybackOnly()
    }

    fun shutdown() {
        stop()
        try { emergencyTts?.stop() } catch (_: Exception) {}
        try { emergencyTts?.shutdown() } catch (_: Exception) {}
        emergencyTts = null
        emergencyReady = false
        if (instance == this) instance = null
    }

    private fun cleanText(text: String): String {
        return text
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun launchSafe(block: suspend () -> Unit): Job {
        return scope.launch {
            try { block() } catch (_: Exception) {}
        }
    }
}
