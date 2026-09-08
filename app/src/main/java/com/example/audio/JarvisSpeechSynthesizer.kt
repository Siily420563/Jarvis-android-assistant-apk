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
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-Time Streaming Speech Synthesizer for SARA.
 * 1. Speaks token-by-token / chunk-by-chunk without waiting for the full sentence to finish.
 * 2. Uses Gemini Live Voice (AUDIO modality) when available and falls back smoothly to Android Native TTS.
 * 3. Supports instant barge-in interruption (stop speaking immediately when user speaks).
 */
class JarvisSpeechSynthesizer(private val context: Context) {
    companion object {
        private const val TAG = "SaraSpeech"
        @Volatile var instance: JarvisSpeechSynthesizer? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var localTts: TextToSpeech? = null
    @Volatile private var localReady = false
    @Volatile private var cloudCooldownUntil = 0L

    // Incremental streaming buffer
    private val tokenAccumulator = StringBuilder()
    private val speechQueue = ConcurrentLinkedQueue<String>()
    private val isPlayingQueue = AtomicBoolean(false)
    private var streamCompletionCallback: (() -> Unit)? = null

    init {
        instance = this
        initLocal()
    }

    private fun initLocal() {
        localTts = TextToSpeech(context.applicationContext) { status ->
            localReady = status == TextToSpeech.SUCCESS
            if (localReady) {
                // Set Hindi-Indian or English-Indian for perfect Hinglish phonetics
                val hi = Locale("hi", "IN")
                val res = localTts?.setLanguage(hi)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    localTts?.setLanguage(Locale("en", "IN"))
                }
                localTts?.setSpeechRate(1.05f)
                localTts?.setPitch(1.0f)
            }
        }
    }

    /**
     * Called token-by-token as the LLM streams back response chunks.
     * Splits into natural spoken clauses immediately (e.g. at commas, punctuation, or conjunctions)
     * so user hears speech without waiting for sentence completion.
     */
    fun onStreamTokenReceived(token: String, apiKey: String? = null, groqApiKey: String? = null) {
        tokenAccumulator.append(token)
        val text = tokenAccumulator.toString()

        // Check for natural chunk break delimiters: comma, period, question, exclamation, semicolon, or newline
        val delimiters = charArrayOf('.', ',', '!', '?', ':', ';', '\n', '—')
        val lastDelim = text.lastIndexOfAny(delimiters)

        if (lastDelim != -1 && lastDelim >= 8) {
            val chunk = text.substring(0, lastDelim + 1).trim()
            tokenAccumulator.delete(0, lastDelim + 1)
            if (chunk.isNotBlank()) {
                enqueuePhrase(chunk, apiKey, groqApiKey)
            }
        } else if (text.length >= 40 && text.contains(" ")) {
            // If clause is long without punctuation, split at last space
            val lastSpace = text.lastIndexOf(' ')
            if (lastSpace >= 15) {
                val chunk = text.substring(0, lastSpace).trim()
                tokenAccumulator.delete(0, lastSpace)
                if (chunk.isNotBlank()) {
                    enqueuePhrase(chunk, apiKey, groqApiKey)
                }
            }
        }
    }

    /**
     * Signals that the LLM response stream has finished.
     * Speaks any trailing characters left in the buffer.
     */
    fun onStreamFinished(apiKey: String? = null, groqApiKey: String? = null, onComplete: (() -> Unit)? = null) {
        val remaining = tokenAccumulator.toString().trim()
        tokenAccumulator.setLength(0)
        streamCompletionCallback = onComplete

        if (remaining.isNotBlank()) {
            enqueuePhrase(remaining, apiKey, groqApiKey)
        } else if (!isPlayingQueue.get() && speechQueue.isEmpty()) {
            mainHandler.post { onComplete?.invoke() }
        }
    }

