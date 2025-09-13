# Repository Guidelines

## Project Structure & Module Organization
- Android app in Kotlin. Main module: `:app`; auxiliary submodule: `:dep:futopay:android:app` (initialize submodules).
- Source: `app/src/main/java/org/futo/voiceinput/` with `res/`, `assets/`, `ml/`, and native code in `app/src/main/cpp/`.
- Flavors (dimension `version`): `dev`, `devSameId`, `playStore`, `standalone`, `fDroid`.
  - `playStore` uses Play Billing; `standalone`/`fDroid` use PayPal; `dev` adds dev-only code and both billings; update checking varies by flavor.
- Tests: unit in `app/src/test/java`; instrumented in `app/src/androidTest/java`.
- Prebuilt AARs in `libs/` (e.g., `vad-release.aar`, `pocketfft-release.aar`).

## Build, Test, and Development Commands
- Initialize deps: `git submodule update --init --recursive`.
- Build (POSIX/Windows): `./gradlew assembleStandaloneRelease` / `gradlew.bat assembleStandaloneRelease`.
- Dev builds: `assembleDevDebug`, install to device: `installDevDebug`.
- Tests: unit `test` (or `testDevDebugUnitTest`), instrumented `connectedAndroidTest` (requires emulator/device).
- Lint/clean: `lint`, `clean`.

## Coding Style & Naming Conventions
- Kotlin style: 4-space indentation, idiomatic Kotlin APIs, null-safety, immutability where practical.
- Files/classes: PascalCase; functions/variables: lowerCamelCase; constants: UPPER_SNAKE_CASE.
- Package stays `org.futo.voiceinput`.
- Resources: lower_snake_case (e.g., `ic_mic_24`, `activity_recognize.xml`, `string/voice_input_*`).

## Testing Guidelines
- Frameworks: JUnit 4 for unit tests, AndroidJUnitRunner + Espresso/Compose test APIs for instrumented tests.
- Name tests `*Test.kt`. Keep unit tests pure/deterministic (no device I/O). Use instrumented tests for UI/integration.
- Run: `./gradlew test` and `./gradlew connectedAndroidTest` (select the desired variant as needed).

## Commit & Pull Request Guidelines
- Commits: clear, imperative subject (<= 72 chars), optional body with rationale. Reference issues (`Fixes #123`).
- PRs: include summary, linked issues, affected flavor(s), screenshots for UI changes, and test notes.

## Security & Configuration Tips
- Do not commit secrets. Optional files: `keystore.properties` (signing) and `crashreporting.properties` (ACRA) are local-only.
- Native build uses CMake; Gradle manages NDK/CMake. Avoid altering flavor wiring or application IDs without discussion.
