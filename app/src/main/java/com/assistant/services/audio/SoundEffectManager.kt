package com.assistant.services.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Manages UI sound effects for wake-word and command processing.
 *
 * Design constraints:
 * - Ultra-low latency (must play IMMEDIATELY on wake).
 * - Non-intrusive (soft, short beeps).
 * - Distinct sounds for "Wake" vs "Captured".
 */
class SoundEffectManager(private val context: Context) {

    private var toneGenerator: ToneGenerator? = null
    
    // Tweakable volume (0-100). Default to 80% to be audible but not deafening.
    private val TONE_VOLUME = 80

    companion object {
        private const val TAG = "SoundEffectManager"
    }

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ToneGenerator", e)
        }
    }

    /**
     * Sound 1: Played immediately when Wake Word is detected.
     * Indicates: "I am listening".
     *
     * Sound: TONE_CDMA_PIP (Short, high pitch chirp)
     */
    fun playWakeConfirmation() {
        playTone(ToneGenerator.TONE_CDMA_PIP, 150) // 150ms duration
    }

    /**
     * Sound 2: Played when Whisper produces FINAL text.
     * Indicates: "I heard you".
     *
     * Sound: TONE_PROP_ACK (Softer acknowledgement beep)
     * OR TONE_SUP_CONFIRM
     */
    fun playCommandCaptured() {
        playTone(ToneGenerator.TONE_PROP_ACK, 100) // 100ms duration
    }

    /**
     * Play a generic error tone.
     */
    fun playError() {
        playTone(ToneGenerator.TONE_PROP_NACK, 200)
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        try {
            toneGenerator?.startTone(toneType, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing tone", e)
            // Attempt to re-init if it crashed
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
                toneGenerator?.startTone(toneType, durationMs)
            } catch (ignored: Exception) { }
        }
    }

    fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }
}
