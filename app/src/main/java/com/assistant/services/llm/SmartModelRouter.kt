package com.assistant.services.llm

import android.util.Log
import com.assistant.services.gemini.GeminiClient
import com.assistant.services.openrouter.OpenRouterClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Smart LLM router with session-locked model selection.
 * 
 * Strategy:
 * 1. During warmup (STT phase), race all models and pick the fastest
 * 2. Lock that model as "session best" for the entire session
 * 3. All LLM requests go DIRECTLY to session best model
 * 4. Only fallback if session best fails
 * 
 * This eliminates wasteful calls to slow/quota-exceeded models.
 */
class SmartModelRouter(
    private val clientPool: PersistentLlmClientPool,
    private val geminiClient: GeminiClient,
    private val openRouterClient: OpenRouterClient
) {
    companion object {
        private const val TAG = "SmartRouter"
    }
    
    /**
     * Supported LLM backends for routing.
     * Note: GeminiClient handles its own internal model fallback chain.
     */
    enum class LlmModel {
        GEMINI,      // GeminiClient (handles internal gemini-2.5-flash -> gemini-3-flash fallback)
        OPENROUTER
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /**
     * Session-locked best model. Set after warmup, used for all requests in session.
     * Volatile for thread-safe reads.
     */
    @Volatile
    private var sessionBestModel: LlmModel? = null
    
    /**
     * Warmup results for model selection.
     */
    private val warmupResults = mutableMapOf<LlmModel, PersistentLlmClientPool.WarmupResult>()
    
    /**
     * Get the currently locked session model (for external queries).
     */
    fun getSessionModel(): LlmModel? = sessionBestModel
    
    /**
     * Generate a reply using session-locked model routing.
     * Routes directly to session best, fallback only on failure.
     */
    suspend fun generateSmart(systemInstruction: String, userText: String): String? {
        val primary = sessionBestModel ?: pickBestModel()
        
        Log.d(TAG, "Routing to session model: $primary")
        
        // Try primary (session best) first
        val result = callModel(primary, systemInstruction, userText)
        
        if (result != null) {
            return result
        }
        
        // Primary failed - try fallbacks in order
        Log.w(TAG, "$primary failed, trying fallbacks...")
        
        val fallbackOrder = LlmModel.values().filter { it != primary }
        
        for (fallback in fallbackOrder) {
            Log.d(TAG, "Trying fallback: $fallback")
            val fallbackResult = callModel(fallback, systemInstruction, userText)
            if (fallbackResult != null) {
                // Update session best to the working model
                sessionBestModel = fallback
                Log.d(TAG, "Fallback successful, updating session model to: $fallback")
                return fallbackResult
            }
        }
        
        Log.e(TAG, "All models failed")
        return null
    }
    
    /**
     * Call a specific model.
     */
    private suspend fun callModel(
        model: LlmModel,
        systemInstruction: String,
        userText: String
    ): String? {
        val startTime = System.currentTimeMillis()
        
        return try {
            val response = when (model) {
                LlmModel.GEMINI -> {
                    // GeminiClient handles its own internal model fallback chain
                    geminiClient.generateReply(systemInstruction, userText)
                }
                LlmModel.OPENROUTER -> {
                    openRouterClient.generateReply(systemInstruction, userText)
                }
            }
            
            val latencyMs = System.currentTimeMillis() - startTime
            
            if (response != null) {
                Log.d(TAG, "$model completed in ${latencyMs}ms")
            } else {
                Log.w(TAG, "$model returned null after ${latencyMs}ms")
            }
            
            response
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "$model failed after ${latencyMs}ms: ${e.message}")
            null
        }
    }
    
    /**
     * Pick the best model based on health and latency.
     * Strategy:
     * 1. Filter for healthy models (2xx status)
     * 2. Of those, pick the one with lowest latency
     * 3. If no healthy models, pick from any that responded (e.g. 404)
     * 4. Default to GEMINI
     */
    private fun pickBestModel(): LlmModel {
        val healthyModels = warmupResults.entries
            .filter { it.value.isHealthy && it.value.latencyMs > 0 }
            .minByOrNull { it.value.latencyMs }
        
        if (healthyModels != null) {
            val model = healthyModels.key
            Log.d(TAG, "PickBest=$model (HEALTHY, latency=${healthyModels.value.latencyMs}ms)")
            return model
        }
        
        // Fallback: pick any that responded even if unhealthy (e.g. 404)
        val anyModel = warmupResults.entries
            .filter { it.value.latencyMs > 0 }
            .minByOrNull { it.value.latencyMs }
            ?.key ?: LlmModel.GEMINI
            
        Log.d(TAG, "PickBest=$anyModel (DEGRADED/FALLBACK, latency=${warmupResults[anyModel]?.latencyMs ?: 0}ms)")
        return anyModel
    }
    
    /**
     * Warm up connections to ALL models and LOCK the best one for this session.
     * Call this during STT to:
     * 1. Pre-establish connections to all endpoints
     * 2. Measure actual connection latency
     * 3. Lock the fastest as session model
     */
    /**
     * Warm up connections to ALL models with functional API handshakes and LOCK the best one.
     */
    fun warmUpAllModels() {
        Log.d(TAG, "LLMWarmup: Racing all models with functional API handshakes")
        
        val requests = mutableMapOf<LlmModel, okhttp3.Request>()
        
        // Collect health check requests from all configured clients
        geminiClient.getHealthCheckRequest()?.let { requests[LlmModel.GEMINI] = it }
        openRouterClient.getHealthCheckRequest()?.let { requests[LlmModel.OPENROUTER] = it }
        
        if (requests.isEmpty()) {
            Log.w(TAG, "No LLM clients configured for warmup")
            return
        }
        
        scope.launch {
            val results = clientPool.warmUpAll(requests)
            
            // Store results
            warmupResults.clear()
            warmupResults.putAll(results)
            
            // LOCK the best model for this session
            val best = pickBestModel()
            sessionBestModel = best
            
            val stats = results[best]
            Log.i(TAG, "SESSION MODEL LOCKED: $best (latency=${stats?.latencyMs ?: 0}ms, status=${stats?.statusCode ?: 0})")
        }
    }
    
    /**
     * Reset session model (call when session ends).
     */
    fun resetSession() {
        val prev = sessionBestModel
        sessionBestModel = null
        warmupResults.clear()
        Log.d(TAG, "Session reset, cleared model lock (was: $prev)")
    }
    
    /**
     * Get debug status of router.
     */
    fun getStatus(): String {
        return buildString {
            appendLine("=== Smart Router Status ===")
            appendLine("Session Model: ${sessionBestModel ?: "NOT LOCKED"}")
            appendLine("Pool: ${clientPool.getPoolStats()}")
            appendLine("Warmup Results: $warmupResults")
        }
    }
    
    /**
     * Detected regional language for this session (used in prompts).
     */
    @Volatile
    var detectedLanguage: String? = null
        private set
    
    /**
     * Force Gemini for regional Indian languages.
     * Gemini has better multilingual support than OpenRouter models.
     * Returns the detected language name or null.
     */
    fun forceGeminiForRegionalLanguage(userText: String): String? {
        val language = detectSpecificLanguage(userText)
        if (language != null) {
            sessionBestModel = LlmModel.GEMINI
            detectedLanguage = language
            Log.i(TAG, "🌐 $language DETECTED → Forcing GEMINI for better multilingual support")
        }
        return language
    }
    
    /**
     * Detect specific regional Indian language from user text.
     * Returns language name or null if not detected.
     */
    private fun detectSpecificLanguage(text: String): String? {
        val lowerText = text.lowercase()
        val words = lowerText.split(Regex("[\\s,\\.!?]+"))
        
        // Bhojpuri - greetings, commands, questions + STT variations
        val bhojpuri = listOf(
            "hum", "ba", "rahal", "tohar", "kaisan", "karhal", "baram",
            "kara", "tara", "hamaar", "rahe", "kare",
            "jaaib", "aaib", "khai", "sunaa",
            "karal", "koral", "hamra", "tohaar", "tohra"
        )
        if (words.any { bhojpuri.contains(it) }) return "BHOJPURI"
        
        // Punjabi - greetings, commands, questions + STT variations
        val punjabi = listOf(
            "oye", "veere", "kiven", "vadiya", "bai", "das",
            "karda", "reha", "paaji", "sat", "sri", "akal",
            "changa", "laao",
            "kive", "kivan", "vadya", "vadia", "kithe", "kithey"
        )
        if (words.any { punjabi.contains(it) }) return "PUNJABI"
        
        // Telugu - greetings, commands, questions + STT variations
        val telugu = listOf(
            "ela", "enti", "cheppandi", "chestunnav", "bagunna", "nuvvu",
            "emi", "ayindi", "kavali", "pettu", "cheyyi", "vinandi", "chudandi",
            "elaa", "untundi", "chepu", "padu", "raandi", "vella",
            "emiti", "emundi", "entidi", "bagundi", "chestunar"
        )
        if (words.any { telugu.contains(it) }) return "TELUGU"
        
        // Tamil - greetings, commands, questions + STT variations
        val tamil = listOf(
            "eppadi", "sollunga", "panra", "irukka", "nalla",
            "achu", "vaanga", "podu", "kelunga", "paaru", "ponga", "iruken",
            "solla", "edunga", "kodunga", "vanakkam", "iruku", "theriyum",
            "epdi", "epadi", "enakku", "unakku", "irukku", "pannunga"
        )
        if (words.any { tamil.contains(it) }) return "TAMIL"
        
        // Bengali - greetings, commands, questions + STT variations
        val bengali = listOf(
            "kemon", "korcho", "bhalo", "achi", "tumi",
            "holo", "khobor", "dao", "shono", "eso",
            "kotha", "koro", "acho", "thako", "chai",
            "kemun", "kercho", "korchi", "aachhi", "jabi", "esho"
        )
        if (words.any { bengali.contains(it) }) return "BENGALI"
        
        // Marathi - greetings, commands, questions + STT variations
        val marathi = listOf(
            "kay", "kasa", "aahes", "mhanta", "kartos",
            "challay", "sanga", "aikla", "bagha", "namaskar",
            "kela", "zala", "karu", "aahe", "mhanun",
            "kaay", "kasaa", "mhanje", "zhaala", "karto"
        )
        if (words.any { marathi.contains(it) }) return "MARATHI"
        
        // Gujarati - greetings, commands, questions + STT variations
        val gujarati = listOf(
            "kem", "cho", "shu", "chale", "maja", "tame",
            "che", "thayo", "sambhlo", "aavo", "khabar",
            "avu", "karun", "kari", "joiye", "saro", "barabar",
            "kemcho", "kemchu", "shun", "thayun", "avjo", "samjo"
        )
        if (words.any { gujarati.contains(it) }) return "GUJARATI"
        
        // Kannada - greetings, commands, questions + STT variations
        val kannada = listOf(
            "hege", "iddira", "enu", "agta", "ide", "chennag", "neevu",
            "hegiddira", "maadu", "keli", "noodu", "banni", "hogi", "namaskara",
            "aagide", "maadtini", "helri", "guru", "namma", "nimma",
            "heg", "maadi", "barthini", "hogona", "barri"
        )
        if (words.any { kannada.contains(it) }) return "KANNADA"
        
        return null
    }
}

