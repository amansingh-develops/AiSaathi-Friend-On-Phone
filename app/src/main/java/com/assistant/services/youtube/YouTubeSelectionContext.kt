package com.assistant.services.youtube

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Context for YouTube video selection with filter criteria.
 * Set by ActionExecutor before launching YouTube, consumed by YouTubeAutoClickService.
 */
data class YouTubeSelectionContext(
    val searchQuery: String,
    val filter: VideoFilter = VideoFilter.PLAY_ANY,
    val creatorName: String? = null  // For SPECIFIC_CREATOR filter
)

/**
 * Filter types for video selection.
 */
enum class VideoFilter {
    PLAY_ANY,           // Just play first result (default)
    MOST_VIEWS,         // Select video with highest view count
    SPECIFIC_CREATOR    // Match by channel name
}

/**
 * Parsed video item from YouTube search results.
 * Contains metadata extracted from accessibility nodes.
 */
data class YouTubeVideoItem(
    val title: String,
    val channelName: String,
    val viewCount: Long,          // Parsed from "1.2M views" -> 1200000
    val viewCountRaw: String,     // Original text like "1.2M views"
    val duration: String?,        // "10:23" (if available)
    val bounds: Rect,
    private val nodeRef: AccessibilityNodeInfo?
) {
    /**
     * Get the clickable node for this video item.
     * Caller is responsible for recycling.
     */
    fun getClickableNode(): AccessibilityNodeInfo? = nodeRef
    
    /**
     * Format for logging.
     */
    override fun toString(): String {
        return "\"${title.take(40)}...\" | $channelName | $viewCountRaw"
    }
}

/**
 * Utility to parse view count strings to numbers.
 */
object ViewCountParser {
    
    /**
     * Parse view count text to a Long value.
     * 
     * Examples:
     * - "1.2M views" -> 1200000
     * - "500K views" -> 500000
     * - "1,234,567 views" -> 1234567
     * - "45 lakh views" -> 4500000
     * - "2 crore views" -> 20000000
     */
    fun parse(text: String): Long {
        val cleaned = text.lowercase()
            .replace(",", "")
            .replace("views", "")
            .replace("view", "")
            .replace("दृश्य", "")  // Hindi for "views"
            .trim()
        
        return when {
            // Handle Indian number format
            cleaned.contains("crore") || cleaned.contains("cr") -> {
                val num = cleaned.replace("crore", "").replace("cr", "").trim()
                ((num.toDoubleOrNull() ?: 0.0) * 10_000_000).toLong()
            }
            cleaned.contains("lakh") || cleaned.contains("l") && cleaned.length <= 5 -> {
                val num = cleaned.replace("lakh", "").replace("l", "").trim()
                ((num.toDoubleOrNull() ?: 0.0) * 100_000).toLong()
            }
            // International format
            cleaned.endsWith("b") -> {
                val num = cleaned.dropLast(1).trim()
                ((num.toDoubleOrNull() ?: 0.0) * 1_000_000_000).toLong()
            }
            cleaned.endsWith("m") -> {
                val num = cleaned.dropLast(1).trim()
                ((num.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong()
            }
            cleaned.endsWith("k") -> {
                val num = cleaned.dropLast(1).trim()
                ((num.toDoubleOrNull() ?: 0.0) * 1_000).toLong()
            }
            else -> cleaned.filter { it.isDigit() }.toLongOrNull() ?: 0L
        }
    }
}
