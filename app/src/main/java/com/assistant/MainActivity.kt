package com.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import com.assistant.data.repository.UserProfileRepository
import com.assistant.domain.model.UserProfile
import com.assistant.services.UserIdentityManager
import com.assistant.services.wakeword.WakeWordService
import com.assistant.domain.model.VoiceGender
import kotlinx.coroutines.launch
import com.assistant.ui.onboarding.OnboardingScreen
import com.assistant.ui.permissions.PermissionScreen
import com.assistant.ui.screen.HomeScreen
import com.assistant.ui.settings.SettingsScreen
import com.assistant.ui.settings.VoicePersona
import com.assistant.ui.theme.AssistantTheme
import com.assistant.ui.welcome.WelcomeScreen
import com.assistant.data.PreferencesRepository
import com.assistant.domain.UserPreferences
import androidx.compose.runtime.collectAsState
import com.assistant.ui.theme.AssistantTheme
import com.assistant.ui.welcome.WelcomeScreen

class MainActivity : ComponentActivity() {
    private var permissionReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Register Activity for permission dialog support
        com.assistant.services.context.ActivityContextProvider.registerActivity(this)
        
        // Register permission broadcast receiver - allows background services to request permissions via UI
        permissionReceiver = com.assistant.services.permissions.PermissionRequestBroadcaster.registerReceiver(this) { permission, _ ->
            android.util.Log.d("MainActivity", "Received permission broadcast for: $permission")
            requestPermissions(arrayOf(permission), 200)
        }
        
