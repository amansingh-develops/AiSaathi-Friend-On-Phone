package com.assistant.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

/**
 * Accessibility service to auto-click first Spotify search result.
 * 
 * IMPORTANT: User must enable this service in Settings > Accessibility
 */
class SpotifyAutoClickService : AccessibilityService() {
    
    companion object {
        private const val TAG = "SpotifyAutoClick"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val CLICK_DELAY_MS = 1500L // Wait for results to load
        
        var isEnabled = false
            private set
    }
    
    private var clickJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SpotifyAutoClickService CREATED")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        Log.i(TAG, "========================================")
        Log.i(TAG, "Spotify auto-click service CONNECTED")
        Log.i(TAG, "========================================")
        
        // We rely on spotify_autoclick_service_config.xml for configuration.
        // Overriding it here with empty or partial info can cause issues.
        
        isEnabled = true
        Log.i(TAG, "Service enabled: $isEnabled")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        // Log EVERY event to see what we're getting
        Log.d(TAG, "Event received: pkg=${event.packageName}, type=${event.eventType}, class=${event.className}")
        
        // Only process Spotify events
        if (event.packageName != SPOTIFY_PACKAGE) {
            Log.v(TAG, "Ignoring non-Spotify event: ${event.packageName}")
            return
        }
        
        Log.i(TAG, "📱 Spotify event detected!")
        Log.i(TAG, "  - Type: ${event.eventType}")
        Log.i(TAG, "  - Class: ${event.className}")
        Log.i(TAG, "  - Text: ${event.text}")
        Log.i(TAG, "  - ContentDesc: ${event.contentDescription}")
        
        // Simplified detection: just trigger on ANY Spotify window change
        // We'll let the click logic handle finding the right element
        Log.i(TAG, "🔍 Triggering auto-click for Spotify event")
        scheduleAutoClick()
    }
    
    private fun isSpotifySearchResults(event: AccessibilityEvent): Boolean {
        // Look for search-related text or UI elements
        val text = event.text?.toString()?.lowercase() ?: ""
        val contentDescription = event.contentDescription?.toString()?.lowercase() ?: ""
        
        val isSearch = text.contains("song") || 
               text.contains("search") ||
               contentDescription.contains("search") ||
               event.className?.contains("search", ignoreCase = true) == true
        
        Log.d(TAG, "Is search results? $isSearch (text=$text, desc=$contentDescription)")
        return isSearch
    }
    
    private fun scheduleAutoClick() {
        // Cancel any pending click
        clickJob?.cancel()
        
        // Schedule new click after delay
        clickJob = scope.launch {
            delay(CLICK_DELAY_MS)
            performAutoClick()
        }
    }
    
    private fun performAutoClick() {
        try {
            val rootNode = rootInActiveWindow ?: run {
                Log.w(TAG, "No root node available")
                return
            }
            
            Log.i(TAG, "🔍 Starting generic auto-click search...")
            
            // New Strategy: Find the main list (RecyclerView) and click its first item
            val listNode = findRecyclerView(rootNode)
            
            if (listNode != null) {
                Log.i(TAG, "✅ Found RecyclerView! Child count: ${listNode.childCount}")
                
                if (listNode.childCount > 0) {
                    // Try to click the first child (first search result)
                    val firstItem = listNode.getChild(0)
                    if (firstItem != null) {
                        Log.i(TAG, "Inspect first item: clickable=${firstItem.isClickable}, class=${firstItem.className}")
                        
                        // If item itself is clickable, click it. Otherwise find clickable child.
                        val clickableTarget = if (firstItem.isClickable) firstItem else findClickableChild(firstItem)
                        
                        if (clickableTarget != null) {
                             val bounds = Rect()
                            clickableTarget.getBoundsInScreen(bounds)
                            Log.i(TAG, "🎯 Clicking target at $bounds")
                            
                            val clicked = clickableTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (clicked) Log.i(TAG, "✅ CLICK SUCCESSFUL!") else Log.w(TAG, "❌ Click failed")
                            
                            clickableTarget.recycle()
                        } else {
                            Log.w(TAG, "First item has no clickable parts")
                        }
                        
                        firstItem.recycle()
                    }
                } else {
                    Log.w(TAG, "RecyclerView is empty - results might be loading")
                }
                listNode.recycle()
            } else {
                Log.w(TAG, "Could not find RecyclerView in current window")
                // Fallback: Dump tree to see what's there
                logNodeTree(rootNode, 0)
            }
            
            rootNode.recycle()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error performing auto-click", e)
        }
    }
    
    private fun findRecyclerView(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.contains("RecyclerView") == true) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findRecyclerView(child)
            if (found != null) {
                // If we found it in a child, we need to return that child (and NOT recycle it)
                // But we must recycle the intermediate child wrapper if it's not the one
                 if (found != child) {
                     child.recycle()
                 }
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun findClickableChild(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableChild(child)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
    
    private fun logNodeTree(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 4) return 
        
        val sb = StringBuilder()
        repeat(depth) { sb.append("  ") }
        sb.append(node.className)
        if (node.isClickable) sb.append(" [CLICK]")
        if (!node.text.isNullOrEmpty()) sb.append(" '${node.text}'")
        if (!node.contentDescription.isNullOrEmpty()) sb.append(" Desc='${node.contentDescription}'")
        
        Log.i(TAG, sb.toString()) // Changed to Info level
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                logNodeTree(child, depth + 1)
                child.recycle()
            }
        }
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isEnabled = false
        clickJob?.cancel()
        scope.cancel()
        Log.i(TAG, "Spotify auto-click service destroyed")
    }
}
