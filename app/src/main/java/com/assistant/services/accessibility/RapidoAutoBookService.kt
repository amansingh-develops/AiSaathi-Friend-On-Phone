package com.assistant.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.assistant.services.rapido.RapidoBookingContext
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Accessibility service to automate Rapido ride booking.
 * 
 * ## Automation Flow:
 * 1. Launch Rapido app (handled by ActionExecutor)
 * 2. [THIS SERVICE] Wait for home screen
 * 3. Find & tap "Where to?" input field
 * 4. Type resolved destination
 * 5. Wait for suggestions to load
 * 6. Select first suggestion from dropdown
 * 7. Wait for ride options screen
 * 8. Select vehicle type (Bike or Auto)
 * 9. Select CASH payment
 * 10. Click "Book Ride" / "Confirm" button
 * 
 * ## Design Principles:
 * - Text-based node matching (resilient to UI changes)
 * - ContentDescription matching as fallback
 * - 2 retries per step
 * - 20 seconds total timeout
 * - NEVER hardcode screen coordinates
 * 
 * IMPORTANT: User must enable this service in Settings > Accessibility
 */
class RapidoAutoBookService : AccessibilityService() {

    companion object {
        private const val TAG = "RapidoAutoBook"
        private const val RAPIDO_PACKAGE = "com.rapido.passenger"
        
        // Timing constants
        private const val INITIAL_DELAY_MS = 2000L      // Wait for app to fully load
        private const val STEP_DELAY_MS = 1500L         // Delay between automation steps
        private const val TYPING_DELAY_MS = 500L        // Delay after typing
        private const val SCREEN_TRANSITION_MS = 1000L  // Wait for screen to load after tap
        private const val MAX_RETRIES = 3               // Maximum retry attempts per step (increased)
        private const val TOTAL_TIMEOUT_MS = 30000L     // Total automation timeout (increased)
        
        var isEnabled = false
            private set
        
        /** Pending booking context set by ActionExecutor before launching Rapido */
        @Volatile
        var pendingBooking: RapidoBookingContext? = null
        
        /** Callback when automation completes */
        var onAutomationComplete: ((success: Boolean, message: String) -> Unit)? = null
    }
    
    // UI Element patterns for Rapido app (text-based matching)
    // Based on Rapido app UI variations (may change with app updates)
    private val whereToPatterns = listOf(
        // CRITICAL: These patterns now focus on contentDescription matching
        "Where are you going?", "Where are you going",  // Exact match from debug dump!
        "Where to?", "where to", "Where to",
        "Destination", "destination", "DESTINATION",
        "Enter destination", "Enter Destination", "enter destination",
        "Search for destination", "Search destination", 
        "Search for places", "Search places",
        "Where would you like to go", "Enter drop location",
        "Drop location",  // Keep this but validate size
        "Going to", "Book a ride", "Book Ride", "Get a ride",
        // Hindi variations  
        "कहाँ जाना है", "गंतव्य", "कहां जाना है",
        "ड्रॉप लोकेशन", "कहाँ"
        // NOTE: Removed generic "Drop", "Search" to avoid matching screen containers
    )
    
    private val bikePatterns = listOf(
        "Bike", "bike", "BIKE", "Bike Taxi", "Rapido Bike", "बाइक"
    )
    
    private val autoPatterns = listOf(
        "Auto", "auto", "AUTO", "Rapido Auto", "ऑटो", "Cab"
    )
    
    private val bookPatterns = listOf(
        "Book", "book", "BOOK", "Confirm", "confirm", "CONFIRM",
        "Book Ride", "Book Now", "Confirm Ride", "बुक करें"
    )
    
    private val cashPatterns = listOf(
        "Cash", "cash", "CASH", "Pay by Cash", "कैश", "नकद"
    )
    
    // DROP location patterns (for Pickup/Drop screen)
    private val dropLocationPatterns = listOf(
        "DROP location", "Drop location", "DROP", "Drop",
        "Select on map", "Enter drop", "Enter DROP",
        "Where do you want to go", "Destination",
        "ड्रॉप", "गंतव्य"
    )
    
    // Confirm Pickup patterns (after destination selection)
    private val confirmPickupPatterns = listOf(
        "Confirm pickup", "confirm pickup", "CONFIRM PICKUP",
        "Confirm Pickup", "Confirm location", "Confirm",
        "Done", "Continue", "पिकअप कन्फर्म करें"
    )
    
    private var automationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var automationStartTime = 0L
    private var currentStep = AutomationStep.IDLE
    private var wasInRapido = false
    private var automationActive = false
    
    enum class AutomationStep {
        IDLE,
        WAITING_FOR_HOME,
        FINDING_WHERE_TO,
        TYPING_DESTINATION,
        SELECTING_SUGGESTION,
        SELECTING_VEHICLE,
        SELECTING_PAYMENT,
        CONFIRMING_BOOKING,
        COMPLETED,
        FAILED
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  🚗 RapidoAutoBookService CREATED                      ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  🚗 Rapido Auto-Book Service CONNECTED                 ║")
        Log.i(TAG, "║  Ready to automate ride booking!                       ║")
        Log.i(TAG, "╠════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║  Config:                                               ║")
        Log.i(TAG, "║  - Initial Delay: ${INITIAL_DELAY_MS}ms                          ║")
        Log.i(TAG, "║  - Step Delay: ${STEP_DELAY_MS}ms                              ║")
        Log.i(TAG, "║  - Max Retries: $MAX_RETRIES                                    ║")
        Log.i(TAG, "║  - Timeout: ${TOTAL_TIMEOUT_MS}ms                             ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        isEnabled = true
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            
            val packageName = event.packageName?.toString() ?: return
            
            // Track if user leaves Rapido
            if (packageName != RAPIDO_PACKAGE) {
                if (wasInRapido && automationActive) {
                    Log.w(TAG, "⚠️ User left Rapido during automation - cancelling")
                    cancelAutomation("User exited Rapido app")
                }
                wasInRapido = false
                return
            }
            
            wasInRapido = true
            
            // Check if we have a pending booking request
            val booking = pendingBooking
            if (booking == null || automationActive) {
                return
            }
            
            // Start automation when Rapido window is ready
            val eventType = event.eventType
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                
                Log.i(TAG, "┌─────────────────────────────────────────────────────────")
                Log.i(TAG, "│ 📱 RAPIDO WINDOW READY - Starting automation")
                Log.i(TAG, "│ 📍 Destination: ${booking.getFinalDestination()}")
                Log.i(TAG, "│ 🚗 Vehicle: ${booking.vehicle}")
                Log.i(TAG, "└─────────────────────────────────────────────────────────")
                
                startAutomation(booking)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent: ${e.message}", e)
        }
    }
    
