package com.assistant.services.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Manages system audio focus for the assistant.
 * 
 * Responsibilities:
 * - Request audio focus before assistant speaks
 * - Abandon audio focus after assistant finishes
 * - Handle interruptions (e.g. phone call, other media)
 * 
 * This ensures the assistant pauses music/podcasts when it speaks,
 * and allows them to resume afterwards (DUCK vs TRANSIENT).
 */
class AudioFocusManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    
    // Callback for when focus changes (e.g. lost to another app)
    var onFocusLost: (() -> Unit)? = null

    companion object {
        private const val TAG = "AudioFocusManager"
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost")
                onFocusLost?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost (ducking)")
                // Usually we don't need to stop speaking for ducking, but for a voice assistant
                // we might want to pause if the user is doing something involved.
                // For now, we continue speaking.
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
            }
        }
    }

    /**
     * Request audio focus for speech synthesis.
     * Uses AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK to lower volume of background music.
     */
    fun requestOutputFocus(): Boolean {
        Log.d(TAG, "Requesting audio output focus...")
        
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_ALARM, // Use Stream Alarm or Music
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }

        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Abandon audio focus.
     */
    fun abandonOutputFocus() {
        Log.d(TAG, "Abandoning audio output focus")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }
}
