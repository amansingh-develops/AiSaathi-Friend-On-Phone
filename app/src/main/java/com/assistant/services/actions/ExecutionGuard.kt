package com.assistant.services.actions

import android.app.Activity
import android.content.Context
import android.util.Log
import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.services.intent.AssistantIntent
import com.assistant.services.llm.UnifiedLLMClient
import com.assistant.services.permissions.*
import com.assistant.services.tts.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Guards action execution by checking permissions and queuing pending actions.
 * 
 * Flow:
 * 1. Check required permissions
 * 2. If all granted: Execute immediately
 * 3. If missing: Queue action, invoke PermissionNegotiator
 * 4. On permission grant: Dequeue and execute
 * 5. On permission deny: Clear queue, speak fallback
 */
class ExecutionGuard(
    private val context: Context,
    private val permissionNegotiator: PermissionNegotiator,
    private val ttsManager: TextToSpeechManager,
    private val userLanguage: OnboardingLanguage
) {
    companion object {
        private const val TAG = "ExecutionGuard"
    }
    
    // Queue of pending actions awaiting permissions
    private val pendingActions = mutableMapOf<PermissionType, PendingAction>()
    
    data class PendingAction(
        val intent: AssistantIntent,
        val originalUserText: String,
        val confidence: Float,
        val onExecute: () -> Unit
    )
    
    /**
     * Check permissions and execute action.
     * 
     * @param intent The intent to execute
     * @param originalUserText Original user request text
     * @param confidence Confidence score (0.0 to 1.0)
     * @param activity Activity context for requesting permissions (optional)
     * @param conversationContext Optional conversation context for LLM-aware permission requests
     * @param onExecute Callback to execute the action
     */
    fun checkAndExecute(
        intent: AssistantIntent,
        originalUserText: String,
        confidence: Float,
        activity: Activity?,
        conversationContext: String? = null,
        onExecute: () -> Unit
    ) {
        // Get required permissions
        val requiredPermissions = FeatureCapabilityMap.getRequiredPermissions(intent)
        
        if (requiredPermissions.isEmpty()) {
            // No permissions required, execute immediately
            Log.d(TAG, "No permissions required, executing immediately")
            onExecute()
            return
        }
        
        // Check which permissions are missing
        val missingPermissions = PermissionManager.getMissingPermissions(context, requiredPermissions)
        
        if (missingPermissions.isEmpty()) {
            // All permissions granted, execute immediately
            Log.d(TAG, "All permissions granted, executing immediately")
            onExecute()
            return
        }
        
        // Missing permissions - need to request
        Log.i(TAG, "Missing permissions: ${missingPermissions.map { it.name }}")
        
        if (activity == null) {
            Log.e(TAG, "Cannot request permissions without Activity context")
            speakError("I need additional permissions to do this. Please open the app.")
            return
        }
        
        // Request the first missing permission
        val permissionToRequest = missingPermissions.first()
        requestPermissionAndQueue(
            permissionToRequest,
            intent,
            originalUserText,
            confidence,
            activity,
            conversationContext,
            onExecute
        )
    }
    
    /**
     * Request a permission and queue the action.
     */
    private fun requestPermissionAndQueue(
        permissionType: PermissionType,
        intent: AssistantIntent,
        originalUserText: String,
        confidence: Float,
        activity: Activity,
        conversationContext: String?,
        onExecute: () -> Unit
    ) {
        // Queue the action
        pendingActions[permissionType] = PendingAction(intent, originalUserText, confidence, onExecute)
        
        // Generate permission request context
        val requestContext = PermissionRequestContext(
            missingPermission = permissionType,
            userIntent = originalUserText,
            normalizedIntent = intent.toString(),
            userLanguage = userLanguage,
            confidence = confidence,
            conversationContext = conversationContext  // Pass session context to LLM
        )
        
        // Request permission via negotiator
        CoroutineScope(Dispatchers.Main).launch {
            val response = permissionNegotiator.requestPermission(
                requestContext = requestContext,
                onExplanationGenerated = { permissionResponse ->
                    Log.d(TAG, "Permission explanation: ${permissionResponse.explanation}")
                },
                onReadyToRequestPermission = {
                    // Show Android permission dialog or redirect to Settings
                    if (permissionType == PermissionType.NOTIFICATION_LISTENER) {
                        // Special handling: redirect to Settings
                        permissionNegotiator.openSettingsForSpecialPermission(permissionType)
                    } else {
                        // Request permission normally
                        PermissionManager.requestPermission(
                            activity = activity,
                            permissionType = permissionType,
                            onGranted = {
                                handlePermissionGranted(permissionType)
                            },
                            onDenied = { permanentlyDenied ->
                                handlePermissionDenied(permissionType, originalUserText, permanentlyDenied)
                            }
                        )
                    }
                }
            )
            
            if (response == null) {
                Log.e(TAG, "Failed to generate permission request")
                speakError("I couldn't request the permission. Please try again.")
                pendingActions.remove(permissionType)
            }
        }
    }
    
    /**
     * Handle permission granted.
     */
    private fun handlePermissionGranted(permissionType: PermissionType) {
        Log.i(TAG, "Permission granted: ${permissionType.name}")
        
        val pendingAction = pendingActions.remove(permissionType)
        if (pendingAction != null) {
            Log.d(TAG, "Executing pending action after permission grant")
            pendingAction.onExecute()
        }
    }
    
    /**
     * Handle permission denied.
     */
    private fun handlePermissionDenied(
        permissionType: PermissionType,
        originalUserText: String,
        permanentlyDenied: Boolean
    ) {
        Log.w(TAG, "Permission denied: ${permissionType.name}, permanently=$permanentlyDenied")
        
        // Remove pending action
        pendingActions.remove(permissionType)
        
        // Generate polite fallback response
        val denialContext = PermissionDenialContext(
            deniedPermission = permissionType,
            originalIntent = originalUserText,
            userLanguage = userLanguage
        )
        
        CoroutineScope(Dispatchers.Main).launch {
            val response = permissionNegotiator.generateDenialResponse(denialContext)
            if (response != null) {
                ttsManager.speak(response.message)
            } else {
                // Fallback if LLM fails
                speakError("I can't do this without permission.")
            }
        }
    }
    
    /**
     * Speak an error message.
     */
    private fun speakError(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            ttsManager.speak(message)
        }
    }
    
    /**
     * Clear all pending actions (e.g., on session end).
     */
    fun clearPendingActions() {
        pendingActions.clear()
    }
}
