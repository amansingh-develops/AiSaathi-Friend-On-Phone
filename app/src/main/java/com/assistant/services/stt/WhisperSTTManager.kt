package com.assistant.services.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.services.openai.WhisperClient
import com.assistant.services.voice.SpeechToTextEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whisper STT Engine.
 *
 * Architecture:
 * - VAD (Voice Activity Detection) runs locally on audio stream.
 * - Records until silence detected.
 * - Uploads WAV to Whisper API.
 * - Returns final text.
 */
class WhisperSTTManager(
    context: Context,
    private val client: WhisperClient = WhisperClient()
) : SpeechToTextEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var listener: SpeechToTextEngine.Listener? = null
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    companion object {
        private const val TAG = "WhisperSTT"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // VAD Thresholds
        // Reverted to higher threshold (450.0) to prevent self-triggering (Echo).
        // User reported "detecting Assistant voice", meaning AEC isn't perfect.
        private const val MIN_START_THRESHOLD_RMS = 450.0 
        private const val END_SILENCE_MS = 1200L // Balanced turn-taking
        private const val MAX_RECORD_MS = 15000L
        private const val WAIT_FOR_SPEECH_MS = 5000L
    }

    private val selfSpeechMaskingEnabled = AtomicBoolean(false)

    fun setSelfSpeechMasking(enabled: Boolean) {
        selfSpeechMaskingEnabled.set(enabled)
        Log.d(TAG, "Self-speech masking enabled=$enabled")
    }

    override fun setListener(listener: SpeechToTextEngine.Listener?) {
        this.listener = listener
    }

    override fun warmUp() {
        // No-op
    }

    override fun startListening(language: OnboardingLanguage, promptContext: String?) {
        if (!client.isConfigured()) {
            Log.e(TAG, "Whisper API key missing")
            listener?.onError(-1)
            return
        }
        if (!running.compareAndSet(false, true)) return

        job = scope.launch {
            try {
                mainHandler.post { listener?.onReady() }
                
                // 1. Record Audio with VAD
                val wav = recordOneUtteranceToWav() ?: run {
                    Log.d(TAG, "No speech detected.")
                    mainHandler.post { listener?.onError(-2) } // Code -2: No speech
                    return@launch
                }

                // 2. Transcribe
                Log.d(TAG, "Transcribing ${wav.length()} bytes...")
                // Pass null language to use auto-detect from WhisperClient
                val text = client.transcribe(wav, language = null, promptContext = promptContext)
                
                if (text.isNullOrBlank()) {
                    mainHandler.post { listener?.onError(-3) } // Code -3: Empty/Err
                } else {
                    Log.d(TAG, "Whisper Result: $text")
                    // Whisper is inherently "final" only for this chunk.
                    mainHandler.post { listener?.onFinalText(text) }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in Whisper pipeline", e)
                mainHandler.post { listener?.onError(-4) }
            } finally {
                running.set(false)
            }
        }
    }

    override fun stopListening() {
        cancel()
    }

    override fun cancel() {
        running.set(false)
        job?.cancel()
        job = null
    }

    override fun shutdown() {
        cancel()
        scope.cancel()
    }

    private fun recordOneUtteranceToWav(): File? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = (minBuffer * 2).coerceAtLeast(SAMPLE_RATE / 2)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            return null
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            return null
        }

        // ENABLE ECHO CANCELLATION (Critical for Full Duplex)
        val sessionId = audioRecord.audioSessionId
        if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            try {
                val aec = android.media.audiofx.AcousticEchoCanceler.create(sessionId)
                if (aec != null) {
                    aec.enabled = true
                    Log.d(TAG, "AcousticEchoCanceler enabled (Session $sessionId)")
                } else {
                    Log.w(TAG, "AcousticEchoCanceler.create() returned null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable AEC", e)
            }
        }
        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            try {
                val ns = android.media.audiofx.NoiseSuppressor.create(sessionId)
                if (ns != null) {
                    ns.enabled = true
                    Log.d(TAG, "NoiseSuppressor enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable NS", e)
            }
        }

        val pcm = ByteArrayOutputStream()
        val readBuffer = ByteArray(bufferSize)

        var started = false
        val createdAt = System.currentTimeMillis()
        var speechStartAt = createdAt
        var lastNonSilent = createdAt

        // Simple adaptive threshold
        var noiseRms = 0.0
        var noiseSamples = 0
        var calibratedThreshold = MIN_START_THRESHOLD_RMS

        try {
            audioRecord.startRecording()
            while (running.get()) {
                val n = audioRecord.read(readBuffer, 0, readBuffer.size)
                if (n <= 0) continue

                val rms = rms16bit(readBuffer, n)
                val now = System.currentTimeMillis()

                // Calibration (first 500ms)
                if (!started && now - createdAt <= 500L) {
                    noiseRms += rms
                    noiseSamples++
                    continue
                } else if (!started && noiseSamples > 0) {
                    val avgNoise = noiseRms / noiseSamples
                    calibratedThreshold = (avgNoise * 1.8).coerceAtLeast(MIN_START_THRESHOLD_RMS)
                    noiseSamples = 0
                }
                
                // Dynamic Thresholding:
                // If assistant is speaking (selfSpeechMaskingEnabled), we raise the bar.
                // Multiplier reduced from 2.5 to 1.8 to allow easier barge-in.
                val activeThreshold = if (selfSpeechMaskingEnabled.get()) {
                    calibratedThreshold * 1.8 // Masking Multiplier
                } else {
                    calibratedThreshold
                }

                if (!started) {
                    if (rms >= activeThreshold) {
                        started = true
                        speechStartAt = now
                        lastNonSilent = now
                        Log.d(TAG, "Voice started (RMS=$rms, Thresh=$activeThreshold)")
                        mainHandler.post { listener?.onBeginningOfSpeech() }
                    } else {
                        if (now - createdAt > WAIT_FOR_SPEECH_MS) break
                        continue
                    }
                }

                // Buffer audio
                pcm.write(readBuffer, 0, n)

                if (rms >= activeThreshold * 0.5) {
                    lastNonSilent = now
                }

                if (now - lastNonSilent >= END_SILENCE_MS) {
                    Log.d(TAG, "Silence detected")
                    break
                }
                if (now - speechStartAt >= MAX_RECORD_MS) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording error", e)
            return null
        } finally {
            try { audioRecord.stop(); audioRecord.release() } catch (_: Exception) {}
            if (started) mainHandler.post { listener?.onEndOfSpeech() }
        }

        val bytes = pcm.toByteArray()
        if (bytes.isEmpty()) return null

        val outFile = File(appContext.cacheDir, "whisper_input.wav")
        return try {
            FileOutputStream(outFile).use { fos ->
                writeWavHeader(fos, bytes.size, SAMPLE_RATE, 1, 16)
                fos.write(bytes)
            }
            outFile
        } catch (e: Exception) {
            null
        }
    }

    private fun rms16bit(buf: ByteArray, len: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val s = ((buf[i].toInt() and 0xFF) or (buf[i+1].toInt() shl 8)).toShort()
            sum += s * s
            i += 2
        }
        return kotlin.math.sqrt(sum / ((len/2).coerceAtLeast(1)))
    }

    private fun writeWavHeader(fos: FileOutputStream, pcmLen: Int, sampleRate: Int, channels: Int, bits: Int) {
        val byteRate = sampleRate * channels * bits / 8
        val total = pcmLen + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(total)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bits / 8).toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray())
        header.putInt(pcmLen)
        fos.write(header.array())
    }
}
