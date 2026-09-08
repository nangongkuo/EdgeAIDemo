## ADDED Requirements

### Requirement: Persist the complete run graph
The system SHALL persist Run, AgentNode, WorkerRun, Step, Event, ToolCall, Approval, Checkpoint, Artifact, and Memory references in Room.

#### Scenario: Run state and event are atomic
- **WHEN** a transition event is appended
- **THEN** the event and derived snapshot state MUST be committed in the same database transaction

### Requirement: Recover non-terminal runs
At SDK initialization, runs left in planning or running states SHALL enter recovery and resume only from a safe persisted checkpoint.

#### Scenario: Recover idempotent step
- **WHEN** the process dies during a model call or an idempotent read and restarts
- **THEN** the coordinator SHALL replay the step using the same stable identity and continue the run

#### Scenario: Do not replay unknown external write
- **WHEN** the process dies after dispatching a non-idempotent external write without a durable result
- **THEN** the run MUST enter `WAITING_USER_INPUT` and MUST NOT automatically repeat the write

### Requirement: Enforce idempotency and ordered events
Every side-effecting attempt SHALL use a stable `runId`, `stepId`, and `callId`, and each run's events SHALL have a strictly increasing sequence.

#### Scenario: Duplicate completion callback
- **WHEN** a provider delivers the same completion callback more than once
- **THEN** the store SHALL persist one logical completion and SHALL NOT execute downstream nodes twice

### Requirement: Propagate cancellation hierarchically
Cancelling a parent run SHALL cancel queued and active descendants, active model generation, and cancellable tools while retaining committed audit events.

#### Scenario: Cancel supervisor with workers
- **WHEN** the host cancels a running Supervisor task
- **THEN** all non-terminal WorkerRuns SHALL reach a terminal cancelled state and no new node SHALL start

### Requirement: Separate Agent workers from Android execution hosts
The run scheduler SHALL use logical workers independently of WorkManager or foreground services, while allowing an optional host adapter to keep the same scheduler alive.

#### Scenario: Resume in a new process host
- **WHEN** a different Android execution host initializes the SDK after process recreation
- **THEN** it SHALL recover the same persisted run graph without converting tasks into Android Worker semantics
