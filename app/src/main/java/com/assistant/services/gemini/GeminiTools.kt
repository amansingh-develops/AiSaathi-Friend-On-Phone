package com.assistant.services.gemini

import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.defineFunction

/**
 * Defines the tools available to the Gemini AI.
 */
object GeminiTools {

    val searchContacts = defineFunction(
        name = "search_contacts",
        description = "Search for a contact in the device address book. Returns names and phone numbers.",
        parameters = listOf(
            Schema.str("name", "The name or partial name to search for (e.g. 'Aman', 'Mom')")
        )
    )

    val setAlarm = defineFunction(
        name = "set_alarm",
        description = "Set an alarm for a specific time.",
        parameters = listOf(
            Schema.str("time", "The time to set the alarm for (e.g. '7:00 AM', '18:30')"),
            Schema.str("label", "Optional label for the alarm")
        )
    )
    
     val getAlarms = defineFunction(
        name = "get_alarms",
        description = "Get a list of currently set alarms (not fully supported on Android 14+ without special permissions, but useful for context if cached). For now returns empty or mock.",
        parameters = emptyList<Schema<Any>>()
    )

    val playMedia = defineFunction(
        name = "play_media",
        description = "Play music or video on a specific app or generally.",
        parameters = listOf(
            Schema.str("query", "The song, artist, video, or playlist to play."),
            Schema.str("media_type", "MUSIC or VIDEO"),
            Schema.str("app", "Preferred app if specified (spotify, youtube, etc.)")
        )
    )
    
    // Tools list
    val allTools = listOf(
        Tool(listOf(searchContacts, setAlarm, playMedia)) // Add getAlarms if implemented
    )
}
