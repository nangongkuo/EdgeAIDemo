## Context

The existing repository packages its only activity from a library module and relies on a dependency-aggregator module containing unrelated networking, media, and utility libraries. The UI validates nested gesture arbitration rather than AI. LiteRT-LM 0.15.0 publishes Kotlin 2.2 metadata and native libraries, so the build, module boundary, storage flow, threading model, and native lifecycle all need to change together.

The validation target is Android 12+ ARM64 hardware. Models are license-controlled files hundreds of megabytes in size. The approved model is a local, Git-ignored build input included in the APK, while tokens and model binaries remain outside the repository. Once the bundled asset is installed into app-private storage, inference must operate without network access.

## Goals / Non-Goals

**Goals:**

- Provide a small Android application that proves LiteRT-LM model import, initialization, offline streaming conversation, cancellation, reset, unload, GPU fallback, and benchmark collection.
- Keep LiteRT-LM types behind a stable project-owned API so UI tests can use a fake runtime.
- Make model replacement transactional and native resource ownership explicit.
- Make a fresh installation usable without manual model selection by installing the bundled asset and initializing it automatically.
- Produce repeatable OpenSpec and Gradle validation commands.

**Non-Goals:**

- In-app model downloading, Hugging Face authentication, or model conversion. License acceptance and the build-time model download remain external preparation steps.
- Multimodal inputs, tool calling, RAG, NPU integration, cloud inference, or production telemetry.
- Cross-device performance pass/fail thresholds; the demo records device-specific measurements only.

## Decisions

### Two-module architecture

The project will contain `app` and `edge-ai`. `app` owns Android screens, adapters, and an activity-scoped ViewModel. `edge-ai` owns model persistence and all LiteRT-LM interaction. The old `base` and `biz-app` modules will be removed. This is preferred over adding LiteRT-LM to the current dependency aggregator because it prevents native/runtime dependencies from leaking into unrelated UI code and eliminates obsolete libraries.

### Pinned compatible build chain

The build will use AGP 8.10.1, Gradle 8.11.1, Kotlin 2.2.21, JDK 17, LiteRT-LM 0.15.0, and `kotlinx-coroutines` 1.11.0. minSdk becomes 31 and packaging is limited to `arm64-v8a`. compileSdk and targetSdk remain 34. Pinning versions is preferred over `latest.release` so validation remains reproducible.

### LiteRT-LM coroutine ABI workaround

**Root-cause confidence: Confirmed.** The `litertlm-android:0.15.0` AAR bytecode invokes `SendChannel.close$default(SendChannel, Throwable, int, Object)` as an interface-static method from the `Conversation.sendMessageAsync()` completion callback. Its published POM nevertheless declares `kotlinx-coroutines-android:1.9.0`. In 1.9.0, that synthetic default-argument bridge instead resides in `SendChannel$DefaultImpls`; Android therefore throws `NoSuchMethodError` on the LiteRT-LM callback thread after a successful inference completes. The captured `crash_004.txt` stack is exactly this call path.

The application shall explicitly resolve `kotlinx-coroutines-core`, `kotlinx-coroutines-android`, and test artifacts to 1.11.0, the minimum version whose `SendChannel` interface supplies the bridge required by the AAR. The version is configured once and used in both modules; Gradle dependency verification will assert that no lower core or Android artifact is selected. This is a consumer-side workaround for a LiteRT-LM publication defect, not a change to generation, UI, or backend behavior.

**Rejected alternatives:** retaining the POM-declared 1.9.0 is known to crash on completion; 1.10.x does not provide the required interface-static bridge; catching the exception in `generate()` cannot work because the exception originates from LiteRT-LM's independent JNI callback thread. Waiting for a future SDK publication would leave the demo deterministically crashable.

### Transactional app-private model storage

