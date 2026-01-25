package com.assistant.services.rapido

import android.util.Log

/**
 * Manages the multi-turn clarification flow for Rapido ride booking.
 * 
 * Handles state transitions and generates contextual clarification questions
 * based on what information is missing or ambiguous.
 * 
 * ## Design Principles:
 * - NEVER assume destination or vehicle
 * - ALWAYS clarify ambiguity
 * - NEVER hardcode addresses
 * - Keep UX human and friendly (Hinglish)
 * 
 * ## State Machine Flow:
 * ```
 * IDLE
 *   ↓ (startBooking called)
 * WAITING_FOR_DESTINATION (if destination needs clarification)
 *   ↓ (user provides destination)
 * WAITING_FOR_PLACE_TYPE (if destination is ambiguous like "office")
 *   ↓ (user clarifies saved vs new)
 * WAITING_FOR_VEHICLE (if vehicle not provided)
 *   ↓ (user picks bike/cab)
 * READY_TO_EXECUTE
 *   ↓ (execute automation)
 * IDLE
 * ```
 */
class RapidoAutomationManager {
    
    companion object {
        private const val TAG = "RapidoAutoMgr"
        
        // Common place keywords that need saved vs new clarification
        private val SAVED_PLACE_KEYWORDS = listOf("office", "home", "work", "ghar", "घर", "ऑफिस")
        
        // Brand names that need city/area disambiguation
        private val BRAND_KEYWORDS = listOf(
            "microsoft", "google", "amazon", "infosys", "tcs", "wipro", 
            "accenture", "ibm", "oracle", "sap", "adobe", "apple",
            "flipkart", "swiggy", "zomato", "ola", "uber"
        )
    }
    
    private var currentState: RapidoAutomationState = RapidoAutomationState.IDLE
    private var bookingContext: RapidoBookingContext = RapidoBookingContext()
    
    /** Get current state for debugging */
    fun getCurrentState(): RapidoAutomationState = currentState
    
    /** Get current booking context */
    fun getBookingContext(): RapidoBookingContext = bookingContext
    
    /**
     * Start a new booking flow with initial parameters from user's command.
     * 
     * @param destination Raw destination from user (may be incomplete/ambiguous)
     * @param vehicle Vehicle type if specified ("bike", "cab", or null)
     * @return ClarificationResult indicating next action
     */
    fun startBooking(destination: String?, vehicle: String?): ClarificationResult {
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  🚗 STARTING RAPIDO BOOKING FLOW                       ║")
        Log.i(TAG, "╠════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║  Destination: ${destination ?: "NOT PROVIDED"}")
        Log.i(TAG, "║  Vehicle: ${vehicle ?: "NOT PROVIDED"}")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        // Reset state
        bookingContext = RapidoBookingContext(
            destination = destination,
            vehicle = normalizeVehicle(vehicle),
            originalDestination = destination
        )
        
        return evaluateNextStep()
    }
    
    /**
     * Process user's response to a clarification question.
     * Updates booking context and transitions state accordingly.
     * 
     * @param userResponse User's text response (in any language/format)
     * @return ClarificationResult indicating next action
     */
    fun processUserResponse(userResponse: String): ClarificationResult {
        val response = userResponse.trim().lowercase()
        Log.i(TAG, "┌─────────────────────────────────────────────────────────")
        Log.i(TAG, "│ 📝 Processing user response: '$response'")
        Log.i(TAG, "│ 📊 Current state: $currentState")
        Log.i(TAG, "└─────────────────────────────────────────────────────────")
        
        return when (currentState) {
            RapidoAutomationState.WAITING_FOR_DESTINATION -> {
                handleDestinationResponse(response, userResponse)
            }
            RapidoAutomationState.WAITING_FOR_PLACE_TYPE -> {
                handlePlaceTypeResponse(response, userResponse)
            }
            RapidoAutomationState.WAITING_FOR_VEHICLE -> {
                handleVehicleResponse(response)
            }
            else -> {
                Log.w(TAG, "processUserResponse called in unexpected state: $currentState")
                evaluateNextStep()
            }
        }
    }
    
