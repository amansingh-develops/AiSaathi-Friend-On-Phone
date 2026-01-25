package com.assistant.services.openrouter

import android.util.Log
import com.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * OpenRouter client for LLM API calls.
 * 
 * CRITICAL: All methods are suspend functions with timeout protection
 * to prevent blocking when audio system is contested.
 * 
 * Supports shared OkHttpClient for connection pooling.
 */
class OpenRouterClient(
    private val apiKey: String = BuildConfig.OPENROUTER_API_KEY,
    sharedClient: OkHttpClient? = null
) {
    companion object {
        private const val TAG = "OpenRouterClient"
        private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
        // User requested Xiaomi Mimo as the specific OpenRouter fallback
        private const val MODEL_FALLBACK = "xiaomi/mimo-v2-flash:free"
        
        // HTTP call timeout (shorter than OkHttp's to enable fallback)
        private const val CALL_TIMEOUT_MS = 3_000L
    }

    // Use shared client if provided, otherwise create a new one
    private val client = sharedClient ?: OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * Generate a reply from OpenRouter.
     * 
     * CRITICAL: This is a suspend function with coroutine timeout protection.
     * If the HTTP call blocks (common during audio contention), the timeout
     * will trigger and allow fallback to other models.
     */
    suspend fun generateReply(systemInstruction: String, userText: String): String? {
        if (!isConfigured()) {
            throw IllegalStateException("OpenRouter API Key not configured")
        }

        val startTime = System.currentTimeMillis()
        Log.i(TAG, "┌─────────────────────────────────────────────────────────")
        Log.i(TAG, "│ 🚀 OPENROUTER REQUEST STARTED")
        Log.i(TAG, "│ Model: $MODEL_FALLBACK")
        Log.i(TAG, "│ User text: '${userText.take(50)}...'")
        Log.i(TAG, "│ Timeout: ${CALL_TIMEOUT_MS}ms")
        Log.i(TAG, "└─────────────────────────────────────────────────────────")

        val messages = JSONArray()
        
        // System
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemInstruction)
        })
        
        // User
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userText)
        })

        val jsonBody = JSONObject().apply {
            put("model", MODEL_FALLBACK)
            put("messages", messages)
            put("temperature", 0.7)
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "com.assistant") 
            .addHeader("X-Title", "Assistant Android")
            .post(jsonBody.toString().toRequestBody(jsonMediaType))
            .build()

        // CRITICAL: Wrap blocking execute() with coroutine timeout
        // This prevents indefinite blocking when audio system is contested
        val result = withTimeoutOrNull(CALL_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                executeRequest(request, startTime)
            }
        }
        
        if (result == null) {
            val latencyMs = System.currentTimeMillis() - startTime
            Log.w(TAG, "╔════════════════════════════════════════════════════════╗")
            Log.w(TAG, "║  ⏰ OPENROUTER TIMEOUT                                 ║")
            Log.w(TAG, "║  Request timed out after ${latencyMs}ms")
            Log.w(TAG, "║  (Likely audio contention blocking network)")
            Log.w(TAG, "╚════════════════════════════════════════════════════════╝")
            // Throw to trigger fallback in SmartModelRouter
            throw IOException("OpenRouter request timed out after ${latencyMs}ms")
        }
        
        return result
    }
    
    /**
     * Execute the HTTP request (blocking).
     * Called from within withContext(Dispatchers.IO).
     */
    private fun executeRequest(request: Request, startTime: Long): String? {
        try {
            Log.d(TAG, "Sending HTTP request to OpenRouter...")
            
            client.newCall(request).execute().use { response ->
                val latencyMs = System.currentTimeMillis() - startTime
                
                if (!response.isSuccessful) {
                    val body = response.body?.string()
                    Log.e(TAG, "╔════════════════════════════════════════════════════════╗")
                    Log.e(TAG, "║  ❌ OPENROUTER FAILED                                  ║")
                    Log.e(TAG, "║  Status: ${response.code} ${response.message}")
                    Log.e(TAG, "║  Latency: ${latencyMs}ms")
                    Log.e(TAG, "║  Body: ${body?.take(200)}")
                    Log.e(TAG, "╚════════════════════════════════════════════════════════╝")
                    
                    // Throw specific exceptions for SmartModelRouter to classify
                    throw IOException("OpenRouter failed: ${response.code} ${response.message}")
                }

                val bodyStr = response.body?.string() ?: return null
                val responseJson = JSONObject(bodyStr)
                
                val choices = responseJson.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val firstChoice = choices.getJSONObject(0)
                    val message = firstChoice.optJSONObject("message")
                    val content = message?.optString("content")?.trim()
                    
                    Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
                    Log.i(TAG, "║  ✅ OPENROUTER SUCCESS                                 ║")
                    Log.i(TAG, "║  Latency: ${latencyMs}ms")
                    Log.i(TAG, "║  Response: '${content?.take(50)}...'")
                    Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
                    
                    return content
                }
                
                Log.w(TAG, "OpenRouter returned empty choices after ${latencyMs}ms")
            }
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "╔════════════════════════════════════════════════════════╗")
            Log.e(TAG, "║  ❌ OPENROUTER EXCEPTION                               ║")
            Log.e(TAG, "║  Error: ${e.message}")
            Log.e(TAG, "║  Latency: ${latencyMs}ms")
            Log.e(TAG, "╚════════════════════════════════════════════════════════╝")
            throw e
        }
        
        return null
    }

    /**
     * Build an OkHttp Request for functional API health check.
     * Hits the models endpoint (which requires auth) to verify key + connectivity.
     */
    fun getHealthCheckRequest(): Request? {
        if (!isConfigured()) return null
        
        return Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
    }
}
