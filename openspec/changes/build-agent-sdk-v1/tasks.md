## 1. Build and module foundation

- [x] 1.1 Add Agent SDK modules to Gradle settings and centralize fixed dependency versions
- [x] 1.2 Configure JDK 21, Kotlin serialization, Room/KSP, consumer rules, and Android API 31 compatibility
- [x] 1.3 Add module-boundary checks ensuring public API does not expose ADK, LiteRT-LM, Room, or provider types

## 2. Public SDK contracts

- [x] 2.1 Implement versioned identifiers, requests, attachments, budgets, model policies, run states, and snapshots
- [x] 2.2 Implement the complete AgentEvent hierarchy and deterministic event ordering contract
- [x] 2.3 Implement EdgeAgentSdk, EdgeAgentClient, RunHandle, continuation, cancel, retry, and snapshot APIs
- [x] 2.4 Preserve EdgeAiRuntime as a deprecated compatibility façade with contract tests

## 3. Durable run lifecycle

- [x] 3.1 Implement Room entities and converters for runs, workers, steps, events, tool calls, approvals, checkpoints, artifacts, and memories
- [x] 3.2 Implement transactional RunStore append/snapshot/replay and monotonic sequence allocation
- [x] 3.3 Implement coroutine-actor RunScheduler with queueing, fair dispatch, hierarchical cancellation, and terminal-state enforcement
- [x] 3.4 Implement startup recovery, safe checkpoint replay, and unknown non-idempotent side-effect suspension
- [x] 3.5 Add Room migration, process-recovery, duplicate-callback, and cancellation tests

## 4. Model plane

- [x] 4.1 Extract LiteRtEngineHost from the current runtime while preserving backend fallback, lifecycle, cancellation, and diagnostics
- [x] 4.2 Implement ModelProvider, ModelCapabilities, ModelRouter, privacy-aware fallback, and OpenAI-compatible provider
- [x] 4.3 Implement fair ModelExecutionBroker with global/per-engine permits, leases, adaptive residency, and low-memory eviction
- [x] 4.4 Implement EdgeLiteRtModelAdapter with manual tool calls, context reconstruction, streaming, and quality retries
- [x] 4.5 Add local serialization, dual-model routing, offline policy, fallback, cancellation, and native lifecycle tests

## 5. Agent orchestration

- [x] 5.1 Implement AgentDefinition, ExecutionPlan, PlanNode, WorkerRun, WorkerResult, and plan validation
- [x] 5.2 Implement deterministic RequestRouter and single-agent fast path
- [x] 5.3 Implement sequential, parallel, loop, human-gate, and bounded workflow execution
- [x] 5.4 Implement Supervisor/Agent-as-Tool logical workers with isolated branches and bounded delegation
- [x] 5.5 Implement controlled Handoff and background-run transfer restrictions
- [x] 5.6 Implement repeated-tool/non-progress detection and budget enforcement
- [x] 5.7 Integrate ADK Kotlin 0.8.0 behind AgentEngine and add ADK event/tool/transfer contract tests

## 6. Capability plane

- [x] 6.1 Implement ToolRegistry, JSON Schema validation, deferred discovery, namespacing, result truncation, and idempotency metadata
- [x] 6.2 Implement LOW/MEDIUM/HIGH/BLOCKED policy evaluation and durable approval continuation
- [x] 6.3 Implement Skill manifest/parser/store, integrity checks, progressive loading, and instruction/workflow backends
- [x] 6.4 Implement bounded JavaScript sandbox with capability injection and resource limits
- [x] 6.5 Implement MCP Streamable HTTP/SSE client, discovery cache, authentication, health, timeout, and reconnection
- [x] 6.6 Implement A2A RemoteWorkerProvider with local policy enforcement and fallback results
- [x] 6.7 Add tool repair, approval recovery, Skill isolation, sandbox limit, MCP schema change, and A2A failure tests

## 7. Context, memory, artifacts, and safety

- [x] 7.1 Implement deterministic ContextAssembler, token budgeting, branch isolation, and history compaction
- [x] 7.2 Implement local long-term MemoryService with FTS ranking, policy filtering, deduplication, expiry, deletion, and optional embeddings
- [x] 7.3 Implement app-private ArtifactStore with SHA-256, metadata, bounded summaries, and recovery
- [x] 7.4 Implement guardrails and untrusted-content boundaries for every model/capability ingress and egress
- [x] 7.5 Implement Keystore-backed secrets and cloud egress classification/redaction
- [x] 7.6 Implement correlated trace, sanitized diagnostics, replay, retention, and content-off-by-default telemetry
- [x] 7.7 Add context-budget, secret-exclusion, memory lifecycle, artifact recovery, injection, and telemetry-redaction tests

## 8. Demo application migration

- [x] 8.1 Wire EdgeAgentClient into the application while retaining existing model import, backend selection, and diagnostics
- [x] 8.2 Migrate chat to replayable AgentEvent rendering and preserve multilingual/semantic-echo behavior
- [x] 8.3 Add run timeline, Worker graph, approval/input, Artifact, memory, Skill, MCP, A2A, and cloud-provider screens
- [x] 8.4 Add optional foreground execution host without coupling it to logical Worker types
- [x] 8.5 Add UI tests for simple, supervisor, approval, recovery, cancellation, and offline flows

## 9. Verification and release

- [x] 9.1 Validate OpenSpec artifacts strictly and run all unit, lint, build, and instrumentation compile checks
- [x] 9.2 Run fault-injection, Golden Trace, public API compatibility, dependency leakage, and database migration suites
- [ ] 9.3 Verify Android 12+ ARM64 hardware scenarios including dual-model memory pressure, GPU fallback, process death, and flight mode
  - Blocked on 2026-09-08: only an ARM64 emulator is connected; emulator instrumentation passed, but it cannot certify physical-device GPU and memory-pressure behavior.
- [x] 9.4 Update architecture, integration, security, Skill/MCP/A2A, migration, and SDK reference documentation
- [x] 9.5 Verify no model binaries, credentials, prompts, private paths, or generated artifacts are tracked and mark the change complete
