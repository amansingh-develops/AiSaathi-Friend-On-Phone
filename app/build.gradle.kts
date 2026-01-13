import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.assistant"
    compileSdk = 34

    // Load API keys from local.properties (gitignored)
    val localPropertiesFile = rootProject.file("local.properties")
    val localProps = run {
        if (localPropertiesFile.exists()) {
            val props = Properties()
            localPropertiesFile.inputStream().use { props.load(it) }
            props
        } else {
            Properties()
        }
    }
    val picovoiceAccessKey = localProps.getProperty("PICOVOICE_ACCESS_KEY") ?: ""
    val geminiApiKey = localProps.getProperty("GEMINI_API_KEY") ?: ""
    val gptGoApiKey = localProps.getProperty("GPT_GO_API_KEY") ?: ""
    val groqApiKey = localProps.getProperty("GROQ_API_KEY") ?: ""
    val openRouterApiKey = localProps.getProperty("OPENROUTER_API_KEY") ?: ""
    val openAiApiKey = localProps.getProperty("OPENAI_API_KEY") ?: ""
    val elevenLabsApiKey = localProps.getProperty("ELEVENLABS_API_KEY") ?: ""
    val elevenLabsVoiceIdFemale = localProps.getProperty("ELEVENLABS_VOICE_ID_FEMALE") ?: ""
    val elevenLabsVoiceIdMale = localProps.getProperty("ELEVENLABS_VOICE_ID_MALE") ?: ""

    defaultConfig {
        applicationId = "com.assistant"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // BuildConfig field for Picovoice Access Key (secure, not in code)
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"$picovoiceAccessKey\"")
        // BuildConfig fields for AI backends (secure, not in code)
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "GPT_GO_API_KEY", "\"$gptGoApiKey\"")
        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"$openRouterApiKey\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
        // ElevenLabs (STT + TTS)
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"$elevenLabsApiKey\"")
        buildConfigField("String", "ELEVENLABS_VOICE_ID_FEMALE", "\"$elevenLabsVoiceIdFemale\"")
        buildConfigField("String", "ELEVENLABS_VOICE_ID_MALE", "\"$elevenLabsVoiceIdMale\"")
        
        // 16 KB page size compatibility for Android 15+
        // Ensure native libraries support 16 KB page sizes
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true // Enable BuildConfig to access API keys
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        
        // Fix for 16 KB page size compatibility (Android 15+)
        // Ensures native libraries are aligned at 16 KB boundaries
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Compose BOM - manages all Compose library versions
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    
    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // Networking (ElevenLabs STT/TTS)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Picovoice Porcupine for wake word detection
    implementation("ai.picovoice:porcupine-android:4.0.0")

    // Google Generative AI SDK (Gemini)
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // LocalBroadcastManager for permission requests
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Vosk Offline STT
    implementation("com.alphacephei:vosk-android:0.3.47")
}

