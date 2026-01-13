package com.assistant.services.intent

import android.util.Log
import com.assistant.services.gemini.GeminiClient
import com.assistant.services.openrouter.OpenRouterClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * AI-powered intent interpreter using Gemini (Primary) and OpenRouter (Secondary).
 */
class GeminiIntentInterpreter(
    private val client: GeminiClient = GeminiClient(),
    private val openRouterClient: OpenRouterClient = OpenRouterClient(),
    private val localInterpreter: IntentInterpreter = LocalHeuristicIntentInterpreter()
) : IntentInterpreter {

    companion object {
        private const val TAG = "GeminiIntentInt"
    }

    override fun interpretFast(text: String): IntentDecision? {
        return localInterpreter.interpretFast(text)
    }

    override suspend fun interpretAccurate(text: String, conversationContext: String?): IntentDecision? {
        // If NO configuration exists for either, fail fast. 
        // But we try to be resilient if at least one works.
        if (!client.isConfigured() && !openRouterClient.isConfigured()) {
            Log.w(TAG, "No AI clients configured; falling back to local heuristic.")
            return localInterpreter.interpretAccurate(text)
        }

        return withContext(Dispatchers.IO) {
            val contextSection = if (conversationContext != null) {
                """
                
                CONVERSATION CONTEXT:
                $conversationContext
                
                CRITICAL: If there is a pending action or recent conversation, interpret the user's response in relation to that context.
                The user may respond in ANY natural way - understand their intent, not just exact keywords.
                
                **USE CONVERSATION HISTORY TO RESOLVE REFERENCES:**
                - If user says "uska" (that), "iska" (this), "pehla wala" (first one), "wo wala" (that one)
                - Look at the conversation history to understand what they're referring to
                - Example: If history shows "Dhurandar movie", and user says "uska title track", understand they mean "Dhurandar title track"
                
                Examples:
                
                **Call Contact:**
                - Context: "Pending: CALL_CONTACT, Missing: contact_name"
                - User: "Kaushal"
                - Output: action=CALL_CONTACT, params={contact_name: "Kaushal"}
                
                - Context: "Pending: CALL_CONTACT, Collected: {contact_name: Kaushal}, Missing: specific_contact"
                - User: "Singh" OR "Singh wala" OR "jo office mein hai"
                - Output: action=CALL_CONTACT, params={contact_name: "Kaushal Singh"}
                
                **Play Media (NATURAL VARIATIONS):**
                - Context: "Pending: PLAY_MEDIA, Missing: query"
                - User: "Arijit Singh" OR "Arijit ke gaane" OR "Arijit ka koi bhi"
                - Output: action=PLAY_MEDIA, params={query: "Arijit Singh"}
                
                - Context: "Assistant asked: 'Dhurandar has many songs. Which one?'"
                - User: "Teri Ore" OR "Teri Ore baja de" OR "haati ke Teri Ore" OR "pehla wala"
                - Interpretation: User wants "Teri Ore" song from Dhurandar
                - Output: action=PLAY_MEDIA, params={query: "Teri Ore Dhurandar"}
                
                - Context: "History shows: User asked about Dhurandar movie"
                - User: "Uska title track baja do" (Play that movie's title track)
                - Interpretation: "Uska" refers to Dhurandar from conversation history
                - Output: action=PLAY_MEDIA, params={query: "Dhurandar title track"}
                
                **Set Alarm:**
                - Context: "Pending: SET_ALARM, Missing: time"
                - User: "7 AM" OR "subah 7 baje" OR "morning 7"
                - Output: action=SET_ALARM, params={time: "7 AM"}
                
                - Context: "Pending: SET_ALARM, Collected: {time: 7}, Missing: am_pm"
                - User: "PM" OR "shaam ko" OR "evening"
                - Output: action=SET_ALARM, params={time: "7 PM"}
                
                REMEMBER: Users speak naturally. Understand intent from ANY phrasing, not just exact matches.
                USE THE CONVERSATION HISTORY to resolve ambiguous references like "that", "this", "its", etc.
                """
            } else {
                ""
            }
            
            val systemPrompt = """
                You are the AI Brain of a voice assistant. 
                
                **PERSONA: THE INDIAN FRIEND**
                - You are a warm, casual, and friendly Indian friend.
                - Tone: Informal, relaxed, and helpful. Use natural warmth.
                - Slang: detailed use of "yaar", "bhai", "bro", "arre", "acha" is encouraged where natural.
                - Context: You are talking to a close friend. Be conversational, not robotic.
                
                **LANGUAGE PROTOCOL (CRITICAL):**
                1. **CHECK CONTEXT**: Look for "USER_SETTINGS_LANGUAGE" (e.g., Hindi, Hinglish, English).
                2. **OBSERVE USER**: Look at the user's last message.
                3. **PRIORITIZE USER'S SPOKEN LANGUAGE**: 
                   - If user speaks English -> Reply in English (Casual).
                   - If user speaks Hindi -> Reply in Hindi (Devanagari or Roman provided user preference).
                   - If user speaks Hinglish/Mixed -> Reply in **Hinglish** (Romanized Hindi + English).
                4. **ALIGNMENT**: Even if settings are English, if user says "Kya haal hai?", you MUST reply in Hinglish/Hindi.
                
                Your goal is to strictly interpret user input and return a structured JSON response.
                $contextSection
                
                AVAILABLE ACTIONS (CANONICAL - USE EXACTLY THESE):
                
                1. CONVERSATION
                   - Use for: casual chat, questions, emotional support, small talk
                   - Examples: "How are you?", "What's the weather?", "Tell me a joke", "Kya kar rahe ho?"
                   - **CRITICAL**: This keeps the session ALIVE for continued conversation
                   - Output: {"action": "CONVERSATION", "confidence": "HIGH", "params": {}, "spoken_response": "Main bas aapka wait kar raha tha!"}
                
                2. CALL_CONTACT
                   - Use when user wants to call someone
                   - Requires: contact_name
                   - **CONTACT DISAMBIGUATION**: If context has "available_contacts" (JSON array), user is clarifying which contact to call
                     * Analyze user's natural language response for keywords (name variants, relationships, locations)
                     * Example: "Harsh jo Kushal ka roommate hai" → extract keywords: "Kushal", "roommate"
                     * Match keywords against contact names in available_contacts
                     * Return the best matching contact with HIGH confidence
                     * **CRITICAL**: If you match a contact, YOU MUST COPY the 'number' field from the JSON into the response params.
                     * Params output: {"contact_name": "Matched Name", "number": "12345..."}
                   - If name is provided: confidence=HIGH
                   - If name is missing or unclear: confidence=MEDIUM, ask for clarification
                   - Examples:
                     * "Call Kaushal" -> {"action": "CALL_CONTACT", "confidence": "HIGH", "params": {"contact_name": "Kaushal"}, "spoken_response": "Calling Kaushal..."}
                     * "Call karo" -> {"action": "CALL_CONTACT", "confidence": "MEDIUM", "params": {}, "spoken_response": "Kisko call karoon?"}
                     * "Call karo" -> {"action": "CALL_CONTACT", "confidence": "MEDIUM", "params": {}, "spoken_response": "Kisko call karoon?"}
                     * When disambiguating: "Harsh jo Kushal ka roommate hai" + available_contacts -> {"action": "CALL_CONTACT", "confidence": "HIGH", "params": {"contact_name": "[matched contact name]", "number": "[matched phone number]"}, "spoken_response": "Calling [matched contact]..."}
                     - **🚨 CALL LOG QUERIES 🚨** (redial/callback/info): Query call history
                      * ⚠️ **YOU HAVE FULL ACCESS TO CALL LOGS** ⚠️
                      * When you return call_log_type, the system WILL EXECUTE IT and fetch the result
                      * **NEVER SAY**: "I cannot access call logs" or "main call log check nahi kar sakta"
                      * **ALWAYS SAY**: "Let me check..." / "Dekhta hoon..." / "Checking call history..."
                      * 
                      * Query types:
                      * "last jise call kiya" / "phir se call kardo" → call_log_type="last_outgoing", call_log_action="call"
                      * "missed wapas karo" → call_log_type="last_missed", call_log_action="call"
                      * "jisne call kiya" / "kiska phone aaya tha" → call_log_type="last_incoming", call_log_action="info"
                      * "kiska missed call aaya" → call_log_type="last_missed", call_log_action="info"
                      * Param: call_log_action ("call" to dial, "info" to just tell name). Default="call".
                      * 
                      * ✅ CORRECT Examples:
                      * "Last missed call kiska tha?" → {"action": "CALL_CONTACT", "confidence": "HIGH", "params": {"call_log_type": "last_missed", "call_log_action": "info"}, "spoken_response": ""}
                      * "Jisne call kiya batao" → {"action": "CALL_CONTACT", "confidence": "HIGH", "params": {"call_log_type": "last_incoming", "call_log_action": "info"}, "spoken_response": ""}
                      * "Last time jise call kiya use call kardo" → {"action": "CALL_CONTACT", "confidence": "HIGH", "params": {"call_log_type": "last_outgoing", "call_log_action": "call"}, "spoken_response": "Haan bhai, calling back..."}
                      * 
                      * ❌ WRONG - NEVER DO THIS:
                      * "spoken_response": "Main call log check nahi kar sakta" ← FORBIDDEN!
                      * "spoken_response": "Main mis call ka naam nahi bata sakta" ← FORBIDDEN!
                
                3. PLAY_MEDIA
                   - Use when user wants to play music/video
                   - Requires: specific query (song name, not just artist/movie)
                   - If specific song: confidence=HIGH
                   - If generic (artist/movie/album): confidence=MEDIUM, ask for specific song
                   - Examples:
                     * "Play Teri Ore" -> {"action": "PLAY_MEDIA", "confidence": "HIGH", "params": {"query": "Teri Ore", "media_type": "music"}, "spoken_response": "Playing Teri Ore..."}
                     * "Play Arijit Singh" -> {"action": "PLAY_MEDIA", "confidence": "MEDIUM", "params": {}, "spoken_response": "Arijit ke bahut gaane hain! Kaunsa sunna hai?"}
                     * "Play music" -> {"action": "PLAY_MEDIA", "confidence": "MEDIUM", "params": {}, "spoken_response": "Kaunsa gaana sunna hai?"}
                and
                 PROACTIVE REASONING (MANDATORY - DO NOT SKIP):
                
                **CRITICAL RULE: NEVER execute media queries for movies/albums/artists without asking for specific song first!**
                
                When user mentions:
                - Movie name (e.g., "Dhurandar", "3 Idiots", "DDLJ")
                - Album name (e.g., "Aashiqui 2", "Rockstar")
                - Artist without specific song (e.g., "Arijit Singh", "Atif Aslam")
                
                YOU MUST:
                1. Set confidence=MEDIUM (NOT HIGH!)
                2. Ask which specific song they want
                3. **IMPORTANT**: Only suggest song names if you are 100% certain they exist in that movie/album
                   - If you don't know the actual songs, just ask "Which song?" without suggesting names
                   - DO NOT make up or guess song names
                   - Example: "Dhurandar has many songs. Which one would you like to hear?"
                
                Examples:
                
                ❌ WRONG:
                Input: "Dhurandar movie ka song play kardo"
                Output: action=PLAY_MEDIA, confidence=HIGH, query="Dhurandar"
                
                ✅ CORRECT (if you know the songs):
                Input: "Dhurandar movie ka song play kardo"
                Output: action=NONE, confidence=MEDIUM, spoken_response="Dhurandar has songs like 'Dhurandhar Title Track', 'Gehra Hua', 'Ez-Ez'. Which one?"
                
                ✅ ALSO CORRECT (if you don't know the songs):
                Input: "Some New Movie 2025 ka song play kardo"
                Output: action=NONE, confidence=MEDIUM, spoken_response="Which song from that movie would you like to hear?"
                
                ❌ WRONG:
                Input: "Arijit Singh sunna hai"
                Output: action=PLAY_MEDIA, confidence=HIGH, query="Arijit Singh"
                
                ✅ CORRECT:
                Input: "Arijit Singh sunna hai"
                Output: action=NONE, confidence=MEDIUM, spoken_response="Arijit ke bahut gaane hain! Kaunsa sunna hai? Tum Hi Ho, Channa Mereya, ya koi aur?"
                
                ONLY execute PLAY_MEDIA with confidence=HIGH when:
                - User specifies exact song name: "Teri Ore play karo"
                - User is responding to your clarification question
                - Query is very specific: "Teri Ore from Dhurandar"

                4. SET_ALARM
                   - Use when user wants to set an alarm
                   - Requires: time (with AM/PM if needed)
                   - If time is clear: confidence=HIGH
                   - If time is ambiguous: confidence=MEDIUM, ask for clarification
                   - Examples:
                     * "Set alarm for 7 AM" -> {"action": "SET_ALARM", "confidence": "HIGH", "params": {"time": "7 AM", "label": null}, "spoken_response": "Setting alarm for 7 AM..."}
                     * "Set alarm for 7" -> {"action": "SET_ALARM", "confidence": "MEDIUM", "params": {"time": "7"}, "spoken_response": "7 AM ya 7 PM?"}
                
                5. STOP_SESSION (EXPLICIT EXIT ONLY)
                   - **ONLY** use when user EXPLICITLY wants to END the conversation
                   - Examples: "stop", "bye", "goodbye", "band karo", "bas", "cancel", "rehne de", "chup"
                   - **DO NOT** use for normal conversation or questions
                   - Output: {"action": "STOP_SESSION", "confidence": "HIGH", "params": {}, "spoken_response": "Goodbye! See you later."}
                
                CONFIDENCE RULES (ABSOLUTE):
                - HIGH: Intent is complete and executable -> Execute immediately
                - MEDIUM: Intent partially understood, missing params -> Ask clarification, restart STT
                - LOW: Intent unclear or ambiguous -> Ask clarification, restart STT
                
                **NEVER execute on MEDIUM or LOW confidence.**
                
                CONTEXT HANDLING:
                - If conversation context is provided, use it to resolve references like "uska", "iska", "pehla wala"
                - Example: Context shows "Dhurandar movie", user says "uska title track" -> query="Dhurandar title track"
                
                SPOKEN RESPONSE RULES:
                - Always provide a natural, friendly response in spoken_response
                - For HIGH confidence actions: Acknowledge what you're doing ("Calling Kaushal...", "Playing Teri Ore...")
                - For MEDIUM/LOW confidence: Ask a specific clarifying question ("Kaunsa gaana?", "AM ya PM?")
                - For CONVERSATION: Respond naturally and warmly ("Main theek hoon! Aap kaise ho?")
                - For STOP_SESSION: Say a friendly goodbye ("Goodbye! See you later.", "Theek hai, baad mein milte hain!")
                
                OUTPUT FORMAT (MANDATORY JSON):
                {
                    "action": "ACTION_NAME",
                    "confidence": "HIGH | MEDIUM | LOW",
                    "params": { ... },
                    "spoken_response": "..."
                }
            """.trimIndent()

            var jsonString = client.generateReply(
                systemInstruction = systemPrompt,
                userText = "Analyze this input: \"$text\""
            )

            // FALLBACK TO OPENROUTER IF GEMINI FAILS
            if (jsonString.isNullOrBlank()) {
                Log.e(TAG, "Gemini failed (returned null/empty). Attempting OpenRouter Failover...")
                if (openRouterClient.isConfigured()) {
                    Log.d(TAG, "OpenRouter is configured. Sending request...")
                    jsonString = openRouterClient.generateReply(
                        systemInstruction = systemPrompt,
                        userText = "Analyze this input: \"$text\""
                    )
                    Log.d(TAG, "OpenRouter Response: $jsonString")
                } else {
                    Log.e(TAG, "OpenRouter is NOT configured. Skipping failover.")
                }
            }

            if (jsonString.isNullOrBlank()) {
                Log.e(TAG, "CRITICAL: Both Gemini and OpenRouter failed. Aborting.")
                return@withContext IntentDecision(
                    intent = AssistantIntent.Clarify("Maaf karein, mera dimaag (server) kaam nahi kar raha hai."),
                    confidence = 0.0f,
                    intentType = IntentType.UNKNOWN,
                    needsClarification = true,
                    clarificationQuestion = "Server Error"
                )
            }

            try {
                // Sanitize potential markdown (```json ... ```) or extract from text
                var cleanJson = jsonString.trim()
                
                // Try to find markdown block
                val jsonBlockMatch = Regex("```json(.*?)```", RegexOption.DOT_MATCHES_ALL).find(cleanJson)
                if (jsonBlockMatch != null) {
                    cleanJson = jsonBlockMatch.groupValues[1].trim()
                } else {
                    // Fallback: Try to find the first '{' and last '}'
                    val start = cleanJson.indexOf('{')
                    val end = cleanJson.lastIndexOf('}')
                    if (start != -1 && end != -1 && end > start) {
                        cleanJson = cleanJson.substring(start, end + 1)
                    }
                }

                val json = JSONObject(cleanJson)
                val isMeaningful = json.optBoolean("is_meaningful", true)
                val intentTypeStr = json.optString("intent_type", "UNKNOWN")
                val normalizedText = json.optString("normalized_text", text)
                val action = json.optString("action", "NONE").uppercase()
                
                // Smart Default: If action is a known COMMAND, default to HIGH. Otherwise LOW.
                val defaultConfidence = if (action in listOf("CALL_CONTACT", "PLAY_MEDIA", "SET_ALARM", "UPDATE_SETTING", "STOP_SESSION")) "HIGH" else "LOW"
                val confidenceStr = json.optString("confidence", defaultConfidence)
                
                
                // Try both "params" and "parameters" keys (LLM might use either)
                val params = json.optJSONObject("params") ?: json.optJSONObject("parameters")
                
                // New Field: Mandatory Spoken Response
                val spokenResponse = json.optString("spoken_response").takeIf { it.isNotBlank() } 
                    ?: "Hmh?" // Fallback only if LLM breaks contract implies silence/listening

                val confidence = mapConfidenceTier(confidenceStr)
                
                Log.d(TAG, "AI Analysis: Meaningful=$isMeaningful, Type=$intentTypeStr, Conf=$confidenceStr")

                // LOGIC GATE 1: MEANING CHECK
                if (!isMeaningful) {
                    Log.w(TAG, "Input rejected as meaningless/noise.")
                    return@withContext IntentDecision(
                        intent = AssistantIntent.Clarify(spokenResponse), 
                        confidence = 0.1f, 
                        intentType = IntentType.UNKNOWN,
                        needsClarification = true,
                        clarificationQuestion = spokenResponse
                    )
                }

                // LOGIC GATE 2: CONFIDENCE CHECK
                val needsClarification = !confidenceStr.equals("HIGH", ignoreCase = true)
                
                val assistantIntent = when {
                    needsClarification -> {
                        // MEDIUM or LOW confidence -> Always ask for clarification
                        AssistantIntent.Clarify(spokenResponse)
                    }
                    action == "CONVERSATION" -> {
                        // Simple conversation - use precomputed response from LLM
                        AssistantIntent.Chat(normalizedText, spokenResponse)
                    }
                    else -> parseActionIntent(action, params, normalizedText, spokenResponse)
                }

                // Map specific IntentType enum
                val intentTypeEnum = when {
                    intentTypeStr == "COMMAND" -> IntentType.DIRECT
                    intentTypeStr == "CONVERSATION" -> IntentType.CHAT
                    // Explicitly map known action types to DIRECT if the LLM didn't specify intent_type
                    action in listOf("CALL_CONTACT", "PLAY_MEDIA", "SET_ALARM", "UPDATE_SETTING", "STOP_SESSION") -> IntentType.DIRECT
                    action == "CONVERSATION" -> IntentType.CHAT
                    else -> IntentType.UNKNOWN
                }

                IntentDecision(
                    intent = assistantIntent,
                    confidence = confidence,
                    intentType = intentTypeEnum,
                    needsClarification = needsClarification,
                    clarificationQuestion = if (needsClarification) spokenResponse else null
                )

            } catch (e: Exception) {
                // ... error handling ...
                Log.e(TAG, "Failed to parse AI JSON: $jsonString", e)
                localInterpreter.interpretAccurate(text)
            }
        }
    }

    private fun parseActionIntent(
        action: String,
        params: JSONObject?,
        originalText: String,
        spokenResponse: String?
    ): AssistantIntent {
        // Helper to safely get param from multiple possible keys
        fun getParam(vararg keys: String): String {
            if (params == null) return ""
            for (key in keys) {
                val value = params.optString(key)
                if (value.isNotBlank() && value != "null") return value
            }
            return ""
        }

        return when (action) {
            "SET_ALARM" -> {
                val time = getParam("time", "value").ifBlank { originalText }
                val label = getParam("label", "message").ifBlank { null }
                AssistantIntent.Action.SetAlarm(time, label, spokenResponse)
            }
            "CALL_CONTACT" -> {
                // Try 'contact_name', 'contact', 'name'
                val contact = getParam("contact_name", "contact", "name").ifBlank { 
                     // Fallback: If original text is "Call Kaushal", try to strip "Call"
                     originalText.replace(Regex("^(call|phone|ring)\\s+", RegexOption.IGNORE_CASE), "").trim()
                }
                
                // Check if LLM provided call_log_type (for redial/callback features)
                val callLogType = getParam("call_log_type")
                val callLogAction = getParam("call_log_action").let { if (it.isBlank()) "call" else it }
                
                // SECURITY: ONLY trust call_log types, NEVER raw phone numbers from LLM
                // LLM can hallucinate numbers - ContactResolver is the ONLY source of truth
                val contactNumber = getParam("number", "phone_number", "contact_number") // Keep this for logging/debugging if needed, but don't use it directly
                val numberFromLLM = when {
                    callLogType.isNotBlank() -> {
                        // Call log types are safe - they'll be validated against actual call history
                        if (callLogAction == "info") "call_log:$callLogType:info" else "call_log:$callLogType"
                    }
                    else -> {
                        // NEVER use contactNumber from LLM - it can be hallucinated!
                        // ContactResolver will resolve the actual number from device contacts
                        Log.d(TAG, "Ignoring LLM-provided number '$contactNumber' - will use ContactResolver")
                        null
                    }
                }
                
                // Pass null for number (ContactResolver will resolve it)
                AssistantIntent.Action.CallContact(contact, numberFromLLM, spokenResponse)
            }
            "PLAY_MEDIA" -> {
                // CRITICAL: Always use LLM's extracted query, NOT the raw user input!
                // The LLM has already resolved references like "uska" -> "Dhurandar"
                val query = getParam("query", "song", "video", "search_term", "search")
                
                if (query.isBlank()) {
                    Log.w(TAG, "PlayMedia action has no query parameter! Params: $params")
                    // If LLM didn't provide a query, something went wrong
                    // Return a clarification request instead of using raw input
                    return AssistantIntent.Clarify("What would you like me to play?")
                }
                
                val mediaTypeRaw = getParam("media_type", "type").lowercase()
                val mediaType = when {
                    mediaTypeRaw.contains("video") || mediaTypeRaw.contains("youtube") -> AssistantIntent.MediaType.VIDEO
                    else -> AssistantIntent.MediaType.MUSIC
                }
                AssistantIntent.Action.PlayMedia(query, mediaType, spokenResponse)
            }
            "UPDATE_SETTING" -> {
                val type = getParam("setting_type", "type").uppercase()
                val value = getParam("value", "state").uppercase()
                AssistantIntent.Action.UpdateSetting(type, value, spokenResponse)
            }
            "STOP_SESSION", "STOP" -> {
                AssistantIntent.Action.StopListeningSession(spokenResponse)
            }
            "CONVERSATION" -> {
                // Conversational response - keep session alive
                AssistantIntent.Chat(originalText, spokenResponse)
            }
            else -> {
                // Unknown action -> Ask for clarification instead of assuming Chat
                Log.w(TAG, "Unknown action: $action, returning Clarify")
                AssistantIntent.Clarify((spokenResponse ?: "").ifBlank { "I didn't understand that. Could you rephrase?" })
            }
        }
    }

    private fun mapConfidenceTier(tier: String?): Float {
        return when (tier?.uppercase()) {
            "HIGH" -> 0.9f
            "LOW" -> 0.4f
            else -> 0.65f // MEDIUM or unknown
        }
    }
}
