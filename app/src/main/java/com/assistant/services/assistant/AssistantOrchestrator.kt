package com.assistant.services.assistant

import android.util.Log
import com.assistant.domain.model.VoiceGender
import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.services.ack.AcknowledgementManager
import com.assistant.services.listening.ListeningSessionManager

/**
 * Ultra-fast assistant coordinator.
 *
 * Latency philosophy (non-negotiable):
 * - Acknowledge immediately (local phrase bank + on-device TTS).
 * - Start STT in parallel (no waiting).
 * - Start intent understanding in parallel (can be local heuristics + optional remote).
 * - Never stay silent.
 *
 * This class contains NO UI and can be used from services.
 */
class AssistantOrchestrator(
    private val acknowledgementManager: AcknowledgementManager,
    private val listeningSessionManager: ListeningSessionManager
) {
    companion object {
        private const val TAG = "AssistantOrchestrator"
    }

    fun setVoiceGender(gender: VoiceGender) {
        acknowledgementManager.setVoiceGender(gender)
        listeningSessionManager.setVoiceGender(gender)
    }

    fun setLanguage(language: OnboardingLanguage) {
        acknowledgementManager.setLanguage(language)
        listeningSessionManager.setLanguage(language)
    }

    fun setUserProfile(profile: com.assistant.domain.model.UserProfile?) {
        acknowledgementManager.setPreferredName(profile?.preferredName ?: profile?.userName)
        listeningSessionManager.setUserProfile(profile)
    }

    fun setPreferredName(name: String?) {
        acknowledgementManager.setPreferredName(name)
        listeningSessionManager.setPreferredName(name)
    }

    /**
     * Wake-word event entry point.
     *
     * MUST be fast:
     * - Speak acknowledgement immediately
     * - Start / refresh a single listening session in parallel
     */
    fun onWakeWordDetected() {
        Log.d(TAG, "onWakeWordDetected() called - Handing over to ListeningSessionManager")
        // 1) Start listening session immediately.
        // Session Manager handles Sound 1 (Ack) + STT start.
        listeningSessionManager.onWake()
    }

    fun shutdown() {
        listeningSessionManager.shutdown()
    }
}


