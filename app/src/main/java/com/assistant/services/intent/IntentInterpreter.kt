package com.assistant.services.intent

/**
 * Intent interpreter contract.
 *
 * Rule:
 * - Wake-word flow must not block on AI.
 * - This interface supports fast local interpretation and optional slower AI interpretation.
 */
interface IntentInterpreter {
    /**
     * Fast, heuristic-based interpretation (local, no AI).
     */
    fun interpretFast(text: String): IntentDecision?

    /**
     * Accurate, AI-powered interpretation.
     * @param text User input text
     * @param conversationContext Optional context from previous turns for multi-turn conversations
     */
    suspend fun interpretAccurate(
        text: String, 
        conversationContext: String? = null
    ): IntentDecision?
}
