# Phase 0 — Project Scaffolding & Build Infrastructure

> **Cross-Reference**: [TASKS.md](./TASKS.md) | [DEVIATIONS.md](./DEVIATIONS.md) | [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) | [WALKTHROUGH.md](./WALKTHROUGH.md) | [FAQ.md](./FAQ.md)

Establish the multi-module Maven reactor, build profiles, and core dependency management (Quarkus 3.23.0 + JDK 25 + LangChain4J).

## Design Decisions

> [!IMPORTANT]
> **Multi-Module Architecture**:
> - `arcclaw-parent`: BOMs and compiler definitions
> - `arcclaw-deployment`: Quarkus build-time processors (CDI extensions)
> - `arcclaw-core`: Business logic, domain entities, CDI implementation
> - `arcclaw-app`: Runtime runner, configuration, and tests

> [!NOTE]
> **JDK 25 Alignment**: Build configured with `-Dnet.bytebuddy.experimental=true` and `--add-opens` to support Java 25 preview features during tests.

## Completed Changes

### Build Configuration

#### [NEW] [pom.xml (Parent)](file:///h:/ws/ArcClaw/pom.xml)
- `quarkus-bom`: 3.23.0
- `maven-compiler-plugin`: source/target 21 (tested on 25)

#### [NEW] [application.yaml](file:///h:/ws/ArcClaw/arcclaw-app/src/main/resources/application.yaml)
- Profiles: `dev`, `test`, `prod`
- Config: HTTP, SQLite, OTel, LangChain4J, Scheduler

## Verification

- ✅ `mvn clean compile` succeeds
- ✅ `mvn test` runs Failsafe/Surefire correctly
- ✅ Native image build config (`native-image.properties`) valid

---

# Phase 1 — Core CDI Scope Layer

Implement `@WorkspaceScoped` custom CDI scope for ArcClaw. This scope ties bean lifecycle to a workspace session ID, enabling per-session bean isolation across concurrent virtual threads.

## Design Decisions

> [!IMPORTANT]
> **JDK 25 Optimization**: Using `synchronized` for context store access (not `ReentrantLock`) per JEP 491 — virtual threads no longer pin platform threads on synchronized blocks, making `synchronized` the simpler and correct choice.

> [!NOTE]
> **No `WorkspaceScopeExtension` needed**: Quarkus CDI Lite does not use portable extensions. Scope registration is handled entirely via `@BuildStep` in the deployment module using `ContextRegistrationPhaseBuildItem`.

> [!NOTE]
> **No `WorkspaceScopeRecorder` needed**: `ContextConfigurator.contextClass()` registers the `InjectableContext` implementation directly — no runtime recorder indirection required.

## Proposed Changes

### Documentation

#### [NEW] [TROUBLESHOOTING.md](file:///h:/ws/ArcClaw/TROUBLESHOOTING.md)
- Master Architecture & Troubleshooting Guide
- Consolidated failure modes (Build, CDI, Security, Sandbox, Native)
- Key Design Decisions & Initial Challenges

### Core Module — Scope Annotation & Runtime

#### [NEW] [jandex.idx](file:///h:/ws/ArcClaw/arcclaw-core/src/main/resources/META-INF/jandex.idx)
- Jandex index generated via `jandex-maven-plugin` to enable CDI bean discovery in core module

#### [NEW] [WorkspaceScoped.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/scope/WorkspaceScoped.java)
- CDI scope annotation: `@NormalScope(passivating = false)`, `@Inherited`
- Retention `RUNTIME`, targets `TYPE`, `METHOD`, `FIELD`

#### [NEW] [WorkspaceScopeHolder.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/scope/WorkspaceScopeHolder.java)
- Static `ThreadLocal<String>` holding current workspace session ID
- `set(sessionId)`, `get()`, `clear()` methods
- JDK 25 `ScopedValue` migration comments (JEP 506)

#### [NEW] [WorkspaceScopeContext.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/scope/WorkspaceScopeContext.java)
- Implements `io.quarkus.arc.InjectableContext`
- **Outer store**: `ConcurrentHashMap<String, SessionStore>` — maps sessionId → per-session store
- **Inner `SessionStore`**: uses `synchronized` (JEP 491) for bean instance map access
- **`ContextState`**: snapshot of current session's bean instances
- Methods: `get(Contextual)`, `get(Contextual, CreationalContext)`, `getScope()`, `isActive()`, `destroy(Contextual)`, `destroy()`, `getState()`, `activate(sessionId)`, `deactivate(sessionId)`

---

### Deployment Module — Build-Time Processor

#### [NEW] [WorkspaceScopeProcessor.java](file:///h:/ws/ArcClaw/arcclaw-deployment/src/main/java/com/arcclaw/deployment/scope/WorkspaceScopeProcessor.java)
- `@BuildStep customScope()` → returns `CustomScopeBuildItem(WorkspaceScoped.class)`
- `@BuildStep registerContext(ContextRegistrationPhaseBuildItem)` → `configure(WorkspaceScoped.class).normal().contextClass(WorkspaceScopeContext.class).done()`
- `@BuildStep unremovableBeans()` → returns `UnremovableBeanBuildItem` for `WorkspaceScopeContext`

---

### Core Module — Test Verification

#### [NEW] [WorkspaceScopeTest.java](file:///h:/ws/ArcClaw/arcclaw-app/src/test/java/com/arcclaw/core/scope/WorkspaceScopeTest.java)
- Moved to `arcclaw-app` to enable full Quarkus test bootstrap
- `@QuarkusTest` using `@Inject` of a `@WorkspaceScoped` test bean
- Verifies: same sessionId → same bean identity across 2 parallel threads
- Verifies: different sessionId → different bean identity
- Verifies: no sessionId → `ContextNotActiveException`

#### [NEW] [ScopedCounter.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/scope/ScopedCounter.java)
- Simple `@WorkspaceScoped` test bean with `increment()` and `getCount()` methods
- Moved to `src/main/java` so it is part of the core module index

## Verification Plan

### Automated Tests

---

# Phase 2 — Security Layer (Vault & Crypto)

Implement the encryption and secret storage layer using AES-GCM-SIV for nonce-misuse resistance and Argon2id for key derivation.

## Design Decisions

> [!IMPORTANT]
> **No `@Lob` for SQLite**: Standard `@Lob` on `byte[]` causes the Hibernate SQLite dialect to use `getBlob()`, which the driver does not support. Using standard `VARBINARY` (no `@Lob`) allows `getBytes()` which works correctly.

> [!TIP]
> **AES-GCM-SIV**: Chosen to prevent catastrophic security failure if a nonce is ever reused (though we use random 12-byte nonces).

> [!NOTE]
> **`VaultKey` Memory Management**: Implements `AutoCloseable` and uses `Arrays.fill(key, (byte) 0)` to clear sensitive material from memory as soon as possible.

## Proposed Changes

### Core Module — Crypto & Repository

#### [NEW] [VaultKey.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/VaultKey.java)
- Wrapper for `byte[]` AES keys with auto-wiping on `close()`.

