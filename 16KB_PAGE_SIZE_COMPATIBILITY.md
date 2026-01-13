# 16 KB Page Size Compatibility

## Issue
The APK contains native libraries with LOAD segments not aligned at 16 KB boundaries, which is required for Android 15+ devices.

**Affected Library**: `lib/x86_64/libpv_porcupine.so` (from Picovoice Porcupine SDK)

## Solutions Applied

### 1. Build Configuration (`app/build.gradle.kts`)
- Added `jniLibs { useLegacyPackaging = false }` to use modern packaging format
- Added NDK ABI filters to ensure compatible architectures

### 2. Gradle Properties (`gradle.properties`)
- Added `android.bundle.enableUncompressedNativeLibs=false` for better alignment

## Important Note

**The Porcupine library is a third-party prebuilt library.** The native `.so` files are provided by Picovoice and cannot be rebuilt by us.

**This warning will appear until Picovoice releases an updated version with 16 KB alignment.** This is expected and does not prevent the app from running on current devices. The warning only affects future Android 15+ devices with 16 KB page sizes. 

### Next Steps:

1. **Check for Porcupine Updates**: 
   - Visit https://github.com/Picovoice/porcupine
   - Check if version 3.0.1 or later includes 16 KB page size support
   - Update dependency if available:
     ```kotlin
     implementation("ai.picovoice:porcupine-android:3.0.1") // or newer
     ```

2. **Contact Picovoice Support**:
   - Request 16 KB page size compatible build
   - Reference: Android 15+ requirement for Google Play (Nov 2025)

3. **Temporary Workaround** (if needed):
   - For testing, you can build APKs without x86_64 architecture:
   ```kotlin
   ndk {
       abiFilters += listOf("armeabi-v7a", "arm64-v8a") // Remove x86, x86_64
   }
   ```
   - Note: This will prevent installation on x86_64 emulators/devices

4. **Monitor for Updates**:
   - Check Picovoice release notes regularly
   - The library vendor needs to rebuild with 16 KB alignment

## Testing

To test 16 KB compatibility:
1. Use Android Studio's 16 KB page size emulator
2. Build and install the APK
3. Check for alignment warnings during build

## References
- [Android 16 KB Page Size Guide](https://developer.android.com/guide/practices/page-sizes)
- [Picovoice Porcupine Android](https://github.com/Picovoice/porcupine/tree/master/binding/android)

