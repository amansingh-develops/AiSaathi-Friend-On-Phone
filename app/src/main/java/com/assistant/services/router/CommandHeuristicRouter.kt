package com.assistant.services.router

import java.util.Locale

/**
 * Simple heuristic router to quickly decide if text is a "Command" (for Gemini)
 * or "Conversation" (for ElevenLabs Agent).
 */
class CommandHeuristicRouter {

    private val commandVerbs = setOf(
        "call", "dial", "ring",
        "play", "pause", "resume",
        "open", "launch",
        "set", "create", "cancel",
        "turn", "switch",
        "send"
    )

    private val appNames = setOf(
        "spotify", "youtube", "music", "whatsapp", "telegram",
        "alarm", "timer", "clock", "camera", "gallery", "photos",
        "torch", "flashlight", "wifi", "bluetooth", "data"
    )

    private val systemKeywords = setOf(
        "volume", "brightness", "battery"
    )

    /**
     * Returns TRUE if the text looks like a functional command.
     * Returns FALSE if it looks like casual conversation.
     */
    fun isCommand(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.getDefault())

        // 1. Check for specific imperative verbs at the start
        val words = lower.split(" ")
        if (words.isEmpty()) return false

        val firstWord = words.first()
        if (commandVerbs.contains(firstWord)) {
            // "Play music", "Set alarm" -> Likely command
            return true
        }

        // 2. Check for App names or System keywords (strong indicator of intent)
        // e.g. "Music chala do" (not starting with Play, but has Music)
        if (appNames.any { lower.contains(it) } || systemKeywords.any { lower.contains(it) }) {
            return true
        }

        // 3. Short, punchy phrases are often commands?
        // "Stop", "Next", "Louder"
        if (words.size <= 2 && (commandVerbs.contains(firstWord) || commandVerbs.any { lower.contains(it) })) {
            return true
        }

        // Default to conversation
        return false
    }
}
