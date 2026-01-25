package com.assistant.services.intent

import android.util.Log
import com.assistant.services.gemini.GeminiClient
import com.assistant.services.llm.SmartModelRouter
import com.assistant.services.openrouter.OpenRouterClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * AI-powered intent interpreter using Gemini (Primary) and OpenRouter (Secondary).
 * 
 * If SmartModelRouter is provided, uses session-locked routing for optimal latency.
 * Otherwise falls back to direct client calls with manual fallback.
 */
class GeminiIntentInterpreter(
    private val client: GeminiClient = GeminiClient(),
    private val openRouterClient: OpenRouterClient = OpenRouterClient(),
    private val localInterpreter: IntentInterpreter = LocalHeuristicIntentInterpreter(),
    private val smartRouter: SmartModelRouter? = null  // OPTIONAL: For session-locked routing
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
                
                **🔴 LANGUAGE MIRRORING (ABSOLUTE - ENTIRE RESPONSE) 🔴**
                DETECT user's language from their message. Reply ENTIRELY in that SAME language.
                
                **BHOJPURI VOCABULARY (MUST USE):**
                - "Hum" (I), "Tohar" (your), "Ba" (is), "Rahal" (doing), "Kaisan" (how)
                - "Hum theek ba" (I'm fine), "Ka haal ba" (how are you), "Ka karata" (what doing)
                - If user says "ka kar rahal", "kya kar rahal", "ka karhal" → Reply in BHOJPURI: "Hum theek ba! Tohar ka haal ba?"
                
                **RESPONSE EXAMPLES BY LANGUAGE:**
                - Bhojpuri: "ka karhal bara" → "Hum theek ba! Tohar ka haal ba?" (NOT "main theek hoon"!)
                - Punjabi: "ki haal aa" → "Vadiya aa bai! Tu das ki karda?"
                - Telugu: "em chestunnav" → "Bagunna! Nuvvu em chestunnav?"
                - Tamil: "enna panra" → "Nalla irukken! Nee enna panra?"
                - Bengali: "ki korcho" → "Bhalo achi! Tumi ki korcho?"
                - Marathi: "kasa aahes" → "Mast! Tu kay kartos?"
                - Gujarati: "kem cho" → "Maja ma! Tame shu karo cho?"
                - Kannada: "hege iddira" → "Chennagiddini! Neevu hegiddira?"
                - Hindi: "kaisa hai" → "Main theek hoon bhai!"
                - Hinglish: "kya scene hai" → "Sab badhiya bro!"
                
                🚫 FORBIDDEN: "main", "hoon", "bhai", "yaar" in Bhojpuri response (use "hum", "ba", "tohar")!
                ✅ CORRECT: Copy EXACT vocabulary from examples above.
                
                Your goal is to strictly interpret user input and return a structured JSON response.
                $contextSection
                
                AVAILABLE ACTIONS (CANONICAL - USE EXACTLY THESE):
                
                1. CONVERSATION
                   - Use for: casual chat, questions, emotional support, small talk, greetings
                   - **REGIONAL LANGUAGE EXAMPLES (LEARN THESE):**
                     * Bhojpuri: "ka kara tara", "ka haal ba", "kaisan ba", "ka karhal bara", "ka ho", "ka ba" = casual chat
                     * Punjabi: "ki haal aa", "ki kar reha", "kiddan", "sat sri akal" = casual chat
                     * Telugu: "em chestunnav", "ela unnav", "emi ayindi" = casual chat
                     * Tamil: "enna panra", "eppadi irukka", "enna achu" = casual chat
                     * Marathi: "kay challay", "kasa aahes", "kay mhanta" = casual chat
                     * Bengali: "ki korcho", "kemon acho", "ki holo", "ki khobor" = casual chat
                     * Gujarati: "kem cho", "shu chale che" = casual chat
                     * Kannada: "hege iddira", "enu agta ide" = casual chat
                     * Hindi/Hinglish: "kya kar raha hai", "kaisa hai" = casual chat
                   - Examples: "How are you?", "What's the weather?", "Tell me a joke"
                   - **🔴 CRITICAL FALLBACK RULE 🔴:**
                     If user speaks in UNKNOWN regional language/dialect that SOUNDS like:
                     - A greeting, "how are you", "what's up", casual question
                     - Short phrase without clear action keywords (call, play, alarm, etc.)
                     → DEFAULT TO CONVERSATION with HIGH confidence
                     → NEVER ask "kya hai?" or "saaf bolna" for regional phrases!
                     → Respond warmly in the SAME regional style
                   - **CRITICAL**: This keeps the session ALIVE for continued conversation
                   - Output: {\"action\": \"CONVERSATION\", \"confidence\": \"HIGH\", \"params\": {}, \"spoken_response\": \"[mirror user's language]\"}
                
                2. CALL_CONTACT
                   - Use when user wants to call someone
                   - Requires: contact_name
                   - **REGIONAL EXAMPLES:** "Amma ke call kar" (Punjabi), "Nanna ki call cheyyi" (Telugu), "Baba ke phone kar" (Bhojpuri), "Aai la phone kar" (Marathi)
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
                      * "Call Kaushal" -> {"action": "CALL_CONTACT", "confidence": "HIGH", "params": {"contact_name": "Kaushal"}, "spoken_response": "Haan bhai, Kaushal ko call laga raha hoon."}
                      * "Call karo" -> {"action": "CALL_CONTACT", "confidence": "MEDIUM", "params": {}, "spoken_response": "Theek hai, kisko call lagaun?"}
                      * When disambiguating: "Harsh jo Kushal ka roommate hai" + available_contacts -> {"action": "CALL_CONTACT", "confidence": "HIGH", "params": {"contact_name": "[matched contact name]", "number": "[matched phone number]"}, "spoken_response": "Mil gaya, Kushal ke roommate Harsh ko dial kar raha hoon."}
                     - **🚨 CALL LOG QUERIES 🚨** (redial/callback/info): Query call history
                      * ⚠️ **YOU HAVE FULL ACCESS TO CALL LOGS** ⚠️
                      * When you return call_log_type, the system WILL EXECUTE IT and fetch the result
                      * **NEVER SAY**: "I cannot access call logs" or "main call log check nahi kar sakta" or "mere pass information nahi hai"
                      * For INFO mode: Leave spoken_response EMPTY - system will fetch and tell the name
                      * For CALL mode: Provide acknowledgement like "Haan call back krdeta hun"
                      * 
                      * **CALL MODE** (call_log_action="call") - User wants to IMMEDIATELY call back:
                      * "last time jisse maine call kiya tha use fir se call krdo na" → last_outgoing, call
                      * "mujhe last time jisne call kiya tha usse call back kardo" → last_incoming, call
                      * "phir se call kardo" / "wapas call lagao" → last_outgoing, call
                      * "missed call wapas karo" / "jo missed call aaya usse call kardo" → last_missed, call
                      * "jo abhi call aaya tha usse call kardo" → last_incoming, call
                      * 
                      * **INFO MODE** (call_log_action="info") - User just wants to KNOW who called:
                      * "mujhe last time kisne call kiya tha zara dekh ke btana" → last_incoming, info
                      * "last missed call kiska aaya tha" → last_missed, info
                      * "abhi kuch der pehle kisi ka call aya tha btana kon tha wo" → last_incoming, info
                      * "dekho kiska missed call hai" → last_missed, info
                      * "kiska phone aaya tha" / "kisne call kiya" → last_incoming, info
                      * "maine last kisko call kiya tha" → last_outgoing, info
                      * 
                      * ✅ CORRECT Examples:
                      * "kisne call kiya tha dekho" → {"action": "CALL_CONTACT", "confidence": "HIGH", "params": {"call_log_type": "last_incoming", "call_log_action": "info"}, "spoken_response": ""}
                      * "last missed call kiska tha?" → {"action": "CALL_CONTACT", "confidence": "HIGH", "params": {"call_log_type": "last_missed", "call_log_action": "info"}, "spoken_response": ""}
                      * "jisne call kiya usse call back kardo" → {"action": "CALL_CONTACT", "confidence": "HIGH", "params": {"call_log_type": "last_incoming", "call_log_action": "call"}, "spoken_response": "Haan call back krdeta hun"}
                      * "last call pe wapas call karo" → {"action": "CALL_CONTACT", "confidence": "HIGH", "params": {"call_log_type": "last_outgoing", "call_log_action": "call"}, "spoken_response": "Theek hai call lagata hun"}
                      * 
                      * ❌ WRONG - NEVER DO THIS:
                      * "spoken_response": "Main call log check nahi kar sakta" ← FORBIDDEN!
                      * "spoken_response": "Mere pass ye information nahi hai" ← FORBIDDEN!
                      * "spoken_response": "Main mis call ka naam nahi bata sakta" ← FORBIDDEN!
                
                3. PLAY_MEDIA
                   - Use when user wants to play music/video
                   - Requires: query (what to search for)
                   - **REGIONAL EXAMPLES:** "Gana baja da" (Bhojpuri/Punjabi), "Paata pettu" (Telugu), "Paattu podu" (Tamil), "Gaana lav" (Marathi), "Gaan bajao" (Bengali)
                   
                   **🚨 MEDIA_TYPE DETECTION (CRITICAL - READ CAREFULLY):**
                   - media_type MUST be "video" if user says ANY of these:
                     * "video", "youtube", "dekho", "dekhna", "watch", "clip", "show me"
                     * YouTuber names (Ashish Chanchlani, CarryMinati, PewDiePie, etc.)
                     * "latest video", "new video", "funny video", "trending video"
                   - media_type = "music" ONLY for songs/music/audio content
                     * "song", "gana", "music", "play [singer name]", "sunao", "sunna"
                   
                   ✅ CORRECT Examples:
                   * "Ashish Chanchlani latest video" → media_type: "video"
                   * "funny cat video" → media_type: "video"  
                   * "youtube pe comedy dekho" → media_type: "video"
                   * "Teri Ore song play karo" → media_type: "music"
                   * "Arijit Singh sunao" → media_type: "music"
                   
                   ❌ WRONG (DO NOT DO THIS):
                   * "Ashish Chanchlani video" with media_type: "music" ← FORBIDDEN!
                   * Any request with "video" word should NEVER be media_type: "music"
                   
                   - Optional params for VIDEO:
                     * video_filter: "any" | "most_views" | "specific_creator" (default: "any")
                     * creator_name: Channel name (required if video_filter=specific_creator)
                   
                   **VIDEO FILTER DETECTION:**
                   - "jisme sabse jyada views hai" / "most viewed" → video_filter: "most_views"
                   - "Ashish Chanchlani ka" / "by PewDiePie" → video_filter: "specific_creator", creator_name: "Ashish Chanchlani"
                   - "koi bhi" / "any" / no preference → video_filter: "any"
                   
                   - Examples:
                      * "Play Teri Ore" -> {"action": "PLAY_MEDIA", "confidence": "HIGH", "params": {"query": "Teri Ore", "media_type": "music"}, "spoken_response": "Chalo, Teri Ore sunte hain! Enjoy karo."}
                      * "Ashish Chanchlani ka latest video" -> {"action": "PLAY_MEDIA", "confidence": "HIGH", "params": {"query": "Ashish Chanchlani latest", "media_type": "video", "video_filter": "specific_creator", "creator_name": "Ashish Chanchlani"}, "spoken_response": "Haan yaar, Ashish ka naya video start kar raha hoon."}
                      * "funny video jisme sabse jyada views hai" -> {"action": "PLAY_MEDIA", "confidence": "HIGH", "params": {"query": "funny", "media_type": "video", "video_filter": "most_views"}, "spoken_response": "Theek hai bhai, most viewed funny video chala diya."}
                     * "Play Arijit Singh" → {\"action\": \"PLAY_MEDIA\", \"confidence\": \"MEDIUM\", \"params\": {}, \"spoken_response\": \"Arijit ke bahut gaane hain! Kaunsa sunna hai?\"}
                     * "Play music" → {\"action\": \"PLAY_MEDIA\", \"confidence\": \"MEDIUM\", \"params\": {}, \"spoken_response\": \"Kaunsa gaana sunna hai?\"}
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
                   - **REGIONAL EXAMPLES:** "Alarm lagawa" (Bhojpuri), "Alarm laa" (Punjabi), "Alarm pettu" (Telugu), "Alarm vayyi" (Tamil)
                   - If time is clear: confidence=HIGH
                   - If time is ambiguous: confidence=MEDIUM, ask for clarification
                   - Examples:
                      * "Set alarm for 7 AM" -> {"action": "SET_ALARM", "confidence": "HIGH", "params": {"time": "7 AM", "label": null}, "spoken_response": "Done! Subah 7 baje ka alarm set ho gaya."}
                      * "Set alarm for 7" -> {"action": "SET_ALARM", "confidence": "MEDIUM", "params": {"time": "7"}, "spoken_response": "7 AM ya 7 PM bhai?"}
                
                5. STOP_SESSION (EXPLICIT EXIT ONLY)
                   - **ONLY** use when user EXPLICITLY wants to END the conversation
                   - Examples: "stop", "bye", "goodbye", "band karo", "bas", "cancel", "rehne de", "chup"
                   - **REGIONAL:** "Ruk ja" (Hindi), "Thamb" (Marathi), "Aagu" (Telugu), "Nirthu" (Tamil), "Ruk" (Punjabi), "Thamba" (Bengali)
                   - **DO NOT** use for normal conversation or questions
                    - Output: {"action": "STOP_SESSION", "confidence": "HIGH", "params": {}, "spoken_response": "Chalo theek hai yaar, baad mein baat karte hain. Bye!"}
                
                6. APP_AUTOMATION (Ride Booking)
                   - Use when user wants to book a ride on Rapido
                   - Supported apps: "Rapido" only
                   - Requires: destination (where user wants to go)
                   - Optional: vehicle ("bike" or "cab"/"auto")
                   - **REGIONAL EXAMPLES:** "Rapido se ghar le chal" (Hindi), "Rapido book kar office tak" (Hinglish), "Rapido la book kar" (Punjabi)
                   - **CRITICAL**: NEVER hardcode addresses. Always use user's exact words.
                   - If destination is clear: confidence=HIGH
                   - If destination is missing: confidence=MEDIUM, ask where
                   - Examples:
                     * "Rapido se bike bula de office tak" -> {"action": "APP_AUTOMATION", "confidence": "HIGH", "params": {"app": "Rapido", "destination": "office", "vehicle": "bike"}, "spoken_response": "Theek hai, Rapido pe office ke liye bike book karta hoon."}
                     * "Microsoft office jana hai rapido krde" -> {"action": "APP_AUTOMATION", "confidence": "HIGH", "params": {"app": "Rapido", "destination": "Microsoft office", "vehicle": null}, "spoken_response": "Okay, Microsoft office ke liye Rapido book karta hoon."}
                     * "Rapido book krde" -> {"action": "APP_AUTOMATION", "confidence": "MEDIUM", "params": {"app": "Rapido"}, "spoken_response": "Kahan jana hai yaar?"}
                
                7. OPEN_CAMERA
                   - Use when user wants to open camera (photo, video, selfie, QR scan)
                   - Optional: mode ("photo", "video", "selfie", "qr")
                   - **REGIONAL EXAMPLES:** "Camera khol" (Hindi), "Photo khich" (Hinglish), "Photo theek" (Bhojpuri), "Camera teeru" (Telugu), "Photo edunga" (Tamil)
                   - If mode is clear: confidence=HIGH
                   - If mode ambiguous: default to "photo"
                   - Examples:
                      * "Camera khol de" -> {"action": "OPEN_CAMERA", "confidence": "HIGH", "params": {"mode": "photo"}, "spoken_response": "Camera on kar diya, photo khicho!"}
                      * "Selfie leni hai" -> {"action": "OPEN_CAMERA", "confidence": "HIGH", "params": {"mode": "selfie"}, "spoken_response": "Selfie time! Smile karo yaar."}
                      * "Video record karo" -> {"action": "OPEN_CAMERA", "confidence": "HIGH", "params": {"mode": "video"}, "spoken_response": "Theek hai bhai, video recording start kar rahi hoon."}
                      * "QR code scan kar" -> {"action": "OPEN_CAMERA", "confidence": "HIGH", "params": {"mode": "qr"}, "spoken_response": "Camera khol rahi hoon, scan ka option select kar lo."}
                
                8. SEARCH_AMAZON
                   - Use when user wants to search for products on Amazon
                   - Requires: query (product to search for)
                   - **REGIONAL EXAMPLES:** "Amazon pe shoes dhundh" (Hindi), "Amazon me iPhone search kar" (Hinglish), "Amazon pe laptop dikha" (Hindi), "Amazon se mobile lena hai" (Hinglish)
                   - If query is clear: confidence=HIGH
                   - If query is vague/missing: confidence=MEDIUM, ask what to search
                   - Examples:
                      * "iPhone 12 Amazon pe dhundh de" -> {"action": "SEARCH_AMAZON", "confidence": "HIGH", "params": {"query": "iPhone 12"}, "spoken_response": "Amazon pe iPhone 12 dhundh rahi hoon, thoda ruko!"}
                      * "Amazon me shoes dikha do" -> {"action": "SEARCH_AMAZON", "confidence": "HIGH", "params": {"query": "shoes"}, "spoken_response": "Shoes dhundhne chali Amazon pe, best deals lati hoon!"}
                      * "Mujhe laptop lena hai Amazon se" -> {"action": "SEARCH_AMAZON", "confidence": "HIGH", "params": {"query": "laptop"}, "spoken_response": "Laptop search kar rahi hoon Amazon pe, budget bata dena!"}
                      * "Amazon khol de" -> {"action": "SEARCH_AMAZON", "confidence": "MEDIUM", "params": {}, "spoken_response": "Amazon pe kya dhundhna hai yaar?"}
                      * "Kuch sasta dhundh Amazon me" -> {"action": "SEARCH_AMAZON", "confidence": "MEDIUM", "params": {}, "spoken_response": "Kya chahiye exactly? Product ka naam bata do."}
                
                9. TRIGGER_SOS
                   - Use in EXTREME EMERGENCIES when user is in danger or needs police/help
                   - **REGIONAL EXAMPLES:** "Bachao!", "Help me!", "Call police", "Danger!", "Mujhe bachao", "Emergency hai"
                   - ACTION: Dials 112 immediately.
                   - **CRITICAL**: Use confidence=HIGH for any distress or urgency phrases.
                   - Examples:
                      * "Bachao!" -> {"action": "TRIGGER_SOS", "confidence": "HIGH", "params": {"emergency_type": "general"}, "spoken_response": "Shant raho, main abhi emergency services ko call kar raha hoon."}
                      * "Help me, someone is following me" -> {"action": "TRIGGER_SOS", "confidence": "HIGH", "params": {"emergency_type": "police", "reason": "following"}, "spoken_response": "Darna mat bhai, police ko call laga rahi hoon. Safe place dhundho."}
                
                CONFIDENCE RULES (ABSOLUTE):
                - HIGH: Intent is complete and executable -> Execute immediately
                - MEDIUM: Intent partially understood, missing params -> Ask clarification, restart STT
                - LOW: Intent unclear or ambiguous -> Ask clarification, restart STT
                
                **NEVER execute on MEDIUM or LOW confidence.**
                
                CONTEXT HANDLING:
                - If conversation context is provided, use it to resolve references like "uska", "iska", "pehla wala"
                - Example: Context shows "Dhurandar movie", user says "uska title track" -> query="Dhurandar title track"
                
                SPOKEN_RESPONSE RULES:
                1. **VARIETY IS KEY**: Never use the same response twice in a row. Rotate between different synonym-rich phrases.
                2. **NATURAL HINGLISH**: Use natural warmth. Instead of "Calling Kaushal", use "Haan bhai, Kaushal ko call laga raha hoon" or "Theek hai, Kaushal ko dial karta hoon".
                3. **ACTION-SPECIFIC VARIATIONS**:
                   - Camera: "Camera khol rahi hoon, smile!", "Chalo photo lete hain, camera on kar diya", "Ek second, camera khul raha hai".
                   - Media: "Aapka gana chala raha hoon, enjoy!", "Music on kar diya, suno!", "Haan bhai, video ready hai".
                   - Alarm: "Theek hai bhai, alarm set ho gaya!", "Alarm laga diya hai, time pe uth jana".
                4. **AVOID ROBOTIC PHRASES**: Avoid "Executing action", "Command perceived", "Task started".
                5. **SOCIABILITY**: Be a friend. Use "yaar", "bhai", "bro" naturally.
                
                OUTPUT FORMAT (MANDATORY JSON):
                {
                    "action": "ACTION_NAME",
                    "confidence": "HIGH | MEDIUM | LOW",
                    "params": { ... },
                    "spoken_response": "..."
                }
            """.trimIndent()

            // Use SmartModelRouter if available (session-locked routing)
            // Otherwise fall back to direct client calls
            val jsonString: String? = if (smartRouter != null) {
                Log.d(TAG, "Using SmartModelRouter for session-locked LLM routing")
                
                // Force Gemini for regional languages (better multilingual support)
                val detectedLang = smartRouter.forceGeminiForRegionalLanguage(text)
                
                // Build user prompt with detected language hint
                val userPrompt = if (detectedLang != null) {
                    "🔴 DETECTED LANGUAGE: $detectedLang - YOU MUST RESPOND ENTIRELY IN $detectedLang!\n\nAnalyze this input: \"$text\""
                } else {
                    "Analyze this input: \"$text\""
                }
                
                smartRouter.generateSmart(
                    systemInstruction = systemPrompt,
                    userText = userPrompt
                )
            } else {
                // Legacy path: Direct client calls with manual fallback
                var result = client.generateReply(
                    systemInstruction = systemPrompt,
                    userText = "Analyze this input: \"$text\""
                )
                
                // FALLBACK TO OPENROUTER IF GEMINI FAILS
                if (result.isNullOrBlank()) {
                    Log.e(TAG, "Gemini failed (returned null/empty). Attempting OpenRouter Failover...")
                    if (openRouterClient.isConfigured()) {
                        Log.d(TAG, "OpenRouter is configured. Sending request...")
                        result = openRouterClient.generateReply(
                            systemInstruction = systemPrompt,
                            userText = "Analyze this input: \"$text\""
                        )
                        Log.d(TAG, "OpenRouter Response: $result")
                    } else {
                        Log.e(TAG, "OpenRouter is NOT configured. Skipping failover.")
                    }
                }
                result
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
                val defaultConfidence = if (action in listOf("CALL_CONTACT", "PLAY_MEDIA", "SET_ALARM", "UPDATE_SETTING", "STOP_SESSION", "APP_AUTOMATION", "OPEN_CAMERA", "SEARCH_AMAZON")) "HIGH" else "LOW"
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
                    action in listOf("CALL_CONTACT", "PLAY_MEDIA", "SET_ALARM", "UPDATE_SETTING", "STOP_SESSION", "APP_AUTOMATION", "OPEN_CAMERA", "SEARCH_AMAZON", "TRIGGER_SOS", "SOS") -> IntentType.DIRECT
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
                
                // Extract video filter params (for smart YouTube video selection)
                val videoFilter = getParam("video_filter", "filter").ifBlank { null }
                val creatorName = getParam("creator_name", "creator", "channel").ifBlank { null }
                
                Log.d(TAG, "PlayMedia: query='$query', type=$mediaType, filter=$videoFilter, creator=$creatorName")
                
                AssistantIntent.Action.PlayMedia(query, mediaType, videoFilter, creatorName, spokenResponse)
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
            "APP_AUTOMATION" -> {
                // App automation (currently only Rapido supported)
                val app = getParam("app").lowercase()
                Log.i(TAG, "APP_AUTOMATION detected: app='$app'")
                
                if (app == "rapido") {
                    val destination = getParam("destination", "whereTo", "where_to", "to")
                    val vehicle = getParam("vehicle", "vehicleType", "vehicle_type").let {
                        when {
                            it.isBlank() -> null
                            it.lowercase().contains("bike") -> "bike"
                            it.lowercase().contains("cab") || it.lowercase().contains("auto") -> "cab"
                            else -> it.lowercase()
                        }
                    }
                    
                    Log.i(TAG, "Rapido booking: destination='$destination', vehicle='$vehicle'")
                    AssistantIntent.Action.BookRapido(destination.ifBlank { null }, vehicle, null, spokenResponse)
                } else {
                    Log.w(TAG, "Unsupported app for automation: $app")
                    AssistantIntent.Clarify("Sorry, $app ke liye automation abhi available nahi hai.")
                }
            }
            "OPEN_CAMERA" -> {
                val mode = getParam("mode", "camera_mode")
                Log.i(TAG, "OPEN_CAMERA detected: mode='$mode'")
                AssistantIntent.Action.OpenCamera(mode.ifBlank { null }, spokenResponse)
            }
            "SEARCH_AMAZON" -> {
                val query = getParam("query", "product", "search_term", "search")
                Log.i(TAG, "SEARCH_AMAZON detected: query='$query'")
                AssistantIntent.Action.SearchAmazon(query.ifBlank { null }, spokenResponse)
            }
            "TRIGGER_SOS", "SOS" -> {
                val emergencyType = getParam("emergency_type").ifBlank { "general" }
                val reason = getParam("reason").ifBlank { originalText }
                AssistantIntent.Action.TriggerSOS(reason, emergencyType, spokenResponse)
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
