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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Gemini client using the official Google Generative AI SDK for Android.
 */
class GeminiClient(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY
) {
    companion object {
        private const val TAG = "GeminiClient"
    }

    // List of models to try in order (As seen in your Rate Limit screenshot).
    // 1. gemini-2.5-flash-lite: 10 RPM (Primary)
    // 2. gemini-2.5-flash: 5 RPM (Fallback 1)
    // 3. gemini-3-flash: 5 RPM (Fallback 2)
    // Note: native-audio-dialog is not supported for text generation (generateContent).
    // List of models to try in order (Based on User's Dashboard).
    // gemini-1.5 family appears deprecated/removed in this environment (404s).
    private val fallbackModels = listOf(
        "gemini-2.5-flash",       // Primary
        "gemini-3-flash",         // Secondary
        "gemini-2.5-flash-lite"   // Tertiary (Low quota but exists)
    )

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * Generate a short, friendly reply.
     * Iterates through fallback models if quota is exceeded or model is unavailable.
     */
    fun generateReply(systemInstruction: String, userText: String): String? {
        val result = generateChatResponse(
            history = listOf(content { text(userText) }),
            systemInstruction = systemInstruction
        )
        return result?.text?.trim()
    }

    /**
     * Advanced generation with full history and tool support.
     * Retries with fallback models on error.
     */
    fun generateChatResponse(
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

                // Blocking call
                // stateless request with history
                val response = runBlocking {
                    model.generateContent(*history.toTypedArray())
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
}


