package com.assistant.services.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.services.voice.SpeechToTextEngine
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [NEW] Foundational STT Manager.
 * 
 * CORE PRINCIPLES:
 * 1. Single Model: Hindi (Devanagari output).
 * 2. Strict State Machine: No overlapping states.
 * 3. VAD Driven: Silence (750ms) triggers endpointing.
 * 4. User Control: System waits for user to finish.
 */
class AssistantSTTManager(private val context: Context) : SpeechToTextEngine {

    companion object {
        private const val TAG = "AssistantSTTManager"
        private const val MODEL_NAME = "model-hi" // Specific Hindi Model request
        private const val SAMPLE_RATE = 16000
        private const val SILENCE_THRESHOLD_MS = 750L // Tuned for natural pauses
        private const val MAX_LISTEN_DURATION_MS = 15000L // 15s Hard cap safety
    }

    // STATE MACHINE
    private enum class State {
        IDLE,
        INITIALIZING,
        LISTENING_IDLE, // Mic on, waiting for speech
        USER_SPEAKING,  // Speech detected, VAD active
        FINAL_PROCESSING // Silence hit, processing result
    }

    private val currentState = AtomicReference(State.IDLE)
    
    // VOSK COMPONENTS
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val recognizerLock = Any() // Fix for SIGSEGV
    
    // TRANSCRIPT BUFFER
    // Vosk clears the buffer on 'result' (phrase end). We must accumulate these pieces
    // because we don't stop listening until *we* decide (silence).
    private val activeTranscript = StringBuilder()

    // AUDIO COMPONENTS
    private var audioRecord: AudioRecord? = null
    private var listeningThread: Thread? = null
    
    // FLAGS
    private val isRunning = AtomicBoolean(false)
    
    // LISTENERS
    private var listener: SpeechToTextEngine.Listener? = null
    
    // SCOPE FOR TIMERS
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var silenceJob: Job? = null
    private var maxDurationJob: Job? = null

    init {
        LibVosk.setLogLevel(LogLevel.INFO)
    }

    override fun setListener(listener: SpeechToTextEngine.Listener?) {
        this.listener = listener
    }

    override fun warmUp() {
        if (model == null && currentState.get() == State.IDLE) {
            transitionTo(State.INITIALIZING)
            scope.launch(Dispatchers.IO) {
                loadModel()
                if (model != null) transitionTo(State.IDLE)
            }
        }
    }

    private fun loadModel() {
        Log.d(TAG, "Loading Vosk Model: $MODEL_NAME")
        StorageService.unpack(context, MODEL_NAME, "model-hi",
            { m: Model ->
                model = m
                Log.i(TAG, "Vosk Model Loaded Successfully")
            },
            { e: IOException ->
                Log.e(TAG, "Failed to load Vosk model: ${e.message}")
                transitionTo(State.IDLE)
            }
        )
    }

    override fun startListening(language: OnboardingLanguage, promptContext: String?) {
        // Enforce State Machine Entry
        if (!transitionTo(State.LISTENING_IDLE)) {
            Log.w(TAG, "Cannot start listening: Invalid State ${currentState.get()}")
            return
        }
        
        // Reset transcript
        synchronized(activeTranscript) {
            activeTranscript.clear()
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission Denied")
            listener?.onError(-5)
            stopListening()
            return
        }

        scope.launch(Dispatchers.IO) {
            // Ensure model is ready
            var attempts = 0
            while (model == null && attempts < 50) { // Wait up to 5s
                delay(100)
                attempts++
            }

            if (model == null) {
                Log.e(TAG, "Model not available after wait")
                listener?.onError(-1)
                stopListening()
                return@launch
            }

            try {
                startAudioRecord()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AudioRecord", e)
                listener?.onError(-6)
                stopListening()
            }
        }
    }

    private fun startAudioRecord() {
        if (recognizer != null) {
            recognizer?.close()
            recognizer = null
        }
        // SYNC: Create recognizer under lock
        synchronized(recognizerLock) {
             recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        }

        val bufferSize = Math.max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            4096
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord initialization failed")
        }

        audioRecord?.startRecording()
        isRunning.set(true)
        
        Log.i(TAG, "Mic Started. Waiting for speech...")

        // Max Duration Safety
        startMaxDurationTimer()

        listeningThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (isRunning.get()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    processAudio(buffer, read)
                }
            }
        }.apply { start() }
    }

    private fun processAudio(buffer: ByteArray, len: Int) {
        // SYNC: Protect AcceptWaveForm
        val accepted = synchronized(recognizerLock) {
            try {
                if (!isRunning.get()) return@synchronized false 
                recognizer?.acceptWaveForm(buffer, len) ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Vosk Accept failed", e)
                false
            }
        }
        
        if (accepted) {
            // SYNC: Protect Result
            val result = synchronized(recognizerLock) { recognizer?.result ?: "" }
             handleIntermediateResult(result)
        } else {
            // SYNC: Protect PartialResult
            val partial = synchronized(recognizerLock) { recognizer?.partialResult ?: "" }
             handlePartialResult(partial)
        }
    }

    private fun handlePartialResult(json: String) {
        val text = parseVosk(json, "partial")
        if (text.isNotBlank()) {
            // Transition to SPEAKING if IDLE
            if (currentState.get() == State.LISTENING_IDLE) {
                if (transitionTo(State.USER_SPEAKING)) {
                    // Notify system
                    listener?.onBeginningOfSpeech() 
                }
            }
            
            // IF SPEAKING, Reset Silence Timer
            if (currentState.get() == State.USER_SPEAKING) {
                resetSilenceTimer()
                // Show Accumulated + Current Partial
                val currentTotal = synchronized(activeTranscript) { activeTranscript.toString() } + " " + text
                listener?.onPartialText(currentTotal.trim()) 
            }
        }
    }

    private fun handleIntermediateResult(json: String) {
        val text = parseVosk(json, "text")
        if (text.isNotBlank()) {
             // 1. Accumulate this confirmed piece
             synchronized(activeTranscript) {
                 if (activeTranscript.isNotEmpty()) activeTranscript.append(" ")
                 activeTranscript.append(text)
             }
             
             // 2. Treat as partial update for UI
             if (currentState.get() == State.LISTENING_IDLE) {
                  transitionTo(State.USER_SPEAKING)
                  listener?.onBeginningOfSpeech()
             }
             if (currentState.get() == State.USER_SPEAKING) {
                 resetSilenceTimer()
                 val currentTotal = synchronized(activeTranscript) { activeTranscript.toString() }
                 listener?.onPartialText(currentTotal.trim()) // Accumulated text visual
             }
        }
    }

    private fun resetSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(SILENCE_THRESHOLD_MS)
            // Timeout hit!
            if (currentState.get() == State.USER_SPEAKING) {
                Log.d(TAG, "Silence Detected ($SILENCE_THRESHOLD_MS ms). Finalizing.")
                commitResult()
            }
        }
    }
    
    private fun startMaxDurationTimer() {
        maxDurationJob?.cancel()
        maxDurationJob = scope.launch {
            delay(MAX_LISTEN_DURATION_MS)
            if (isRunning.get()) {
                 Log.w(TAG, "Max Duration Reached. Forcing commit.")
                 commitResult()
            }
        }
    }

    private fun commitResult() {
        if (!transitionTo(State.FINAL_PROCESSING)) return

        // 1. Stop Mic
        isRunning.set(false)
        try {
             audioRecord?.stop()
             audioRecord?.release()
        } catch(e: Exception) {}
        audioRecord = null
        
        // 2. Get Remainder Text (Final flush)
        // SYNC: Protect finalResult
        val remainderText = synchronized(recognizerLock) {
            val finalJson = recognizer?.finalResult ?: ""
            parseVosk(finalJson, "text")
        }
        
        // 3. Combine
        val finalText = synchronized(activeTranscript) {
            if (remainderText.isNotBlank()) {
                if (activeTranscript.isNotEmpty()) activeTranscript.append(" ")
                activeTranscript.append(remainderText)
            }
            activeTranscript.toString().trim()
        }
        
        Log.i(TAG, "Final Result Committed: '$finalText'")
        
        // 4. Emit
        listener?.onFinalText(finalText)
        
        // 5. Reset
        transitionTo(State.IDLE)
    }

    override fun stopListening() {
        // Validation: If we are already Idle, do nothing
        if (currentState.get() == State.IDLE) return
        
        Log.i(TAG, "Stop Requested.")
        isRunning.set(false) // This stops the loop
        silenceJob?.cancel()
        maxDurationJob?.cancel()
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) { /* ignore */ }
        audioRecord = null
        
        transitionTo(State.IDLE)
    }

    override fun cancel() {
        stopListening()
    }

    override fun shutdown() {
        stopListening()
        synchronized(recognizerLock) {
            recognizer?.close()
            recognizer = null
        }
        model?.close()
        scope.cancel()
    }

    // --- UTILS ---

    private fun transitionTo(newState: State): Boolean {
        val success = when (newState) {
            State.IDLE -> {
                currentState.set(State.IDLE)
                true
            }
            State.INITIALIZING -> currentState.compareAndSet(State.IDLE, State.INITIALIZING)
            State.LISTENING_IDLE -> {
                // Can enter from IDLE (Wake) 
                currentState.set(State.LISTENING_IDLE)
                true // Force set for robustness
            }
            State.USER_SPEAKING -> currentState.compareAndSet(State.LISTENING_IDLE, State.USER_SPEAKING) || currentState.get() == State.USER_SPEAKING
            State.FINAL_PROCESSING -> currentState.compareAndSet(State.USER_SPEAKING, State.FINAL_PROCESSING) || currentState.compareAndSet(State.LISTENING_IDLE, State.FINAL_PROCESSING) // Allow fast finalize if force stopped
        }
        
        if (success) {
             Log.v(TAG, "State -> $newState")
        }
        return success
    }

    private fun parseVosk(json: String, key: String): String {
        return try {
            val j = JSONObject(json)
            j.optString(key, "")
        } catch (e: Exception) { "" }
    }
}
