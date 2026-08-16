## 1. Specification and build foundation

- [x] 1.1 Initialize OpenSpec for Codex and strictly validate the complete change artifacts
- [x] 1.2 Upgrade Gradle, AGP, Kotlin, JDK target, AndroidX tests, SDK baseline, and ARM64 packaging
- [x] 1.3 Replace the old module graph with `app` and `edge-ai` and remove unrelated dependencies

## 2. Model management

- [x] 2.1 Define model descriptors, import events, and a model store interface
- [x] 2.2 Implement transactional SAF import, SHA-256 naming, progress, metadata persistence, and stale partial cleanup
- [x] 2.3 Implement safe model replacement and deletion coordinated with runtime unload
- [x] 2.4 Implement first-launch transactional installation from an uncompressed bundled asset
- [x] 2.5 Download the gated Gemma model after user license acceptance and place it in the local asset path (584,417,280 bytes; SHA-256 `1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`)

## 3. LiteRT-LM runtime

- [x] 3.1 Define SDK-neutral runtime state, generation events, backends, diagnostics, and runtime interface
- [x] 3.2 Implement serialized Engine/Conversation initialization with GPU-first CPU fallback
- [x] 3.3 Implement streaming generation, single-request enforcement, cancellation, conversation reset, benchmark mapping, and deterministic close
- [x] 3.4 Automatically initialize the existing or freshly installed model during ViewModel startup

## 4. Validation console

- [x] 4.1 Create the activity-scoped ViewModel and shared UI state
- [x] 4.2 Build Model, Chat, and Diagnostics tabs with XML Views and ViewPager2
- [x] 4.3 Add message rendering, import progress, backend controls, error recovery, and benchmark display

## 5. Verification and documentation

- [x] 5.1 Add unit tests for model storage, runtime coordination, and ViewModel state behavior
- [x] 5.2 Run OpenSpec strict validation, unit tests, lint, debug APK, and AndroidTest APK builds
- [x] 5.3 Verify applicationId, ARM64-only native packaging, ignored local/model artifacts, and clean error paths
- [x] 5.4 Document model licensing/download, offline workflow, physical-device acceptance, and known limitations
- [x] 5.5 Perform Android 12+ ARM64 physical-device inference acceptance before archiving the OpenSpec change (Xiaomi 2206123SC, Android 15 / API 35, `arm64-v8a`: GPU initialization and five completed turns; process remained alive and the PID-filtered log contained no `NoSuchMethodError`)
- [x] 5.6 Build and inspect the model-bundled APK after the gated asset is provisioned (621,168,273 bytes; verified stored model asset and `arm64-v8a`-only native code)

## 6. Android Studio emulator GPU compatibility

- [x] 6.1 Add documented optional GPU native-library manifest declarations and preflight Android emulator signatures to select CPU before Engine initialization
- [x] 6.2 Add required unit regression coverage for emulator CPU selection and preserve physical-device GPU-first selection (`GPU-EMU-01`)
- [x] 6.3 Run required build/manifest validation (`GPU-EMU-02`)
- [ ] 6.4 Run emulator prompt, log, and visual acceptance when an Android Studio ARM64 emulator is connected (`GPU-EMU-03`)

## 7. LiteRT-LM completion-callback ABI compatibility

- [x] 7.1 Pin the application, runtime module, and coroutine test dependencies to `kotlinx-coroutines` 1.11.0, the minimum runtime ABI required by the published LiteRT-LM 0.15.0 AAR
- [x] 7.2 Add dependency-resolution verification and run unit/build validation to prove that `kotlinx-coroutines-core` and `kotlinx-coroutines-android` resolve only to 1.11.0 (`ABI-01`)
- [x] 7.3 On an Android 12+ ARM64 physical device or `emulator-5554`, validate a completed response leaves the process alive without `SendChannel.close$default` (`ABI-02`); this was passed on the connected physical device
