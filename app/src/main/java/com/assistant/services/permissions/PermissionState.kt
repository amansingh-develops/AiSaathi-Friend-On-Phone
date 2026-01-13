package com.assistant.services.permissions

/**
 * Tracks the state of a permission request.
 */
data class PermissionState(
    val permissionType: PermissionType,
    val status: Status,
    val lastRequestedTimestamp: Long = 0L,
    val denialCount: Int = 0,
    val permanentlyDenied: Boolean = false
) {
    enum class Status {
        GRANTED,
        DENIED,
        PENDING,
        NOT_REQUESTED
    }
    
    /**
     * Check if we should retry requesting this permission.
     * Avoid aggressive retries if user has denied multiple times.
     */
    fun shouldRetryRequest(): Boolean {
        return !permanentlyDenied && denialCount < 3
    }
    
    /**
     * Check if enough time has passed since last request.
     * Avoid spamming the user with permission requests.
     */
    fun canRequestAgain(minDelayMs: Long = 60_000): Boolean {
        if (lastRequestedTimestamp == 0L) return true
        return System.currentTimeMillis() - lastRequestedTimestamp >= minDelayMs
    }
}
