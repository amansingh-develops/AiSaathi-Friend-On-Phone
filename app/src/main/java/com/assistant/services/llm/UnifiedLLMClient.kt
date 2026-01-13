package com.assistant.services.llm

import android.util.Log
import com.assistant.services.gemini.GeminiClient
import com.assistant.services.openrouter.OpenRouterClient
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.Tool

/**
 * Unified LLM client with automatic fallback from Gemini to OpenRouter.
 * 
 * This ensures permission negotiation and other critical LLM operations
 * continue working even if Gemini rate limits are hit.
 */
class UnifiedLLMClient(
    private val geminiClient: GeminiClient,
    private val openRouterClient: OpenRouterClient
) {
    companion object {
        private const val TAG = "UnifiedLLMClient"
    }
    
    /**
     * Generate a reply with automatic fallback.
     * Tries Gemini first, falls back to OpenRouter on failure.
     */
    fun generateReply(systemInstruction: String, userText: String): String? {
        // Try Gemini first
        if (geminiClient.isConfigured()) {
            try {
                val geminiResponse = geminiClient.generateReply(systemInstruction, userText)
                if (geminiResponse != null) {
                    Log.d(TAG, "Gemini response successful")
                    return geminiResponse
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini failed, falling back to OpenRouter", e)
            }
        }
        
        // Fallback to OpenRouter
        if (openRouterClient.isConfigured()) {
            try {
                val openRouterResponse = openRouterClient.generateReply(systemInstruction, userText)
                if (openRouterResponse != null) {
                    Log.d(TAG, "OpenRouter fallback successful")
                    return openRouterResponse
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenRouter fallback also failed", e)
            }
        }
        
        Log.e(TAG, "All LLM backends failed")
        return null
    }
    
    /**
     * Generate chat response with full history and tool support.
     * Only supported by Gemini (OpenRouter fallback doesn't support tools).
     */
    fun generateChatResponse(
        history: List<Content>,
        tools: List<Tool>? = null,
        systemInstruction: String? = null
    ): com.google.ai.client.generativeai.type.GenerateContentResponse? {
        if (!geminiClient.isConfigured()) {
            Log.w(TAG, "Gemini not configured, cannot use chat with tools")
            return null
        }
        
        return try {
            geminiClient.generateChatResponse(history, tools, systemInstruction)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini chat response failed", e)
            null
        }
    }
    
    /**
     * Check if at least one LLM backend is configured.
     */
    fun isConfigured(): Boolean {
        return geminiClient.isConfigured() || openRouterClient.isConfigured()
    }
}
