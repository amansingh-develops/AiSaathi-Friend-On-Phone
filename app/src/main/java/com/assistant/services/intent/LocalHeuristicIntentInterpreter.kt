package com.assistant.services.intent

import com.assistant.services.intent.IntentType.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Local-only intent interpreter for ultra-fast decisions.
 *
 * This is NOT "AI". It's a deterministic heuristic layer used to:
 * - execute obvious commands immediately (stop, settings)
 * - classify action vs chat quickly
 * - reduce perceived latency while AI (optional) runs in parallel
 */
class LocalHeuristicIntentInterpreter : IntentInterpreter {

    override fun interpretFast(text: String): IntentDecision? {
        val t = normalize(text)
        if (t.isBlank()) return null

        // Stop session (explicit).
        if (t.contains("stop listening") || t.contains("stop") || t.contains("band") || t.contains("bas")) {
            return IntentDecision(
                intent = AssistantIntent.Action.StopListeningSession(),
                confidence = 0.95f,
                intentType = DIRECT
            )
        }

        // Settings intent.
        if (t.contains("settings") || t.contains("setings") || t.contains("preferences")) {
            return IntentDecision(
                intent = AssistantIntent.Action.OpenSettings,
                confidence = 0.85f,
                intentType = DIRECT
            )
        }

        // Alarm intent (very common voice ask)
        if (t.contains("alarm")) {
            val timeText = extractTimePhrase(t)
            val needsClarification = timeText.isNullOrBlank()
            val question = timeText.takeIf { !needsClarification }?.let { null }
                ?: "Alarm kis time ke liye lagau?"
            return IntentDecision(
                intent = AssistantIntent.Action.SetAlarm(timeText = timeText.orEmpty()),
                confidence = if (needsClarification) 0.55f else 0.9f,
                intentType = if (needsClarification) VAGUE else DIRECT,
                needsClarification = needsClarification,
                clarificationQuestion = question
            )
        }

        // Call intent
        if (t.startsWith("call ") || t.contains("call karo") || t.contains("call lagao") || t.contains("call laga")) {
            val name = extractAfterKeyword(t, "call").ifBlank { extractAfterKeyword(t, "call karo") }
            val needsClarification = name.isBlank()
            return IntentDecision(
                intent = AssistantIntent.Action.CallContact(contactName = name),
                confidence = if (needsClarification) 0.6f else 0.9f,
                intentType = if (needsClarification) VAGUE else DIRECT,
                needsClarification = needsClarification,
                clarificationQuestion = if (needsClarification) "Kisse call karna hai?" else null
            )
        }

        // Media commands (Fast Path).
        // "play music", "play song", "play video", "play [query]"
        if (t.startsWith("play ") || t.startsWith("chalao ")) {
            val queryRaw = if (t.startsWith("play ")) t.removePrefix("play ").trim() else t.removePrefix("chalao ").trim()
            
            // 1. Explicit Video
            if (queryRaw.startsWith("video")) {
                val q = queryRaw.removePrefix("video").trim()
                return IntentDecision(
                    intent = AssistantIntent.Action.PlayMedia(q, AssistantIntent.MediaType.VIDEO),
                    confidence = 0.9f,
                    intentType = DIRECT
                )
            }
            
            // 2. Explicit Music/Song (can be empty "play music")
            if (queryRaw.startsWith("music") || queryRaw.startsWith("song") || queryRaw.startsWith("gana")) {
                val q = queryRaw
                    .removePrefix("music").removePrefix("song").removePrefix("gana")
                    .trim()
                return IntentDecision(
                    intent = AssistantIntent.Action.PlayMedia(q, AssistantIntent.MediaType.MUSIC),
                    confidence = 0.9f,
                    intentType = DIRECT
                )
            }
            
            // 3. Implicit Music (e.g. "play sharat")
            // Assume music for generic "play X" unless it matches other keywords.
            return IntentDecision(
                intent = AssistantIntent.Action.PlayMedia(queryRaw, AssistantIntent.MediaType.MUSIC),
                confidence = 0.85f,
                intentType = DIRECT
            )
        }

        // If it starts like a command ("open", "turn on"), treat as action-ish but uncertain.
        if (t.startsWith("open ") || t.startsWith("turn on") || t.startsWith("turn off")) {
            return IntentDecision(
                intent = AssistantIntent.Unknown,
                confidence = 0.55f,
                intentType = VAGUE,
                needsClarification = true,
                clarificationQuestion = "Kya kholna ya chalana hai?"
            )
        }

        // Default: chat.
        return IntentDecision(
            intent = AssistantIntent.Chat(userText = text),
            confidence = 0.6f,
            intentType = CHAT
        )
    }

    override suspend fun interpretAccurate(text: String, conversationContext: String?): IntentDecision? {
        // Local heuristic doesn't use context, just delegates to fast interpretation
        return interpretFast(text)
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase(Locale.ROOT)
    }

    private fun extractAfterKeyword(text: String, keyword: String): String {
        val idx = text.indexOf(keyword)
        if (idx == -1) return ""
        return text.substring(idx + keyword.length).trim()
    }

    private fun extractTimePhrase(text: String): String? {
        // Very small heuristic: pick first number-ish substring
        val match = Regex("(\\d{1,2})(?:[:.](\\d{1,2}))?").find(text)
        return match?.value
    }
}



