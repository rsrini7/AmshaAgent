# ArcClaw — System Requirements

> **Document Type:** Software Requirements Specification (SRS)
> **V1.0.0 Status:** Baselined — Production Implementation Complete
> **Audit Date:** February 2026
> **Audience:** Engineers, Architects, SREs, Product Owners

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Technology Stack & Version Constraints](#2-technology-stack--version-constraints)
3. [Maven Module Architecture](#3-maven-module-architecture)
4. [Functional Requirements](#4-functional-requirements)
   - 4.1 CDI Scope Layer
   - 4.2 Security & Vault Layer
   - 4.3 Sandbox & Process Isolation Layer
   - 4.4 Tool Layer
   - 4.5 Agent & Orchestration Layer
   - 4.6 State & Checkpointing Layer
   - 4.7 Lifecycle Management Layer
   - 4.8 Governance Layer
   - 4.9 Observability & OTel Layer
   - 4.10 Health Layer
   - 4.11 REST API Layer
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Contract](#6-api-contract)
7. [Configuration Requirements](#7-configuration-requirements)
8. [Deployment Requirements](#8-deployment-requirements)
9. [Testing Requirements](#9-testing-requirements)
10. [Operational Constraints & Known Boundaries](#10-operational-constraints--known-boundaries)
11. [Feature Parity Baseline](#11-feature-parity-baseline)
12. [Roadmap & Phases](#12-roadmap--phases)

---

## 1. System Overview

ArcClaw is a **production-grade, self-contained agentic kernel** built on Java/Quarkus. It enables autonomous AI agents to plan, reason, and act using LLM-backed multi-step thought loops, while enforcing strict security boundaries, financial governance, and operational observability. It is designed to run as a single native binary on edge or cloud infrastructure, requiring no external database.

### 1.1 Design Philosophy

ArcClaw synthesizes the core philosophies of three predecessor systems:

| Predecessor | Contribution |
|---|---|
| **ZeroClaw** (Rust) | Trait-driven agent design, sandboxing model, lifecycle patterns |
| **PicoClaw** (Go) | Portability-first: small binary, fast startup, edge-deployable mindset |
| **SafeClaw** | Cryptographic hardening: Argon2id, AES-GCM-SIV vault, autonomy policy |

### 1.2 Core Pillars

| Pillar | Requirement Summary |
|---|---|
| **Isolation** | AI-generated tool executions must run in OS-level isolated containers (Podman rootless) or Linux user namespaces. No Docker daemon socket shall be mounted. |
| **State** | Per-workspace SQLite in WAL mode provides session-isolated, concurrent-write-safe storage without external dependencies. |
| **Lifecycle** | A custom `@WorkspaceScoped` CDI scope must survive across multiple HTTP requests within the same logical agent session. |
| **Resiliency** | Every reasoning step (thought/action/observation) must be checkpointed so agents can resume after JVM restart without data loss. |
| **Governance** | Real-time per-session token counters and USD cost guardrails must downgrade agent autonomy or halt execution when budget is exceeded. |
| **Security** | All secrets are encrypted at rest using Argon2id + AES-256-GCM-SIV. Secrets are injected into sandbox containers via ephemeral env-files that are deleted immediately after process handoff. No plaintext must ever materialise as a Java `String`. |

---

## 2. Technology Stack & Version Constraints

All versions below are **pinned and non-negotiable** for this release. Upgrades require a full compatibility verification pass.

| Component | Version | Rationale |
|---|---|---|
| **Java (Runtime)** | JDK 21 minimum, JDK 25 preferred | JEP 491 (JDK 25) eliminates virtual-thread pinning on `synchronized`, enabling safe `ReentrantLock` simplification. JDK 21 supported but incurs thread-pinning penalty on carrier threads. |
| **Quarkus** | 3.23.0 (pinned) | Resolves ByteBuddy/Hibernate incompatibilities with JDK 25. Do not upgrade without verifying ByteBuddy support. |
| **LangChain4J** (Quarkus Extension) | 0.26.1 (transitively managed by Quarkus BOM) | Importing `langchain4j-bom:1.0.0-beta4` directly causes `NoClassDefFoundError: ChatLanguageModel` due to package migrations. Must NOT be imported separately. |
| **BouncyCastle** | 1.83 (bcprov-jdk18on) | Required for `GCMSIVBlockCipher` performance and JDK 25 compatibility. `bc-fips` variant must be excluded to avoid signed-jar JCE conflicts. |
| **SQLite JDBC** | `quarkus-jdbc-sqlite` (Quarkus-managed) | Explicitly required in `arcclaw-app/pom.xml`; not auto-detected. |
| **ByteBuddy** | Experimental flag required | `-Dnet.bytebuddy.experimental=true` must be set in `MAVEN_OPTS` and Surefire configuration when running on JDK 25. |
| **Maven** | 3.x (Wrapper enforced) | All builds use `mvnw` (Maven Wrapper) to ensure version consistency. |

### 2.1 JDK 25 JEP Alignment

The following JEPs are relevant to ArcClaw's architecture. Their status must be considered before adopting any JDK 25-exclusive APIs:

| JEP | Feature | Status in JDK 25 | Impact on ArcClaw |
|---|---|---|---|
| **JEP 491** | Virtual Thread Pinning Elimination | **Finalized** | `synchronized` blocks no longer pin carrier threads. `ReentrantLock` remains the safe default for JDK 21/23 cross-compatibility. |
| **JEP 506** | Scoped Values | **Finalized** | `WorkspaceScopeHolder` must document the `ThreadLocal` → `ScopedValue` migration path. `orElse(null)` throws `NullPointerException` in JDK 25 — use `orElseThrow()`. |
| **JEP 505** | Structured Concurrency | **Fifth Preview** (NOT finalized) | Must NOT be used in stable production code. Constructor API changed: `new ShutdownOnFailure()` removed. Finalization expected JDK 26. |
| **JEP 473** | Stream Gatherers | **Finalized in JDK 24** | Available without preview flags since JDK 24 GA. |

---

## 3. Maven Module Architecture

The system is structured as a **3-module Maven project** with strict boundary rules:

```
arcclaw-parent/
├── pom.xml                         (Parent BOM, dependency management)
├── arcclaw-core/                   (Runtime library — all business logic)
├── arcclaw-deployment/             (Quarkus build-time augmentation)
└── arcclaw-app/                    (Runnable Quarkus application, tests, config)
```

### 3.1 Module Boundary Rules

**`arcclaw-core`** (Runtime Library):
- Must include the Jandex plugin (`jandex-maven-plugin`). Every new class added to this module requires a Jandex index rebuild; otherwise Quarkus ArC skips bean discovery for the module entirely.
- Must NOT use `PanacheEntity` or `PanacheRepository`. Panache bytecode enhancement only executes within the application module classloader.
- Must use plain `EntityManager` and JPQL for all JPA operations.
- Must NOT use `@Lob` on `byte[]` entity fields. SQLite's JDBC driver does not implement `getBlob()`, causing `SQLFeatureNotSupportedException`. Use raw `byte[]` mapping instead.
- Health check beans must be annotated with `@Liveness` or `@Readiness` and `@ApplicationScoped` only.

**`arcclaw-deployment`** (Build-Time Augmentation):
- Must contain `@BuildStep` processors only.
- Must declare `quarkus-arc-deployment` and `quarkus-core-deployment` as `provided` scope.
- Must include `quarkus-extension-processor` as a `provided` dependency. Without it, `@BuildStep` methods are silently ignored and custom CDI scopes are never registered.
- Must contain `META-INF/quarkus-extension.properties`.

**`arcclaw-app`** (Application Module):
- Must declare `quarkus-rest-jackson` and `quarkus-hibernate-validator` explicitly. These are not auto-resolved from the core module.
- Must declare `quarkus-jdbc-sqlite` explicitly.
- Must declare `quarkus.hibernate-orm.packages=com.arcclaw.core.state` in `application.yaml` for cross-module JPA entity discovery.
- All `@QuarkusTest` classes reside here.
- Native and JVM profiles, Failsafe integration tests, and the Quarkus Maven plugin are configured here.

### 3.2 Jandex Requirement (Critical)

Every module added to ArcClaw MUST include the `jandex-maven-plugin`. Failure to do so causes silent bean discovery failures that manifest as `UnsatisfiedResolutionException` at runtime and are difficult to diagnose. This is non-negotiable.

---

## 4. Functional Requirements

### 4.1 CDI Scope Layer (`@WorkspaceScoped`)

**Purpose:** Provide a custom CDI scope that binds bean instances to a logical "workspace session" rather than an HTTP request, allowing agent state to survive across multiple REST calls.

**REQ-SCOPE-01:** The system must provide a `@WorkspaceScoped` CDI scope annotation (`@NormalScope(passivating = false)`).

**REQ-SCOPE-02:** The scope context must store bean instances in a two-level map: outer key = `sessionId` (String), inner key = `InjectableBean`. This enables multiple threads (e.g., parallel sub-agents) to safely share the same workspace session.

**REQ-SCOPE-03:** The scope must be registered via a `@BuildStep` processor in `arcclaw-deployment`. Quarkus CDI Lite does not support Portable Extensions (`PortableExtension`). `ContextConfigurator.contextClass()` must be used for registration — no `BytecodeRecorder` is required.

**REQ-SCOPE-04:** The active session ID must be tracked via a thread-local holder (`WorkspaceScopeHolder`). The holder must be activated before entering any agent loop and cleared (in a `finally` block) upon exit to prevent context leaks across virtual thread reuse.

**REQ-SCOPE-05:** Bean creation within a session must use per-session locking. On JDK 21/23, `ReentrantLock` is required (not `synchronized`) to prevent virtual thread pinning on the carrier thread. On JDK 25, `synchronized` is acceptable due to JEP 491. The safe cross-version default is `ReentrantLock`.

**REQ-SCOPE-06:** The lock map (`sessionId → ReentrantLock`) must be cleaned up in the session destroy path to prevent unbounded memory growth.

**REQ-SCOPE-07:** The scope context must expose public lifecycle APIs: `destroySession(sessionId)`, `activeSessionCount()`, `beanCountForSession(sessionId)`. These are called by `WorkspaceManager`, not by ArC directly.

**REQ-SCOPE-08:** `WorkspaceScopeContext` must NOT be injectable as a CDI bean. It implements the Arc `InjectableContext` SPI and must only be retrieved via `Arc.container().getActiveContext(WorkspaceScoped.class)`.

**REQ-SCOPE-09:** The system must document the JDK 25 `ScopedValue` (JEP 506) migration path for `WorkspaceScopeHolder` as an inline code comment for future engineers.

**REQ-SCOPE-10:** MicroProfile context propagation must be configured: `mp.context.propagators=Quarkus, CDI, JAX-RS`. Without this, `@WorkspaceScoped` attributes and CDI contexts are silently null on virtual threads (REST endpoints annotated with `@RunOnVirtualThread`).

---

### 4.2 Security & Vault Layer

**Purpose:** Provide zero-plaintext-on-disk secret storage using authenticated encryption, with key derivation, rotation support, and session-scoped access control.

**REQ-SEC-01:** All cryptographic secrets must be stored using **Argon2id key derivation** (BouncyCastle) followed by **AES-256-GCM-SIV authenticated encryption**. The SIV (Synthetic IV) mode is required for nonce-misuse resistance.

**REQ-SEC-02:** Encryption keys must be encapsulated in an auto-wiping `VaultKey` wrapper. The `VaultKey` must implement `AutoCloseable` and zero-fill its internal `byte[]` on close. Secrets must never be materialised as Java `String` objects.

**REQ-SEC-03:** Secret rotation (`reEncrypt`) must stay entirely within `byte[]` arrays. No `String` intermediate may be used during the decrypt-then-encrypt rotation operation.

**REQ-SEC-04:** The `SecretVault` interface must expose: `store(name, secret)`, `get(name)` returning `Optional<VaultKey>`, `rotate(name, newMasterKey)`, and `delete(name)`.

**REQ-SEC-05:** The `EncryptedSecret` JPA entity must NOT use `@Lob` on its `byte[]` fields (ciphertext, salt, nonce). SQLite maps raw `byte[]` natively via `getBytes()`.

**REQ-SEC-06:** The system must support `@RequiresSecret` and `@RequiresSecrets` (repeatable) annotations on tool implementations. The `ToolRegistry` uses these annotations on either the `execute()` or `sandboxCommand()` methods to resolve and inject secrets from the vault before tool execution.

**REQ-SEC-07:** For native image builds, BouncyCastle's `SecureRandom` DRBG must be initialized at **runtime** (not build time). `native-image.properties` must configure fine-grained per-package `--initialize-at-run-time` flags for all BouncyCastle entropy-related classes. Two consecutive native binary executions must produce different nonces (DRBG verification test).

**REQ-SEC-08:** The autonomy policy must be enforced via an `AutonomyPolicy` component that gates tool execution based on the session's current `AutonomyLevel` (`READONLY`, `SUPERVISED`, `FULL`).

---

### 4.3 Sandbox & Process Isolation Layer

**Purpose:** Execute AI-generated tool code inside resource-capped, network-isolated containers or Linux namespaces to prevent host system compromise.

**REQ-SAND-01:** The `ProcessIsolationExecutor` must support three execution strategies:
- `PODMAN` — Primary strategy. Spawns a Podman rootless container.
- `NAMESPACE` — Fallback. Uses Linux `unshare` for namespace isolation without a container runtime.
- `NONE` — Development mode only. Direct execution with no isolation.

**REQ-SAND-02:** Podman containers must enforce resource caps: `--memory=512m`, `--cpus=0.5`, `--pids-limit=64`, `--ulimit=nofile=256:256`. These caps prevent a prompt-injected fork bomb or resource exhaustion attack from escaping the sandbox.

**REQ-SAND-03:** Podman containers must run with: `--network=bridge`, `--read-only`, `--cap-drop=ALL`. No Docker daemon socket (`/var/run/docker.sock`) shall ever be mounted.

**REQ-SAND-04:** Secrets required by a sandboxed tool must be injected via an ephemeral env-file (`--env-file`). The file must be written to `java.io.tmpdir` and **immediately locked down to POSIX permission `600` (owner read/write only) via `Files.setPosixFilePermissions()` before any secret data is written**. This eliminates the race window in which another OS process on the shared `/tmp` filesystem could read plaintext credentials. The file is then passed to Podman, zero-filled, and deleted in a `finally` block regardless of execution outcome.

**REQ-SAND-05:** A circuit breaker must detect Podman unavailability at startup (`@PostConstruct`). On 3 consecutive Podman execution failures, the strategy must automatically fall back to `NAMESPACE`. The fallback must be logged at WARNING level.

**REQ-SAND-06:** Active Podman container names must be tracked in a thread-safe set. On JVM `SIGTERM`, a `@PreDestroy` method must call `podman stop` with a 10-second grace period for all tracked containers to prevent zombie container accumulation.

**REQ-SAND-07:** Each sandbox execution must create a child OTel span under the current session's root span, with attributes for container name, strategy, and exit code.

---

### 4.4 Tool Layer

**Purpose:** Provide a pluggable, CDI-discovered tool system where each tool declares its governance requirements and may require vault-injected credentials.

**REQ-TOOL-01:** All tools must implement the `AgentTool` interface, exposing: `name()`, `description()`, `requiredOperation()` (the `AutonomyLevel` required to execute), and `execute(params)` returning a `ToolResponse`.

**REQ-TOOL-02:** `ToolResponse` must be a record carrying: `exitCode`, `output` (String), and `success` (boolean).

**REQ-TOOL-03:** The `ToolRegistry` (`@ApplicationScoped`) must discover all `AgentTool` implementations via CDI `Instance<AgentTool>`. New tools are added by implementing the interface and adding `@ApplicationScoped` — no manual registration is required.

**REQ-TOOL-04:** The `ToolRegistry` must enforce autonomy policy and OS-level sandboxing before delegating to `ProcessIsolationExecutor`. By default, all tools run within a sandbox unless `requiresSandbox()` returns `false`. Tools requiring a higher `AutonomyLevel` than the session's current level must be rejected.

**REQ-TOOL-05:** The `GithubTool` must serve as the canonical reference implementation: `@RequiresSecret(envVar = "GH_TOKEN", vaultKey = "GITHUB_TOKEN")`, delegates to `ProcessIsolationExecutor`, executes `gh repo view` inside the sandbox.

**REQ-TOOL-06:** Tool execution output must be truncated to a maximum of **4,000 characters** before being returned as an observation to the agent loop. This prevents context window overflow and silent `HTTP 400` errors from the LLM provider.

---

### 4.5 Agent & Orchestration Layer

**Purpose:** Implement a multi-agent Thought-Plan-Act-Observe loop with guardrails against hallucination, infinite cycling, and null parameter errors.

**REQ-AGENT-01:** The system must implement a `ReasoningEngine` interface as a testable abstraction over the LLM provider. This decouples the agent loop from LangChain4J, enabling unit testing without a live LLM.

**REQ-AGENT-02:** The `AgentLoop` must implement the Thought-Plan-Act-Observe pattern:
1. Load or resume checkpoint for the session + role.
2. Call `ReasoningEngine.nextStep()` to get the next `ReasoningStep`.
3. If action is `FINISH`, save a `COMPLETED` checkpoint and return the result.
4. Otherwise, resolve and execute the tool via `ToolRegistry`.
5. Append the observation to the history and save an `IN_PROGRESS` checkpoint.
6. Repeat up to `maxIterations`.

**REQ-AGENT-03:** The `AgentLoop` must enforce three guardrails:
- **Hallucination check:** If the LLM requests a tool name not found in `ToolRegistry`, inject an error observation listing available tools and continue (do not crash).
- **Loop protection:** After `maxIterations` (default 5), return `AgentResult.loopExceeded()`.
- **Null parameter guard:** If the LLM returns a tool action with null parameters, inject an error observation and continue.

**REQ-AGENT-04:** The `AgentLoop.run()` must be annotated with `@ActivateRequestContext` to ensure proper CDI context availability on virtual threads.

**REQ-AGENT-05:** MDC logging must tag every log entry within an agent loop execution with the `sessionId` for structured log filtering.

**REQ-AGENT-06:** The system must include four agent personas, each implemented as an `Agent` interface with a unique `@AgentRole` CDI qualifier:

| Agent | Role | CDI Qualifier |
|---|---|---|
| `VishnuAgent` | Coordinator — orchestrates other agents, synthesizes results | `@AgentRole("coordinator")` |
| `SaraswatiAgent` | Researcher — factual retrieval and knowledge synthesis | `@AgentRole("researcher")` |
| `BrahmaAgent` | Coder — logic, engineering, and code generation | `@AgentRole("coder")` |
| `ShivaAgent` | Debugger — challenges assumptions, detects blind spots, provides contrarian balance | `@AgentRole("debugger")` |

**REQ-AGENT-07:** `ArcClawSupervisor` (`@ApplicationScoped`) must maintain the CDI agent roster via `Instance<Agent>` with programmatic lookup by `@AgentRole` qualifier. Injecting `Agent` without a qualifier must not be permitted (ambiguous).

**REQ-AGENT-08:** The real LLM integration (`LangChainReasoningEngine`) must handle malformed JSON responses from the LLM. When `JsonParseException` is caught, it must return a `ReasoningStep` with `action=FINISH` and an error message, preventing infinite hallucination loops.

**REQ-AGENT-09:** The LLM provider must be disambiguated explicitly in `application.yaml` via `quarkus.langchain4j.chat-model.provider`. With multiple provider extensions on the classpath, Quarkus cannot auto-wire `ChatLanguageModel` unambiguously.

---

### 4.6 State & Checkpointing Layer

**Purpose:** Persist every agent reasoning step to durable storage so that agent sessions can be resumed after JVM restart without restarting the task from scratch.

**REQ-STATE-01:** Every `ReasoningStep` (thought, action, actionInput, observation) must be serialised to JSON and persisted to the `AgentCheckpoint` JPA entity after each loop iteration.

**REQ-STATE-02:** `AgentCheckpoint` must have a unique constraint on `(sessionId, agentRole)`. An upsert pattern (`EntityManager.merge()`, not `persist()`) must be used to update existing checkpoints.

**REQ-STATE-03:** Checkpoint status must be tracked via a `CheckpointStatus` enum: `IN_PROGRESS`, `COMPLETED`, `FAILED`.

**REQ-STATE-04:** `AgentCheckpoint` must include `failedAt` (timestamp) and `failReason` (String) fields for post-mortem diagnostics.

**REQ-STATE-05:** The `StatefulAgentStore` interface must expose: `save(checkpoint)`, `load(sessionId, role)`, `markFailed(sessionId, role, reason)`, `markCompleted(sessionId, role)`.

**REQ-STATE-06:** SQLite must be configured with WAL (Write-Ahead Logging) mode and `PRAGMA busy_timeout` via `SqliteWalConfigurer` to handle concurrent virtual thread write contention without deadlock.

**REQ-STATE-07:** Hibernate entity scanning must be explicitly configured: `quarkus.hibernate-orm.packages=com.arcclaw.core.state`. Without this, Hibernate in the app module does not discover entities defined in library modules.

---

### 4.7 Lifecycle Management Layer

**Purpose:** Manage the full workspace lifecycle — creation, heartbeat, garbage collection, and zombie cleanup — across JVM restarts.

**REQ-LIFE-01:** `WorkspaceManager` (`@ApplicationScoped`) must run a startup zombie scan (`@Startup`) that identifies `IN_PROGRESS` checkpoints from prior JVM sessions and marks them `FAILED` with a "JVM restart" reason. This prevents stale sessions from being resumed incorrectly.

**REQ-LIFE-02:** `WorkspaceManager` must run a periodic GC sweep (`@Scheduled`) that identifies and terminates workspaces that have exceeded their session TTL without receiving a heartbeat.

**REQ-LIFE-03:** `terminateWorkspace(sessionId)` must: destroy the `@WorkspaceScoped` CDI context for the session, clear the session's budget from `GovernanceLayer`, end the session's OTel root span, and update the checkpoint to `COMPLETED` or `FAILED`.

**REQ-LIFE-04:** Integration tests that use static `ConcurrentHashMap` stores (e.g., `WorkspaceManager` session maps) must implement `clearAll()` teardown in `@BeforeEach` or `@AfterEach` to prevent state leakage across test suite runs.

---

### 4.8 Governance Layer

**Purpose:** Enforce financial and operational guardrails per agent session to prevent runaway LLM cost and resource abuse.

**REQ-GOV-01:** `GovernanceLayer` must implement `AgentObserver` and maintain per-session budget tracking using: `AtomicInteger` for token counts and a `synchronized` accumulator for USD cost.

**REQ-GOV-02:** After each LLM call, `GovernanceLayer.recordLlmCall(sessionId, promptTokens, completionTokens, modelName)` must calculate and accumulate the USD cost using configurable per-model pricing rates.

**REQ-GOV-03:** When a session's cumulative USD cost exceeds the configured budget threshold, the system must: downgrade the session's `AutonomyLevel` to `READONLY` and throw `BudgetExceededException` to halt the agent loop.

**REQ-GOV-04:** `BudgetExceededException` must be a `RuntimeException` carrying the session ID, accumulated cost, and budget limit.

**REQ-GOV-05:** Session budget data must be cleared from the `GovernanceLayer` budget map when `WorkspaceManager.terminateWorkspace()` is called, to prevent unbounded memory growth.

**REQ-GOV-06:** The `AgentLoop` must iterate all registered `AgentObserver` implementations (via `Instance<AgentObserver>`) after each `ReasoningEngine.nextStep()` call. Both `GovernanceLayer` and `AgentOtelContext` must be in the observer list.

**REQ-GOV-07:** In addition to USD cost, the `GovernanceLayer` must enforce a **Cumulative Context Token Limit** per session (configurable, e.g., 100,000 tokens). As the agent loop accumulates reasoning steps — each contributing prompt tokens back to the LLM on the next iteration — the total can exceed the LLM's hard context window even when individual tool observations are truncated to 4,000 characters. If the session's cumulative prompt token count exceeds the configured limit, the system must throw a `ContextExhaustedException` and gracefully halt the agent loop. This prevents unrecoverable `HTTP 400 Bad Request` crashes from the LLM provider caused by context window overflow across multi-iteration sessions.

---

### 4.9 Observability & OTel Layer

**Purpose:** Provide session-correlated distributed traces, structured logs, and health-correlated telemetry.

**REQ-OTEL-01:** `AgentOtelContext` (`@ApplicationScoped`) must create a **root OTel span** when a workspace session begins and end it when the session is terminated. All child operations (LLM calls, tool executions, sub-agent loops) must be tracked as child spans under this root.

**REQ-OTEL-02:** Each sandbox tool execution in `ProcessIsolationExecutor` must create a child span with attributes: container name, isolation strategy, and exit code.

**REQ-OTEL-03:** OTel spans must not leak. `WorkspaceManager.terminateWorkspace()` must call `agentOtelContext.endSessionSpan(sessionId)` as part of cleanup.

**REQ-OTEL-04:** For testing OTel instrumentation, `@InjectMock Tracer` must be used instead of `InMemorySpanExporter`. The Quarkus OTel extension does not expose test exporters as CDI beans. Verification is done via Mockito `verify(tracer).spanBuilder(...)`.

**REQ-OTEL-05:** The OpenTelemetry collector must be configured to ingest via OTLP gRPC and forward to Jaeger for trace visualisation. Configuration is managed via `otel-collector-config.yaml`.

---

### 4.10 Health Layer

**Purpose:** Expose Kubernetes-compatible liveness and readiness probes that reflect the actual availability of the AI sandbox runtime.

**REQ-HEALTH-01:** A `PodmanHealthCheck` (`@Liveness`) must verify Podman binary availability by running `podman --version` with a 2-second timeout. Failure = liveness failure.

**REQ-HEALTH-02:** A `PodmanImageHealthCheck` (`@Readiness`) must verify that the required sandbox container image is present locally via `podman image exists` with a 2-second timeout. Failure = readiness failure (application is not ready to process tool-based agent requests).

**REQ-HEALTH-03:** Health endpoints are served on the **Quarkus management interface** (port **9001** in test mode, **9000** in production). They are NOT served on the main HTTP port (8080/8081). This is a Quarkus 3.x architectural decision. RestAssured tests must target port 9001 explicitly.

**REQ-HEALTH-04:** For testing health checks in multi-module projects, `@Alternative @Priority(1)` standalone `HealthCheck` implementations must be used as mocks. The following approaches must NOT be used:
- `@InjectMock ConcreteHealthCheck` — ArC indexes by interface, not concrete class.
- `@Mock extends ConcreteHealthCheck` — ArC-generated `_Bean.doCreate()` code runs in the app classloader and cannot access `@ConfigProperty` fields defined in the core module classloader.
- `@InjectMock @Liveness HealthCheck` — Ambiguous with built-in `DataSourceHealthCheck`.

---

### 4.11 REST API Layer

**Purpose:** Expose a minimal, virtual-thread-backed HTTP API for session lifecycle management and agent execution.

**REQ-REST-01:** All REST endpoints must be annotated with `@RunOnVirtualThread`. Agent loops are I/O bound (LLM wait, sandbox process wait). Blocking Quarkus worker or event-loop threads is not acceptable.

**REQ-REST-02:** All request DTOs must use `@Valid` bean validation. Blank or null required fields must return `HTTP 400` with a validation error body.

**REQ-REST-03:** The base API path must be `/api/v1`.

**REQ-REST-04:** The REST API must expose the following endpoints (see Section 6 for full contract):
- `POST /api/v1/sessions` — Create session
- `POST /api/v1/sessions/{id}/run` — Synchronous agent execution (blocking)
- `POST /api/v1/sessions/{id}/run-async` — Asynchronous agent execution (non-blocking)
- `GET /api/v1/sessions/{id}/status/{role}` — Poll agent status
- `DELETE /api/v1/sessions/{id}` — Terminate session

---

## 5. Non-Functional Requirements

### 5.1 Performance

| Metric | Target | Notes |
|---|---|---|
| **Cold start (JVM mode)** | < 3 seconds | Quarkus fast-start with AOT class loading |
| **Cold start (native mode)** | 30–50 ms | GraalVM native binary, ~30 MB RSS |
| **Native binary size** | ~30 MB | Larger than Rust ZeroClaw (~8.8 MB) due to JVM overhead; acceptable for cloud deployments |
| **Peak RAM (native)** | 15–30 MB RSS | Baseline idle; grows with active agent sessions and SQLite caches |
| **Concurrent agent sessions** | Thousands | Virtual threads (Project Loom) provide near-zero overhead per concurrent session |

### 5.2 Reliability

**REQ-NFR-RELY-01:** The system must resume interrupted agent sessions after JVM restart. No manual intervention must be required. `WorkspaceManager` performs this automatically at startup.

**REQ-NFR-RELY-02:** Sandbox execution failures must not crash the JVM. `ProcessIsolationExecutor` must handle all `ProcessBuilder` exceptions, log them, and return a failed `ToolResponse`.

**REQ-NFR-RELY-03:** The Podman circuit breaker must prevent cascading failures when the container runtime is unavailable. After 3 failures, automatic fallback to namespace isolation must occur.

**REQ-NFR-RELY-04:** SQLite WAL mode and `busy_timeout` must be configured to prevent `SQLITE_BUSY` errors under concurrent virtual thread write loads.

### 5.3 Security

**REQ-NFR-SEC-01:** No secret shall ever be materialised as a Java `String` object. `byte[]` arrays must be used throughout the cryptographic pipeline.

**REQ-NFR-SEC-02:** Ephemeral env-files used for secret injection into sandboxed containers must be deleted in `finally` blocks regardless of execution outcome.

**REQ-NFR-SEC-03:** The Docker daemon socket must never be mounted into the ArcClaw container.

**REQ-NFR-SEC-04:** Native image builds must verify that BouncyCastle's DRBG is seeded at runtime, not build time. Two consecutive binary runs must produce different cryptographic nonces.

**REQ-NFR-SEC-05:** Sandbox containers must run with `--cap-drop=ALL --network=bridge --read-only` enforced. Resource caps (`--memory`, `--cpus`, `--pids-limit`, `--ulimit`) must prevent fork bomb or resource exhaustion attacks.

### 5.4 Portability

**REQ-NFR-PORT-01:** ArcClaw must run on Linux (native target), macOS (development), and Windows via WSL2. It cannot run natively on the Windows NT kernel due to Linux user namespace requirements.

**REQ-NFR-PORT-02:** Windows development environments must use Podman Desktop with WSL2 backend. Windows developers using Podman Desktop must either enable **"Docker Compatibility Mode"** (Docker API) in Podman Desktop Settings, or set the Docker host environment variable before running Compose commands: `$env:DOCKER_HOST="npipe:////./pipe/podman-machine-default"`. Alternatively, a PowerShell alias (`Set-Alias -Name docker -Value podman`) ensures standard build scripts and Compose commands route to the Podman engine correctly. Without one of these steps, `docker compose` commands will fail with `open //./pipe/dockerDesktopLinuxEngine`.

**REQ-NFR-PORT-03:** When Podman is running inside a container (nested containerisation), the VFS storage driver must be configured in `containers.conf`. Standard `overlayfs` fails in unprivileged mount scenarios. VFS creates full copies of images — `podman system prune -f` must be run periodically.

---

## 6. API Contract

### POST /api/v1/sessions
Create a new agent workspace session.

**Request:** No body required.
**Response 201:**
```json
{ "sessionId": "<uuid>" }
```

---

### POST /api/v1/sessions/{sessionId}/run
Execute an agent synchronously. Blocks until the agent loop completes or hits `maxIterations`.

**Request Body:**
```json
{
  "goal": "string (required, non-blank)",
  "agentRole": "string (required — coordinator | researcher | coder | debugger)",
  "maxIterations": "integer (optional, default: 5, max: 20)"
}
```
**Response 200:** Full `AgentResult` with reasoning steps.
**Response 400:** Bean validation failure (blank goal or role).
**Response 404:** Session not found.

---

### POST /api/v1/sessions/{sessionId}/run-async
Execute an agent asynchronously. Returns immediately; poll `/status` for results.

**Request Body:** Same as `/run`.
**Response 202 Accepted:** Empty body. Agent runs in background.

---

### GET /api/v1/sessions/{sessionId}/status/{role}
Poll the status of an agent execution for a specific role within a session.

**Response 200:**
```json
{
  "sessionId": "uuid",
  "agentRole": "coordinator",
  "status": "IN_PROGRESS | COMPLETED | FAILED",
  "steps": [ /* ReasoningStep array */ ]
}
```
**Response 404:** No checkpoint found for this session + role combination.

---

### DELETE /api/v1/sessions/{sessionId}
Terminate a session, releasing all associated CDI beans, budget data, OTel spans, and SQLite state.

**Response 204:** Session terminated successfully.

---

## 7. Configuration Requirements

All configuration is managed in `application.yaml`. Key configuration areas:

### 7.1 LLM Provider

```yaml
quarkus:
  langchain4j:
    chat-model:
      provider: openai   # Must be set explicitly when multiple providers are on the classpath
    openai:
      base-url: http://ollama:11434/v1   # Ollama compatibility via OpenAI-compatible API
      chat-model:
        model-name: ${OLLAMA_MODEL:llama3.2}
```

**Important:** The `quarkus.langchain4j.openai.chat-model.model-name` property must be used, NOT `quarkus.langchain4j.openai.model-name`. The latter causes a fallback to `gpt-4o-mini`.

**Dev Mode vs. Native Profile Split:** Local Dev Mode (`%dev` profile) may use the `ollama` provider directly for ease of use with Quarkus DevServices. However, the **production and native profiles must override this to the `openai` provider** mapped to the Ollama endpoint (`http://ollama:11434/v1`). This bypasses the native compilation reflection-stripping bug in the `quarkus-langchain4j-ollama` extension. Do not assume the `%dev` `application.yaml` provider setting reflects the production/native configuration — they differ intentionally.

**Native Image Note (Critical):** The `quarkus-langchain4j-ollama` extension is broken for native compilation in Quarkus 3.23.0. It locks the model ID at build time and strips JSON reflection schemas. The `openai` provider mapping to the Ollama endpoint is the correct native-mode approach and must NOT be reverted.

### 7.2 Database

```yaml
quarkus:
  datasource:
    db-kind: sqlite
    jdbc:
      url: jdbc:sqlite:${ARCCLAW_DATA_DIR}/arcclaw.db
  hibernate-orm:
    packages: com.arcclaw.core.state
    database:
      generation: none   # MUST be 'none' in production
  flyway:
    migrate-at-start: true   # Schema evolution managed by Flyway migrations
```

> **⚠️ Critical Production Note:** `generation: update` is permitted only in the `%dev` profile for rapid local iteration. In production and staging environments, `generation: none` is mandatory. Allowing Hibernate to auto-mutate schema on startup risks locking tables, silently dropping constraints, or applying irreversible DDL during a rolling deployment. All schema evolution must be managed through explicit, version-controlled Flyway (or Liquibase) migration scripts applied at start via `migrate-at-start: true`.

### 7.3 Governance Budgets

Configurable per deployment:
- Max tokens per session
- Max USD cost per session
- Per-model USD cost rates (prompt and completion tokens)

### 7.4 Sandbox

```yaml
arcclaw:
  sandbox:
    strategy: PODMAN   # PODMAN | NAMESPACE | NONE
    image: arcclaw-tool-runner:latest
    memory: 512m
    cpus: 0.5
    pids-limit: 64
```

### 7.5 Context Propagation (Required)

```yaml
mp:
  context:
    propagators: Quarkus, CDI, JAX-RS
```

This is mandatory. Without it, `@WorkspaceScoped` beans and OTel contexts are silently null on virtual threads.

### 7.6 JVM/Maven Arguments (Required for JDK 25)

The following must be set in `MAVEN_OPTS` and Surefire plugin configuration:

```
-Dnet.bytebuddy.experimental=true
--add-opens java.base/java.lang=ALL-UNNAMED
```

---

## 8. Deployment Requirements

### 8.1 Docker Compose Stack

The production stack consists of four services:

| Service | Image | Purpose |
|---|---|---|
| `arcclaw` | `arcclaw:native` | Main application (native binary) |
| `ollama` | `ollama/ollama` | Local LLM inference endpoint |
| `ollama-init` | `ollama/ollama` (ephemeral) | One-shot sidecar that automatically pulls the configured LLM model weights (e.g., `llama3.2:1b`) before the main service boots. Zero-touch model provisioning. |
| `otel-collector` | `otel/opentelemetry-collector` | OTLP gRPC ingestion and Jaeger forwarding |
| `jaeger` | `jaegertracing/all-in-one` | Distributed trace visualisation (UI: port 16686) |

### 8.2 SQLite Data Volume Permissions

The `arcclaw` container runs as a non-root user (UID 1000). Docker-created named volumes are root-owned by default, causing `Permission denied` on SQLite writes.

**Solution:** An `init-perms` ephemeral service in `docker-compose.yaml` must run as root, execute `chown -R 1000:1000 /app/data` on the volume, and exit before the main service starts.

### 8.3 Dockerfile Requirements

- Base image: UBI 9 minimal
- Create `arcclaw` user (UID 1000) with custom subuids/subgids for nested rootless Podman
- Copy and embed Podman binary into the image
- Set `containers.conf` with VFS storage driver
- Switch to non-root user before `ENTRYPOINT`
- `chown -R` over `/app` must occur before user context switch

### 8.4 OTel Port Configuration

Jaeger port 4317 (OTLP gRPC) must be declared as `expose:` (internal only) in `docker-compose.yaml`, not `ports:`. Publishing 4317 to the host creates a conflict with the OTel Collector, which also listens on 4317 for ingestion.

### 8.5 Environment Variables

All secrets and runtime configuration must be provided via a `.env` file (`.env.example` template committed to the repository):

```
GITHUB_TOKEN=<token>
OLLAMA_BASE_URL=http://ollama:11434
OLLAMA_MODEL=llama3.2
ARCCLAW_DATA_DIR=/app/data
ARCCLAW_BUDGET_USD=1.00
```

| Variable | Default | Purpose |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Points to the local or remote Ollama LLM provider. |
| `OLLAMA_MODEL` | `llama3.2` | The model name passed to Ollama. `llama3.2:1b` is recommended for local testing — fully offline, free. |
| `GITHUB_TOKEN` | *(empty)* | Required when using `SaraswatiAgent` or any tool annotated `@RequiresSecret(GITHUB_TOKEN)`. |
| `ARCCLAW_DATA_DIR` | `./data` | Host path where SQLite databases and workspace files are stored. Must exist before startup on Windows dev mode. |
| `ARCCLAW_BUDGET_USD` | `1.00` | Per-session USD budget cap enforced by `GovernanceLayer`. |

### 8.6 Native Build Command

```bash
mvnw clean package -pl arcclaw-app -Pnative -am
```

The `-am` (Also Make) flag is **mandatory**. Without it, changes in `arcclaw-core` are not recompiled before the native binary is linked, resulting in stale class linking errors.

---

## 9. Testing Requirements

### 9.1 Test Coverage by Phase

| Phase | Test Class | Coverage |
|---|---|---|
| 1 — Scope Layer | `WorkspaceScopeTest` | Session isolation, concurrency across virtual threads |
| 2 — Security | `SecretVaultImplTest` | Encrypt, decrypt, rotation, session isolation |
| 3 — Sandbox | `ProcessIsolationExecutorTest` | NONE strategy (3 tests) |
| 4 — Tools | `ToolRegistryTest` | Discovery, governance, reflection, error handling (7 tests) |
| 5 — Agents | `AgentResilienceTest` | Hallucination, loop limit, FINISH, null params, exception (5 tests) |
| 6 — State | `StatefulAgentStoreTest` | Save+load, markFailed, markCompleted, upsert (4 tests) |
| 7 — Lifecycle | `WorkspaceManagerTest` | Zombie recovery, GC sweep, termination, multi-role (4 tests) |
| 8 — Governance | `GovernanceLayerTest` | Cost accumulation, READONLY trigger, BudgetExceededException (3 tests) |
| 9 — OTel | `AgentOtelContextTest` | Span creation, attribute tagging (2 tests) |
| 10 — Health | `PodmanHealthCheckTest` | Liveness on port 9001, readiness on port 9001 (2 tests) |
| 11 — REST | `AgentResourceTest` | Sync lifecycle, async 202 return, validation 400 (3 tests) |
| 12 — LangChain | `LangChainReasoningEngineTest` | JSON hallucination recovery, successful completion (2 tests) |

### 9.2 Testing Patterns — Decision Matrix

| Bean Type | Mock Strategy | Port |
|---|---|---|
| Regular CDI beans (`@ApplicationScoped`) | `@InjectMock` | 8081 |
| `HealthCheck` beans (`@Liveness`/`@Readiness`) | `@Alternative @Priority(1)` standalone implementation | 9001 |
| `ReasoningEngine` (to avoid live Ollama) | `@InjectMock ReasoningEngine` | 8081 |
| OTel `Tracer` | `@InjectMock Tracer` + Mockito `verify()` | N/A |
| `WorkspaceScopeContext` | `Arc.container().getActiveContext()` — never `@InjectMock` | N/A |

### 9.3 Test Execution Commands

```bash
# Unit tests (core module only)
mvnw test -pl arcclaw-core

# Full test suite
mvnw clean verify -pl arcclaw-app -am

# Dev mode (hot reload)
mvnw quarkus:dev -pl arcclaw-app
```

### 9.4 Test Isolation Requirements

- Tests that interact with application-scoped static maps (e.g., `WorkspaceManager` session map) must call a `clearAll()` teardown in `@BeforeEach` or `@AfterEach`.
- Tests must always call `WorkspaceScopeHolder.set(sessionId)` in `@BeforeEach` and `WorkspaceScopeHolder.clear()` in `@AfterEach`.
- `mvn clean test` (not `mvn test`) must be used when switching between incremental builds across modules to prevent stale class files in `arcclaw-deployment/target/`.

---

## 10. Operational Constraints & Known Boundaries

### 10.1 Confirmed Architectural Constraints

| Constraint | Detail |
|---|---|
| **SQLite is single-node only** | SQLite WAL mode supports concurrent reads/writes within one JVM. Multi-node deployment is not supported without migrating to PostgreSQL or equivalent. |
| **Podman must be embedded** | The Docker daemon socket must never be mounted. Podman must be embedded in the container image for nested execution. |
| **Windows requires WSL2** | Linux user namespaces are required for sandbox isolation. The Windows NT kernel does not support them natively. |
| **LangChain4J version is fixed** | The `langchain4j-bom` must not be imported separately. Quarkus BOM manages the version transitively. Independent imports cause `NoClassDefFoundError` due to package migrations between versions. |
| **Ollama native provider is broken** | `quarkus-langchain4j-ollama` extension does not work in native mode under Quarkus 3.23.0. The `openai` provider mapped to the Ollama endpoint (`http://ollama:11434/v1`) is the required workaround and must not be reverted. |
| **SQLite JDBC Directory Creation** | The SQLite JDBC driver creates the database file but refuses to create missing parent directories. On Windows dev mode, the `./data` directory must exist before launching Quarkus. Developers must run `mkdir data` in the `arcclaw-app` directory prior to `quarkus:dev`, or set `ARCCLAW_DATA_DIR` to an absolute path that already exists. Failure produces `java.sql.SQLException: path to './data/arcclaw-meta.db' does not exist`. |
| **Ollama Port Collision** | If Ollama is already running natively on the host (macOS/Windows), `docker compose up` will fail with `bind: address already in use` on port 11434. Either quit the local Ollama application before starting the Compose stack, or remap the Ollama port in `docker-compose.yaml` (e.g., `"11435:11434"`). |
| **Strict Singleton Topology** | Because `WorkspaceManager` runs a destructive `@Startup` zombie sweep that marks all `IN_PROGRESS` checkpoints as `FAILED`, **ArcClaw must never be horizontally scaled against a shared SQLite file**. If two ArcClaw instances share the same SQLite database (e.g., via a network-mounted volume), Instance B booting will immediately destroy all active agent sessions running on Instance A. Multi-instance scaling is strictly prohibited without migrating to PostgreSQL and implementing row-level distributed lock tracking. |

### 10.2 Performance Boundaries vs. ZeroClaw (Rust)

| Metric | ZeroClaw (Rust) | ArcClaw V1.0.0 (Native) | Gap |
|---|---|---|---|
| Binary size | 8.8 MB | ~30 MB | 3.4× — accepted |
| Cold start | < 10 ms | 30–50 ms | 3–5× — accepted for Java |
| Peak RAM | ~4 MB RSS | ~15–30 MB RSS | 4–8× — accepted for Java |

These gaps are considered acceptable given the Java ecosystem's tooling, maintainability, and LangChain4J integration advantages.

### 10.3 Out-of-Scope Items (V1.0.0)

| Feature | Status | Notes |
|---|---|---|
| Multi-device session pairing | Partial | Session resumption via `parentSessionId` implemented; full device pairing is a follow-on |
| AIEOS identity integration | Out of scope | No Java AIEOS SDK available as of February 2026 |
| Multi-node clustering | Out of scope | Requires SQLite → PostgreSQL migration |
| Prometheus metrics endpoint | Planned | `AgentObserver` + Micrometer scaffolding in place |

---

## 11. Feature Parity Baseline

The following table tracks ArcClaw's feature parity against the ZeroClaw Rust reference implementation:

| Feature | ZeroClaw Rust | ArcClaw V1.0.0 Status |
|---|---|---|
| Trait-driven pluggable kernel | Provider/Tool traits | ✅ CDI interfaces + qualifiers |
| 22+ LLM providers | Multiple | ✅ LangChain4J `ChatLanguageModel` |
| Tool system | OS-level execution | ✅ `AgentTool` + `ToolRegistry` + `ProcessIsolationExecutor` |
| OS-level sandbox | Docker containers | ✅ Podman rootless / Linux namespaces |
| Memory / State | File-backed | ✅ SQLite WAL + `StatefulAgentStore` |
| Autonomy levels | readonly/supervised/full | ✅ `AutonomyPolicy` + `GovernanceLayer` |
| Suspend and Resume | Checkpoint file | ✅ `AgentCheckpoint` + startup re-hydration |
| Financial governance | Not present | ✅ **Exceeds parity** — `GovernanceLayer` with USD tracking |
| Observer pipeline | Log/Prometheus/OTel | ✅ `AgentObserver` + Micrometer + OTel |
| Workspace scoping + GC | Implicit in Rust ownership | ✅ `@WorkspaceScoped` + `WorkspaceManager` |
| Secret vault | ChaCha20-Poly1305 | ✅ AES-256-GCM-SIV (stronger nonce-misuse resistance) |
| Secret injection into sandbox | Environment injection | ✅ `@RequiresSecret` + ephemeral env-file + tmpfs |
| Session-bound OTel traces | Not present | ✅ `AgentOtelContext` root span + child spans |
| Zombie workspace cleanup | OS process cleanup | ✅ `WorkspaceManager.onStartup()` scan |
| Sandbox health probes | Not present | ✅ `PodmanHealthCheck` + `PodmanImageHealthCheck` |
| Multi-device pairing | Partial | ⚠️ Follow-on work |
| < 5 MB RAM / < 10 ms start | Rust native | ⚠️ ~15–30 MB RSS / ~30–50 ms — near but not Rust-equivalent |

---

## 12. Roadmap & Phases

### Phase 1–10: ArcClaw V1.0.0 (Core Kernel)
* **Status:** ✅ Completed and Baselined.
* **Features:** CDI Sandbox, REST API, Financial Governance, Vault Secrets, OpenTelemetry, JDBC SQLite Checkpointing, Multi-Agent Tool Registry.

### Phase 11: ArcClaw V1.0.0 (Multi-Channel & Human-in-the-Loop)
* **Status:** ✅ Completed.
* **Sprint 1 (Dual-Mode Binary):** Implemented Picocli integration allowing the single compiled artifact to run as a transient CLI task (`ask`) or a background daemon (`serve`).
* **Sprint 2A (Telegram Channel):** Integrated `camel-quarkus-telegram`. Implemented non-blocking Vert.x to Virtual Thread handoff, mapping Telegram `chatId` deterministically to SQLite workspace sessions.
* **Sprint 2B (Human-in-the-Loop State Machine):** Implemented the `/approve` and `/deny` flow. Refactored `AgentLoop` to cleanly exit on `RequiresApprovalException`, saving pending tool invocations to SQLite (`AWAITING_APPROVAL`), allowing asynchronous resumption without thread parking.
* **Sprint 2C (Dynamic Role Routing):** Implemented a keyword-based Intent Router in the Telegram handler to dynamically route user prompts to `brahma`, `saraswati`, or `coordinator` agents.

### Phase 12: ArcClaw V1.0.0 (State Consistency, Observability & Hardening)
* **Status:** ✅ Completed (Sprint 3 & 4)
* **Sprint 3A (Persistent ChatMemory):** ✅ **[CRITICAL ARCHITECTURAL FIX]** Implemented a custom LangChain4j `ChatMemoryStore` backed by the existing JDBC SQLite database. Resolves "split-brain" state issues and enables "Task Amnesia" for Telegram reliability.
* **Sprint 3B (LLM Intent Router):** ✅ Replace the brittle keyword-based `determineAgentRole` heuristic with a fast, low-latency LLM classification call (`llama-3.1-8b-instant` via Groq) to accurately route ambiguous prompts.
* **Sprint 3C (Prometheus Metrics):** ✅ Implemented Micrometer/Prometheus integration. Metrics exposed at `/q/metrics` for token tracking and role-based invocation counting.
* **Sprint 3D (ShivaAgent Integration):** ✅ Enhanced Intent Router with `shiva` (debugger) role to distinguish between code creation and bug fixing.
* **Sprint 3E (Discord Adapter):** [➖] Deferred due to library deprecation (See [ARCHITECTURAL_DECISIONS.md](file:///h:/ws/ArcClaw/ARCHITECTURAL_DECISIONS.md)).

### Phase 24: ArcClaw V1.0.0 (Tool Bias & Context Hardening)
* **Status:** ✅ Completed.
* **Strict Tool Binding:** ✅ Implemented `STRICT TOOL BINDING CONTRACT` injection in `ReasoningEngine`. Forcefully restricts `brahma` (coder) and `shiva` (debugger) to text-only output, eliminating tool bias.
* **Context Reset Command:** ✅ Added `/reset` to Telegram Channel. Purges `WorkspaceScope` and `PersistentChatMemoryStore` to allow clean-slate verification of multi-turn logic.

### Phase 27: ArcClaw V1.0.0 (Security Hardening & Secret Injection Pipeline)
* **Status:** ✅ Completed.
* **Fail-Closed Secrets:** ✅ Enforced strict vault lookups by removing legacy `System.getenv()` fallbacks in the sandbox executor.
* **Process Exit Robustness:** ✅ Addressed silent subprocess failures by explicitly validating `proc.exitValue()` and `waitFor()` timeouts.
* **Concurrency Hardening:** ✅ Eliminated `VaultKey` memory clearance race conditions by marking the `destroyed` flag `volatile` for virtual thread visibility.
* **Secure Pipeline:** ✅ Replaced `String` materialization of credentials with a direct `byte[]` pipeline, fortified by Atomic POSIX file permissions on tmpfs.
* **Developer Experience:** ✅ Added `DevSecretSeeder` to automate SQLite token hydration inside the `%dev` profile.

#### Tool Flexibility & Proxy Unwrapping
* **Status:** ✅ Completed.
* **CDI Proxy Transparency:** ✅ Refactored `ToolRegistry.extractRequiredSecrets` to climb the class hierarchy, bypassing Quarkus `_ClientProxy` wrappers.
* **Dynamic Tool Commands:** ✅ Overhauled `GithubTool.java` to accept dynamic actions and targets.

### Phase 28: ArcClaw V1.0.0 (Intent Hardening & Auto-Cancellation)
* **Status:** ✅ Completed.
* **Auto-Cancel Pipeline:** ✅ Implemented proactive scanning in `TelegramAgentHandler` to wipe dangling `AWAITING_APPROVAL` checkpoints.
* **Routing Guardrails:** ✅ Hardened `IntentClassifierService` to prioritize Actions (Create) over Context (GitHub).

### Phase 29: ArcClaw V1.0.0 (Memory Optimization & History Filtering)
* **Status:** ✅ Completed.
* **Rolling Window:** ✅ Implemented identity-aware memory pruning (n=20) in `PersistentChatMemoryStore` to preserve system persona.
* **History Scrubbing:** ✅ Sanitized reasoning history in `LangChainReasoningEngine` to filter out internal orchestration markers.

*ArcClaw V1.0.0 Requirements — Baselined from production implementation.*
*Source documents: ArcClaw-v1_0-CONSOLIDATED.md, TASKS.md, DEVIATIONS.md, WALKTHROUGH.md, TROUBLESHOOTING.md, FAQ.md, README.md, claw-ecosystem.md.*
*Audit trail: 5 LLMs, 4+ review passes, 21 unique risks resolved, 7 factual corrections applied.*
*Post-baseline review pass 1 (Gemini): 4 corrections — ShivaAgent role → debugger, Windows SQLite dir constraint, Podman Desktop Docker compat, dev/native LLM profile split.*
*Post-baseline review pass 2 (Gemini — Security/Ops): 5 patches — POSIX 600 env-file hardening (REQ-SAND-04), singleton topology constraint (10.1), Hibernate → Flyway in production (7.2), maxIterations API bounds (Section 6), cumulative context token limit (REQ-GOV-07).*
*Section 12 updated: Baselined after full implementation of Multi-Channel & Human-in-the-Loop flow.*
