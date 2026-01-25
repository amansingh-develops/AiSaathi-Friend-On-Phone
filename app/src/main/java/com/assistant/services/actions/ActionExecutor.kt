package com.assistant.services.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.util.Log
import com.assistant.services.intent.AssistantIntent

/**
 * Executes system actions with permission-guarded execution.
 *
 * IMPORTANT:
 * - Must be safe to call quickly.
 * - Must NEVER crash.
 * - Must not do heavy work on the main thread.
 * - Checks permissions before execution via ExecutionGuard
 *
 * In this repo, only a couple of example actions exist; expand as needed.
 */
class ActionExecutor(
    private val context: Context,
    private val mediaRouter: MediaRouter = MediaRouter(com.assistant.data.PreferencesRepository(context)),
    private val executionGuard: com.assistant.services.actions.ExecutionGuard? = null
) {
    companion object {
        private const val TAG = "ActionExecutor"
    }

    suspend fun execute(
        action: AssistantIntent.Action,
        conversationContext: String? = null
    ): ActionResult {
        return try {
            // For actions that don't require permissions, execute immediately
            when (action) {
                is AssistantIntent.Action.StopListeningSession -> {
                    // User wants to end the conversation
                    Log.d(TAG, "StopListeningSession requested by LLM")
                    // Return special result that tells ListeningSessionManager to end session
                    val ack = action.acknowledgement ?: "Theek hai."
                    ActionResult.EndSession(ack)
                }
                AssistantIntent.Action.OpenSettings -> {
                    Log.d(TAG, "OpenSettings requested.")
                    ActionResult.Success
                }
                is AssistantIntent.Action.PlayMedia -> {
                    // Check permissions via ExecutionGuard before executing
                    if (executionGuard != null) {
                        // Permission-guarded execution
                        var result: ActionResult = ActionResult.Success
                        executionGuard.checkAndExecute(
                            intent = action,
                            originalUserText = "play ${action.query}",
                            confidence = 1.0f,
                            activity = com.assistant.services.context.ActivityContextProvider.getActivity(),
                            conversationContext = conversationContext,  // Pass session context
                            onExecute = {
                                // Execute media playback
                                kotlinx.coroutines.runBlocking {
                                    result = executePlayMedia(action)
                                }
                            }
                        )
                        result
                    } else {
                        // Fallback: execute without permission guard
                        executePlayMedia(action)
                    }
                }
                is AssistantIntent.Action.SetAlarm -> {
                    launchAlarm(action.timeText, action.label)
                }
                is AssistantIntent.Action.CallContact -> {
                    // Check permissions via ExecutionGuard before executing
                    if (executionGuard != null) {
                        var result: ActionResult = ActionResult.Success
                        executionGuard.checkAndExecute(
                            intent = action,
                            originalUserText = "call ${action.contactName}",
                            confidence = 1.0f,
                            activity = com.assistant.services.context.ActivityContextProvider.getActivity(),
                            conversationContext = conversationContext,  // Pass session context
                            onExecute = {
                                kotlinx.coroutines.runBlocking {
                                    result = if (!action.number.isNullOrBlank()) {
                                        dialDirect(action.number)
                                    } else {
                                        launchDialer(action.contactName)
                                    }
                                }
                            }
                        )
                        result
                    } else {
                        // Fallback: execute without permission guard
                        if (!action.number.isNullOrBlank()) {
                            dialDirect(action.number)
                        } else {
                            launchDialer(action.contactName)
                        }
                    }
                }
                is AssistantIntent.Action.UpdateSetting -> {
                    updateSetting(action.settingType, action.value)
                }
                is AssistantIntent.Action.BookRapido -> {
                    Log.i(TAG, "BookRapido action: dest='${action.destination}', vehicle='${action.vehicle}'")
                    launchRapido(action.destination, action.vehicle)
                }
                is AssistantIntent.Action.OpenCamera -> {
                    Log.i(TAG, "OpenCamera action: mode='${action.mode}'")
                    launchCamera(action)
                }
                is AssistantIntent.Action.SearchAmazon -> {
                    Log.i(TAG, "SearchAmazon action: query='${action.query}'")
                    launchAmazon(action)
                }
                is AssistantIntent.Action.TriggerSOS -> {
                    Log.w(TAG, "🚨 SOS TRIGGERED! Reason: ${action.reason}")
                    dialEmergency112()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to execute action=$action", e)
            ActionResult.Failure
        }
    }

    private fun dialEmergency112(): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:112")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "Dialing Emergency 112 with ACTION_CALL")
            ActionResult.SuccessWithFeedback("Emergency services ko call laga raha hoon. Shant rahiye.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dial 112 directly, trying ACTION_DIAL fallback", e)
            try {
                val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(dial)
                ActionResult.SuccessWithFeedback("Dialer mein 112 khol raha hoon. Jaldi call button dabayein.")
            } catch (dialEx: Exception) {
                Log.e(TAG, "Fatal: Emergency dial failed", dialEx)
                ActionResult.Failure
            }
        }
    }
    
    /**
     * Execute media playback (extracted for permission-guarded execution).
     */
    private suspend fun executePlayMedia(action: AssistantIntent.Action.PlayMedia): ActionResult {
        return when (action.mediaType) {
            AssistantIntent.MediaType.MUSIC -> {
                val decision = mediaRouter.routeMusic(action.query)
                handleRouteDecision(decision, action.videoFilter, action.creatorName)
            }
            AssistantIntent.MediaType.VIDEO -> {
                val decision = mediaRouter.routeVideo(action.query)
                handleRouteDecision(decision, action.videoFilter, action.creatorName)
            }
        }
    }
    
    /**
     * Helper: Get MediaControlService instance if available.
     */
    private fun getMediaControlService(): com.assistant.services.media.MediaControlService? {
        return try {
            com.assistant.services.media.MediaControlService.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "MediaControlService not available", e)
            null
        }
    }
    
    /**
     * Helper: Send play command via MediaControlService.
     * Returns true if command was sent successfully.
     */
    private fun sendMediaPlayCommand(): Boolean {
        val service = getMediaControlService()
        return if (service != null) {
            try {
                service.play()
                Log.i(TAG, "Sent play command via MediaControlService")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send play command", e)
                false
            }
        } else {
            false
        }
    }
    
    /**
     * Helper: Send pause command via MediaControlService.
     */
    private fun sendMediaPauseCommand(): Boolean {
        val service = getMediaControlService()
        return if (service != null) {
            try {
                service.pause()
                Log.i(TAG, "Sent pause command via MediaControlService")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send pause command", e)
                false
            }
        } else {
            false
        }
    }
    
    /**
     * Helper: Check if media is currently playing.
     */
    private fun isMediaPlaying(): Boolean {
        val service = getMediaControlService()
        return service?.isPlaying() ?: false
    }

    private fun handleRouteDecision(decision: MediaRouter.RouteDecision, videoFilter: String? = null, creatorName: String? = null): ActionResult {
        return when (decision) {
            is MediaRouter.RouteDecision.PlayUnknown -> {
                Log.i(TAG, "Routing to ${decision.app} for query: ${decision.query}")
                when (decision.app) {
                    com.assistant.domain.PlaybackApp.SPOTIFY -> launchSpotify(decision.query)
                    com.assistant.domain.PlaybackApp.YOUTUBE -> launchYouTube(decision.query, videoFilter, creatorName)
                    else -> Log.w(TAG, "Unknown app for routing: ${decision.app}")
                }
                ActionResult.SuccessWithFeedback("Haan, chala raha hoon.")
            }
            MediaRouter.RouteDecision.AskUser -> {
                 Log.i(TAG, "Asking user for preference.")
                 ActionResult.AskUser("Do you want to play this on Spotify or YouTube?")
            }
        }
    }

    private fun launchAlarm(timeText: String, label: String?): ActionResult {
        val parsed = parseTime(timeText)
        if (parsed == null) {
            return ActionResult.AskUser("Alarm kis time ke liye lagau?")
        }

        val (hour, minute) = parsed
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "Assistant Alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                ActionResult.SuccessWithFeedback("Alarm set kar diya $hour:${minute.toString().padStart(2, '0')} ke liye.")
            } else {
                ActionResult.AskUser("Alarm app nahi mili, koi aur tareeka?")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm", e)
            ActionResult.Failure
        }
    }

    private fun launchDialer(rawContact: String): ActionResult {
        val contact = rawContact.trim()
        if (contact.isBlank()) {
            return ActionResult.AskUser("Kisse call karna hai?")
        }

        // 1. Check Permission for "Smart" features
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
             val (count, number) = resolveContact(contact)
             if (count > 1) {
                 return ActionResult.AskUser("Mere paas $count $contact hain. Kaunsa wala? Pura naam bataiye.")
             } else if (count == 1 && number != null) {
                 // Exact match found! Call directly.
                 return dialDirect(number)
             }
             // Count 0 -> Fallback to search
        }

        // 2. Fallback (No permission OR Not found in existing contacts)
        return try {
            val isNumber = contact.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }
            
            val intent = if (isNumber) {
                // Try ACTION_CALL first (will throw SecurityException if permission denied)
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$contact"))
            } else {
                 Intent(Intent.ACTION_VIEW).apply {
                    data = android.provider.ContactsContract.Contacts.CONTENT_URI
                 }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                context.startActivity(intent)
                ActionResult.SuccessWithFeedback("Call laga raha hoon.")
            } catch (e: SecurityException) {
                // Permission denied - ExecutionGuard should have handled this
                Log.w(TAG, "CALL_PHONE permission denied", e)
                
                // Fallback to ACTION_DIAL
                try {
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$contact"))
                    dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(dialIntent)
                    ActionResult.SuccessWithFeedback("Dialer khol raha hoon.")
                } catch (dialEx: Exception) {
                    Log.e(TAG, "Failed to open dialer", dialEx)
                    ActionResult.AskUser("Sorry, call app nahi mili.")
                }
            } catch (e: Exception) {
                 try {
                     val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$contact"))
                     dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                     context.startActivity(dialIntent)
                     ActionResult.SuccessWithFeedback("Dialer khol raha hoon.")
                 } catch (e2: Exception) {
                      ActionResult.AskUser("Sorry, call app nahi mili.")
                 }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch dialer", e)
            ActionResult.Failure
        }
    }
    
    
    private fun dialDirect(number: String): ActionResult {
        return try {
            // Try ACTION_CALL first (auto-dial if permission is granted)
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "Calling $number with ACTION_CALL")
            ActionResult.SuccessWithFeedback("Call laga raha hoon.")
        } catch(e: SecurityException) {
            // Permission not granted - ExecutionGuard should have handled this
            Log.w(TAG, "CALL_PHONE permission denied", e)
            
            // Fallback to ACTION_DIAL for now
            try {
                val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(dial)
                ActionResult.SuccessWithFeedback("Dialer khol raha hoon.")
            } catch (dialEx: Exception) {
                Log.e(TAG, "Failed to open dialer", dialEx)
                ActionResult.Failure
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dial", e)
            ActionResult.Failure
        }
    }

    private fun resolveContact(name: String): Pair<Int, String?> {
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")
        
        var count = 0
        var number: String? = null
        
        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                count = cursor.count
                if (cursor.moveToFirst()) {
                    number = cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact query failed", e)
        }
        return count to number
    }

    private fun parseTime(text: String): Pair<Int, Int>? {
        val normalized = text.lowercase().trim()
        val match = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(normalized) ?: return null
        val hourRaw = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val meridiem = match.groupValues[3]

        var hour = hourRaw
        if (meridiem.equals("pm", ignoreCase = true) && hour < 12) {
            hour += 12
        } else if (meridiem.equals("am", ignoreCase = true) && hour == 12) {
            hour = 0
        }

        if (hour in 0..23 && minute in 0..59) {
            return hour to minute
        }
        return null
    }


    /**
 * Launch Spotify with a search query and auto-play.
 * 
 * Uses Android's standard MEDIA_PLAY_FROM_SEARCH intent which tells
 * Spotify to search and automatically play the first result.
 */
