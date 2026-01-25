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
 * - Request audio focus before assistant speaks (PAUSES other apps)
 * - Abandon audio focus after assistant finishes (RESUMES other apps)
 * - Handle interruptions (e.g. phone call, other media)
 * 
 * Uses AUDIOFOCUS_GAIN_TRANSIENT to PAUSE music/podcasts when speaking.
 * When focus is abandoned, media apps automatically resume playback.
 */
class AudioFocusManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    
    // Track focus state for external queries
    @Volatile
    private var _hasFocus: Boolean = false
    
    /** Returns true if we currently hold audio focus */
    fun hasFocus(): Boolean = _hasFocus
    
    // Callback for when focus changes (e.g. lost to another app)
    var onFocusLost: (() -> Unit)? = null
    var onFocusGained: (() -> Unit)? = null

    companion object {
        private const val TAG = "AudioFocusManager"
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost")
                _hasFocus = false
                onFocusLost?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost (ducking) - ignoring, still have focus")
                // For voice assistant, we keep focus during duck requests
                // Other apps should pause for us, not the other way around
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                _hasFocus = true
                onFocusGained?.invoke()
            }
        }
    }

    /**
     * Request audio focus for voice assistant session.
     * Uses AUDIOFOCUS_GAIN_TRANSIENT to PAUSE background music (not just duck).
     * Music will automatically resume when abandonOutputFocus() is called.
     */
    fun requestOutputFocus(): Boolean {
        Log.d(TAG, "Requesting audio output focus...")
        
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // AUDIOFOCUS_GAIN_TRANSIENT: Pauses other apps temporarily
            // (vs MAY_DUCK which only lowers volume)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)  // Allow delayed grant if focus can't be given immediately
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,  // Use STREAM_MUSIC to properly pause music apps
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT  // PAUSE (not just duck) other apps
            )
        }

        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ||
                       result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        
        if (granted) {
            _hasFocus = true
            Log.d(TAG, "Audio focus granted (result=$result)")
        } else {
            Log.w(TAG, "Audio focus DENIED (result=$result)")
        }
        
        return granted
    }

    /**
     * Abandon audio focus.
     */
    fun abandonOutputFocus() {
        Log.d(TAG, "Abandoning audio output focus (music will resume)")
        _hasFocus = false
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }
}
