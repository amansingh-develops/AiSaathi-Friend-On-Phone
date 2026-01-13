package com.assistant.services.permissions

import com.assistant.domain.onboarding.OnboardingLanguage

/**
 * Context data sent to LLM for generating permission requests.
 * 
 * This provides all the information needed for the LLM to generate
 * a natural, contextual, language-appropriate permission explanation.
 */
data class PermissionRequestContext(
    val missingPermission: PermissionType,
    val userIntent: String, // Original user request (e.g., "call mom")
    val normalizedIntent: String, // Internally inferred intent (e.g., "CallContact(contactName=mom)")
    val userLanguage: OnboardingLanguage,
    val confidence: Float, // 0.0 to 1.0
    val conversationContext: String? = null // Optional context from previous turns
)

/**
 * Response from LLM for permission request.
 */
data class PermissionRequestResponse(
    val explanation: String, // The human-friendly explanation to speak
    val actionSummary: String // Brief summary of what will happen after permission is granted
)

/**
 * Context for permission denial fallback.
 */
data class PermissionDenialContext(
    val deniedPermission: PermissionType,
    val originalIntent: String,
    val userLanguage: OnboardingLanguage
)

/**
 * Response from LLM for permission denial.
 */
data class PermissionDenialResponse(
    val message: String, // Polite fallback message
    val alternativeSuggestions: List<String> = emptyList() // Optional alternative actions
)
