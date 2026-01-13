package com.assistant.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Helper utilities for accessibility service.
 */
object AccessibilityHelper {
    private const val TAG = "AccessibilityHelper"
    
    /**
     * Open accessibility settings page.
     * User can enable the Spotify Auto-Click service from there.
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened accessibility settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
        }
    }
    
    /**
     * Check if accessibility service is enabled.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceName = "${context.packageName}/.services.accessibility.SpotifyAutoClickService"
        
        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            
            val isEnabled = enabledServices.contains(serviceName)
            Log.i(TAG, "Accessibility service enabled: $isEnabled")
            return isEnabled
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service status", e)
            return false
        }
    }
    
    /**
     * Get user-friendly instructions for enabling the service.
     */
    fun getEnableInstructions(): String {
        return """
            To enable Spotify auto-click:
            
            1. Go to Settings → Accessibility
            2. Find "Assistant" or "Spotify Auto-Click"
            3. Toggle it ON
            4. Grant permission
            
            Then try playing a song!
        """.trimIndent()
    }
}
