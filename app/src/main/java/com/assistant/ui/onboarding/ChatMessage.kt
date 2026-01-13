package com.assistant.ui.onboarding

/**
 * Represents a single message in the chat.
 * 
 * Data Flow:
 * - Messages are created by the ViewModel based on OnboardingStateMachine state
 * - UI observes list of messages and displays them as chat bubbles
 */
data class ChatMessage(
    val text: String,
    val isFromAssistant: Boolean,
    val id: String = java.util.UUID.randomUUID().toString()
)

