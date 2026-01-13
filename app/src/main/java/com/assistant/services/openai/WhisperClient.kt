package com.assistant.services.openai

import android.util.Log
import com.assistant.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Client for Whisper API (Groq/OpenAI compatible).
 *
 * Uses Groq for ultra-low latency transcription.
 */
class WhisperClient(
    // Prefer GROQ API Key if available for speed, else OpenAI
    private val apiKey: String = BuildConfig.GROQ_API_KEY.ifBlank { BuildConfig.OPENAI_API_KEY },
    private val endpoint: String = if (BuildConfig.GROQ_API_KEY.isNotBlank()) "https://api.groq.com/openai/v1/audio/transcriptions" else "https://api.openai.com/v1/audio/transcriptions",
    private val model: String = if (BuildConfig.GROQ_API_KEY.isNotBlank()) "whisper-large-v3" else "whisper-1",
    private val http: OkHttpClient = defaultHttp()
) {

    companion object {
        private const val TAG = "WhisperClient"

        private fun defaultHttp(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * Transcribe audio file (WAV).
     * Returns the text string strictly.
     *
     * @param promptContext Previous assistant response to provide context for the answer.
     */
    fun transcribe(file: File, language: String? = null, promptContext: String? = null): String? {
        if (!isConfigured()) return null

        val fileBody = file.asRequestBody("audio/wav".toMediaType())
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", fileBody)
            .addFormDataPart("model", model)
            // .addFormDataPart("language", "en") <-- REMOVED to allow auto-detect
            // PROMPT ENGINEERING:
            // We explicitly include English, Hinglish, and Devanagari Hindi in the prompt.
            // This "primes" Whisper to use Devanagari for Hindi sounds instead of Urdu script.
            // We also inject the previous assistant response to give context (e.g. "Who is prime minister?" -> "Modi").
            .addFormDataPart("prompt", buildString {
                if (!promptContext.isNullOrBlank()) {
                    append("Previous Assistant: $promptContext\n")
                }
                append("User: Hello. Namaste. नमस्ते। क्या हाल है? Main Hindi aur English boloonga. Today is a good day.")
            })
        
        if (!language.isNullOrBlank()) {
            builder.addFormDataPart("language", language)
        }

        val multipart = builder.build()
        
        // If using OpenAI, language param helps accuracy. Groq supports it too.
        // We map our "hi-IN" etc to just ISO-639-1 code if needed, but Whisper handles auto-detect well.
        // For speed, let's trust auto-detect or a simple "en".
        
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()
                    Log.e(TAG, "Whisper API failed: ${response.code} $err")
                    return null
                }
                val bodyStr = response.body?.string()
                // Parse simple JSON: {"text": "..."}
                extractTextFromJson(bodyStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe failed", e)
            null
        }
    }

    private fun extractTextFromJson(json: String?): String? {
        if (json == null) return null
        return try {
            org.json.JSONObject(json).optString("text").trim()
        } catch (_: Exception) {
            null
        }
    }
}
