package com.assistant.services.permissions

import android.Manifest
import android.os.Build

/**
 * Comprehensive enum defining all supported permissions.
 * Maps to Android manifest permission strings and provides metadata.
 */
enum class PermissionType(
    val manifestPermission: String,
    val displayName: String,
    val featureDescription: String,
    val group: PermissionGroup,
    val minSdkVersion: Int = 1
) {
    // Microphone
    RECORD_AUDIO(
        Manifest.permission.RECORD_AUDIO,
        "Microphone",
        "Voice commands and wake word detection",
        PermissionGroup.AUDIO
    ),
    
    // Phone
    CALL_PHONE(
        Manifest.permission.CALL_PHONE,
        "Phone Calls",
        "Make phone calls on your behalf",
        PermissionGroup.PHONE
    ),
    READ_PHONE_STATE(
        Manifest.permission.READ_PHONE_STATE,
        "Phone State",
        "Detect call status and manage calls",
        PermissionGroup.PHONE
    ),
    READ_CALL_LOG(
        Manifest.permission.READ_CALL_LOG,
        "Call History",
        "Access recent and missed calls",
        PermissionGroup.PHONE
    ),
    
    // Contacts
    READ_CONTACTS(
        Manifest.permission.READ_CONTACTS,
        "Contacts",
        "Find and call your contacts by name",
        PermissionGroup.CONTACTS
    ),
    
    // SMS
    SEND_SMS(
        Manifest.permission.SEND_SMS,
        "Send SMS",
        "Send text messages on your behalf",
        PermissionGroup.SMS
    ),
    READ_SMS(
        Manifest.permission.READ_SMS,
        "Read SMS",
        "Read your text messages",
        PermissionGroup.SMS
    ),
    
    // Calendar
    READ_CALENDAR(
        Manifest.permission.READ_CALENDAR,
        "Read Calendar",
        "View your schedule and events",
        PermissionGroup.CALENDAR
    ),
    WRITE_CALENDAR(
        Manifest.permission.WRITE_CALENDAR,
        "Modify Calendar",
        "Create alarms, reminders, and events",
        PermissionGroup.CALENDAR
    ),
    
    // Camera
    CAMERA(
        Manifest.permission.CAMERA,
        "Camera",
        "Take photos and videos",
        PermissionGroup.CAMERA
    ),
    
    // Media (Photos & Videos)
    READ_MEDIA_IMAGES(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
        "Photos",
        "Access your photos and images",
        PermissionGroup.MEDIA,
        minSdkVersion = Build.VERSION_CODES.TIRAMISU
    ),
    READ_MEDIA_VIDEO(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
        "Videos",
        "Access your videos",
        PermissionGroup.MEDIA,
        minSdkVersion = Build.VERSION_CODES.TIRAMISU
    ),
    
    // Bluetooth (API 31+)
    BLUETOOTH_CONNECT(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        },
        "Bluetooth Connection",
        "Connect to earbuds and other devices",
        PermissionGroup.BLUETOOTH,
        minSdkVersion = Build.VERSION_CODES.S
    ),
    BLUETOOTH_SCAN(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.BLUETOOTH
        },
        "Bluetooth Scanning",
        "Find nearby Bluetooth devices",
        PermissionGroup.BLUETOOTH,
        minSdkVersion = Build.VERSION_CODES.S
    ),
    
    // Audio Settings
    MODIFY_AUDIO_SETTINGS(
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        "Audio Control",
        "Control volume and audio playback",
        PermissionGroup.AUDIO
    ),
    
    // Notifications
    POST_NOTIFICATIONS(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            "" // Not required before API 33
        },
        "Notifications",
        "Show notifications and alerts",
        PermissionGroup.NOTIFICATIONS,
        minSdkVersion = Build.VERSION_CODES.TIRAMISU
    ),
    
    // Special: Notification Listener (requires Settings redirect)
    NOTIFICATION_LISTENER(
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "Notification Access",
        "Control media playback (Spotify, YouTube, etc.)",
        PermissionGroup.SPECIAL
    );
    
    /**
     * Check if this permission is applicable for the current Android version.
     */
    fun isApplicable(): Boolean {
        return Build.VERSION.SDK_INT >= minSdkVersion && manifestPermission.isNotEmpty()
    }
}

/**
 * Permission groups for organizational purposes.
 */
enum class PermissionGroup {
    AUDIO,
    PHONE,
    CONTACTS,
    SMS,
    CALENDAR,
    CAMERA,
    MEDIA,
    BLUETOOTH,
    NOTIFICATIONS,
    SPECIAL // Requires special handling (e.g., Settings redirect)
}
