# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FUTO Voice Input is an Android application that provides speech-to-text functionality through third-party keyboards and generic speech-to-text APIs. It integrates with various speech recognition providers including local Whisper models and remote services like Soniox.

## Build System & Commands

### Core Build Commands
```bash
# Build release APK for standalone distribution
./gradlew assembleStandaloneRelease

# Build specific flavor variants
./gradlew assembleDevRelease          # Development build with all features
./gradlew assemblePlayStoreRelease    # Play Store build without auto-update
./gradlew assembleFDroidRelease       # F-Droid build without Google services
./gradlew assembleDevSameIdRelease    # Dev build with same app ID as release

# Clean and rebuild
./gradlew clean assembleStandaloneRelease
```

### Build Flavors
The project uses Android product flavors for different distribution channels:
- **dev/devSameId**: Development builds with all payment methods and update checking
- **playStore**: Play Store builds with only Play Store billing, no auto-update
- **standalone**: Standalone builds with PayPal billing and auto-update
- **fDroid**: F-Droid builds with PayPal billing, no auto-update, no Google services

### Testing
```bash
# Run unit tests
./gradlew test

# Run instrumentation tests
./gradlew connectedAndroidTest
```

## Architecture Overview

### Core Components

**AudioRecognizer (Abstract Base Class)**
- Handles audio recording via AudioRecord API
- Implements Voice Activity Detection (VAD) using WebRTC GMM model
- Manages audio focus and permissions
- Provides template for different recognition providers
- Location: `app/src/main/java/org/futo/voiceinput/AudioRecognizer.kt`

**VoiceInputMethodService**
- Android InputMethodService implementation for keyboard integration
- Manages Compose UI lifecycle and input method lifecycle
- Handles text insertion via InputConnection API
- Location: `app/src/main/java/org/futo/voiceinput/VoiceInputMethodService.kt`

**RecognizerView (Abstract)**
- Base class for recognition UI components
- Manages recognition state machine and UI updates
- Handles result processing and error states
 - Drives provider selection: Whisper local, Soniox async, Soniox realtime

### Speech Recognition Providers

**Local Whisper Models**
- Uses whisper.cpp via JNI for on-device recognition
- GGML quantized models stored in `app/src/main/ml/`
- C++ implementation in `app/src/main/cpp/` with CMake build system
- Supports multiple languages and model sizes

**Soniox Provider**
- Remote speech recognition service integration
- Both async and real-time recognition modes
- Located in `app/src/main/java/org/futo/voiceinput/providers/soniox/`
- Classes: `SonioxAsyncRecognizer`, `SonioxRealtimeRecognizer`, `RealtimeSttClient`
 - Realtime uses `wss://stt-rt.soniox.com/transcribe-websocket` via OkHttp; partial tokens stream into IME composing text, `onRealtimeFinalResult` consolidates the final transcript

### Settings & Configuration

**DataStore-based Settings**
- Uses Android DataStore for preferences persistence
- Centralized settings management in `app/src/main/java/org/futo/voiceinput/settings/Settings.kt`
- Coroutine-based async settings operations with blocking fallbacks
- Type-safe settings keys with defaults
 - Relevant keys: `STT_PROVIDER`, `SONIOX_MODE` ("async"|"realtime"), `SONIOX_API_KEY`, `LANGUAGE_TOGGLES`, `PERSONAL_DICTIONARY`, `ENABLE_SOUND`, `VERBOSE_PROGRESS`

**Theme System**
- Jetpack Compose Material 3 theming
- Dynamic color support (Android 12+)
- Multiple theme presets in `app/src/main/java/org/futo/voiceinput/theme/presets/`
- Theme selection UI with live preview

### Native Code Integration

**whisper.cpp Integration**
- GGML-based Whisper implementation
- JNI wrapper in `voiceinput.cpp` and `jni_common.cpp`
- Optimized for mobile ARM processors with NEON instructions
- CMake build system with Android NDK

**Audio Processing Libraries**
- WebRTC VAD for voice activity detection (prebuilt AAR in libs/)
- PocketFFT for audio feature extraction (prebuilt AAR in libs/)

## Payment & Licensing

**Multi-platform Payment Support**
- Play Store billing for Google Play distribution
- PayPal integration for direct sales via FutoPay module
- Conditional compilation based on build flavor
- Billing logic in `app/src/main/java/org/futo/voiceinput/payments/`

## Key Development Patterns

### Async Operations
- Heavy use of Kotlin coroutines throughout the app
- `withContext(Dispatchers.Default)` for background processing
- `withContext(Dispatchers.Main)` for UI updates
- Proper lifecycle-aware coroutine scoping

### Compose UI Architecture
- Single-activity architecture with Compose navigation
- Lifecycle-aware ViewModels where appropriate
- Custom Compose components for recognition UI
- Material 3 design system implementation

### Error Handling
- ACRA crash reporting (configurable via build config)
- Graceful degradation for permission errors
- Out-of-memory handling for model loading
- Network error handling for remote providers

### Model Management
- Lazy loading of machine learning models
- Model migration system for updates
- Download manager for obtaining models
- Memory management with proper model cleanup

## File Structure Notes

**Source Sets by Flavor:**
- `src/main/` - Common code for all flavors  
- `src/dev/` - Development-specific code
- `src/playStoreBilling/` - Google Play billing implementation
- `src/payPalBilling/` - PayPal billing implementation
- `src/withUpdateChecking/` - Auto-update functionality
- `src/withoutUpdateChecking/` - Builds without auto-update

**Critical Configuration Files:**
- `app/build.gradle` - Complex multi-flavor build configuration
- `app/src/main/cpp/CMakeLists.txt` - Native code build setup
- `libs/` - Prebuilt AAR libraries for audio processing
 - `app/src/main/AndroidManifest.xml` - IME service, Recognize activity, accessibility insertion service

## Development Workflow

1. Changes should maintain compatibility across all build flavors
2. Test both local Whisper and remote provider functionality
3. Consider memory implications when modifying model loading
4. Ensure proper lifecycle management in UI components
5. Test keyboard integration via IME APIs
6. Validate permissions handling especially for microphone access

## External Dependencies

**Key Libraries:**
- Jetpack Compose (UI framework)
- Kotlin Coroutines (async operations)
- DataStore (settings persistence)
- OkHttp (network operations for remote providers)
- ACRA (crash reporting)
- WebRTC VAD (voice activity detection)
- Material 3 (design system)

**Build Tools:**
- Android Gradle Plugin (from project)
- Kotlin 2.1.0 with Compose compiler plugin
- CMake 3.22.1 for native builds
- Android NDK for C++ compilation

## Quick Commands

```bash
# Core builds
./gradlew assembleStandaloneRelease
./gradlew assemblePlayStoreRelease
./gradlew assembleFDroidRelease
./gradlew assembleDevRelease

# Tests & lint
./gradlew test
./gradlew testDevDebugUnitTest
./gradlew connectedAndroidTest
./gradlew lint
```