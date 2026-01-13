package com.assistant.services.elevenlabs

import android.util.Log
import com.assistant.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal ElevenLabs HTTP client (TTS + STT).
 *
 * Notes:
 * - Endpoints and payload shapes can evolve. Keep this class isolated so swapping is easy.
 * - All functions are blocking; call them from Dispatchers.IO only.
 */
class ElevenLabsClient(
    private val apiKey: String = BuildConfig.ELEVENLABS_API_KEY,
    private val http: OkHttpClient = defaultHttp()
) {
    companion object {
        private const val TAG = "ElevenLabsClient"

        // Base URL used by ElevenLabs REST APIs.
        private const val BASE_URL = "https://api.elevenlabs.io"

        private fun defaultHttp(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(35, TimeUnit.SECONDS)
                .build()
        }
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * Text-to-Speech.
     *
     * API reference: `https://api.elevenlabs.io/v1/text-to-speech/{voice_id}`
     *
     * Returns raw audio bytes (typically MP3).
     */
    fun tts(voiceId: String, text: String): ByteArray? {
        if (apiKey.isBlank()) return null
        if (voiceId.isBlank()) return null
        if (text.isBlank()) return null

        val json = JSONObject().apply {
            put("text", text)
            // Keep settings conservative; we're optimizing for naturalness + speed.
            put("model_id", "eleven_multilingual_v2")
            put(
                "voice_settings",
                JSONObject().apply {
                    put("stability", 0.45)
                    put("similarity_boost", 0.85)
                    put("style", 0.18)
                    put("use_speaker_boost", true)
                }
            )
        }.toString()

        val req = Request.Builder()
            .url("$BASE_URL/v1/text-to-speech/$voiceId")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()
                    Log.w(TAG, "TTS failed: HTTP ${resp.code}, body: $errBody")
                    return null
                }
                val bytes = resp.body?.bytes()
                Log.d(TAG, "TTS success: ${bytes?.size ?: 0} bytes for '$text'")
                bytes
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS exception for '$text'", e)
            null
        }
    }

    /**
     * Speech-to-Text (file upload).
     *
     * API reference: `https://api.elevenlabs.io/v1/speech-to-text`
     *
     * Returns best-effort transcript string, or null on failure.
     */
    fun stt(audioWavFile: File): String? {
        if (apiKey.isBlank()) return null
        if (!audioWavFile.exists()) return null

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = audioWavFile.name,
                body = audioWavFile.asRequestBody("audio/wav".toMediaType())
            )
            // Best-effort model hint (ElevenLabs "scribe" family).
            .addFormDataPart("model_id", "scribe_v1")
            .build()

        val req = Request.Builder()
            .url("$BASE_URL/v1/speech-to-text")
            .addHeader("xi-api-key", apiKey)
            .post(body)
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()
                    Log.w(TAG, "STT failed: HTTP ${resp.code}, body: $errBody")
                    return null
                }
                val raw = resp.body?.string().orEmpty()
                Log.d(TAG, "STT response: $raw")
                // Response is JSON; try to read common fields ("text", "transcript").
                val json = runCatching { JSONObject(raw) }.getOrNull()
                val transcript = json?.optString("text")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("transcript")?.takeIf { it.isNotBlank() }
                
                if (transcript == null) {
                    Log.w(TAG, "STT: No text/transcript field in response JSON")
                }
                transcript
            }
        } catch (e: Exception) {
            Log.w(TAG, "STT exception", e)
            null
        }
    }
}



