package com.assistant.services.ack

import com.assistant.domain.model.VoiceGender
import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.services.voice.VoiceOutput
import java.util.LinkedList
import kotlin.random.Random

/**
 * Intelligent Acknowledgement Layer.
 * - Categories: WAKE, COMMAND_ACCEPTED, EXECUTING, DONE, LISTENING
 * - Anti-repetition: Remembers last 3 used phrases.
 * - Natural: Designed for TTS to sound human-like (commas, ellipses).
 */
class AcknowledgementManager(
    private val voice: VoiceOutput,
    private val random: Random = Random.Default
) {
    
    enum class AckCategory {
        WAKE,               // "Haan bolo", "Yes?"
        COMMAND_ACCEPTED,   // "Samajh gaya", "Got it"
        EXECUTING,          // "Kar raha hoon", "On it"
        DONE,               // "Ho gaya", "Done"
        REJECTION,          // "Nahi kar sakta", "Can't do that"
        LISTENING           // "Main sun raha hoon", "I'm listening"
    }

    @Volatile private var language: OnboardingLanguage = OnboardingLanguage.ENGLISH
    @Volatile private var voiceGender: VoiceGender = VoiceGender.NEUTRAL
    @Volatile private var preferredName: String? = null
    
    // History queue to prevent repetition (store last 3 spoken text)
    private val recentHistory = LinkedList<String>()
    private val HISTORY_SIZE = 3

    fun setLanguage(language: OnboardingLanguage) {
        this.language = language
    }

    fun setVoiceGender(gender: VoiceGender) {
        this.voiceGender = gender
        voice.setVoiceGender(gender)
    }

    fun setPreferredName(name: String?) {
        preferredName = name?.trim()?.takeIf { it.isNotBlank() }
    }

    fun speak(category: AckCategory) {
        val phrase = pickPhrase(category)
        if (phrase.isNotBlank()) {
            voice.setVoiceGender(voiceGender)
            voice.speak(phrase)
            addToHistory(phrase)
        }
    }
    
    // Returns the string so it can be logged or used in UI
    fun getPhrase(category: AckCategory): String {
        return pickPhrase(category) 
    }

    private fun pickPhrase(category: AckCategory): String {
        val pool = getPool(category, language)
        
        // Anti-repetition: filtering
        val available = pool.filter { !recentHistory.contains(it) }
        
        // If all used, reset to full pool (minus immediate last one to avoid direct repeat)
        val validCandidates = if (available.isEmpty()) {
            val last = recentHistory.peekLast()
            pool.filter { it != last }
        } else {
            available
        }
        
        var selected = validCandidates.randomOrNull() ?: pool.random()
        
        // Inject name occasionally only for WAKE or LISTENING
        if (preferredName != null && (category == AckCategory.WAKE || category == AckCategory.LISTENING)) {
            if (random.nextFloat() < 0.3f) { // 30% chance
                 selected = personalize(selected, language)
            }
        }
        
        return selected
    }
    
    private fun addToHistory(phrase: String) {
        recentHistory.add(phrase)
        if (recentHistory.size > HISTORY_SIZE) {
            recentHistory.removeFirst()
        }
    }

    private fun personalize(base: String, lang: OnboardingLanguage): String {
        return when (lang) {
             OnboardingLanguage.ENGLISH -> "Hey $preferredName, $base" // e.g. "Hey Aman, Yes?" (needs lower case start for base maybe?)
             // Actually most bases start capitalized. "Yes?" -> "Hey Aman, Yes?" works.
             else -> "$preferredName, $base"
        }
    }

    private fun getPool(category: AckCategory, lang: OnboardingLanguage): List<String> {
        return when (lang) {
            OnboardingLanguage.HINDI -> when (category) {
                AckCategory.WAKE -> listOf("जी?", "हा बोलिये", "बताइये", "मैं सुन रहा हूँ", "हम्म?")
                AckCategory.COMMAND_ACCEPTED -> listOf("समझ गया", "ठीक है", "ओके", "जी ज़रूर")
                AckCategory.EXECUTING -> listOf("कर रहा हूँ...", "एक सेकंड...", "अभी करता हूँ...", "हो रहा है...")
                AckCategory.DONE -> listOf("हो गया", "लीजिए, हो गया", "डन", "बस हो गया")
                AckCategory.REJECTION -> listOf("ये नहीं कर पाऊँगा", "माफ़ कीजिये", "सॉरी, ये नहीं होगा")
                AckCategory.LISTENING -> listOf("मैं सुन रहा हूँ...", "बोलते रहिये...", "मैं यही हूँ...")
            }
            // Fallback / English for Hinglish/English for now (customize as needed)
            else -> when (category) {
                AckCategory.WAKE -> listOf("Yes?", "Go on.", "I'm here.", "Listening.", "Yeah?")
                AckCategory.COMMAND_ACCEPTED -> listOf("Got it.", "Sure.", "Okay.", "Understood.")
                AckCategory.EXECUTING -> listOf("On it...", "One sec...", "Working on it...", "Doing it now...")
                AckCategory.DONE -> listOf("Done.", "All set.", "Finished.", "There you go.")
                AckCategory.REJECTION -> listOf("I can't do that.", "Sorry, no.", "Apologies, I can't.")
                AckCategory.LISTENING -> listOf("I'm listening...", "Go ahead...", "I'm with you...")
            }
        }
    }
}