The app accepts either the packaged model asset or a document URI from the Storage Access Framework and copies it into `filesDir/models`. It writes a `.partial` file while calculating SHA-256, then atomically renames the complete file to `<sha256>.litertlm`. Metadata is persisted only after the rename succeeds. The previously selected model remains intact until replacement completes. This approach is required because LiteRT-LM needs a filesystem path and cannot infer directly from an `AssetManager` stream or arbitrary provider URI.

### Bundled model and automatic startup

The accepted Gemma file is placed at `app/src/main/assets/models/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm` and packaged with `noCompress` so `AssetManager` exposes the original length and packaging does not waste time recompressing model data. Its expected size and SHA-256 are pinned in both the build verification task and `BundledModelSpec`; the runtime deletes a mismatched extraction before it can become the selected model. On ViewModel creation, an existing valid private model wins; otherwise the bundled asset is transactionally copied through the same importer. The resulting model is then initialized automatically with GPU preference and one CPU fallback. A missing asset or initialization failure remains recoverable through the Model tab.

The model is ignored by Git because the upstream repository is gated, the binary exceeds normal GitHub file limits, and redistribution obligations must be handled by the distributor. Reproducible local builds must provision this external input before assembling the bundled APK.

### Serialized native runtime

All Engine and Conversation operations will run on one owned executor-backed CoroutineDispatcher. Import I/O runs on `Dispatchers.IO`. A Mutex/state guard will reject concurrent initialization or generation. This avoids overlapping JNI calls and provides deterministic close ordering.

### GPU-first with one CPU fallback

GPU initialization is attempted when selected. If it throws, the partial Conversation and Engine are closed, the error text is retained as a fallback reason, and CPU initialization is attempted exactly once. Explicit CPU selection does not probe GPU. The effective backend is always visible to the UI.

### Android emulator GPU preflight

**Root-cause confidence: Confirmed.** On the Android Studio ARM64 emulator (`ro.kernel.qemu=1`, hardware `ranchu`, API 37), `Engine.initialize()` accepts `Backend.GPU()` and initializes the model through WebGPU. LiteRT-LM defers sampler creation until `Conversation.sendMessageAsync()`. The captured runtime log then reports that both `libLiteRtTopKWebGpuSampler.so` and `libLiteRtTopKOpenClSampler.so` are unavailable, followed by `Can not find OpenCL library on this device`. The existing fallback function is invoked only around `initializeBackend`, while `generate()` converts later failures directly into `GenerationEvent.Failed`; therefore the user sees an unrecoverable-looking message even though CPU inference is supported.

The corrected startup path is:

```text
preferred GPU
  -> Android Studio emulator signature (generic/ranchu/goldfish)
  -> skip GPU before Engine creation
  -> initialize CPU once
  -> Ready(preferred=GPU, effective=CPU, fallbackReason=emulator/OpenCL)
```

Physical Android devices keep the current GPU-first attempt and initialization-time CPU fallback. The manifest will add LiteRT-LM's documented optional `libvndksupport.so` and `libOpenCL.so` declarations; these declarations permit access on compatible hardware but do not fabricate OpenCL on an emulator. The CPU preflight is therefore the compatibility solution for the reported emulator scenario.

**Rejected alternatives:** packaging a synthetic OpenCL library is unsafe and cannot provide a real emulator GPU driver; treating a successful `Engine.initialize()` call as a GPU capability test is disproven by the captured lazy sampler failure; retrying a failed multi-turn GPU prompt on a fresh CPU `Conversation` would silently lose previous conversation state.

**Required regression matrix:**

