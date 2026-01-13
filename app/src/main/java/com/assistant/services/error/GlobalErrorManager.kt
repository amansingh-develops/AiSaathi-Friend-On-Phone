package com.assistant.services.error

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GlobalErrorManager {
    
    data class ErrorState(
        val message: String,
        val type: ErrorType,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    enum class ErrorType {
        API_LIMIT,
        AUTH_ERROR,
        NETWORK_ERROR,
        UNKNOWN
    }
    
    private val _errorFlow = MutableStateFlow<ErrorState?>(null)
    val errorFlow: StateFlow<ErrorState?> = _errorFlow.asStateFlow()

    fun reportError(message: String, type: ErrorType) {
        _errorFlow.value = ErrorState(message, type)
    }

    fun clearError() {
        _errorFlow.value = null
    }
}
