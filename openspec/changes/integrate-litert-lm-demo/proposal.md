## Why

EdgeAIDemo currently contains an unrelated nested ViewPager gesture sample and an outdated Android build chain. It does not exercise Google AI Edge. The project needs a focused, reproducible validation surface for loading a LiteRT-LM model on Android, running private offline text generation, observing hardware fallback, and collecting runtime diagnostics.

## What Changes

- Replace the gesture sample with an AI validation console containing Model, Chat, and Diagnostics pages.
- Upgrade the Android toolchain for Kotlin 2.2 bytecode and pin LiteRT-LM 0.15.0.
- Reduce the project to an application module plus an isolated `edge-ai` runtime library.
- Package a locally license-approved Gemma `.litertlm` as an uncompressed APK asset, transactionally install it into app-private storage on first launch, and retain SAF replacement.
- Automatically initialize the selected or freshly installed model at application startup using GPU-first CPU fallback.
- Detect Android emulators before startup initialization and select CPU with an explicit diagnostic reason instead of reporting a late GPU/OpenCL sampler error after the user's first prompt.
- Declare LiteRT-LM's optional Android GPU system-library dependencies in the merged manifest so compatible physical devices may access them.
- Override LiteRT-LM 0.15.0's published coroutine metadata and resolve `kotlinx-coroutines-core` and `kotlinx-coroutines-android` 1.11.0 consistently. This prevents its completion callback from calling a missing `SendChannel.close$default` bridge after an otherwise successful response.
- Add serialized Engine and Conversation lifecycle management with GPU-first initialization and CPU fallback.
- Stream multi-turn responses, support cancellation/reset/unload, and surface LiteRT-LM benchmark data.
- Document model licensing, physical-device validation, offline behavior, and known limitations.

## Capabilities

### New Capabilities

- `model-management`: Import, validate, persist, replace, and delete local LiteRT-LM models safely.
- `on-device-chat`: Initialize LiteRT-LM, select a backend, stream multi-turn text, cancel work, and release native resources.
- `runtime-diagnostics`: Report device, SDK, model, backend fallback, and inference benchmark information.

### Modified Capabilities

None. This repository has no existing OpenSpec capabilities.

## Impact

- Replaces the current UI and removes the `base` and `biz-app` modules and their unrelated dependencies.
- Adds the `edge-ai` module and `com.google.ai.edge.litertlm:litertlm-android:0.15.0`.
- Pins all Android coroutine runtime and test artifacts to 1.11.0 as a LiteRT-LM binary-compatibility workaround; the 0.15.0 POM incorrectly advertises 1.9.0.
- Raises minSdk to 31, limits packaged native ABI to ARM64, and upgrades Gradle, AGP, Kotlin, and AndroidX test tooling.
- Adds an approximately 584 MB local model build input and a correspondingly large APK; the binary remains outside version control but is included in locally produced APKs after license acceptance.
- Requires roughly twice the model size in free device storage during first-launch installation because both the APK asset and private inference copy coexist.
- Affects `LiteRtLmRuntime`, backend-selection unit coverage, and the app manifest; no model, user prompt, or cloud-facing behavior changes.
