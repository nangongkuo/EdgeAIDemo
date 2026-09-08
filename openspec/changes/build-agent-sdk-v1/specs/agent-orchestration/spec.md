## ADDED Requirements

### Requirement: Route requests by complexity
The orchestrator SHALL route ordinary chat, deterministic workflows, and decomposable complex tasks to the single-agent, workflow, and Supervisor paths respectively.

#### Scenario: Simple chat avoids workers
- **WHEN** a request needs no decomposition and no multi-step capability sequence
- **THEN** the orchestrator SHALL execute it through one root agent and SHALL NOT create a WorkerRun

#### Scenario: Complex request uses supervisor
- **WHEN** a request contains independent goals, heterogeneous capabilities, or an explicit verification requirement
- **THEN** the orchestrator SHALL validate an execution plan and create bounded WorkerRuns

### Requirement: Validate execution plans before execution
The system MUST validate plan node types, dependencies, cycles, budgets, capability references, and output contracts before scheduling any node.

#### Scenario: Invalid plan is repaired safely
- **WHEN** FunctionGemma returns an invalid or unauthorized plan
- **THEN** the system SHALL reject execution, allow at most two repair attempts, and fail safely if no valid plan is produced

### Requirement: Isolate logical workers
Each WorkerRun SHALL have an isolated session branch, objective, input, capability allowlist, budget, and typed result, and MUST NOT own a LiteRT Engine.

#### Scenario: Parallel worker context isolation
- **WHEN** two sibling workers execute in the same parent run
- **THEN** neither worker SHALL receive the other worker's private history and both SHALL return only summaries, evidence, state deltas, and Artifact references to the parent

### Requirement: Support bounded orchestration patterns
The engine SHALL support sequential, parallel, loop, Agent-as-Tool, and Handoff patterns with defaults of four workers, depth two, and twelve steps per agent.

#### Scenario: Local parallel workflow respects model serialization
- **WHEN** parallel workers request the same local model
- **THEN** they MAY remain logically active but their model calls MUST execute through the shared single-permit broker

#### Scenario: Handoff is disallowed for background work
- **WHEN** a non-interactive background run attempts an implicit Handoff
- **THEN** the engine MUST reject the transfer and return control to the Supervisor

### Requirement: Detect non-progress loops
The orchestrator SHALL terminate a branch that repeats an equivalent tool call twice without material state progress or exceeds a configured budget.

#### Scenario: Repeated call is surfaced to supervisor
- **WHEN** a worker emits the same normalized tool name and arguments twice with unchanged relevant state
- **THEN** the worker SHALL stop and the Supervisor SHALL receive a structured non-progress result
