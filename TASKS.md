# ArcClaw v1.0 — Implementation Task Checklist

> Tracking all implementation phases derived from the [REQUIREMENTS.md](./REQUIREMENTS.md) specification.

> **Cross-Reference**: [PLAN.md](./PLAN.md) | [DEVIATIONS.md](./DEVIATIONS.md) | [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) | [WALKTHROUGH.md](./WALKTHROUGH.md) | [FAQ.md](./FAQ.md)

---

## Phase 0 — Project Scaffolding & Build Infrastructure
- [x] Create 3-module Maven structure: `arcclaw-parent/`, `arcclaw-core/`, `arcclaw-deployment/`, `arcclaw-app/`
- [x] Write parent `pom.xml` with `dependencyManagement` (Quarkus 3.23.0 BOM, LangChain4J 1.0.0-beta4 BOM, BouncyCastle 1.78.1)
- [x] Write `arcclaw-core/pom.xml` (all runtime dependencies)
- [x] Write `arcclaw-deployment/pom.xml` (`quarkus-arc-deployment`, `quarkus-core-deployment` as `provided`)
- [x] Write `arcclaw-app/pom.xml` with Quarkus plugin, `native` + `jvm` profiles, Failsafe integration tests
- [x] Create `META-INF/quarkus-extension.properties` in `arcclaw-deployment`
- [x] Create `native-image.properties` (fine-grained BouncyCastle build/run-time flags)
- [x] Write complete `application.yaml` (HTTP, SQLite, LangChain4J, OTel, health, scheduler, profiles)
- [x] Validate Maven build succeeds: `mvnw clean compile`
- [x] Create `TROUBLESHOOTING.md` (Master Architecture, Design Decisions, & Failure Recovery Guide)

---

## Phase 1 — Core Scope Layer (`@WorkspaceScoped` CDI)
- [x] `@WorkspaceScoped` annotation (`@NormalScope(passivating = false)`)
- [x] `WorkspaceScopeHolder` (ThreadLocal holder, JDK 25 ScopedValue migration comments)
- [x] `WorkspaceScopeContext` (Arc `InjectableContext`, `synchronized` per-session store — JEP 491)
- [x] ~~`WorkspaceScopeExtension`~~ Not needed — Quarkus CDI Lite uses @BuildStep
- [x] `WorkspaceScopeProcessor` (Quarkus `@BuildStep` — in deployment module)
- [x] ~~`WorkspaceScopeRecorder`~~ Not needed — ContextConfigurator.contextClass() handles it
- [x] Verify: `@WorkspaceScoped` beans resolve correctly via JUnit test <!-- id: 4 -->

---

## Phase 2 — Security Layer (Vault & Crypto)
- [x] `VaultKey` (Auto-wiping wrapper) <!-- id: 6 -->
- [x] `Argon2idKeyDerivation` (Key stretching) <!-- id: 7 -->
- [x] `AesGcmSivCipher` (Encryption) <!-- id: 8 -->
- [x] `EncryptedSecret` (JPA Entity) <!-- id: 9 -->
- [x] `SecretVault` interface <!-- id: 14 -->
- [x] `SecretVaultImpl` (Iteration 1) <!-- id: 11 -->
- [x] `WorkspaceContext` bean (`@WorkspaceScoped`, no-arg constructor) <!-- id: 1 -->
- [x] `WorkspaceContextProducer` (`@Produces @WorkspaceScoped`) <!-- id: 2 -->
- [x] `SecretVaultImplTest` (Iteration 1) <!-- id: 10 -->
- [x] `WorkspaceGovernanceTest` (context injection, policy logic) <!-- id: 12 -->

---

