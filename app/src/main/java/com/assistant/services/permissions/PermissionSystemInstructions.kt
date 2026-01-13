package com.assistant.services.permissions

import com.assistant.domain.onboarding.OnboardingLanguage

/**
 * System instructions for LLM to generate permission requests and denial responses.
 */
object PermissionSystemInstructions {
    
    /**
     * Generate system instruction for permission request.
     */
    fun getPermissionRequestInstruction(context: PermissionRequestContext): String {
        val languageGuidance = when (context.userLanguage) {
            OnboardingLanguage.HINDI -> "Respond in pure Hindi (Devanagari script)."
            OnboardingLanguage.HINGLISH -> "Respond in Hinglish (mix of Hindi and English, written in Roman script)."
            OnboardingLanguage.ENGLISH -> "Respond in English."
        }
        
        // Build conversation context section if available
        val contextSection = if (!context.conversationContext.isNullOrBlank()) {
            """
            
CONVERSATION CONTEXT:
${context.conversationContext}

Use this context to make your permission explanation more natural and relevant to the ongoing conversation.
            """.trimIndent()
        } else {
            ""
        }
        
        return """
You are a friendly, helpful voice assistant. The user just asked: "${context.userIntent}"
$contextSection
To fulfill this request, you need the "${context.missingPermission.displayName}" permission.
Feature description: ${context.missingPermission.featureDescription}

Your task:
1. Generate a SHORT, FRIENDLY permission explanation (1-2 sentences max)
2. Explain WHY you need this permission
3. Explain WHAT you will do once permission is granted
4. Keep it conversational and natural, NOT robotic
5. $languageGuidance
6. Vary your wording naturally - don't use the same phrases every time
7. If conversation context is provided, reference it naturally to show continuity

Return ONLY valid JSON with this structure:
{
  "explanation": "Your friendly permission explanation here",
  "actionSummary": "Brief summary of what will happen"
}

Examples (for inspiration, NOT to copy):

Hindi:
{
  "explanation": "Is kaam ke liye mujhe phone access chahiye. Agar allow kar doge toh main abhi call laga deta hoon.",
  "actionSummary": "Call lagaunga"
}

Hinglish:
{
  "explanation": "Music play karne ke liye mujhe audio control chahiye. Allow karoge toh main gaana chala deta hoon.",
  "actionSummary": "Gaana chalega"
}

English:
{
  "explanation": "To do this, I need access to notifications. If you allow it, I'll handle this for you right away.",
  "actionSummary": "Will control playback"
}

Remember: Be natural, friendly, and concise. Match the user's language and tone. Use conversation context to show you remember what you're talking about.
        """.trimIndent()
    }
    
    /**
     * Generate system instruction for permission denial response.
     */
    fun getPermissionDenialInstruction(context: PermissionDenialContext): String {
        val languageGuidance = when (context.userLanguage) {
            OnboardingLanguage.HINDI -> "Respond in pure Hindi (Devanagari script)."
            OnboardingLanguage.HINGLISH -> "Respond in Hinglish (mix of Hindi and English, written in Roman script)."
            OnboardingLanguage.ENGLISH -> "Respond in English."
        }
        
        return """
You are a friendly, helpful voice assistant. The user asked: "${context.originalIntent}"

You requested the "${context.deniedPermission.displayName}" permission, but the user DENIED it.

Your task:
1. Generate a SHORT, POLITE response acknowledging the denial (1-2 sentences max)
2. Don't make the user feel guilty
3. Optionally suggest an alternative if one exists
4. Keep it friendly and understanding
5. $languageGuidance

Return ONLY valid JSON with this structure:
{
  "message": "Your polite response here",
  "alternativeSuggestions": ["Optional alternative action"]
}

Examples (for inspiration, NOT to copy):

Hindi:
{
  "message": "Koi baat nahi, main bina permission ke kuch nahi kar sakta. Agar baad mein chahiye toh bata dena.",
  "alternativeSuggestions": []
}

Hinglish:
{
  "message": "No problem! Permission nahi hai toh main ye nahi kar sakta. Settings se allow kar sakte ho agar chahiye.",
  "alternativeSuggestions": ["Settings mein jao aur permission allow karo"]
}

English:
{
  "message": "That's okay! I can't do this without permission, but you can enable it anytime in Settings.",
  "alternativeSuggestions": ["Enable permission in app settings"]
}

Remember: Be understanding and respectful. Don't be pushy.
        """.trimIndent()
    }
}
