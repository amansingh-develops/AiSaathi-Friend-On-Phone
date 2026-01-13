package com.assistant.domain.onboarding

sealed class OnboardingState {
    object AskLanguage : OnboardingState()
    object AskName : OnboardingState()
    object AskPreferredName : OnboardingState()
    object AskAssistantName : OnboardingState()
    object AskEmotionalSupportPreference : OnboardingState()
    object AskWakeWord : OnboardingState()
    object Completed : OnboardingState()
}

