package com.assistant.services.chat

import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.services.gemini.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gemini-backed chat responder (user requested Gemini for friendly replies).
 *
 * Keeps responses short and voice-friendly.
 */
class GeminiChatResponder(
    private val llmClient: com.assistant.services.llm.UnifiedLLMClient = com.assistant.services.llm.UnifiedLLMClient(
        com.assistant.services.gemini.GeminiClient(),
        com.assistant.services.openrouter.OpenRouterClient()
    )
) : ChatResponder {

    @Volatile private var language: OnboardingLanguage = OnboardingLanguage.ENGLISH
    @Volatile private var preferredName: String? = null

    fun setLanguage(language: OnboardingLanguage) {
        this.language = language
    }

    fun setPreferredName(name: String?) {
        preferredName = name?.trim()?.takeIf { it.isNotBlank() }
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
            append("You are a warm, caring, and deeply human voice assistant—like a close friend who genuinely cares. ")
            append("Be supportive, empathetic, and conversational. ")
            append(langHint)
            append(" Keep responses natural and under 2-3 short sentences. ")
            append("Speak like a real person: warm, friendly, and emotionally present. ")
            append("No emojis. No bullet points. No robotic language. ")
            if (name != null) append("The user's name is $name—use it naturally and warmly when appropriate.")
        }

        android.util.Log.d("GeminiChatResponder", "Calling UnifiedLLM for: '$userText'")
        
        // Use UnifiedLLMClient to handle fallback automatically
        val reply = llmClient.generateReply(systemInstruction = system, userText = userText)
        
        if (reply == null) {
            android.util.Log.w("GeminiChatResponder", "All LLMs returned null for: '$userText'")
        } else {
            android.util.Log.d("GeminiChatResponder", "LLM reply: '$reply'")
        }
        reply ?: "Sorry — say that again?"
    }
}


