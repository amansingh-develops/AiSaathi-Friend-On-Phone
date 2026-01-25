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
 * Updated for Spotify's modern UI (2024/2025):
 * - Uses content description matching instead of hardcoded view IDs
 * - Smarter detection of playable items (songs, tracks)
 * - Retry mechanism for reliability
 * - Cooldown to prevent duplicate clicks
 * 
 * IMPORTANT: User must enable this service in Settings > Accessibility
 */
class SpotifyAutoClickService : AccessibilityService() {
    
    companion object {
        private const val TAG = "SpotifyAutoClick"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        
        // Timing constants
        private const val INITIAL_DELAY_MS = 1500L      // Wait for search results to load
        private const val RETRY_DELAY_MS = 1000L        // Delay between retries
        private const val MAX_RETRIES = 3               // Maximum retry attempts
        private const val COOLDOWN_MS = 5000L           // Cooldown after successful click
        
        var isEnabled = false
            private set
    }
    
    // Content description patterns that indicate playable items
    private val playablePatterns = listOf(
        "song", "play", "track", "listen", "artist", "album"
    )
    
    // Section headers that indicate we're in search results
    private val searchSectionHeaders = listOf(
        "Songs", "Top result", "Artists", "Albums", "Playlists"
    )
    
    // Elements to skip (filters, navigation, etc.)
    private val skipPatterns = listOf(
        "filter", "chip", "tab", "navigation", "search", "back", "close",
        "premium", "settings", "library", "home"
    )
    
    private var clickJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastClickTime = 0L
    private var currentSearchQuery: String? = null
    private var retryCount = 0
    private var eventCount = 0
    
    // Task completion tracking - stop observing after successful click
    private var taskCompleted = false
    private var wasInSpotify = false
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  SpotifyAutoClickService CREATED                       ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  🎵 Spotify Auto-Click Service CONNECTED               ║")
        Log.i(TAG, "║  Ready to auto-play music!                             ║")
        Log.i(TAG, "╠════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║  Config:                                               ║")
        Log.i(TAG, "║  - Initial Delay: ${INITIAL_DELAY_MS}ms                          ║")
        Log.i(TAG, "║  - Max Retries: $MAX_RETRIES                                    ║")
        Log.i(TAG, "║  - Cooldown: ${COOLDOWN_MS}ms                              ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        isEnabled = true
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Wrap everything in try-catch to prevent service from being marked as "malfunctioning"
        try {
            if (event == null) return
            
            val packageName = event.packageName?.toString() ?: return
            
            // Track if user leaves Spotify (to reset task on return)
            if (packageName != SPOTIFY_PACKAGE) {
                if (wasInSpotify && taskCompleted) {
                    // User left Spotify after task completed - reset for next time
                    Log.i(TAG, "🔄 User left Spotify, resetting for next search")
                    taskCompleted = false
                }
                wasInSpotify = false
                return
            }
            
            wasInSpotify = true
            
            // If task is already completed, don't process more events (silent)
            if (taskCompleted) {
                eventCount++
                return
            }
            
            eventCount++
            
            // Check cooldown (5 seconds after click)
            val now = System.currentTimeMillis()
            val timeSinceLastClick = now - lastClickTime
            if (timeSinceLastClick < COOLDOWN_MS) {
                return
            }
            
            // Only trigger on significant events
            val eventType = event.eventType
            val isSignificantEvent = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                                     eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            
            if (!isSignificantEvent) {
                return
            }
            
            val className = event.className?.toString()?.substringAfterLast(".") ?: "Unknown"
            Log.i(TAG, "┌─────────────────────────────────────────────────────────")
            Log.i(TAG, "│ 📱 SPOTIFY EVENT #$eventCount ($className)")
            Log.i(TAG, "│ 🔍 Scheduling auto-click check in ${INITIAL_DELAY_MS}ms...")
            Log.i(TAG, "└─────────────────────────────────────────────────────────")
            scheduleAutoClick()
            
        } catch (e: Exception) {
            // Log but don't crash - prevents "malfunctioning" status
            Log.e(TAG, "Error in onAccessibilityEvent: ${e.message}", e)
        }
    }
    
