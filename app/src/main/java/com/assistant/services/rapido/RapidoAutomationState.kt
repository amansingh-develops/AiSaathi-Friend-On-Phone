package com.assistant.services.rapido

import android.util.Log

/**
 * State machine for Rapido ride-booking automation.
 * 
 * Flow: IDLE → WAITING_FOR_DESTINATION → WAITING_FOR_VEHICLE → WAITING_FOR_PLACE_TYPE → READY_TO_EXECUTE
 */
enum class RapidoAutomationState {
    /** No active booking request */
    IDLE,
    
    /** Waiting for user to provide/clarify destination */
    WAITING_FOR_DESTINATION,
    
    /** Waiting for user to specify vehicle type (bike/cab) */
    WAITING_FOR_VEHICLE,
    
    /** Waiting for user to confirm if saved place or specify location details */
    WAITING_FOR_PLACE_TYPE,
    
    /** All parameters collected, ready to execute automation */
    READY_TO_EXECUTE
}

/**
 * Context holding all collected booking parameters.
 * Builds up as user provides information through multi-turn dialog.
 */
data class RapidoBookingContext(
    /** Final resolved destination to type into Rapido */
    val destination: String? = null,
    
    /** Vehicle type: "bike" or "cab" (auto) */
    val vehicle: String? = null,
    
    /** Whether user wants to use a saved/favorite place */
    val isSavedPlace: Boolean? = null,
    
    /** Original destination from first request (before disambiguation) */
    val originalDestination: String? = null,
    
    /** Additional location context (city/area for brand disambiguation) */
    val locationContext: String? = null
) {
    companion object {
        private const val TAG = "RapidoBookingCtx"
    }
    
    /** Check if destination is resolved (either saved place confirmed or full address built) */
    fun isDestinationResolved(): Boolean {
        val resolved = !destination.isNullOrBlank()
        Log.d(TAG, "isDestinationResolved: $resolved (destination='$destination')")
        return resolved
    }
    
    /** Check if vehicle type is set */
    fun isVehicleResolved(): Boolean {
        val resolved = !vehicle.isNullOrBlank()
        Log.d(TAG, "isVehicleResolved: $resolved (vehicle='$vehicle')")
        return resolved
    }
    
    /** Check if all required parameters are collected */
    fun isComplete(): Boolean {
        val complete = isDestinationResolved() && isVehicleResolved()
        Log.d(TAG, "isComplete: $complete")
        return complete
    }
    
    /** Get the final destination string to use in Rapido */
    fun getFinalDestination(): String {
        return when {
            isSavedPlace == true && !originalDestination.isNullOrBlank() -> {
                // Use the original destination name for saved places (e.g., "office")
                Log.d(TAG, "Using saved place destination: $originalDestination")
                originalDestination
            }
            !locationContext.isNullOrBlank() && !destination.isNullOrBlank() -> {
                // Combine destination with location context (e.g., "Microsoft Office Noida")
                val combined = "$destination $locationContext"
                Log.d(TAG, "Using combined destination: $combined")
                combined
            }
            !destination.isNullOrBlank() -> {
                Log.d(TAG, "Using plain destination: $destination")
                destination
            }
            else -> {
                Log.e(TAG, "getFinalDestination called with no destination set!")
                ""
            }
        }
    }
    
    override fun toString(): String {
        return "RapidoBookingContext(dest='$destination', vehicle='$vehicle', " +
               "isSaved=$isSavedPlace, original='$originalDestination', location='$locationContext')"
    }
}
