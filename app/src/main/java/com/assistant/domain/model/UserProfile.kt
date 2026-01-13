package com.assistant.domain.model

data class UserProfile(
    val userId: String,
    val userName: String? = null,
    val preferredName: String? = null,
    val assistantName: String? = null,
    val preferredLanguage: String? = null,
    val tonePreference: String? = null,
    val emotionalSupportPreference: String? = null,
    val wakeWord: String? = null
) {
    fun update(
        userName: String? = this.userName,
        preferredName: String? = this.preferredName,
        assistantName: String? = this.assistantName,
        preferredLanguage: String? = this.preferredLanguage,
        tonePreference: String? = this.tonePreference,
        emotionalSupportPreference: String? = this.emotionalSupportPreference,
        wakeWord: String? = this.wakeWord
    ): UserProfile {
        return copy(
            userName = userName,
            preferredName = preferredName,
            assistantName = assistantName,
            preferredLanguage = preferredLanguage,
            tonePreference = tonePreference,
            emotionalSupportPreference = emotionalSupportPreference,
            wakeWord = wakeWord
        )
    }
}