#### [NEW] [Argon2idKeyDerivation.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/Argon2idKeyDerivation.java)
- Uses BouncyCastle to derive 256-bit keys from session IDs.

#### [NEW] [AesGcmSivCipher.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/AesGcmSivCipher.java)
- Encapsulates encryption/decryption logic.

#### [NEW] [EncryptedSecret.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/EncryptedSecret.java)
- JPA Entity with composite key (`sessionId`, `keyName`). `byte[]` fields mapped without `@Lob`.

#### [NEW] [EncryptedSecretRepository.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/EncryptedSecretRepository.java)
- Panache repository for secret storage.

### App Module — Service & Tests

#### [NEW] [SecretVaultImpl.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/SecretVaultImpl.java)
- Core service orchestration: `store`, `get`, `reEncrypt`.

#### [NEW] [WorkspaceContext.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/scope/WorkspaceContext.java)
- Session-scoped bean holding `AutonomyLevel` and attributes.

#### [NEW] [AutonomyPolicy.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/AutonomyPolicy.java)
- Interface and default logic for tool permission enforcement.

#### [NEW] [WorkspaceGovernanceTest.java](file:///h:/ws/ArcClaw/arcclaw-app/src/test/java/com/arcclaw/core/security/WorkspaceGovernanceTest.java)
- Verification: context injection, state isolation, policy checks.
- AutonomyLevels: READONLY, SUPERVISED, FULL.

## Verification Plan

### Automated Tests
```bash
$env:MAVEN_OPTS="-Dnet.bytebuddy.experimental=true --add-opens=java.base/java.lang=ALL-UNNAMED"
mvn test -pl arcclaw-app -Dtest=SecretVaultImplTest
```
- ✅ Encryption/Decryption round trip
- ✅ AEAD Tamper detection
- ✅ AAD Key swapping protection
- ✅ Active Session requirement enforced

---

# Phase 3 — Sandbox Layer (Process Isolation)

Implement isolated tool execution per the consolidated spec §5.2. Three strategies: Podman (primary), Linux Namespaces (fallback), NONE (dev).

## Design Decisions

> [!IMPORTANT]
> **Podman-First Strategy**: Podman is preferred for its rootless operation and strong OCI-based isolation. `@PostConstruct` checks availability; circuit breaker auto-degrades after 3 failures.

> [!TIP]
> **Ephemeral Secret Injection**: Secrets resolved from `SecretVault` → written to temp `.env` file → Podman reads via `--env-file` → file deleted in `finally`. Never passed as process args.

> [!NOTE]
> **OTel Stub**: `AgentOtelContext` returns `Span.getInvalid()` (no-op). Will be replaced with full session-span management in Phase 9.

> [!WARNING]
> **VaultKey API Adaptation**: Spec expects `String` returns from vault, but our `SecretVault.get()` returns `Optional<VaultKey>`. `resolveSecrets()` unwraps via try-with-resources to auto-wipe key bytes.

## Proposed Changes

### Core Module — Sandbox

#### [NEW] [ProcessIsolationExecutor.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/sandbox/ProcessIsolationExecutor.java)
- `@ApplicationScoped` executor with three strategies: PODMAN, NAMESPACE, NONE
- Circuit breaker: 3 consecutive Podman failures → auto-fallback to NAMESPACE
- Resource caps: `--memory`, `--cpus`, `--pids-limit=64`, `--ulimit=nofile=256:256`
- Named containers (`zc-<session>-<nonce>`) tracked in `ConcurrentHashMap.newKeySet()`
- `@PreDestroy` graceful shutdown with 10s grace period
- OTel child spans via `AgentOtelContext.childSpan()`

#### [NEW] [AgentOtelContext.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/observability/AgentOtelContext.java)
- Phase 9 stub: static `childSpan()` returning `Span.getInvalid()`

### Core Module — Annotations

#### [NEW] [RequiresSecret.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/RequiresSecret.java)
- `@Repeatable`, `@Retention(RUNTIME)`, `@Target(METHOD)`
- Fields: `envVar()` (container env name), `vaultKey()` (vault key name)

#### [NEW] [RequiresSecrets.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/RequiresSecrets.java)
- Container annotation for `@RequiresSecret`

### App Module — Tests

#### [NEW] [ProcessIsolationExecutorTest.java](file:///h:/ws/ArcClaw/arcclaw-app/src/test/java/com/arcclaw/core/sandbox/ProcessIsolationExecutorTest.java)
- NONE strategy direct execution test
- Empty secrets map handling test
- Invalid command error handling test

## Verification Plan

### Automated Tests
```bash
$env:MAVEN_OPTS="-Dnet.bytebuddy.experimental=true --add-opens=java.base/java.lang=ALL-UNNAMED"
mvn clean test -pl arcclaw-app -am -Dtest=ProcessIsolationExecutorTest "-Dsurefire.failIfNoSpecifiedTests=false"
```
- ✅ NONE strategy direct execution
- ✅ Empty secrets map handling
- ✅ Invalid command error handling

---

# Phase 4 — Tool Layer

Build the tool abstraction, CDI-based discovery registry with governance enforcement, and a reference `GithubTool` implementation.

## Design Decisions

> [!IMPORTANT]
> **`AutonomyPolicy.Default` not CDI-managed**: Instantiated directly in `ToolRegistry` — stateless logic, no injection needed. Can be refactored to `@Produces` later.

> [!NOTE]
> **`@RequiresSecret` reflection**: `ToolRegistry.extractRequiredSecrets()` reads annotations from the tool's `execute(Map)` method to build the env-var → vault-key mapping for `ProcessIsolationExecutor`.

> [!TIP]
> **`ToolResponse` as `record`**: Immutable, native-image safe, with `success()`/`failure()` factory methods.

## Proposed Changes

### Core Module — Tool Abstraction

#### [NEW] [ToolResponse.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/tools/ToolResponse.java)
- Java record: `exitCode`, `output`, `success`
- Factory methods: `success(String)`, `failure(int, String)`

#### [NEW] [AgentTool.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/tools/AgentTool.java)
- Interface: `name()`, `description()`, `requiredOperation()` → `ToolOperation`, `execute(Map)`

#### [NEW] [ToolRegistry.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/tools/ToolRegistry.java)
- `@ApplicationScoped`, CDI `Instance<AgentTool>` discovery
- `invokeTool()`: governance check → tool execution
- `extractRequiredSecrets()`: reflective `@RequiresSecret` annotation reading

### Core Module — Reference Tool

#### [NEW] [GithubTool.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/tools/GithubTool.java)
- `@ApplicationScoped`, `@RequiresSecret(envVar="GITHUB_TOKEN", vaultKey="GITHUB_TOKEN")`
- Runs `gh repo view <repo>` via `ProcessIsolationExecutor`
- `requiredOperation()` → `NETWORK_OUTBOUND`

### App Module — Tests

#### [NEW] [ToolRegistryTest.java](file:///h:/ws/ArcClaw/arcclaw-app/src/test/java/com/arcclaw/core/tools/ToolRegistryTest.java)
- 7 tests: discovery, findTool, unknown tool, governance block/allow, secret extraction, invocation error

## Verification Plan