## Phase 3 — Sandbox Layer (Process Isolation)
- [x] `AgentOtelContext` stub (no-op `Span.getInvalid()`, Phase 9 placeholder) <!-- id: 27 -->
- [x] `@RequiresSecret` + `@RequiresSecrets` repeatable annotations <!-- id: 17 -->
- [x] `ProcessIsolationExecutor` (`@ApplicationScoped`) <!-- id: 18 -->
  - [x] Podman strategy: resource caps (`--memory`, `--cpus`, `--pids-limit`, `--ulimit`) <!-- id: 19 -->
  - [x] Namespace strategy (Linux `unshare` fallback) <!-- id: 21 -->
  - [x] Direct execution (NONE strategy for dev) <!-- id: 22 -->
  - [x] Circuit breaker (3 failures → NAMESPACE fallback, `@PostConstruct` check) <!-- id: 23 -->
  - [x] Ephemeral env-file secret injection (write → Podman reads → delete in `finally`) <!-- id: 24 -->
  - [x] Named containers + `activeContainerNames` tracking <!-- id: 25 -->
  - [x] `@PreDestroy` graceful container shutdown (10s grace) <!-- id: 26 -->
  - [x] OTel child span integration <!-- id: 20 -->
- [x] `ProcessIsolationExecutorTest` (NONE strategy, 3 tests passing) <!-- id: 28 -->

---

## Phase 4 — Tool Layer
- [x] `ToolResponse` record (`exitCode`, `output`, `success`) <!-- id: 29 -->
- [x] `AgentTool` interface (`name()`, `description()`, `requiredOperation()`, `execute()`) <!-- id: 30 -->
- [x] `ToolRegistry` (`@ApplicationScoped`, CDI `Instance<AgentTool>`, autonomy check, secret extraction) <!-- id: 31 -->
- [x] `GithubTool` (`@RequiresSecret(GITHUB_TOKEN)`, `ProcessIsolationExecutor` delegation) <!-- id: 32 -->
- [x] `ToolRegistryTest` (7 tests: discovery, governance, reflection, error handling) <!-- id: 33 -->

---

## Phase 5 — Agent & Orchestration Layer
- [x] `ReasoningStep` record (thought/action/actionInput/observation) <!-- id: 34 -->
- [x] `AgentResult` record (finalAnswer/steps/success/terminationReason) <!-- id: 35 -->
- [x] `ReasoningEngine` interface (testable LLM abstraction) <!-- id: 36 -->
- [x] `AgentLoop` (`@ApplicationScoped`, Thought-Plan-Act-Observe) <!-- id: 37 -->
  - [x] Loop protection (`maxIterations=5` default) <!-- id: 38 -->
  - [x] Hallucination check (unknown tool → error observation) <!-- id: 39 -->
  - [x] Schema validation (null params guard) <!-- id: 40 -->
  - [x] `synchronized` shared state per JEP 491 <!-- id: 41 -->
- [x] `Agent` interface + `@AgentRole` CDI qualifier <!-- id: 42 -->
- [x] `VishnuAgent` (`@AgentRole("coordinator")` — Coordinator) <!-- id: 43 -->
- [x] `SaraswatiAgent` (`@AgentRole("researcher")` — Research) <!-- id: 44 -->
- [x] `BrahmaAgent` (`@AgentRole("coder")` — Code/Logic) <!-- id: 45 -->
- [x] `AgentResilienceTest` (5 tests: hallucination, loop limit, finish, null, exception) <!-- id: 46 -->
- [x] `ShivaAgent` (`@AgentRole("creative")` — Creative & Balance) <!-- id: 47 -->
- [x] `ArcClawSupervisor` (`@ApplicationScoped` — CDI agent roster) <!-- id: 48 -->

---

## Phase 6 — State Layer (Checkpointing)
- [x] `CheckpointStatus` enum (IN_PROGRESS, COMPLETED, FAILED) <!-- id: 49 -->
- [x] `AgentCheckpoint` JPA entity (unique sessionId+agentRole, TEXT stateJson) <!-- id: 50 -->
- [x] `StatefulAgentStore` interface + `StatefulAgentStoreImpl` (EntityManager, Jackson) <!-- id: 51 -->
- [x] `SqliteWalConfigurer` (WAL/NORMAL/busy_timeout PRAGMAs) <!-- id: 52 -->
- [x] `StatefulAgentStoreTest` (4 tests: save+load, markFailed, markCompleted, upsert) <!-- id: 53 -->
- [x] Hibernate `packages` config for cross-module entity discovery <!-- id: 54 -->

