package com.assistant.services.listening

import android.content.Context
import android.util.Log
import com.assistant.domain.model.VoiceGender
import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.services.assistant.AssistantState
import com.assistant.services.actions.ActionExecutor
import com.assistant.services.actions.ActionResult
import com.assistant.services.ack.AcknowledgementManager

import com.assistant.services.audio.SoundEffectManager
import com.assistant.services.calllog.CallLogHelper
import com.assistant.services.chat.ChatResponder
import com.assistant.services.contacts.ContactResolver
import com.assistant.services.context.ConversationContext
import com.assistant.services.history.ConversationHistoryManager
import com.assistant.services.intent.AssistantIntent
import com.assistant.services.intent.IntentDecision
import com.assistant.services.intent.IntentInterpreter
import com.assistant.services.listening.BargeInDetector
import com.assistant.services.router.GeminiRouter
import com.assistant.services.voice.SpeechToTextEngine
import com.assistant.services.voice.VoiceOutput
import com.assistant.services.audio.AudioFocusManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the "Wake -> Listen -> Think -> Act" loop.
 * 
 * VOSK VERSION (Offline STT).
 * 
 * Flows:
 * 1. Wake Only: "Hey Aman" -> Sound 1 -> 1s Silence -> Ack "Haan bolo" -> Listen.
 * 2. Wake+Command: "Hey Aman play music" -> Sound 1 -> Vosk Final -> Sound 2 -> Gemini -> Execute.
 * 3. Conversation: "Hey Aman how are you" -> Sound 1 -> Vosk Final -> Sound 2 -> Gemini -> Agent Loop.
 * 4. Interruption: User speaks -> Stop Audio -> Restart Vosk (Instant).
 */
