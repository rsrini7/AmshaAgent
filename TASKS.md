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
- [ ] `WorkspaceContext` bean (`@WorkspaceScoped`, non-record, no-arg constructor) — Phase 2
- [ ] `WorkspaceContextProducer` (`@Produces @WorkspaceScoped`) — Phase 2
- [x] Verify: `@WorkspaceScoped` beans resolve correctly via JUnit test

---

## Phase 2 — Security Layer (Vault & Crypto)
- [x] `VaultKey` (AutoCloseable, 32-byte AES-256 key, wipe-on-close)
- [x] `Argon2idKeyDerivation` (64 MB memory, 3 iterations, 16-byte salt)
- [x] `AesGcmSivCipher` (encrypt/decrypt/reEncrypt with nonce-misuse resistance)
- [x] `EncryptedSecret` (Panache entity) + `EncryptedSecretId` (composite key)
- [x] `SecretVault` interface
- [x] `SecretVaultImpl` (`@Dependent`, Argon2id init, batch rotation via `reEncrypt()`, memory wipe)
- [x] `AutonomyPolicy` interface + `AutonomyLevel` / `ToolOperation` enums
- [x] Unit tests: `SecretVaultImplTest` (store/retrieve, nonce misuse, AAD swap, rotation, tamper detection)
- [x] Unit tests: `WorkspaceGovernanceTest` (context injection, policy logic)

---

## Phase 3 — Sandbox Layer (ProcessIsolation)
- [ ] `@RequiresSecret` + `@RequiresSecrets` repeatable annotations
- [ ] `ProcessIsolationExecutor` (`@ApplicationScoped`)
  - [ ] Podman strategy: resource caps (`--memory`, `--cpus`, `--pids-limit`, `--ulimit`)
  - [ ] Namespace strategy (Linux `unshare` fallback)
  - [ ] Direct execution (NONE strategy for dev)
  - [ ] Circuit breaker (3 failures → NAMESPACE fallback, `@PostConstruct` check)
  - [ ] Ephemeral env-file secret injection (write → Podman reads → delete in `finally`)
  - [ ] Named containers + `activeContainerNames` tracking
  - [ ] `@PreDestroy` graceful container shutdown (10s grace)
  - [ ] OTel child span integration

---

## Phase 4 — Tool Layer
- [ ] `AgentTool` interface (`name()`, `description()`)
- [ ] `ToolRegistry` (`@ApplicationScoped`, CDI `Instance<AgentTool>`, autonomy check, secret collection)
- [ ] `GithubTool` example (`@RequiresSecret` annotations, `@Tool` LangChain4J method)

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
