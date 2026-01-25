package com.assistant.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Helper utilities for accessibility services.
 */
object AccessibilityHelper {
    private const val TAG = "AccessibilityHelper"
    
    /**
     * Open accessibility settings page.
     * User can enable the Spotify/YouTube Auto-Click services from there.
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
     * Check if Spotify accessibility service is enabled.
     */
    fun isSpotifyServiceEnabled(context: Context): Boolean {
        return isServiceEnabled(context, "SpotifyAutoClickService")
    }
    
    /**
     * Check if YouTube accessibility service is enabled.
     */
    fun isYouTubeServiceEnabled(context: Context): Boolean {
        return isServiceEnabled(context, "YouTubeAutoClickService")
    }
    
    /**
     * Check if Rapido Auto-Book accessibility service is enabled.
     */
    fun isRapidoServiceEnabled(context: Context): Boolean {
        return isServiceEnabled(context, "RapidoAutoBookService")
    }
    
    /**
     * Check if an accessibility service is enabled.
     */
    fun isServiceEnabled(context: Context, serviceName: String): Boolean {
        val fullServiceName = "${context.packageName}/.services.accessibility.$serviceName"
        
        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            
            val isEnabled = enabledServices.contains(fullServiceName) || 
                           enabledServices.contains(".$serviceName") ||
                           enabledServices.contains(serviceName)
            
            return isEnabled
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service status", e)
            return false
        }
    }
    
    /**
     * Log status of all accessibility services with detailed output.
     */
    fun logServiceStatus(context: Context) {
        val spotifyEnabled = isSpotifyServiceEnabled(context)
        val youtubeEnabled = isYouTubeServiceEnabled(context)
        val rapidoEnabled = isRapidoServiceEnabled(context)
        
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  ACCESSIBILITY SERVICE STATUS                          ║")
        Log.i(TAG, "╠════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║  Spotify Auto-Click:  ${if (spotifyEnabled) "✅ ENABLED" else "❌ DISABLED"}                   ║")
        Log.i(TAG, "║  YouTube Auto-Click:  ${if (youtubeEnabled) "✅ ENABLED" else "❌ DISABLED"}                   ║")
        Log.i(TAG, "║  Rapido Auto-Book:    ${if (rapidoEnabled) "✅ ENABLED" else "❌ DISABLED"}                   ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        if (!spotifyEnabled && !youtubeEnabled && !rapidoEnabled) {
            Log.w(TAG, "")
            Log.w(TAG, "⚠️ NO ACCESSIBILITY SERVICES ENABLED!")
            Log.w(TAG, "")
            Log.w(TAG, "To enable auto-play, go to:")
            Log.w(TAG, "  Settings → Accessibility → Installed Services")
            Log.w(TAG, "  Then enable 'Spotify Auto-Click' and/or 'YouTube Auto-Click'")
            Log.w(TAG, "")
        }
        
        // Also log what services are currently enabled for debugging
        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: "(none)"
            
            Log.d(TAG, "All enabled accessibility services: $enabledServices")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading enabled services", e)
        }
    }
    
    /**
     * Get user-friendly instructions for enabling the services.
     */
    fun getEnableInstructions(): String {
        return """
            To enable auto-play for Spotify/YouTube:
            
            1. Go to Settings → Accessibility
            2. Scroll to "Installed Services" or "Downloaded Services"
            3. Find and tap "Spotify Auto-Click" or "YouTube Auto-Click"
            4. Toggle it ON
            5. Confirm permission when prompted
            
            Now try: "Play Shape of You" and it will auto-play!
        """.trimIndent()
    }
}
