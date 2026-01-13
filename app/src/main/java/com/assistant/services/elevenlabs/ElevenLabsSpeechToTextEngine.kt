package com.assistant.services.elevenlabs

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.assistant.domain.onboarding.OnboardingLanguage
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
 * ElevenLabs Speech-to-Text engine (NO Android SpeechRecognizer).
 *
 * Why this exists:
 * - SpeechRecognizer causes unavoidable OEM "beep/earcons" on many devices.
 * - We want a voice-first UX: NO beeps, only spoken acknowledgement.
 *
 * Approach:
 * - Capture audio with AudioRecord (16kHz mono PCM).
 * - Detect speech start/end locally (simple energy threshold).
 * - Write WAV to temp file.
 * - Upload to ElevenLabs STT and return transcript.
 *
 * Notes:
 * - This implementation provides FINAL results only (no true streaming partials).
 *   The pipeline still works; it just won’t do “partial intent” until we add a realtime STT API.
 */
class ElevenLabsSpeechToTextEngine(
    context: Context,
    private val client: ElevenLabsClient = ElevenLabsClient()
) : SpeechToTextEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var listener: SpeechToTextEngine.Listener? = null
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    companion object {
        private const val TAG = "ElevenLabsSTT"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Simple VAD thresholds (adaptive).
        // We calibrate a noise floor and set a dynamic start threshold.
        // Lowered significantly to detect quiet/normal speech (was 700, way too high).
        private const val MIN_START_THRESHOLD_RMS = 180.0
        // Increased silence threshold to capture longer phrases/sentences.
        private const val END_SILENCE_MS = 1800L
        private const val MAX_RECORD_MS = 12000L
        private const val WAIT_FOR_SPEECH_MS = 3500L
    }

    override fun setListener(listener: SpeechToTextEngine.Listener?) {
        this.listener = listener
    }

    override fun warmUp() {
        // Nothing to warm locally. This stays async and cheap.
    }

    override fun startListening(language: OnboardingLanguage, promptContext: String?) {
        if (!client.isConfigured()) {
            listener?.onError(-1)
            return
        }
        if (!running.compareAndSet(false, true)) return

        job = scope.launch {
            try {
                mainHandler.post { listener?.onReady() }
                val wav = recordOneUtteranceToWav() ?: run {
                    Log.w(TAG, "No audio captured (VAD didn't detect speech or recording failed)")
                    mainHandler.post { listener?.onError(-2) }
                    return@launch
                }

                Log.d(TAG, "Recorded WAV: ${wav.length()} bytes, sending to ElevenLabs...")
                val transcript = client.stt(wav)
                Log.d(TAG, "ElevenLabs transcript result: '$transcript' (length=${transcript?.length ?: 0})")
                
                if (transcript.isNullOrBlank()) {
                    Log.w(TAG, "Empty transcript from ElevenLabs (audio may be unclear or too quiet)")
                    mainHandler.post { listener?.onError(-3) }
                } else {
                    mainHandler.post { listener?.onFinalText(transcript) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "STT flow failed", e)
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
            Log.w(TAG, "AudioRecord create failed", e)
            return null
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized (state=${audioRecord.state})")
            try { audioRecord.release() } catch (_: Exception) {}
            return null
        }

        val pcm = ByteArrayOutputStream()
        val readBuffer = ByteArray(bufferSize)

        var started = false
        val createdAt = System.currentTimeMillis()
        var speechStartAt = createdAt
        var lastNonSilent = createdAt

        // Calibrate noise floor quickly (first few reads).
        var noiseRms = 0.0
        var noiseSamples = 0
        var startThreshold = MIN_START_THRESHOLD_RMS

        try {
            audioRecord.startRecording()
            while (running.get()) {
                val n = audioRecord.read(readBuffer, 0, readBuffer.size)
                if (n <= 0) continue

                val rms = rms16bit(readBuffer, n)
                val now = System.currentTimeMillis()

                // 1) Noise calibration phase (first ~600ms).
                // Wait longer to let any acknowledgement audio settle.
                if (!started && now - createdAt <= 650L) {
                    noiseRms += rms
                    noiseSamples += 1
                    continue
                } else if (!started && noiseSamples > 0) {
                    val avgNoise = noiseRms / noiseSamples
                    // Dynamic threshold: 2x noise floor (more sensitive), with a lower minimum.
                    // This helps detect normal/quiet speech without false triggers.
                    startThreshold = maxOf(MIN_START_THRESHOLD_RMS, avgNoise * 2.0)
                    noiseSamples = 0 // lock it
                    Log.d(TAG, "VAD threshold set: $startThreshold (avgNoise=$avgNoise)")
                }

                if (!started) {
                    if (rms >= startThreshold) {
                        started = true
                        speechStartAt = now
                        lastNonSilent = now
                        Log.d(TAG, "Speech started! RMS=$rms (threshold=$startThreshold)")
                        mainHandler.post { listener?.onBeginningOfSpeech() }
                    } else {
                        // Wait a little longer for speech; some mics start "quiet".
                        if (now - createdAt > WAIT_FOR_SPEECH_MS) {
                            Log.d(TAG, "Timeout waiting for speech (${WAIT_FOR_SPEECH_MS}ms)")
                            break
                        }
                        continue
                    }
                }

                // Buffer only after speech begins.
                pcm.write(readBuffer, 0, n)

                // Consider it "non-silent" if above 40% of start threshold.
                // This is more forgiving for natural speech with pauses.
                if (rms >= startThreshold * 0.40) {
                    lastNonSilent = now
                }

                // End conditions:
                if (now - lastNonSilent >= END_SILENCE_MS) {
                    Log.d(TAG, "Stopping: silence detected for ${END_SILENCE_MS}ms")
                    break
                }
                if (now - speechStartAt >= MAX_RECORD_MS) {
                    Log.d(TAG, "Stopping: max recording time ${MAX_RECORD_MS}ms reached")
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord loop failed", e)
            return null
        } finally {
            try { audioRecord.stop() } catch (_: Exception) {}
            try { audioRecord.release() } catch (_: Exception) {}
            mainHandler.post { listener?.onEndOfSpeech() }
        }

        val pcmBytes = pcm.toByteArray()
        if (pcmBytes.isEmpty()) return null

        // Write temp WAV.
        val outFile = File(appContext.cacheDir, "stt_${System.currentTimeMillis()}.wav")
        return try {
            FileOutputStream(outFile).use { fos ->
                writeWavHeader(
                    fos = fos,
                    pcmDataLen = pcmBytes.size,
                    sampleRate = SAMPLE_RATE,
                    channels = 1,
                    bitsPerSample = 16
                )
                fos.write(pcmBytes)
            }
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "WAV write failed", e)
            null
        }
    }

    private fun rms16bit(buf: ByteArray, len: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = sample.toShort().toDouble()
            sum += s * s
            i += 2
        }
        val n = (len / 2).coerceAtLeast(1)
        return kotlin.math.sqrt(sum / n)
    }

    private fun writeWavHeader(
        fos: FileOutputStream,
        pcmDataLen: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val totalDataLen = pcmDataLen + 36

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // PCM
        header.putShort(1) // AudioFormat PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(pcmDataLen)
        fos.write(header.array())
    }
}


