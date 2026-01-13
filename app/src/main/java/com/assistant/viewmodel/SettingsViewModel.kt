package com.assistant.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.assistant.data.PreferencesRepository
import com.assistant.domain.ConfirmationStyle
import com.assistant.domain.PlaybackApp
import com.assistant.domain.UserPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: PreferencesRepository
) : ViewModel() {

    // Expose preferences as a StateFlow for the UI
    val preferences: StateFlow<UserPreferences> = repository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserPreferences() // Default initial value
        )

    fun setMusicPlaybackApp(app: PlaybackApp) {
        viewModelScope.launch {
            repository.updateMusicPlaybackApp(app)
        }
    }

    fun setVideoPlaybackApp(app: PlaybackApp) {
        viewModelScope.launch {
            repository.updateVideoPlaybackApp(app)
        }
    }

    fun setAllowAutoLearning(allow: Boolean) {
        viewModelScope.launch {
            repository.updateAllowAutoLearning(allow)
        }
    }
    
    fun setConfirmationStyle(style: ConfirmationStyle) {
        viewModelScope.launch {
            repository.updateConfirmationStyle(style)
        }
    }
}

class SettingsViewModelFactory(private val repository: PreferencesRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