    /**
     * Start the full automation flow.
     */
    private fun startAutomation(booking: RapidoBookingContext) {
        if (automationActive) {
            Log.w(TAG, "Automation already active, ignoring duplicate start")
            return
        }
        
        automationActive = true
        automationStartTime = System.currentTimeMillis()
        currentStep = AutomationStep.WAITING_FOR_HOME
        
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  🚀 STARTING RAPIDO AUTOMATION                         ║")
        Log.i(TAG, "╠════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║  Destination: ${booking.getFinalDestination()}")
        Log.i(TAG, "║  Vehicle: ${booking.vehicle ?: "bike (default)"}")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        automationJob = scope.launch {
            try {
                delay(INITIAL_DELAY_MS)
                
                // Step 1: Find and tap "Where to?" field
                currentStep = AutomationStep.FINDING_WHERE_TO
                if (!findAndTapWhereToField()) {
                    failAutomation("Could not find 'Where to?' input field")
                    return@launch
                }
                
                // Wait for NEW screen to load (screen transition)
                Log.d(TAG, "│ ⏳ Waiting for search screen to load...")
                delay(1500)
                
                // Step 2: Find the INPUT FIELD on the NEW screen and paste there
                currentStep = AutomationStep.TYPING_DESTINATION
                val destination = booking.getFinalDestination()
                Log.d(TAG, "│ ⌨️ Finding input field and pasting: '$destination'")
                
                if (!findInputFieldAndPaste(destination)) {
                    failAutomation("Could not paste destination")
                    return@launch
                }
                
                // Wait for suggestions to populate (network time)
                Log.d(TAG, "│ ⏳ Waiting for suggestions to load...")
                delay(2000)  // Wait for search results
                
                // Step 3: Select first suggestion
                currentStep = AutomationStep.SELECTING_SUGGESTION
                if (!selectFirstSuggestion()) {
                    failAutomation("Could not select destination suggestion")
                    return@launch
                }
                // Wait for ride options screen
                Log.d(TAG, "│ ⏳ Waiting for ride options screen...")
                delay(SCREEN_TRANSITION_MS + STEP_DELAY_MS + 1000)
                
                // Step 4: Select vehicle type (bike, auto, cab)
                currentStep = AutomationStep.SELECTING_VEHICLE
                val vehicleType = booking.vehicle ?: "bike"
                Log.d(TAG, "│ 🚗 Looking for vehicle: $vehicleType")
                if (!selectVehicleType(vehicleType)) {
                    Log.w(TAG, "│ ⚠️ Could not select vehicle type, it may already be selected")
                }
                delay(STEP_DELAY_MS)
                
                // Step 5: Click "Book [vehicle]" button
                currentStep = AutomationStep.CONFIRMING_BOOKING
                Log.d(TAG, "│ 🎯 Looking for Book button...")
                if (!clickBookButton(vehicleType)) {
                    failAutomation("Could not click Book button")
                    return@launch
                }
                // Wait for payment/confirmation screen
                Log.d(TAG, "│ ⏳ Waiting for payment or confirmation screen...")
                delay(SCREEN_TRANSITION_MS + STEP_DELAY_MS)
                
                // Step 6: Select Cash payment (OPTIONAL - skip if not found)
                currentStep = AutomationStep.SELECTING_PAYMENT
                Log.d(TAG, "│ 💵 Checking for payment options...")
                val paymentSelected = selectCashPayment()
                if (paymentSelected) {
                    Log.d(TAG, "│ ✅ Selected Cash payment")
                    delay(STEP_DELAY_MS)
                } else {
                    Log.d(TAG, "│ ⏭️ No payment screen found, skipping...")
                }
                
                // Step 7: Click "Confirm Pickup" button (REQUIRED after payment)
                Log.d(TAG, "│ 🎯 Looking for Confirm Pickup button...")
                delay(SCREEN_TRANSITION_MS)  // Wait for new page to load
                if (!clickConfirmPickup()) {
                    // Try waiting a bit longer
                    Log.d(TAG, "│ ⏳ Waiting longer for Confirm Pickup button...")
                    delay(STEP_DELAY_MS + 1000)
                    if (!clickConfirmPickup()) {
                        Log.w(TAG, "│ ⚠️ Could not find Confirm Pickup - ride may already be booked!")
                    }
                }
                
                // Success! - Ride booking completed
                currentStep = AutomationStep.COMPLETED
                completeAutomation("Rapido ride booked successfully!")
                
            } catch (e: CancellationException) {
                Log.i(TAG, "Automation cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Automation error: ${e.message}", e)
                failAutomation("Automation error: ${e.message}")
            }
        }
    }
    
    /**
     * Find and tap the "Where to?" input field.
     */
    private suspend fun findAndTapWhereToField(): Boolean {
        Log.d(TAG, "│ 🔍 Looking for 'Where to?' field...")
        
        for (attempt in 1..MAX_RETRIES) {
            if (isTimedOut()) return false
            
            val rootNode = rootInActiveWindow ?: continue
            
            // STRATEGY 1: Search by text (covers both text and contentDescription)
            for (pattern in whereToPatterns) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(pattern)
                if (!nodes.isNullOrEmpty()) {
                    Log.d(TAG, "│    Found ${nodes.size} nodes matching '$pattern'")
                    for (node in nodes) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        
                        // Skip if it's a full-screen container (too large)
                        if (rect.width() > 900 || rect.height() > 2000) {
                            Log.d(TAG, "│    ⏭️ Skipping large container: ${rect.width()}x${rect.height()}")
                            node.recycle()
                            continue
                        }
                        
                        Log.d(TAG, "│    Trying node at (${rect.centerX()}, ${rect.centerY()}) - ${rect.width()}x${rect.height()}")
                        
                        if (forceClickNode(node, "Where to? (text match: $pattern)")) {
                            node.recycle()
                            rootNode.recycle()
                            return true
                        }
                        node.recycle()
                    }
                }
            }
            
