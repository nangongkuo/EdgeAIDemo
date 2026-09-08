## ADDED Requirements

### Requirement: Apply guardrails at every trust boundary
The SDK SHALL run configurable guardrails on user input, model output, tool arguments, tool results, Skill content, MCP content, and remote-agent content.

#### Scenario: External result contains injected instructions
- **WHEN** an external capability returns text attempting to override system or approval rules
- **THEN** the SDK MUST preserve it as untrusted data and MUST NOT treat it as executable instruction

### Requirement: Enforce privacy-aware cloud egress
Cloud-bound prompts and attachments MUST pass privacy classification, policy validation, and configured redaction before transmission.

#### Scenario: Sensitive local-only content targets cloud
- **WHEN** a request or worker context is classified local-only
- **THEN** cloud routing and remote capability invocation MUST be denied with a policy event

### Requirement: Protect secrets and private storage
Provider secrets SHALL be protected by Android Keystore-backed storage, and model, run, memory, artifact, and capability data SHALL remain in app-private storage.

#### Scenario: Production trace records a credential-bearing call
- **WHEN** a tool call uses a stored credential
- **THEN** traces SHALL contain only credential metadata or redacted placeholders and MUST NOT contain the secret value

### Requirement: Emit unified trace and diagnostics
Every run SHALL emit correlated events and spans for routing, agents, workers, model calls, tools, approvals, artifacts, memory, recovery, and terminal outcome.

#### Scenario: Replay completed task
- **WHEN** diagnostics requests a completed RunId
- **THEN** the SDK SHALL reconstruct an ordered execution timeline from persisted events without requiring prompt or tool-result bodies to be logged externally

### Requirement: Keep production content telemetry off by default
Production telemetry MUST disable raw prompts, model output, tool arguments, tool results, and memory values unless the host explicitly enables a development-only diagnostic policy.

#### Scenario: Default telemetry export
- **WHEN** the SDK exports metrics under default configuration
- **THEN** it SHALL include timing, counts, states, provider/model identifiers, and sanitized errors but no raw user content
