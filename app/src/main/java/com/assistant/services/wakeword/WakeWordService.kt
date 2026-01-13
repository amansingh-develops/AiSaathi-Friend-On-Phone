package com.assistant.services.wakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.assistant.MainActivity
import com.assistant.data.repository.UserProfileRepository
import com.assistant.debug.AgentDebugLog
import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.domain.model.VoiceGender

import com.assistant.services.actions.ActionExecutor
import com.assistant.services.assistant.AssistantOrchestrator
import com.assistant.services.chat.GeminiChatResponder
import com.assistant.services.chat.LocalFallbackChatResponder
import com.assistant.services.elevenlabs.ElevenLabsVoiceOutput
// import com.assistant.services.stt.AssistantSTTManager // Vosk STT (kept for potential future use)
import com.assistant.services.stt.SpeechToTextManager
import com.assistant.services.gemini.GeminiClient
import com.assistant.services.router.GeminiRouter
import com.assistant.services.audio.SoundEffectManager
import com.assistant.services.ack.AcknowledgementManager
import com.assistant.services.intent.LocalHeuristicIntentInterpreter
import com.assistant.services.listening.ListeningSessionManager
import com.assistant.services.tts.TextToSpeechManager
import com.assistant.services.voice.AndroidTtsVoiceOutput
import com.assistant.services.voice.VoiceOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ForegroundService for continuous wake word detection.
 * 
 * Architecture:
 * - Runs as ForegroundService (required for continuous audio capture)
 * - Uses WakeWordManager for Porcupine logic
 * - Lifecycle-aware and battery-efficient
 * - Works offline, no network required
 * 
 * Service Lifecycle:
 * 1. onCreate() - Initialize notification channel and manager
 * 2. onStartCommand() - Start as foreground service, initialize wake word detection
 * 3. onBind() - Return binder for local binding (optional)
 * 4. onDestroy() - Clean up resources
 * 
 * Audio Handling:
 * - Uses AudioRecord for low-latency capture
 * - Continues working while other apps play audio (uses VOICE_RECOGNITION source)
 * - Processes audio on background thread (IO dispatcher)
 * - Battery-safe: only processes when actively listening
 */
class WakeWordService : Service() {
    
    private val serviceScope = CoroutineScope(SupervisorJob())
    private var wakeWordManager: WakeWordManager? = null
    private var ttsManager: TextToSpeechManager? = null
    private var voiceOutput: VoiceOutput? = null
    private var elevenVoiceOutput: ElevenLabsVoiceOutput? = null
    private var userProfileRepository: UserProfileRepository? = null
    private var currentVoiceGender: VoiceGender = VoiceGender.NEUTRAL
    private var currentLanguage: OnboardingLanguage = OnboardingLanguage.ENGLISH

    // Ultra-fast assistant pipeline (non-UI).
    private var assistantOrchestrator: AssistantOrchestrator? = null
    
    private val binder = LocalBinder()
    
    // Configuration - wake word from user profile (set during onboarding)
    // Note: The .ppn model is trained for "hey aman", so we use that
    private var currentWakeWord: String = "hey aman"
    @Volatile private var isWakeWordPausedForSession: Boolean = false
    
    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "WakeWordServiceChannel"
        private const val NOTIFICATION_ID = 1
        
