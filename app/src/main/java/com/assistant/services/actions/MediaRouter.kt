package com.assistant.services.actions

import com.assistant.data.PreferencesRepository
import com.assistant.domain.PlaybackApp
import com.assistant.domain.UserPreferences
import kotlinx.coroutines.flow.first

/**
 * Routes media requests to the appropriate application based on user preferences.
 */
class MediaRouter(
    private val preferencesRepository: PreferencesRepository
) {

    sealed class RouteDecision {
        data class PlayUnknown(val app: PlaybackApp, val query: String) : RouteDecision() // Placeholder for actual intent launch
        data object AskUser : RouteDecision()
    }

    suspend fun routeMusic(query: String): RouteDecision {
        val prefs = preferencesRepository.preferencesFlow.first()
        
        // 1. Explicit Preference
        if (prefs.musicPlaybackApp != PlaybackApp.ASK) {
            return RouteDecision.PlayUnknown(prefs.musicPlaybackApp, query)
        }
        
        // 2. Learned Behavior (if allowed)
        if (prefs.allowAutoLearning && prefs.learnedMusicApp != null) {
            return RouteDecision.PlayUnknown(prefs.learnedMusicApp, query)
        }

        // 3. Fallback / Ask
        return RouteDecision.AskUser
    }

    suspend fun routeVideo(query: String): RouteDecision {
        val prefs = preferencesRepository.preferencesFlow.first()
        
        if (prefs.videoPlaybackApp != PlaybackApp.ASK) {
            return RouteDecision.PlayUnknown(prefs.videoPlaybackApp, query)
        }
        
        return RouteDecision.AskUser
    }
}