            // STRATEGY 2: Deep search by contentDescription
            val descNode = findNodeByContentDescription(rootNode, whereToPatterns)
            if (descNode != null) {
                Log.d(TAG, "│    Found node by contentDescription")
                if (forceClickNode(descNode, "Where to? (by desc)")) {
                    rootNode.recycle()
                    return true
                }
            }
            
            // STRATEGY 3: Look for any Button class with matching description
            val buttonNode = findButtonByPatterns(rootNode, whereToPatterns)
            if (buttonNode != null) {
                Log.d(TAG, "│    Found Button with matching pattern")
                if (forceClickNode(buttonNode, "Where to? (Button)")) {
                    rootNode.recycle()
                    return true
                }
            }
            
            rootNode.recycle()
            Log.d(TAG, "│    Attempt $attempt/$MAX_RETRIES failed, retrying...")
            delay(STEP_DELAY_MS)
        }
        
        Log.e(TAG, "│ ❌ Could not find 'Where to?' field after $MAX_RETRIES attempts")
        dumpVisibleTextNodes()
        return false
    }
    
    /**
     * Find the input field on the NEW screen (after tapping Where to?) and paste destination.
     * Looks for "Drop location", "Enter destination", etc.
     */
    private suspend fun findInputFieldAndPaste(destination: String): Boolean {
        Log.d(TAG, "│ 🔍 Looking for input field on search screen...")
        
        // Patterns to find the input field on the search screen
        val inputPatterns = listOf(
            "Drop location",
            "drop location", 
            "Enter drop location",
            "enter drop location",
            "Where are you going",
            "where are you going",
            "Search destination",
            "destination"
        )
        
        for (attempt in 1..MAX_RETRIES) {
            if (isTimedOut()) return false
            
            val rootNode = rootInActiveWindow ?: continue
            
            // Find the input field by text/description
            for (pattern in inputPatterns) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(pattern)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        
                        // Skip large containers
                        if (rect.width() > 900 || rect.height() > 300) {
                            node.recycle()
                            continue
                        }
                        
                        val centerX = rect.centerX().toFloat()
                        val centerY = rect.centerY().toFloat()
                        Log.d(TAG, "│    Found input field '$pattern' at ($centerX, $centerY)")
                        
                        // Now type at these coordinates
                        node.recycle()
                        rootNode.recycle()
                        return typeAtCoordinates(destination, centerX, centerY)
                    }
                }
            }
            
            // Also try finding by contentDescription
            val descNode = findNodeByContentDescription(rootNode, inputPatterns)
            if (descNode != null) {
                val rect = Rect()
                descNode.getBoundsInScreen(rect)
                val centerX = rect.centerX().toFloat()
                val centerY = rect.centerY().toFloat()
                Log.d(TAG, "│    Found input field by desc at ($centerX, $centerY)")
                descNode.recycle()
                rootNode.recycle()
                return typeAtCoordinates(destination, centerX, centerY)
            }
            
            rootNode.recycle()
            Log.d(TAG, "│    Attempt $attempt/$MAX_RETRIES - input field not found yet")
            delay(STEP_DELAY_MS)
        }
        
        Log.e(TAG, "│ ❌ Could not find input field on search screen")
        dumpVisibleTextNodes()
        return false
    }
    
    /**
     * Tap input field to focus, then inject text via keyboard clipboard suggestion.
     * GBoard and other keyboards show copied text as a suggestion - we tap that!
     */
    private suspend fun typeAtCoordinates(text: String, x: Float, y: Float): Boolean {
        try {
            // Step 1: Copy text to clipboard FIRST
            Log.d(TAG, "│    Copying '$text' to clipboard...")
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("destination", text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "│    ✅ Copied to clipboard")
            
            // Step 2: TAP the input field to focus it and bring up keyboard
            Log.d(TAG, "│    Tapping input field at ($x, $y)...")
            val tapPath = Path().apply { moveTo(x, y) }
            val tapGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tapPath, 0, 100))
                .build()
            
            val tapSuccess = withTimeoutOrNull(1500L) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val callback = object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                    if (!dispatchGesture(tapGesture, callback, null)) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false
            
            if (!tapSuccess) {
                Log.e(TAG, "│    ❌ Tap failed")
                return false
            }
            
            Log.d(TAG, "│    ✅ Tapped input field, waiting for keyboard...")
            delay(800)  // Wait for keyboard to fully appear with clipboard suggestion
            
            // Step 3: Look for clipboard suggestion in keyboard area
            // GBoard shows copied text as a suggestion chip we can tap!
            Log.d(TAG, "│    Looking for clipboard suggestion '$text'...")
            
            if (tapClipboardSuggestion(text)) {
                Log.i(TAG, "│ ✅ Tapped clipboard suggestion!")
                return true
            }
            
            // Fallback: Try ACTION_SET_TEXT on focused node
            Log.d(TAG, "│    Clipboard suggestion not found, trying ACTION_SET_TEXT...")
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                        Log.i(TAG, "│ ✅ Set text using ACTION_SET_TEXT!")
                        focusedNode.recycle()
                        rootNode.recycle()
                        return true
                    }
                    focusedNode.recycle()
                }
                rootNode.recycle()
            }
            
            Log.e(TAG, "│    All methods failed!")
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "│    Type error: ${e.message}")
            return false
        }
    }
    
    /**
     * Find and tap the clipboard suggestion in keyboard's suggestion strip.
     * GBoard shows copied text in suggestion strip at the top of keyboard.
     * After tapping, we wait for results and tap the first one.
     */
    private suspend fun tapClipboardSuggestion(text: String): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        
        // TAP BY COORDINATES - keyboard suggestion strip location
        // From the screenshot: suggestion strip is at ~56% from top (just above keyboard keys)
        // The clipboard suggestion "College" with icon is roughly CENTER of the strip
        
        // Y position: The suggestion strip is at the very top of keyboard area
        // GBoard suggestion strip is typically at about 55-57% from screen top
        val suggestionY = (screenHeight * 0.70f)  // 56% from top = suggestion strip
        val suggestionX = (screenWidth * 0.5f)     // Center of screen
        
        Log.d(TAG, "│    Tapping clipboard suggestion at center ($suggestionX, $suggestionY)")
        val centerTapped = performGestureTap(suggestionX, suggestionY)
        
        if (centerTapped) {
            Log.d(TAG, "│    Tapped center of suggestion strip")
            delay(500)  // Wait for text to be inserted
            
            // Check if we need to wait for search results and tap first one
            // This happens when the text gets inserted and Rapido shows location suggestions
            Log.d(TAG, "│    Waiting for search results to appear...")
            delay(1500)  // Wait for Rapido to show location suggestions
            
            // Now tap the FIRST search result (roughly at Y = 35-40% of screen, first item in list)
            if (tapFirstSearchResult()) {
                return true
            }
            
            return true  // At least the suggestion was tapped
        }
        
        // Try slightly lower position (80%)
        Log.d(TAG, "│    Trying lower position (80%)...")
        val lowerY = (screenHeight * 0.68f)
        val lowerTapped = performGestureTap(suggestionX, lowerY)
        
        if (lowerTapped) {
            delay(500)
            Log.d(TAG, "│    Tapped lower suggestion strip position")
            delay(1500)
            tapFirstSearchResult()
            return true
        }
        
        return false
    }
    
    /**
     * Tap the first search result in Rapido's location search list.
     * The first result is typically visible below the search input area.
     */
    private suspend fun tapFirstSearchResult(): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        
        // First search result is typically at around 35-40% of screen height
        // (below the input fields which are at top, above the keyboard)
        val resultY = (screenHeight * 0.38f)  // 38% from top = first result area
        val resultX = (screenWidth * 0.5f)     // Center of screen
        
        Log.d(TAG, "│    Tapping first search result at ($resultX, $resultY)")
        val tapped = performGestureTap(resultX, resultY)
        
        if (tapped) {
            Log.d(TAG, "│    ✅ Tapped first search result")
            return true
        }
        
        return false
    }
    
    /**
     * Simulate typing by finding and tapping each key on the visible keyboard.
     * Works for any keyboard (GBoard, Samsung, etc.) by searching for key labels.
     */
    private suspend fun simulateKeyboardTyping(text: String): Boolean {
        for ((index, char) in text.lowercase().withIndex()) {
            Log.d(TAG, "│    Typing character: '$char' (${index + 1}/${text.length})")
            
            // Find and tap the key for this character
            val keyTapped = findAndTapKeyboardKey(char.toString())
            if (!keyTapped) {
                // If key not found, try looking for it differently
                Log.w(TAG, "│    Key '$char' not found by text, trying by position...")
                val altTapped = tapKeyByApproximatePosition(char)
                if (!altTapped) {
                    Log.e(TAG, "│    ❌ Could not type '$char'")
                    // Continue anyway, partial typing is better than nothing
                }
            }
            
            delay(100)  // Brief delay between key presses
        }
        
        Log.i(TAG, "│ ✅ Finished typing '$text'")
        return true
    }
    
    /**
     * Find a keyboard key by its label and tap it.
     */
    private suspend fun findAndTapKeyboardKey(keyLabel: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        
        // Search for nodes with this character as text
        val nodes = rootNode.findAccessibilityNodeInfosByText(keyLabel)
        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                val text = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                
                // Skip if it's not an exact match (avoid matching "college" when looking for "c")
                if (text != keyLabel && desc != keyLabel && !desc.startsWith("$keyLabel ")) {
                    node.recycle()
                    continue
                }
                
                val rect = Rect()
                node.getBoundsInScreen(rect)
                
                // Keyboard keys are typically small (less than 200px height)
                // and in the lower portion of screen
                if (rect.height() > 200 || rect.top < 1000) {
                    node.recycle()
                    continue
                }
                
                Log.d(TAG, "│      Found key '$keyLabel' at ${rect.centerX()}, ${rect.centerY()}")
                
                // Tap the key
                val tapped = performGestureTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                node.recycle()
                rootNode.recycle()
                return tapped
            }
        }
        
        rootNode.recycle()
        return false
    }
    
    /**
     * Tap key by approximate keyboard position (fallback when key search fails).
     * Standard QWERTY layout positions.
     */
    private suspend fun tapKeyByApproximatePosition(char: Char): Boolean {
        // Get screen dimensions (approximate)
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()
        
        // Keyboard typically starts around 60% down the screen
        val keyboardTop = screenHeight * 0.6f
        val keyboardHeight = screenHeight * 0.35f
        val keyHeight = keyboardHeight / 4  // 4 rows
        
        // QWERTY keyboard layout
        val row1 = "qwertyuiop"
        val row2 = "asdfghjkl"
        val row3 = "zxcvbnm"
        
        val lowerChar = char.lowercaseChar()
        
        val (row, col, rowLength) = when {
            row1.contains(lowerChar) -> Triple(0, row1.indexOf(lowerChar), row1.length)
            row2.contains(lowerChar) -> Triple(1, row2.indexOf(lowerChar), row2.length)
            row3.contains(lowerChar) -> Triple(2, row3.indexOf(lowerChar), row3.length)
            lowerChar == ' ' -> Triple(3, 4, 10)  // Spacebar is in the center of bottom row
            else -> return false  // Unknown character
        }
        
        // Calculate approximate key position
        val keyWidth = screenWidth / rowLength
        val xOffset = if (row == 1) screenWidth * 0.05f else if (row == 2) screenWidth * 0.08f else 0f
        
        val x = xOffset + (col + 0.5f) * keyWidth
        val y = keyboardTop + (row + 0.5f) * keyHeight
        
        Log.d(TAG, "│      Tapping approximate key position for '$lowerChar' at ($x, $y)")
        return performGestureTap(x, y)
    }
    
    /**
     * Perform a gesture tap at the given coordinates.
     */
    private suspend fun performGestureTap(x: Float, y: Float): Boolean {
        val tapPath = Path().apply { moveTo(x, y) }
        val tapGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath, 0, 50))
            .build()
        
        return withTimeoutOrNull(1500L) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                if (!dispatchGesture(tapGesture, callback, null)) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        } ?: false
    }
    
    /**
     * Find an editable field by hint text.
     * PRIORITY method for finding destination input field.
     */
    private fun findEditableFieldByHint(root: AccessibilityNodeInfo, hintKeywords: List<String>): AccessibilityNodeInfo? {
        // Check if this node is editable and has matching hint
        if (root.isEditable) {
            val hintText = root.hintText?.toString()?.lowercase() ?: ""
            val desc = root.contentDescription?.toString()?.lowercase() ?: ""
            val combined = "$hintText $desc"
            
            for (keyword in hintKeywords) {
                if (combined.contains(keyword.lowercase())) {
                    Log.d(TAG, "│    ✅ Found editable field with hint: '$hintText', desc: '$desc'")
                    return root
                }
            }
        }
        
        // Recurse into children
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findEditableFieldByHint(child, hintKeywords)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        
        return null
    }
    
    /**
     * Find and tap the DROP location field on Pickup/Drop screen.
     * This is an intermediate step when Rapido shows multi-stop selection.
     * 
     * CRITICAL: Must find the EDITABLE DROP field with hint "where are you going",
     * NOT the "Select on map" button.
     * 
     * Returns true if DROP location editable field was found and focused, false if not found.
     */
    private suspend fun findAndTapDropLocation(): Boolean {
        Log.d(TAG, "│ 🔍 Looking for editable DROP location field...")
        
        val rootNode = rootInActiveWindow ?: return false
        
        // PRIORITY 1: Find editable field with hint containing "where are you going" or similar
        Log.d(TAG, "│    Strategy 1: Looking for editable DROP field with hint...")
        val editableDropField = findEditableFieldByHint(
            rootNode, 
            listOf("where are you going", "where to", "destination", "drop location", "drop")
        )
        
        if (editableDropField != null) {
            val hintText = editableDropField.hintText?.toString() ?: ""
            val desc = editableDropField.contentDescription?.toString() ?: ""
            Log.d(TAG, "│    ✅ Found editable DROP field: hint='$hintText', desc='$desc'")
            
            // Focus and click the field to activate it
            if (forceClickNode(editableDropField, "Editable DROP field")) {
                editableDropField.recycle()
                rootNode.recycle()
                return true
            }
            editableDropField.recycle()
        }
        
        // PRIORITY 2: Look for any editable field (might not have hint set yet)
        Log.d(TAG, "│    Strategy 2: Looking for any editable field...")
        val anyEditableField = findEditableNode(rootNode)
        if (anyEditableField != null) {
            Log.d(TAG, "│    Found editable field")
            if (forceClickNode(anyEditableField, "Editable field (no hint)")) {
                anyEditableField.recycle()
                rootNode.recycle()
                return true
            }
            anyEditableField.recycle()
        }
        
        rootNode.recycle()
        Log.d(TAG, "│    No editable DROP field found (might already be on search screen)")
        return false
    }
    
    /**
     * Find a node by contentDescription, but skip full-screen containers.
     * Only returns nodes that are smaller than 80% of screen width.
     */
    private fun findSmallNodeByContentDescription(
        root: AccessibilityNodeInfo,
        patterns: List<String>
    ): AccessibilityNodeInfo? {
        val desc = root.contentDescription?.toString()?.lowercase() ?: ""
        
        for (pattern in patterns) {
            if (desc.contains(pattern.lowercase())) {
                // Check if this is a small node, not a container
                val rect = Rect()
                root.getBoundsInScreen(rect)
                
                // Skip full-screen containers (more than 80% of 1080 width)
                if (rect.width() < 900 && rect.height() < 500) {
                    Log.d(TAG, "│    ✅ Found small node match: '$desc' (${rect.width()}x${rect.height()})")
                    return root
                } else {
                    Log.d(TAG, "│    ⏭️ Skipping full-screen container: '$desc' (${rect.width()}x${rect.height()})")
                }
            }
        }
        
        // Recurse into children
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findSmallNodeByContentDescription(child, patterns)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        
        return null
    }
    
    /**
     * Find node by contentDescription matching any pattern.
     * Skips full-screen containers (larger than 900px wide or 2000px tall).
     */
    private fun findNodeByContentDescription(
        root: AccessibilityNodeInfo, 
        patterns: List<String>
    ): AccessibilityNodeInfo? {
        val desc = root.contentDescription?.toString()?.lowercase() ?: ""
        for (pattern in patterns) {
            if (desc.contains(pattern.lowercase())) {
                // Check bounds to avoid full-screen containers
                val rect = Rect()
                root.getBoundsInScreen(rect)
                
                // Skip if it's a full-screen container (too large)
                if (rect.width() > 900 || rect.height() > 2000) {
                    Log.d(TAG, "│    ⏭️ Skipping full-screen container: '$desc' (${rect.width()}x${rect.height()})")
                } else {
                    Log.d(TAG, "│    ✅ Content desc match: '$desc' contains '$pattern' (${rect.width()}x${rect.height()})")
                    return root
                }
            }
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByContentDescription(child, patterns)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
    
    /**
     * Find a Button node whose text or contentDescription matches patterns.
     */
    private fun findButtonByPatterns(
        root: AccessibilityNodeInfo,
        patterns: List<String>
    ): AccessibilityNodeInfo? {
        val className = root.className?.toString() ?: ""
        val isButton = className.contains("Button") || className.contains("button")
        
        if (isButton) {
            val text = root.text?.toString()?.lowercase() ?: ""
            val desc = root.contentDescription?.toString()?.lowercase() ?: ""
            val combined = "$text $desc"
            
            for (pattern in patterns) {
                if (combined.contains(pattern.lowercase())) {
                    Log.d(TAG, "│    ✅ Button match: class=$className, desc='$desc'")
                    return root
                }
            }
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findButtonByPatterns(child, patterns)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
    
    /**
     * Force click a node even if it's not marked as clickable.
     * This is needed because Rapido's "Where are you going?" button
     * reports isClickable=false but still responds to clicks.
     * 
     * Uses multiple strategies:
     * 1. Direct ACTION_CLICK
     * 2. Focus + ACTION_CLICK
     * 3. Parent chain ACTION_CLICK
     * 4. Gesture-based tap (dispatchGesture) - FINAL FALLBACK
     */
    private suspend fun forceClickNode(node: AccessibilityNodeInfo, description: String): Boolean {
        Log.d(TAG, "│    Attempting to click: $description")
        Log.d(TAG, "│    Node: clickable=${node.isClickable}, focusable=${node.isFocusable}, class=${node.className}")
        
        // Try 1: Direct ACTION_CLICK (works even if isClickable is false sometimes)
        var clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Log.i(TAG, "│ ✅ Clicked (direct): $description")
            return true
        }
        
        // Try 2: Focus first, then click
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Log.i(TAG, "│ ✅ Clicked (after focus): $description")
            return true
        }
        
        // Try 3: Click parent chain
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            Log.d(TAG, "│    Trying parent at depth $depth, clickable=${current.isClickable}")
            
            // Try clicking parent even if not marked clickable
            clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                Log.i(TAG, "│ ✅ Clicked parent (depth=$depth): $description")
                current.recycle()
                return true
            }
            
            val next = current.parent
            current.recycle()
            current = next
            depth++
        }
        current?.recycle()
        
        // Try 4: GESTURE-BASED TAP - Final fallback for stubborn UI components
        Log.d(TAG, "│    Trying gesture-based tap...")
        if (performGestureTap(node, description)) {
            return true
        }
        
        Log.w(TAG, "│    ⚠️ Could not click: $description")
        return false
    }
    
    /**
     * Perform a gesture-based tap at the center of a node's screen bounds.
     * This works for UI components that don't respond to ACTION_CLICK.
     * 
     * Uses suspendCancellableCoroutine to wait for the gesture callback
     * before returning, ensuring the tap actually happens.
     */
    private suspend fun performGestureTap(node: AccessibilityNodeInfo, description: String): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        if (rect.isEmpty) {
            Log.w(TAG, "│    Node has empty bounds, cannot tap")
            return false
        }
        
        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()
        
        Log.d(TAG, "│    Tapping at ($centerX, $centerY) - bounds: $rect")
        
        // Build a tap gesture
        val path = Path().apply {
            moveTo(centerX, centerY)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        // Wait for gesture completion with timeout (prevent hanging forever)
        val result = withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.i(TAG, "│ ✅ Gesture tap COMPLETED: $description at ($centerX, $centerY)")
                        if (continuation.isActive) {
                            continuation.resume(true) { }
                        }
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "│    Gesture tap CANCELLED: $description")
                        if (continuation.isActive) {
                            continuation.resume(false) { }
                        }
                    }
                }
                
                val dispatched = dispatchGesture(gesture, callback, null)
                
                if (!dispatched) {
                    Log.w(TAG, "│    Failed to dispatch gesture tap")
                    if (continuation.isActive) {
                        continuation.resume(false) { }
                    }
                } else {
                    Log.d(TAG, "│    Gesture dispatched, waiting for callback...")
                }
            }
        }
        
        if (result == null) {
            Log.w(TAG, "│    ⏰ Gesture tap TIMED OUT after 3s - callback never fired!")
            Log.w(TAG, "│    Make sure android:canPerformGestures=\"true\" is in service config")
            // Still return true because the gesture was dispatched
            // The screen may have changed even without callback
            return true
        }
        
        return result
    }
    
    /**
     * DEBUG: Dump all visible text on screen to help identify correct patterns.
     */
    private fun dumpVisibleTextNodes() {
        val rootNode = rootInActiveWindow ?: return
        Log.e(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.e(TAG, "║  🔍 DEBUG: DUMPING ALL NODES (including editable)      ║")
        Log.e(TAG, "╚════════════════════════════════════════════════════════╝")
        collectAllNodes(rootNode, 0)
        rootNode.recycle()
    }
    
    private fun collectAllNodes(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 8) return  // Limit depth to avoid too much output
        
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val hint = node.hintText?.toString() ?: ""
        val className = node.className?.toString()?.substringAfterLast(".") ?: ""
        val clickable = if (node.isClickable) "[C]" else "[ ]"
        val focusable = if (node.isFocusable) "[F]" else "[ ]"
        val editable = if (node.isEditable) "[E]" else "[ ]"
        
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val size = "${rect.width()}x${rect.height()}"
        
        // Show ALL nodes, but highlight important ones
        val hasContent = text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || node.isEditable
        
        if (hasContent || depth < 3) {  // Always show first 3 levels
            val info = StringBuilder("$indent$clickable$focusable$editable $className ($size)")
            if (text.isNotEmpty()) info.append(" text='$text'")
            if (desc.isNotEmpty()) info.append(" desc='$desc'")
            if (hint.isNotEmpty()) info.append(" hint='$hint'")
            
            Log.d(TAG, info.toString())
        }
        
        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, depth + 1)
            child.recycle()
        }
    }
    
    /**
     * Find node by text content.
     */
    private fun findNodeByText(root: AccessibilityNodeInfo, patterns: List<String>): AccessibilityNodeInfo? {
        for (pattern in patterns) {
            val nodes = root.findAccessibilityNodeInfosByText(pattern)
            if (!nodes.isNullOrEmpty()) {
                return nodes[0]  // Return first match
            }
        }
        return null
    }

    
    /**
     * Find any node that is editable.
     */
    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
    
    /**
     * Click the "Confirm Pickup" button after destination selection.
     */
    private suspend fun clickConfirmPickup(): Boolean {
        Log.d(TAG, "│ 🎯 Looking for Confirm Pickup button...")
        
        for (attempt in 1..MAX_RETRIES) {
            if (isTimedOut()) return false
            
            val rootNode = rootInActiveWindow ?: continue
            
            // Search by patterns
            for (pattern in confirmPickupPatterns) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(pattern)
                if (!nodes.isNullOrEmpty()) {
                    Log.d(TAG, "│    Found ${nodes.size} nodes matching '$pattern'")
                    for (node in nodes) {
                        val className = node.className?.toString() ?: ""
                        // Prefer Button class
                        if (className.contains("Button") || node.isClickable) {
                            if (forceClickNode(node, "Confirm Pickup: $pattern")) {
                                rootNode.recycle()
                                Log.i(TAG, "│ ✅ Clicked Confirm Pickup")
                                return true
                            }
                        }
                        node.recycle()
                    }
                }
            }
            
            // Also try by content description
            val confirmNode = findNodeByContentDescription(rootNode, confirmPickupPatterns)
            if (confirmNode != null) {
                if (forceClickNode(confirmNode, "Confirm Pickup (by desc)")) {
                    rootNode.recycle()
                    Log.i(TAG, "│ ✅ Clicked Confirm Pickup")
                    return true
                }
            }
            
            rootNode.recycle()
            Log.d(TAG, "│    Attempt $attempt/$MAX_RETRIES - no Confirm Pickup found yet")
            delay(STEP_DELAY_MS)
        }
        
        Log.w(TAG, "│ ⚠️ Could not find Confirm Pickup button")
        return false
    }
    
    /**
     * Select the first suggestion from the dropdown.
     */
    private suspend fun selectFirstSuggestion(): Boolean {
        Log.d(TAG, "│ 🔍 Looking for first suggestion...")
        
        for (attempt in 1..MAX_RETRIES) {
            if (isTimedOut()) return false
            
            val rootNode = rootInActiveWindow ?: continue
            
            // Look for RecyclerView with suggestions
            val recyclerView = findNodeByClassName(rootNode, "RecyclerView")
            if (recyclerView != null && recyclerView.childCount > 0) {
                // Skip first child if it looks like a header
                val startIndex = if (recyclerView.childCount > 1) 0 else 0
                for (i in startIndex until minOf(startIndex + 3, recyclerView.childCount)) {
                    val child = recyclerView.getChild(i) ?: continue
                    if (forceClickNode(child, "Suggestion #${i+1}")) {
                        recyclerView.recycle()
                        rootNode.recycle()
                        return true
                    }
                    child.recycle()
                }
                recyclerView.recycle()
            }
            
            // Fallback: Look for any clickable item that looks like a location
            val locationNode = findClickableNode(rootNode) { node ->
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val combined = "$text $desc".lowercase()
                // Location suggestions often have addresses or place names
                (text.isNotEmpty() || desc.isNotEmpty()) && 
                !whereToPatterns.any { combined.contains(it.lowercase()) }
            }
            
            if (locationNode != null && forceClickNode(locationNode, "Location suggestion")) {
                rootNode.recycle()
                return true
            }
            
            rootNode.recycle()
            Log.d(TAG, "│    Attempt $attempt/$MAX_RETRIES - no suggestions found yet")
            delay(STEP_DELAY_MS)
        }
        
        Log.e(TAG, "│ ❌ Could not select suggestion after $MAX_RETRIES attempts")
        return false
    }
    
    /**
     * Select vehicle type (bike or auto/cab).
     */
    private suspend fun selectVehicleType(vehicle: String): Boolean {
        Log.d(TAG, "│ 🚗 Selecting vehicle: $vehicle")
        
        val patterns = if (vehicle == "bike") bikePatterns else autoPatterns
        
        for (attempt in 1..MAX_RETRIES) {
            if (isTimedOut()) return false
            
            val rootNode = rootInActiveWindow ?: continue
            
            for (pattern in patterns) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(pattern)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        if (forceClickNode(node, "Vehicle: $pattern")) {
                            rootNode.recycle()
                            return true
                        }
                        node.recycle()
                    }
                }
            }
            
            rootNode.recycle()
            delay(STEP_DELAY_MS)
        }
        
        Log.w(TAG, "│ ⚠️ Could not find vehicle option, may already be selected")
        return false
    }
    
    /**
     * Select CASH payment option.
     */
    private suspend fun selectCashPayment(): Boolean {
        Log.d(TAG, "│ 💵 Selecting CASH payment...")
        
        val rootNode = rootInActiveWindow ?: return false
        
        for (pattern in cashPatterns) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(pattern)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (forceClickNode(node, "Payment: Cash")) {
                        rootNode.recycle()
                        return true
                    }
                    node.recycle()
                }
            }
        }
        
        rootNode.recycle()
        Log.w(TAG, "│ ⚠️ Could not find CASH option, may already be selected")
        return false
    }
    
    /**
     * Click the final Book/Confirm button.
     * Simplified to match any button containing the word "book" (case-insensitive).
     * This covers "Book Bike", "Book Auto", "Book Now", etc.
     */
    private suspend fun clickBookButton(vehicleType: String): Boolean {
        Log.d(TAG, "│ 🎯 Looking for Book button...")
        
        for (attempt in 1..MAX_RETRIES) {
            if (isTimedOut()) return false
            
            val rootNode = rootInActiveWindow ?: continue
            
            // Find any node containing "book" in text or contentDescription
            val bookNodes = rootNode.findAccessibilityNodeInfosByText("book")
            if (!bookNodes.isNullOrEmpty()) {
                Log.d(TAG, "│    Found ${bookNodes.size} nodes matching 'book'")
                for (node in bookNodes) {
                    val className = node.className?.toString() ?: ""
                    val text = node.text?.toString()?.lowercase() ?: ""
                    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                    
                    // Accept if it contains "book"
                    val hasBook = text.contains("book") || desc.contains("book")
                    val isLikelyButton = className.contains("Button") || node.isClickable
                            
                    if (hasBook && isLikelyButton) {
                        Log.d(TAG, "│    Trying Book button: text='$text', desc='$desc'")
                        if (forceClickNode(node, "Book button")) {
                            Log.i(TAG, "│ ✅ Clicked Book button")
                            rootNode.recycle()
                            return true
                        }
                    }
                    node.recycle()
                }
            }
            
            // Also try scrolling to find the button
            if (attempt == 2) {
                Log.d(TAG, "│    Trying to scroll to find Book button...")
                if (scrollUntilFound("book")) {
                    // Try clicking again
                    val nodes = rootNode.findAccessibilityNodeInfosByText("book")
                    if (!nodes.isNullOrEmpty()) {
                        val node = nodes[0]
                        if (forceClickNode(node, "Book button (after scroll)")) {
                            Log.i(TAG, "│ ✅ Clicked Book button after scrolling")
                            rootNode.recycle()
                            return true
                        }
                        node.recycle()
                    }
                }
            }
            
            rootNode.recycle()
            Log.d(TAG, "│    Attempt $attempt/$MAX_RETRIES - no Book button found yet")
            delay(STEP_DELAY_MS)
        }
        
        Log.e(TAG, "│ ❌ Could not find Book button")
        return false
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Wait for a node with specific text to appear, with timeout.
     * Used for waiting for UI elements to load.
     * 
     * @param text Text to search for in node text or contentDescription
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return The found node, or null if not found within timeout
     */
    private suspend fun waitForNode(text: String, timeoutMs: Long = 3000L): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                // Try finding by text
                val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                if (!nodes.isNullOrEmpty()) {
                    val node = nodes[0]
                    rootNode.recycle()
                    return node
                }
                
                // Try finding by contentDescription
                val descNode = findNodeByContentDescription(rootNode, listOf(text))
                if (descNode != null) {
                    rootNode.recycle()
                    return descNode
                }
                
                rootNode.recycle()
            }
            
            delay(300)  // Check every 300ms
        }
        
        Log.d(TAG, "│    waitForNode: '$text' not found after ${timeoutMs}ms")
        return null
    }
    
    /**
     * Scroll through scrollable containers until target text is found.
     * 
     * @param text Text to search for
     * @return True if text was found and is now visible, false otherwise
     */
    private suspend fun scrollUntilFound(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        
        // Find scrollable container
        val scrollableNode = findScrollableNode(rootNode)
        if (scrollableNode == null) {
            Log.d(TAG, "│    No scrollable container found")
            rootNode.recycle()
            return false
        }
        
        var attempts = 0
        val maxAttempts = 10
        
        while (attempts < maxAttempts) {
            // Check if text is now visible
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                Log.d(TAG, "│    Found '$text' after scroll")
                scrollableNode.recycle()
                rootNode.recycle()
                return true
            }
            
            // Scroll forward
            val scrolled = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (!scrolled) {
                // Can't scroll anymore
                break
            }
            
            delay(500)  // Wait for scroll animation
            attempts++
        }
        
        scrollableNode.recycle()
        rootNode.recycle()
        Log.d(TAG, "│    Could not find '$text' after scrolling")
        return false
    }
    
    /**
     * Find a scrollable node in the hierarchy.
     */
    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
    
    /**
     * Perform click on a node or its clickable parent.
     */
    private fun performClickOnNode(node: AccessibilityNodeInfo, description: String): Boolean {
        // Try clicking the node directly
        if (node.isClickable) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                Log.i(TAG, "│ ✅ Clicked: $description")
                return true
            }
        }
        
        // Try clicking parent
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current.recycle()
                if (clicked) {
                    Log.i(TAG, "│ ✅ Clicked parent of: $description")
                    return true
                }
                return false
            }
            val next = current.parent
            current.recycle()
            current = next
            depth++
        }
        current?.recycle()
        
        return false
    }
    
    /**
     * Find a focused EditText in the tree.
     */
    private fun findFocusedEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // First try to find explicitly focused node
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.className?.contains("EditText") == true) {
            return focused
        }
        focused?.recycle()
        
        // Fallback: search for any EditText
        return findNodeByClassName(root, "EditText")
    }
    
    /**
     * Find node by class name (partial match).
     */
    private fun findNodeByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className?.contains(className) == true) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByClassName(child, className)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
    
    /**
     * Find a clickable node matching a predicate.
     */
    private fun findClickableNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root) && (root.isClickable || root.isFocusable)) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findClickableNode(child, predicate)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
    
    /**
     * Check if automation has exceeded timeout.
     */
    private fun isTimedOut(): Boolean {
        val elapsed = System.currentTimeMillis() - automationStartTime
        if (elapsed > TOTAL_TIMEOUT_MS) {
            Log.e(TAG, "⏰ Automation timed out after ${elapsed}ms")
            return true
        }
        return false
    }
    
    /**
     * Complete automation successfully.
     */
    private fun completeAutomation(message: String) {
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  ✅ RAPIDO AUTOMATION COMPLETED                        ║")
        Log.i(TAG, "║  $message")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        cleanupAutomation()
        onAutomationComplete?.invoke(true, message)
    }
    
    /**
     * Fail automation with error.
     */
    private fun failAutomation(error: String) {
        Log.e(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.e(TAG, "║  ❌ RAPIDO AUTOMATION FAILED                           ║")
        Log.e(TAG, "║  Error: $error")
        Log.e(TAG, "║  Step: $currentStep")
        Log.e(TAG, "╚════════════════════════════════════════════════════════╝")
        
        currentStep = AutomationStep.FAILED
        cleanupAutomation()
        onAutomationComplete?.invoke(false, error)
    }
    
    /**
     * Cancel automation.
     */
    private fun cancelAutomation(reason: String) {
        Log.w(TAG, "⚠️ Automation cancelled: $reason")
        automationJob?.cancel()
        cleanupAutomation()
        onAutomationComplete?.invoke(false, reason)
    }
    
    /**
     * Clean up automation state.
     */
    private fun cleanupAutomation() {
        automationActive = false
        pendingBooking = null
        currentStep = AutomationStep.IDLE
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Service INTERRUPTED")
        cancelAutomation("Service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isEnabled = false
        automationJob?.cancel()
        scope.cancel()
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  Rapido Auto-Book Service DESTROYED                    ║")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
    }
}