private fun launchSpotify(query: String) {
    Log.i(TAG, "Launching Spotify with query: $query")
    
    // Check and log accessibility service status
    val isAutoClickEnabled = com.assistant.utils.AccessibilityHelper.isSpotifyServiceEnabled(context)
    Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
    Log.i(TAG, "║  🎵 LAUNCHING SPOTIFY                                  ║")
    Log.i(TAG, "╠════════════════════════════════════════════════════════╣")
    Log.i(TAG, "║  Query: '$query'")
    Log.i(TAG, "║  Auto-Click Service: ${if (isAutoClickEnabled) "✅ ENABLED" else "❌ DISABLED"}")
    if (!isAutoClickEnabled) {
        Log.w(TAG, "║  ⚠️ Auto-click will NOT work!")
        Log.w(TAG, "║  Enable in: Settings → Accessibility → Spotify Auto-Click")
    }
    Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
    
    var spotifyLaunched = false
    
    try {
        // Method 1: MEDIA_PLAY_FROM_SEARCH (Standard Android auto-play)
        val playIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(SearchManager.QUERY, query)
            putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        context.startActivity(playIntent)
        Log.i(TAG, "Launched Spotify with MEDIA_PLAY_FROM_SEARCH: $query")
        spotifyLaunched = true
        
    } catch (e: Exception) {
        Log.w(TAG, "MEDIA_PLAY_FROM_SEARCH failed, trying spotify: URI", e)
        
        // Method 2: Try Spotify search URI
        try {
            val spotifyUri = "spotify:search:$query"
            val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUri)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage("com.spotify.music")
            }
            
            context.startActivity(spotifyIntent)
            Log.i(TAG, "Launched Spotify with URI: $spotifyUri")
            spotifyLaunched = true
            
        } catch (uriEx: Exception) {
            Log.w(TAG, "Spotify URI failed, trying web URL", uriEx)
            
            // Method 3: Try web URL
            try {
                val webUrl = "https://open.spotify.com/search/${Uri.encode(query)}"
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    setPackage("com.spotify.music")
                }
                
                context.startActivity(webIntent)
                Log.i(TAG, "Launched Spotify with web URL")
                spotifyLaunched = true
                
            } catch (webEx: Exception) {
                Log.w(TAG, "Web URL failed, trying search intent", webEx)
                
                // Method 4: Try search intent
                try {
                    val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        setPackage("com.spotify.music")
                        putExtra(SearchManager.QUERY, query)
                    }
                    
                    context.startActivity(searchIntent)
                    Log.i(TAG, "Launched Spotify with search intent")
                    spotifyLaunched = true
                    
                } catch (searchEx: Exception) {
                    Log.w(TAG, "Search intent failed, opening Spotify app", searchEx)
                    
                    // Method 5: Just open Spotify app
                    try {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(launchIntent)
                            Log.i(TAG, "Opened Spotify app")
                            spotifyLaunched = true
                        } else {
                            Log.e(TAG, "Spotify not installed")
                        }
                    } catch (launchEx: Exception) {
                        Log.e(TAG, "Failed to launch Spotify at all", launchEx)
                    }
                }
            }
        }
    }
    
    // If Spotify launched, send play broadcast after delay to trigger playback
    if (spotifyLaunched) {
        Log.i(TAG, "Spotify launched successfully, sending play trigger")
        
        // Wait for search results to load, then trigger play
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                // Method 1: Try Spotify-specific play broadcast
                val playBroadcast = Intent("com.spotify.mobile.android.ui.widget.PLAY")
                playBroadcast.setPackage("com.spotify.music")
                context.sendBroadcast(playBroadcast)
                Log.i(TAG, "Sent Spotify play broadcast")
            } catch (e: Exception) {
                Log.w(TAG, "Spotify play broadcast failed, trying media button", e)
                
                // Method 2: Try standard media play button
                try {
                    val mediaIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                    mediaIntent.setPackage("com.spotify.music")
                    val keyEvent = android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    )
                    mediaIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
                    context.sendBroadcast(mediaIntent)
                    Log.i(TAG, "Sent media play button")
                } catch (mediaEx: Exception) {
                    Log.e(TAG, "Failed to trigger playback", mediaEx)
                }
            }
        }, 2000) // 2 second delay to let search results load
    }
}

    /**
     * Launch YouTube with a search query and smart video selection.
     * 
     * Sets the pendingSelection context for YouTubeAutoClickService
     * which will apply the specified filter to select the best video.
     * 
     * @param query Search query
     * @param videoFilter "any", "most_views", or "specific_creator"
     * @param creatorName Channel name (required for specific_creator filter)
     */
    private fun launchYouTube(query: String, videoFilter: String? = null, creatorName: String? = null) {
        Log.i(TAG, "Launching YouTube with query: $query")
        
        // Set pending selection context for accessibility service
        val filter = when (videoFilter?.lowercase()) {
            "most_views" -> com.assistant.services.youtube.VideoFilter.MOST_VIEWS
            "specific_creator" -> com.assistant.services.youtube.VideoFilter.SPECIFIC_CREATOR
            else -> com.assistant.services.youtube.VideoFilter.PLAY_ANY
        }
        
        com.assistant.services.accessibility.YouTubeAutoClickService.pendingSelection = 
            com.assistant.services.youtube.YouTubeSelectionContext(
                searchQuery = query,
                filter = filter,
                creatorName = creatorName
            )
        
        // Check and log accessibility service status
        val isAutoClickEnabled = com.assistant.utils.AccessibilityHelper.isYouTubeServiceEnabled(context)
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  🎬 LAUNCHING YOUTUBE (Smart Selection)                ║")
        Log.i(TAG, "╠════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║  Query: '$query'")
        Log.i(TAG, "║  Filter: $filter")
        if (creatorName != null) {
            Log.i(TAG, "║  Creator: $creatorName")
        }
        Log.i(TAG, "║  Auto-Click Service: ${if (isAutoClickEnabled) "✅ ENABLED" else "❌ DISABLED"}")
        if (!isAutoClickEnabled) {
            Log.w(TAG, "║  ⚠️ Smart selection will NOT work!")
            Log.w(TAG, "║  Enable in: Settings → Accessibility → YouTube Auto-Click")
        }
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        var youtubeLaunched = false
        
        // Method 1: vnd.youtube:// search URI (MOST RELIABLE for search)
        try {
            val youtubeUri = "vnd.youtube://results?search_query=${Uri.encode(query)}"
            val youtubeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUri)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage("com.google.android.youtube")
            }
            
            context.startActivity(youtubeIntent)
            Log.i(TAG, "✅ Launched YouTube with vnd.youtube URI: $youtubeUri")
            youtubeLaunched = true
            
        } catch (e: Exception) {
            Log.w(TAG, "vnd.youtube URI failed, trying web URL", e)
            
            // Method 2: Try HTTPS web URL (YouTube app handles this)
            try {
                val webUrl = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    setPackage("com.google.android.youtube")
                }
                
                context.startActivity(webIntent)
                Log.i(TAG, "✅ Launched YouTube with HTTPS URL: $webUrl")
                youtubeLaunched = true
                
            } catch (webEx: Exception) {
                Log.w(TAG, "Web URL failed, trying MEDIA_PLAY_FROM_SEARCH", webEx)
                
                // Method 3: MEDIA_PLAY_FROM_SEARCH (less reliable on some devices)
                try {
                    val playIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        setPackage("com.google.android.youtube")
                        putExtra(SearchManager.QUERY, query)
                        putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    
                    context.startActivity(playIntent)
                    Log.i(TAG, "✅ Launched YouTube with MEDIA_PLAY_FROM_SEARCH: $query")
                    youtubeLaunched = true
                    
                } catch (playEx: Exception) {
                    Log.w(TAG, "MEDIA_PLAY_FROM_SEARCH failed, trying search intent", playEx)
                    
                    // Method 4: Try search intent
                    try {
                        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            setPackage("com.google.android.youtube")
                            putExtra("query", query)
                            putExtra(SearchManager.QUERY, query)
                        }
                        
                        context.startActivity(searchIntent)
                        Log.i(TAG, "✅ Launched YouTube with ACTION_SEARCH: $query")
                        youtubeLaunched = true
                        
                    } catch (searchEx: Exception) {
                        Log.w(TAG, "Search intent failed, opening YouTube app directly", searchEx)
                        
                        // Method 5: Just open YouTube app (fallback - no search)
                        try {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                            if (launchIntent != null) {
                                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(launchIntent)
                                Log.w(TAG, "⚠️ Opened YouTube app WITHOUT search (all search methods failed)")
                                youtubeLaunched = true
                            } else {
                                Log.e(TAG, "YouTube not installed")
                            }
                        } catch (launchEx: Exception) {
                            Log.e(TAG, "Failed to launch YouTube at all", launchEx)
                        }
                    }
                }
            }
        }
        
        if (youtubeLaunched) {
            Log.i(TAG, "YouTube launched successfully with query: '$query'")
        }
    }


    private suspend fun updateSetting(type: String, value: String): ActionResult {
        // MediaRouter has repository, but it's private. 
        // Ideally we should inject repo directly, but for now we can rely on MediaRouter's access if we expose it 
        // OR better: Just make a quick new instance or use the one we have if we refactor.
        // Refactoring ActionExecutor to take Repository is safer.
        val repo = com.assistant.data.PreferencesRepository(context) 
        val app = when(value) {
            "SPOTIFY" -> com.assistant.domain.PlaybackApp.SPOTIFY
            "YOUTUBE" -> com.assistant.domain.PlaybackApp.YOUTUBE
             else -> com.assistant.domain.PlaybackApp.ASK
        }
        
        return when(type) {
            "MUSIC" -> {
                repo.updateMusicPlaybackApp(app)
                ActionResult.SuccessWithFeedback("Music default set to ${app.name}.")
            }
            "VIDEO" -> {
                repo.updateVideoPlaybackApp(app)
                ActionResult.SuccessWithFeedback("Video default set to ${app.name}.")
            }
            else -> ActionResult.Failure
        }
    }
    
    /**
     * Launch Rapido app with booking context for automation.
     * 
     * The RapidoAutoBookService will pick up the pending booking and automate the UI.
     */
    private fun launchRapido(destination: String?, vehicle: String?): ActionResult {
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  🚗 LAUNCHING RAPIDO                                   ║")
        Log.i(TAG, "╠════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║  Destination: ${destination ?: "NOT SET"}")
        Log.i(TAG, "║  Vehicle: ${vehicle ?: "NOT SET"}")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        // Check if accessibility service is enabled
        val isServiceEnabled = com.assistant.utils.AccessibilityHelper.isRapidoServiceEnabled(context)
        if (!isServiceEnabled) {
            Log.w(TAG, "⚠️ Rapido Auto-Book accessibility service is NOT enabled!")
            Log.w(TAG, "Opening accessibility settings...")
            
            // Open accessibility settings
            com.assistant.utils.AccessibilityHelper.openAccessibilitySettings(context)
            
            return ActionResult.AskUser(
                "Rapido automation ke liye accessibility permission chahiye. " +
                "Settings mein jaake 'Rapido Auto-Book' service enable karo, " +
                "phir dobara try karo."
            )
        }
        
        // Create booking context for the accessibility service
        val bookingContext = com.assistant.services.rapido.RapidoBookingContext(
            destination = destination,
            vehicle = vehicle,
            originalDestination = destination
        )
        
        // Set pending booking for the accessibility service to pick up
        com.assistant.services.accessibility.RapidoAutoBookService.pendingBooking = bookingContext
        Log.i(TAG, "Set pending booking: $bookingContext")
        
        // Try to launch Rapido app
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.rapido.passenger")
            if (launchIntent != null) {
                launchIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                     android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                context.startActivity(launchIntent)
                Log.i(TAG, "✅ Rapido app launched successfully")
                
                return ActionResult.SuccessWithFeedback(
                    "Rapido khol raha hoon, thoda ruko booking ho jayegi."
                )
            } else {
                Log.e(TAG, "❌ Rapido app not installed!")
                // Clear pending booking since we can't proceed
                com.assistant.services.accessibility.RapidoAutoBookService.pendingBooking = null
                
                return ActionResult.AskUser(
                    "Yaar Rapido app installed nahi lag raha. " +
                    "Pehle Rapido install karo Play Store se."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Rapido", e)
            // Clear pending booking since we can't proceed
            com.assistant.services.accessibility.RapidoAutoBookService.pendingBooking = null
            
            return ActionResult.SuccessWithFeedback(
                "Rapido app launch karne mein problem aa gayi."
            )
        }
    }
    
    /**
     * Launch camera with specified mode.
     * 
     * Modes:
     * - photo: Default photo capture
     * - video: Video recording
     * - selfie: Front camera (hint only, not guaranteed)
     * - qr: Opens camera (no native QR intent)
     */
    private suspend fun launchCamera(action: AssistantIntent.Action.OpenCamera): ActionResult {
        val mode = when (action.mode?.lowercase()?.trim()) {
            "video", "record", "recording" -> "video"
            "selfie", "front" -> "selfie"
            "qr", "scan" -> "qr"
            else -> "photo"
        }
        
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  📷 LAUNCHING CAMERA                                   ║")
        Log.i(TAG, "╠════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║  Mode: $mode")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        // Check camera permission
        if (!com.assistant.services.permissions.PermissionManager.hasPermission(
                context, 
                com.assistant.services.permissions.PermissionType.CAMERA
            )) {
            Log.w(TAG, "⚠️ Camera permission not granted")
            return ActionResult.AskUser(
                "Camera ke liye permission chahiye yaar. Settings mein jaake allow kar do."
            )
        }
        
        return try {
            // Create appropriate intent based on mode
            val cameraIntent = when (mode) {
                "video" -> android.content.Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE)
                else -> android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            }
            
            // Front camera hints for selfie mode (NOT guaranteed on all devices)
            if (mode == "selfie") {
                cameraIntent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                cameraIntent.putExtra("android.intent.extras.CAMERA_FACING", 1)
                cameraIntent.putExtra("camerafacing", 1) // Samsung specific
            }
            
            cameraIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            
            // Check if camera app exists
            if (cameraIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(cameraIntent)
                Log.i(TAG, "✅ Camera launched: mode=$mode")
                
                // Generate acknowledgement
                val ack = action.acknowledgement ?: "Camera ready hai."
                
                ActionResult.SuccessWithFeedback(ack)
            } else {
                Log.e(TAG, "❌ No camera app found")
                ActionResult.AskUser("Yaar camera app nahi mili device pe.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera launch failed", e)
            ActionResult.SuccessWithFeedback(
                "Yaar camera open nahi ho pa raha, manually try kar lo."
            )
        }
    }
    
    /**
     * Launch Amazon with a search query.
     * 
     * Uses web URL with package targeting - Amazon app intercepts if installed.
     * Falls back to browser if Amazon app is not available.
     */
    private fun launchAmazon(action: AssistantIntent.Action.SearchAmazon): ActionResult {
        val query = action.query
        
        Log.i(TAG, "╔════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  🛒 LAUNCHING AMAZON                                   ║")
        Log.i(TAG, "╠════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║  Query: ${query ?: "NOT SET"}")
        Log.i(TAG, "╚════════════════════════════════════════════════════════╝")
        
        if (query.isNullOrBlank()) {
            return ActionResult.AskUser("Amazon pe kya search karna hai yaar?")
        }
        
        // Construct Amazon India search URL
        val url = "https://www.amazon.in/s?k=${android.net.Uri.encode(query)}"
        
        // Method 1: Try Amazon India app with web URL
        try {
            val amazonIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                setPackage("in.amazon.mShop.android.shopping")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            context.startActivity(amazonIntent)
            Log.i(TAG, "✅ Launched Amazon India app with query: '$query'")
            return ActionResult.Success
            
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "Amazon India app not found, trying US app...")
            
            // Method 2: Try Amazon US app (fallback)
            try {
                val usAmazonIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                    setPackage("com.amazon.mShop.android.shopping")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                
                context.startActivity(usAmazonIntent)
                Log.i(TAG, "✅ Launched Amazon US app with query: '$query'")
                return ActionResult.Success
                
            } catch (usEx: android.content.ActivityNotFoundException) {
                Log.w(TAG, "Amazon app not installed, opening browser...")
                
                // Method 3: Open in browser (fallback)
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(browserIntent)
                    Log.i(TAG, "✅ Opened Amazon in browser with query: '$query'")
                    return ActionResult.SuccessWithFeedback("Amazon app nahi mila, browser mein khol diya.")
                    
                } catch (browserEx: Exception) {
                    Log.e(TAG, "❌ Failed to open Amazon in browser", browserEx)
                    return ActionResult.AskUser("Yaar Amazon nahi khul raha, manually try kar lo.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to launch Amazon", e)
            return ActionResult.AskUser("Amazon launch mein problem aa gayi yaar.")
        }
    }
}



