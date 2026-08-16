## ADDED Requirements

### Requirement: Environment diagnostics
The application SHALL display the application, LiteRT-LM, device, Android API, ABI, selected model, preferred backend, and effective backend information needed to reproduce a validation run.

#### Scenario: Runtime is ready
- **WHEN** model initialization completes
- **THEN** the Diagnostics page SHALL show the selected model and effective backend without exposing a user source URI or credential

#### Scenario: Runtime is not initialized
- **WHEN** no Engine is loaded
- **THEN** the Diagnostics page SHALL show environment and model metadata while marking runtime metrics unavailable

### Requirement: Backend fallback diagnostics
The application MUST expose whether GPU fallback occurred and preserve a sanitized failure reason for the current runtime session.

#### Scenario: Emulator compatibility fallback
- **WHEN** startup selects CPU because an Android emulator cannot provide the LiteRT-LM GPU/OpenCL runtime
- **THEN** diagnostics SHALL identify GPU as preferred, CPU as effective, and display an emulator-compatible fallback reason without exposing an internal model path

#### Scenario: CPU is used after GPU failure
- **WHEN** CPU initialization succeeds after a GPU error
- **THEN** diagnostics SHALL identify CPU as effective, GPU as preferred, and display the fallback reason

### Requirement: Inference benchmark diagnostics
The application SHALL map LiteRT-LM BenchmarkInfo into initialization time, time to first token, prefill token count, decode token count, prefill tokens per second, and decode tokens per second.

#### Scenario: Generation completes
- **WHEN** LiteRT-LM reports benchmark information after a response
- **THEN** the Diagnostics page SHALL display every reported metric with explicit units

#### Scenario: Benchmark information is unavailable
- **WHEN** generation has not completed or the SDK does not return a usable value
- **THEN** the application MUST show the metric as unavailable rather than inventing or estimating it
