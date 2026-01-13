package com.assistant.domain.onboarding

import com.assistant.domain.model.UserProfile

class OnboardingStateMachine(private val userId: String) {
    private var currentState: OnboardingState = OnboardingState.AskLanguage
    private var selectedLanguage: OnboardingLanguage? = null
    
    private var name: String? = null
    private var preferredName: String? = null
    private var assistantName: String? = null
    private var emotionalSupportPreference: String? = null
    private var wakeWord: String? = null
    
    fun getCurrentQuestion(): String {
        return when (currentState) {
            is OnboardingState.AskLanguage -> getLanguageQuestion()
            is OnboardingState.AskName -> getQuestion("name")
            is OnboardingState.AskPreferredName -> getQuestion("preferredName")
            is OnboardingState.AskAssistantName -> getQuestion("assistantName")
            is OnboardingState.AskEmotionalSupportPreference -> getQuestion("emotionalSupport")
            is OnboardingState.AskWakeWord -> getQuestion("wakeWord")
            is OnboardingState.Completed -> ""
        }
    }
    
    fun provideInput(input: String): Boolean {
        return when (currentState) {
            is OnboardingState.AskLanguage -> handleLanguageInput(input)
            is OnboardingState.AskName -> handleNameInput(input)
            is OnboardingState.AskPreferredName -> handlePreferredNameInput(input)
            is OnboardingState.AskAssistantName -> handleAssistantNameInput(input)
            is OnboardingState.AskEmotionalSupportPreference -> handleEmotionalSupportInput(input)
            is OnboardingState.AskWakeWord -> handleWakeWordInput(input)
            is OnboardingState.Completed -> false
        }
    }
    
    fun isCompleted(): Boolean {
        return currentState is OnboardingState.Completed
    }
    
    fun getUserProfile(): UserProfile? {
        return if (currentState is OnboardingState.Completed) {
            UserProfile(
                userId = userId,
                userName = name,
                preferredName = preferredName,
                assistantName = assistantName,
                preferredLanguage = selectedLanguage?.code,
                emotionalSupportPreference = emotionalSupportPreference,
                wakeWord = wakeWord
            )
        } else {
            null
        }
    }
    
    private fun getLanguageQuestion(): String {
        return "Hey! 👋 Hum kaunsi language mein baat karein? Hindi, Hinglish ya English?"
    }
    
    private fun getQuestion(key: String): String {
        val lang = selectedLanguage ?: OnboardingLanguage.ENGLISH
        return when (lang) {
            OnboardingLanguage.HINDI -> getHindiQuestion(key)
            OnboardingLanguage.HINGLISH -> getHinglishQuestion(key)
            OnboardingLanguage.ENGLISH -> getEnglishQuestion(key)
        }
    }
    
    private fun getEnglishQuestion(key: String): String {
        return when (key) {
            "name" -> "Nice to meet you! 😊 What should I call you?"
            "preferredName" -> "Got it! Any nickname you'd like me to use? Or just your name is fine!"
            "assistantName" -> "Fun question! What would you like to name me? Pick something you'll enjoy saying!"
            "emotionalSupport" -> "When you're having a rough day, what helps? A chat, some music, or just quiet company?"
            "wakeWord" -> "Last thing! What word should wake me up? Like 'Hey Assistant' or something more personal?"
            else -> ""
        }
    }
    
    private fun getHindiQuestion(key: String): String {
        return when (key) {
            "name" -> "हैलो! 😊 तुम्हारा नाम क्या है? मैं तुम्हें क्या बुलाऊं?"
            "preferredName" -> "अच्छा! कोई छोटा नाम चाहिए? जैसे नीतू, राहुल... या बस नाम ही ठीक?"
            "assistantName" -> "अरे यार, मेरा भी नाम तो बताओ! 😄 कुछ fun सा सोचो - मैं तुम्हारा दोस्त बनने आया हूं!"
            "emotionalSupport" -> "सुनो, जब तुम्हारा mood off हो या stress हो, तो मैं क्या करूं? बात करूं, तुम्हें motivate करूं, या बस quietly साथ रहूं?"
            "wakeWord" -> "एक last बात! मुझे कैसे बुलाओगे? जैसे 'हे' या 'सुनो' या कोई और cool word?"
            else -> ""
        }
    }
    
    private fun getHinglishQuestion(key: String): String {
        return when (key) {
            "name" -> "Hello! 😊 Tera naam kya hai? Main tujhe kya bulaun?"
            "preferredName" -> "Accha! Koi chota naam? Like Neetu, Rahul... ya bas naam hi theek hai?"
            "assistantName" -> "Are yaar, mera bhi naam toh batao! 😄 Kuch fun sa socho - main tera dost banne aaya hoon!"
            "emotionalSupport" -> "Sun, jab tera mood off ho ya stress ho, toh main kya karu? Baat karun, motivate karun, ya bas quietly saath rahun?"
            "wakeWord" -> "Ek last baat! Mujhe kaise bulayega? Jaise 'Hey' ya 'Sun' ya koi aur cool word?"
            else -> ""
        }
    }
    
    private fun handleLanguageInput(input: String): Boolean {
        val normalizedInput = input.trim().lowercase()
        val language = when {
            normalizedInput.contains("hindi") && !normalizedInput.contains("hinglish") -> OnboardingLanguage.HINDI
            normalizedInput.contains("hinglish") -> OnboardingLanguage.HINGLISH
            normalizedInput.contains("english") -> OnboardingLanguage.ENGLISH
            else -> null
        }
        
        if (language != null) {
            selectedLanguage = language
            currentState = OnboardingState.AskName
            return true
        }
        return false
    }
    
    private fun handleNameInput(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isNotBlank()) {
            name = trimmed
            currentState = OnboardingState.AskPreferredName
            return true
        }
        return false
    }
    
    private fun handlePreferredNameInput(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isNotBlank()) {
            preferredName = trimmed
            currentState = OnboardingState.AskAssistantName
            return true
        }
        return false
    }
    
    private fun handleAssistantNameInput(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isNotBlank()) {
            assistantName = trimmed
            currentState = OnboardingState.AskEmotionalSupportPreference
            return true
        }
        return false
    }
    
    private fun handleEmotionalSupportInput(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isNotBlank()) {
            emotionalSupportPreference = trimmed
            currentState = OnboardingState.AskWakeWord
            return true
        }
        return false
    }
    
    private fun handleWakeWordInput(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isNotBlank()) {
            wakeWord = trimmed
            currentState = OnboardingState.Completed
            return true
        }
        return false
    }
}

