package com.assistant.services.openrouter

import android.util.Log
import com.assistant.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class OpenRouterClient(
    private val apiKey: String = BuildConfig.OPENROUTER_API_KEY
) {
    companion object {
        private const val TAG = "OpenRouterClient"
        private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
        // User requested Xiaomi Mimo as the specific OpenRouter fallback
        private const val MODEL_FALLBACK = "xiaomi/mimo-v2-flash:free"
    }

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    fun generateReply(systemInstruction: String, userText: String): String? {
        if (!isConfigured()) {
            Log.w(TAG, "OpenRouter API Key not configured")
            return null
        }

        try {
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
                // Optional: Response format json_object (if supported by model, otherwise rely on prompt)
                // "response_format": { "type": "json_object" } 
            }

            val request = Request.Builder()
                .url(BASE_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "com.assistant") 
                .addHeader("X-Title", "Assistant Android")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "OpenRouter Request Failed: ${response.code} / ${response.message}")
                    Log.e(TAG, "Body: ${response.body?.string()}")
                    return null
                }

                val bodyStr = response.body?.string() ?: return null
                val responseJson = JSONObject(bodyStr)
                
                val choices = responseJson.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val firstChoice = choices.getJSONObject(0)
                    val message = firstChoice.optJSONObject("message")
                    return message?.optString("content")?.trim()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter Fallback Failed", e)
        }
        return null
    }
}
