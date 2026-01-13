# Assets Directory

## Wake Word Model File

Place your custom Porcupine wake word model file here:

- **File name**: `hey-aman_en_android_v4_0_0.ppn`
- **Location**: `app/src/main/assets/hey-aman_en_android_v4_0_0.ppn`

### How to add the .ppn file:

1. Copy your trained `.ppn` file (e.g., `hey-aman_en_android_v4_0_0.ppn`)
2. Place it in this directory: `app/src/main/assets/`
3. The file will be automatically included in the APK during build

### Current Configuration:

The app is configured to use the custom wake word model for "hey aman".
The model file is loaded from assets in `WakeWordManager.kt`.

**Note**: The wake word model is trained for "hey aman". Even if users enter a different wake word during onboarding, the app will use the "hey aman" model for detection.

