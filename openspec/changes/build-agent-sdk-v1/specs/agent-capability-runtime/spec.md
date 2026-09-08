## ADDED Requirements

### Requirement: Execute tools through a unified validated pipeline
All local, Skill, MCP, and remote-agent capabilities MUST pass discovery, schema validation, policy validation, risk classification, approval, execution, sanitization, and persistence.

#### Scenario: Invalid tool arguments are repairable
- **WHEN** a model produces arguments that fail the registered JSON Schema
- **THEN** the runtime SHALL return a structured validation error for at most two model repair attempts and MUST NOT invoke the tool prematurely

### Requirement: Enforce risk-based approval
The runtime SHALL automatically run LOW capabilities, request scoped first-use consent for MEDIUM capabilities, request confirmation for every HIGH call, and reject BLOCKED calls.

#### Scenario: High-risk write pauses durably
- **WHEN** a HIGH tool call is proposed
- **THEN** its normalized arguments and risk explanation SHALL be persisted before the run enters `WAITING_APPROVAL`

#### Scenario: Approval executes once after restart
- **WHEN** the user approves a pending call after process recreation
- **THEN** the exact persisted call SHALL execute once with its original idempotency identity

### Requirement: Load and execute controlled skills
The SDK SHALL support versioned Skill packages with Manifest, `SKILL.md`, resources, capability declarations, integrity metadata, and progressive instruction loading.

#### Scenario: Selected skill loads lazily
- **WHEN** routing selects an enabled Skill
- **THEN** the runtime SHALL verify integrity, load its full instructions and resources, and expose only its allowed capabilities to the executing agent

#### Scenario: Skill requests undeclared capability
- **WHEN** a Skill workflow or script requests a capability absent from its manifest
- **THEN** execution MUST be denied and an auditable policy event SHALL be recorded

### Requirement: Sandbox JavaScript skills
The JavaScript backend MUST run with bounded time, memory, output, and no ambient file, network, process, or Android API access.

#### Scenario: Script exceeds execution limit
- **WHEN** a script exceeds its configured CPU/time or output budget
- **THEN** the sandbox SHALL terminate it and return a structured failure without terminating the SDK process

### Requirement: Operate as an MCP client
The SDK SHALL support MCP Streamable HTTP and SSE compatibility, tool discovery, health, authentication, timeout, reconnection, and namespaced registration, and MUST NOT launch arbitrary stdio servers.

#### Scenario: MCP schema changes
- **WHEN** server capability discovery returns a schema version different from the cached version
- **THEN** the registry SHALL atomically replace the cached descriptors before new calls use them

### Requirement: Invoke remote agents through A2A client
The SDK SHALL map configured A2A remote agents to RemoteWorkerProvider while enforcing local privacy, budget, approval, and untrusted-output policies.

#### Scenario: Remote worker unavailable
- **WHEN** an A2A worker times out or fails health validation
- **THEN** the Supervisor SHALL receive a structured failure and MAY select an allowed local or cloud fallback
