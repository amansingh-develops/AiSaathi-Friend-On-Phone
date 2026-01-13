package com.assistant.services.elevenlabs

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client for interacting with ElevenLabs Conversational AI Agent via WebSocket.
 * 
 * Agent ID: agent_3701kec1m271frfvg8c5ac5ypn74
 */
class ElevenLabsAgentClient(
    private val agentId: String = "agent_3701kec1m271frfvg8c5ac5ypn74",
    private val listener: Listener
) {

    interface Listener {
        fun onAudioData(audioData: ByteArray)
        fun onAgentResponseStarted()
        fun onAgentResponseStopped()
        fun onAgentText(text: String) // NEW: For "Silent Gemini Bridge"
        fun onError(t: Throwable)
    }

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isInterrupted = AtomicBoolean(false)

    companion object {
        private const val TAG = "ElevenLabsAgent"
        private const val AGENT_WSS_URL = "wss://api.elevenlabs.io/v1/convai/conversation?agent_id="
    }

    fun connect() {
        if (isConnected.get()) return

        val url = "$AGENT_WSS_URL$agentId"
        val request = Request.Builder().url(url).build()

        Log.d(TAG, "Connecting to Agent: $agentId")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Connected")
                isConnected.set(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket Closing: $code / $reason")
                isConnected.set(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure", t)
                isConnected.set(false)
                listener.onError(t)
            }
        })
    }

    fun sendText(text: String) {
        if (!isConnected.get()) {
            connect()
        }
        // New turn: reset interrupt flag so we accept audio
        isInterrupted.set(false)
        
        val json = JSONObject()
        json.put("text", text)
        webSocket?.send(json.toString())
    }

    /**
     * Closes the WebSocket connection.
     */
    fun close() {
        try {
            webSocket?.close(1000, "Session ended")
            webSocket = null
            isConnected.set(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
    }

    /**
     * Interrupts the current response handling (ignores incoming audio)
     * but keeps the connection open for the next turn.
     * This is crucial for low-latency "barge-in".
     */
    fun interrupt() {
        Log.d(TAG, "Interrupting Agent (ignoring subsequent audio)")
        isInterrupted.set(true)
        // Ideally we would send a "stop" frame to server if API supported it.
        // For now, client-side ignore is sufficient.
    }

    private fun handleMessage(text: String) {
        if (isInterrupted.get()) {
             // Drop packet
             return
        }
        try {
            val json = JSONObject(text)
            
            // 1. Audio Event
            if (json.has("audio_event")) {
                val event = json.getJSONObject("audio_event")
                if (event.has("audio_base_64")) {
                    val b64 = event.getString("audio_base_64")
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    listener.onAudioData(bytes)
                }
            } 
            // 2. Agent Response Event (Text)
            // Structure assumption: { "type": "agent_response", "agent_response_event": { "agent_response": "..." } }
            // OR simply check for "agent_response" key if top level.
            // ElevenLabs docs vary, but usually "agent_response" contains the text.
            else if (json.has("agent_response_event")) {
                 val event = json.getJSONObject("agent_response_event")
                 
                 // State change
                 if (event.has("agent_response")) {
                     val responseState = event.getString("agent_response") // "started", "stopped"
                     if (responseState == "started") listener.onAgentResponseStarted()
                     if (responseState == "stopped") listener.onAgentResponseStopped()
                 }
                 
                 // Transcript/Text?
                 // Sometimes it's in "agent_response_message" or inside the event.
                 // Let's check for a "text" field inside the event or top level?
                 // Actually common pattern is "agent_response_event": { "text": "..." }?
                 // Or separate "transcript_event".
                 
            }
            // 3. Explicit Text/Transcript logic
            // If we see a "text" field related to agent, grab it.
            // Some versions send: { "type": "agent_response", "text": "Hello" }
            if (json.has("text") && !json.has("user_message")) { 
                 // Avoid reflecting user's own message if echo is on
                 val txt = json.getString("text")
                 if (txt.isNotBlank()) {
                     listener.onAgentText(txt)
                 }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User Session Ended")
        isConnected.set(false)
        webSocket = null
    }
}
