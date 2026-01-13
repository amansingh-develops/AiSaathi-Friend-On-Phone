package com.assistant.ui.onboarding

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Plays a soft message notification sound (WhatsApp-like).
 * Uses system notification sound as a placeholder.
 * 
 * Note: In production, add a custom sound file to res/raw/message_sound.ogg
 * and load it with: MediaPlayer.create(context, R.raw.message_sound)
 */
@Composable
fun rememberMessageSoundPlayer(): MessageSoundPlayer {
    val context = LocalContext.current
    return remember {
        MessageSoundPlayer(context)
    }
}

class MessageSoundPlayer(private val context: Context) {
    
    fun playMessageSound() {
        try {
            // Play a soft, short sound using system notification
            // In production, replace with: MediaPlayer.create(context, R.raw.message_sound)
            val notificationUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            val mediaPlayer = MediaPlayer.create(context, notificationUri)
            mediaPlayer?.apply {
                setVolume(0.25f, 0.25f) // Low volume for subtlety
                setOnCompletionListener { release() }
                start()
            }
        } catch (e: Exception) {
            // Silently fail if sound can't play (no permissions or sound unavailable)
        }
    }
    
    fun release() {
        // MediaPlayer releases itself after playing
    }
}

