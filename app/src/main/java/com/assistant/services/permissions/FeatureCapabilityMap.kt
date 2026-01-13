package com.assistant.services.permissions

import com.assistant.services.intent.AssistantIntent

/**
 * Maps features (intents/actions) to required permissions.
 * This is the central authority for determining what permissions are needed for each action.
 */
object FeatureCapabilityMap {
    
    /**
     * Get all required permissions for an intent.
     */
    fun getRequiredPermissions(intent: AssistantIntent): List<PermissionType> {
        return when (intent) {
            is AssistantIntent.Action.CallContact -> listOf(
                PermissionType.CALL_PHONE,
                PermissionType.READ_CONTACTS
            )
            
            is AssistantIntent.Action.PlayMedia -> {
                // Media playback requires audio settings and notification listener for real control
                listOf(
                    PermissionType.MODIFY_AUDIO_SETTINGS,
                    PermissionType.NOTIFICATION_LISTENER
                )
            }
            
            is AssistantIntent.Action.SetAlarm -> listOf(
                PermissionType.WRITE_CALENDAR
            )
            
            is AssistantIntent.Action.UpdateSetting -> {
                // Settings changes may require audio permissions
                when (intent.settingType.lowercase()) {
                    "volume", "audio" -> listOf(PermissionType.MODIFY_AUDIO_SETTINGS)
                    else -> emptyList()
                }
            }
            
            // Chat and clarification don't require special permissions
            is AssistantIntent.Chat,
            is AssistantIntent.Clarify,
            is AssistantIntent.Unknown,
            is AssistantIntent.Action.StopListeningSession,
            is AssistantIntent.Action.OpenSettings -> emptyList()
        }
    }
    
    /**
     * Get missing permissions for an intent given current context.
     */
    fun getMissingPermissions(
        intent: AssistantIntent,
        grantedPermissions: Set<PermissionType>
    ): List<PermissionType> {
        val required = getRequiredPermissions(intent)
        return required.filter { it !in grantedPermissions && it.isApplicable() }
    }
    
    /**
     * Check if all required permissions are granted for an intent.
     */
    fun hasAllPermissions(
        intent: AssistantIntent,
        grantedPermissions: Set<PermissionType>
    ): Boolean {
        return getMissingPermissions(intent, grantedPermissions).isEmpty()
    }
    
    /**
     * Get optional permissions that enhance functionality but aren't strictly required.
     */
    fun getOptionalPermissions(intent: AssistantIntent): List<PermissionType> {
        return when (intent) {
            is AssistantIntent.Action.CallContact -> listOf(
                PermissionType.READ_CALL_LOG, // For redial, recent calls
                PermissionType.READ_PHONE_STATE // For call state detection
            )
            
            is AssistantIntent.Action.PlayMedia -> {
                // Bluetooth for audio routing to earbuds
                listOfNotNull(
                    PermissionType.BLUETOOTH_CONNECT.takeIf { it.isApplicable() }
                )
            }
            
            else -> emptyList()
        }
    }
}

/**
 * Represents permission requirements for a feature.
 */
data class PermissionRequirement(
    val required: List<PermissionType>,
    val optional: List<PermissionType> = emptyList()
) {
    /**
     * Check if the feature is available (all required permissions granted).
     */
    fun isAvailable(grantedPermissions: Set<PermissionType>): Boolean {
        return required.all { it in grantedPermissions || !it.isApplicable() }
    }
    
    /**
     * Check if the feature is fully enabled (all permissions granted).
     */
    fun isFullyEnabled(grantedPermissions: Set<PermissionType>): Boolean {
        val allPermissions = required + optional
        return allPermissions.all { it in grantedPermissions || !it.isApplicable() }
    }
}