        // Action constants
        const val ACTION_START = "com.assistant.wakeword.START"
        const val ACTION_STOP = "com.assistant.wakeword.STOP"
        const val ACTION_UPDATE_PREFS = "com.assistant.wakeword.UPDATE_PREFS"
        const val EXTRA_WAKE_WORD = "wake_word"
        /**
         * Optional extra to update voice without touching wake-word flow.
         *
         * Expected values: "MALE", "FEMALE", "NEUTRAL"
         */
        const val EXTRA_VOICE_GENDER = "voice_gender"
        /**
         * Optional extra to update onboarding language while service is running.
         *
         * Expected values: "hi", "hi-en", "en" (or common labels like "hindi")
         */
        const val EXTRA_PREFERRED_LANGUAGE = "preferred_language"
        const val EXTRA_ASSISTANT_NAME = "assistant_name"
        const val EXTRA_PREFERRED_NAME = "preferred_name"
        const val WAKE_WORD_DETECTED_ACTION = "com.assistant.WAKE_WORD_DETECTED"
    }
    
    /**
     * Binder for local service binding (if needed by other components).
     */
    inner class LocalBinder : Binder() {
        fun getService(): WakeWordService = this@WakeWordService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")

        // Load user profile (fast local read) so language is correct for instant acknowledgement + STT.
        userProfileRepository = UserProfileRepository(applicationContext)
        try {
            val profile = userProfileRepository?.getUserProfile()
            currentLanguage = OnboardingLanguage.fromCode(profile?.preferredLanguage)
        } catch (e: Exception) {
            // Never crash on profile issues; default to English.
            Log.w(TAG, "Failed to read preferredLanguage from profile; defaulting to ENGLISH.", e)
            currentLanguage = OnboardingLanguage.ENGLISH
        }

        // Voice output selection:
        // STACK: Streaming ElevenLabs only.
        elevenVoiceOutput = ElevenLabsVoiceOutput(applicationContext)
        voiceOutput = elevenVoiceOutput // Force ElevenLabs

        // Build the assistant pipeline (ack + STT session + intent routing).
        // WakeWordService remains "detection-only": it just forwards events to this orchestrator.
        val voice = voiceOutput
        if (voice != null) {
            val ack = AcknowledgementManager(voice)

            // New dependencies
            val soundManager = SoundEffectManager(applicationContext)
            val geminiClient = GeminiClient() 
            
            // Initialize OpenRouter client for LLM fallback
            val openRouterClient = com.assistant.services.openrouter.OpenRouterClient()
            
            // Initialize UnifiedLLMClient with Gemini → OpenRouter fallback
            val unifiedLLMClient = com.assistant.services.llm.UnifiedLLMClient(
                geminiClient = geminiClient,
                openRouterClient = openRouterClient
            )
            
            // Initialize TTS manager for permission negotiation
            val ttsManager = TextToSpeechManager(applicationContext)
            
            // Initialize PermissionNegotiator for LLM-driven permission requests
            val permissionNegotiator = com.assistant.services.permissions.PermissionNegotiator(
                context = applicationContext,
                llmClient = unifiedLLMClient,
                ttsManager = ttsManager
            )
            
            // Initialize ExecutionGuard for permission-guarded action execution
            val executionGuard = com.assistant.services.actions.ExecutionGuard(
                context = applicationContext,
                permissionNegotiator = permissionNegotiator,
                ttsManager = ttsManager,
                userLanguage = currentLanguage
            )
            
            // Speech-to-text selection:
            // STACK: Android SpeechRecognizer (Google Voice Typing)
            // Previous: Vosk (Offline) - AssistantSTTManager (kept in codebase, not deleted)
            val stt = SpeechToTextManager(applicationContext)
            
            val interpreter = if (geminiClient.isConfigured()) {
                com.assistant.services.intent.GeminiIntentInterpreter(geminiClient)
            } else {
                LocalHeuristicIntentInterpreter()
            }
            val geminiChat = GeminiChatResponder()
            val chat = if (geminiChat.isConfigured()) geminiChat else LocalFallbackChatResponder()
            
            // Create ActionExecutor with ExecutionGuard
            val actions = ActionExecutor(
                context = applicationContext,
                executionGuard = executionGuard
            )
            
            val audioFocus = com.assistant.services.audio.AudioFocusManager(applicationContext)
            
            val router = GeminiRouter(geminiClient)

            val contactResolver = com.assistant.services.contacts.ContactResolver(this)
            val historyManager = com.assistant.services.history.ConversationHistoryManager(this)
            
            val session = ListeningSessionManager(
                appContext = applicationContext,
                stt = stt,
                voice = voice,
                intentInterpreter = interpreter,
                chatResponder = chat,
                actionExecutor = actions,
                audioFocusManager = audioFocus,
                soundEffectManager = soundManager,
                geminiRouter = router,
                acknowledgementManager = ack,
                contactResolver = contactResolver,
                historyManager = historyManager,
                onSessionEnded = {
                    // Give microphone back to wake-word detection after the session ends.
                    resumeWakeWordListeningAfterSession()
                }
            )
            assistantOrchestrator = AssistantOrchestrator(
                acknowledgementManager = ack,
                listeningSessionManager = session
            ).also { orchestrator ->
                orchestrator.setVoiceGender(currentVoiceGender)
                orchestrator.setLanguage(currentLanguage)
                // Set User Profile (Name, Tone, Assistant Identity)
                val profile = try { userProfileRepository?.getUserProfile() } catch (_: Exception) { null }
                orchestrator.setUserProfile(profile)
                
                // If Gemini chat is active, update basic preferrences (Language handled by setLanguage above)
                // Note: GeminiChatResponder might need updates to support full profile later, but for now name/lang is enough fallback.
                if (chat is GeminiChatResponder) {
                    chat.setLanguage(currentLanguage)
                    chat.setPreferredName(profile?.preferredName ?: profile?.userName)
                }
            }


        }
        
        // Create notification channel (required for Android O+)
        createNotificationChannel()
        
        // NOTE: DO NOT call startForeground() here!
        // On Android 14+ (API 34), startForeground() with type "microphone" requires
        // RECORD_AUDIO permission. We must check permissions first in onStartCommand().
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand()")
        
        // Check if RECORD_AUDIO permission is granted BEFORE starting foreground service
        // This is required on Android 14+ (API 34) for foregroundServiceType="microphone"
        if (android.content.pm.PackageManager.PERMISSION_GRANTED != 
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)) {
            Log.e(TAG, "RECORD_AUDIO permission not granted. Stopping service.")
            // Stop service without calling startForeground() to avoid SecurityException
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Now that permissions are verified, start as foreground service
        // This must be called within 5 seconds of startForegroundService() or service will be killed
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d(TAG, "Service started as foreground")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting foreground service. Missing permissions?", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        
        when (intent?.action) {
            ACTION_START -> {
                val wakeWord = intent.getStringExtra(EXTRA_WAKE_WORD) ?: currentWakeWord
                // Optional: voice gender can be pushed in by whichever component owns settings/profile.
                // Wake word flow itself only calls speak().
                updateVoiceGenderFromIntent(intent)
                updateLanguageFromIntent(intent)
                startWakeWordDetection(wakeWord)
            }
            ACTION_STOP -> {
                stopWakeWordDetection()
                stopSelf()
            }
            ACTION_UPDATE_PREFS -> {
                // Update runtime preferences.
                updateVoiceGenderFromIntent(intent)
                updateLanguageFromIntent(intent)
                updateNamesFromIntent(intent)
                updateWakeWordFromIntent(intent)
            }
            else -> {
                // Default: start with configured wake word
                updateVoiceGenderFromIntent(intent)
                updateLanguageFromIntent(intent)
                startWakeWordDetection(currentWakeWord)
            }
        }
        
        // Return START_STICKY to restart service if killed by system
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy()")
        
        // Clean up resources
        stopWakeWordDetection()
        try { voiceOutput?.shutdown() } catch (_: Exception) {}
        try { assistantOrchestrator?.shutdown() } catch (_: Exception) {}
        voiceOutput = null
        elevenVoiceOutput = null
        ttsManager = null
        userProfileRepository = null
        serviceScope.cancel()
    }
    
    /**
     * Start wake word detection with specified wake word.
     * 
     * Flow:
     * 1. Release previous manager (if any)
     * 2. Create new WakeWordManager with wake word
     * 3. Initialize Porcupine
     * 4. Start listening
     */
    private fun startWakeWordDetection(wakeWord: String) {
        Log.d(TAG, "Starting wake word detection for: $wakeWord")
        // Keep service/UI messaging consistent with what we *intend* to listen for
        currentWakeWord = wakeWord
        
        // Stop previous detection if running
        stopWakeWordDetection()
        
        // Create new manager
        wakeWordManager = WakeWordManager(
            context = applicationContext,
            wakeWord = wakeWord,
            onWakeWordDetected = {
                handleWakeWordDetected()
            }
        )
        
        // Initialize on a background thread to avoid blocking the main thread (activation can take seconds)
        // #region agent log
        AgentDebugLog.log(
            context = applicationContext,
            runId = "run1",
            hypothesisId = "H5",
            location = "WakeWordService.kt:startWakeWordDetection:launch_init",
            message = "Launching Porcupine init off main thread",
            data = mapOf("thread" to Thread.currentThread().name, "wakeWord" to wakeWord) + AgentDebugLog.networkSnapshot(applicationContext)
        )
        // #endregion
        serviceScope.launch(Dispatchers.IO) {
            val ok = try {
                wakeWordManager?.initialize() == true
            } catch (e: Exception) {
                Log.e(TAG, "Exception during wake word init (License Limit?)", e)
                
                // Notify user of the specific error
                val isLicenseLimit = e.javaClass.name.contains("ActivationLimitException") || 
                                     e.message?.contains("ActivationLimitException") == true
                                     
                runOnMainThread {
                   val msg = if (isLicenseLimit) 
                       "Wake Word Limit Reached (3 devices). Get a new Key." 
                   else 
                       "Wake word init failed: ${e.message}"
                   
                   Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                }
                false
            }

            if (ok) {
                wakeWordManager?.startListening(serviceScope)
                Log.d(TAG, "Wake word detection started")
            } else {
                Log.e(TAG, "Failed to initialize wake word detection")
                wakeWordManager = null
                
                // Stop service to prevent battery drain or zombie state
                stopSelf()
            }
        }
    }
    
    /**
     * Stop wake word detection and release resources.
     */
    private fun stopWakeWordDetection() {
        Log.d(TAG, "Stopping wake word detection")
        wakeWordManager?.stopListening()
        wakeWordManager?.release()
        wakeWordManager = null
    }
    
    /**
     * Handle wake word detection event.
     * 
     * This is called by WakeWordManager when wake word is detected.
     * Broadcasts event to UI and shows Toast for testing.
     */
    private fun handleWakeWordDetected() {
        Log.i(TAG, "Wake word detected: $currentWakeWord")
        // #region agent log
        AgentDebugLog.log(
            context = applicationContext,
            runId = "run2",
            hypothesisId = "H8",
            location = "WakeWordService.kt:handleWakeWordDetected",
            message = "Service broadcasting wake word detected",
            data = mapOf(
                "action" to WAKE_WORD_DETECTED_ACTION,
                "wakeWord" to currentWakeWord
            )
        )
        // #endregion
        
        // Broadcast wake word detection event for UI
        val intent = Intent(WAKE_WORD_DETECTED_ACTION).apply {
            putExtra("wake_word", currentWakeWord)
        }
        sendBroadcast(intent)
        
        // Show Toast on main thread (for testing)
        runOnMainThread {
            Toast.makeText(
                applicationContext,
                "Wake word detected!",
                Toast.LENGTH_SHORT
            ).show()
        }

        // IMPORTANT: Release the microphone from wake-word capture before starting STT.
        // Otherwise ElevenLabs STT (AudioRecord) may fail to capture audio and you'll get silence / STT errors.
        pauseWakeWordListeningForSession()

        // Ultra-fast pipeline:
        // 1) IMMEDIATE local acknowledgement (no AI)
        // 2) Start STT in parallel and keep mic open for follow-ups
        assistantOrchestrator?.onWakeWordDetected()
        
        // In production, this would:
        // - Start voice recognition
        // - Trigger assistant interaction
        // - Emit event to ViewModel/Activity
    }

    /**
     * Allows safe, non-UI updates to the TTS voice preference.
     *
     * Wake word detection code path remains: wake word -> speak().
     */
    fun setVoiceGender(gender: VoiceGender) {
        currentVoiceGender = gender
        try {
            assistantOrchestrator?.setVoiceGender(gender)
            voiceOutput?.setVoiceGender(gender)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply voice gender=$gender; using default voice.", e)
        }
    }

    fun setOnboardingLanguage(language: OnboardingLanguage) {
        currentLanguage = language
        try {
            assistantOrchestrator?.setLanguage(language)
        } catch (_: Exception) {
        }
    }

    private fun updateVoiceGenderFromIntent(intent: Intent?) {
        val raw = intent?.getStringExtra(EXTRA_VOICE_GENDER) ?: return
        val gender = try {
            VoiceGender.valueOf(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Unknown voice gender extra='$raw'; keeping current=$currentVoiceGender", e)
            return
        }
        setVoiceGender(gender)
    }

    private fun updateLanguageFromIntent(intent: Intent?) {
        val raw = intent?.getStringExtra(EXTRA_PREFERRED_LANGUAGE) ?: return
        val lang = OnboardingLanguage.fromCode(raw)
        setOnboardingLanguage(lang)

        // Also refresh preferred name if profile was updated.
    }

    private fun updateNamesFromIntent(intent: Intent?) {
        val assistantName = intent?.getStringExtra(EXTRA_ASSISTANT_NAME)
        val preferredName = intent?.getStringExtra(EXTRA_PREFERRED_NAME)
        
        if (assistantName != null || preferredName != null) {
            assistantOrchestrator?.setPreferredName(preferredName ?: assistantName)
            
            // Sync with Gemini Chat Responder if it exists
            assistantOrchestrator?.let { orchestrator ->
                // Note: If we had direct access to chat responder we'd update it here.
                // Orchestrator already has logic to setPreferredName on its internal objects.
            }
        }
    }

    private fun updateWakeWordFromIntent(intent: Intent?) {
        val wakeWord = intent?.getStringExtra(EXTRA_WAKE_WORD) ?: return
        if (wakeWord != currentWakeWord) {
            Log.i(TAG, "Wake word changed from $currentWakeWord to $wakeWord. Restarting detection.")
            updateWakeWord(wakeWord)
        }
    }

    
    /**
     * Run code on main thread.
     */
    private fun runOnMainThread(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(action)
        }
    }
    
    /**
     * Create notification channel for Android O+.
     * 
     * Required for foreground services on Android 8.0+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wake Word Detection",
                NotificationManager.IMPORTANCE_LOW // Low priority, no sound
            ).apply {
                description = "Listening for wake word"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create foreground service notification.
     * 
     * Required to run as foreground service.
     * Uses low priority to minimize visual impact.
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wake Word Detection")
            .setContentText("Listening for: $currentWakeWord")
            .setSmallIcon(android.R.drawable.ic_media_play) // Use system icon for now
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Cannot be dismissed
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    /**
     * Update wake word dynamically.
     * 
     * Can be called while service is running to change wake word.
     */
    fun updateWakeWord(wakeWord: String) {
        currentWakeWord = wakeWord
        startWakeWordDetection(wakeWord)
        
        // Update notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun pauseWakeWordListeningForSession() {
        if (isWakeWordPausedForSession) return
        isWakeWordPausedForSession = true
        try {
            wakeWordManager?.stopListening()
        } catch (_: Exception) {
        }
    }

    private fun resumeWakeWordListeningAfterSession() {
        if (!isWakeWordPausedForSession) return
        isWakeWordPausedForSession = false
        try {
            wakeWordManager?.startListening(serviceScope)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resume wake-word listening; restarting detection.", e)
            // As a fallback, re-init detection.
            startWakeWordDetection(currentWakeWord)
        }
    }
}

