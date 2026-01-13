package com.assistant.services.router

import com.assistant.services.gemini.GeminiClient
import com.assistant.services.intent.IntentDecision
import com.assistant.services.intent.AssistantIntent
import org.json.JSONObject
import android.util.Log

/**
 * High-level router that uses Gemini to classify user intent into:
 * 1. COMMAND (Direct execution)
 * 2. CONVERSATION (ElevenLabs Agent)
 * 3. CONVERSATION_WITH_ACTION (Agent + potential action)
 */
class GeminiRouter(
    private val geminiClient: GeminiClient
) {
    companion object {
        private const val TAG = "GeminiRouter"
    }

    sealed class Route {
        data class Command(val decision: IntentDecision) : Route()
        object Conversation : Route()
        data class ConversationWithAction(val suggestion: String) : Route()
    }

    /**
     * Asks Gemini to classify the text.
     *
     * Optimization:
     * - Uses a compact prompt to reduce input tokens.
     * - Asks for specific JSON format to parse easily.
     */
    suspend fun route(text: String, contextActive: Boolean): Route {
        // If we are already in a context (e.g. follow up to "Do you want music?"), 
        // we might prioritize Command classification.
        
        val prompt = if (contextActive) {
            """
            You are a router. The user is replying to a previous suggestion.
            Classify prompt: "$text"
            
            Output JSON:
            { "type": "COMMAND" | "CONVERSATION" | "NEGATION" }
            
            If type is COMMAND, also extract basic intent if possible.
            If type is NEGATION (user said no/cancel), map to COMMAND (Stop/Cancel).
            """
        } else {
            """
            You are a voice assistant router. Classify user input: "$text"
            
            CRITICAL:
            - Input may be in Hindi. TRANSLATE INTERNALLY to English first.
            - If the translated intent is a specific action (Play, Call, Alarm), it is a COMMAND.
            - Example: "Gaan baja do" -> "Play music" -> COMMAND.
            - Example: "Papa ko phone lagao" -> "Call Dad" -> COMMAND.
            - Example: "Tum kaise ho" -> "How are you" -> CONVERSATION.
            
            Rules:
            1. COMMAND: User wants specific action.
            2. CONVERSATION: Casual chat, emotional sharing.
            
            Output strictly JSON:
            { "type": "COMMAND" | "CONVERSATION" }
            """
        }
        
        val systemInstruction = "You are a low-latency router. Output only valid JSON."
        val response = geminiClient.generateReply(systemInstruction, prompt) ?: return Route.Conversation


        return try {
            val json = JSONObject(cleanJson(response))
            val type = json.optString("type", "CONVERSATION")
            
            when (type) {
                "COMMAND" -> {
                    // In a real implementation, we would extract the full intent here.
                    // For now, we signal that it IS a command, and let the IntentInterpreter handle the specifics.
                    Route.Command(IntentDecision(AssistantIntent.Unknown, 1.0f)) 
                }
                "NEGATION" -> {
                     Route.Command(IntentDecision(AssistantIntent.Action.StopListeningSession(), 1.0f))
                }
                else -> Route.Conversation
            }
        } catch (e: Exception) {
            Log.e(TAG, "Router parse error: $response", e)
            Route.Conversation // Default to conversation on error
        }
    }
    
    // Helper to strip markdown code blocks if any
    private fun cleanJson(text: String): String {
        return text.replace("```json", "").replace("```", "").trim()
    }
}
