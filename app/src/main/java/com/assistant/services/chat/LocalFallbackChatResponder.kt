package com.assistant.services.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local fallback chat responder.
 *
 * Used when GPT-Go isn't wired yet. Keeps the "never be silent" rule.
 */
class LocalFallbackChatResponder : ChatResponder {
    override suspend fun respond(userText: String): String {
        return withContext(Dispatchers.Default) {
            // Minimal, human, non-robotic placeholder.
            "Okay — tell me."
        }
    }
}



