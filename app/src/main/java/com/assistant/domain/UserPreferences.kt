package com.assistant.domain

import com.assistant.domain.model.VoiceGender

/**
 * Manual user preferences for media playback and behavior.
 */
data class UserPreferences(
    val musicPlaybackApp: PlaybackApp = PlaybackApp.ASK,
    val videoPlaybackApp: PlaybackApp = PlaybackApp.YOUTUBE,
    val allowAutoLearning: Boolean = true,
    val confirmationStyle: ConfirmationStyle = ConfirmationStyle.MINIMAL,
    val voiceGender: VoiceGender = VoiceGender.FEMALE,
    val learnedMusicApp: PlaybackApp? = null,
    val learnedVideoApp: PlaybackApp? = null
)

enum class PlaybackApp {
    SPOTIFY,
    YOUTUBE,
    ASK
}

enum class ConfirmationStyle {
    MINIMAL, // "Done."
    FRIENDLY // "Okay, playing that for you!"
}
