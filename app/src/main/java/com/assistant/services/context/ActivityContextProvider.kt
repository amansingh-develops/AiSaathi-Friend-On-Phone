package com.assistant.services.context

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Singleton provider for accessing the current Activity context from services.
 * 
 * This is necessary because permission dialogs require Activity context,
 * but our execution flow runs in services (WakeWordService).
 * 
 * Usage:
 * - MainActivity registers itself on onCreate()
 * - MainActivity unregisters on onDestroy()
 * - Services can get Activity via getActivity()
 */
object ActivityContextProvider {
    private var activityRef: WeakReference<Activity>? = null
    
    /**
     * Register the current Activity.
     * Should be called from Activity.onCreate()
     */
    fun registerActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }
    
    /**
     * Unregister the current Activity.
     * Should be called from Activity.onDestroy()
     */
    fun unregisterActivity() {
        activityRef?.clear()
        activityRef = null
    }
    
    /**
     * Get the current Activity if available.
     * Returns null if no Activity is registered or if it has been garbage collected.
     */
    fun getActivity(): Activity? {
        return activityRef?.get()
    }
    
    /**
     * Check if an Activity is currently available.
     */
    fun isActivityAvailable(): Boolean {
        return getActivity() != null
    }
}
