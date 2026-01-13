package com.assistant.services.voice

import com.assistant.domain.model.VoiceGender
import com.assistant.domain.onboarding.OnboardingLanguage

/**
 * Abstraction for TTS/voice playback.
 *
 * Needed so we can swap Android TTS vs ElevenLabs TTS without rewriting the pipeline.
 */
interface VoiceOutput {
    fun setListener(listener: Listener?)

    interface Listener {
        fun onSpeechStarted()
        fun onSpeechEnded()
    }

    fun setVoiceGender(gender: VoiceGender)
    fun setLanguageHint(language: OnboardingLanguage)
    fun speak(text: String)
    fun stop()
    fun shutdown()
}



