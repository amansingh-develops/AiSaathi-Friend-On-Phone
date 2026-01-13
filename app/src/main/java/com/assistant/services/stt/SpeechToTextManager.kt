package com.assistant.services.stt

import android.content.Context.AUDIO_SERVICE
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.services.voice.SpeechToTextEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speech-to-Text manager focused on low latency.
 *
 * Performance rules:
 * - Reuse a single SpeechRecognizer instance during a session.
 * - Start listening immediately on wake.
 * - Enable partial results (for early intent inference).
 * - Prefer offline when available (lower latency / more reliable in weak network).
 *
 * Threading:
 * - SpeechRecognizer APIs must be called on the main thread.
 * - This class posts work to the main thread internally.
 */
class SpeechToTextManager(
    context: Context
) : SpeechToTextEngine {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AUDIO_SERVICE) as AudioManager

    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechToTextEngine.Listener? = null
    private val isListening = AtomicBoolean(false)

    companion object {
        private const val TAG = "SpeechToTextManager"
    }

    override fun setListener(listener: SpeechToTextEngine.Listener?) {
        this.listener = listener
    }

    override fun warmUp() {
        mainHandler.post { ensureRecognizer() }
    }

    override fun startListening(language: OnboardingLanguage, promptContext: String?) {
        mainHandler.post {
            ensureRecognizer()
            val r = recognizer ?: return@post
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                Log.w(TAG, "Speech recognition not available on this device.")
                listener?.onError(SpeechRecognizer.ERROR_CLIENT)
                return@post
            }
            if (isListening.get()) {
                // Don't spam startListening; keep one active session.
                return@post
            }

            val intent = buildRecognizerIntent(language)
            try {
                isListening.set(true)
                r.startListening(intent)
            } catch (e: Exception) {
                isListening.set(false)
                Log.w(TAG, "startListening failed", e)
                listener?.onError(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    override fun stopListening() {
        mainHandler.post {
            isListening.set(false)
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
            }
        }
    }

    override fun cancel() {
        mainHandler.post {
            isListening.set(false)
            try {
                recognizer?.cancel()
            } catch (_: Exception) {
            }
        }
    }

    override fun shutdown() {
        mainHandler.post {
            isListening.set(false)
            try {
                recognizer?.destroy()
            } catch (_: Exception) {
            } finally {
                recognizer = null
            }
        }
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        try {
            // Create SpeechRecognizer with explicit Google service
            // Note: We don't use on-device recognizer as it may not be available on all devices
            // and can cause ERROR_SERVER (13) if the service isn't properly configured
            val r = SpeechRecognizer.createSpeechRecognizer(appContext)
            
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listener?.onReady()
                }

                override fun onBeginningOfSpeech() {
                    listener?.onBeginningOfSpeech()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Not used; avoid UI-level noise.
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // Not used.
                }

                override fun onEndOfSpeech() {
                    listener?.onEndOfSpeech()
                }

                override fun onError(error: Int) {
                    isListening.set(false)
                    listener?.onError(error)
                }

                override fun onResults(results: Bundle?) {
                    isListening.set(false)
                    val best = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (best.isNotBlank()) listener?.onFinalText(best)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val best = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (best.isNotBlank()) listener?.onPartialText(best)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // Not used.
                }
            })
            recognizer = r
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create SpeechRecognizer; STT disabled.", e)
            recognizer = null
        }
    }

    private fun buildRecognizerIntent(language: OnboardingLanguage): Intent {
        val localeTag = when (language) {
            OnboardingLanguage.ENGLISH -> "en-IN"
            OnboardingLanguage.HINDI -> "hi-IN"
            // Hinglish doesn't have an official locale; we bias English (India) but allow mixing.
            OnboardingLanguage.HINGLISH -> "en-IN"
        }

        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)

            // Continuous-session tuning:
            // We keep silence thresholds longer to prevent frequent timeout->restart loops.
            // Partial results still arrive quickly for early intent decisions.
            // CALIBRATED: Increased timeouts to prevent premature cutoff and allow hardware settle time
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)

            // REMOVED: EXTRA_PREFER_OFFLINE - This can cause ERROR_SERVER (13) if offline
            // recognition isn't available. Let the system decide online vs offline.
            // putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

            // Language
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)

            // Hint: accept common India locales.
            putExtra(
                RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES,
                arrayListOf("hi-IN", "en-IN")
            )
        }
    }


}


