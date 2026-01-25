package com.assistant.services.camera

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import com.assistant.services.actions.ActionResult
import com.assistant.services.intent.AssistantIntent
import com.assistant.services.permissions.PermissionManager
import com.assistant.services.permissions.PermissionType
import com.assistant.services.voice.VoiceOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CameraController - Handles camera automation for the voice assistant.
 * 
 * Features:
 * - Mode normalization (photo, video, selfie, qr)
 * - Debounce protection against duplicate requests
 * - Device lock detection with pending action storage
 * - Permission handling with resume callback
 * - Safe spoken response fallback (Hinglish)
 * - Session cleanup after success/failure
 * - Analytics logging for debugging
 * 
 * UX Rules:
 * - Always speak before opening camera
 * - Use Hinglish tone
 * - Never open silently
 * - Never fail silently
 */
class CameraController(
    private val context: Context,
    private val voice: VoiceOutput
) {
    companion object {
        private const val TAG = "CameraController"
        private const val DEBOUNCE_MS = 2000L
    }

    // Debounce tracking
    private var lastRequestTime = 0L
    
    // Pending action for resume after unlock/permission
    @Volatile 
    var pendingCameraAction: AssistantIntent.Action.OpenCamera? = null
        private set

    /**
     * Mode normalization - Maps various user inputs to standard modes.
     * 
     * @param raw Raw mode string from LLM
     * @return Normalized mode: "photo", "video", "selfie", or "qr"
     */
    private fun normalizeMode(raw: String?): String = when (raw?.lowercase()?.trim()) {
        "video", "record", "recording", "vid" -> "video"
        "selfie", "front", "self" -> "selfie"
        "qr", "scan", "scanner", "barcode" -> "qr"
        else -> "photo"
    }

    /**
     * Safe spoken response fallback.
     * Returns the LLM's acknowledgement or a minimal generic string.
     */
    private fun getAckText(intent: AssistantIntent.Action.OpenCamera): String {
        return intent.acknowledgement?.takeIf { it.isNotBlank() } ?: "Theek hai."
    }

    /**
     * Main entry point for handling camera open requests.
     * 
     * Performs:
     * 1. Debounce check
     * 2. Device lock check
     * 3. Permission check
     * 4. Camera launch
     */
    suspend fun handle(intent: AssistantIntent.Action.OpenCamera): ActionResult {
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  📷 CAMERA CONTROLLER                                  ║")
        Log.i(TAG, "╠════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║  Mode requested: ${intent.mode ?: "default (photo)"}")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        // 1. Debounce check - prevent duplicate rapid requests
        val now = System.currentTimeMillis()
        if (now - lastRequestTime < DEBOUNCE_MS) {
            Log.w(TAG, "⚠️ Debounced duplicate camera request (within ${DEBOUNCE_MS}ms)")
            return ActionResult.Success
        }
        lastRequestTime = now

        // 2. Device lock check
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isKeyguardLocked == true) {
            Log.w(TAG, "⚠️ Device is locked, storing pending action")
            pendingCameraAction = intent
            voice.speak("Phone unlock kar do yaar, phir camera khol deti hoon.")
            return ActionResult.Success
        }

        // 3. Permission check
        if (!PermissionManager.hasPermission(context, PermissionType.CAMERA)) {
            Log.w(TAG, "⚠️ Camera permission not granted")
            pendingCameraAction = intent
            return ActionResult.AskUser(
                "Camera ke liye permission chahiye yaar. Settings mein jaake allow kar do."
            )
        }

        // 4. Launch camera
        return launchCamera(intent)
    }

    /**
     * Launch the camera with the specified mode.
     */
    private suspend fun launchCamera(intent: AssistantIntent.Action.OpenCamera): ActionResult {
        val mode = normalizeMode(intent.mode)
        val ackText = getAckText(intent)

        Log.i(TAG, "Launching camera: mode=$mode")

        return try {
            withContext(Dispatchers.Main) {
                // Create appropriate intent based on mode
                val cameraIntent = when (mode) {
                    "video" -> Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                    else -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                }

                // Front camera hints for selfie mode
                // Note: These are HINTS only, not guaranteed on all devices
                if (mode == "selfie") {
                    Log.i(TAG, "Adding front camera hints for selfie mode")
                    cameraIntent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    cameraIntent.putExtra("android.intent.extras.CAMERA_FACING", 1) // Front = 1
                    cameraIntent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    cameraIntent.putExtra("camerafacing", 1) // Samsung specific
                }

                // QR mode warning - no standard Android intent for QR scanning
                if (mode == "qr") {
                    Log.w(TAG, "⚠️ QR mode requested but no standard intent exists. Opening photo camera.")
                    voice.speak("QR scanner ke liye Google Lens use karo yaar, abhi normal camera khol rahi hoon.")
                }

                cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                // REQUIRED: Check if camera app exists before launching
                if (cameraIntent.resolveActivity(context.packageManager) != null) {
                    // Speak acknowledgement BEFORE opening camera
                    if (mode != "qr") { // Already spoke for QR
                        voice.speak(ackText)
                    }
                    
                    // Small delay to let TTS start
                    kotlinx.coroutines.delay(300)
                    
                    context.startActivity(cameraIntent)
                    
                    Log.i(TAG, "✅ Camera launched successfully: mode=$mode")
                    
                    // Clear pending action on success
                    pendingCameraAction = null
                    
                    ActionResult.Success
                } else {
                    Log.e(TAG, "❌ No camera app found on device")
                    fail(Exception("No camera app found"))
                }
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    /**
     * Handle failure with logging and user feedback.
     */
    private fun fail(e: Exception): ActionResult {
        Log.e(TAG, "❌ Camera launch failed", e)
        voice.speak("Yaar camera open nahi ho pa raha, manually try kar lo.")
        
        // Clear pending action on failure
        pendingCameraAction = null
        
        return ActionResult.Failure
    }

    /**
     * Resume pending camera action after unlock or permission grant.
     * Call this when device is unlocked or camera permission is granted.
     */
    fun resumePendingAction() {
        val pending = pendingCameraAction
        if (pending != null) {
            Log.i(TAG, "Resuming pending camera action: mode=${pending.mode}")
            GlobalScope.launch(Dispatchers.Main) {
                handle(pending)
            }
        }
    }

    /**
     * Clear pending action without executing.
     * Call this when session ends or user cancels.
     */
    fun clearPending() {
        Log.d(TAG, "Clearing pending camera action")
        pendingCameraAction = null
    }

    /**
     * Check if there's a pending camera action.
     */
    fun hasPendingAction(): Boolean = pendingCameraAction != null
}
