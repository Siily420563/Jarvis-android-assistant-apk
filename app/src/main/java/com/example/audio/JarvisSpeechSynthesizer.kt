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
 *
 * FIXES for "TTS not working":
 * - Added isSpeaking flag so barge-in doesn't kill speech spuriously
 * - Fixed init race: if speak() called before TTS ready, we now wait/retry instead of silent drop
 * - Added auto retry for LANG_MISSING_DATA and init failure
 * - Improved cloud -> local fallback reliability and logging
 * - Fixed MediaPlayer blocking wait to use proper async completion
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
    @Volatile private var localInitFailed = false
    @Volatile var isSpeaking: Boolean = false
        private set
    @Volatile private var cloudCooldownUntil = 0L

    // Incremental streaming buffer
    private val tokenAccumulator = StringBuilder()
    private val speechQueue = ConcurrentLinkedQueue<String>()
    private val isPlayingQueue = AtomicBoolean(false)
    private var streamCompletionCallback: (() -> Unit)? = null
    private var initRetryCount = 0

    init {
        instance = this
        initLocal()
    }

    private fun initLocal() {
        try {
            localTts = TextToSpeech(context.applicationContext) { status ->
                mainHandler.post {
                    if (status == TextToSpeech.SUCCESS) {
                        localReady = true
                        localInitFailed = false
                        // Try Hindi-Indian for Hinglish phonetics, fallback chain
                        val tts = localTts
                        if (tts != null) {
                            val hiResult = try { tts.setLanguage(Locale("hi", "IN")) } catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
                            val hiSuccess = hiResult != TextToSpeech.LANG_MISSING_DATA && hiResult != TextToSpeech.LANG_NOT_SUPPORTED
                            if (!hiSuccess) {
                                val enInResult = try { tts.setLanguage(Locale("en", "IN")) } catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
                                val enInSuccess = enInResult != TextToSpeech.LANG_MISSING_DATA && enInResult != TextToSpeech.LANG_NOT_SUPPORTED
                                if (!enInSuccess) {
                                    try { tts.setLanguage(Locale.US) } catch (_: Exception) {}
                                } else {
                                    SystemLogBus.i(TAG, "TTS locale set to en-IN")
                                }
                            } else {
                                SystemLogBus.i(TAG, "TTS locale set to hi-IN")
                            }
                            try {
                                tts.setSpeechRate(1.05f)
                                tts.setPitch(1.0f)
                                // Attach a persistent utterance listener that tracks speaking state
                                tts.setOnUtteranceProgressListener(createUtteranceListener())
                            } catch (e: Exception) {
                                Log.w(TAG, "TTS config failed: ${e.message}")
                            }
                            Log.i(TAG, "Local TTS READY")
                            SystemLogBus.i(TAG, "TTS ready")
                        }
                    } else {
                        localReady = false
                        localInitFailed = true
                        Log.e(TAG, "TextToSpeech init FAILED status=$status")
                        SystemLogBus.e(TAG, "TTS init failed: $status")
                        // Retry once after delay if engine missing
                        if (initRetryCount < 2) {
                            initRetryCount++
                            mainHandler.postDelayed({ initLocal() }, 2000)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initLocal exception", e)
            SystemLogBus.e(TAG, "TTS init exception: ${e.message}")
            localReady = false
        }
    }

    private var currentUtteranceCallback: (() -> Unit)? = null

    private fun createUtteranceListener(): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                val cb = currentUtteranceCallback
                currentUtteranceCallback = null
                mainHandler.post { cb?.invoke() }
            }
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                Log.w(TAG, "TTS utterance error $utteranceId")
                val cb = currentUtteranceCallback
                currentUtteranceCallback = null
                mainHandler.post { cb?.invoke() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?, errorCode: Int) {
                isSpeaking = false
                Log.w(TAG, "TTS utterance error $utteranceId code $errorCode")
                val cb = currentUtteranceCallback
                currentUtteranceCallback = null
                mainHandler.post { cb?.invoke() }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                isSpeaking = false
                val cb = currentUtteranceCallback
                currentUtteranceCallback = null
                mainHandler.post { cb?.invoke() }
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
                            if (!completer.isCompleted) completer.complete(Unit)
                        }
                    }
                    try {
                        withTimeout(12000L) { completer.await() }
                    } catch (e: Exception) {
                        Log.w(TAG, "Chunk speech timeout: ${e.message}")
                        // Ensure isSpeaking reset
                        isSpeaking = false
                    }
                    // Small gap between chunks for natural rhythm
                    delay(80)
                } else {
                    // Cloud already played via MediaPlayer blocking wait
                    delay(80)
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
        isSpeaking = true

        scope.launch {
            val canUseCloud = System.currentTimeMillis() > cloudCooldownUntil && !apiKey.isNullOrBlank()
            val cloudSpoken = if (canUseCloud) {
                tryCloudLiveAudio(clean, apiKey.orEmpty())
            } else false

            if (cloudSpoken) {
                isSpeaking = false
                mainHandler.post { onComplete?.invoke() }
            } else {
                // Fallback to local TTS - ensure we are on main thread
                mainHandler.post {
                    if (!localReady) {
                        Log.w(TAG, "Local TTS not ready yet, attempting to speak anyway (ready=$localReady, failed=$localInitFailed)")
                        SystemLogBus.w(TAG, "TTS not ready - retrying init")
                        // Try to re-init if failed
                        if (localInitFailed || localTts == null) {
                            initLocal()
                            // Wait briefly then attempt speak
                            mainHandler.postDelayed({
                                speakLocal(clean, onComplete)
                            }, 600)
                            return@post
                        }
                    }
                    speakLocal(clean, onComplete)
                }
            }
        }
    }

    private fun speakLocalChunk(text: String, onComplete: () -> Unit) {
        val tts = localTts
        if (!localReady || tts == null) {
            Log.w(TAG, "speakLocalChunk dropped: not ready")
            SystemLogBus.w(TAG, "TTS chunk dropped - engine not ready")
            isSpeaking = false
            onComplete()
            return
        }
        val id = "chunk_${System.currentTimeMillis()}_${(1..9999).random()}"
        currentUtteranceCallback = onComplete
        isSpeaking = true
        val args = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        try {
            val result = tts.speak(text, TextToSpeech.QUEUE_ADD, args, id)
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "QUEUE_ADD speak returned $result")
                isSpeaking = false
                val cb = currentUtteranceCallback
                currentUtteranceCallback = null
                onComplete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "speakLocalChunk exception", e)
            isSpeaking = false
            currentUtteranceCallback = null
            onComplete()
        }
    }

    fun speakLocal(text: String, onComplete: (() -> Unit)? = null) {
        val clean = cleanText(text)
        if (clean.isBlank()) {
            isSpeaking = false
            mainHandler.post { onComplete?.invoke() }
            return
        }
        val tts = localTts
        if (!localReady || tts == null) {
            Log.w(TAG, "speakLocal dropped: not ready ready=$localReady ttsNull=${tts == null}")
            SystemLogBus.w(TAG, "TTS utterance skipped - engine not ready: $clean".take(120))
            isSpeaking = false
            mainHandler.post { onComplete?.invoke() }
            // Attempt to recover: re-init
            if (localInitFailed) {
                mainHandler.postDelayed({ initLocal() }, 1000)
            }
            return
        }

        val id = "local_${System.currentTimeMillis()}"
        currentUtteranceCallback = onComplete
        isSpeaking = true
        val args = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        try {
            val result = tts.speak(clean, TextToSpeech.QUEUE_FLUSH, args, id)
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "QUEUE_FLUSH speak returned $result")
                isSpeaking = false
                val cb = currentUtteranceCallback
                currentUtteranceCallback = null
                mainHandler.post { cb?.invoke() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "speakLocal exception", e)
            isSpeaking = false
            currentUtteranceCallback = null
            mainHandler.post { onComplete?.invoke() }
        }
    }

    /**
     * Calls Google Gemini Live Audio / TTS API
     * Uses gemini-2.5-flash-preview-tts
     * On failure, logs and falls back to local TTS.
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
                    val errBody = resp.body?.string()?.take(200) ?: ""
                    Log.w(TAG, "Cloud TTS HTTP ${resp.code}: $errBody")
                    // 400/404 = model not found -> cooldown 2min; 401/403 = auth -> cooldown 5min
                    when (resp.code) {
                        404, 400 -> cloudCooldownUntil = System.currentTimeMillis() + 120_000L
                        401, 403 -> {
                            cloudCooldownUntil = System.currentTimeMillis() + 300_000L
                            SystemLogBus.w(TAG, "Cloud TTS auth failed - using local voice")
                        }
                        429 -> cloudCooldownUntil = System.currentTimeMillis() + 60_000L
                        else -> if (resp.code >= 500) cloudCooldownUntil = System.currentTimeMillis() + 30_000L
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
            // Don't cooldown on network exception - may be transient
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
            var completed = false

            mainHandler.post {
                var mp: MediaPlayer? = null
                try {
                    mp = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener {
                            synchronized(lock) {
                                completed = true
                                try { file.delete() } catch (_: Exception) {}
                                lock.notifyAll()
                            }
                            // Release after completion
                            try { release() } catch (_: Exception) {}
                            if (mediaPlayer == this) mediaPlayer = null
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                            synchronized(lock) {
                                ok = false
                                completed = true
                                try { file.delete() } catch (_: Exception) {}
                                lock.notifyAll()
                            }
                            try { release() } catch (_: Exception) {}
                            if (mediaPlayer == this) mediaPlayer = null
                            true
                        }
                        // Use async prepare to avoid blocking
                        setOnPreparedListener { it.start() }
                        prepareAsync()
                    }
                    mediaPlayer = mp
                    isSpeaking = true
                } catch (e: Exception) {
                    Log.e(TAG, "MediaPlayer setup failed", e)
                    try { mp?.release() } catch (_: Exception) {}
                    synchronized(lock) {
                        ok = false
                        completed = true
                        try { file.delete() } catch (_: Exception) {}
                        lock.notifyAll()
                    }
                    isSpeaking = false
                }
            }

            synchronized(lock) {
                val start = System.currentTimeMillis()
                while (!completed && System.currentTimeMillis() - start < 15000L) {
                    lock.wait(15000L)
                }
                if (!completed) {
                    Log.w(TAG, "playAudio timeout")
                    ok = false
                }
            }
            isSpeaking = false
            ok
        } catch (e: Exception) {
            Log.e(TAG, "playAudio exception", e)
            isSpeaking = false
            false
        }
    }

    private fun stopPlayback() {
        isSpeaking = false
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    /**
     * Barge-in interruption: immediately cuts off all speech.
     */
    fun stop() {
        isSpeaking = false
        tokenAccumulator.setLength(0)
        speechQueue.clear()
        isPlayingQueue.set(false)
        currentUtteranceCallback = null
        stopPlayback()
        try { localTts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        stop()
        try { localTts?.shutdown() } catch (_: Exception) {}
        localTts = null
        localReady = false
        localInitFailed = false
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
