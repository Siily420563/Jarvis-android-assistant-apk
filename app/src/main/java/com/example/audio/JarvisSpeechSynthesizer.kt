package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class JarvisSpeechSynthesizer(private val context: Context) {

    companion object {
        @Volatile
        var instance: JarvisSpeechSynthesizer? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var androidTts: TextToSpeech? = null
    @Volatile
    private var isTtsReady = false

    init {
        instance = this
        initAndroidTts()
    }

    private fun initAndroidTts() {
        androidTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val hiLocale = Locale("hi", "IN")
                val res = androidTts?.setLanguage(hiLocale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    androidTts?.setLanguage(Locale("en", "IN"))
                }
                isTtsReady = true
                Log.i("SaraVoice", "Android TextToSpeech engine initialized (hi-IN / fallback en-IN)")
            } else {
                Log.w("SaraVoice", "Android TextToSpeech initialization failed with status $status")
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private var isAudioPlaying = false

    @Volatile
    private var currentSpeakJob: Job? = null

    fun speakLocal(text: String, onComplete: (() -> Unit)? = null) {
        val cleanText = cleanTextForSpeech(text)
        if (cleanText.isBlank()) {
            mainHandler.post { onComplete?.invoke() }
            return
        }

        stop()
        mainHandler.post {
            val ttsEngine = androidTts
            if (ttsEngine != null && isTtsReady) {
                val utteranceId = "tts_${System.currentTimeMillis()}"
                ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        isAudioPlaying = true
                    }

                    override fun onDone(id: String?) {
                        isAudioPlaying = false
                        mainHandler.post { onComplete?.invoke() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        isAudioPlaying = false
                        mainHandler.post { onComplete?.invoke() }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        isAudioPlaying = false
                        mainHandler.post { onComplete?.invoke() }
                    }
                })
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                isAudioPlaying = true
                ttsEngine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            } else {
                Log.w("SaraVoice", "Android TTS not ready")
                mainHandler.post { onComplete?.invoke() }
            }
        }
    }

    /**
     * Speak text using Android TTS or Cloud AI TTS.
     * Uses Android TTS:
     * (a) always for texts shorter than 6 words
     * (b) whenever cloud TTS fails, times out after 3 seconds, or no API key exists.
     */
    fun speak(
        text: String,
        apiKey: String? = null,
        groqApiKey: String? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val cleanText = cleanTextForSpeech(text)
        if (cleanText.isBlank()) {
            mainHandler.post { onComplete?.invoke() }
            return
        }

        val words = cleanText.split(Regex("\\s+")).filter { it.isNotBlank() }
        // (a) Always for texts shorter than 6 words
        if (words.size < 6) {
            Log.i("SaraVoice", "Text shorter than 6 words (${words.size} words). Using local Android TTS.")
            speakLocal(cleanText, onComplete)
            return
        }

        val key = apiKey?.ifBlank { null } ?: getBuildConfigGeminiKey()

        // (b) Whenever no API key exists
        if (key.isBlank() && groqApiKey.isNullOrBlank()) {
            Log.i("SaraVoice", "No API key available for cloud TTS. Using local Android TTS.")
            speakLocal(cleanText, onComplete)
            return
        }

        stop()

        currentSpeakJob = scope.launch {
            var audioBytes: ByteArray? = null
            var mimeType: String? = null

            // (b) Whenever cloud TTS fails or times out after 3 seconds
            val cloudResult = withTimeoutOrNull(3000L) {
                // 1. Primary: Gemini TTS (with 2 retry attempts)
                if (!key.isNullOrBlank()) {
                    for (attempt in 1..2) {
                        try {
                            val result = fetchGeminiTts(cleanText, key)
                            if (result != null) {
                                return@withTimeoutOrNull result
                            }
                        } catch (e: Exception) {
                            Log.w("SaraVoice", "Gemini TTS attempt $attempt failed: ${e.message}")
                            delay(300)
                        }
                    }
                }

                // 2. Fallback: Groq TTS if Gemini failed and Groq key is present
                if (!groqApiKey.isNullOrBlank()) {
                    for (attempt in 1..2) {
                        try {
                            val result = fetchGroqTts(cleanText, groqApiKey)
                            if (result != null) {
                                return@withTimeoutOrNull result
                            }
                        } catch (e: Exception) {
                            Log.w("SaraVoice", "Groq TTS attempt $attempt failed: ${e.message}")
                            delay(300)
                        }
                    }
                }
                null
            }

            if (cloudResult != null) {
                audioBytes = cloudResult.first
                mimeType = cloudResult.second
            }

            // 3. Play audio if fetched, otherwise local Android TTS fallback
            if (audioBytes != null && audioBytes.isNotEmpty()) {
                playAudioBytes(audioBytes, mimeType ?: "audio/wav", onComplete)
            } else {
                Log.i("SaraVoice", "Cloud TTS failed or timed out after 3s. Falling back to local Android TTS.")
                speakLocal(cleanText, onComplete)
            }
        }
    }

    private suspend fun fetchGeminiTts(text: String, apiKey: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        try {
            val modelsToTry = listOf("gemini-3.1-flash-tts-preview")
            for (model in modelsToTry) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

                    val jsonBody = JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("text", text)
                                    })
                                })
                            })
                        })
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", JSONArray().apply {
                                put("AUDIO")
                            })
                            put("speechConfig", JSONObject().apply {
                                put("voiceConfig", JSONObject().apply {
                                    put("prebuiltVoiceConfig", JSONObject().apply {
                                        put("voiceName", "Kore") // Friendly & warm persona voice
                                    })
                                })
                            })
                        })
                    }

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Content-Type", "application/json")
                        .post(jsonBody.toString().toRequestBody(jsonMediaType))
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: continue
                        val respJson = JSONObject(bodyStr)
                        val candidates = respJson.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val parts = candidates.getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")

                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                if (part.has("inlineData")) {
                                    val inline = part.getJSONObject("inlineData")
                                    val mime = inline.optString("mimeType", "audio/wav")
                                    val dataBase64 = inline.optString("data", "")
                                    if (dataBase64.isNotBlank()) {
                                        val bytes = Base64.decode(dataBase64, Base64.DEFAULT)
                                        return@withContext Pair(bytes, mime)
                                    }
                                }
                            }
                        }
                    } else {
                        Log.w("SaraVoice", "Gemini TTS returned status ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.w("SaraVoice", "Model $model failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("SaraVoice", "fetchGeminiTts failed", e)
        }
        return@withContext null
    }

    private suspend fun fetchGroqTts(text: String, apiKey: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.groq.com/openai/v1/audio/speech"
            val jsonBody = JSONObject().apply {
                put("model", "playai-tts")
                put("input", text)
                put("voice", "nova")
                put("response_format", "mp3")
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    return@withContext Pair(bytes, "audio/mp3")
                }
            }
        } catch (e: Exception) {
            Log.w("SaraVoice", "Groq TTS failed: ${e.message}")
        }
        return@withContext null
    }

    private fun playAudioBytes(bytes: ByteArray, mimeType: String, onComplete: (() -> Unit)?) {
        try {
            stop()
            isAudioPlaying = true

            // If raw PCM (often 24000Hz 16-bit mono from Gemini audio), play via AudioTrack or wrap in WAV header
            val playableBytes = if (mimeType.contains("pcm", ignoreCase = true) || !hasWavOrMp3Header(bytes)) {
                wrapPcmWithWavHeader(bytes, 24000, 1, 16)
            } else {
                bytes
            }

            val tempFile = File(context.cacheDir, "sara_ai_speech_${System.currentTimeMillis()}.wav")
            FileOutputStream(tempFile).use { it.write(playableBytes) }

            val mp = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    this@JarvisSpeechSynthesizer.isAudioPlaying = false
                    try { tempFile.delete() } catch (e: Exception) {}
                    mainHandler.post { onComplete?.invoke() }
                }
                setOnErrorListener { _, _, _ ->
                    this@JarvisSpeechSynthesizer.isAudioPlaying = false
                    try { tempFile.delete() } catch (e: Exception) {}
                    mainHandler.post { onComplete?.invoke() }
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e("SaraVoice", "Failed to play audio bytes", e)
            isAudioPlaying = false
            mainHandler.post { onComplete?.invoke() }
        }
    }

    private fun hasWavOrMp3Header(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val isRiff = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        val isId3 = bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
        val isMp3Sync = (bytes[0].toInt() and 0xFF) == 0xFF && ((bytes[1].toInt() and 0xE0) == 0xE0)
        return isRiff || isId3 || isMp3Sync
    }

    private fun wrapPcmWithWavHeader(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = blockAlign.toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val output = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, output, 0, 44)
        System.arraycopy(pcmData, 0, output, 44, pcmData.size)
        return output
    }

    private fun getBuildConfigGeminiKey(): String {
        return try {
            val field = com.example.BuildConfig::class.java.getField("GEMINI_API_KEY")
            val value = field.get(null) as? String ?: ""
            if (value.isNotBlank() && value != "null" && value != "MY_GEMINI_API_KEY") value else ""
        } catch (e: Throwable) {
            ""
        }
    }

    fun stop() {
        try {
            currentSpeakJob?.cancel()
            currentSpeakJob = null
            androidTts?.stop()
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
            audioTrack?.let {
                it.stop()
                it.release()
            }
            audioTrack = null
            isAudioPlaying = false
        } catch (e: Exception) {
            Log.w("SaraVoice", "Error stopping audio: ${e.message}")
        }
    }

    fun shutdown() {
        stop()
        try {
            androidTts?.shutdown()
            androidTts = null
        } catch (e: Exception) {}
        if (instance == this) {
            instance = null
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