### Automated Tests
```bash
$env:MAVEN_OPTS="-Dnet.bytebuddy.experimental=true --add-opens=java.base/java.lang=ALL-UNNAMED"
mvn clean test -pl arcclaw-app -am -Dtest=ToolRegistryTest "-Dsurefire.failIfNoSpecifiedTests=false"
```
- ✅ Tool discovery via CDI `Instance<AgentTool>`
- ✅ `findTool()` returns correct tool / empty for unknown
- ✅ Governance: READ_ONLY blocks NETWORK_OUTBOUND
- ✅ Governance: FULL allows tool invocation
- ✅ `@RequiresSecret` extraction from GithubTool
- ✅ Unknown tool invocation throws `IllegalArgumentException`

---

# Phase 5 — Agent & Orchestration Layer

Stateful Thought-Plan-Act-Observe reasoning loop with resilience guardrails and specialized agent personas.

## Design Decisions

> [!IMPORTANT]
> **Testability**: `ReasoningEngine` interface abstracts LLM calls. Tests inject mock engines with scripted responses — no Ollama/OpenAI needed for CI.

> [!NOTE]
> **`synchronized` per JEP 491**: Loop state (`history`, `iterationCount`) uses `synchronized` blocks — safe for virtual threads on JDK 25.

> [!TIP]
> **Three guardrails**: Loop protection (maxIterations=5), hallucination interception (unknown tool → error observation), schema validation (null params → guard).

## Proposed Changes

### Core Module — Loop Architecture

#### [NEW] [ReasoningStep.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/ReasoningStep.java)
- Record: `thought`, `action`, `actionInput`, `observation`
- Factory: `pending()`, `withObservation()`

#### [NEW] [AgentResult.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/AgentResult.java)
- Record: `finalAnswer`, `steps`, `success`, `terminationReason`
- Factory: `finished()`, `loopExceeded()`, `error()`

#### [NEW] [ReasoningEngine.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/ReasoningEngine.java)
- Interface: `nextStep(goal, history)` → `ReasoningStep`

#### [NEW] [AgentLoop.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/AgentLoop.java)
- `@ApplicationScoped`, Thought-Plan-Act-Observe pattern
- 3 guardrails: loop protection, hallucination check, schema validation

### Core Module — Agent Personas

#### [NEW] [Agent.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/Agent.java) + [AgentRole.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/AgentRole.java)
- Interface + CDI `@Qualifier` for persona discovery

#### [NEW] [VishnuAgent.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/VishnuAgent.java)
- `@AgentRole("coordinator")` — Coordinator with task decomposition prompt

#### [NEW] [SaraswatiAgent.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/SaraswatiAgent.java)
- `@AgentRole("researcher")` — Research & Facts, github-only

#### [NEW] [BrahmaAgent.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/BrahmaAgent.java)
- `@AgentRole("coder")` — Code/Logic, github-only

### App Module — Tests

#### [NEW] [AgentResilienceTest.java](file:///h:/ws/ArcClaw/arcclaw-app/src/test/java/com/arcclaw/core/agent/AgentResilienceTest.java)
- 5 tests: hallucination, loop limit, FINISH, null step, engine exception

## Verification Plan

### Automated Tests
```bash
$env:MAVEN_OPTS="-Dnet.bytebuddy.experimental=true --add-opens=java.base/java.lang=ALL-UNNAMED"
mvn clean test -pl arcclaw-app -am -Dtest=AgentResilienceTest "-Dsurefire.failIfNoSpecifiedTests=false"
```
- ✅ Hallucinated tool handled gracefully (self-correction)
- ✅ maxIterations terminates runaway loop
- ✅ FINISH action returns correct result
- ✅ Null step terminates gracefully
- ✅ Engine exception handled

---

# Phase 6 — State Layer (Checkpointing)

Make the AgentLoop stateful and resilient to crashes via JPA + SQLite checkpointing.

## Design Decisions

> [!IMPORTANT]
> **No PanacheEntity / PanacheRepository**: Both require Quarkus bytecode enhancement which only runs in the app module. Since the entity lives in `arcclaw-core`, we use plain `@Entity` + `EntityManager` for cross-module compatibility.

> [!NOTE]
> **No `@Lob`**: SQLite handles `@Column(columnDefinition = "TEXT")` better than `@Lob`.

> [!TIP]
> **`hibernate-orm.packages`**: Added `com.arcclaw.core.state` to `application.yaml` so Hibernate discovers entities in the library module.

## Proposed Changes

### Core Module — Entity & Store

#### [NEW] [CheckpointStatus.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/state/CheckpointStatus.java)
- Enum: `IN_PROGRESS`, `COMPLETED`, `FAILED`

#### [NEW] [AgentCheckpoint.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/state/AgentCheckpoint.java)
- JPA `@Entity` with unique constraint on `(sessionId, agentRole)`
- `@Column(columnDefinition = "TEXT")` for `stateJson`
- `@PrePersist` / `@PreUpdate` lifecycle timestamps

#### [NEW] [StatefulAgentStore.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/state/StatefulAgentStore.java) + [StatefulAgentStoreImpl.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/state/StatefulAgentStoreImpl.java)
- Plain `EntityManager` (not Panache) with JPQL queries
- Jackson `ObjectMapper` for `List<ReasoningStep>` ↔ `stateJson`
- `@Transactional` on save/markFailed/markCompleted

#### [NEW] [SqliteWalConfigurer.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/state/SqliteWalConfigurer.java)
- `@Observes StartupEvent` → WAL/NORMAL/busy_timeout PRAGMAs via `AgroalDataSource`

### Phase 5 Backfills

#### [NEW] [ShivaAgent.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/ShivaAgent.java)
- `@AgentRole("creative")` — blind-spot detection and creative alternatives

#### [NEW] [ArcClawSupervisor.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/ArcClawSupervisor.java)
- CDI `Instance<Agent>` agent roster with listing, lookup, and delegation

### Config Changes

#### [MODIFY] [application.yaml](file:///h:/ws/ArcClaw/arcclaw-app/src/main/resources/application.yaml)
- Added `hibernate-orm.packages: [com.arcclaw.core.state]`

### Tests

#### [NEW] [StatefulAgentStoreTest.java](file:///h:/ws/ArcClaw/arcclaw-app/src/test/java/com/arcclaw/core/state/StatefulAgentStoreTest.java)
- 4 tests: save+load+deserialize, markFailed, markCompleted, upsert

## Verification

- ✅ Save and load checkpoint with deserialized ReasoningSteps
- ✅ Mark failed with reason and timestamp
- ✅ Mark completed status transition
- ✅ Upsert: save twice → same entity ID, updated steps

---

# Phase 7 — Lifecycle Layer

Connect CDI Scope, Security Vault, State Store, and Agent Loop into a cohesive lifecycle.

## Design Decisions

> [!IMPORTANT]
> **WorkspaceManager** is the lifecycle orchestrator — it owns heartbeats, GC sweeps, zombie recovery, and session termination. `AgentLoop` becomes lifecycle-aware via context setup/teardown.

> [!NOTE]
> **WorkspaceScopeContext**: Is an Arc custom context, not a CDI bean. Injected via `Arc.container().getActiveContext(WorkspaceScoped.class)` in `WorkspaceManager`.

