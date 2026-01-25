package com.assistant.services.llm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Maintains persistent HTTP connections for LLM API calls.
 * 
 * Benefits:
 * - Eliminates TLS handshake overhead on subsequent calls
 * - Reduces DNS resolution latency via keep-alive
 * - Pre-warms connections during user speech for faster response
 * 
 * Thread-safe via OkHttp's internal connection pooling.
 */
class PersistentLlmClientPool {
    
    companion object {
        private const val TAG = "LLMClientPool"
        
        // Connection pool configuration
        private const val MAX_IDLE_CONNECTIONS = 5
        private const val KEEP_ALIVE_MINUTES = 5L
    }
    
    /**
     * Result of warmup including health status.
     * @param latencyMs Latency in milliseconds, or -1 if failed
     * @param statusCode HTTP status code (200=healthy, 404=connection ok but endpoint not found)
     * @param isHealthy True if status is 2xx (preferred for model selection)
     */
    data class WarmupResult(
        val latencyMs: Long,
        val statusCode: Int,
        val isHealthy: Boolean
    )
    
    /**
     * Shared OkHttpClient with connection pooling.
     * Reused across all LLM calls to maintain persistent connections.
     */
    val sharedClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(
            ConnectionPool(
                maxIdleConnections = MAX_IDLE_CONNECTIONS,
                keepAliveDuration = KEEP_ALIVE_MINUTES,
                timeUnit = TimeUnit.MINUTES
            )
        )
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    /**
     * Warm up connection to a specific model endpoint.
     * Uses provided request (handshake) to establish TCP/TLS and verify health.
     * 
     * @param model Enum for logging
     * @param request Functional API request to verify connectivity and key validity
     * @return WarmupResult with latency and health status
     */
    suspend fun warmUp(model: SmartModelRouter.LlmModel, request: Request): WarmupResult {
        Log.d(TAG, "Warming model=$model (Handshake: ${request.url})")
        
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                sharedClient.newCall(request).execute().use { response ->
                    val latencyMs = System.currentTimeMillis() - startTime
                    val statusCode = response.code
                    val isHealthy = statusCode in 200..299
                    
                    // Log with health indicator
                    val healthStr = if (isHealthy) "HEALTHY" else "DEGRADED"
                    Log.d(TAG, "WarmUp $healthStr: $model, status=$statusCode, latency=${latencyMs}ms")
                    
                    WarmupResult(latencyMs, statusCode, isHealthy)
                }
            } catch (e: Exception) {
                val latencyMs = System.currentTimeMillis() - startTime
                Log.w(TAG, "WarmUp FAILED for $model after ${latencyMs}ms: ${e.message}")
                WarmupResult(-1L, 0, false)
            }
        }
    }
    
    /**
     * Warm up ALL model endpoints simultaneously using provided handshake requests.
     */
    suspend fun warmUpAll(requests: Map<SmartModelRouter.LlmModel, Request>): Map<SmartModelRouter.LlmModel, WarmupResult> {
        Log.d(TAG, "Warming ALL models with functional handshakes")
        
        return withContext(Dispatchers.IO) {
            val results = mutableMapOf<SmartModelRouter.LlmModel, WarmupResult>()
            
            // Launch all warm-ups in parallel
            val jobs = requests.entries.map { (model, request) ->
                async {
                    model to warmUp(model, request)
                }
            }
            
            // Collect results
            jobs.awaitAll().forEach { (model, result) ->
                results[model] = result
            }
            
            // Log summary with health status
            val summary = results.entries
                .sortedBy { if (it.value.isHealthy) 0 else 1 } // Healthy first
                .joinToString { "${it.key}=${it.value.latencyMs}ms(${if (it.value.isHealthy) "OK" else "${it.value.statusCode}"})" }
            Log.d(TAG, "WarmUp ALL complete: $summary")
            
            results
        }
    }
    
    /**
     * Get connection pool statistics for debugging.
     */
    fun getPoolStats(): String {
        val pool = sharedClient.connectionPool
        return "Connections: idle=${pool.idleConnectionCount()}, total=${pool.connectionCount()}"
    }
    
    /**
     * Evict all idle connections (useful for cleanup).
     */
    fun evictAll() {
        sharedClient.connectionPool.evictAll()
        Log.d(TAG, "Evicted all idle connections")
    }
}
