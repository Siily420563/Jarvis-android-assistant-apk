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
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var emergencyTts: TextToSpeech? = null
    @Volatile private var emergencyReady = false
    @Volatile private var isAudioPlaying = false
    @Volatile private var currentJob: Job? = null

    private val streamQueue = ArrayDeque<String>()
    private var streamCallback: (() -> Unit)? = null

    init {
        instance = this
        initEmergencyLocalTts()
    }

    private fun initEmergencyLocalTts() {
        emergencyTts = TextToSpeech(context.applicationContext) { status ->
            emergencyReady = status == TextToSpeech.SUCCESS
            if (emergencyReady) {
                emergencyTts?.setLanguage(Locale("en", "IN"))
            }
        }
    }

    fun speakLocal(text: String, onComplete: (() -> Unit)? = null) {
        val clean = cleanTextForSpeech(text)
        if (clean.isBlank()) {
            mainHandler.post { onComplete?.invoke() }
            return
        }
        val tts = emergencyTts
        if (!emergencyReady || tts == null) {
            mainHandler.post { onComplete?.invoke() }
            return
        }
        val id = "local_${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isAudioPlaying = true }
            override fun onDone(utteranceId: String?) {
                isAudioPlaying = false
                mainHandler.post { onComplete?.invoke() }
            }
            override fun onError(utteranceId: String?) {
                isAudioPlaying = false
                mainHandler.post { onComplete?.invoke() }
            }
        })
        val args = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, args, id)
    }

    fun speak(
        text: String,
        apiKey: String? = null,
        groqApiKey: String? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val clean = cleanTextForSpeech(text)
        if (clean.isBlank()) {
            mainHandler.post { onComplete?.invoke() }
            return
        }

        // Chunking lets speech start early and keeps latency lower than waiting giant text.
        val chunks = clean.chunkedByWords(9)
        synchronized(streamQueue) {
            chunks.forEach { streamQueue.addLast(it) }
            streamCallback = onComplete
        }
        if (currentJob?.isActive != true) {
            currentJob = scope.launch {
                processQueue(apiKey, groqApiKey)
            }
        }
    }

    // For future real token streaming integration: call this on each partial delta token/chunk.
    fun speakStreamDelta(deltaText: String, apiKey: String? = null, groqApiKey: String? = null) {
        val clean = cleanTextForSpeech(deltaText)
        if (clean.isBlank()) return
        synchronized(streamQueue) {
            streamQueue.addLast(clean)
        }
        if (currentJob?.isActive != true) {
            currentJob = scope.launch { processQueue(apiKey, groqApiKey) }
        }
    }

    private suspend fun processQueue(apiKey: String?, groqApiKey: String?) {
        while (true) {
            val next = synchronized(streamQueue) { if (streamQueue.isEmpty()) null else streamQueue.removeFirst() } ?: break
            val spoken = speakWithCloud(next, apiKey, groqApiKey)
            if (!spoken) {
                SystemLogBus.w("SaraVoice", "Cloud TTS failed for chunk, using emergency local")
                speakLocalSuspend(next)
            }
        }
        val cb = synchronized(streamQueue) {
            if (streamQueue.isEmpty()) {
                val done = streamCallback
                streamCallback = null
                done
            } else null
        }
        cb?.let { mainHandler.post { it.invoke() } }
    }

    private suspend fun speakWithCloud(text: String, apiKey: String?, groqApiKey: String?): Boolean {
        val geminiKey = apiKey?.trim().orEmpty()
        val groqKey = groqApiKey?.trim().orEmpty()

        if (geminiKey.isNotBlank()) {
            val audio = fetchGeminiTts(text, geminiKey)
            if (audio != null) {
                return playAudioBytesSuspend(audio.first, audio.second)
            }
        }
        if (groqKey.isNotBlank()) {
            val audio = fetchGroqTts(text, groqKey)
            if (audio != null) {
                return playAudioBytesSuspend(audio.first, audio.second)
            }
        }
        return false
    }

    private suspend fun fetchGeminiTts(text: String, apiKey: String): Pair<ByteArray, String>? {
        return try {
            val model = "gemini-3.1-flash-tts"
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore"))))
                })
            }
            val req = Request.Builder()
                .url(url)
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
                        val b64 = inline.optString("data", "")
                        if (b64.isNotBlank()) return Pair(Base64.decode(b64, Base64.DEFAULT), mime)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w("SaraVoice", "Gemini TTS failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchGroqTts(text: String, apiKey: String): Pair<ByteArray, String>? {
        return try {
            val url = "https://api.groq.com/openai/v1/audio/speech"
            val body = JSONObject().apply {
                put("model", "playai-tts")
                put("input", text)
                put("voice", "nova")
                put("response_format", "mp3")
            }
            val req = Request.Builder()
                .url(url)
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
            Log.w("SaraVoice", "Groq TTS failed: ${e.message}")
            null
        }
    }

    private suspend fun playAudioBytesSuspend(bytes: ByteArray, mimeType: String): Boolean {
        return try {
            stop()
            isAudioPlaying = true
            val ext = if (mimeType.contains("mp3", true)) "mp3" else "wav"
            val tmp = File(context.cacheDir, "sara_tts_${System.currentTimeMillis()}.$ext")
            FileOutputStream(tmp).use { it.write(bytes) }

            val done = Object()
            var ok = true

            mainHandler.post {
                try {
                    val mp = MediaPlayer().apply {
                        setDataSource(tmp.absolutePath)
                        setOnCompletionListener {
                            synchronized(done) {
                                isAudioPlaying = false
                                try { tmp.delete() } catch (_: Exception) {}
                                done.notify()
                            }
                        }
                        setOnErrorListener { _, _, _ ->
                            synchronized(done) {
                                isAudioPlaying = false
                                ok = false
                                try { tmp.delete() } catch (_: Exception) {}
                                done.notify()
                            }
                            true
                        }
                        prepare()
                        start()
                    }
                    mediaPlayer = mp
                } catch (_: Exception) {
                    synchronized(done) {
                        ok = false
                        isAudioPlaying = false
                        try { tmp.delete() } catch (_: Exception) {}
                        done.notify()
                    }
                }
            }

            synchronized(done) { done.wait(12_000L) }
            ok
        } catch (_: Exception) {
            isAudioPlaying = false
            false
        }
    }

    private suspend fun speakLocalSuspend(text: String) {
        val done = Object()
        mainHandler.post {
            speakLocal(text) { synchronized(done) { done.notify() } }
        }
        synchronized(done) { done.wait(4_000L) }
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        synchronized(streamQueue) {
            streamQueue.clear()
            streamCallback = null
        }
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        isAudioPlaying = false
    }

    fun shutdown() {
        stop()
        try { emergencyTts?.stop() } catch (_: Exception) {}
        try { emergencyTts?.shutdown() } catch (_: Exception) {}
        emergencyTts = null
        emergencyReady = false
        if (instance == this) instance = null
    }

    private fun cleanTextForSpeech(text: String): String {
        return text
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.chunkedByWords(wordsPerChunk: Int): List<String> {
        val words = this.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val end = (i + wordsPerChunk).coerceAtMost(words.size)
            out.add(words.subList(i, end).joinToString(" "))
            i = end
        }
        return out
    }
}
