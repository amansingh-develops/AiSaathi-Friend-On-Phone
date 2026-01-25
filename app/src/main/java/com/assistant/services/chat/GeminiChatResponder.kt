package com.assistant.services.chat

import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.services.gemini.GeminiClient
import com.assistant.services.llm.SmartModelRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gemini-backed chat responder (user requested Gemini for friendly replies).
 *
 * Keeps responses short and voice-friendly.
 * Uses SmartModelRouter for session-locked routing when available.
 */
class GeminiChatResponder(
    private val llmClient: com.assistant.services.llm.UnifiedLLMClient = com.assistant.services.llm.UnifiedLLMClient(
        com.assistant.services.gemini.GeminiClient(),
        com.assistant.services.openrouter.OpenRouterClient()
    ),
    private var smartRouter: SmartModelRouter? = null
) : ChatResponder {

    @Volatile private var language: OnboardingLanguage = OnboardingLanguage.ENGLISH
    @Volatile private var preferredName: String? = null

    fun setLanguage(language: OnboardingLanguage) {
        this.language = language
    }

    fun setPreferredName(name: String?) {
        preferredName = name?.trim()?.takeIf { it.isNotBlank() }
    }
    
    fun setSmartRouter(router: SmartModelRouter?) {
        this.smartRouter = router
    }

    fun isConfigured(): Boolean = llmClient.isConfigured()

    override suspend fun respond(userText: String): String = withContext(Dispatchers.IO) {
        val name = preferredName
        val langHint = when (language) {
            OnboardingLanguage.HINDI -> "Reply in Hindi (natural, warm)."
            OnboardingLanguage.HINGLISH -> "Reply in Hinglish (natural mix of Hindi+English)."
            OnboardingLanguage.ENGLISH -> "Reply in English."
        }

        val system = buildString {
            append("You are a warm, caring voice assistant—like a close friend. ")
            append("Be supportive, empathetic, conversational. ")
            append("🔴 LANGUAGE MIRRORING (ABSOLUTE): DETECT user's language/dialect/accent from their message. MIRROR it exactly. ")
            append("Indian languages: Hindi, Hinglish, Punjabi, Marathi, Telugu, Tamil, Kannada, Bengali, Gujarati, Bhojpuri. ")
            append("Script: Devanagari→Devanagari, Roman→Roman. Tone: match user's formality. ")
            append("Fallback if unclear: $langHint ")
            append("Keep responses natural, 2-3 short sentences. ")
            append("No emojis. No bullet points. No robotic language. ")
            if (name != null) append("User's name: $name—use naturally. ")
            append("\n\nIMPORTANT: Return JSON: {\"response\": \"your spoken text here\"}")
        }

        android.util.Log.d("GeminiChatResponder", "Calling LLM for: '$userText'")
        
        // Use SmartModelRouter if available (session-locked routing)
        val rawReply = smartRouter?.let { router ->
            android.util.Log.d("GeminiChatResponder", "Using SmartModelRouter for session-locked routing")
            router.generateSmart(system, userText)
        } ?: run {
            // Fallback to UnifiedLLMClient if SmartModelRouter not wired
            android.util.Log.d("GeminiChatResponder", "Using UnifiedLLMClient (fallback)")
            llmClient.generateReply(systemInstruction = system, userText = userText)
        }
        
        if (rawReply == null) {
            android.util.Log.w("GeminiChatResponder", "All LLMs returned null for: '$userText'")
            return@withContext "Sorry — say that again?"
        }
        
        android.util.Log.d("GeminiChatResponder", "LLM reply: '$rawReply'")
        
        // Parse the JSON response to extract just the text
        // LLM returns: {"response": "actual text"} due to responseMimeType = application/json
        val extractedText = try {
            val cleanJson = rawReply.trim()
            // Try to parse as JSON and extract "response" field
            if (cleanJson.startsWith("{") && cleanJson.contains("response")) {
                val json = org.json.JSONObject(cleanJson)
                json.optString("response").takeIf { it.isNotBlank() } ?: cleanJson
            } else {
                // Not JSON, use as-is
                cleanJson
            }
        } catch (e: Exception) {
            android.util.Log.w("GeminiChatResponder", "Failed to parse JSON, using raw response: ${e.message}")
            rawReply.trim()
        }
        
        extractedText.ifBlank { "Sorry — say that again?" }
    }
}



