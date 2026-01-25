package com.assistant.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.assistant.services.youtube.YouTubeSelectionContext
import kotlinx.coroutines.*

/**
 * Simple YouTube auto-click service.
 * 
 * When YouTube opens with a pending selection, waits for page to load
 * then clicks at fixed screen coordinates to play the first video.
 * 
 * IMPORTANT: User must enable this service in Settings > Accessibility
 */
class YouTubeAutoClickService : AccessibilityService() {
    
    companion object {
        private const val TAG = "YouTubeAutoClick"
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        
        // Timing
        private const val CLICK_DELAY_MS = 3500L        // Wait for search results to load
        private const val COOLDOWN_MS = 10000L          // Cooldown after click
        
        var isEnabled = false
            private set
        
        /** Pending selection context set by ActionExecutor */
        @Volatile
        var pendingSelection: YouTubeSelectionContext? = null
    }
    
    private var clickJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastClickTime = 0L
    
    // State tracking
    private var taskCompleted = false
    private var hasScheduledClick = false
    private var eventCount = 0
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  YouTubeAutoClickService CREATED                       ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  🎬 YouTube Auto-Click Service CONNECTED               ║")
        Log.i(TAG, "║  Click delay: ${CLICK_DELAY_MS}ms                                ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        isEnabled = true
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            
            val packageName = event.packageName?.toString() ?: return
            
            // Only care about YouTube
            if (packageName != YOUTUBE_MUSIC_PACKAGE && packageName != YOUTUBE_PACKAGE) {
                // User left YouTube - reset for next time
                if (taskCompleted) {
                    Log.i(TAG, "🔄 User left YouTube, resetting state")
                    resetState()
                }
                return
            }
            
            eventCount++
            
            // Log first few events for debugging
            if (eventCount <= 3) {
                Log.d(TAG, "📱 YouTube event #$eventCount: type=${event.eventType}, pending=${pendingSelection != null}")
            }
            
            // If task completed, ignore
            if (taskCompleted) {
                return
            }
            
            // Need pending selection
            val selection = pendingSelection
            if (selection == null) {
                if (eventCount <= 2) {
                    Log.d(TAG, "⏸️ No pending selection, ignoring")
                }
                return
            }
            
            // Check cooldown
            val now = System.currentTimeMillis()
            if (now - lastClickTime < COOLDOWN_MS) {
                return
            }
            
            // Already scheduled?
            if (hasScheduledClick) {
                return
            }
            
            // Only react to WINDOW_STATE_CHANGED (app/activity opened)
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                return
            }
            
            Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
            Log.i(TAG, "║  🎯 YOUTUBE OPENED - Scheduling click                  ║")
            Log.i(TAG, "║  Query: ${selection.searchQuery.take(40)}")
            Log.i(TAG, "║  Click in ${CLICK_DELAY_MS}ms...                                ║")
            Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
            
            hasScheduledClick = true
            scheduleClick()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent: ${e.message}", e)
        }
    }
    
    private fun scheduleClick() {
        clickJob?.cancel()
        
        clickJob = scope.launch {
            delay(CLICK_DELAY_MS)
            performClick()
        }
    }
    
    /**
     * Perform click at fixed coordinates.
     * 
     * YouTube search results layout:
     * - Top ~10%: Search bar
     * - ~10-25%: Filters/chips  
     * - ~25-55%: First video result
     * 
     * Click at 45% Y, 50% X to hit first video.
     */
    private fun performClick() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()
        
        // Click coordinates - first video result area
        val clickX = screenWidth * 0.4f    // Center horizontally
        val clickY = screenHeight * 0.45f  // 45% from top
        
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  👆 CLICKING FIRST VIDEO                               ║")
        Log.i(TAG, "║  Screen: ${screenWidth.toInt()} x ${screenHeight.toInt()}")
        Log.i(TAG, "║  Click at: (${clickX.toInt()}, ${clickY.toInt()})")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        val success = clickAtCoordinates(clickX, clickY)
        
        if (success) {
            Log.i(TAG, "✅ Click gesture dispatched!")
            lastClickTime = System.currentTimeMillis()
            taskCompleted = true
            pendingSelection = null
            hasScheduledClick = false
        } else {
            Log.w(TAG, "❌ Click dispatch failed")
            hasScheduledClick = false
        }
    }
    
    /**
     * Perform gesture click at specific coordinates.
     */
    private fun clickAtCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "✅ Gesture COMPLETED at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "❌ Gesture CANCELLED at ($x, $y)")
            }
        }, null)
    }
    
    private fun resetState() {
        taskCompleted = false
        pendingSelection = null
        hasScheduledClick = false
        eventCount = 0
        clickJob?.cancel()
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Service INTERRUPTED")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isEnabled = false
        resetState()
        scope.cancel()
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  YouTube Auto-Click Service DESTROYED                  ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
    }
}
