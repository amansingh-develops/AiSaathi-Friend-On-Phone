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
        data class PlayMedia(val query: String, val mediaType: MediaType = MediaType.MUSIC, override val acknowledgement: String? = null) : Action(acknowledgement)
        data class SetAlarm(val timeText: String, val label: String? = null, override val acknowledgement: String? = null) : Action(acknowledgement)
        data class CallContact(val contactName: String, val number: String? = null, override val acknowledgement: String? = null) : Action(acknowledgement)
        data class UpdateSetting(val settingType: String, val value: String, override val acknowledgement: String? = null) : Action(acknowledgement)
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



