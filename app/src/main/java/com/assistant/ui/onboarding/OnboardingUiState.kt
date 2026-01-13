package com.assistant.ui.onboarding

import com.assistant.domain.model.UserProfile

/**
 * UI State for the onboarding screen.
 * 
 * Data Flow:
 * - ViewModel holds this state and updates it based on OnboardingStateMachine
 * - UI observes this state via StateFlow/LiveData
 * - When user provides input, ViewModel processes it through state machine
 * - State machine transitions trigger UI state updates
 */
data class OnboardingUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val completedProfile: UserProfile? = null,
    val inputText: String = ""
) {
    val isCompleted: Boolean
        get() = completedProfile != null
}

