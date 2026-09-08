## ADDED Requirements

### Requirement: Assemble bounded context deterministically
The ContextAssembler SHALL allocate context in the order of security/instructions, worker objective, relevant memory, current branch events, tool summaries, and compacted history.

#### Scenario: Context exceeds model budget
- **WHEN** candidate context exceeds the selected model's limit
- **THEN** lower-priority history SHALL be compacted or omitted while security rules and current objective remain intact

### Requirement: Isolate worker history and data
Workers SHALL receive only explicitly shared inputs, scoped memory, current branch events, and capability outputs.

#### Scenario: Sibling worker has private tool result
- **WHEN** one worker obtains a sensitive result not included in its parent result contract
- **THEN** the sibling worker MUST NOT receive that result in its context

### Requirement: Provide local long-term memory by default
The SDK SHALL provide locally persisted, user-controllable long-term memory with provenance, confidence, sensitivity, timestamps, expiry, and deletion metadata.

#### Scenario: Recall relevant memory
- **WHEN** a new task is eligible for memory retrieval
- **THEN** the service SHALL rank Room FTS matches by relevance, recency, and importance and inject only policy-allowed records

#### Scenario: Exclude secrets from memory
- **WHEN** a memory candidate contains credentials, authorization headers, approvals, or raw sensitive tool output
- **THEN** the service MUST reject or redact the candidate before persistence

### Requirement: Commit worker memory through supervisor policy
Worker agents SHALL propose memory candidates but MUST NOT directly commit long-term memory.

#### Scenario: Duplicate memory proposal
- **WHEN** multiple workers propose semantically equivalent facts
- **THEN** MemoryPolicy SHALL merge or reject duplicates and record the originating runs

### Requirement: Persist large outputs as artifacts
Large or binary results SHALL be stored in app-private Artifact storage with versioned metadata and SHA-256, while model context receives only bounded summaries and references.

#### Scenario: Artifact survives run recovery
- **WHEN** a process restarts after an artifact was committed
- **THEN** its ArtifactRef SHALL resolve to the verified content and remain associated with the originating step