    private fun getEventTypeName(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
            else -> "OTHER($eventType)"
        }
    }
    
    /**
     * Check if the UI tree contains search results by looking for actual content.
     * This is called DURING the auto-click attempt, not from the event.
     */
    private fun hasSearchResultsInTree(rootNode: AccessibilityNodeInfo): Boolean {
        // Look for "Songs" or "Top result" section which indicates search results
        for (indicator in searchSectionHeaders) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(indicator)
            if (!nodes.isNullOrEmpty()) {
                Log.d(TAG, "│ ✓ Found search indicator in UI: '$indicator'")
                nodes.forEach { it.recycle() }
                return true
            }
        }
        
        // Also check for RecyclerView with content (might be search results)
        val recyclerView = findNodeByClassName(rootNode, "RecyclerView")
        if (recyclerView != null && recyclerView.childCount > 2) {
            Log.d(TAG, "│ ✓ Found RecyclerView with ${recyclerView.childCount} children")
            recyclerView.recycle()
            return true
        }
        
        return false
    }
    
    private fun scheduleAutoClick() {
        // Cancel any pending click
        clickJob?.cancel()
        retryCount = 0
        
        Log.i(TAG, "┌─────────────────────────────────────────────────────────")
        Log.i(TAG, "│ ⏰ AUTO-CLICK SCHEDULED")
        Log.i(TAG, "│ Waiting ${INITIAL_DELAY_MS}ms for results to load...")
        Log.i(TAG, "└─────────────────────────────────────────────────────────")
        
        // Schedule new click after delay
        clickJob = scope.launch {
            delay(INITIAL_DELAY_MS)
            performAutoClickWithRetry()
        }
    }
    
    /**
     * Attempts to auto-click with retry mechanism.
     */
    private suspend fun performAutoClickWithRetry() {
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  🎯 STARTING AUTO-CLICK SEQUENCE                       ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        while (retryCount < MAX_RETRIES) {
            Log.i(TAG, "┌─────────────────────────────────────────────────────────")
            Log.i(TAG, "│ 🎯 ATTEMPT ${retryCount + 1} OF $MAX_RETRIES")
            Log.i(TAG, "└─────────────────────────────────────────────────────────")
            
            if (performAutoClick()) {
                Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
                Log.i(TAG, "║  ✅ AUTO-CLICK SUCCESSFUL!                             ║")
                Log.i(TAG, "║  Music should be playing now 🎵                        ║")
                Log.i(TAG, "║  Service now dormant until next search                 ║")
                Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
                lastClickTime = System.currentTimeMillis()
                taskCompleted = true  // Stop observing until user leaves and returns
                return
            }
            
            retryCount++
            if (retryCount < MAX_RETRIES) {
                Log.i(TAG, "│ ⏳ Attempt failed, retrying in ${RETRY_DELAY_MS}ms...")
                delay(RETRY_DELAY_MS)
            }
        }
        
        Log.w(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.w(TAG, "║  ❌ AUTO-CLICK FAILED                                  ║")
        Log.w(TAG, "║  All $MAX_RETRIES attempts exhausted                            ║")
        Log.w(TAG, "║  User may need to tap manually                         ║")
        Log.w(TAG, "╚════════════════════════════════════════════════════════╝")
    }
    
    /**
     * Main auto-click logic with improved detection.
     * Returns true if click was successful.
     */
    private fun performAutoClick(): Boolean {
        try {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.w(TAG, "│ ⚠️ No root node available - cannot access window")
                return false
            }
            
            Log.i(TAG, "│ 🔍 Analyzing UI tree...")
            Log.i(TAG, "│ Root: ${rootNode.className} with ${rootNode.childCount} children")
            
            // Try multiple strategies in order of preference
            Log.i(TAG, "│")
            Log.i(TAG, "│ 📋 STRATEGY 1: Looking for 'Songs' section header...")
            var clickTarget = findPlayableItemStrategy1(rootNode)
            
            if (clickTarget == null) {
                Log.i(TAG, "│    ↳ Strategy 1 failed")
                Log.i(TAG, "│")
                Log.i(TAG, "│ 📋 STRATEGY 2: Looking by content description...")
                clickTarget = findPlayableItemStrategy2(rootNode)
            }
            
            if (clickTarget == null) {
                Log.i(TAG, "│    ↳ Strategy 2 failed")
                Log.i(TAG, "│")
                Log.i(TAG, "│ 📋 STRATEGY 3: Looking for RecyclerView children...")
                clickTarget = findPlayableItemStrategy3(rootNode)
            }
            
            if (clickTarget != null) {
                val bounds = Rect()
                clickTarget.getBoundsInScreen(bounds)
                val text = clickTarget.text?.toString()?.take(40) ?: ""
                val desc = clickTarget.contentDescription?.toString()?.take(40) ?: ""
                val displayText = text.ifEmpty { desc }.ifEmpty { "unknown" }
                
                Log.i(TAG, "│")
                Log.i(TAG, "│ ╔══════════════════════════════════════════════════╗")
                Log.i(TAG, "│ ║ 🎯 FOUND TARGET!                                 ║")
                Log.i(TAG, "│ ╠══════════════════════════════════════════════════╣")
                Log.i(TAG, "│ ║ Text: '$displayText'")
                Log.i(TAG, "│ ║ Class: ${clickTarget.className?.toString()?.substringAfterLast(".")}")
                Log.i(TAG, "│ ║ Bounds: $bounds")
                Log.i(TAG, "│ ║ Clickable: ${clickTarget.isClickable}")
                Log.i(TAG, "│ ╚══════════════════════════════════════════════════╝")
                
                Log.i(TAG, "│")
                Log.i(TAG, "│ 👆 Performing click action...")
                
                val clicked = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clickTarget.recycle()
                rootNode.recycle()
                
                if (clicked) {
                    Log.i(TAG, "│ ✅ CLICK ACTION RETURNED TRUE")
                    return true
                } else {
                    Log.w(TAG, "│ ❌ performAction(ACTION_CLICK) returned FALSE")
                    Log.w(TAG, "│    The element may not be truly clickable")
                }
            } else {
                Log.w(TAG, "│")
                Log.w(TAG, "│ ⚠️ NO PLAYABLE ITEM FOUND")
                Log.w(TAG, "│ Dumping UI tree for debugging...")
                Log.w(TAG, "│")
                logNodeTree(rootNode, 0)
            }
            
            rootNode.recycle()
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "│ ❌ EXCEPTION during auto-click: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Strategy 1: Find "Songs" section header, then get the first item after it.
     * This is the most reliable for Spotify's current UI.
     */
    private fun findPlayableItemStrategy1(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Look for "Songs" header
        val songsNodes = rootNode.findAccessibilityNodeInfosByText("Songs")
        if (songsNodes.isNullOrEmpty()) {
            Log.d(TAG, "│    No 'Songs' text found in window")
            return null
        }
        
        Log.d(TAG, "│    Found ${songsNodes.size} nodes with 'Songs' text")
        
        // Find the parent that contains both the header and the songs list
        for ((index, songsNode) in songsNodes.withIndex()) {
            Log.d(TAG, "│    Checking Songs node #${index + 1}: ${songsNode.className}")
            
            val parent = songsNode.parent
            if (parent != null) {
                Log.d(TAG, "│      Parent: ${parent.className} with ${parent.childCount} children")
                
                // Look for clickable children that are NOT the header itself
                val clickable = findFirstClickableChild(parent, songsNode)
                if (clickable != null) {
                    Log.d(TAG, "│    ✓ Found clickable child after Songs header!")
                    parent.recycle()
                    songsNode.recycle()
                    return clickable
                }
                parent.recycle()
            }
            songsNode.recycle()
        }
        
        return null
    }
    
    /**
     * Strategy 2: Find elements with play-related content descriptions.
     */
    private fun findPlayableItemStrategy2(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (pattern in playablePatterns) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(pattern)
            if (!nodes.isNullOrEmpty()) {
                Log.d(TAG, "│    Found ${nodes.size} nodes with pattern '$pattern'")
                
                for (node in nodes) {
                    // Skip if it's a section header or filter
                    val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
                    
                    if (shouldSkipElement(text)) {
                        Log.v(TAG, "│      Skipping (matches skip pattern): '$text'")
                        node.recycle()
                        continue
                    }
                    
                    // Find clickable parent or self
                    if (node.isClickable) {
                        Log.d(TAG, "│    ✓ Found clickable element with pattern '$pattern'")
                        return node
                    }
                    
                    val clickable = findClickableParent(node)
                    if (clickable != null) {
                        Log.d(TAG, "│    ✓ Found clickable parent for pattern '$pattern'")
                        node.recycle()
                        return clickable
                    }
                    node.recycle()
                }
            }
        }
        
        return null
    }
    
    /**
     * Strategy 3: Find RecyclerView and click first valid child.
     * Fallback when other strategies fail.
     */
    private fun findPlayableItemStrategy3(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val recyclerView = findNodeByClassName(rootNode, "RecyclerView")
        if (recyclerView == null) {
            Log.d(TAG, "│    No RecyclerView found in window")
            return null
        }
        
        Log.d(TAG, "│    Found RecyclerView with ${recyclerView.childCount} children")
        
        // Skip first 1-2 items (often headers/filters) and try to find a song item
        val startIndex = minOf(1, recyclerView.childCount - 1)
        Log.d(TAG, "│    Checking children starting from index $startIndex")
        
        for (i in startIndex until minOf(startIndex + 5, recyclerView.childCount)) {
            val child = recyclerView.getChild(i) ?: continue
            
            val childClass = child.className?.toString()?.substringAfterLast(".") ?: "?"
            val childText = child.text?.toString()?.take(30) ?: ""
            val childDesc = child.contentDescription?.toString()?.take(30) ?: ""
            
            Log.d(TAG, "│      Child[$i]: $childClass, text='$childText', desc='$childDesc'")
            
            // Check if this looks like a playable item
            if (isLikelyPlayableItem(child)) {
                Log.d(TAG, "│        ↳ Looks playable! Checking for clickable...")
                
                val clickable = if (child.isClickable) child else findClickableInTree(child)
                if (clickable != null) {
                    Log.d(TAG, "│    ✓ Found clickable element at index $i")
                    recyclerView.recycle()
                    return clickable
                }
            } else {
                Log.v(TAG, "│        ↳ Not a likely playable item")
            }
            child.recycle()
        }
        
        recyclerView.recycle()
        return null
    }
    
    /**
     * Check if an element looks like a playable song item.
     */
    private fun isLikelyPlayableItem(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val combined = "$text $contentDesc"
        
        // Skip filter chips, navigation, etc.
        if (shouldSkipElement(combined)) {
            return false
        }
        
        // A song item typically has:
        // 1. Some text content (song title)
        // 2. Is clickable or has clickable parent
        // 3. Has children (album art, title, artist subtexts)
        val hasContent = text.isNotEmpty() || contentDesc.isNotEmpty() || node.childCount > 0
        val isInteractive = node.isClickable || node.isFocusable
        
        return hasContent && isInteractive
    }
    
    /**
     * Check if element should be skipped (filters, navigation, etc.)
     */
    private fun shouldSkipElement(text: String): Boolean {
        return skipPatterns.any { pattern -> text.contains(pattern) }
    }
    
    /**
     * Find the first clickable child of a node, excluding a specific node.
     */
    private fun findFirstClickableChild(parent: AccessibilityNodeInfo, exclude: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            
            // Skip the excluded node (e.g., the header)
            if (exclude != null && child == exclude) {
                child.recycle()
                continue
            }
            
            // Skip if it looks like a header or filter
            val text = (child.text?.toString() ?: child.contentDescription?.toString() ?: "").lowercase()
            if (shouldSkipElement(text) || text in searchSectionHeaders.map { it.lowercase() }) {
                child.recycle()
                continue
            }
            
            // Check if this child is clickable
            if (child.isClickable) {
                return child
            }
            
            // Check children recursively
            val clickableInChild = findClickableInTree(child)
            if (clickableInChild != null) {
                child.recycle()
                return clickableInChild
            }
            
            child.recycle()
        }
        return null
    }
    
    /**
     * Find clickable parent of a node.
     */
    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                return current
            }
            val next = current.parent
            current.recycle()
            current = next
            depth++
        }
        current?.recycle()
        return null
    }
    
    /**
     * Find any clickable element within a subtree.
     */
    private fun findClickableInTree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableInTree(child)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
    
    /**
     * Find node by class name (partial match).
     */
    private fun findNodeByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className?.contains(className) == true) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByClassName(child, className)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
    
    /**
     * Debug: Log node tree structure with visual hierarchy.
     */
    private fun logNodeTree(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 4) return  // Limit depth
        
        val prefix = when (depth) {
            0 -> "│ 🌳 "
            else -> "│ " + "   ".repeat(depth - 1) + "├── "
        }
        
        val className = node.className?.toString()?.substringAfterLast(".") ?: "?"
        val text = node.text?.toString()?.take(25) ?: ""
        val desc = node.contentDescription?.toString()?.take(25) ?: ""
        val clickable = if (node.isClickable) " [CLICKABLE]" else ""
        val focusable = if (node.isFocusable) " [FOCUSABLE]" else ""
        
        val displayContent = when {
            text.isNotEmpty() -> "\"$text\""
            desc.isNotEmpty() -> "desc=\"$desc\""
            else -> ""
        }
        
        Log.d(TAG, "$prefix$className$clickable$focusable $displayContent")
        
        for (i in 0 until minOf(node.childCount, 8)) {
            val child = node.getChild(i)
            if (child != null) {
                logNodeTree(child, depth + 1)
                child.recycle()
            }
        }
        
        if (node.childCount > 8) {
            Log.d(TAG, "│ " + "   ".repeat(depth) + "└── ... and ${node.childCount - 8} more children")
        }
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Service INTERRUPTED")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isEnabled = false
        clickJob?.cancel()
        scope.cancel()
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  Spotify Auto-Click Service DESTROYED                  ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
    }
}