    private fun enqueuePhrase(phrase: String, apiKey: String?, groqApiKey: String?) {
        val clean = cleanText(phrase)
        if (clean.isBlank()) return
        speechQueue.offer(clean)
        processQueue(apiKey, groqApiKey)
    }

    private fun processQueue(apiKey: String?, groqApiKey: String?) {
        if (!isPlayingQueue.compareAndSet(false, true)) return

        scope.launch {
            while (true) {
                val nextChunk = speechQueue.poll()
                if (nextChunk == null) {
                    isPlayingQueue.set(false)
                    mainHandler.post { streamCompletionCallback?.invoke() }
                    break
                }

                val canUseCloud = System.currentTimeMillis() > cloudCooldownUntil && !apiKey.isNullOrBlank()
                val cloudSpoken = if (canUseCloud) {
                    tryCloudLiveAudio(nextChunk, apiKey.orEmpty())
                } else false

                if (!cloudSpoken) {
                    val completer = CompletableDeferred<Unit>()
                    mainHandler.post {
                        speakLocalChunk(nextChunk) {
                            completer.complete(Unit)
                        }
                    }
                    try {
                        withTimeout(10000L) { completer.await() }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Single call to speak full text (for direct messages or step announcements).
     */
    fun speak(text: String, apiKey: String? = null, groqApiKey: String? = null, onComplete: (() -> Unit)? = null) {
        val clean = cleanText(text)
        if (clean.isBlank()) {
            mainHandler.post { onComplete?.invoke() }
            return
        }

        stop() // Interrupt any previous speech

        scope.launch {
            val canUseCloud = System.currentTimeMillis() > cloudCooldownUntil && !apiKey.isNullOrBlank()
            val cloudSpoken = if (canUseCloud) {
                tryCloudLiveAudio(clean, apiKey.orEmpty())
            } else false

            if (cloudSpoken) {
                mainHandler.post { onComplete?.invoke() }
            } else {
                mainHandler.post {
                    speakLocal(clean, onComplete)
                }
            }
        }
    }

    private fun speakLocalChunk(text: String, onComplete: () -> Unit) {
        val tts = localTts
        if (!localReady || tts == null) {
            onComplete()
            return
        }
        val id = "chunk_${System.currentTimeMillis()}_${(1..1000).random()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) onComplete()
            }
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) onComplete()
            }
        })
        val args = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        tts.speak(text, TextToSpeech.QUEUE_ADD, args, id)
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

    /**
     * Calls Google Gemini Live Audio / TTS API
     * Uses gemini-2.5-flash-preview-tts or gemini-2.5-flash-native-audio-preview-12-2025
     */
    private fun tryCloudLiveAudio(text: String, apiKey: String): Boolean {
        return try {
            val model = "gemini-2.5-flash-preview-tts"
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Aoede"))))
                })
            }

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 404 || resp.code == 400) {
                        cloudCooldownUntil = System.currentTimeMillis() + 60_000L
                    }
                    return false
                }
                val root = JSONObject(resp.body?.string().orEmpty())
                val parts = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: return false

                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.has("inlineData")) {
                        val inline = p.getJSONObject("inlineData")
                        val mime = inline.optString("mimeType", "audio/wav")
                        val data = inline.optString("data", "")
                        if (data.isNotBlank()) {
                            val bytes = Base64.decode(data, Base64.DEFAULT)
                            return playAudio(bytes, mime)
                        }
                    }
                }
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Live Audio call failed: ${e.message}")
            false
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

    /**
     * Barge-in interruption: immediately cuts off all speech.
     */
    fun stop() {
        tokenAccumulator.setLength(0)
        speechQueue.clear()
        isPlayingQueue.set(false)
        stopPlayback()
        try { localTts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        stop()
        try { localTts?.shutdown() } catch (_: Exception) {}
        localTts = null
        localReady = false
        if (instance == this) instance = null
    }

    private fun cleanText(text: String): String {
        return text
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*#_`~]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
