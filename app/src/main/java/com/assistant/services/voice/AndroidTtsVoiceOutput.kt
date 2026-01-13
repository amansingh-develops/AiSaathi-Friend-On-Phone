package com.assistant.services.voice

import com.assistant.domain.model.VoiceGender
import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.services.tts.TextToSpeechManager

/**
 * Adapter so existing Android TTS implementation can be used through [VoiceOutput].
 */
class AndroidTtsVoiceOutput(
    private val tts: TextToSpeechManager
) : VoiceOutput {
    override fun setListener(listener: VoiceOutput.Listener?) {
        tts.setListener(if (listener == null) null else object : TextToSpeechManager.Listener {
            override fun onSpeechStarted() = listener.onSpeechStarted()
            override fun onSpeechEnded() = listener.onSpeechEnded()
        })
    }

    override fun setVoiceGender(gender: VoiceGender) = tts.setVoiceGender(gender)
    override fun setLanguageHint(language: OnboardingLanguage) = tts.setLanguageHint(language)
    override fun speak(text: String) = tts.speak(text)
    override fun stop() = tts.stop()
    override fun shutdown() = tts.shutdown()
}