> [!TIP]
> **AgentOtelContext**: Converted from static utility to `@ApplicationScoped` for proper CDI injection into `ProcessIsolationExecutor` and `AgentLoop`.

## Proposed Changes

### Core Module — WorkspaceManager

#### [NEW] [WorkspaceManager.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/lifecycle/WorkspaceManager.java)
- `@ApplicationScoped`, `ConcurrentHashMap<String, Instant>` heartbeats
- `recordHeartbeat(sessionId)` — updates timestamp
- `terminateSession(sessionId)` — marks DB checkpoints FAILED, wipes workspace dir, destroys CDI scope
- `@Scheduled` GC sweep — expires sessions beyond `session-ttl-minutes`
- `@Observes StartupEvent` zombie recovery — marks orphaned IN_PROGRESS checkpoints FAILED (made `public` for testing)

### Core Module — AgentOtelContext Upgrade

#### [MODIFY] [AgentOtelContext.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/observability/AgentOtelContext.java)
- Converted to `@ApplicationScoped`
- Added `startSessionSpan(sessionId)` / `endSessionSpan(sessionId)` stubs

### Core Module — AgentLoop Upgrades

#### [MODIFY] [AgentLoop.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/AgentLoop.java)
- New `run(sessionId, agentRole, ...)` overload
- Context setup: `WorkspaceScopeHolder`, MDC, OTel session span
- State integration: `agentStore.load()` resume + per-iteration checkpointing
- Context teardown in `finally` block

### Tests

#### [NEW] [WorkspaceManagerTest.java](file:///h:/ws/ArcClaw/arcclaw-app/src/test/java/com/arcclaw/core/lifecycle/WorkspaceManagerTest.java)
- 4 tests: Zombie recovery, GC sweep, Termination, Multi-role termination

## Verification

- ✅ Zombie recovery marks IN_PROGRESS as FAILED on startup
- ✅ GC sweep terminates expired sessions
- ✅ Termination wipes workspace dir and destroys CDI scope
- ✅ Completed checkpoints not overwritten during termination

---

# Phase 8 — Governance Layer ✅

Implement hard financial limits on agent reasoning loops to prevent budget exhaustion.

## Design Decisions

> [!IMPORTANT]
> **Token Tracking Strategy**: Input and output tokens are tracked at the `ReasoningStep` level and aggregated natively in the `AgentResult`.

> [!NOTE]
> **Autonomy Downgrade**: When a session exceeds its budget (`arcclaw.governance.budget-usd`), the session's autonomy is immediately downgraded to `READONLY` and a `BudgetExceededException` is thrown, aborting the thought loop.

> [!TIP]
> **Thread Safety (JEP 491)**: `SessionBudget` accumulator uses concurrency primitives (`AtomicInteger`, `synchronized`) to safely update shared budget maps without race conditions from parallel tool execution.

## Completed Changes

### Core Module — Governance Logic

#### [NEW] [BudgetExceededException.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/governance/BudgetExceededException.java)
- RuntimeException triggered when a session's LLM consumption crosses the USD threshold.

#### [NEW] [AgentObserver.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/observability/AgentObserver.java)
- Interface for interception. Method: `recordLlmCall`.

#### [NEW] [GovernanceLayer.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/governance/GovernanceLayer.java)
- Implementation of `AgentObserver` with USD calculation and enforcement.

## Verification
- ✅ `GovernanceLayerTest`: Verified cost accumulation and `READ_ONLY` trigger.

---

# Phase 9 — Observer / OTel Layer ✅

Replace observability stubs with a full OpenTelemetry implementation.

## Design Decisions

> [!IMPORTANT]
> **Mock-First Verification**: Due to complex OTLP setup in CI, tests use `@InjectMock Tracer` to verify span creation and attribute attribution.

## Completed Changes

#### [MODIFY] [AgentOtelContext.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/observability/AgentOtelContext.java)
- Full OTel `Tracer` integration for session and LLM spans.

#### [MODIFY] [AgentLoop.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/AgentLoop.java) / [WorkspaceManager.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/lifecycle/WorkspaceManager.java)
- Integrated `AgentObserver` list and budget cleanup.

## Verification
- ✅ `AgentOtelContextTest`: Verified span creation and attribute tagging.

---

# Phase 10 — Health Layer ✅

Implement Podman liveness and readiness checks using MicroProfile Health.

## Design Decisions

> [!IMPORTANT]
> **Process-Based Checks**: Both liveness and readiness checks execute sub-processes (`podman --version` and `podman image exists`) with a strict 2-second timeout to avoid health-probe hanging.

> [!WARNING]
> **Management Port**: Quarkus 3.x serves health/metrics/openapi endpoints on a separate management interface (port 9001 in test mode). RestAssured tests must explicitly target `.port(9001)` instead of the default HTTP port.

> [!NOTE]
> **Cross-Module Mock Pattern**: `@Mock extends ParentClass` fails across classloader boundaries due to ArC-generated bean code attempting to inject `@ConfigProperty` fields in the parent. Used standalone `@Alternative` + `@Priority` implementations of `HealthCheck` interface instead.

## Completed Changes

### Core Module — Health Checks

#### [NEW] [PodmanHealthCheck.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/health/PodmanHealthCheck.java)
- `@Liveness` probe for Podman engine availability.

#### [NEW] [PodmanImageHealthCheck.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/health/PodmanImageHealthCheck.java)
- `@Readiness` probe for sandbox image presence.

### App Module — Test Mocks

#### [NEW] [MockPodmanHealthCheck.java](file:///h:/ws/ArcClaw/arcclaw-app/src/test/java/com/arcclaw/core/health/MockPodmanHealthCheck.java)
- `@Alternative` + `@Priority(1)` standalone `HealthCheck` for liveness test.

#### [NEW] [MockPodmanImageHealthCheck.java](file:///h:/ws/ArcClaw/arcclaw-app/src/test/java/com/arcclaw/core/health/MockPodmanImageHealthCheck.java)
- `@Alternative` + `@Priority(1)` standalone `HealthCheck` for readiness test.

## Verification

- ✅ `/q/health/live` returns HTTP 200 with `{"status": "UP"}`
- ✅ `/q/health/ready` returns HTTP 200 with `{"status": "UP"}`

## Lessons for Future Phases

> [!CAUTION]
> **52 iterations were consumed on this phase.** The following rules MUST be followed for any future health/metrics/non-application endpoint work:

1. **Never use `@InjectMock` for `HealthCheck` beans** — ArC indexes them by interface, not concrete class.
2. **Always use `@Alternative` + `@Priority`** for test replacements of cross-module beans with `@ConfigProperty` fields.
3. **Always check the management port** — Quarkus 3.x serves health endpoints on port 9001 (test mode). Use `.port(9001)` in RestAssured.
4. **Never `@Mock extends` a class with `@ConfigProperty` fields** — ArC's generated `_Bean.doCreate()` code runs in a different classloader and cannot access parent fields.
5. **Always run `mvn clean install -DskipTests`** when adding new beans or annotations to `arcclaw-core` before running tests in `arcclaw-app`.