class ListeningSessionManager(
    private val appContext: Context,
    private val stt: SpeechToTextEngine, // Injected as VoskSTTManager
    private val voice: VoiceOutput,
    private val intentInterpreter: IntentInterpreter,
    private val chatResponder: ChatResponder,
    private val actionExecutor: ActionExecutor,
    private val audioFocusManager: AudioFocusManager,
    private val soundEffectManager: SoundEffectManager,
    private val geminiRouter: GeminiRouter,
    private val acknowledgementManager: AcknowledgementManager,
    private val contactResolver: ContactResolver,
    private val historyManager: ConversationHistoryManager,
    private val onSessionEnded: (() -> Unit)? = null
) : SpeechToTextEngine.Listener, VoiceOutput.Listener {

    companion object {
        private const val TAG = "ListeningSessionManager"
        private const val SILENCE_TIMEOUT_MS = 8000L // 8s silence -> End Session
        private const val WAKE_WORD_ONLY_CHECK_MS = 3000L // 3s - If no speech after wake word, play acknowledgement
        private const val MAX_STT_RETRIES = 3 // Maximum retry attempts for STT errors
        private const val STT_RETRY_DELAY_MS = 500L // Base delay between retries
        
        // CALIBRATED: Hardware settle and echo prevention timing
        private const val HARDWARE_SETTLE_DELAY_MS = 850L  // TTS→STT restart delay (prevents self-triggering)
        private const val ECHO_IGNORE_WINDOW_MS = 900L     // Anti-echo debounce (ignores TTS residual audio)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var silenceJob: Job? = null
    // Timer to detect if user stopped speaking after wake word
    private var wakeOnlyJob: Job? = null
    // Track pending STT restart to cancel if new TTS starts
    private var sttRestartJob: Job? = null
    
    // MICROPHONE CONTROL: Prevents STT activation during TTS/LLM processing
    private val microphoneEnabled = AtomicBoolean(false)
    
    // STATES
    private val isConversationMode = AtomicBoolean(false) // Track if in conversation mode
    private val isContextActive = AtomicBoolean(false) // For follow-up commands/suggestions
    private val sttRetryCount = AtomicInteger(0) // Track STT retry attempts
    private val sessionActive = AtomicBoolean(false)
    private val listenAfterSpeaking = AtomicBoolean(false) // SYNC: Turn-taking control
    
    // NEW: Session ID for grouping related conversations
    private var currentSessionId: String = "session_${System.currentTimeMillis()}_${(0..9999).random()}"
    
    private fun generateNewSession() {
        currentSessionId = "session_${System.currentTimeMillis()}_${(0..9999).random()}"
        Log.d(TAG, "New session started: $currentSessionId")
    }
    
    // SUGGESTION STATE
    @Volatile private var pendingSuggestion: String? = null
    @Volatile private var pendingAction: AssistantIntent.Action? = null
    
    // CONVERSATION CONTEXT
    @Volatile private var conversationContext: ConversationContext? = null

    // CONFIG
    @Volatile private var language: OnboardingLanguage = OnboardingLanguage.ENGLISH
    @Volatile private var voiceGender: VoiceGender = VoiceGender.NEUTRAL
    @Volatile private var state: AssistantState = AssistantState.IDLE

    // REMOVED: Agent client - using simple TTS-based conversation instead

    init {
        stt.setListener(this)
        voice.setListener(this)
        stt.warmUp()
    }

    // --- PUBLIC API ---

    fun setLanguage(language: OnboardingLanguage) {
        this.language = language
        acknowledgementManager.setLanguage(language)
    }

    fun setVoiceGender(gender: VoiceGender) {
        this.voiceGender = gender
        voice.setVoiceGender(gender) // Apply to Main TTS
        acknowledgementManager.setVoiceGender(gender)
    }
    
    fun setPreferredName(name: String?) {
        acknowledgementManager.setPreferredName(name)
    }
    
    // NEW: User Profile (Context)
    private var userProfile: com.assistant.domain.model.UserProfile? = null
    
    fun setUserProfile(profile: com.assistant.domain.model.UserProfile?) {
        this.userProfile = profile
        // Also update legacy fields if needed
        acknowledgementManager.setPreferredName(profile?.preferredName ?: profile?.userName)
    }

    fun onWake() {
        // NEW: Start a fresh session when wake word is detected
        generateNewSession()
        
        if (sessionActive.compareAndSet(false, true)) {
             Log.i(TAG, "Session Started (Wake)")
             
             // Reset retry counter for new session
             sttRetryCount.set(0)
             
             // Start Vosk
             startListeningInternal(AssistantState.ACTIVE_LISTENING)
             
             // Start "Wake Word Only" Check
             startWakeOnlyTimer()
             
             audioFocusManager.requestOutputFocus() 
             
             resetSilenceTimer()
        } else {
            // Already active - restart wake timer
            startWakeOnlyTimer()
        }
    }
    
    fun endSession(reason: String) {
        if (!sessionActive.compareAndSet(true, false)) return
        Log.i(TAG, "Ending Session. Reason: $reason")
        
        // Cleanup all resources
        stt.cancel()
        voice.stop()
        
        silenceJob?.cancel()
        wakeOnlyJob?.cancel()
        
        isConversationMode.set(false)
        isContextActive.set(false)
        pendingSuggestion = null
        pendingAction = null
        
        transitionTo(AssistantState.IDLE)
        audioFocusManager.abandonOutputFocus()
        
        onSessionEnded?.invoke()
    }

    fun shutdown() {
        endSession("Service Shutdown")
        stt.shutdown()
        scope.cancel()
    }

    // --- STT CALLBACKS ---

    override fun onBeginningOfSpeech() {
        // CRITICAL: Ignore ALL speech detection if microphone is disabled
        if (!microphoneEnabled.get()) {
            Log.w(TAG, "onBeginningOfSpeech IGNORED - Microphone disabled (TTS playing or processing)")
            return
        }
        
        // User started speaking - reset retry counter on successful activation
        sttRetryCount.set(0)
        
        // Cancel Wake Only timer - we have speech!
        wakeOnlyJob?.cancel()
        resetSilenceTimer() // Reset 8s timeout
    }

    // ... (rest of file)

    override fun onSpeechStarted() {
        Log.d(TAG, "TTS Started. DISABLING MICROPHONE and STOPPING STT.")
        silenceJob?.cancel()
        
        // CRITICAL: Cancel any pending STT restart to prevent race condition
        sttRestartJob?.cancel()
        sttRestartJob = null
        
        // CRITICAL: Disable microphone to prevent any STT activation during TTS
        microphoneEnabled.set(false)
        stt.stopListening()
        
        transitionTo(AssistantState.SPEAKING)
    }

    override fun onPartialText(text: String) {
        // Confirm user is speaking words, not just noise
        wakeOnlyJob?.cancel()
        resetSilenceTimer()
    }

    override fun onFinalText(text: String) {
        val cleaned = removeWakeWord(text.trim())
        Log.d(TAG, "Vosk Final: '$cleaned' (Original: '$text')")
        
        // 1. Filter Noise
        if (cleaned.isBlank() || cleaned.length < 2) {
            Log.w(TAG, "Ignoring short/empty text")
            // Don't play Sound 2. Just listen again.
            // STT will continue listening (no need to restart, already listening)
             return
        }
        
        // Play command captured sound for user feedback
        soundEffectManager.playCommandCaptured()
        
        // CRITICAL: Disable microphone while processing request
        // This prevents STT from restarting until TTS completes
        microphoneEnabled.set(false)
        
        // Stop STT (to process)
        stt.stopListening()
        
        // 4. Send to LLM for intent interpretation
        // LLM will detect quit intent via StopListeningSession if user wants to end
        // No hardcoded quit detection - let LLM handle it naturally
        scope.launch(Dispatchers.IO) {
            processUserRequest(cleaned)
        }
    }

    // --- LOGIC ---

    private suspend fun processUserRequest(text: String) {
        // CRITICAL: Stop the silence timer while we think. 
        // Otherwise, long LLM latency or TTS preparation will trigger "Silence Timeout" and kill the session.
        silenceJob?.cancel()
        
        transitionTo(AssistantState.UNDERSTANDING)
        
        // Handle "Conversation with Action" Confirmation
        var textToProcess = text
        if (pendingSuggestion != null && isPositiveConfirmation(text)) {
             textToProcess = mapSuggestionToIntent(pendingSuggestion!!)
             Log.i(TAG, "Mapped confirmation '$text' -> '$textToProcess'")
             pendingSuggestion = null
             setContextActive(false)
             // Force this as a Command routing
             handleDirectCommand(textToProcess) // Skip router, we know it's a command
             return
        }

        // Get conversation history for CURRENT SESSION ONLY (prevents context confusion)
        val recentHistory = historyManager.getSessionContext(
            sessionId = currentSessionId,
            maxEntries = 20
        )
        
        // Prepare conversation context for multi-turn interpretation
        val contextSummary = buildString {
            // Add recent conversation history
            if (recentHistory.isNotBlank()) {
                appendLine(recentHistory)
                appendLine()
            }
            
            // INJECT LANGUAGE SETTING
            appendLine("USER_SETTINGS_LANGUAGE: ${language.displayName}")
            
            // INJECT DYNAMIC CONTEXT FROM PROFILE
            userProfile?.let { profile ->
                val assistName = profile.assistantName ?: "Assistant"
                val userName = profile.preferredName ?: profile.userName ?: "User"
                
                appendLine("Context: You are $assistName. The user is $userName.")
                
                if (!profile.tonePreference.isNullOrBlank()) {
                     appendLine("Tone: ${profile.tonePreference}")
                }
                
                 if (!profile.emotionalSupportPreference.isNullOrBlank()) {
                     appendLine("Style: ${profile.emotionalSupportPreference}")
                }
                
                // Implicit "Close Friend" context if not overridden
                appendLine("Relationship: Close Friend")
            } ?: run {
                // Fallback
                 appendLine("Context: You are a helpful AI assistant.")
            }
            appendLine()
            
            // Add pending action context if exists
            conversationContext?.let { ctx ->
                if (!ctx.isExpired()) {
                    appendLine(ctx.getSummary())
                }
            }
        }.takeIf { it.isNotBlank() }

        // DEBUG: Log the context being sent to LLM
        if (contextSummary != null) {
            Log.d(TAG, "=== CONTEXT SENT TO LLM ===")
            Log.d(TAG, contextSummary)
            Log.d(TAG, "=== END CONTEXT ===")
        } else {
            Log.d(TAG, "No conversation context available")
        }

        // NEW: Use IntentInterpreter directly (Dual-Brain enabled) with full context
        // This consolidates routing and interpretation into one robust call.
        val rawDecision = intentInterpreter.interpretAccurate(textToProcess, contextSummary) ?: 
            IntentDecision(AssistantIntent.Chat(textToProcess, null), 0.5f, com.assistant.services.intent.IntentType.CHAT) // Fallback to chat if null

        // NEW: Refine confidence with Local Knowledge (Ground Truth)
        // This ensures "Call [Name]" works perfectly if name exists, or asks clarification if ambiguous.
        val decision = if (rawDecision.intent is AssistantIntent.Action.CallContact) {
            val contactName = rawDecision.intent.contactName
            
            // Check if this is a clarification (user responded to "Which Harsh?" in SAME session)
            // Context is cleared after successful call, so pending context only exists during active clarification
            val isPendingCallContact = conversationContext?.pendingAction?.actionType == ConversationContext.ActionType.CALL_CONTACT
            val previousContactName = conversationContext?.pendingAction?.collectedParams?.get("contact_name") as? String
            
            val resolution = if (isPendingCallContact && previousContactName != null) {
                // User is clarifying which contact - extract keywords from their response
                Log.i(TAG, "Clarification detected for '$previousContactName'. Extracting keywords from: '$textToProcess'")
                
                // Extract potential keywords from user's clarification
                // Remove common filler words and extract meaningful terms
                val fillerWords = setOf("jo", "jo", "ka", "ki", "ke", "hai", "hain", "use", "ko", "call", "laga", "do", "the", "is", "who", "that", "with")
                val keywords = textToProcess.split(" ")
                    .map { it.trim().lowercase() }
                    .filter { it.length > 2 && !fillerWords.contains(it) && it != previousContactName.lowercase() }
                
                if (keywords.isNotEmpty()) {
                    Log.i(TAG, "Extracted keywords for disambiguation: ${keywords.joinToString()}")
                    contactResolver.resolveContactWithKeywords(previousContactName, keywords)
                } else {
                    contactResolver.resolveContact(previousContactName)
                }
            } else {
                // Initial contact search OR different request type (like call_log)
                contactResolver.resolveContact(contactName)
            }
            
            Log.i(TAG, "Refining Call Decision for '$contactName': Status=${resolution.status}")
            
            when (resolution.status) {
                ContactResolver.ResolutionResult.Status.EXACT_MATCH -> {
                    // Perfect match! Check if number is valid.
                    val contact = resolution.contacts.first()
                    if (contact.phoneNumber.isNullOrBlank()) {
                        // Contact exists but no number! Ask LLM to generate clarification request.
                        // INJECT CONTEXT: Pass history and original request so LLM knows what's happening.
                        val prompt = buildString {
                            if (!contextSummary.isNullOrBlank()) appendLine(contextSummary)
                            appendLine("[User Request: '$textToProcess']")
                            appendLine("[System Event: found contact '${contact.displayName}' but it has NO phone number. Generate a polite response asking the user to provide the number.]")
                        }
                        val ack = chatResponder.respond(prompt)
                        
                         rawDecision.copy(
                            confidence = 0.5f,
                            needsClarification = true,
                            intentType = com.assistant.services.intent.IntentType.DIRECT,
                            intent = rawDecision.intent.copy(
                                acknowledgement = ack
                            )
                        )
                    } else {
                        // Valid contact and number.
                        rawDecision.copy(
                            confidence = 1.0f,
                            intentType = com.assistant.services.intent.IntentType.DIRECT,
                            needsClarification = false,
                            intent = rawDecision.intent.copy(
                                contactName = contact.displayName,
                                number = contact.phoneNumber
                            )
                        )
                    }
                }
                ContactResolver.ResolutionResult.Status.MULTIPLE_MATCHES -> {
                     val count = resolution.contacts.size
                     val names = resolution.contacts.take(4).joinToString(", ") { it.displayName }
                     val prompt = buildString {
                        if (!contextSummary.isNullOrBlank()) appendLine(contextSummary)
                        appendLine("[User Request: '$textToProcess']")
                        appendLine("[System Event: found $count contacts named '$contactName': $names. Generate a short response asking 'Which one?' in the user's language.]")
                     }
                     val ack = chatResponder.respond(prompt)
                     
                     rawDecision.copy(
                        confidence = 0.5f, // Medium
                        needsClarification = true,
                        intentType = com.assistant.services.intent.IntentType.DIRECT,
                        intent = rawDecision.intent.copy(
                            acknowledgement = ack
                        )
                    )
                }
                ContactResolver.ResolutionResult.Status.NO_MATCH -> {
                     val prompt = buildString {
                        if (!contextSummary.isNullOrBlank()) appendLine(contextSummary)
                        appendLine("[User Request: '$textToProcess']")
                        appendLine("[System Event: cannot find contact named '$contactName'. Generate a helpful response asking who they want to call.]")
                     }
                     val ack = chatResponder.respond(prompt)
                     
                     rawDecision.copy(
                        confidence = 0.5f, // Low
                        needsClarification = true,
                        intentType = com.assistant.services.intent.IntentType.DIRECT,
                        intent = rawDecision.intent.copy(
                            acknowledgement = ack
                        )
                    )
                }
                else -> rawDecision // Permission denied etc, handle normally
            }
        } else {
            rawDecision
        }

        Log.i(TAG, "Decision: ${decision.intentType} -> ${decision.intent}")
        
        // Save to history (will be updated with AI response after execution)
        val aiResponse = when (val intent = decision.intent) {
            is AssistantIntent.Action -> intent.acknowledgement ?: "Executing action..."
            is AssistantIntent.Chat -> "Processing..."
            is AssistantIntent.Clarify -> intent.question
            else -> "Processing..."
        }
        
        scope.launch {
            historyManager.addEntry(
                sessionId = currentSessionId,  // NEW: Pass session ID
                userInput = textToProcess,
                aiResponse = aiResponse,
                actionType = when (decision.intent) {
                    is AssistantIntent.Action.CallContact -> "CALL_CONTACT"
                    is AssistantIntent.Action.PlayMedia -> "PLAY_MEDIA"
                    is AssistantIntent.Action.SetAlarm -> "SET_ALARM"
                    else -> null
                }
            )
        }

        when (decision.intentType) {
            com.assistant.services.intent.IntentType.DIRECT -> {
                // Direct command - execute immediately
                executeDecision(decision, textToProcess, contextSummary)
            }
            else -> {
                // CHAT or UNKNOWN - execute decision (will handle appropriately)
                executeDecision(decision, textToProcess, contextSummary)
            }
        }
    }
    
    private suspend fun handleDirectCommand(text: String) {
        val decision = intentInterpreter.interpretFast(text) 
        if (decision != null) {
             executeDecision(decision, text, null)  // No context for fast commands
        } else {
             // Fallback
             processUserRequest(text) 
        }
    }
    
    private suspend fun executeDecision(
        decision: IntentDecision,
        originalText: String,
        contextSummary: String?
    ) {
        // Check for Unknown
        if (decision.intent is AssistantIntent.Unknown) {
            // Try Interpreter as backup or Ask Clarification
             val local = intentInterpreter.interpretAccurate(originalText) // Double check
             if (local != null) {
                 executeDecision(local, originalText, contextSummary)
                 return
             }
             
             // Fallback: Ask
             listenAfterSpeaking.set(true) // Expect response
             acknowledgementManager.speak(AcknowledgementManager.AckCategory.REJECTION)
             endSession("Unknown Command")
             return
        }

        // Valid Decision
        when (val intent = decision.intent) {
            is AssistantIntent.Action -> {
                when (intent) {
                    is AssistantIntent.Action.StopListeningSession -> {
                        // STOP_SESSION: Explicit exit
                        isConversationMode.set(false)
                        listenAfterSpeaking.set(false)
                        voice.setVoiceGender(voiceGender)
                        voice.speak(intent.acknowledgement ?: "Goodbye! See you later.")
                        // Session will end in onSpeechEnded
                        state = AssistantState.SPEAKING
                        return
                    }
                    is AssistantIntent.Action.CallContact -> {
                        // Route to specialized handler
                        isConversationMode.set(false)
                        handleCallContactWithResolution(intent)
                        return
                    }
                    is AssistantIntent.Action.PlayMedia -> {
                        // Route to specialized handler
                        isConversationMode.set(false)
                        handlePlayMediaWithResolution(intent)
                        return
                    }
                    is AssistantIntent.Action.SetAlarm -> {
                        // Route to specialized handler
                        isConversationMode.set(false)
                        handleSetAlarmWithResolution(intent)
                        return
                    }
                    else -> {
                        // Other actions: execute directly and end session
                        isConversationMode.set(false)
                        listenAfterSpeaking.set(false)
                        
                        // Speak acknowledgement
                        if (!intent.acknowledgement.isNullOrBlank()) {
                            voice.speak(intent.acknowledgement!!)
                        } else {
                            acknowledgementManager.speak(AcknowledgementManager.AckCategory.EXECUTING)
                        }
                        
                        // Execute action
                        val result = actionExecutor.execute(intent, contextSummary)
                        handleActionResult(result)
                    }
                }
            }
            is AssistantIntent.Chat -> {
                // CONVERSATION: Simple TTS-based conversation
                if (intent.precomputedResponse != null) {
                    // LLM provided a response - speak it and keep session alive
                    isConversationMode.set(true)
                    listenAfterSpeaking.set(true)
                    voice.setVoiceGender(voiceGender)
                    voice.speak(intent.precomputedResponse)
                    state = AssistantState.SPEAKING
                } else {
                    // No precomputed response - ask ChatResponder to generate one
                    isConversationMode.set(true)
                    listenAfterSpeaking.set(true)
                    scope.launch {
                        val response = chatResponder.respond(intent.userText)
                        voice.setVoiceGender(voiceGender)
                        voice.speak(response)
                        state = AssistantState.SPEAKING
                    }
                }
            }
            is AssistantIntent.Clarify -> {
                // Clarification: Ask question and keep session alive
                isConversationMode.set(false)
                listenAfterSpeaking.set(true)
                voice.setVoiceGender(voiceGender)
                voice.speak(intent.question)
                state = AssistantState.SPEAKING
            }
            else -> {}
        }
    }
    
    private fun handleActionResult(result: ActionResult) {
        when(result) {
            is ActionResult.Success -> {
                // Done.
                listenAfterSpeaking.set(false)
                endSession("Action Complete")
            }
            is ActionResult.SuccessWithFeedback -> {
                listenAfterSpeaking.set(false)
                voice.speak(result.feedback)
                // End after speaking
                 scope.launch {
                     delay(2000)
                     endSession("Action with feedback complete")
                 }
            }
            is ActionResult.EndSession -> {
                // User wants to quit - LLM detected quit intent
                listenAfterSpeaking.set(false)
                voice.speak(result.acknowledgement)
                // End session after speaking acknowledgement
                scope.launch {
                    delay(1500)
                    endSession("User requested to stop (LLM detected)")
                }
            }
            is ActionResult.AskUser -> {
                listenAfterSpeaking.set(true) // Logic needs user input
                voice.speak(result.question)
                // Keeps session open, mic restarts in onSpeechEnded
            }
            is ActionResult.Failure -> {
                listenAfterSpeaking.set(false)
                voice.speak("Something went wrong.")
                endSession("Action Failed")
            }
        }
    }
    
    /**
     * Handle CallContact action with intelligent contact resolution.
     */
    private suspend fun handleCallContactWithResolution(intent: AssistantIntent.Action.CallContact) {
        val contactName = intent.contactName
        
        // Check if number field contains a contact_id (from LLM disambiguation)
        if (intent.number != null && conversationContext?.pendingAction?.collectedParams?.containsKey("available_contacts") == true) {
            // LLM has already resolved the contact - extract phone number from available_contacts
            val availableContacts = conversationContext?.pendingAction?.collectedParams?.get("available_contacts") ?: ""
            
            // Parse JSON to find matching contact by ID
            val contactIdMatch = Regex("\"id\":\"${intent.number}\"").find(availableContacts)
            if (contactIdMatch != null) {
                // Extract phone number from the same contact object
                val startIdx = availableContacts.indexOf("{", contactIdMatch.range.first - 200.coerceAtLeast(0))
                val endIdx = availableContacts.indexOf("}", contactIdMatch.range.last)
                val contactJson = if (startIdx >= 0 && endIdx > startIdx) {
                    availableContacts.substring(startIdx, endIdx + 1)
                } else ""
                
                val numberMatch = Regex("\"number\":\"([^\"]+)\"").find(contactJson)
                val phoneNumber = numberMatch?.groupValues?.get(1)
                
                if (phoneNumber != null && phoneNumber.isNotBlank()) {
                    // Found the phone number - proceed with call
                    listenAfterSpeaking.set(false)
                    voice.speak(intent.acknowledgement ?: "Calling $contactName...")
                    
                    val callIntent = AssistantIntent.Action.CallContact(
                        contactName = phoneNumber,  // Use phone number for calling
                        number = phoneNumber,
                        acknowledgement = null
                    )
                    val result = actionExecutor.execute(callIntent, null)
                    handleActionResult(result)
                    return
                }
            }
        }
        
        // If contact name is empty, ask for it
        if (contactName.isBlank()) {
            listenAfterSpeaking.set(true)
            voice.speak("Who would you like me to call?")
            conversationContext = ConversationContext(
                pendingAction = ConversationContext.PendingAction(
                    actionType = ConversationContext.ActionType.CALL_CONTACT,
                    missingParams = listOf("contact_name")
                )
            )
            return
        }
        
        // CALL LOG QUERIES: Check if number field contains call log request
        if (intent.number?.startsWith("call_log:") == true) {
            val rawType = intent.number.substring("call_log:".length)
            val parts = rawType.split(":")
            val callLogType = parts[0]
            val callLogAction = if (parts.size > 1) parts[1] else "call"
            
            // Check if READ_CALL_LOG permission is granted
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.READ_CALL_LOG
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (!hasPermission) {
                // Permission not granted - broadcast request to MainActivity
                listenAfterSpeaking.set(true)  // Keep session alive for user to respond
                val permissionMessage = "Call history dekhne ke liye mujhe permission chahiye. May I access your call logs?"
                voice.speak(permissionMessage)
                
                // Broadcast permission request to MainActivity
                try {
                    com.assistant.services.permissions.PermissionRequestBroadcaster.requestPermission(
                        context = appContext,
                        permission = android.Manifest.permission.READ_CALL_LOG,
                        reason = "To access call history for redial and callback features"
                    )
                    Log.i(TAG, "Broadcasted READ_CALL_LOG permission request to MainActivity")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast permission request", e)
                    voice.speak("Settings mein jaake READ_CALL_LOG permission allow kar dijiye.")
                    endSession("Failed to request permission")
                }
                return
            }
            
            val callLogHelper = CallLogHelper(appContext)
            
            val callInfo = when (callLogType) {
                "last_outgoing" -> callLogHelper.getLastOutgoingCall()
                "last_missed" -> callLogHelper.getLastMissedCall()
                "last_incoming" -> callLogHelper.getLastIncomingCall()
                else -> null
            }
            
            if (callInfo != null) {
                // Found call log entry
                val displayName = callInfo.contactName ?: callInfo.phoneNumber
                
                if (callLogAction == "info") {
                    // INFO MODE: Ask LLM to generate response using found call log info
                    listenAfterSpeaking.set(true) // Keep session open for confirmation
                    
                    scope.launch {
                        val systemPrompt = """
                            [System Context: Found recent call from $displayName. User asked: '${intent.contactName}']
                            
                            Generate a natural response that:
                            1. Tells the user who called (use the name: $displayName)
                            2. Asks if they want to call them back
                            
                            Example: "Last missed call $displayName ka tha. Unhe call karoon?"
                            Or: "$displayName ne call kiya tha. Wapas call lagaoon?"
                            
                            Keep it conversational and friendly.
                        """.trimIndent()
                        val llmResponse = chatResponder.respond(systemPrompt)
                        
                        voice.speak(llmResponse)
                        
                        historyManager.addEntry(
                            sessionId = currentSessionId,
                            userInput = "[System: Found Call Log for $displayName]", 
                            aiResponse = llmResponse,
                            actionType = "CALL_LOG_INFO"
                        )
                    }
                    // Do NOT execute call yet - wait for user response ("Yes" -> CALL_CONTACT)
                } else {
                    // CALL MODE: Execute call immediately
                    listenAfterSpeaking.set(false)
                    voice.speak(intent.acknowledgement ?: "Calling $displayName...")
                    
                    val callIntent = AssistantIntent.Action.CallContact(
                        contactName = callInfo.phoneNumber,
                        number = callInfo.phoneNumber,
                        acknowledgement = null
                    )
                    delay(1500) // Allow TTS to start before Dialer takes focus
                    val result = actionExecutor.execute(callIntent, null)
                    handleActionResult(result)
                }

            } else {
                // No call log entry found
                listenAfterSpeaking.set(false)
                voice.speak("Sorry, I couldn't find that call in your history.")
                endSession("No call log found")
            }
        }
        
        // Resolve contact using ContactResolver (ONLY source of truth for phone numbers)
        val resolution = contactResolver.resolveContact(contactName)
        
        when (resolution.status) {
            ContactResolver.ResolutionResult.Status.EXACT_MATCH -> {
                val contact = resolution.contacts.first()
                
                if (contact.phoneNumber.isNullOrBlank()) {
                    // GATEKEEPER: Contact exists but NO number. Do NOT execute.
                    listenAfterSpeaking.set(true)
                    
                    // Use the acknowledgement from refinement (which should ask for number)
                    val ack = intent.acknowledgement ?: "Found ${contact.displayName}, but I don't have a number saved. What is the number?"
                    voice.speak(ack)
                    
                    conversationContext = ConversationContext(
                        pendingAction = ConversationContext.PendingAction(
                            actionType = ConversationContext.ActionType.CALL_CONTACT,
                            missingParams = listOf("number"), // We need the number now
                            collectedParams = mutableMapOf("contact_name" to contact.displayName)
                        )
                    )
                } else {
                    // Valid number - Execute
                    listenAfterSpeaking.set(false)
                    voice.speak(intent.acknowledgement ?: "Calling ${contact.displayName}...")
                    
                    // Create CallContact with resolved phone number
                    val callIntent = AssistantIntent.Action.CallContact(
                        contactName = contact.displayName,
                        number = contact.phoneNumber,  // Resolved phone number
                        acknowledgement = null
                    )
                    
                    // Clear conversation context - clarification is complete!
                    // Next CALL_CONTACT request will start fresh
                    conversationContext = null
                    
                    delay(1500) // Allow TTS to start before Dialer takes focus
                    val result = actionExecutor.execute(callIntent, null)
                    handleActionResult(result)
                }
            }
            
            
            ContactResolver.ResolutionResult.Status.MULTIPLE_MATCHES -> {
                // LLM-DRIVEN DISAMBIGUATION
                listenAfterSpeaking.set(true)
                
                // CRITICAL: Ensure session is active to capture user response
                if (!sessionActive.get()) {
                    Log.w(TAG, "Session died during LLM processing. Reviving for disambiguation.")
                    sessionActive.set(true)
                }
                
                // Use LLM-generated acknowledgement if available (from processUserRequest refinement)
                // Otherwise generate one now.
                val clarificationQuestion = if (!intent.acknowledgement.isNullOrBlank()) {
                    intent.acknowledgement!!
                } else {
                     val count = resolution.contacts.size
                     val names = resolution.contacts.take(4).joinToString(", ") { it.displayName }
                     val prompt = "[System: User asked to call '$contactName', but I found $count matches: $names. Generate a short response asking 'Which one?' in the user's language.]"
                     chatResponder.respond(prompt)
                }
                
                voice.speak(clarificationQuestion)
                
                // Store ALL contact details in conversation context
                // LLM will use this on next turn to intelligently match user's response
                val contactsJson = resolution.contacts.joinToString(",") { contact ->
                    "{\"name\":\"${contact.displayName}\",\"number\":\"${contact.phoneNumber ?: ""}\",\"id\":\"${contact.id}\"}"
                }
                
                conversationContext = ConversationContext(
                    pendingAction = ConversationContext.PendingAction(
                        actionType = ConversationContext.ActionType.CALL_CONTACT,
                        collectedParams = mutableMapOf(
                            "contact_name" to contactName,
                            // Send contact list as JSON for LLM to analyze
                            "available_contacts" to "[$contactsJson]"
                        ),
                        missingParams = listOf("specific_contact")
                    )
                )
            }
            
            ContactResolver.ResolutionResult.Status.NO_MATCH -> {
                listenAfterSpeaking.set(true)
                val response = intent.acknowledgement ?: run {
                     val prompt = "[System: User asked to call '$contactName', but I cannot find that name in contacts. Generate a helpful response asking who they want to call.]"
                     chatResponder.respond(prompt)
                }
                voice.speak(response)
                conversationContext = ConversationContext(
                    pendingAction = ConversationContext.PendingAction(
                        actionType = ConversationContext.ActionType.CALL_CONTACT,
                        missingParams = listOf("contact_name")
                    )
                )
            }
            
            ContactResolver.ResolutionResult.Status.PERMISSION_DENIED -> {
                listenAfterSpeaking.set(false)
                val response = intent.acknowledgement ?: run {
                     val prompt = "[System: I need permission to access contacts to make this call. Explain this to the user politely.]"
                     chatResponder.respond(prompt)
                }
                voice.speak(response)
                endSession("Permission Required")
            }
        }
    }
    
    /**
     * Handle PlayMedia action with intelligent query validation.
     */
    private suspend fun handlePlayMediaWithResolution(intent: AssistantIntent.Action.PlayMedia) {
        val query = intent.query
        
        // If query is too vague or empty, ask for specifics
        if (query.isBlank() || query.length < 2) {
            listenAfterSpeaking.set(true)
            voice.speak("What would you like me to play?")
            conversationContext = ConversationContext(
                pendingAction = ConversationContext.PendingAction(
                    actionType = ConversationContext.ActionType.PLAY_MEDIA,
                    missingParams = listOf("query")
                )
            )
            return
        }
        
        // Check if query is too generic (single word, common terms)
        val genericTerms = listOf("music", "song", "video", "gana", "gaana")
        if (genericTerms.any { query.lowercase().trim() == it }) {
            listenAfterSpeaking.set(true)
            voice.speak("Sure! What kind of music? Any specific artist, genre, or song?")
            conversationContext = ConversationContext(
                pendingAction = ConversationContext.PendingAction(
                    actionType = ConversationContext.ActionType.PLAY_MEDIA,
                    collectedParams = mutableMapOf("query" to query),
                    missingParams = listOf("specific_query")
                )
            )
            return
        }
        
        // Query is specific enough - execute
        listenAfterSpeaking.set(false)
        voice.speak(intent.acknowledgement ?: "Playing ${query}...")
        val result = actionExecutor.execute(intent, null)  // No context needed for direct execution
        handleActionResult(result)
    }
    
    /**
     * Handle SetAlarm action with intelligent time validation.
     */
    private suspend fun handleSetAlarmWithResolution(intent: AssistantIntent.Action.SetAlarm) {
        val time = intent.timeText
        
        // If time is missing or unclear, ask for it
        if (time.isBlank()) {
            listenAfterSpeaking.set(true)
            voice.speak("What time should I set the alarm for?")
            conversationContext = ConversationContext(
                pendingAction = ConversationContext.PendingAction(
                    actionType = ConversationContext.ActionType.SET_ALARM,
                    missingParams = listOf("time")
                )
            )
            return
        }
        
        // Check if time format is ambiguous (e.g., just "7" without AM/PM)
        val timePattern = Regex("^\\d{1,2}$") // Just a number like "7"
        if (timePattern.matches(time.trim())) {
            listenAfterSpeaking.set(true)
            voice.speak("Got it, $time o'clock. Should that be AM or PM?")
            conversationContext = ConversationContext(
                pendingAction = ConversationContext.PendingAction(
                    actionType = ConversationContext.ActionType.SET_ALARM,
                    collectedParams = mutableMapOf("time" to time),
                    missingParams = listOf("am_pm")
                )
            )
            return
        }
        
        // Time is clear - execute
        listenAfterSpeaking.set(false)
        voice.speak(intent.acknowledgement ?: "Setting alarm for $time...")
        val result = actionExecutor.execute(intent, null)  // No context needed for direct execution
        handleActionResult(result)
    }

    // REMOVED: startAgentConversation - no longer using Agent

    private fun startListeningInternal(targetState: AssistantState) {
        transitionTo(targetState)
        // CRITICAL: Enable microphone when starting STT
        microphoneEnabled.set(true)
        stt.startListening(language)
    }
    
    private fun startWakeOnlyTimer() {
        wakeOnlyJob?.cancel()
        wakeOnlyJob = scope.launch {
            delay(WAKE_WORD_ONLY_CHECK_MS)
            if (isActive && sessionActive.get()) {
                // No partial text received -> User just said "Hey Aman" and paused.
                Log.i(TAG, "Wake Word Only detected. Requesting LLM acknowledgement...")
                
                // CRITICAL: Disable microphone before speaking acknowledgement
                microphoneEnabled.set(false)
                stt.stopListening() // Pause STT to speak
                
                // LLM-DRIVEN: Request contextually appropriate acknowledgement
                val wakeAck = requestWakeAcknowledgement()
                
                // Speak the LLM-generated acknowledgement
                listenAfterSpeaking.set(true) // Ensure STT restarts after acknowledgement
                voice.speak(wakeAck)
                
                // Mic will be re-opened by onSpeechEnded of the Ack
            }
        }
    }
    
    /**
     * Request LLM-generated wake acknowledgement.
     * Called when user says wake word but doesn't speak for 3 seconds.
     */
    private suspend fun requestWakeAcknowledgement(): String = withContext(Dispatchers.IO) {
        val contextPrompt = buildString {
            // Add user profile context
            userProfile?.let { profile ->
                appendLine("Context: You are ${profile.assistantName ?: "Assistant"}.")
                appendLine("The user is ${profile.preferredName ?: profile.userName ?: "User"}.")
                if (!profile.tonePreference.isNullOrBlank()) {
                    appendLine("Tone: ${profile.tonePreference}")
                }
            }
            appendLine()
            appendLine("The user said your wake word but hasn't spoken yet.")
            appendLine("Generate a SHORT, natural acknowledgement (1-2 words max) to prompt them to speak.")
            appendLine()
            appendLine("Examples:")
            when (language) {
                OnboardingLanguage.HINDI -> {
                    appendLine("- जी?")
                    appendLine("- हाँ बोलिये")
                    appendLine("- बताइये")
                    appendLine("- सुन रहा हूँ")
                }
                OnboardingLanguage.HINGLISH -> {
                    appendLine("- Haan?")
                    appendLine("- Ji bolo")
                    appendLine("- Batao")
                    appendLine("- Sun raha hoon")
                }
                OnboardingLanguage.ENGLISH -> {
                    appendLine("- Yes?")
                    appendLine("- Go on")
                    appendLine("- Listening")
                    appendLine("- I'm here")
                }
            }
            appendLine()
            appendLine("Language: ${language.displayName}")
            appendLine("IMPORTANT: Respond with ONLY the acknowledgement text, nothing else.")
        }
        
        return@withContext try {
            chatResponder.respond(contextPrompt).takeIf { it.isNotBlank() }
                ?: getDefaultWakeAcknowledgement() // Fallback if LLM fails
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get LLM wake acknowledgement", e)
            getDefaultWakeAcknowledgement()
        }
    }
    
    /**
     * Fallback acknowledgement if LLM fails.
     * Only used as emergency fallback - LLM should handle this normally.
     */
    private fun getDefaultWakeAcknowledgement(): String {
        return when (language) {
            OnboardingLanguage.HINDI -> "जी?"
            OnboardingLanguage.HINGLISH -> "Haan?"
            OnboardingLanguage.ENGLISH -> "Yes?"
        }
    }

    override fun onSpeechEnded() {
        // TTS finished.
        Log.i(TAG, "TTS Finished. listenAfterSpeaking=${listenAfterSpeaking.get()}, sessionActive=${sessionActive.get()}")

        if (!sessionActive.get()) {
             Log.i(TAG, "Session inactive, not restarting STT.")
             return
        }

        // STRICT SESSION LIFECYCLE:
        // - If listenAfterSpeaking is true: Restart STT (CONVERSATION or Clarify)
        // - If listenAfterSpeaking is false: End session (Action or STOP_SESSION)
        
        if (listenAfterSpeaking.get()) {
            Log.i(TAG, "Restarting STT for continued conversation after hardware settle delay...")
            // CALIBRATED: Increased delay to ensure audio hardware fully stops before STT starts
            // This prevents STT from picking up residual TTS audio
            sttRestartJob = scope.launch {
                delay(HARDWARE_SETTLE_DELAY_MS) // 850ms delay for audio hardware to settle
                if (sessionActive.get()) {
                    // CRITICAL: Re-enable microphone BEFORE starting STT
                    // This ensures onBeginningOfSpeech will be processed
                    microphoneEnabled.set(true)
                    startListeningInternal(AssistantState.ACTIVE_LISTENING)
                    resetSilenceTimer()
                }
            }
        } else {
            Log.i(TAG, "Ending session after TTS (action complete or explicit stop).")
            endSession("TTS Complete")
        }
    }

    // --- HELPERS ---

    private fun transitionTo(newState: AssistantState) {
        state = newState
        Log.d(TAG, "State: $newState")
    }

    private fun resetSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(SILENCE_TIMEOUT_MS)
            if (sessionActive.get()) {
                endSession("Silence Timeout")
            }
        }
    }
    
    private fun setContextActive(active: Boolean) {
        isContextActive.set(active)
        if(active) {
            scope.launch { 
                delay(8000) 
                isContextActive.compareAndSet(true, false) 
            }
        }
    }
    
    private fun checkStopCommand(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "stop" || lower == "bas" || lower == "cancel" || lower == "chup" || lower == "exit" || lower.contains("goodbye")
    }
    
    private fun isPositiveConfirmation(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("yes") || lower.contains("haan") || lower.contains("sure") || lower.contains("do it") || lower.contains("baja do")
    }
    
    private fun mapSuggestionToIntent(suggestion: String): String {
        return when(suggestion) {
            "play_music" -> "Play some music"
            "set_alarm" -> "Set an alarm"
            else -> suggestion
        }
    }
    
    private suspend fun checkForSuggestedAction(text: String): String? {
        val lower = text.lowercase()
        if (lower.contains("chala doon") || lower.contains("play")) return "play_music"
        return null
    }

    private fun removeWakeWord(text: String): String {
        var lower = text.lowercase()
        // Common transcriptions for "Hey Aman"
        val wakeWords = listOf("hey aman", "hi aman", "hello aman", "aman", "hey man", "he man", "hey", "hi")
        
        for (w in wakeWords) {
            if (lower.startsWith(w)) {
                return text.substring(w.length).trim()
            }
        }
        return text
    }

    // Unused overrides
    override fun onReady() {}
    override fun onEndOfSpeech() {} // Vosk timeout
    override fun onError(code: Int) {
        if (!sessionActive.get()) return
        
        Log.e(TAG, "STT Error $code")
        
        // Android SpeechRecognizer error codes
        when (code) {
            // Fatal errors - end session immediately
            9, // ERROR_INSUFFICIENT_PERMISSIONS
            3  // ERROR_AUDIO
            -> {
                endSession("Fatal STT Error: $code")
            }
            
            // Busy/Client errors - cancel and retry with delay
            8, // ERROR_RECOGNIZER_BUSY
            5  // ERROR_CLIENT
            -> {
                // CRITICAL: Same logic as ERROR_NO_MATCH - check state first
                if (state == AssistantState.SPEAKING) {
                    Log.d(TAG, "STT client error during TTS (expected). Will restart after TTS completes.")
                    return
                }
                
                if (state == AssistantState.UNDERSTANDING) {
                    Log.d(TAG, "STT client error during LLM processing (expected). Will restart after TTS completes.")
                    return
                }
                
                // Only retry if actively listening for user speech
                if (state == AssistantState.ACTIVE_LISTENING && sttRetryCount.incrementAndGet() <= MAX_STT_RETRIES) {
                    Log.w(TAG, "STT busy/client error while listening, retry ${sttRetryCount.get()}/$MAX_STT_RETRIES")
                    scope.launch {
                        stt.cancel() // Important: cancel first to clear busy state
                        delay(STT_RETRY_DELAY_MS)
                        if (sessionActive.get() && microphoneEnabled.get()) {
                            stt.startListening(language)
                        }
                    }
                } else if (sttRetryCount.get() > MAX_STT_RETRIES) {
                    Log.e(TAG, "STT retry limit exceeded")
                    endSession("STT Retry Limit Exceeded")
                } else {
                    Log.d(TAG, "STT client error in state $state, not retrying")
                }
            }
            
            // Network/server errors - retry with exponential backoff
            2,  // ERROR_NETWORK
            1,  // ERROR_NETWORK_TIMEOUT
            13  // ERROR_SERVER
            -> {
                if (sttRetryCount.incrementAndGet() <= MAX_STT_RETRIES) {
                    val delay = STT_RETRY_DELAY_MS * sttRetryCount.get()
                    Log.w(TAG, "STT network/server error, retry ${sttRetryCount.get()}/$MAX_STT_RETRIES after ${delay}ms")
                    scope.launch {
                        delay(delay)
                        if (sessionActive.get()) {
                            stt.startListening(language)
                        }
                    }
                } else {
                    Log.e(TAG, "Network error - retry limit exceeded")
                    endSession("Network Error - Retry Limit Exceeded")
                }
            }
            
            // Speech timeout - normal, just restart with small delay
            6  // ERROR_SPEECH_TIMEOUT
            -> {
                Log.d(TAG, "Speech timeout, restarting STT")
                // Don't count as retry - this is expected behavior
                scope.launch {
                    delay(200L)
                    if (sessionActive.get()) {
                        stt.startListening(language)
                    }
                }
            }
            
            // No match - restart without counting as error
            7  // ERROR_NO_MATCH
            -> {
                // CRITICAL: Only restart STT if we're actively listening for user speech
                // Do NOT restart if:
                // - TTS is speaking (SPEAKING state)
                // - LLM is processing (UNDERSTANDING state)
                // - We're doing something else (not ACTIVE_LISTENING)
                
                if (state == AssistantState.SPEAKING) {
                    Log.d(TAG, "STT stopped during TTS (expected - mic disabled). Will restart after TTS completes.")
                    return
                }
                
                if (state == AssistantState.UNDERSTANDING) {
                    Log.d(TAG, "STT stopped for LLM processing (expected). Will restart after TTS completes.")
                    return
                }
                
                // Only auto-restart if we were actively listening for user speech
                if (state == AssistantState.ACTIVE_LISTENING) {
                    Log.d(TAG, "No speech match while listening, restarting STT")
                    scope.launch {
                        delay(300L)
                        if (sessionActive.get() && microphoneEnabled.get()) {
                            stt.startListening(language)
                        }
                    }
                } else {
                    Log.d(TAG, "No speech match in state $state, not restarting STT")
                }
            }
            
            // Vosk-specific fatal errors (kept for compatibility)
            -1, // No Model
            -5, // Permission
            -6  // AudioRecord
            -> {
                endSession("Fatal STT Error: $code")
            }
            
            // Other errors - log and end session to prevent loops
            else -> {
                Log.w(TAG, "Unhandled STT error: $code, ending session")
                endSession("STT Error: $code")
            }
        }
    }

}
