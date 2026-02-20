# ArcClaw v1.0 — Implementation Task Checklist

> Tracking all implementation phases derived from the [ArcClaw-v1_0-CONSOLIDATED.md](file:///h:/ws/ArcClaw/ArcClaw-v1_0-CONSOLIDATED.md) specification.

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
- [x] `GrokAgent` (`@AgentRole("grok")` — Coordinator) <!-- id: 43 -->
- [x] `HarperAgent` (`@AgentRole("researcher")` — Research) <!-- id: 44 -->
- [x] `BenjaminAgent` (`@AgentRole("coder")` — Code/Logic) <!-- id: 45 -->
- [x] `AgentResilienceTest` (5 tests: hallucination, loop limit, finish, null, exception) <!-- id: 46 -->
- [x] `LucasAgent` (`@AgentRole("creative")` — Creative & Balance) <!-- id: 47 -->
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

## Phase 10 — Health Layer
- [ ] `PodmanHealthCheck` (`@Liveness` — Podman binary availability)
- [ ] `PodmanImageHealthCheck` (`@Readiness` — tool-runner image presence)

---

## Phase 11 — Channel / REST Layer
- [ ] `AgentResource` (JAX-RS `@Path("/sessions")`)
  - [ ] `POST /sessions` — create session (201)
  - [ ] `POST /sessions/{id}/run` — sync execution
  - [ ] `POST /sessions/{id}/run-async` — async (202 Accepted)
  - [ ] `GET /sessions/{id}/status` — query status
  - [ ] `DELETE /sessions/{id}` — terminate
  - [ ] All endpoints `@RunOnVirtualThread`
  - [ ] Bean validation on request records

---

## Phase 12 — Cross-Cutting Concerns
- [ ] `ReflectionConfig` (package-level `@RegisterForReflection`)
- [ ] MicroProfile context propagation config (`mp.context.propagators`)

---

## Phase 13 — Deployment Artifacts
- [ ] `docker-compose.yaml` (arcclaw, ollama, otel-collector, jaeger)
- [ ] `otel-collector-config.yaml`
- [ ] `Dockerfile.native` (UBI 9 minimal + embedded rootless Podman)
- [ ] `containers.conf` (rootless Podman storage config)
- [ ] `.env` template
- [ ] `.gitignore`

---

## Phase 14 — Verification & Integration Testing
- [ ] JVM dev mode: `mvnw quarkus:dev -pl arcclaw-app` starts without errors
- [ ] Unit tests pass: `mvnw test -pl arcclaw-core`
- [ ] REST endpoint smoke test via `curl`
- [ ] Health endpoints: `/q/health/live`, `/q/health/ready`
- [ ] OTel traces visible in Jaeger UI (docker-compose stack)
- [ ] Native image builds successfully (optional — requires GraalVM or container builder)
- [ ] DRBG verification: two native runs produce different nonces

---

## Phase 15 — JDK 25 LTS Alignment (Future-Ready)
- [ ] All JDK 25 JEP notes documented inline (JEP 491, 506, 505, 473)
- [ ] ScopedValue migration path in `WorkspaceScopeHolder` comments
- [ ] ReentrantLock vs synchronized documented (optional on JDK 25)
- [ ] Structured Concurrency noted as preview-only (avoid in stable kernel)
