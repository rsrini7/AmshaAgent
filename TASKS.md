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
- [ ] `Agent` interface (marker)
- [ ] `@AgentRole` CDI qualifier
- [ ] `ArcClawSupervisor` (`@SupervisorAgent` — LangChain4J experimental)
- [ ] `GrokAgent` stub (`@AgentRole("grok")`, OTel child span pattern)
- [ ] `HarperAgent` stub (`@AgentRole("researcher")`)
- [ ] `BenjaminAgent` stub (`@AgentRole("coder")`)
- [ ] `LucasAgent` stub (`@AgentRole("creative")`)

---

## Phase 6 — State Layer (Checkpointing)
- [ ] `AgentCheckpoint` Panache entity (with `failedAt`, `failReason` fields)
- [ ] `StatefulAgentStore` interface (checkpoint, load, complete, findInProgress, fail)
- [ ] `StatefulAgentStoreImpl` (`@ApplicationScoped`, Jackson serialization)
- [ ] `SqliteWalConfigurer` (startup WAL + `busy_timeout` PRAGMAs)

---

## Phase 7 — Lifecycle Layer
- [ ] `WorkspaceManager` (`@ApplicationScoped`)
  - [ ] Startup zombie workspace scan (`@Observes StartupEvent`)
  - [ ] Heartbeat tracking (`ConcurrentHashMap<String, Instant>`)
  - [ ] Scheduled GC (`@Scheduled`, TTL-based expiration)
  - [ ] Session destruction (CDI teardown + directory cleanup + DB mark FAILED)
- [ ] `AgentLoop` (`@ApplicationScoped`)
  - [ ] MDC session tagging
  - [ ] OTel root span lifecycle (`AgentOtelContext.start/end`)
  - [ ] Scope activation/deactivation
  - [ ] Checkpoint resume + supervisor execution
  - [ ] `terminateSession()` endpoint support

---

## Phase 8 — Governance Layer
- [ ] `GovernanceLayer` (`@ApplicationScoped`, implements `AgentObserver`)
  - [ ] Per-session `AtomicLong` token counter + `Double` cost accumulator
  - [ ] `recordLlmCall()` with token + USD math
  - [ ] Budget enforcement → `AutonomyLevel.READONLY` + `BudgetExceededException`
- [ ] `BudgetExceededException` (RuntimeException)

---

## Phase 9 — Observer / OTel Layer
- [ ] `AgentObserver` interface (`onSessionStart`, `onSessionEnd`, `onError`)
- [ ] `AgentOtelContext` (static session root span manager, child span factory)

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
