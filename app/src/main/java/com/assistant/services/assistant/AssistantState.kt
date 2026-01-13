package com.assistant.services.assistant

/**
 * Single source of truth for assistant runtime state.
 *
 * Only one of these states can be active at any time.
 */
enum class AssistantState {
    /** Wake word idle; only Porcupine active. */
    IDLE,
    /** User is speaking; Whisper listening. */
    ACTIVE_LISTENING,
    /** Transcript captured; silent reasoning. */
    UNDERSTANDING,
    /** Assistant is speaking via TTS; STT must be OFF. */
    SPEAKING,
    /** Deterministic background execution (no TTS unless confirmation). */
    EXECUTION,
    /** Short follow-up window; Whisper listening, TTS off. */
    WAITING
}

