package com.assistant.services.intent

/**
 * Intent output is STRICTLY structured (for speed and reliability).
 *
 * This mirrors the "Gemini returns JSON only" rule, but stays local to Kotlin models.
 */
sealed class AssistantIntent {
    data object Unknown : AssistantIntent()

    /**
     * System/action intents (execute immediately).
     */
    sealed class Action(open val acknowledgement: String? = null) : AssistantIntent() {
        data class StopListeningSession(override val acknowledgement: String? = null) : Action(acknowledgement)
        data object OpenSettings : Action()
        data class PlayMedia(
            val query: String, 
            val mediaType: MediaType = MediaType.MUSIC, 
            val videoFilter: String? = null,     // "any", "most_views", "specific_creator"
            val creatorName: String? = null,     // Channel name for SPECIFIC_CREATOR filter
            override val acknowledgement: String? = null
        ) : Action(acknowledgement)
        data class SetAlarm(val timeText: String, val label: String? = null, override val acknowledgement: String? = null) : Action(acknowledgement)
        data class CallContact(val contactName: String, val number: String? = null, override val acknowledgement: String? = null) : Action(acknowledgement)
        data class UpdateSetting(val settingType: String, val value: String, override val acknowledgement: String? = null) : Action(acknowledgement)
        
        /**
         * Rapido ride booking automation.
         * @param destination Target destination (may need clarification)
         * @param vehicle "bike" or "cab" (null if not specified)
         * @param isSavedPlace Whether to use saved/favorite place
         */
        data class BookRapido(
            val destination: String?,
            val vehicle: String?,
            val isSavedPlace: Boolean? = null,
            override val acknowledgement: String? = null
        ) : Action(acknowledgement)
        
        /**
         * Open camera automation.
         * @param mode Camera mode: "photo", "video", "selfie", "qr" (null defaults to photo)
         */
        data class OpenCamera(
            val mode: String? = null,
            override val acknowledgement: String? = null
        ) : Action(acknowledgement)
        
        /**
         * Amazon product search automation.
         * @param query Product search query (may need clarification if vague)
         */
        data class SearchAmazon(
            val query: String?,
            override val acknowledgement: String? = null
        ) : Action(acknowledgement)
        
        /**
         * Emergency SOS - Dials emergency services (112) immediately.
         * Works offline via local keyword detection.
         * @param reason Why SOS was triggered (for logging)
         * @param emergencyType "police", "ambulance", "fire", or "general"
         */
        data class TriggerSOS(
            val reason: String?,
            val emergencyType: String = "general",
            override val acknowledgement: String? = "Shant raho, main abhi emergency services ko call kar raha hoon."
        ) : Action(acknowledgement)
    }

    enum class MediaType {
        MUSIC, VIDEO
    }

    /**
     * Chat intent (route to ChatResponder).
     */
    data class Chat(val userText: String, val precomputedResponse: String? = null) : AssistantIntent()

    /**
     * Clarification required (ask a single short question and keep mic open).
     */
    data class Clarify(val question: String) : AssistantIntent()
}

/**
 * High-level intent type from the classifier.
 */
enum class IntentType {
    DIRECT,
    VAGUE,
    CHAT,
    UNKNOWN
}

data class IntentDecision(
    val intent: AssistantIntent,
    /**
     * 0..1 confidence used to decide whether we can execute immediately.
     */
    val confidence: Float,
    val intentType: IntentType = IntentType.UNKNOWN,
    val needsClarification: Boolean = false,
    val clarificationQuestion: String? = null
)



