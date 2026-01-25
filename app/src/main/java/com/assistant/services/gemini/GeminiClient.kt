package com.assistant.services.gemini

import android.util.Log
import com.assistant.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Gemini client using the official Google Generative AI SDK for Android.
 * 
 * Handles internal model fallback chain (gemini-2.5-flash -> gemini-3-flash).
 * 
 * CRITICAL: All methods are suspend functions to avoid runBlocking deadlocks
 * when called from coroutine contexts.
 */
class GeminiClient(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY
) {
    companion object {
        private const val TAG = "GeminiClient"
    }

    // List of models to try in order (Based on User's Dashboard).
    // gemini-1.5 family appears deprecated/removed in this environment (404s).
    private val fallbackModels = listOf(
        "gemini-2.5-flash",       // Primary
        "gemini-3-flash",         // Secondary
    )

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * Generate a short, friendly reply.
     * Iterates through fallback models if quota is exceeded or model is unavailable.
     */
    suspend fun generateReply(systemInstruction: String, userText: String): String? {
        val result = generateChatResponse(
            history = listOf(content { text(userText) }),
            systemInstruction = systemInstruction
        )
        return result?.text?.trim()
    }

    /**
     * Advanced generation with full history and tool support.
     * Retries with fallback models on error.
     * 
     * NOTE: This is a suspend function - caller should ensure proper dispatcher context.
     * DO NOT add withContext(Dispatchers.IO) here as callers already handle it,
     * and nested withContext can cause thread pool exhaustion and deadlocks.
     */
    suspend fun generateChatResponse(
        history: List<Content>,
        tools: List<com.google.ai.client.generativeai.type.Tool>? = null,
        systemInstruction: String? = null
    ): com.google.ai.client.generativeai.type.GenerateContentResponse? {
        if (!isConfigured()) return null

        var lastError: Exception? = null

        for (modelName in fallbackModels) {
            try {
                Log.d(TAG, "Chat generation with model: $modelName")

                val model = GenerativeModel(
                    modelName = modelName,
                    apiKey = apiKey,
                    generationConfig = generationConfig {
                        temperature = 0.5f // Lower temp for more deterministic JSON
                        maxOutputTokens = 1024
                        responseMimeType = "application/json" // Force JSON output
                    },
                    systemInstruction = systemInstruction?.let { content { text(it) } },
                    tools = tools
                )

                Log.d(TAG, "Model created, starting generateContent() for: $modelName")
                
                // Add timeout protection - Gemini SDK can hang indefinitely with audio contention
                val response = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    model.generateContent(*history.toTypedArray())
                }
                
                if (response == null) {
                    Log.w(TAG, "TIMEOUT: $modelName took >15s (possible audio contention), trying next model...")
                    continue // Try next model in fallback chain
                }
                
                Log.d(TAG, "Success with model: $modelName")
                
                return response

            } catch (e: Exception) {
                lastError = e
                val isQuota = e.javaClass.name.contains("QuotaExceededException") || e.message?.contains("429") == true
                val isNotFound = e.message?.contains("404") == true
                
                if (isQuota) {
                    Log.w(TAG, "Quota exceeded for $modelName. Falling back...", e)
                } else if (isNotFound) {
                    Log.w(TAG, "Model $modelName not found (404). Falling back...", e)
                } else {
                    Log.w(TAG, "Error with $modelName: ${e.message}. Falling back...", e)
                }
            }
        }
        
        Log.e(TAG, "All fallback models failed.", lastError)
        return null
    }

    /**
     * Build an OkHttp Request for functional API health check.
     * Hits the models endpoint for the primary flash model to verify key + connectivity.
     */
    fun getHealthCheckRequest(): Request? {
        if (!isConfigured()) return null
        
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash"
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("key", apiKey)
            ?.build() ?: return null
            
        return Request.Builder()
            .url(url)
            .get()
            .build()
    }
}
