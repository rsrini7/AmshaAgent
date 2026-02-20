# Phase 0 — Project Scaffolding & Build Infrastructure

> **Cross-Reference**: [TASKS.md](./TASKS.md) | [DEVIATIONS.md](./DEVIATIONS.md) | [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) | [WALKTHROUGH.md](./WALKTHROUGH.md)

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

#### [NEW] [GrokAgent.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/GrokAgent.java)
- `@AgentRole("grok")` — Coordinator with task decomposition prompt

#### [NEW] [HarperAgent.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/HarperAgent.java)
- `@AgentRole("researcher")` — Research & Facts, github-only

#### [NEW] [BenjaminAgent.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/BenjaminAgent.java)
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

#### [NEW] [LucasAgent.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/agent/LucasAgent.java)
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
> **Autonomy Downgrade**: When a session exceeds its budget (`arcclaw.governance.budget-usd`), the session's autonomy is immediately downgraded to `READ_ONLY` and a `BudgetExceededException` is thrown, aborting the thought loop.

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
