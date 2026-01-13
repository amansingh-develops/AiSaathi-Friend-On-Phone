package com.assistant.services.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent

/**
 * NotificationListenerService for real media playback control.
 * 
 * This service enables actual control of media playback (play/pause/next/previous)
 * for apps like Spotify, YouTube, etc., instead of just launching them.
 * 
 * Fixes: "Spotify opens but does not play" issue.
 */
class MediaControlService : NotificationListenerService() {
    companion object {
        private const val TAG = "MediaControlService"
        
        @Volatile
        private var instance: MediaControlService? = null
        
        /**
         * Get the active instance of this service.
         */
        fun getInstance(): MediaControlService? = instance
        
        /**
         * Check if service is running.
         */
        fun isRunning(): Boolean = instance != null
    }
    
    private lateinit var mediaSessionManager: MediaSessionManager
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        Log.i(TAG, "MediaControlService created")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "MediaControlService destroyed")
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // We don't need to do anything when notifications are posted
        // We only use this service for media session access
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // We don't need to do anything when notifications are removed
    }
    
    /**
     * Get the active media controller.
     */
    private fun getActiveMediaController(): MediaController? {
        try {
            val controllers = mediaSessionManager.getActiveSessions(
                ComponentName(this, MediaControlService::class.java)
            )
            
            // Return the first active controller (usually the most recent media app)
            return controllers.firstOrNull()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Notification listener permission not granted", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active media controller", e)
            return null
        }
    }
    
    /**
     * Play media.
     */
    fun play(): Boolean {
        val controller = getActiveMediaController()
        if (controller != null) {
            Log.d(TAG, "Sending PLAY command to ${controller.packageName}")
            controller.transportControls.play()
            return true
        } else {
            Log.w(TAG, "No active media controller found")
            return false
        }
    }
    
    /**
     * Pause media.
     */
    fun pause(): Boolean {
        val controller = getActiveMediaController()
        if (controller != null) {
            Log.d(TAG, "Sending PAUSE command to ${controller.packageName}")
            controller.transportControls.pause()
            return true
        } else {
            Log.w(TAG, "No active media controller found")
            return false
        }
    }
    
    /**
     * Toggle play/pause.
     */
    fun togglePlayPause(): Boolean {
        val controller = getActiveMediaController()
        if (controller != null) {
            val isPlaying = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            Log.d(TAG, "Toggling play/pause for ${controller.packageName}, currently playing=$isPlaying")
            
            if (isPlaying) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
            return true
        } else {
            Log.w(TAG, "No active media controller found")
            return false
        }
    }
    
    /**
     * Skip to next track.
     */
    fun next(): Boolean {
        val controller = getActiveMediaController()
        if (controller != null) {
            Log.d(TAG, "Sending NEXT command to ${controller.packageName}")
            controller.transportControls.skipToNext()
            return true
        } else {
            Log.w(TAG, "No active media controller found")
            return false
        }
    }
    
    /**
     * Skip to previous track.
     */
    fun previous(): Boolean {
        val controller = getActiveMediaController()
        if (controller != null) {
            Log.d(TAG, "Sending PREVIOUS command to ${controller.packageName}")
            controller.transportControls.skipToPrevious()
            return true
        } else {
            Log.w(TAG, "No active media controller found")
            return false
        }
    }
    
    /**
     * Send a media button event (alternative method).
     */
    private fun sendMediaButtonEvent(keyCode: Int): Boolean {
        val controller = getActiveMediaController()
        if (controller != null) {
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            
            controller.dispatchMediaButtonEvent(downEvent)
            controller.dispatchMediaButtonEvent(upEvent)
            return true
        }
        return false
    }
    
    /**
     * Get the package name of the active media app.
     */
    fun getActiveMediaApp(): String? {
        return getActiveMediaController()?.packageName
    }
    
    /**
     * Check if media is currently playing.
     */
    fun isPlaying(): Boolean {
        val controller = getActiveMediaController()
        return controller?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
    }
}