---


# Phase 11 — Channel / REST Layer ✅

Expose the agentic kernel via a resilient, virtual-thread-powered REST API using Quarkus RESTEasy Reactive and Hibernate Validator.

## Design Decisions

> [!IMPORTANT]
> **Virtual Threads**: The REST resource uses `@RunOnVirtualThread` to ensure that long-running LLM calls (synchronous) or process executions do not block the Quarkus event loop or carrier threads. This is critical for scalability.

> [!NOTE]
> **REST Subpaths**: Endpoints tested via `/api/v1/sessions` per `quarkus.rest.path` configured in `application.yaml`. Tests hitting `/sessions` will receive a 404.

> [!WARNING]
> **RESTEasy Extensions**: Extensions tracking Jackson validation (`quarkus-rest-jackson` and `quarkus-hibernate-validator`) must explicitly be declared in the runtime `arcclaw-app` module to be discovered by Quarkus. Declaring them only in `arcclaw-core` does not reliably carry over into the test lifecycle execution environment without configuration.

## Completed Changes

#### [MODIFY] [pom.xml](file:///h:/ws/ArcClaw/arcclaw-core/pom.xml) / [pom.xml](file:///h:/ws/ArcClaw/arcclaw-app/pom.xml)
- Included `quarkus-rest-jackson` and `quarkus-hibernate-validator` dependencies.

#### [NEW] [CreateSessionResponse.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/api/dto/CreateSessionResponse.java)
- `record CreateSessionResponse(String sessionId)`

#### [NEW] [RunAgentRequest.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/api/dto/RunAgentRequest.java)
- `record RunAgentRequest(@NotBlank String goal, @NotBlank String agentRole, Integer maxIterations)`

#### [NEW] [SessionStatusResponse.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/api/dto/SessionStatusResponse.java)
- `record SessionStatusResponse(String sessionId, String status, java.util.List<com.arcclaw.core.agent.ReasoningStep> steps)`

#### [NEW] [DefaultReasoningEngine.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/DefaultReasoningEngine.java)
- `@ApplicationScoped` stub implementation of `ReasoningEngine`. Returns a hardcoded `FINISH` step to allow the REST layer to be tested before LangChain4J is fully wired.

#### [NEW] [AgentResource.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/api/AgentResource.java)
- `@Path("/sessions")`, `@Consumes(MediaType.APPLICATION_JSON)`, `@Produces(MediaType.APPLICATION_JSON)`
- `@RunOnVirtualThread` on the class level.
- `POST /` -> Creates session, records heartbeat, returns `201 Created`.
- `POST /{id}/run` -> Sync agent run, returns `200 OK` with `AgentResult`.
- `POST /{id}/run-async` -> Spawns virtual thread for agent run, returns `202 Accepted`.
- `GET /{id}/status/{role}` -> Loads checkpoint, returns `200 OK` with `SessionStatusResponse` or `404 Not Found`.
- `DELETE /{id}` -> Terminates session, returns `204 No Content`.

## Verification
- ✅ `AgentResourceTest`: Used RestAssured to test all 5 endpoints via `/api/v1/sessions/...`. Verified HTTP statuses 201, 200, 202, 404, 204. Verified sync output behavior and validation (400) failures logic.

---

# Phase 12 — Cross-Cutting Concerns ✅

Integrates LangChain4J's `ReasoningEngine` and addresses CDI reflection constraints for testing on JDK 21+.

## Design Decisions

> [!IMPORTANT]
> **Provider Ambiguity**: When multiple LangChain4J provider extensions (e.g., `openai` and `ollama`) are present on the classpath, Quarkus cannot unambiguously autowire `ChatLanguageModel`. This requires explicitly setting `quarkus.langchain4j.chat-model.provider` in `application.yaml`.

> [!WARNING]
> **Mockito on JDK 21+**: Mockito's inline mock-maker requires a dynamic agent to attach. On JDK 21, this requires `--add-opens=java.base/java.lang=ALL-UNNAMED` in the test execution environment to prevent attachment failures during test initialization.

## Completed Changes

#### [NEW] [LangChainReasoningEngine.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/LangChainReasoningEngine.java)
- `@ApplicationScoped` implementation of `ReasoningEngine` using `ChatLanguageModel`.
- Configured to use the `0.26.1` Quarkus extension API, utilizing `chatLanguageModel.chat()`.

## Verification
- ✅ `LangChainReasoningEngineTest`: Verifies behavior using Mockito on the `@Inject`ed chat model successfully.

---

# Phase 13 — Deployment Artifacts ✅

Provide local containerized execution scaffolding via Docker Compose and rootless Podman execution contexts.

## Completed Changes

#### [NEW] [docker-compose.yaml](file:///h:/ws/ArcClaw/docker-compose.yaml)
- Defines services for `arcclaw`, `ollama`, `otel-collector`, and `jaeger`.
- **`init-perms`**: Ephemeral container to `chown 1000:1000 /app/data` avoiding rootless Podman SQLite `Permission denied` errors on default `root:root` named volumes.
- Exposes necessary ports and mounts data volumes.

#### [NEW] [Dockerfile.native](file:///h:/ws/ArcClaw/Dockerfile.native)
- Uses `ubi-minimal:9.3` to provide a container native runner.
- Configures `arcclaw` standard user (UID 1000), subuids/subgids, and installs `podman` for embedded tool isolation execution.

#### [NEW] [containers.conf](file:///h:/ws/ArcClaw/containers.conf)
- Forces `vfs` storage driver inside the UBI container to bridge rootless podman.

#### [NEW] [.env.example](file:///h:/ws/ArcClaw/.env.example)
- Environment variable template containing `GITHUB_TOKEN`, Model configurations, etc.

## Verification
- ✅ Deployment artifacts created and `.gitignore` updated to ignore real `.env` files.

---

# Phase 14 — Verification & Integration Testing ✅

Ensures all modules (`arcclaw-core`, `arcclaw-deployment`, `arcclaw-app`) fully compile, wire successfully via CDI, and pass their unit/integration tests natively under the Quarkus JVM and Surefire providers.

## Design Decisions

> [!IMPORTANT]
> **MicroProfile Context Propagation**: Virtual thread execution (`@RunOnVirtualThread`) completely detaches the `ThreadLocal` storage from the parent carrier. Without explicitly adding `mp.context.propagators=Quarkus, CDI, JAX-RS` to `application.yaml`, any request-scoped or `@WorkspaceScoped` attributes resolve to null inside the asynchronous agent loop or sandbox executions.

> [!NOTE]
> **Test State Leakage**: In-memory static maps (like `ConcurrentHashMap` in `WorkspaceManager` or `StatefulAgentStore`) will leak across `@QuarkusTest` class boundaries when running a full suite (`mvn clean verify`). A hermetic clear/teardown method (e.g., `workspaceManager.clearAll()`) MUST be invoked in an `@AfterEach` or `@BeforeEach` to isolate integration tests.

> [!WARNING]
> **Provider Isolation in Tests**: Injecting the *real* `ReasoningEngine` mapped to an external Ollama or OpenAI service will fail integration tests with `Connection refused`. Tests validating the REST layer should strictly use `@InjectMock ReasoningEngine` to isolate API testing from the LLM execution layer.

