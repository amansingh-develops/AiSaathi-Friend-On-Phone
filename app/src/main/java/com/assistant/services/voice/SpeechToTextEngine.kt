package com.assistant.services.voice

import com.assistant.domain.onboarding.OnboardingLanguage

/**
 * Abstraction for speech-to-text.
 *
 * Needed so we can swap Android SpeechRecognizer (beeps on some OEMs) vs ElevenLabs STT.
 */
interface SpeechToTextEngine {
    interface Listener {
        fun onReady()
        fun onBeginningOfSpeech()
        fun onPartialText(text: String)
        fun onFinalText(text: String)
        fun onError(code: Int)
        fun onEndOfSpeech()
    }

    fun setListener(listener: Listener?)
    fun warmUp()
    fun startListening(language: OnboardingLanguage, promptContext: String? = null)
    fun stopListening()
    fun cancel()
    fun shutdown()
}



