# Wake Word Detection System

## Overview
This package implements continuous wake word detection using Picovoice Porcupine. The system runs as a ForegroundService and works completely offline.

## Files

### WakeWordService.kt
ForegroundService that runs continuously and manages the wake word detection lifecycle.

**Key Features:**
- Runs as foreground service (required for continuous audio capture)
- Lifecycle-aware (proper cleanup on destroy)
- Battery-efficient (only processes when listening)
- Works offline (no network required)

**Usage:**
```kotlin
// Start service
val intent = Intent(context, WakeWordService::class.java).apply {
    action = WakeWordService.ACTION_START
    putExtra(WakeWordService.EXTRA_WAKE_WORD, "Hey Assistant")
}
context.startForegroundService(intent)

// Stop service
val stopIntent = Intent(context, WakeWordService::class.java).apply {
    action = WakeWordService.ACTION_STOP
}
context.startService(stopIntent)
```

### WakeWordManager.kt
Encapsulates Porcupine logic and audio processing.

**Key Features:**
- Handles AudioRecord initialization and management
- Processes audio frames through Porcupine
- Low-latency audio capture (16kHz, 16-bit, mono)
- Continues working while other apps play audio

**Architecture:**
- Service calls WakeWordManager (not Porcupine directly)
- Audio processing runs on IO dispatcher
- Error handling prevents service crashes

## AndroidManifest Requirements

The following have been added to `AndroidManifest.xml`:

### Permissions:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

### Service Declaration:
```xml
<service
    android:name=".services.wakeword.WakeWordService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="microphone" />
```

## Runtime Permissions

You must request `RECORD_AUDIO` permission at runtime before starting the service:

```kotlin
if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
    != PackageManager.PERMISSION_GRANTED) {
    ActivityCompat.requestPermissions(activity, 
        arrayOf(Manifest.permission.RECORD_AUDIO), 
        REQUEST_RECORD_AUDIO)
}
```

## Porcupine Integration Notes

**Current Implementation:**
- The code structure is ready for Porcupine integration
- Placeholder comments indicate where actual Porcupine initialization should occur
- Actual initialization requires:
  1. Picovoice Access Key (from Picovoice Console)
  2. Wake word model file (.ppn) - can be downloaded or included in assets

**To Complete Integration:**
1. Add Porcupine dependency to `build.gradle.kts`:
   ```kotlin
   implementation("ai.picovoice:porcupine-android:3.0.0")
   ```

2. Initialize Porcupine in `WakeWordManager.initialize()`:
   ```kotlin
   porcupine = Porcupine.Builder()
       .setAccessKey("YOUR_ACCESS_KEY")
       .setKeywordPaths(arrayOf("path/to/keyword.ppn"))
       .build(context)
   ```

3. Process audio frames:
   ```kotlin
   val detected = porcupine.process(frameBuffer)
   if (detected) {
       onWakeWordDetected()
   }
   ```

## Service Lifecycle

1. **onCreate()**: Initialize notification channel and start foreground
2. **onStartCommand()**: Start wake word detection
3. **onDestroy()**: Clean up resources (AudioRecord, Porcupine)

## Audio Configuration

- **Sample Rate**: 16kHz (Porcupine requirement)
- **Format**: 16-bit PCM, mono
- **Frame Size**: 512 samples
- **Source**: VOICE_RECOGNITION (optimized for voice, continues during music)

## Battery Optimization

- Uses efficient audio capture (VOICE_RECOGNITION source)
- Processes on background thread (IO dispatcher)
- No unnecessary wake locks
- Low-priority notification (minimal visual impact)

## Testing

To test the service:
1. Grant RECORD_AUDIO permission
2. Start the service with an Intent
3. Check Logcat for "Wake word detected" messages
4. Verify Toast appears on detection (if enabled)

