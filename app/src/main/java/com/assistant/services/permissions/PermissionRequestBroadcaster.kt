package com.assistant.services.permissions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Broadcasts permission request events to MainActivity.
 * This allows background services to request permissions through the UI.
 */
object PermissionRequestBroadcaster {
    private const val TAG = "PermissionRequestBroadcaster"
    
    const val ACTION_REQUEST_PERMISSION = "com.assistant.REQUEST_PERMISSION"
    const val EXTRA_PERMISSION = "permission"
    const val EXTRA_REASON = "reason"
    
    /**
     * Request a permission from the UI.
     * This sends a broadcast that MainActivity will receive and handle.
     */
    fun requestPermission(context: Context, permission: String, reason: String) {
        Log.i(TAG, "Broadcasting permission request: $permission")
        val intent = Intent(ACTION_REQUEST_PERMISSION).apply {
            putExtra(EXTRA_PERMISSION, permission)
            putExtra(EXTRA_REASON, reason)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }
    
    /**
     * Request CALL_PHONE permission.
     */
    fun requestCallPhonePermission(context: Context) {
        requestPermission(
            context,
            android.Manifest.permission.CALL_PHONE,
            "To automatically place calls for you"
        )
    }
    
    /**
     * Register a receiver to listen for permission requests.
     */
    fun registerReceiver(context: Context, onPermissionRequested: (String, String) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_REQUEST_PERMISSION) {
                    val permission = intent.getStringExtra(EXTRA_PERMISSION) ?: return
                    val reason = intent.getStringExtra(EXTRA_REASON) ?: ""
                    onPermissionRequested(permission, reason)
                }
            }
        }
        
        val filter = IntentFilter(ACTION_REQUEST_PERMISSION)
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)
        return receiver
    }
    
    /**
     * Unregister a receiver.
     */
    fun unregisterReceiver(context: Context, receiver: BroadcastReceiver) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
    }
}
