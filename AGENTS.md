# Repository Guidelines

## Project Structure & Module Organization
- Main Android app lives in `:app`; initialize `:dep:futopay:android:app` with `git submodule update --init --recursive`.
- Kotlin sources in `app/src/main/java/org/futo/voiceinput/`; UI assets under `app/src/main/res/`, native code in `app/src/main/cpp/`, ML models in `app/src/main/ml/`.
- Flavor dimension `version` defines `dev`, `devSameId`, `playStore`, `standalone`, and `fDroid`. Billing integrations differ per flavor; review `build.gradle` before toggling.
- Tests reside in `app/src/test/java` (unit) and `app/src/androidTest/java` (instrumented).
 - IME entrypoint: `VoiceInputMethodService`; Recognize intent entrypoint: `RecognizeActivity`.

## Build, Test, and Development Commands
- `./gradlew assembleStandaloneRelease`: create production-ready APK with PayPal billing.
- `./gradlew assembleDevDebug` / `./gradlew installDevDebug`: fast iteration build and install on a connected device.
- `./gradlew test`: run JVM unit tests (use `./gradlew testDevDebugUnitTest` for flavor-specific checks).
- `./gradlew connectedAndroidTest`: execute instrumented tests; requires emulator or USB device.
- `./gradlew lint`: static analysis; run before PRs to catch regressions.
 - Initialize submodules for PayPal billing: `git submodule update --init --recursive`.

## Coding Style & Naming Conventions
- Kotlin with 4-space indent, prefer immutable vals, idiomatic null-handling. Enable IDE formatting with ktlint-compatible settings.
- Classes and files use PascalCase; members are lowerCamelCase; constants UPPER_SNAKE_CASE.
- Resources follow lower_snake_case (e.g., `string/voice_input_error_api_key`); keep package `org.futo.voiceinput`.

## Testing Guidelines
- JUnit4 for unit tests; AndroidJUnitRunner with Espresso/Compose for UI. Name files `*Test.kt`.
- Cover new logic paths, especially provider selection and VAD timing. Mock Soniox services in unit tests; reserve network calls for instrumented suites.
- Run unit tests locally before pushing; include emulator runs when touching IME flows.

## Architecture Overview
- Default STT provider is on-device Whisper via `AudioRecognizer`. Soniox cloud supports async REST and realtime WebSocket; select via `STT_PROVIDER` setting.
 - Realtime Soniox streams partial tokens into IME composing text, final results replace partials; async mode behaves like local mode without realtime text.
 - Settings keys: `STT_PROVIDER`, `SONIOX_MODE`, `SONIOX_API_KEY`, `LANGUAGE_TOGGLES`, `PERSONAL_DICTIONARY`, `ENABLE_SOUND`, `VERBOSE_PROGRESS`.
- Realtime mode streams partial tokens directly into IME using `VoiceInputMethodService`; intent callers display an overlay before committing final text.
- VAD thresholds (`VAD_SPEECH_MS`, etc.) remain user configurable; ensure new features respect existing defaults.

## Commit & Pull Request Guidelines
- Commits: imperative subject <= 72 chars, optional body capturing rationale and references (e.g., `Fixes #123`). Group related changes.
- Pull requests: summarize behavior, call out affected flavors, attach screenshots for UI tweaks, and list executed commands/tests. Highlight configuration steps when Soniox keys or billing modes change.
- Async Soniox transcripts now keep the IME focused, reusing the last input connection and retrying insertion automatically.
