package com.assistant.domain.model

/**
 * User preference for the assistant's spoken voice.
 *
 * Notes:
 * - Android's platform TTS API does not expose an explicit "gender" field for voices.
 * - We interpret this as a *preference* and choose the best matching installed voice.
 * - If no suitable match exists, we silently fall back to the system default voice.
 */
enum class VoiceGender {
    MALE,
    FEMALE,
    /**
     * Fallback / "no preference" mode. Uses system default voice.
     */
    NEUTRAL
}