    /**
     * Check if all parameters are collected and ready to execute.
     */
    fun isReadyToExecute(): Boolean {
        val ready = currentState == RapidoAutomationState.READY_TO_EXECUTE
        Log.d(TAG, "isReadyToExecute: $ready (state=$currentState)")
        return ready
    }
    
    /**
     * Get the final resolved booking context for execution.
     * Should only be called when isReadyToExecute() returns true.
     */
    fun getResolvedContext(): RapidoBookingContext {
        if (!isReadyToExecute()) {
            Log.e(TAG, "getResolvedContext called when not ready! State: $currentState")
        }
        return bookingContext
    }
    
    /**
     * Reset the manager to initial state.
     */
    fun reset() {
        Log.i(TAG, "🔄 Resetting RapidoAutomationManager to IDLE")
        currentState = RapidoAutomationState.IDLE
        bookingContext = RapidoBookingContext()
    }
    
    // ==================== Private Helper Methods ====================
    
    /**
     * Evaluate current context and determine next step.
     * Core state machine logic.
     */
    private fun evaluateNextStep(): ClarificationResult {
        Log.d(TAG, "evaluateNextStep: context=$bookingContext")
        
        val destination = bookingContext.destination
        val vehicle = bookingContext.vehicle
        
        // Step 1: Check if destination needs clarification
        if (destination.isNullOrBlank()) {
            currentState = RapidoAutomationState.WAITING_FOR_DESTINATION
            Log.i(TAG, "→ State: WAITING_FOR_DESTINATION (no destination)")
            return ClarificationResult.NeedsClarification(
                question = "Kahan jana hai yaar? Destination bata do.",
                state = currentState
            )
        }
        
        // Step 2: Check if destination is a saved place keyword
        if (isSavedPlaceKeyword(destination) && bookingContext.isSavedPlace == null) {
            currentState = RapidoAutomationState.WAITING_FOR_PLACE_TYPE
            Log.i(TAG, "→ State: WAITING_FOR_PLACE_TYPE (saved place keyword detected)")
            return ClarificationResult.NeedsClarification(
                question = "Saved $destination wale address pe hi jana hai ya kisi aur $destination?",
                state = currentState
            )
        }
        
        // Step 3: Check if destination contains brand name needing disambiguation
        val brand = findBrandKeyword(destination)
        if (brand != null && bookingContext.locationContext.isNullOrBlank()) {
            currentState = RapidoAutomationState.WAITING_FOR_DESTINATION
            Log.i(TAG, "→ State: WAITING_FOR_DESTINATION (brand disambiguation for '$brand')")
            return ClarificationResult.NeedsClarification(
                question = "Kaunsa $brand? City ya area bata do.",
                state = currentState
            )
        }
        
        // Step 4: Check if vehicle is missing
        if (vehicle.isNullOrBlank()) {
            currentState = RapidoAutomationState.WAITING_FOR_VEHICLE
            Log.i(TAG, "→ State: WAITING_FOR_VEHICLE")
            return ClarificationResult.NeedsClarification(
                question = "Bike chahiye ya cab?",
                state = currentState
            )
        }
        
        // All parameters collected!
        currentState = RapidoAutomationState.READY_TO_EXECUTE
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  ✅ ALL PARAMETERS COLLECTED - READY TO EXECUTE        ║")
        Log.i(TAG, "║  Final: ${bookingContext.getFinalDestination()} via $vehicle")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        return ClarificationResult.Ready(bookingContext)
    }
    
    /**
     * Handle response when waiting for destination info.
     */
    private fun handleDestinationResponse(responseLower: String, originalResponse: String): ClarificationResult {
        Log.d(TAG, "handleDestinationResponse: '$originalResponse'")
        
        // Check if this is location context for a brand (e.g., "Noida wala")
        val currentDest = bookingContext.destination
        if (currentDest != null && findBrandKeyword(currentDest) != null) {
            // User is providing city/area context
            val locationContext = extractLocationContext(originalResponse)
            bookingContext = bookingContext.copy(
                locationContext = locationContext,
                destination = "${bookingContext.originalDestination} $locationContext"
            )
            Log.i(TAG, "Added location context: '$locationContext'. New dest: '${bookingContext.destination}'")
        } else {
            // User is providing a new destination
            bookingContext = bookingContext.copy(
                destination = originalResponse.trim(),
                originalDestination = originalResponse.trim()
            )
            Log.i(TAG, "Set new destination: '${bookingContext.destination}'")
        }
        
        return evaluateNextStep()
    }
    
