package com.assistant.domain.onboarding

enum class OnboardingLanguage(val code: String, val displayName: String) {
    HINDI("hi", "Hindi"),
    HINGLISH("hi-en", "Hinglish"),
    ENGLISH("en", "English")
    ;

    companion object {
        /**
         * Best-effort parsing from persisted profile values.
         *
         * Accepts:
         * - Enum codes: "hi", "hi-en", "en"
         * - Common labels: "hindi", "hinglish", "english"
         */
        fun fromCode(raw: String?): OnboardingLanguage {
            val v = raw?.trim()?.lowercase()
            return when (v) {
                "hi", "hi-in", "hindi" -> HINDI
                "hi-en", "hinglish" -> HINGLISH
                "en", "en-in", "english" -> ENGLISH
                else -> ENGLISH
            }
        }
    }
}

