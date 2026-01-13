package com.assistant.services.listening

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight VAD-only listener to detect user speech while TTS is playing.
 *
 * Rules:
 * - Runs only during SPEAKING state.
 * - Does NOT run Whisper; just monitors RMS energy.
 * - Stops immediately on detection and invokes callback.
 */
class BargeInDetector(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BargeInDetector"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val WINDOW_MS = 120L
        private const val TRIGGER_RMS = 420.0  // Tuned to avoid TTS bleed-through
    }

    private val running = AtomicBoolean(false)
    private var job: Job? = null

    fun start(onSpeechDetected: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch(Dispatchers.Default) {
            monitor(onSpeechDetected)
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun monitor(onSpeechDetected: () -> Unit) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = (minBuffer * 2).coerceAtLeast(SAMPLE_RATE / 4)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord init failed for barge-in", e)
            running.set(false)
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "Barge-in AudioRecord not initialized")
            running.set(false)
            return
        }

        val readBuffer = ByteArray(bufferSize)

        try {
            recorder.startRecording()
            while (running.get() && scope.isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val n = recorder.read(readBuffer, 0, readBuffer.size)
                if (n <= 0) continue
                val rms = rms16bit(readBuffer, n)
                if (rms >= TRIGGER_RMS) {
                    Log.d(TAG, "Barge-in detected (RMS=$rms)")
                    withContext(Dispatchers.Main) { onSpeechDetected() }
                    break
                }
                // Windowed sampling
                kotlinx.coroutines.delay(WINDOW_MS)
            }
        } catch (_: Exception) {
            // Ignore; detector is best-effort
        } finally {
            try {
                recorder.stop()
                recorder.release()
            } catch (_: Exception) {
            }
            running.set(false)
        }
    }

    private fun rms16bit(buf: ByteArray, len: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort()
            sum += s * s
            i += 2
        }
        val samples = (len / 2).coerceAtLeast(1)
        return kotlin.math.sqrt(sum / samples)
    }
}

