## ADDED Requirements

### Requirement: Stable SDK client lifecycle
The SDK SHALL expose project-owned initialization and client APIs without exposing ADK, LiteRT-LM, Room, or cloud-provider types.

#### Scenario: Initialize and close client
- **WHEN** a host creates the SDK with a valid Android context and configuration and later closes it
- **THEN** the SDK SHALL initialize registered services and release active execution resources without deleting persisted runs

### Requirement: Durable task submission and observation
`EdgeAgentClient` SHALL persist an accepted request before returning its RunHandle and SHALL expose a replayable event stream and current snapshot by RunId.

#### Scenario: Observe after subscribing late
- **WHEN** a caller subscribes after a run has already emitted events
- **THEN** the client SHALL replay persisted events in order and then emit live events without gaps or duplicates

### Requirement: Unified continuation controls
The client SHALL support approval decisions, user input, cancellation, and checkpoint retry through versioned continuation types.

#### Scenario: Continue a waiting run
- **WHEN** a caller supplies the expected continuation for a run waiting on approval or input
- **THEN** the run SHALL persist the continuation exactly once and resume from its checkpoint

#### Scenario: Reject stale continuation
- **WHEN** a continuation targets an already resolved or terminal request
- **THEN** the client MUST reject it without changing run state

### Requirement: Legacy runtime compatibility
The 1.x SDK SHALL retain the existing `EdgeAiRuntime` contract as a deprecated compatibility façade over the single-agent path.

#### Scenario: Existing chat caller runs unchanged
- **WHEN** an existing caller initializes a model and invokes `generate(prompt)`
- **THEN** it SHALL receive compatible generation events without adopting new Agent SDK types
