package com.assistant.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.domain.model.UserProfile
import com.assistant.domain.onboarding.OnboardingStateMachine
import com.assistant.ui.onboarding.ChatMessage
import com.assistant.ui.onboarding.OnboardingUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for onboarding chat screen.
 * 
 * Architecture:
 * - This ViewModel manages UI state and delegates all business logic to OnboardingStateMachine
 * - ViewModel observes state machine changes and updates UI state accordingly
 * - UI sends user input to ViewModel, which forwards it to state machine
 * - State machine processes input and transitions, ViewModel updates chat messages
 * 
 * Data Flow:
 * 1. User types input -> UI calls onUserInput()
 * 2. ViewModel calls stateMachine.provideInput() with user text
 * 3. ViewModel adds user message to chat
 * 4. State machine transitions to next state
 * 5. ViewModel gets new question via stateMachine.getCurrentQuestion()
 * 6. ViewModel adds assistant message to chat
 * 7. If completed, ViewModel gets UserProfile via stateMachine.getUserProfile()
 * 8. UI observes state changes and updates display
 */
class OnboardingViewModel(
    userId: String
) : ViewModel() {
    
    // Business logic delegate - ViewModel does not contain onboarding logic
    private val stateMachine = OnboardingStateMachine(userId)
    
    // UI state - observed by Compose UI
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()
    
    init {
        // Initialize with first assistant question
        viewModelScope.launch {
            val initialQuestion = stateMachine.getCurrentQuestion()
            addAssistantMessage(initialQuestion)
        }
    }
    
    /**
     * Called when user submits input.
     * 
     * Flow:
     * 1. Add user message to chat
     * 2. Process input through state machine
     * 3. If successful, get next question and add assistant message
     * 4. If completed, get UserProfile and update state
     */
    fun onUserInput(input: String) {
        if (input.isBlank() || _uiState.value.isCompleted) return
        
        viewModelScope.launch {
            // Add user message to chat
            addUserMessage(input)
            
            // Process input through state machine (business logic happens here)
            val success = stateMachine.provideInput(input)
            
            if (success) {
                // Check if onboarding is complete
                if (stateMachine.isCompleted()) {
                    // Get completed profile from state machine
                    val profile = stateMachine.getUserProfile()
                    _uiState.update { it.copy(completedProfile = profile) }
                } else {
                    // Get next question from state machine
                    val nextQuestion = stateMachine.getCurrentQuestion()
                    if (nextQuestion.isNotBlank()) {
                        addAssistantMessage(nextQuestion)
                    }
                }
            }
            
            // Clear input field
            _uiState.update { it.copy(inputText = "") }
        }
    }
    
    /**
     * Update input text as user types.
     */
    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }
    
    /**
     * Add assistant message to chat.
     * Private helper - messages are added by ViewModel based on state machine state.
     */
    private fun addAssistantMessage(text: String) {
        _uiState.update { currentState ->
            currentState.copy(
                messages = currentState.messages + ChatMessage(
                    text = text,
                    isFromAssistant = true
                )
            )
        }
    }
    
    /**
     * Add user message to chat.
     * Private helper - messages are added when user submits input.
     */
    private fun addUserMessage(text: String) {
        _uiState.update { currentState ->
            currentState.copy(
                messages = currentState.messages + ChatMessage(
                    text = text,
                    isFromAssistant = false
                )
            )
        }
    }
}

