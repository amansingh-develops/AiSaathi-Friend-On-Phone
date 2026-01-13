package com.assistant.services.context

import com.assistant.services.intent.AssistantIntent
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks the state of an ongoing multi-turn conversation.
 * Enables the assistant to remember what the user is trying to do across multiple exchanges.
 */
data class ConversationContext(
    val id: String = System.currentTimeMillis().toString(),
    val pendingAction: PendingAction? = null,
    val conversationHistory: List<Turn> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        private const val CONTEXT_TIMEOUT_MS = 60_000L // 1 minute
    }

    /**
     * Represents an action that's waiting for more information.
     */
    data class PendingAction(
        val actionType: ActionType,
        val collectedParams: MutableMap<String, String> = mutableMapOf(),
        val missingParams: List<String> = emptyList(),
        val lastQuestion: String? = null
    )

    enum class ActionType {
        CALL_CONTACT,
        PLAY_MEDIA,
        SET_ALARM,
        UPDATE_SETTING,
        UNKNOWN
    }

    /**
     * A single turn in the conversation.
     */
    data class Turn(
        val userInput: String,
        val assistantResponse: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Check if this context has expired due to inactivity.
     */
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - lastUpdatedAt > CONTEXT_TIMEOUT_MS
    }

    /**
     * Add a new turn to the conversation history.
     */
    fun addTurn(userInput: String, assistantResponse: String): ConversationContext {
        return copy(
            conversationHistory = conversationHistory + Turn(userInput, assistantResponse),
            lastUpdatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Update the pending action with new parameters.
     */
    fun updatePendingAction(
        params: Map<String, String>,
        missingParams: List<String> = emptyList(),
        lastQuestion: String? = null
    ): ConversationContext {
        val updatedAction = pendingAction?.copy(
            collectedParams = (pendingAction.collectedParams + params).toMutableMap(),
            missingParams = missingParams,
            lastQuestion = lastQuestion
        )
        return copy(
            pendingAction = updatedAction,
            lastUpdatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Clear the pending action (when completed or cancelled).
     */
    fun clearPendingAction(): ConversationContext {
        return copy(
            pendingAction = null,
            lastUpdatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Get a summary of the conversation for LLM context.
     */
    fun getSummary(): String {
        val history = conversationHistory.takeLast(3).joinToString("\n") { turn ->
            "User: ${turn.userInput}\nAssistant: ${turn.assistantResponse}"
        }
        
        val pending = pendingAction?.let { action ->
            "\nPending Action: ${action.actionType}\n" +
            "Collected: ${action.collectedParams}\n" +
            "Missing: ${action.missingParams}"
        } ?: ""

        return history + pending
    }
}