## Verification
- ✅ `mvn clean verify -pl arcclaw-app -am` completed successfully, executing 43 unit and integration tests across all modules.
- ✅ `TESTING_GUIDE.md` produced for local developers providing E2E `curl` and Jaeger commands.

---

# Phase 14.1 — Day 2 Operations (Hardening) ✅

Harden the LangChain4J execution pipeline against real-world LLM chaos.

## Design Decisions

> [!WARNING]
> **Context Window Exhaustion**: Verbose tools (e.g., fetching a huge file or running a bash script) can return 30k+ character `ToolOutput` observations. Feeding this back into `llama3.2:1b` via `AiMessage` instantly blows past the 8k context window, silently crashing the process.
> **Fix**: Observations are strictly truncated to `4000` characters before serialization.

> [!CAUTION]
> **JSON Parsing Death-Spirals**: Local models occasionally hallucinate invalid Tool Argument JSON configurations. Swallowing Jackson `JsonParseException`s results in an empty `Map.of()`, causing the tool to fail validation, returning an error to the LLM, which the LLM then attempts to resolve by generating the *exact same bad JSON array* in an infinite loop.
> **Fix**: Catch parsing exceptions explicitly and return a `FINISH` ReasoningStep with a strict "System Error: Invalid JSON" directive to force the system to halt or self-correct.

## Verification
- ✅ Modified `LangChainReasoningEngine.java` to inject truncation logic and Jackson interceptors.
- ✅ Re-ran `mvn clean test -pl arcclaw-core` to ensure `LangChainReasoningEngineTest` is unaffected by the new loop conditionals.

---

# Phase 14.2 — Day 2 Operations (Windows Dev Ex) ✅

Fix initialization crashes for Windows developers running `quarkus:dev` natively.

## Design Decisions

> [!CAUTION]
> **SQLite Parent Directory Creation**: SQLite's JDBC driver drops a `GenericJDBCException` if the `ARCCLAW_DATA_DIR` (default `./data`) does not exist on the filesystem prior to Hibernate DDL boot. Linux container mapped volumes hide this requirement by implicitly creating directories.
> **Fix**: Dropped a custom `@QuarkusMain` entrypoint in `ArcClawApp.java`. It invokes Eclipse MicroProfile's `ConfigProvider` to natively locate `ARCCLAW_DATA_DIR` from `.env` and provisions the host directory via `Files.createDirectories()` *before* control is handed to `Quarkus.run()`.

> [!CAUTION]
> **Hibernate DDL Redundant Index Rejection**: SQLite generates hidden indices for `@UniqueConstraint` declarations. Leaving a secondary `@Index` block for the exact same columns caused Hibernate to blindly cast `CREATE INDEX` without `IF NOT EXISTS`, triggering an `SQLITE_ERROR` crash during the schema `update` phase.
> **Fix**: Removed the duplicate secondary `@Index` from `AgentCheckpoint.java`.

## Verification
- ✅ `.\mvnw.cmd quarkus:dev -pl arcclaw-app -am` no longer crashes on Windows. Hibernate cleanly executes DDL and boot succeeds.

---

# Phase 14.3 — Day 2 Operations (JDK 25 Edge Cases) ✅

Hardening the local Quarkus Hot-Reload Test Runner (`quarkus:dev`) against JDK 25 encapsulation.

## Design Decisions

> [!CAUTION]
> **JBoss Threads Teardown Exception**: When `quarkus:dev` re-runs tests or drops the event loop, JBoss attempts to perform thread-local-resets via private internals in `java.lang.Thread`. JDK 25 aggressively blocks this, throwing an `IllegalAccessError: module java.base does not open java.lang`.
> **Fix**: Injected `<jvmArgs>--add-opens java.base/java.lang=ALL-UNNAMED</jvmArgs>` into the `quarkus-maven-plugin` configuration for both `arcclaw-app` and `arcclaw-parent`.

> [!NOTE]
> **JUnit Jupiter Loading Anomaly**: Quarkus `DevModeMain` tries to load legacy JUnit 4 interceptors to handle transitive exceptions like `AssumptionViolatedException` natively. On strict module-path boundaries, Jupiter complains loudly via `ClassNotFoundException`.
> **Fix**: Extracted `<groupId>junit</groupId><artifactId>junit</artifactId>` explicitly into the `<scope>test</scope>` block of `arcclaw-app` to silence the loading anomaly.

> [!CAUTION]
> **Podman Rootless Isolation Context**: The native Dockerfile switches to `USER arcclaw` (UID 1000) for security, but the default `WORKDIR /app` establishes file ownership as root. Subsequent `RUN mkdir -p /app/data` crashed the build sequence with `Permission denied`.
> **Fix**: Executed internal `/app` and `.config/` directory mounts and explicitly `chown -R arcclaw:arcclaw` while still impersonating `root` before committing to the lower privileged `USER arcclaw`.

> [!CAUTION]
> **Virtual Thread Context Leaks**: The `/run-async` API endpoint manually spawns unmanaged `Thread.ofVirtual()`. This instantly drops the Quarkus CDI RequestContext, triggering `ContextNotActiveException` upon Hibernate DB calls during the agent loop.
> **Fix**: Annotated `AgentLoop.run` natively with `@ActivateRequestContext` so independent virtual thread executions safely instantiate their own RequestContext boundaries.

## Verification
- ✅ Executing `r` in the `quarkus:dev` console successfully runs all 43 tests without `ClassNotFoundException` clutter and cleanly terminates without `IllegalAccessError`.
- ✅ `docker compose up -d --build` successfully creates `/app/data` and launches the rootless container via Podman Desktop.
- ✅ Making POST requests to `/api/v1/sessions/{id}/run-async` safely initializes asynchronous DB accesses without `ContextNotActiveException` (in dev mode).

---

# Phase 14.6 — Day 3 Operations (Native Mode Resilience) ✅

Complete post-mortem and resolution of critical blockers encountered *exclusively* during Native Image AOT execution via GraalVM/Mandrel.

## Design Decisions

> [!CAUTION]
> **Native Context Propagation Failure**: The Phase 14.3 fix for `ContextNotActiveException` (using `@ActivateRequestContext` on the `AgentLoop.run` method inside an unmanaged `Thread.ofVirtual()`) successfully propagated context in JVM mode, but **failed entirely** in Native mode.
> **Root Cause**: Unmanaged virtual threads completely circumvent Quarkus's Vert.x duplicated context mechanisms in AOT binaries.
> **The Fix**: Refactored `AgentResource.runAgentAsync` to use Quarkus's managed executor: `@Inject @VirtualThreads ExecutorService vThreads`. Submitting the task via `vThreads.execute(...)` ensures the Vert.x duplicated context (and thus the CDI RequestScope and Hibernate EntityManager hooks) cleanly transitions across thread boundaries in native images.

