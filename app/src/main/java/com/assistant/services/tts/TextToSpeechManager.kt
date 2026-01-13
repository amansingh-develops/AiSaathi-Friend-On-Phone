package com.assistant.services.tts

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.assistant.domain.model.VoiceGender
import com.assistant.domain.onboarding.OnboardingLanguage
import java.util.Locale
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Lightweight, reusable Text-To-Speech wrapper.
 *
 * Design goals for wake-word responsiveness:
 * - Initialize the TTS engine once (async) and reuse it.
 * - Choose / update the desired voice *before* speaking.
 * - Switching gender is safe and does not reinitialize the engine.
 * - If preferred voices are missing, silently fall back to system default voice.
 *
 * IMPORTANT LIMITATION (platform reality):
 * Android's [Voice] API does not provide a reliable gender attribute across engines.
 * We approximate "male/female" via:
 * - Voice features / name keywords (if present)
 * - Locale preference (hi-IN / en-IN)
 * - Quality/latency signals
 * - "network/neural" name hints (common in Google TTS voice names)
 */
class TextToSpeechManager(
    context: Context
) : TextToSpeech.OnInitListener {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioFocusManager = com.assistant.services.audio.AudioFocusManager(context)

    private val isInitStarted = AtomicBoolean(false)
    @Volatile private var isReady: Boolean = false

    @Volatile private var requestedGender: VoiceGender = VoiceGender.NEUTRAL
    @Volatile private var appliedGender: VoiceGender = VoiceGender.NEUTRAL
    @Volatile private var appliedVoiceName: String? = null
    @Volatile private var languageHint: OnboardingLanguage = OnboardingLanguage.ENGLISH

    // Keep wake-word acks snappy if speak() is called before init completes.
    private val pendingUtterances: ArrayDeque<String> = ArrayDeque()

    private var tts: TextToSpeech? = null
    private val random = Random.Default

    companion object {
        private const val TAG = "TextToSpeechManager"

        // Target locales per your scope (India English + Hindi).
        private val LOCALE_HI_IN = Locale("hi", "IN")
        private val LOCALE_EN_IN = Locale("en", "IN")

        // Upper bound to avoid unbounded memory if speak() gets called repeatedly before init.
        private const val MAX_PENDING_UTTERANCES = 6
    }

    init {
        // Construct TTS on main thread. Initialization callback is async.
        startIfNeeded()
    }

    /**
     * Optional hint to bias voice + prosody selection.
     *
     * This keeps the public API stable (still works with just setVoiceGender+speak),
     * but allows the assistant to sound more natural when switching languages.
     */
    fun setLanguageHint(language: OnboardingLanguage) {
        languageHint = language
        mainHandler.post {
            // If ready, allow a voice re-evaluation next speak (without engine re-init).
            appliedGender = VoiceGender.NEUTRAL // force re-apply path to consider locale bias
        }
    }

    /**
     * Set the user's preferred voice gender.
     *
     * This does NOT reinitialize the engine.
     * If TTS is ready, we attempt to apply a better matching voice immediately (on main thread).
     */
    fun setVoiceGender(gender: VoiceGender) {
        requestedGender = gender
        mainHandler.post {
            startIfNeeded()
            if (isReady) {
                applyPreferredVoiceIfNeeded()
            }
        }
    }

    /**
     * Speak text using the currently configured engine/voice.
     *
     * - Voice selection happens before speaking.
     * - If TTS isn't ready yet, we queue a small number of utterances and return immediately.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        mainHandler.post {
            startIfNeeded()
            val engine = tts
            if (!isReady || engine == null) {
                enqueuePending(text)
                return@post
            }

            // Request focus before speaking
            audioFocusManager.requestOutputFocus()

            applyPreferredVoiceIfNeeded()
            speakInternal(engine, text)
        }
    }

    /**
     * Stop any current speech immediately and abandon audio focus.
     */
    fun stop() {
        mainHandler.post {
            try {
                tts?.stop()
                pendingUtterances.clear()
            } catch (_: Exception) {
            } finally {
                audioFocusManager.abandonOutputFocus()
            }
        }
    }

    /**
     * Optional cleanup (recommended from Service/Application on shutdown).
     */
    fun shutdown() {
        mainHandler.post {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (_: Exception) {
                // Never crash on shutdown.
            } finally {
                audioFocusManager.abandonOutputFocus()
                tts = null
                isReady = false
            }
        }
    }

    interface Listener {
        fun onSpeechStarted()
        fun onSpeechEnded()
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    override fun onInit(status: Int) {
        // Called on the thread that created TextToSpeech (main thread here).
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed (status=$status). Will fall back to silent no-op until re-created.")
            isReady = false
            return
        }

        val engine = tts
        if (engine == null) {
            isReady = false
            return
        }

        isReady = true

        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post { 
                     listener?.onSpeechStarted()
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post { 
                    audioFocusManager.abandonOutputFocus()
                    listener?.onSpeechEnded()
                }
            }

            override fun onError(utteranceId: String?) {
                mainHandler.post { 
                    audioFocusManager.abandonOutputFocus()
                    listener?.onSpeechEnded()
                }
            }
        })

        // Apply preferred voice once at init, then flush any queued speak() calls.
        applyPreferredVoiceIfNeeded()
        flushPending(engine)
    }

    private fun startIfNeeded() {
        if (tts != null) return
        if (!isInitStarted.compareAndSet(false, true)) return

        // Create on main thread (we're always called via mainHandler in public APIs).
        try {
            // Prefer Google TTS engine if installed (better chance of "network/neural" voices).
            // If unavailable, fall back to system default engine.
            val preferredEngine = findGoogleTtsEnginePackage()
            tts = if (preferredEngine != null) {
                TextToSpeech(appContext, this, preferredEngine)
            } else {
                TextToSpeech(appContext, this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create TextToSpeech instance; will remain disabled.", e)
            tts = null
            isReady = false
        }
    }

    private fun findGoogleTtsEnginePackage(): String? {
        return try {
            val pm = appContext.packageManager
            val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
            resolveInfos
                .firstOrNull { it.serviceInfo?.packageName == "com.google.android.tts" }
                ?.serviceInfo
                ?.packageName
        } catch (_: Exception) {
            null
        }
    }

    private fun enqueuePending(text: String) {
        if (pendingUtterances.size >= MAX_PENDING_UTTERANCES) {
            pendingUtterances.removeFirst()
        }
        pendingUtterances.addLast(text)
    }

    private fun flushPending(engine: TextToSpeech) {
        while (pendingUtterances.isNotEmpty()) {
            val next = pendingUtterances.removeFirst()
            // Ensure voice is still applied before each queued utterance.
            applyPreferredVoiceIfNeeded()
            audioFocusManager.requestOutputFocus()
            speakInternal(engine, next)
        }
    }

    /**
     * Applies the requested voice gender if it hasn't been applied yet.
     *
     * Must be called on the main thread.
     */
    private fun applyPreferredVoiceIfNeeded() {
        val engine = tts ?: return
        if (!isReady) return

        val targetGender = requestedGender
        if (targetGender == appliedGender) return

        val selection = selectVoiceForGender(engine, targetGender)
        if (selection.voice == null) {
            // Gender preference couldn't be fulfilled; keep default and log.
            if (targetGender != VoiceGender.NEUTRAL) {
                Log.w(
                    TAG,
                    "Preferred voice not available for gender=$targetGender. Falling back to default voice."
                )
            }
            applyDefaultVoice(engine)
            appliedGender = targetGender
            appliedVoiceName = engine.voice?.name
            return
        }

        val ok = applyVoice(engine, selection.voice, selection.locale)
        if (!ok) {
            Log.w(
                TAG,
                "Failed to apply selected voice (gender=$targetGender, voice=${selection.voice.name}). Falling back to default."
            )
            applyDefaultVoice(engine)
        }

        appliedGender = targetGender
        appliedVoiceName = engine.voice?.name
    }

    private data class VoiceSelection(
        val voice: Voice?,
        val locale: Locale?
    )

    /**
     * Select the best available voice for the requested gender.
     *
     * Heuristic rules (high-level):
     * - NEUTRAL: use system default voice.
     * - MALE/FEMALE: prefer hi-IN/en-IN voices, then prefer high-quality voices.
     * - Prefer voices with "network/neural" hints in the name (common for Google neural voices),
     *   but never assume they're installed.
     * - If gender hints are detectable in features/name, prefer matching ones.
     */
    private fun selectVoiceForGender(engine: TextToSpeech, gender: VoiceGender): VoiceSelection {
        if (gender == VoiceGender.NEUTRAL) {
            return VoiceSelection(engine.defaultVoice, engine.defaultVoice?.locale)
        }

        val voices: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query TTS voices; falling back to default.", e)
            return VoiceSelection(engine.defaultVoice, engine.defaultVoice?.locale)
        }

        if (voices.isEmpty()) {
            return VoiceSelection(engine.defaultVoice, engine.defaultVoice?.locale)
        }

        val deviceLocale = Locale.getDefault()
        val langHint = languageHint
        val preferredLocales = buildList {
            // First bias by onboarding language (more important than device locale for assistant "persona").
            when (langHint) {
                OnboardingLanguage.HINDI -> {
                    add(LOCALE_HI_IN); add(LOCALE_EN_IN)
                }
                OnboardingLanguage.HINGLISH -> {
                    // Hinglish tends to land better with English (India) voice, but allow Hindi.
                    add(LOCALE_EN_IN); add(LOCALE_HI_IN)
                }
                OnboardingLanguage.ENGLISH -> {
                    add(LOCALE_EN_IN); add(LOCALE_HI_IN)
                }
            }

            // Then fallback bias by device locale if it's India.
            if (deviceLocale.language.equals("hi", ignoreCase = true) && deviceLocale.country.equals("IN", ignoreCase = true)) {
                if (!contains(LOCALE_HI_IN)) add(LOCALE_HI_IN)
                if (!contains(LOCALE_EN_IN)) add(LOCALE_EN_IN)
            } else if (deviceLocale.language.equals("en", ignoreCase = true) && deviceLocale.country.equals("IN", ignoreCase = true)) {
                if (!contains(LOCALE_EN_IN)) add(LOCALE_EN_IN)
                if (!contains(LOCALE_HI_IN)) add(LOCALE_HI_IN)
            }
        }

        val candidates = voices.filter { voice ->
            // Prefer India locales first, but we still allow fallback to any voice if needed.
            voice.locale in preferredLocales
        }

        val shortList = if (candidates.isNotEmpty()) candidates else voices.toList()

        val best = shortList.maxByOrNull { voice ->
            scoreVoice(engine, voice, gender, preferredLocales)
        }

        return VoiceSelection(best, best?.locale)
    }

    private fun scoreVoice(
        engine: TextToSpeech,
        voice: Voice,
        gender: VoiceGender,
        preferredLocales: List<Locale>
    ): Int {
        var score = 0

        // Locale priority (hi-IN / en-IN first as per requirements).
        val localeIndex = preferredLocales.indexOf(voice.locale)
        if (localeIndex >= 0) {
            score += (preferredLocales.size - localeIndex) * 25
        }

        // Quality/latency hints (best-effort; not all engines populate these meaningfully).
        score += when (voice.quality) {
            Voice.QUALITY_VERY_HIGH -> 40
            Voice.QUALITY_HIGH -> 30
            Voice.QUALITY_NORMAL -> 10
            else -> 0
        }
        score += when (voice.latency) {
            Voice.LATENCY_VERY_LOW -> 12
            Voice.LATENCY_LOW -> 8
            Voice.LATENCY_NORMAL -> 3
            else -> 0
        }

        val name = voice.name.orEmpty()
        val nameLower = name.lowercase(Locale.ROOT)
        val featuresLower = try {
            voice.features?.map { it.lowercase(Locale.ROOT) }?.toSet().orEmpty()
        } catch (_: Exception) {
            emptySet()
        }

        // Prefer Google neural-ish voices when present (usually "network" in name).
        if (nameLower.contains("network") || nameLower.contains("neural")) score += 18

        // Avoid voices that require network for wake-word snappiness (still allowed if it's the only match).
        if (!voice.isNetworkConnectionRequired) score += 6

        // Gender hinting:
        // Some engines include "male"/"female" in features or name. If so, strongly prefer matches.
        val wantsMale = gender == VoiceGender.MALE
        val wantsFemale = gender == VoiceGender.FEMALE

        val hintsMale = ("male" in featuresLower) || nameLower.contains("male") || nameLower.contains("#male")
        val hintsFemale = ("female" in featuresLower) || nameLower.contains("female") || nameLower.contains("#female")

        when {
            wantsMale && hintsMale -> score += 80
            wantsFemale && hintsFemale -> score += 80
            wantsMale && hintsFemale -> score -= 25
            wantsFemale && hintsMale -> score -= 25
        }

        // If engine is Google TTS, gently bias toward its voices (common in production).
        // NOTE: This doesn't *force* Google; it only influences ranking among already installed voices.
        val engineNameLower = try { engine.defaultEngine?.lowercase(Locale.ROOT).orEmpty() } catch (_: Exception) { "" }
        if (engineNameLower.contains("google")) score += 2

        // Stability bias: prefer to keep the currently applied voice if it still matches.
        if (appliedVoiceName != null && appliedVoiceName == name) score += 3

        return score
    }

    /**
     * Apply language and voice safely.
     *
     * Must be called on the main thread.
     */
    private fun applyVoice(engine: TextToSpeech, voice: Voice, locale: Locale?): Boolean {
        return try {
            if (locale != null) {
                val avail = engine.isLanguageAvailable(locale)
                if (avail >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = locale
                } else {
                    Log.w(TAG, "Locale $locale not available on this TTS engine (code=$avail).")
                }
            }

            engine.voice = voice
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error applying voice=${voice.name} locale=$locale", e)
            false
        }
    }

    private fun applyDefaultVoice(engine: TextToSpeech) {
        try {
            val default = engine.defaultVoice
            if (default != null) {
                engine.voice = default
            }
        } catch (_: Exception) {
            // Silent fallback.
        }
    }

    private fun speakInternal(engine: TextToSpeech, text: String) {
        try {
            applyHumanProsody(engine)
            val utteranceId = UUID.randomUUID().toString()
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (e: Exception) {
            // Never crash on speak. If the engine dies, we'll just skip speaking.
            Log.w(TAG, "speak() failed; dropping utterance.", e)
            audioFocusManager.abandonOutputFocus()
        }
    }

    /**
     * Micro-prosody that makes "Yes?/Go ahead" feel less robotic.
     *
     * Constraints:
     * - Keep it deterministic enough (no wild swings).
     * - Must be very cheap; called on main thread.
     */
    private fun applyHumanProsody(engine: TextToSpeech) {
        // Base tuning: slightly slower than default and with a hint of warmth.
        // Small randomness prevents "same clip" feeling.
        val gender = requestedGender
        val lang = languageHint

        val baseRate = when (lang) {
            OnboardingLanguage.HINDI -> 0.98f
            OnboardingLanguage.HINGLISH -> 1.00f
            OnboardingLanguage.ENGLISH -> 1.02f
        }

        val basePitch = when (gender) {
            VoiceGender.FEMALE -> 1.06f
            VoiceGender.MALE -> 0.94f
            VoiceGender.NEUTRAL -> 1.00f
        }

        // Tiny variation: +/- ~2%
        val rateJitter = 1f + random.nextFloat().let { (it - 0.5f) * 0.04f }
        val pitchJitter = 1f + random.nextFloat().let { (it - 0.5f) * 0.04f }

        try {
            engine.setSpeechRate((baseRate * rateJitter).coerceIn(0.85f, 1.18f))
            engine.setPitch((basePitch * pitchJitter).coerceIn(0.85f, 1.25f))
        } catch (_: Exception) {
            // Ignore; not all engines behave nicely.
        }
    }
}


