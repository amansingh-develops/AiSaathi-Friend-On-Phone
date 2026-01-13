package com.assistant.services.wakeword

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import com.assistant.BuildConfig
import com.assistant.debug.AgentDebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages Porcupine wake word detection logic.
 * 
 * Responsibilities:
 * - Initialize Porcupine with custom wake word
 * - Handle audio capture using AudioRecord
 * - Process audio frames through Porcupine
 * - Emit wake word detection events
 * 
 * Architecture:
 * - Encapsulates all Porcupine-specific logic
 * - Service calls this manager, not Porcupine directly
 * - Runs audio processing on IO dispatcher for performance
 */
class WakeWordManager(
    private val context: Context,
    private val wakeWord: String,
    private val onWakeWordDetected: () -> Unit
) {
    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private var audioProcessingJob: Job? = null
    
    // Audio configuration for Porcupine
    companion object {
        private const val TAG = "WakeWordManager"
        
        // Porcupine requires 16kHz, 16-bit, mono PCM audio
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Porcupine processes frames of 512 samples
        private const val FRAME_LENGTH = 512
        private const val BYTES_PER_SAMPLE = 2 // 16-bit = 2 bytes
        
        // Buffer size for AudioRecord (optimized for wake word detection)
        private fun getBufferSize(): Int {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            // Use larger buffer for more stable audio processing (4x frame size minimum)
            // This helps with wake word detection reliability
            val optimalBufferSize = FRAME_LENGTH * BYTES_PER_SAMPLE * 4
            return optimalBufferSize.coerceAtLeast(minBufferSize)
        }
    }
    
    /**
     * Initialize Porcupine with the custom wake word model (.ppn file).
     * 
     * Note: Uses custom .ppn file from assets directory.
     * The .ppn file is trained for "hey aman" wake word.
     * 
     * Reference: https://picovoice.ai/docs/quick-start/porcupine-android/
     */
    fun initialize(): Boolean {
        // #region agent log
        AgentDebugLog.log(
            context = context,
            runId = "run1",
            hypothesisId = "H1",
            location = "WakeWordManager.kt:initialize:entry",
            message = "initialize() called",
            data = AgentDebugLog.networkSnapshot(context) + mapOf(
                "wakeWord" to wakeWord,
                "thread" to Thread.currentThread().name
            )
        )
        // #endregion
        // #region agent log
        val hasInternetPermission = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
            } else true
        } catch (_: Exception) {
            null
        }
        AgentDebugLog.log(
            context = context,
            runId = "run1",
            hypothesisId = "H6",
            location = "WakeWordManager.kt:initialize:perm_check",
            message = "Permission snapshot",
            data = mapOf(
                "internetPermissionGranted" to hasInternetPermission
            )
        )
        // #endregion
        return try {
            // Get access key from BuildConfig (loaded from local.properties, gitignored)
            val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY
            
            if (accessKey.isEmpty()) {
                Log.e(TAG, "Picovoice access key not found. Please set PICOVOICE_ACCESS_KEY in local.properties")
                // #region agent log
                AgentDebugLog.log(
                    context = context,
                    runId = "run1",
                    hypothesisId = "H2",
                    location = "WakeWordManager.kt:initialize:key_empty",
                    message = "BuildConfig key empty",
                    data = mapOf("keyLen" to 0)
                )
                // #endregion
                return false
            }
            
            // Log first few characters of key for debugging (without exposing full key)
            Log.d(TAG, "Using access key: ${accessKey.take(10)}... (${accessKey.length} chars)")
            // #region agent log
            AgentDebugLog.log(
                context = context,
                runId = "run1",
                hypothesisId = "H2",
                location = "WakeWordManager.kt:initialize:key_shape",
                message = "Key shape",
                data = mapOf(
                    "keyLen" to accessKey.length,
                    "startsWith" to accessKey.take(6),
                    "hasWhitespace" to accessKey.any { it.isWhitespace() },
                    "hasQuotes" to (accessKey.contains("\"") || accessKey.contains("'"))
                ) + AgentDebugLog.networkSnapshot(context)
            )
            // #endregion
            
            // Load custom .ppn file from assets
            // The file should be placed in app/src/main/assets/
            val keywordPath = "hey-aman_en_android_v4_0_0.ppn"
            
            Log.d(TAG, "Initializing Porcupine with keyword: $keywordPath")
            // #region agent log
            AgentDebugLog.log(
                context = context,
                runId = "run1",
                hypothesisId = "H3",
                location = "WakeWordManager.kt:initialize:before_build",
                message = "Before Porcupine.Builder.build()",
                data = mapOf("keywordPath" to keywordPath) + AgentDebugLog.networkSnapshot(context)
            )
            // #endregion
            
            // Initialize Porcupine with custom keyword file
            // The .ppn file is trained for "hey aman" wake word
            // Increased sensitivity for better detection accuracy
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(arrayOf(keywordPath)) // Load custom .ppn from assets
                .setSensitivities(floatArrayOf(0.9f)) // Higher sensitivity for better detection
                .build(context)
            
            Log.d(TAG, "Porcupine initialized successfully with custom wake word model: $keywordPath (for wake word: $wakeWord)")
            // #region agent log
            AgentDebugLog.log(
                context = context,
                runId = "run1",
                hypothesisId = "H0",
                location = "WakeWordManager.kt:initialize:success",
                message = "Porcupine initialized successfully",
                data = mapOf("keywordPath" to keywordPath)
            )
            // #endregion
            true
        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to initialize Porcupine: ${e.message}", e)
            if (e.message?.contains("activation", ignoreCase = true) == true) {
                Log.e(TAG, "Activation failed. Possible causes:")
                Log.e(TAG, "1. Invalid or expired access key")
                Log.e(TAG, "2. Access key doesn't have Porcupine permissions enabled")
                Log.e(TAG, "3. Network connectivity required for first-time activation")
                Log.e(TAG, "Please verify your access key at: https://console.picovoice.ai/")
            }
            // #region agent log
            AgentDebugLog.log(
                context = context,
                runId = "run1",
                hypothesisId = "H1",
                location = "WakeWordManager.kt:initialize:porcupine_exception",
                message = "PorcupineException during init",
                data = mapOf(
                    "exceptionClass" to e.javaClass.name,
                    "exceptionMessage" to (e.message ?: "null")
                ) + AgentDebugLog.networkSnapshot(context)
            )
            // #endregion
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Porcupine: ${e.message}", e)
            // #region agent log
            AgentDebugLog.log(
                context = context,
                runId = "run1",
                hypothesisId = "H4",
                location = "WakeWordManager.kt:initialize:exception",
                message = "Non-Porcupine exception during init",
                data = mapOf(
                    "exceptionClass" to e.javaClass.name,
                    "exceptionMessage" to (e.message ?: "null")
                ) + AgentDebugLog.networkSnapshot(context)
            )
            // #endregion
            false
        }
    }
    
    /**
     * Start listening for the wake word.
     * 
     * Lifecycle:
     * 1. Initialize AudioRecord with proper configuration
     * 2. Start recording
     * 3. Launch coroutine to process audio frames
     * 4. Continuously read audio and process through Porcupine
     */
    fun startListening(scope: CoroutineScope) {
        if (audioRecord != null) {
            Log.w(TAG, "Already listening")
            return
        }
        
        val porcupine = this.porcupine
        if (porcupine == null) {
            Log.e(TAG, "Porcupine not initialized")
            return
        }
        
        try {
            // Initialize AudioRecord with best available audio source
            val bufferSize = getBufferSize()
            val audioSource = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                // Use UNPROCESSED for better wake word detection (no AGC, no noise reduction)
                try {
                    MediaRecorder.AudioSource.UNPROCESSED
                } catch (e: Exception) {
                    Log.w(TAG, "UNPROCESSED audio source not available, falling back to VOICE_RECOGNITION")
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                }
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }

            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                stopListening()
                return
            }
            
            // Start recording
            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord started")
            
            // Start processing audio frames
            audioProcessingJob = scope.launch(Dispatchers.IO) {
                processAudioFrames(porcupine)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio recording", e)
            stopListening()
        }
    }
    
    /**
     * Process audio frames continuously.
     * 
     * Flow:
     * 1. Read audio data into buffer (16-bit PCM)
     * 2. Convert to short array (Porcupine expects short[])
     * 3. Process frame through Porcupine
     * 4. If wake word detected, emit callback
     * 5. Repeat
     * 
     * Performance:
     * - Runs on IO dispatcher (non-blocking)
     * - Processes frames of 512 samples (efficient)
     * - Handles errors gracefully without stopping service
     */
    private suspend fun processAudioFrames(porcupine: Porcupine) {
        val audioRecord = this.audioRecord ?: return

        val frameBuffer = ShortArray(FRAME_LENGTH)
        val audioBuffer = ByteArray(FRAME_LENGTH * BYTES_PER_SAMPLE)

        // Audio preprocessing parameters
        val gainMultiplier = 1.5f // Slight gain boost for better wake word detection
        val noiseGateThreshold = 50 // Ignore very quiet audio
        
        Log.d(TAG, "Audio processing started")
        // #region agent log
        AgentDebugLog.log(
            context = context,
            runId = "run2",
            hypothesisId = "H7",
            location = "WakeWordManager.kt:processAudioFrames:start",
            message = "Audio processing loop started",
            data = mapOf(
                "frameLength" to FRAME_LENGTH,
                "sampleRate" to SAMPLE_RATE,
                "audioSource" to "VOICE_RECOGNITION"
            )
        )
        // #endregion
        
        try {
            var lastLevelLogMs = 0L
            var consecutiveLowLevels = 0
            val job = kotlin.coroutines.coroutineContext[Job]
            while (job?.isActive != false && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                // Read audio data
                val samplesRead = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                
                if (samplesRead < 0) {
                    Log.w(TAG, "AudioRecord.read() returned error: $samplesRead")
                    continue
                }
                
                // Convert bytes to shorts (16-bit PCM) with preprocessing
                // AudioRecord provides 16-bit PCM as byte array
                // Convert to short array for Porcupine with gain adjustment
                var j = 0
                for (i in 0 until samplesRead step 2) {
                    val sample = (audioBuffer[i].toInt() and 0xFF) or
                                 ((audioBuffer[i + 1].toInt() and 0xFF) shl 8)
                    // Apply gain and ensure we don't exceed short range
                    val amplifiedSample = (sample.toShort() * gainMultiplier).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    frameBuffer[j++] = amplifiedSample.toShort()
                }

                // #region agent log
                // Monitor audio levels to detect microphone issues
                val now = System.currentTimeMillis()
                if (now - lastLevelLogMs > 3000) {
                    var sumAbs = 0L
                    val n = j.coerceAtMost(frameBuffer.size).coerceAtLeast(1)
                    for (k in 0 until n) sumAbs += kotlin.math.abs(frameBuffer[k].toInt()).toLong()
                    val avgAbs = (sumAbs / n)

                    // Check for consistently low audio levels (potential microphone issue)
                    if (avgAbs < 100) { // Very low audio level threshold
                        consecutiveLowLevels++
                        if (consecutiveLowLevels >= 3) { // 3 consecutive low readings
                            Log.w(TAG, "⚠️ Microphone input appears very low! avgAbs=$avgAbs. Check microphone permissions and settings.")
                            consecutiveLowLevels = 0 // Reset counter
                        }
                    } else {
                        consecutiveLowLevels = 0 // Reset on good audio
                    }

                    AgentDebugLog.log(
                        context = context,
                        runId = "run2",
                        hypothesisId = "H7",
                        location = "WakeWordManager.kt:processAudioFrames:level",
                        message = "Audio level sample",
                        data = mapOf(
                            "samplesRead" to samplesRead,
                            "shorts" to n,
                            "avgAbs" to avgAbs,
                            "consecutiveLowLevels" to consecutiveLowLevels
                        )
                    )
                    lastLevelLogMs = now
                }
                // #endregion
                
                // Process frame through Porcupine
                // Porcupine.process() returns index of detected keyword (0-based)
                // Returns -1 if no wake word detected
                try {
                    val keywordIndex = porcupine.process(frameBuffer)
                    if (keywordIndex >= 0) {
                        Log.i(TAG, "🎯 Wake word detected! Keyword index: $keywordIndex")
                        // #region agent log
                        AgentDebugLog.log(
                            context = context,
                            runId = "run2",
                            hypothesisId = "H8",
                            location = "WakeWordManager.kt:processAudioFrames:detected",
                            message = "Porcupine detected wake word",
                            data = mapOf("keywordIndex" to keywordIndex)
                        )
                        // #endregion
                        onWakeWordDetected()
                    } else {
                        // Log occasional "no detection" to confirm processing is working
                        // This helps diagnose if the issue is detection vs. audio input
                        if (kotlin.random.Random.nextInt(100) < 2) { // 2% chance to log
                            Log.v(TAG, "No wake word detected in this frame (normal)")
                        }
                    }
                } catch (e: PorcupineException) {
                    Log.e(TAG, "Porcupine processing error", e)
                    // Don't stop processing on Porcupine errors - just log and continue
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in frame processing", e)
                    // For critical errors, add small delay to prevent tight error loops
                    kotlinx.coroutines.delay(100)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in audio processing loop", e)
        } finally {
            Log.d(TAG, "Audio processing stopped")
        }
    }
    
    /**
     * Stop listening for wake word.
     * 
     * Lifecycle:
     * 1. Cancel audio processing coroutine
     * 2. Stop AudioRecord
     * 3. Release AudioRecord resources
     * 4. Clean up references
     */
    fun stopListening() {
        try {
            // Cancel audio processing
            audioProcessingJob?.cancel()
            audioProcessingJob = null
            
            // Stop and release AudioRecord
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
                Log.d(TAG, "AudioRecord released")
            }
            audioRecord = null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording", e)
        }
    }
    
    /**
     * Get current audio input level for diagnostics.
     * Returns average absolute amplitude (0-32767 range).
     */
    fun getCurrentAudioLevel(): Int {
        val audioRecord = this.audioRecord ?: return 0
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) return 0

        try {
            val buffer = ShortArray(FRAME_LENGTH)
            val bytesRead = audioRecord.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                var sumAbs = 0L
                for (i in 0 until bytesRead) {
                    sumAbs += kotlin.math.abs(buffer[i].toInt())
                }
                return (sumAbs / bytesRead).toInt()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting audio level", e)
        }
        return 0
    }

    /**
     * Release Porcupine resources.
     *
     * Should be called when service is being destroyed.
     */
    fun release() {
        stopListening()
        try {
            porcupine?.delete()
            porcupine = null
            Log.d(TAG, "Porcupine released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Porcupine", e)
        }
    }
}
