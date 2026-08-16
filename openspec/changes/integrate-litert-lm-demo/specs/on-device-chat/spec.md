## ADDED Requirements

### Requirement: Automatic startup initialization
The application SHALL automatically initialize the valid selected model after startup provisioning without requiring the user to press Initialize.

#### Scenario: Automatic GPU startup succeeds
- **WHEN** a selected or freshly installed model is available and GPU initialization succeeds
- **THEN** the runtime SHALL become ready automatically and enable chat input

#### Scenario: Automatic GPU startup requires CPU fallback
- **WHEN** automatic GPU initialization fails and CPU initialization succeeds
- **THEN** the runtime MUST become ready on CPU and display the GPU failure reason

#### Scenario: Automatic startup initialization fails
- **WHEN** neither backend can initialize the model
- **THEN** the application SHALL keep the model-management controls available and display a recoverable error for retry, replacement, or deletion

### Requirement: Backend-aware runtime initialization
The runtime SHALL initialize a selected model off the main thread using the preferred backend and expose the effective backend in state.

#### Scenario: Android emulator GPU preflight
- **WHEN** GPU is preferred on an Android emulator identified by a standard generic, ranchu, or goldfish runtime signature
- **THEN** the runtime MUST initialize CPU without attempting a GPU Engine, and SHALL report GPU as preferred, CPU as effective, and an emulator/OpenCL compatibility reason

#### Scenario: Physical-device GPU behavior is preserved
- **WHEN** GPU is preferred on a non-emulator Android device
- **THEN** the runtime SHALL continue to attempt GPU initialization before its existing CPU fallback behavior

#### Scenario: GPU initialization succeeds
- **WHEN** GPU is preferred and LiteRT-LM initializes successfully
- **THEN** the runtime SHALL become ready with GPU as the effective backend

#### Scenario: GPU initialization fails
- **WHEN** GPU is preferred and initialization throws an error
- **THEN** the runtime MUST close partial native resources, retain the GPU error as a fallback reason, and attempt CPU initialization exactly once

#### Scenario: CPU fallback also fails
- **WHEN** CPU fallback cannot initialize the model
- **THEN** the runtime SHALL enter a recoverable error state and MUST NOT retain an Engine or Conversation

### Requirement: Offline streaming conversation
The runtime SHALL generate multi-turn text locally using LiteRT-LM Flow streaming without requiring network access after model import.

#### Scenario: Stream a response
- **WHEN** the runtime is ready and the user submits a non-blank prompt
- **THEN** the runtime SHALL emit ordered text deltas followed by one completion event while retaining conversation context

#### Scenario: Reject concurrent generation
- **WHEN** a generation is already active and another prompt is submitted
- **THEN** the runtime MUST reject the second prompt without starting another native generation

### Requirement: LiteRT-LM completion-callback ABI compatibility
The application MUST resolve `org.jetbrains.kotlinx:kotlinx-coroutines-core` and `org.jetbrains.kotlinx:kotlinx-coroutines-android` version 1.11.0 for every packaged runtime variant that includes LiteRT-LM 0.15.0.

#### Scenario: Verify the packaged coroutine runtime
- **WHEN** the debug runtime dependency graph is resolved
- **THEN** Gradle SHALL select 1.11.0 for both coroutine core and Android artifacts and MUST NOT select a lower version through LiteRT-LM's published metadata or another transitive dependency

#### Scenario: Complete a streamed response
- **WHEN** LiteRT-LM finishes an otherwise successful streamed response
- **THEN** the application MUST remain alive, emit its normal completion event, and MUST NOT terminate with `NoSuchMethodError` for `SendChannel.close$default`

### Requirement: Generation cancellation
The application SHALL allow the user to cancel active generation and return the runtime to a usable ready state.

#### Scenario: Cancel an active response
- **WHEN** the user selects Stop during generation
- **THEN** the runtime MUST call LiteRT-LM cancellation, stop collecting output, and become ready for another prompt

### Requirement: Conversation reset
The runtime SHALL clear conversation history without reloading the Engine.

#### Scenario: Clear chat history
- **WHEN** the user confirms Clear conversation while the runtime is ready
- **THEN** the runtime MUST close the old Conversation, create a new Conversation with default sampling configuration, and retain the loaded Engine

### Requirement: Deterministic resource release
The runtime MUST close Conversation before Engine during model change, explicit unload, or final owner cleanup.

#### Scenario: Unload a model
- **WHEN** the user unloads the active model
- **THEN** the runtime SHALL cancel active work, release all native resources, and return to the unloaded state