    /**
     * Handle response when clarifying saved vs new place.
     */
    private fun handlePlaceTypeResponse(responseLower: String, originalResponse: String): ClarificationResult {
        Log.d(TAG, "handlePlaceTypeResponse: '$originalResponse'")
        
        // Check for affirmative responses (saved place)
        val isSaved = isAffirmativeResponse(responseLower)
        
        if (isSaved) {
            bookingContext = bookingContext.copy(isSavedPlace = true)
            Log.i(TAG, "User confirmed: SAVED place")
        } else {
            // User wants a different place - extract new destination
            bookingContext = bookingContext.copy(
                isSavedPlace = false,
                destination = originalResponse.trim(),
                originalDestination = originalResponse.trim()
            )
            Log.i(TAG, "User wants different place: '${bookingContext.destination}'")
        }
        
        return evaluateNextStep()
    }
    
    /**
     * Handle response when waiting for vehicle type.
     */
    private fun handleVehicleResponse(responseLower: String): ClarificationResult {
        Log.d(TAG, "handleVehicleResponse: '$responseLower'")
        
        val vehicle = when {
            responseLower.contains("bike") || responseLower.contains("बाइक") -> "bike"
            responseLower.contains("cab") || responseLower.contains("auto") || 
            responseLower.contains("कैब") || responseLower.contains("ऑटो") -> "cab"
            else -> {
                Log.w(TAG, "Could not understand vehicle type from: '$responseLower'")
                // Default to bike if unclear
                "bike"
            }
        }
        
        bookingContext = bookingContext.copy(vehicle = vehicle)
        Log.i(TAG, "Vehicle set to: $vehicle")
        
        return evaluateNextStep()
    }
    
    /**
     * Check if destination matches a saved place keyword.
     */
    private fun isSavedPlaceKeyword(destination: String): Boolean {
        val lower = destination.lowercase()
        return SAVED_PLACE_KEYWORDS.any { lower.contains(it) }
    }
    
    /**
     * Find any brand keyword in the destination.
     */
    private fun findBrandKeyword(destination: String): String? {
        val lower = destination.lowercase()
        return BRAND_KEYWORDS.find { lower.contains(it) }
    }
    
    /**
     * Normalize vehicle type input to standard format.
     */
    private fun normalizeVehicle(vehicle: String?): String? {
        if (vehicle == null) return null
        val lower = vehicle.lowercase()
        return when {
            lower.contains("bike") || lower.contains("बाइक") -> "bike"
            lower.contains("cab") || lower.contains("auto") || 
            lower.contains("कैब") || lower.contains("ऑटो") -> "cab"
            else -> vehicle
        }
    }
    
    /**
     * Check if response is affirmative (yes, saved, etc.)
     */
    private fun isAffirmativeResponse(response: String): Boolean {
        val affirmatives = listOf(
            "yes", "yeah", "yep", "ok", "okay", "sure", "haan", "ha", "hnji",
            "saved", "save", "wahi", "wohi", "same", "usme", "ussi",
            "हां", "हाँ", "ठीक", "सेव", "वही", "उसी"
        )
        return affirmatives.any { response.contains(it) }
    }
    
    /**
     * Extract location context from user's response.
     */
    private fun extractLocationContext(response: String): String {
        // Remove common filler words
        val fillers = listOf("wala", "wali", "ka", "ki", "ke", "me", "mein", "का", "की", "के", "में", "वाला", "वाली")
        var cleaned = response.lowercase()
        fillers.forEach { cleaned = cleaned.replace(it, "") }
        return cleaned.trim().replaceFirstChar { it.uppercase() }
    }
    
    /**
     * Result of a clarification step.
     */
    sealed class ClarificationResult {
        /** Need more information from user */
        data class NeedsClarification(
            val question: String,
            val state: RapidoAutomationState
        ) : ClarificationResult()
        
        /** All info collected, ready to execute */
        data class Ready(
            val context: RapidoBookingContext
        ) : ClarificationResult()
    }
}
