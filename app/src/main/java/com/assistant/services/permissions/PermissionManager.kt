package com.assistant.services.permissions

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Enhanced permission manager with comprehensive permission support.
 * 
 * Features:
 * - Support for all permission types (Phone, Contacts, SMS, Calendar, Camera, etc.)
 * - Permission state tracking
 * - Callbacks for grant/deny events
 * - Special handling for NotificationListenerService
 */
object PermissionManager {
    private const val TAG = "PermissionManager"
    
    // Permission state tracking
    private val permissionStates = mutableMapOf<PermissionType, PermissionState>()
    
    // Callbacks for permission results
    private val permissionCallbacks = mutableMapOf<Int, PermissionCallback>()
    private var nextRequestCode = 2000
    
    /**
     * Callback for permission request results.
     */
    data class PermissionCallback(
        val permissionType: PermissionType,
        val onGranted: () -> Unit,
        val onDenied: (permanentlyDenied: Boolean) -> Unit
    )
    
    /**
     * Check if a permission is granted.
     */
    fun hasPermission(context: Context, permissionType: PermissionType): Boolean {
        // Special handling for NotificationListenerService
        if (permissionType == PermissionType.NOTIFICATION_LISTENER) {
            return isNotificationListenerEnabled(context)
        }
        
        // Check if permission is applicable for current Android version
        if (!permissionType.isApplicable()) {
            return true // Permission not needed on this Android version
        }
        
        return ContextCompat.checkSelfPermission(
            context,
            permissionType.manifestPermission
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if multiple permissions are granted.
     */
    fun hasPermissions(context: Context, permissionTypes: List<PermissionType>): Boolean {
        return permissionTypes.all { hasPermission(context, it) }
    }
    
    /**
     * Get all granted permissions from a list.
     */
    fun getGrantedPermissions(context: Context, permissionTypes: List<PermissionType>): Set<PermissionType> {
        return permissionTypes.filter { hasPermission(context, it) }.toSet()
    }
    
    /**
     * Get all missing permissions from a list.
     */
    fun getMissingPermissions(context: Context, permissionTypes: List<PermissionType>): List<PermissionType> {
        return permissionTypes.filter { !hasPermission(context, it) && it.isApplicable() }
    }
    
    /**
     * Request a permission with callbacks.
     * 
     * @param activity Activity context for requesting permission
     * @param permissionType Permission to request
     * @param onGranted Called when permission is granted
     * @param onDenied Called when permission is denied (includes permanentlyDenied flag)
     * @return Request code for this permission request
     */
    fun requestPermission(
        activity: Activity,
        permissionType: PermissionType,
        onGranted: () -> Unit,
        onDenied: (permanentlyDenied: Boolean) -> Unit
    ): Int {
        // Special handling for NotificationListenerService
        if (permissionType == PermissionType.NOTIFICATION_LISTENER) {
            Log.i(TAG, "NotificationListenerService requires Settings redirect")
            // Cannot request programmatically, must redirect to Settings
            return -1
        }
        
        // Check if permission is applicable
        if (!permissionType.isApplicable()) {
            Log.i(TAG, "Permission ${permissionType.name} not applicable on this Android version")
            onGranted()
            return -1
        }
        
        // Update permission state
        val currentState = permissionStates[permissionType] ?: PermissionState(
            permissionType = permissionType,
            status = PermissionState.Status.NOT_REQUESTED
        )
        
        permissionStates[permissionType] = currentState.copy(
            status = PermissionState.Status.PENDING,
            lastRequestedTimestamp = System.currentTimeMillis()
        )
        
        // Generate request code and store callback
        val requestCode = nextRequestCode++
        permissionCallbacks[requestCode] = PermissionCallback(permissionType, onGranted, onDenied)
        
        // Request permission
        Log.i(TAG, "Requesting permission: ${permissionType.name}")
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(permissionType.manifestPermission),
            requestCode
        )
        
        return requestCode
    }
    
    /**
     * Handle permission request result.
     * Call this from Activity.onRequestPermissionsResult().
     */
    fun onPermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        activity: Activity
    ) {
        val callback = permissionCallbacks.remove(requestCode) ?: return
        
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission granted
            Log.i(TAG, "Permission granted: ${callback.permissionType.name}")
            permissionStates[callback.permissionType] = permissionStates[callback.permissionType]!!.copy(
                status = PermissionState.Status.GRANTED,
                denialCount = 0
            )
            callback.onGranted()
        } else {
            // Permission denied
            val currentState = permissionStates[callback.permissionType]!!
            val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                callback.permissionType.manifestPermission
            )
            
            Log.w(TAG, "Permission denied: ${callback.permissionType.name}, permanently=$permanentlyDenied")
            permissionStates[callback.permissionType] = currentState.copy(
                status = PermissionState.Status.DENIED,
                denialCount = currentState.denialCount + 1,
                permanentlyDenied = permanentlyDenied
            )
            callback.onDenied(permanentlyDenied)
        }
    }
    
    /**
     * Get permission state.
     */
    fun getPermissionState(permissionType: PermissionType): PermissionState {
        return permissionStates[permissionType] ?: PermissionState(
            permissionType = permissionType,
            status = PermissionState.Status.NOT_REQUESTED
        )
    }
    
    /**
     * Check if NotificationListenerService is enabled.
     */
    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        
        if (flat.isNullOrEmpty()) {
            return false
        }
        
        val names = flat.split(":")
        return names.any { 
            val componentName = ComponentName.unflattenFromString(it)
            componentName?.packageName == packageName
        }
    }
    
    // Legacy methods for backward compatibility
    
    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    
    fun hasCallPhonePermission(context: Context): Boolean {
        return hasPermission(context, PermissionType.CALL_PHONE)
    }
    
    fun hasReadContactsPermission(context: Context): Boolean {
        return hasPermission(context, PermissionType.READ_CONTACTS)
    }
}
