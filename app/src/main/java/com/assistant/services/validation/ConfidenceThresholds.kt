package com.assistant.services.validation

import com.assistant.services.intent.AssistantIntent

/**
 * Defines confidence thresholds for different action types.
 * 
 * Ensures sensitive actions (SMS, Calls, Calendar) require high confidence
 * before execution, preventing accidental actions from misunderstood commands.
 */
object ConfidenceThresholds {
    
    /**
     * Confidence levels for action execution.
     */
    enum class ConfidenceLevel {
        HIGH,    // >= 0.8
        MEDIUM,  // >= 0.6
        LOW      // < 0.6
    }
    
    /**
     * Minimum confidence thresholds per action type.
     */
    private val thresholds = mapOf(
        // Sensitive actions - require HIGH confidence
        "CallContact" to 0.8f,
        "SendSms" to 0.8f,
        "AddCalendarEvent" to 0.8f,
        
        // Medium sensitivity - require MEDIUM confidence
        "PlayMedia" to 0.6f,
        "SetAlarm" to 0.6f,
        "UpdateSetting" to 0.6f,
        
        // Low sensitivity - require LOW confidence
        "OpenSettings" to 0.4f,
        "StopListeningSession" to 0.3f
    )
    
    /**
     * Get the minimum confidence required for an action.
     */
    fun getMinimumConfidence(action: AssistantIntent.Action): Float {
        val actionName = action::class.simpleName ?: "Unknown"
        return thresholds[actionName] ?: 0.6f // Default to MEDIUM
    }
    
    /**
     * Check if confidence meets the threshold for an action.
     */
    fun meetsThreshold(action: AssistantIntent.Action, confidence: Float): Boolean {
        return confidence >= getMinimumConfidence(action)
    }
    
    /**
     * Get the confidence level for a given score.
     */
    fun getConfidenceLevel(confidence: Float): ConfidenceLevel {
        return when {
            confidence >= 0.8f -> ConfidenceLevel.HIGH
            confidence >= 0.6f -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }
    
    /**
     * Check if an action is sensitive (requires high confidence).
     */
    fun isSensitiveAction(action: AssistantIntent.Action): Boolean {
        return getMinimumConfidence(action) >= 0.8f
    }
    
    /**
     * Generate clarification message for low confidence.
     */
    fun getClarificationMessage(action: AssistantIntent.Action, confidence: Float): String {
        val actionName = when (action) {
            is AssistantIntent.Action.CallContact -> "call ${action.contactName}"
            is AssistantIntent.Action.PlayMedia -> "play ${action.query}"
            is AssistantIntent.Action.SetAlarm -> "set alarm for ${action.timeText}"
            else -> "do that"
        }
        
        return when {
            confidence < 0.4f -> "I'm not sure what you want. Could you please repeat?"
            confidence < 0.6f -> "Did you want me to $actionName?"
            else -> "Just to confirm, you want me to $actionName?"
        }
    }
}
