## ADDED Requirements

### Requirement: Route models by capability and policy
The ModelRouter SHALL select local or cloud providers using requested capability, ModelPolicy, privacy policy, network availability, and provider health.

#### Scenario: Local-only request remains offline
- **WHEN** a request uses `LOCAL_ONLY`
- **THEN** the SDK MUST NOT make a cloud model request even when the local model fails

#### Scenario: Prefer-local fallback is permitted
- **WHEN** a `PREFER_LOCAL` request encounters a classified local failure and cloud use is allowed
- **THEN** the router SHALL emit a fallback event and retry through the configured cloud provider

### Requirement: Share and serialize local inference
All local workers SHALL acquire model leases through ModelExecutionBroker; default global local inference concurrency and per-Engine concurrency SHALL both be one.

#### Scenario: Two workers request local inference
- **WHEN** two workers concurrently request local generation
- **THEN** the broker SHALL execute both fairly without overlapping native inference or allocating a worker-owned Engine

### Requirement: Manage native resources safely
The LiteRT host MUST initialize, invoke, cancel, reset, and close native resources on an exclusive dispatcher and MUST close Conversation before Engine.

#### Scenario: GPU initialization fails
- **WHEN** GPU initialization fails and CPU initialization succeeds
- **THEN** the host SHALL release the failed candidate, continue on CPU, and expose the effective backend and sanitized fallback reason

### Requirement: Support dual local models and adaptive residency
The SDK SHALL support FunctionGemma for planning/tool selection and Gemma3 for conversation/synthesis while adapting resident model count to device memory state.

#### Scenario: Low-memory device switches models
- **WHEN** a low-memory device needs a second local model
- **THEN** the broker SHALL release an idle model before loading the next and SHALL preserve run context outside Conversation state

### Requirement: Keep tool execution above the model layer
The LiteRT adapter MUST disable automatic tool execution and SHALL surface structured tool calls to the Agent engine.

#### Scenario: Model emits a tool call
- **WHEN** the local model returns one or more function calls
- **THEN** the adapter SHALL return them without executing tools and SHALL accept validated tool responses in a subsequent model request

### Requirement: Preserve runtime quality and diagnostics
The new model plane SHALL retain model integrity checks, backend diagnostics, generation benchmarks, cancellation, and semantic-echo rejection from the existing runtime.

#### Scenario: Generated answer is rejected as an echo
- **WHEN** the response quality policy classifies an answer as semantic repetition
- **THEN** the adapter SHALL discard the incomplete conversation cache and retry within the configured attempt limit
