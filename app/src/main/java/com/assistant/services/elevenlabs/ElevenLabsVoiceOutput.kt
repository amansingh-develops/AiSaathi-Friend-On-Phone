package com.assistant.services.elevenlabs

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.assistant.BuildConfig
import com.assistant.domain.model.VoiceGender
import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.services.voice.VoiceOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ElevenLabs Streaming Voice Output.
 *
 * Architecture:
 * - Uses AudioTrack for immediate low-latency playback of streaming chunks.
 * - Streams directly from ElevenLabs WebSocket/REST stream endpoint (using okhttp streaming).
 * - "Stop" immediately flushes the AudioTrack.
 */
class ElevenLabsVoiceOutput(
    private val context: Context
) : VoiceOutput {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().build()

    @Volatile private var gender: VoiceGender = VoiceGender.NEUTRAL
    @Volatile private var language: OnboardingLanguage = OnboardingLanguage.ENGLISH

    private var currentStreamJob: Job? = null
    // audioTrack removed in favor of MediaPlayer
    private var mediaPlayer: android.media.MediaPlayer? = null
    private val isPlaying = AtomicBoolean(false)

    companion object {
        private const val TAG = "ElevenLabsStream"
    }

    private var listener: VoiceOutput.Listener? = null

    override fun setListener(listener: VoiceOutput.Listener?) {
        this.listener = listener
    }

    override fun setVoiceGender(gender: VoiceGender) { this.gender = gender }
    override fun setLanguageHint(language: OnboardingLanguage) { this.language = language }

    override fun speak(text: String) {
        if (text.isBlank()) return
        stop() // Interrupt previous

        currentStreamJob = scope.launch {
            streamAudio(text)
        }
    }

    override fun stop() {
        val wasPlaying = isPlaying.getAndSet(false)
        currentStreamJob?.cancel()
        currentStreamJob = null
        
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
            
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.stop()
            }
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Media", e)
        }
        
        if (wasPlaying) {
             mainHandler.post { listener?.onSpeechEnded() }
        }
    }

    override fun shutdown() {
        stop()
        scope.cancel()
    }

    fun isConfigured(): Boolean {
        return BuildConfig.ELEVENLABS_API_KEY.isNotBlank()
    }

    // Stub for compatibility
    fun warmUpPhrases(@Suppress("UNUSED_PARAMETER") texts: List<String>) { }
    fun cacheResponse(@Suppress("UNUSED_PARAMETER") text: String) { }

    private fun streamAudio(text: String) {
        val voiceId = resolveVoiceId(gender)
        if (voiceId.isBlank()) return

        // 1. Request stream (MP3 44.1kHz)
        // Tune: model_id=eleven_multilingual_v2 for best Hindi/English accuracy
        // Tune: optimize_streaming_latency=3 (Balanced) for better quality than 4 (Max Speed)
        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?optimize_streaming_latency=3&output_format=mp3_44100_128&model_id=eleven_multilingual_v2"
        
        val json = JSONObject().apply {
            put("text", text)
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)       // Lower = More emotional/varied
                put("similarity_boost", 0.8)// High = Clear voice identity
                put("style", 0.5)           // Expressiveness (v2 model only)
                put("use_speaker_boost", true)
            })
        }.toString()
        val req = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(json.toRequestBody(null))
            .build()
            
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "ElevenLabs Stream Error: ${resp.code}")
                    if (resp.code == 401) {
                         com.assistant.services.error.GlobalErrorManager.reportError(
                             "ElevenLabs Auth Failed (401). Check API Key.",
                             com.assistant.services.error.GlobalErrorManager.ErrorType.AUTH_ERROR
                         )
                    } else if (resp.code == 429) {
                         com.assistant.services.error.GlobalErrorManager.reportError(
                             "ElevenLabs Limit Reached (429).",
                             com.assistant.services.error.GlobalErrorManager.ErrorType.API_LIMIT
                         )
                    }
                    return
                }

                val bytes = resp.body?.bytes() ?: return
                if (bytes.isNotEmpty()) {
                    playMp3Data(bytes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed", e)
        }
    }

    private fun playMp3Data(mp3Bytes: ByteArray) {
        try {
            stop() // Stop previous playback

            // Write MP3 to a temp file so MediaPlayer can read it
            val tempFile = java.io.File(context.cacheDir, "speech_output.mp3")
            java.io.FileOutputStream(tempFile).use { fos -> 
                fos.write(mp3Bytes) 
            }

            // Create new MediaPlayer on main thread or background? Background is fine for prepare(), 
            // but let's be safe with listeners.
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(tempFile.absolutePath)
            mp.prepare() // Synchronous prepare is okay here as we are already on background thread (streamAudio)
            mp.start()
            
            mediaPlayer = mp
            if (!isPlaying.getAndSet(true)) {
               mainHandler.post { listener?.onSpeechStarted() }
            }
            
            mp.setOnCompletionListener { mpRef ->
                if (isPlaying.getAndSet(false)) {
                    mainHandler.post { listener?.onSpeechEnded() }
                }
                mpRef.release()
                if (mediaPlayer == mpRef) {
                    mediaPlayer = null
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error playing MP3", e)
            if (isPlaying.getAndSet(false)) {
                 mainHandler.post { listener?.onSpeechEnded() }
            }
        }
    }

    private fun resolveVoiceId(gender: VoiceGender): String {
        return when (gender) {
            VoiceGender.FEMALE -> BuildConfig.ELEVENLABS_VOICE_ID_FEMALE
            VoiceGender.MALE -> BuildConfig.ELEVENLABS_VOICE_ID_MALE
            VoiceGender.NEUTRAL -> BuildConfig.ELEVENLABS_VOICE_ID_FEMALE.ifBlank { BuildConfig.ELEVENLABS_VOICE_ID_MALE }
        }
    }

    /**
     * Play raw audio chunks (e.g. from Agent WebSocket).
     * Decodes Base64 or takes raw bytes and plays immediately.
     * Assumes MP3 format (or whatever Agent sends).
     */
    private var audioTrack: AudioTrack? = null

    /**
     * Play raw audio chunks (PCM 16kHz 16-bit Mono) from Agent WebSocket.
     */
    fun streamRawAudio(audioData: ByteArray) {
        try {
            if (audioTrack == null) {
                // Initialize AudioTrack on first chunk
                // Agent usually sends 16kHz 16-bit PCM.
                val sampleRate = 16000 
                val channelConfig = AudioFormat.CHANNEL_OUT_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val format = AudioFormat.Builder()
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .build()

                // BUFFER SIZE OPTIMIZATION
                // 16kHz 16-bit mono = 32000 bytes/sec.
                // 4096 bytes is only ~128ms. Network jitter > 128ms causes silence (breaking voice).
                // We increase buffer to ~1 second (32KB) to absorb jitter.
                val safeBufferSize = maxOf(minBufferSize * 10, 32000)

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(safeBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()
                
                if (!isPlaying.getAndSet(true)) {
                    mainHandler.post { listener?.onSpeechStarted() }
                }
            }
            
            // Write data to track
            audioTrack?.write(audioData, 0, audioData.size)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to AudioTrack", e)
        }
    }
}