---

## Phase 7 — Lifecycle Layer
- [x] `WorkspaceManager` (Heartbeats, GC, Termination, Zombie Recovery) <!-- id: 55 -->
- [x] `AgentOtelContext` refactor (static → @ApplicationScoped + session spans) <!-- id: 56 -->
- [x] `ProcessIsolationExecutor` update (inject `AgentOtelContext`) <!-- id: 57 -->
- [x] `AgentLoop` upgrade (Context setup/teardown + Checkpoint resume) <!-- id: 58 -->
- [x] `WorkspaceManagerTest` (Zombie recovery, GC sweep, Termination) <!-- id: 59 -->

---


## Phase 8 — Governance Layer
- [x] `GovernanceLayer` (`@ApplicationScoped`, implements `AgentObserver`)
  - [x] Per-session `AtomicInteger` token counter + `Double` cost accumulator
  - [x] `recordLlmCall()` with token + USD math
  - [x] Budget enforcement → `AutonomyLevel.READONLY` + `BudgetExceededException`
- [x] `BudgetExceededException` (RuntimeException)

---

## Phase 9 — Observer / OTel Layer
- [x] Fix Phase 8 Memory Leak (`WorkspaceManager` clearing `GovernanceLayer` budget)
- [x] `AgentOtelContext` (OpenTelemetry `Tracer` integration, root `Span` and `Scope` tracking)
- [x] Expand `AgentObserver` list in `AgentLoop`
- [x] `AgentOtelContextTest` (Mockito-based trace verification)

---

## Phase 10 — Health Layer (⚠️ 52 iterations — see TROUBLESHOOTING.md §10)
- [x] `PodmanHealthCheck` (`@Liveness` — Podman binary availability) <!-- id: 60 -->
- [x] `PodmanImageHealthCheck` (`@Readiness` — tool-runner image presence) <!-- id: 61 -->
- [x] `PodmanHealthCheckTest` (RestAssured health endpoint verification, port 9001) <!-- id: 62 -->
- [x] `@Alternative` + `@Priority` mock pattern for cross-module health checks <!-- id: 63 -->
- [x] `MockPodmanHealthCheck` + `MockPodmanImageHealthCheck` (standalone `HealthCheck` impls) <!-- id: 64 -->

---

## Phase 11 — Channel / REST Layer
- [x] Add `quarkus-hibernate-validator` dependency to `arcclaw-core` <!-- id: 65 -->
- [x] DTOs: `CreateSessionResponse`, `RunAgentRequest`, `SessionStatusResponse` <!-- id: 66 -->
- [x] `DefaultReasoningEngine` stub for testing <!-- id: 67 -->
- [x] `AgentResource` (`@Path("/sessions")`, `@RunOnVirtualThread`) <!-- id: 68 -->
  - [x] `POST /sessions` — create session (201) <!-- id: 69 -->
  - [x] `POST /sessions/{id}/run` — sync execution (200) <!-- id: 70 -->
  - [x] `POST /sessions/{id}/run-async` — async execution (202) <!-- id: 71 -->
  - [x] `GET /sessions/{id}/status/{role}` — load state (200/404) <!-- id: 72 -->
  - [x] `DELETE /sessions/{id}` — terminate session (204) <!-- id: 73 -->
- [x] `AgentResourceTest` in `arcclaw-app` <!-- id: 74 -->
  - [x] All endpoints `@RunOnVirtualThread`
  - [x] Bean validation on request records

---

## Phase 12 — Cross-Cutting Concerns
- [x] `ReflectionConfig` (package-level `@RegisterForReflection`)
- [x] MicroProfile context propagation config (`mp.context.propagators`)

---

