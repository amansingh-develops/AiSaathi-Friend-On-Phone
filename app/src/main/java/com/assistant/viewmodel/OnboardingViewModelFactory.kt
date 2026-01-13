package com.assistant.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Factory for creating OnboardingViewModel with userId parameter.
 * 
 * Data Flow:
 * - Factory is created with userId
 * - ViewModel is instantiated with userId
 * - ViewModel creates OnboardingStateMachine with userId
 */
class OnboardingViewModelFactory(
    private val userId: String
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OnboardingViewModel::class.java)) {
            return OnboardingViewModel(userId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

