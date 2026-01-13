package com.assistant.debug

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Minimal debug logger for Cursor DEBUG MODE.
 *
 * Writes NDJSON to app-internal storage and Logcat, and best-effort POSTs to host endpoints
 * for emulator-based collection.
 *
 * IMPORTANT: Never log secrets (API keys, tokens, PII).
 */
object AgentDebugLog {
    private const val TAG = "AgentDebug"
    private const val SESSION_ID = "debug-session"
    private const val LOG_FILE_NAME = "agent_debug.ndjson"
    private const val ENDPOINT_LOCALHOST =
        "http://127.0.0.1:7242/ingest/463a19ba-cfd7-4a69-9b74-7a0a1e072589"
    private const val ENDPOINT_EMULATOR_HOST =
        "http://10.0.2.2:7242/ingest/463a19ba-cfd7-4a69-9b74-7a0a1e072589"

    fun log(
        context: Context?,
        runId: String,
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap()
    ) {
        try {
            val payload = JSONObject().apply {
                put("id", "log_${System.currentTimeMillis()}_${UUID.randomUUID()}")
                put("timestamp", System.currentTimeMillis())
                put("sessionId", SESSION_ID)
                put("runId", runId)
                put("hypothesisId", hypothesisId)
                put("location", location)
                put("message", message)
                put("data", JSONObject(data))
            }
            val line = payload.toString()

            // 1) Logcat (always available)
            Log.d(TAG, line)

            // 2) Write NDJSON to app internal storage (pullable via Device File Explorer)
            context?.let {
                try {
                    val f = File(it.filesDir, LOG_FILE_NAME)
                    f.appendText(line + "\n")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed writing internal debug log: ${e.message}")
                }
            }

            // 3) Best-effort POST to host endpoints (works on emulator via 10.0.2.2)
            postAsync(ENDPOINT_EMULATOR_HOST, line)
            postAsync(ENDPOINT_LOCALHOST, line)
        } catch (e: Exception) {
            Log.w(TAG, "AgentDebugLog.log failed: ${e.message}")
        }
    }

    fun networkSnapshot(context: Context?): Map<String, Any?> {
        if (context == null) return mapOf("network" to "no_context")
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            mapOf(
                "sdkInt" to Build.VERSION.SDK_INT,
                "model" to Build.MODEL,
                "activeNetwork" to (network != null),
                "hasInternet" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true),
                "validated" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
                "wifi" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true),
                "cell" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true),
                "ethernet" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true)
            )
        } catch (e: Exception) {
            mapOf("network_error" to (e.message ?: "unknown"))
        }
    }

    private fun postAsync(endpoint: String, jsonLine: String) {
        Thread {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 800
                    readTimeout = 800
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { os ->
                    os.write(jsonLine.toByteArray(Charsets.UTF_8))
                }
                conn.inputStream.close()
                conn.disconnect()
            } catch (_: Exception) {
                // Ignore: debug only, may fail on physical devices / non-emulator networking
            }
        }.start()
    }
}