> [!CAUTION]
> **Native Build Incremental Failure (`-am` Flag)**: While debugging the context propagation issue, code changes made to `arcclaw-core` were not reflecting in the native binary.
> **Root Cause**: The Maven build command `mvnw clean package -pl arcclaw-app -Pnative` only built the `arcclaw-app` module natively. It silently reused the *old, cached JAR* for `arcclaw-core` from prior builds.
> **The Fix**: The native build command MUST include the `-am` (also-make) flag: `mvnw clean package -pl arcclaw-app -am -Pnative`. This ensures `-core` is recompiled before `-app` is packaged into a native binary.

> [!CAUTION]
> **Ollama `model is required` HttpException in Native Container**: Triggering an agent natively resulted in the Ollama container throwing an HTTP 400 `model is required` exception, despite `.env` injection.
> **Root Cause**: In Quarkus LangChain4j version `0.26.1`, the property `quarkus.langchain4j.ollama.chat-model.model-id` is read natively from `LangChain4jOllamaFixedRuntimeConfig`. This means the property is **strictly evaluated at build time**. Whatever the value of `OLLAMA_MODEL` is in the host's `.env` during the `mvnw package -Pnative` execution gets permanently embedded into the binary. Any runtime environment variables passed in `docker-compose.yaml` are completely ignored, leading to a blank `model` payload if the host machine lacked the environment variable during build.
> **The Fix**: We completely abandoned the `ollama` provider natively. We explicitly configured `quarkus.langchain4j.chat-model.provider=openai` mapped to `http://localhost:11434/v1`. The OpenAI extension properly parses `openai.chat-model.model-name` dynamically at runtime, providing 100% Ollama native image compatibility without the build-time lock bug. By user request, technical exception mapping in `AgentLoop` and `AgentResource` was reverted back to raw Strings.

## Verification
- ✅ Native Docker image booted correctly via Compose without "model is required" HTTP crashes using the OpenAI provider fallback.
- ✅ Native `/run-async` endpoint correctly executes `AgentLoop` tasks against the SQLite database using Virtual Threads without throwing `ContextNotActiveException`.
- ✅ Asynchronous JAX-RS `@Suspended AsyncResponse` gracefully handles error tracking and HTTP 202/500 signaling.
- ✅ Reverted `AgentLoop` exception mapping back to native REST API string propagation.

---

# Phase 17 — Groq Cloud Migration

Repoint the default LLM provider from local Ollama to Groq Cloud for production workloads while maintaining dev-mode flexibility and hardening the autonomy governance framework.

## Design Decisions

> [!IMPORTANT]
> **LLM Provider Pivot**: Migrated from `ollama` as default to `openai` extension mapped to Groq Cloud (`llama-3.3-70b-versatile`). This provides higher reasoning stability and enterprise-grade performance.
> **Autonomy Logic Refinement**: Renamed `READ_ONLY` to `READONLY` and `PARTIAL` to `SUPERVISED` to align with the enterprise configuration keys and provide clearer semantics for human-in-the-loop oversight.

## Proposed Changes

### Core Module — Governance

#### [MODIFY] [AutonomyLevel.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/AutonomyLevel.java)
- Renamed constants: `READ_ONLY` -> `READONLY`, `PARTIAL` -> `SUPERVISED`.

### App Module — Channel

#### [MODIFY] [TelegramAgentHandler.java](file:///h:/ws/ArcClaw/arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram/TelegramAgentHandler.java)
- Injected `arcclaw.autonomy.default-level` and applied it to active sessions.
- Added diagnostic logging for `AutonomyLevel` and completion status.

## Verification

- ✅ All tests updated to use renamed constants.
- ✅ Verified `SUPERVISED` injection in Telegram diagnostic logs.
- ✅ Verified Groq Cloud routing via OpenAI compatibility layer.


---

# Phase 15 — Dual-Mode Binary Upgrade ✅

Upgrade ArcClaw from a single-mode REST daemon into a dual-mode binary using `quarkus-picocli`. This enables CLI usage alongside daemon API processing.

## Design Decisions

> [!IMPORTANT]
> **Daemon vs CLI Separation**: The top-level `ArcClawCli` intercepts Quarkus bootstrap to provision `ARCCLAW_DATA_DIR` before handing over execution to Picocli. CLI one-shot mode (`AskCommand`) manages its own `WorkspaceScopeHolder` and CDI RequestContext, bypassing `Quarkus.waitForExit()` to allow seamless termination. Daemon mode (`ServeCommand`) explicitly waits for exit.

> [!NOTE]
> **Guarded Sub-Observer**: `CliAgentObserver` acts as an `AgentObserver` but relies on a `volatile boolean enabled` flag, conditionally activated by `AskCommand`, to prevent log-spamming during background daemon mode execution.

## Verification
- ✅ Single executable provides both daemon (`arcclaw serve`) and CLI (`arcclaw ask`) capabilities.
- ✅ Compiled via `mvn compile` with 0 warnings/errors.


---

# Sprint 2A — Telegram Channel Adapter

Network/threading layer for ArcClaw ↔ Telegram integration via Camel Quarkus.

## Design Decisions

> [!IMPORTANT]
> **Camel Quarkus BOM Required**: The `camel-quarkus-telegram` dependency is NOT managed by the standard `quarkus-bom`. We must import `quarkus-camel-bom:3.23.0` into the parent `pom.xml` `<dependencyManagement>` to get version management.

> [!WARNING]
> **Session Continuity (REQ-V11-CLI-11)**: Unlike `AskCommand`, the Telegram handler does NOT call `workspaceManager.terminateSession()` — the session stays `IN_PROGRESS` for conversation memory. Only `WorkspaceScopeHolder.clear()` is called in `finally`.

## Proposed Changes

### Telegram Integration
1. **Parent BOM**: Added `quarkus-camel-bom` to `<dependencyManagement>`.
2. **arcclaw-app**: Added `camel-quarkus-telegram` dependency.
3. **application.yaml**: Added `arcclaw.channel.telegram` configuration block.
4. **TelegramChannelRoute.java**: Camel `RouteBuilder` for the telegram component.
5. **TelegramAgentHandler.java**: CDI bean to handle incoming messages and dispatch agent loops asynchronously via `ManagedExecutor`.

## Verification Plan
- ✅ `TelegramAgentHandlerTest`: Verified session ID generation and offloading.
- ✅ `TelegramChannelRouteTest`: Verified route registration in CamelContext.

---

# Sprint 2B — Human-in-the-Loop Telegram Approval Flow

Implement a robust human-in-the-loop (HITL) state machine for `SUPERVISED` autonomy mode, allowing the agent to pause for approval during sensitive operations without thread parking.

## Design Decisions

> [!IMPORTANT]
> **No Thread Parking**: When approval is required, the `AgentLoop` returns an `AWAITING_APPROVAL` status and exits. The thread is released. The state is saved to the `AgentCheckpoint` including the pending tool name and serialized JSON parameters.
> **Resume Hook**: The `AgentLoop.run()` method includes a "resume hook" at the beginning. If an `AWAITING_APPROVAL` checkpoint exists, it executes the pending tool first before resuming the thought loop.

## Proposed Changes

