package com.assistant.services.history

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages persistent conversation history for context-aware interactions.
 * Stores all user inputs and AI responses to provide rich context to the LLM.
 */
class ConversationHistoryManager(private val context: Context) {

    companion object {
        private const val TAG = "ConversationHistory"
        private const val HISTORY_FILE = "conversation_history.json"
        private const val MAX_HISTORY_ENTRIES = 100 // Keep last 100 exchanges
        private const val CONTEXT_WINDOW_MINUTES = 30 // Use last 30 minutes for context
    }

    data class HistoryEntry(
        val timestamp: Long,
        val sessionId: String,  // NEW: Track which session this belongs to
        val userInput: String,
        val aiResponse: String,
        val actionType: String? = null,
        val actionResult: String? = null
    )

    private val historyFile: File
        get() = File(context.filesDir, HISTORY_FILE)

    /**
     * Add a new conversation entry to history.
     * Automatically cleans up entries older than 24 hours to save storage.
     */
    suspend fun addEntry(
        sessionId: String,  // NEW: Session ID to group related conversations
        userInput: String,
        aiResponse: String,
        actionType: String? = null,
        actionResult: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val history = loadHistory().toMutableList()
            
            // Add new entry
            history.add(
                HistoryEntry(
                    timestamp = System.currentTimeMillis(),
                    sessionId = sessionId,  // NEW: Store session ID
                    userInput = userInput,
                    aiResponse = aiResponse,
                    actionType = actionType,
                    actionResult = actionResult
                )
            )

            // Auto-cleanup: Remove entries older than 24 hours
            val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24 hours
            val recentHistory = history.filter { it.timestamp > cutoffTime }

            // Also enforce max entries limit
            val trimmedHistory = if (recentHistory.size > MAX_HISTORY_ENTRIES) {
                recentHistory.takeLast(MAX_HISTORY_ENTRIES)
            } else {
                recentHistory
            }

            saveHistory(trimmedHistory)
            Log.d(TAG, "Added history entry. Total: ${trimmedHistory.size}, Cleaned: ${history.size - trimmedHistory.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding history entry", e)
        }
    }

    /**
     * Get recent conversation history for LLM context.
     * @deprecated Use getSessionContext instead for session-based filtering
     */
    suspend fun getRecentContext(maxEntries: Int = 10): String = withContext(Dispatchers.IO) {
        try {
            val history = loadHistory()
            val cutoffTime = System.currentTimeMillis() - (CONTEXT_WINDOW_MINUTES * 60 * 1000)
            
            val recentHistory = history
                .filter { it.timestamp > cutoffTime }
                .takeLast(maxEntries)

            if (recentHistory.isEmpty()) {
                return@withContext ""
            }

            buildString {
                appendLine("RECENT CONVERSATION HISTORY:")
                recentHistory.forEach { entry ->
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(entry.timestamp))
                    appendLine("[$time] User: ${entry.userInput}")
                    appendLine("[$time] Assistant: ${entry.aiResponse}")
                    if (entry.actionType != null) {
                        appendLine("         Action: ${entry.actionType} - ${entry.actionResult ?: "executed"}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recent context", e)
            ""
        }
    }
    
    /**
     * Get conversation history for the CURRENT SESSION ONLY.
     * This prevents context confusion from previous sessions.
     */
    suspend fun getSessionContext(sessionId: String, maxEntries: Int = 20): String = withContext(Dispatchers.IO) {
        try {
            val history = loadHistory()
            
            // Filter by session ID ONLY - ignore time window
            val sessionHistory = history
                .filter { it.sessionId == sessionId }
                .takeLast(maxEntries)

            if (sessionHistory.isEmpty()) {
                return@withContext ""
            }

            buildString {
                appendLine("CURRENT SESSION HISTORY:")
                sessionHistory.forEach { entry ->
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(entry.timestamp))
                    appendLine("[$time] User: ${entry.userInput}")
                    appendLine("[$time] Assistant: ${entry.aiResponse}")
                    if (entry.actionType != null) {
                        appendLine("         Action: ${entry.actionType} - ${entry.actionResult ?: "executed"}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting session context", e)
            ""
        }
    }

    /**
     * Get full conversation history.
     */
    suspend fun getAllHistory(): List<HistoryEntry> = withContext(Dispatchers.IO) {
        loadHistory()
    }

    /**
     * Clear all history.
     */
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        try {
            historyFile.delete()
            Log.d(TAG, "History cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing history", e)
        }
    }

    /**
     * Load history from file.
     */
    private fun loadHistory(): List<HistoryEntry> {
        if (!historyFile.exists()) {
            return emptyList()
        }

        return try {
            val jsonString = historyFile.readText()
            val jsonArray = JSONArray(jsonString)
            
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                HistoryEntry(
                    timestamp = obj.getLong("timestamp"),
                    sessionId = obj.optString("sessionId", "unknown"),  // NEW: Load session ID (default for old entries)
                    userInput = obj.getString("userInput"),
                    aiResponse = obj.getString("aiResponse"),
                    actionType = obj.optString("actionType").takeIf { it.isNotBlank() },
                    actionResult = obj.optString("actionResult").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading history", e)
            emptyList()
        }
    }

    /**
     * Save history to file.
     */
    private fun saveHistory(history: List<HistoryEntry>) {
        try {
            val jsonArray = JSONArray()
            history.forEach { entry ->
                val obj = JSONObject().apply {
                    put("timestamp", entry.timestamp)
                    put("sessionId", entry.sessionId)  // NEW: Save session ID
                    put("userInput", entry.userInput)
                    put("aiResponse", entry.aiResponse)
                    entry.actionType?.let { put("actionType", it) }
                    entry.actionResult?.let { put("actionResult", it) }
                }
                jsonArray.put(obj)
            }

            historyFile.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving history", e)
        }
    }
}