| ID | Layer | Required | Preconditions / steps | Expected result | Evidence |
|---|---|---:|---|---|---|
| GPU-EMU-01 | unit | yes | Run `./gradlew :edge-ai:testDebugUnitTest` | Emulator signature selects CPU without attempting GPU and retains a fallback reason; physical signature remains GPU-first | JUnit result |
| GPU-EMU-02 | build | yes | Run `./gradlew :app:assembleDebug` and inspect merged APK manifest | Optional native-library declarations are present | Gradle / `aapt` output |
| GPU-EMU-03 | device + visual | yes | Install the approved debug APK on `emulator-5554`, launch with the bundled model, submit one prompt | Status shows effective CPU and the prompt produces non-empty streamed text; no `Can not find OpenCL library` error | captured screenshot, UI dump, filtered logcat |
| ABI-01 | dependency graph | yes | Run Gradle dependency insight for `kotlinx-coroutines-core` and `kotlinx-coroutines-android` on `debugRuntimeClasspath` | Both resolve exclusively to 1.11.0 | Gradle output |
| ABI-02 | device + visual | yes | On an Android 12+ ARM64 physical device or `emulator-5554`, submit a prompt and wait for LiteRT-LM completion | A completed response leaves the process alive; no `NoSuchMethodError` for `SendChannel.close$default` is emitted | filtered logcat and screenshot/UI dump |

The Android 12+ ARM64 physical-device acceptance remains separately required before archive; emulator evidence does not substitute for it.

### Runtime state and lifecycle

An activity-scoped ViewModel owns one runtime and one model store. Rotation retains the ViewModel. The runtime permits one active generation, uses LiteRT-LM Flow streaming, calls `cancelProcess()` on cancellation, recreates only Conversation for reset, and closes Conversation before Engine for unload or final cleanup.

### Stable SDK-neutral API

`EdgeAiRuntime`, `RuntimeState`, `GenerationEvent`, `RuntimeDiagnostics`, and `ModelDescriptor` contain no LiteRT-LM classes. `BenchmarkInfo` values are mapped into project-owned diagnostics. This allows deterministic unit tests and localizes future SDK migrations.

### XML validation console

The app uses one activity and ViewPager2/TabLayout with Model, Chat, and Diagnostics fragments. A shared ViewModel exposes StateFlow-backed UI state. No Compose migration is included because it does not contribute to SDK validation.

## Risks / Trade-offs

- **Large imports can exhaust storage or be interrupted** → Check known source size against usable space with a safety margin, stream-copy, and delete stale `.partial` files on startup and failure.
- **Bundling increases APK size and first-launch disk usage** → Store the asset uncompressed, expose installation progress, require adequate space, and retain only one private selected copy.
- **Gemma is license-gated** → Never embed credentials or auto-accept terms; require the builder to accept the license externally and keep the binary out of Git.
- **GPU support varies by vendor** → Declare optional OpenCL/VNDK libraries, catch initialization failure, close partial native state, and show CPU fallback explicitly.
- **LiteRT-LM's published POM understates its coroutine ABI requirement** → Explicitly pin core, Android, and test coroutine artifacts to 1.11.0 and verify the resolved runtime graph before device validation.
- **Native initialization can take seconds** → Run it off the main thread and expose progress state.
- **A malformed `.litertlm` file may pass extension checks** → Treat SDK initialization as the definitive validation and keep the recoverable model-management screen available.
- **A large model may fail on low-memory hardware** → Document the real-device requirement, expose errors, and avoid claiming emulator performance.
- **ABI filtering excludes x86 devices** → This is intentional for the selected ARM64 validation baseline.

## Migration Plan

1. Add and strictly validate OpenSpec artifacts.
2. Upgrade the toolchain, introduce `edge-ai`, migrate the manifest/resources into `app`, and remove old modules.
3. Provision the license-approved model asset locally, implement bundled/private model persistence and runtime behind interfaces, then add the validation UI.
4. Run unit, instrumentation-build, lint, APK package, and ABI checks.
5. Validate model import and inference on an Android 12+ ARM64 physical device before archiving the change.

Rollback is a Git revert to the initial project commit plus removal of the local ignored asset; installed model files live in app-private runtime storage.

## Open Questions

None. Device-specific performance results are recorded during acceptance and do not alter the implementation contract.
