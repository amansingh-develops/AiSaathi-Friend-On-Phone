package com.assistant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.assistant.domain.ConfirmationStyle
import com.assistant.domain.PlaybackApp
import com.assistant.domain.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_preferences")

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val MUSIC_PLAYBACK_APP = stringPreferencesKey("music_playback_app")
        val VIDEO_PLAYBACK_APP = stringPreferencesKey("video_playback_app")
        val ALLOW_AUTO_LEARNING = booleanPreferencesKey("allow_auto_learning")
        val CONFIRMATION_STYLE = stringPreferencesKey("confirmation_style")
        val VOICE_GENDER = stringPreferencesKey("voice_gender")
        val LEARNED_MUSIC_APP = stringPreferencesKey("learned_music_app")
        val LEARNED_VIDEO_APP = stringPreferencesKey("learned_video_app")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data
        .map { preferences ->
            UserPreferences(
                musicPlaybackApp = PlaybackApp.valueOf(
                    preferences[Keys.MUSIC_PLAYBACK_APP] ?: PlaybackApp.ASK.name // Changed default to ASK as per requirement implied "Explicit > Learned"
                ),
                videoPlaybackApp = PlaybackApp.valueOf(
                    preferences[Keys.VIDEO_PLAYBACK_APP] ?: PlaybackApp.YOUTUBE.name
                ),
                allowAutoLearning = preferences[Keys.ALLOW_AUTO_LEARNING] ?: true,
                confirmationStyle = ConfirmationStyle.valueOf(
                    preferences[Keys.CONFIRMATION_STYLE] ?: ConfirmationStyle.MINIMAL.name
                ),
                voiceGender = com.assistant.domain.model.VoiceGender.valueOf(
                    preferences[Keys.VOICE_GENDER] ?: com.assistant.domain.model.VoiceGender.FEMALE.name
                ),
                learnedMusicApp = preferences[Keys.LEARNED_MUSIC_APP]?.let { PlaybackApp.valueOf(it) },
                learnedVideoApp = preferences[Keys.LEARNED_VIDEO_APP]?.let { PlaybackApp.valueOf(it) }
            )
        }

    suspend fun updateMusicPlaybackApp(app: PlaybackApp) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MUSIC_PLAYBACK_APP] = app.name
        }
    }

    suspend fun updateVideoPlaybackApp(app: PlaybackApp) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VIDEO_PLAYBACK_APP] = app.name
        }
    }

    suspend fun updateAllowAutoLearning(allow: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ALLOW_AUTO_LEARNING] = allow
        }
    }

    suspend fun updateConfirmationStyle(style: ConfirmationStyle) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONFIRMATION_STYLE] = style.name
        }
    }
    
    suspend fun updateLearnedMusicApp(app: PlaybackApp) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LEARNED_MUSIC_APP] = app.name
        }
    }
    
    suspend fun updateLearnedVideoApp(app: PlaybackApp) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LEARNED_VIDEO_APP] = app.name
        }
    }

    suspend fun updateVoiceGender(gender: com.assistant.domain.model.VoiceGender) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VOICE_GENDER] = gender.name
        }
    }
}