## Phase 13 — Deployment Artifacts
- [x] `docker-compose.yaml` (arcclaw, ollama, otel-collector, jaeger)
- [x] `otel-collector-config.yaml`
- [x] `Dockerfile.native` (UBI 9 minimal + embedded rootless Podman)
- [x] `containers.conf` (rootless Podman storage config)
- [x] `.env` template
- [x] `.gitignore`

---

## Phase 14 — Verification & Integration Testing
- [x] JVM dev mode: `mvnw quarkus:dev -pl arcclaw-app` starts without errors
- [x] Unit tests pass: `mvnw test -pl arcclaw-core`
- [x] REST endpoint smoke test via `curl`
- [x] Health endpoints: `/q/health/live`, `/q/health/ready`
- [x] OTel traces visible in Jaeger UI (docker-compose stack)
- [x] Native image builds successfully (optional — requires GraalVM or container builder)
- [x] DRBG verification: two native runs produce different nonces

---

## Phase 14.1 — Day 2 Operations (Hardening)
- [x] JSON Parsing Circuit Breaker (`LangChainReasoningEngine`)
- [x] Context Window Observation Truncation (`4000` chars max)
- [x] Cleaned up unused imports
- [x] Verified `mvn clean test` suite integrity

---

## Phase 14.2 — Day 2 Operations (Windows Dev Ex)
- [x] Implement `@QuarkusMain` to provision `ARCCLAW_DATA_DIR` natively
- [x] Prevent `GenericJDBCException` on missing `./data` target path
- [x] Remove redundant `@Index` colliding with `@UniqueConstraint` in `AgentCheckpoint` (fixing SQLite DDL exception)

---

## Phase 14.3 — Day 2 Operations (JDK 25 Edge Cases)
- [x] Unblock Netty Unsafe Memory buffers via `maven-surefire-plugin` and `quarkus-maven-plugin` JVM args
- [x] Unblock JBoss Threads thread-local-resets by opening `java.base/java.lang`
- [x] Silence DevMode JUnit framework loader exceptions by restoring `junit` in test scope
- [x] Correct rootless `Permission denied` failure inside `Dockerfile.native` by mapping `chown` over `/app` before switching user contexts
- [x] Fix unmanaged `Thread.ofVirtual()` CDI context leakage by explicitly annotating `AgentLoop.run` with `@ActivateRequestContext`

---

## Phase 15 — Dual-Mode Binary Upgrade (quarkus-picocli)
- [x] Add `quarkus-picocli` dependency
- [x] Add `onStepComplete` default method to `AgentObserver.java`
- [x] Update `AgentLoop.runLoop()` to call `onStepComplete` with `agentRole` and `completedStep` (effectively-final fix)
- [x] Create `ArcClawCli.java` mapped via `@QuarkusMain` to boot Picocli (replaces `ArcClawApp.java`)
- [x] Create `ServeCommand.java` (`arcclaw serve`) for daemon mode (`Quarkus.waitForExit()`)
- [x] Create `AskCommand.java` (`arcclaw ask "<goal>"`) for CLI one-shot execution (`@ActivateRequestContext`)
- [x] Create `CliAgentObserver.java` static-guarded CLI output observer
- [x] Compile verified: `mvn compile` exit code 0


---

## Phase 16 — Telegram Channel Adapter
- [x] Step 1: Add `quarkus-camel-bom` to parent `pom.xml` + `camel-quarkus-telegram` to `arcclaw-app/pom.xml`
- [x] Step 2: Add `arcclaw.channel.telegram` config block to `application.yaml`
- [x] Step 3: Create `TelegramChannelRoute.java` — Camel `RouteBuilder`
- [x] Step 4: Create `TelegramAgentHandler.java` — message handler + async agent dispatch
- [x] Step 5: Telegram Integration Testing
  - [x] Create `TelegramAgentHandlerTest.java` (Mocked Executor flow)
  - [x] Create `TelegramChannelRouteTest.java` (Route discovery check)
  - [x] Document test failures and pending issues in `TROUBLESHOOTING.md`
  - [x] Verification: Resolve remaining exit-code-1 in `TelegramAgentHandlerTest`