### Core Module — State & Logic
1. **`CheckpointStatus`**: Added `AWAITING_APPROVAL`.
2. **`AgentCheckpoint`**: Added `pendingToolName`, `pendingToolParametersJson`, and `LocalDateTime` timestamps.
3. **`RequiresApprovalException`**: Custom exception thrown by `ToolRegistry` for sensitive operations (`NETWORK_OUTBOUND`, `VAULT_ACCESS`, `EXECUTE_SHELL`) in `SUPERVISED` mode.
4. **`AgentLoop`**: Implemented `try-catch` for `RequiresApprovalException` and the resume-checkpoint hook.

### App Module — Channel
1. **`TelegramAgentHandler`**: Intercepts `/approve` and `/deny` commands. Elevates autonomy to `FULL` upon approval and triggers `AgentLoop.run()` to resume processing.

## Verification
- ✅ Verified `AWAITING_APPROVAL` state survives JVM restarts.
- ✅ Verified `/approve` successfully resumes execution and completes the task.

---

# Sprint 2C — Dynamic Role Routing & Hardening

Refine the Telegram user experience with intent discovery and resolve critical state machine edge cases discovered during HITL testing.

## Design Decisions

> [!IMPORTANT]
> **Intent Router**: Implemented a keyword-based `determineAgentRole` heuristic in `TelegramAgentHandler` to route user goals to the most appropriate persona (`brahma` for code, `saraswati` for research, `coordinator` for general/system).
> **Deterministic ID Alignment**: Enforced `chatId:userId` as the source for deterministic session UUIDs to prevent session collisions and "phantom" UUIDs in logs.

## Hardening Fixes
1. **State Machine Overwrite**: Modified `AgentLoop.run()` wrapper to prevent marking a session as `FAILED` or `COMPLETED` if the internal loop returned `AWAITING_APPROVAL`.
2. **Phantom Sessions**: Implemented `workspaceManager.terminateSession()` call in the Telegram handler logic to wipe previous `COMPLETED`/`FAILED` records, allowing users to start a "fresh" conversation under the same deterministic ID.

## Verification
- ✅ Verified `/approve` no longer suffers from "No pending approval found" errors.
- ✅ Verified session resetting logic allows immediate new conversations after completion.

---

# Phase 20 — State Consistency & Observability ✅

Harden the state layer and implement intelligent routing for multi-agent coordination.

## Design Decisions

> [!IMPORTANT]
> **Persistent ChatMemory**: Migrated from in-memory to JDBC-backed `ChatMemoryStore` to ensure conversation history survives bot restarts and enables deterministic session resumption.

> [!CAUTION]
> **Task Amnesia**: Implemented automatic state wiping (ChatMemory + Reasoning History) when a user resumes a session marked as `COMPLETED` or `FAILED`. This ensures a fresh start for new tasks while maintaining deterministic IDs.

> [!NOTE]
> **LLM Intent Routing**: Replaced keyword-based heuristics with a fast LLM classifier (`llama-3.1-8b-instant`) to dynamically route ambiguous user prompts to specialized agent roles (`brahma` for creating, `shiva` for fixing).

> [!TIP]
> **Real-time Observability**: Integrated Micrometer Prometheus registry to track token consumption and agent invocation rates via a dedicated `/q/metrics` endpoint.

## Proposed Changes

### Core Module — Persistence & Observability
- [NEW] `ChatMemoryEntity.java`: JPA entity for LangChain4j message persistence.
- [NEW] `PersistentChatMemoryStore.java`: JDBC-backed implementation of `ChatMemoryStore`.
- [NEW] `IntentClassifierService.java`: LLM-based prompt classification and role mapping.
- [NEW] `ModelProducer.java`: Manual CDI producer for named models.

### App Module — Integration & Metrics
- [NEW] `MetricsAgentObserver.java`: Agent observer for Micrometer telemetry.
- [MODIFY] `TelegramAgentHandler.java`: Integrated intent routing, session reset logic, and multi-agent approval discovery.

### Infrastructure
- [NEW] `prometheus.yml`: Local scrape configuration.
- [MODIFY] `docker-compose.yaml`: Added Prometheus service.

## Verification
- ✅ `PersistentChatMemoryStoreTest` verifies history persistence.
- ✅ `TelegramAgentHandlerTest` verifies routing and task amnesia.
- ✅ **Secret Code Check**: Verified working as designed. The agent's intentional "goldfish memory" (Task Amnesia) correctly wipes the secret code between isolated tasks to prevent context poisoning.
- ✅ **Metrics Exposure**: Verified Prometheus export at `/q/metrics`.
- ✅ **Semantic Routing**: Verified accurate dispatching between `brahma` (create) and `shiva` (fix) roles.
- ✅ Full suite (48 tests) passed.

---

> [!NOTE]
> **Sprint 3E (Discord Adapter)** has been deferred. See [AD-014: De-prioritization of Discord Channel Adapter (Feb 2026)](file:///h:/ws/ArcClaw/ARCHITECTURAL_DECISIONS.md) for details on the library deprecation and security risks that led to this decision.

---

# Phase 21 — CLI Subcommands (Sprint 4A) ✅

Implement interactive REPL and background daemon management in the root CLI.

## Design Decisions

> [!IMPORTANT]
> **CDI Isolation**: Used `@ActivateRequestContext` in Picocli commands and offloaded scope management to `AgentLoop` to ensure stable state across iterative REPL turns.
> **Windows Terminal Compatibility**: Swapped emojis for compatible Unicode symbols (`✦`, `⚡`, `▶`) to ensure reliable rendering in standard PowerShell/CMD environments.

## Proposed Changes

### [arcclaw-app]

#### [NEW] [ChatCommand.java](file:///h:/ws/ArcClaw/arcclaw-app/src/main/java/com/arcclaw/app/cli/ChatCommand.java)
- Interactive REPL with session resume (`--session`) and reset (`/reset`) support.
- Graceful `Ctrl+C` interrupt handling.

#### [NEW] [AttachCommand.java](file:///h:/ws/ArcClaw/arcclaw-app/src/main/java/com/arcclaw/app/cli/AttachCommand.java)
- Background daemon mode for channel adapters (Telegram/Discord).

#### [MODIFY] [ArcClawCli.java](file:///h:/ws/ArcClaw/arcclaw-app/src/main/java/com/arcclaw/app/ArcClawCli.java)
- Registered new subcommands in the root CLI entry point.

#### [MODIFY] [arcclaw-cli.ps1](file:///h:/ws/ArcClaw/arcclaw-cli.ps1)
- Added an optional interactive `(y/N)` rebuild step before execution.
- Forced PowerShell console and JVM to use UTF-8 (`-Dfile.encoding=UTF-8`) to fix symbol rendering.

#### [MODIFY] [LangChainReasoningEngine.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/LangChainReasoningEngine.java)
- Replaced the generic LangChain system message with the comprehensive **ArcClaw Trimurti/Tridevi Persona**, properly introducing the roles of Vishnu, Brahma, Shiva, and Saraswati.

## Verification
- ✅ **Build**: `mvnw clean package` successful.
- ✅ **REPL**: Verified multi-turn chatting and session persistence.
- ✅ **Daemon**: Verified background polling for Telegram bots.