        setContent {
            AssistantTheme {
                App()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Unregister Activity
        com.assistant.services.context.ActivityContextProvider.unregisterActivity()
        
        // Unregister permission receiver
        permissionReceiver?.let {
            com.assistant.services.permissions.PermissionRequestBroadcaster.unregisterReceiver(this, it)
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val profileRepository = remember { UserProfileRepository(context) }
    val identityManager = remember { UserIdentityManager(context) }
    val preferencesRepository = remember { PreferencesRepository(context) }
    val userPreferences by preferencesRepository.preferencesFlow.collectAsState(initial = UserPreferences())
    
    // App state management
    var hasCompletedOnboarding by remember {
        mutableStateOf(profileRepository.hasUserProfile())
    }
    
    var showWelcome by remember { mutableStateOf(false) }
    var showHome by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    
    var savedProfile by remember { mutableStateOf<UserProfile?>(null) }
    var selectedVoicePersona by rememberSaveable(userPreferences.voiceGender) {
        mutableStateOf(if (userPreferences.voiceGender == VoiceGender.MALE) VoicePersona.Masculine else VoicePersona.Feminine)
    }
    
    // Check permissions status - recalculate when needed
    var hasGrantedPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // Load saved profile on app start (for returning users)
    // This ensures home screen is shown immediately for returning users
    // Also ensures service is stopped if permissions are not granted (prevent crashes)
    LaunchedEffect(Unit) {
        // First, re-check permissions to ensure state is correct
        val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val contactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val currentPermissionStatus = audioGranted && contactsGranted
        
        hasGrantedPermissions = currentPermissionStatus
        
        // If permissions are not granted, stop any running service to prevent crashes
        // We only STRICTLY NEED Audio for the service to run without crashing immediately.
        // But let's be safe.
        if (!audioGranted) {
            try {
                val stopIntent = Intent(context, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_STOP
                }
                context.stopService(stopIntent)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error stopping service", e)
            }
        }
        
        if (hasCompletedOnboarding && savedProfile == null) {
            val profile = profileRepository.getUserProfile()
            if (profile != null) {
                savedProfile = profile
                showHome = true // Set home to true immediately
                showWelcome = false // Don't show welcome again
            }
        }
    }
    
    // Track if service has been started to prevent multiple starts
    var serviceStarted by remember { mutableStateOf(false) }
    
    // Show permission screen after home screen appears (with 2 second delay)
    // This ensures user sees the home screen with bulb first
    // Works for both new users (after welcome) and returning users
    LaunchedEffect(showHome, savedProfile) {
        if (showHome && savedProfile != null && !hasGrantedPermissions && !showPermissions) {
            kotlinx.coroutines.delay(2000) // Wait 2 seconds after home screen appears
            // Re-check permissions after delay (might have been granted externally)
            val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val contactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            
            if (!audioGranted || !contactsGranted) {
                showPermissions = true
            } else {
                // Update state if permissions were granted
                hasGrantedPermissions = true
            }
        }
    }
    
    // Start wake word service ONLY when permissions are granted and home is shown
    // This prevents crashes from starting service without permissions
    LaunchedEffect(showHome, hasGrantedPermissions, showPermissions) {
        // Double-check permissions before starting service
        val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // We can technically run service without contacts, but let's encourage having both
        val contactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        
        val currentPermissionStatus = audioGranted && contactsGranted
        
        // Update permission state
        if (currentPermissionStatus != hasGrantedPermissions) {
            hasGrantedPermissions = currentPermissionStatus
        }
        
        // Only start service if ALL conditions are met:
        // 1. Home screen is shown
        // 2. Profile is loaded
        // 3. Permissions are granted (double-checked)
        // 4. Permission screen is not showing
        // 5. Service hasn't been started yet
        if (showHome && 
            savedProfile != null && 
            currentPermissionStatus && 
            !showPermissions && 
            !serviceStarted) {
            // Only start service if permissions are granted and permission screen is not showing
            try {
                val wakeWord = savedProfile?.wakeWord ?: "hey aman"
                startWakeWordService(context, wakeWord)
                serviceStarted = true
                android.util.Log.d("MainActivity", "Wake word service started successfully")
            } catch (e: SecurityException) {
                // Permission denied - don't crash, just log and stop service
                android.util.Log.e("MainActivity", "Permission denied for wake word service", e)
                hasGrantedPermissions = false
                try {
                    val stopIntent = Intent(context, WakeWordService::class.java).apply {
                        action = WakeWordService.ACTION_STOP
                    }
                    context.stopService(stopIntent)
                } catch (stopEx: Exception) {
                    android.util.Log.e("MainActivity", "Error stopping service after permission denial", stopEx)
                }
            } catch (e: Exception) {
                // Log error but don't crash
                android.util.Log.e("MainActivity", "Failed to start wake word service", e)
            }
        } else if (showHome && !currentPermissionStatus && serviceStarted) {
            // If permissions were revoked, stop the service
            try {
                val stopIntent = Intent(context, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_STOP
                }
                context.stopService(stopIntent)
                serviceStarted = false
                android.util.Log.d("MainActivity", "Service stopped due to missing permissions")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error stopping service", e)
            }
        }
    }
    
    // Error Monitoring
    val errorState by com.assistant.services.error.GlobalErrorManager.errorFlow.collectAsState()
    
    if (errorState != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { com.assistant.services.error.GlobalErrorManager.clearError() },
            title = { Text(text = "System Alert") },
            text = { Text(text = errorState!!.message) },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { com.assistant.services.error.GlobalErrorManager.clearError() }
                ) {
                    Text("OK")
                }
            },
            containerColor = Color(0xFF2A2A2F),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFE8E8E8)
        )
    }

    // Navigation flow: onboarding -> welcome -> home (with permission overlay)
    when {
        !hasCompletedOnboarding -> {
            // Step 1: Onboarding (first time users)
            val userId = remember { identityManager.getUserId() }
            
            OnboardingScreen(
                userId = userId,
                onOnboardingComplete = { profile: UserProfile ->
                    profileRepository.saveUserProfile(profile)
                    savedProfile = profile
                    hasCompletedOnboarding = true
                    showWelcome = true
                    showHome = false // Ensure home is not shown yet
                }
            )
        }
        showWelcome && savedProfile != null -> {
            // Step 2: Welcome animation
            WelcomeScreen(
                userProfile = savedProfile!!,
                onAnimationComplete = {
                    showWelcome = false
                    showHome = true
                }
            )
        }
        showHome && savedProfile != null -> {
            // Step 3: Home screen (FINAL DESTINATION - stays here permanently)
            // Once showHome is true and profile exists, we NEVER leave this screen
            Box(modifier = Modifier.fillMaxSize()) {
                // Home screen (base layer - always visible, never hidden)
                HomeScreen(
                    userProfile = savedProfile!!,
                    onOpenSettings = { showSettings = true }
                )

                // Settings screen (overlay layer - opened from the top-right icon)
                if (showSettings) {
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    SettingsScreen(
                        userProfile = savedProfile!!,
                        userPreferences = userPreferences,
                        selectedVoicePersona = selectedVoicePersona,
                        onBack = { showSettings = false },
                        onSave = { updatedProfile, updatedPrefs, updatedPersona ->
                            // 1. Update Profile (including Language, Wake Word)
                            savedProfile = updatedProfile
                            profileRepository.saveUserProfile(updatedProfile)
                            
                            // 2. Update Persisted Preferences (Music, Video, Learning)
                            scope.launch {
                                preferencesRepository.updateMusicPlaybackApp(updatedPrefs.musicPlaybackApp)
                                preferencesRepository.updateVideoPlaybackApp(updatedPrefs.videoPlaybackApp)
                                preferencesRepository.updateAllowAutoLearning(updatedPrefs.allowAutoLearning)
                                preferencesRepository.updateConfirmationStyle(updatedPrefs.confirmationStyle)
                                
                                val gender = if (updatedPersona == VoicePersona.Masculine) VoiceGender.MALE else VoiceGender.FEMALE
                                preferencesRepository.updateVoiceGender(gender)
                            }

                            // 3. Update Local State (Persona)
                            selectedVoicePersona = updatedPersona

                            // 4. Notify Service of changes (Language, Gender via Intent)
                            try {
                                val prefsIntent = Intent(context, WakeWordService::class.java).apply {
                                    action = WakeWordService.ACTION_UPDATE_PREFS
                                    putExtra(WakeWordService.EXTRA_PREFERRED_LANGUAGE, updatedProfile.preferredLanguage)
                                    putExtra(WakeWordService.EXTRA_WAKE_WORD, updatedProfile.wakeWord)
                                    putExtra(WakeWordService.EXTRA_ASSISTANT_NAME, updatedProfile.assistantName)
                                    putExtra(WakeWordService.EXTRA_PREFERRED_NAME, updatedProfile.preferredName)
                                    
                                    // Pass Gender to service
                                    val genderStr = if (updatedPersona == VoicePersona.Masculine) "MALE" else "FEMALE"
                                    putExtra(WakeWordService.EXTRA_VOICE_GENDER, genderStr)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(prefsIntent)
                                } else {
                                    context.startService(prefsIntent)
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("MainActivity", "Failed to push language update to service", e)
                            }
                        }
                    )
                }
                
                // Permission screen (overlay layer - appears on top, doesn't hide home)
                if (showPermissions) {
                    PermissionScreen(
                        onAllPermissionsGranted = {
                            showPermissions = false
                            // Re-check permissions and update state
                            val currentPermissionStatus = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            hasGrantedPermissions = currentPermissionStatus
                            // Service will start automatically via LaunchedEffect when permissions are granted
                        }
                    )
                }
            }
        }
        hasCompletedOnboarding && savedProfile == null -> {
            // Loading state: We have completed onboarding but profile is still loading
            // This should only show briefly while LaunchedEffect loads the profile
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A1F)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Loading...",
                    color = Color(0xFFE8E8E8)
                )
            }
        }
        else -> {
            // This should never happen, but show loading as fallback
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A1F)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Loading...",
                    color = Color(0xFFE8E8E8)
                )
            }
        }
    }
}

/**
 * Start the wake word detection service.
 */
private fun startWakeWordService(context: android.content.Context, wakeWord: String) {
    val intent = Intent(context, WakeWordService::class.java).apply {
        action = WakeWordService.ACTION_START
        putExtra(WakeWordService.EXTRA_WAKE_WORD, wakeWord)
        // Optional: if/when you have VoiceGender in memory, pass it here as:
        // putExtra(WakeWordService.EXTRA_VOICE_GENDER, voiceGender.name)
    }
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}
