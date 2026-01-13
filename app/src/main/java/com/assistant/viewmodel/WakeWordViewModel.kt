package com.assistant.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assistant.debug.AgentDebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * ViewModel for managing wake word detection state.
 * 
 * Listens to broadcast events from WakeWordService to update UI
 * when wake word is detected.
 */
class WakeWordViewModel : ViewModel() {
    
    private val _isWakeWordDetected = MutableStateFlow(false)
    val isWakeWordDetected: StateFlow<Boolean> = _isWakeWordDetected.asStateFlow()
    
    private var broadcastReceiver: BroadcastReceiver? = null
    
    /**
     * Register broadcast receiver to listen for wake word detection events.
     */
    fun registerReceiver(context: Context) {
        if (broadcastReceiver != null) return
        
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WAKE_WORD_DETECTED_ACTION) {
                    // #region agent log
                    AgentDebugLog.log(
                        context = context,
                        runId = "run2",
                        hypothesisId = "H9",
                        location = "WakeWordViewModel.kt:onReceive",
                        message = "UI received wake word broadcast",
                        data = mapOf(
                            "action" to (intent.action ?: "null"),
                            "extraWakeWord" to intent.getStringExtra("wake_word")
                        )
                    )
                    // #endregion
                    _isWakeWordDetected.value = true
                    
                    // Reset after a short delay (for visual feedback)
                    viewModelScope.launch {
                        delay(2000) // Glow for 2 seconds
                        _isWakeWordDetected.value = false
                    }
                }
            }
        }
        
        val filter = IntentFilter(WAKE_WORD_DETECTED_ACTION)
        
        // On Android 13+ (API 33+), must specify RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
        // Using RECEIVER_NOT_EXPORTED since this is for internal broadcasts only
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                broadcastReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(broadcastReceiver, filter)
        }
    }
    
    /**
     * Unregister broadcast receiver.
     */
    fun unregisterReceiver(context: Context) {
        broadcastReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Receiver might not be registered
            }
            broadcastReceiver = null
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        broadcastReceiver = null
    }
    
    companion object {
        const val WAKE_WORD_DETECTED_ACTION = "com.assistant.WAKE_WORD_DETECTED"
    }
}
