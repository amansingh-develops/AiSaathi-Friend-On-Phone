package com.assistant.services.chat

/**
 * Chat responder contract.
 *
 * Strict rule:
 * - Use GPT-Go ONLY for casual chat/emotional support.
 * - Never use it for action parsing.
 *
 * In this repo, the network client isn't present yet; implementations can be swapped later.
 */
interface ChatResponder {
    suspend fun respond(userText: String): String
}



