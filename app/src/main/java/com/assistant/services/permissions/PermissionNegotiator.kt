package com.assistant.services.permissions

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.assistant.services.llm.UnifiedLLMClient
import com.assistant.services.tts.TextToSpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Orchestrates LLM-driven permission requests.
 * 
 * Flow:
 * 1. Receive PermissionRequestContext
 * 2. Generate explanation via LLM (Gemini with OpenRouter fallback)
 * 3. Speak explanation via TTS
 * 4. Show Android permission dialog (or redirect to Settings for special permissions)
 * 5. Handle grant/deny callbacks
 */
class PermissionNegotiator(
    private val context: Context,
    private val llmClient: UnifiedLLMClient,
    private val ttsManager: TextToSpeechManager
) {
    companion object {
        private const val TAG = "PermissionNegotiator"
    }
    
    /**
     * Request a permission with LLM-generated explanation.
     * 
     * @param requestContext Context for generating the permission request
     * @param onExplanationGenerated Called when explanation is ready to be spoken
     * @param onReadyToRequestPermission Called when ready to show Android permission dialog
     * @return Generated permission request response, or null if LLM fails
     */
    suspend fun requestPermission(
        requestContext: PermissionRequestContext,
        onExplanationGenerated: (PermissionRequestResponse) -> Unit,
        onReadyToRequestPermission: () -> Unit
    ): PermissionRequestResponse? = withContext(Dispatchers.IO) {
        try {
            // Generate explanation via LLM
            val systemInstruction = PermissionSystemInstructions.getPermissionRequestInstruction(requestContext)
            val llmResponse = llmClient.generateReply(systemInstruction, requestContext.userIntent)
            
            if (llmResponse == null) {
                Log.e(TAG, "LLM failed to generate permission explanation")
                return@withContext null
            }
            
            // Parse JSON response
            val response = parsePermissionRequestResponse(llmResponse)
            if (response == null) {
                Log.e(TAG, "Failed to parse LLM response: $llmResponse")
                return@withContext null
            }
            
            Log.d(TAG, "Generated permission explanation: ${response.explanation}")
            
            // Notify that explanation is ready
            withContext(Dispatchers.Main) {
                onExplanationGenerated(response)
            }
            
            // Speak the explanation
            withContext(Dispatchers.Main) {
                ttsManager.speak(response.explanation)
            }
            
            // Wait for TTS to finish (approximate)
            kotlinx.coroutines.delay(response.explanation.length * 50L) // ~50ms per character
            
            // Notify that we're ready to show permission dialog
            withContext(Dispatchers.Main) {
                onReadyToRequestPermission()
            }
            
            return@withContext response
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during permission negotiation", e)
            return@withContext null
        }
    }
    
    /**
     * Generate a polite fallback response for permission denial.
     */
    suspend fun generateDenialResponse(
        denialContext: PermissionDenialContext
    ): PermissionDenialResponse? = withContext(Dispatchers.IO) {
        try {
            val systemInstruction = PermissionSystemInstructions.getPermissionDenialInstruction(denialContext)
            val llmResponse = llmClient.generateReply(systemInstruction, denialContext.originalIntent)
            
            if (llmResponse == null) {
                Log.e(TAG, "LLM failed to generate denial response")
                return@withContext null
            }
            
            return@withContext parsePermissionDenialResponse(llmResponse)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating denial response", e)
            return@withContext null
        }
    }
    
    /**
     * Open Settings for special permissions (e.g., Notification Listener).
     */
    fun openSettingsForSpecialPermission(permissionType: PermissionType) {
        when (permissionType) {
            PermissionType.NOTIFICATION_LISTENER -> {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            else -> {
                // Open app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        }
    }
    
    /**
     * Parse permission request response from LLM.
     */
    private fun parsePermissionRequestResponse(jsonString: String): PermissionRequestResponse? {
        return try {
            val json = JSONObject(jsonString)
            PermissionRequestResponse(
                explanation = json.getString("explanation"),
                actionSummary = json.getString("actionSummary")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse permission request response", e)
            null
        }
    }
    
    /**
     * Parse permission denial response from LLM.
     */
    private fun parsePermissionDenialResponse(jsonString: String): PermissionDenialResponse? {
        return try {
            val json = JSONObject(jsonString)
            val suggestions = mutableListOf<String>()
            val suggestionsArray = json.optJSONArray("alternativeSuggestions")
            if (suggestionsArray != null) {
                for (i in 0 until suggestionsArray.length()) {
                    suggestions.add(suggestionsArray.getString(i))
                }
            }
            
            PermissionDenialResponse(
                message = json.getString("message"),
                alternativeSuggestions = suggestions
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse permission denial response", e)
            null
        }
    }
}
