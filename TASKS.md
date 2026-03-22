# ArcClaw V1.0.0 — Implementation Task Checklist

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
- [x] `GithubTool` (`@RequiresSecret(envVar = "GH_TOKEN", vaultKey = "GITHUB_TOKEN")`, `ProcessIsolationExecutor` delegation) <!-- id: 32 -->
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
- [x] Step 6: Diagnostic Logging & Autonomy Level Rename
  - [x] Rename `READ_ONLY` -> `READONLY` and `PARTIAL` -> `SUPERVISED` in all modules
  - [x] Inject default autonomy level into `TelegramAgentHandler`
  - [x] Add log statements to verify session autonomy and completion in Telegram flow

---

## Phase 17 — Groq Cloud Migration
- [x] Repoint LangChain4J OpenAI extension to Groq API
- [x] Update `.env.example` with `GROQ_API_KEY`
- [x] Configure `application.yaml` with `llama-3.3-70b-versatile` as default
- [x] Pass `GROQ_API_KEY` through `docker-compose.yaml`
- [x] Verify production profile boots with Groq configuration

---

## Phase 18 — Sprint 2B: Human-in-the-Loop Telegram Approval Flow
- [x] Add `AWAITING_APPROVAL` to `CheckpointStatus`
- [x] Update `AgentCheckpoint` Entity with pending tool columns
- [x] Create `RequiresApprovalException`
- [x] Update `ToolRegistry` to enforce `SUPERVISED` network approvals
- [x] Update `AgentResult` record with `awaitingApproval` flag
- [x] Update `AgentLoop.runLoop()` to handle `RequiresApprovalException` and resume pending tools
- [x] Implement `WorkspaceManager` approval timeout GC sweep
- [x] Update `TelegramAgentHandler` to process `/approve` and `/deny` commands

---

## Phase 19 — Sprint 2C: Dynamic Role Routing & Day 2 Hardening
- [x] Implement keyword-based Intent Router in `TelegramAgentHandler`
- [x] Route to `brahma`, `saraswati`, or `coordinator` based on prompt matching
- [x] Bug Fix: Prevent `AgentLoop` from overwriting `AWAITING_APPROVAL` with `FAILED` on exit
- [x] Bug Fix: Enforce deterministic `baseId` and implement session wipe for completed/failed tasks
- [x] Updated SRS `REQUIREMENTS.md` with V1.0.0 baseline and roadmap

---

## Phase 20 — ArcClaw V1.0.0: State Consistency & Observability
- [x] **Sprint 3A: Persistent ChatMemoryStore**
  - [x] Create `ChatMemoryEntity` (JPA/SQLite persistence)
  - [x] Create `PersistentChatMemoryStore` (LangChain4j 0.26.1 implementation)
  - [x] Update `WorkspaceManager` to delete chat memory on session termination
  - [x] "Task Amnesia" Fix: Automated session reset for `COMPLETED`/`FAILED` tasks in `TelegramAgentHandler`
  - [x] Verify: `PersistentChatMemoryStoreTest` passed
  - [x] Verify: Realtime testing of Secret code check with Telegram - `Every message is treated as a completely isolated task... The bot has the memory of a goldfish. It cannot answer follow-up questions about the previous message because it intentionally wiped its own memory.`
- [x] **Sprint 3B: LLM Intent Router (Groq-backed classification)**
  - [x] Create `IntentClassifierService.java` (LLM-based role determination)
  - [x] Create `ModelProducer.java` (Manual CDI producer for named model)
  - [x] Integrate intent routing into `TelegramAgentHandler`
  - [x] Implement dynamic role discovery for `/approve` and `/deny`
  - [x] Verify: `TelegramAgentHandlerTest` routing logic
- [x] **Sprint 3C: Prometheus Metrics (Micrometer + AgentObserver)**
  - [x] Add `quarkus-micrometer-registry-prometheus` dependency
  - [x] Create `MetricsAgentObserver.java`
  - [x] Create `prometheus.yml` in project root
  - [x] Add `prometheus` service to `docker-compose.yaml`
  - [x] Verify `/q/metrics` endpoint and Prometheus scrape status
- [x] **Sprint 3D: ShivaAgent ("shiva") Integration**
  - [x] Integrate `shiva` role into `IntentClassifierService.java`
  - [x] Update `TelegramAgentHandler.java` role discovery loop to include `shiva`
  - [x] Verify intent routing: "fixing" vs "creating" logic
  - [x] Verify: Metrics tracking for `shiva` role
- [x] **Sprint 3E: CLI Hardening (%cli profile & ps1 wrapper)**
  - [x] Create `%cli` profile in `application.yaml` to silence Quarkus/Hibernate logs
  - [x] Create `arcclaw-cli.ps1` PowerShell wrapper for Windows users
  - [x] Update `README.md` with Option C (Silent CLI) instructions
- [x] **Sprint 4A: CLI Subcommands (Chat & Attach)**
  - [x] Create `ChatCommand.java` (REPL)
  - [x] Create `AttachCommand.java` (Daemon)
  - [x] Register subcommands in `ArcClawCli.java`
  - [x] Verify REPL interaction and session reset
  - [x] Verify attach daemon mode
  - [x] Inject ArcClaw Trimurti/Tridevi persona into `LangChainReasoningEngine`
  - [x] Force JVM UTF-8 encoding in `arcclaw-cli.ps1`
- [x] **Sprint 4B: Pre-Release Hardening & Agent UX**
  - [x] Fix "Goldfish Memory" bug in `TelegramAgentHandler` (soft wipe on COMPLETE)
  - [x] Fix "Amnesia on Error" by updating the `/approve` continuation prompt
  - [x] Fix "OpenClaw Hallucination" by adding negative constraints to the system prompt
  - [x] Add an escape hatch in the system prompt for generic conversational replies
  - [x] Route GithubTool execution to an isolated workspace directory (`.arcclaw/workspaces/{sessionId}`)
  - [x] Fix "Phantom Memory" by fetching history before LLM request in `LangChainReasoningEngine`
  - [x] Fix "Amnesia on Approval" by persisting user goal at the start of `AgentLoop`
  - [x] Fix "Double-Prompt Echo" by removing redundant goal appending in engine
  - [x] Fix "Ghost of the Denied Prompt" by injecting Closure Message on `/deny`
  - [x] Prevent Internal Noise Contamination by filtering system commands from history
  - [x] Refactor: Centralize ProcessIsolationExecutor invocation in ToolRegistry 
    (Deviation #18) — closes sandbox opt-in security gap

---

## Phase 24 — Context Poisoning & Tool Bias Fix
- [x] Step 1: Add `/reset` command to `TelegramAgentHandler.java` <!-- id: 75 -->
- [x] Step 2: Add `agentRole` to `ReasoningEngine.nextStep()` interface <!-- id: 76 -->
- [x] Step 3: Implement `STRICT TOOL BINDING CONTRACT` in `LangChainReasoningEngine.java` <!-- id: 77 -->
- [x] Step 4: Update `AgentLoop.java` call sites <!-- id: 78 -->
- [x] Step 5: Fix test compilation and argument matching in all test modules <!-- id: 79 -->
- [x] Step 6: Full verification build (`mvn clean verify`) <!-- id: 80 -->
- [x] Step 7: Update `DEVIATIONS.md` with Deviation #19 <!-- id: 81 -->
- [x] Step 8: Document and sync all Phase 24 changes across `.md` files <!-- id: 82 -->

---

## Phase 25 — Sandbox Isolation Hardening
- [x] Step 1: Ingest `podman-path` into `ProcessIsolationExecutor.java` <!-- id: 87 -->
- [x] Step 2: Extract and log raw `STDERR` from failing Podman processes <!-- id: 88 -->
- [x] Step 3: Use system `PATH` discovery for `podman` instead of hardcoding paths <!-- id: 89 -->
- [x] Step 4: Create standalone `WSL2_SETUP_GUIDE.md` and `MAC_SETUP_GUIDE.md` <!-- id: 90 -->
- [x] Step 5: Update `TESTING_GUIDE.md` and `README.md` cross-references <!-- id: 91 -->
- [x] Step 6: Verify build and logging via `mvn compile` and manual socket break <!-- id: 92 -->

---

## Phase 26 — Security Hardening & Secret Injection Pipeline
- [x] Step 1: Implement `DevSecretSeeder` for host env token injection during JVM boot <!-- id: 93 -->
- [x] Step 2: Secure byte[] secret pipeline and remove String materialization in `ProcessIsolationExecutor` <!-- id: 94 -->
- [x] Step 3: Enforce Atomic POSIX file permissions for ephemeral `.env` creation <!-- id: 95 -->
- [x] Step 4: Add explicit timeouts and `proc.exitValue()` tracking to catch silent sandbox failures <!-- id: 96 -->
- [x] Step 5: Fail-closed `resolveSecrets` dropping `System.getenv()` falls and throwing `SecurityException` <!-- id: 97 -->
- [x] Step 6: Concurrency hardening: Add `volatile` keyword to `VaultKey.destroyed` <!-- id: 98 -->
- [x] Step 7: Document GH_TOKEN rename and sandbox `bridge` networking <!-- id: 99 -->

---

## Phase 27 — Tool Flexibility & Proxy Unwrapping
- [x] Step 1: Unwrap Quarkus CDI Proxies in `ToolRegistry` to expose `@RequiresSecret` <!-- id: 100 -->
- [x] Step 2: Implement dynamic subcommands to `GithubTool.java` (removing hardcoded commands) <!-- id: 101 -->
- [x] Step 3: Upgrade `IOException` to `ToolExecutionException` in `ProcessIsolationExecutor` <!-- id: 102 -->
- [x] Step 4: Overhaul `application.yaml` logging to suppress JVM security cert events <!-- id: 103 -->
- [x] Step 5: Document `TROUBLESHOOTING.md` sections 18-20 for proxy faults and tool failures <!-- id: 104 -->

---

## Phase 28 — Intent Hardening & Auto-Cancellation
- [x] Step 1: Implement auto-cancellation loop for dangling approvals in `TelegramAgentHandler` <!-- id: 105 -->
- [x] Step 2: Extract numbered guardrails into `IntentClassifierService` prompt <!-- id: 106 -->
- [x] Step 3: Harden routing logic to prioritize Actions (Create) over Context (GitHub) <!-- id: 107 -->
- [x] Step 4: Ensure free-text messages force-fail pending tool states <!-- id: 108 -->
- [x] Step 5: Document Troubleshooting items #20 and #21 <!-- id: 109 -->

---

## Phase 29 — Memory Optimization & History Filtering
- [x] Step 1: Configure `quarkus.langchain4j.chat-memory.max-messages: 20` in `application.yaml` <!-- id: 110 -->
- [x] Step 2: Implement identity-aware rolling window in `PersistentChatMemoryStore` <!-- id: 111 -->
- [x] Step 3: Implement internal protocol message filtering in `LangChainReasoningEngine` <!-- id: 112 -->
- [x] Step 4: Add JUnit coverage for rolling window and filtering logic <!-- id: 113 -->
- [x] Step 5: Verify build success and test passes (13/13) <!-- id: 114 -->

---

## Phase 30 — Reflex Registry & Test Infrastructure Hardening
- [x] Step 1: Create `AgentReflex` interface for LLM-bypass commands
- [x] Step 2: Implement `ReflexRegistry` with CDI discovery and log injection guards
- [x] Step 3: Implement standard reflexes: `HaltReflex`, `StatusReflex`, `ResetReflex`, `ApproveReflex`, `DenyReflex`
- [x] Step 4: Inject `ReflexRegistry` into `TelegramAgentHandler` to replace hardcoded intercepts
- [x] Step 5: Expand `GovernanceLayer` API with `getSessionCost()`
- [x] Step 6: Test Infrastructure: Document T-1 to T-6 troubleshooting scenarios
- [x] Step 7: Inject `--add-opens java.base/java.lang=ALL-UNNAMED` to parent `pom.xml` for Java 24+ compatibility
- [x] Step 8: Verify build success and test passes

---

## Phase 31 — PiiScanReflex (Local PII Redaction)
- [x] Step 1: Add `sanitize` method to `AgentReflex` interface
- [x] Step 2: Implement Two-Pass processing inside `ReflexRegistry` replacing `Optional` with `ReflexResult`
- [x] Step 3: Create `PiiScanReflex` with 10 exact PII regex patterns and Configurable Mode (`REDACT` vs `BLOCK`)
- [x] Step 4: Fix regex matching priority and implement negative lookarounds for Aadhaar (`+91` collision)
- [x] Step 5: Route strict `sanitizedInput` into `IntentClassifierService` and `AgentLoop` in `TelegramAgentHandler`
- [x] Step 6: Expand `PiiScanReflexTest` covering mixed inputs, idempotency, and mode-specific `matches()` behavior
- [x] Step 7: Finalize Mockito configuration in `TelegramAgentHandlerTest` to default to `sanitizedInput`

---

## Phase 32 — REST API Authentication & Telegram Security
- [x] Step 1: Create `AuthErrorResponse` and `RateLimitErrorResponse` records
- [x] Step 2: Create `ClockProducer` and `ApiKeyRegistry` with rate limiting logic
- [x] Step 3: Implement `ApiKeyAuthFilter` for API key validation on JAX-RS endpoints
- [x] Step 4: Create `TelegramAllowlistGuard` for Telegram user authorization
- [x] Step 5: Update `TelegramAgentHandler.java` to block unauthorized users
- [x] Step 6: Add application.yaml properties for API keys and allowlists
- [x] Step 7: Fix JAX-RS endpoint test failures due to missing authentication headers
- [x] Step 8: Verify build success and test passes

---

## Phase 33 — Prompt Injection & Adversarial Defense
- [x] Step 1: Update `AgentReflex` execute signature to accept raw `input`
- [x] Step 2: Implement 15 injection vectors inside `PromptInjectionReflex.java` with 4 categorical tags
- [x] Step 3: Update `ReflexRegistry` and implementors (`PiiScanReflex`, `HaltReflex`, etc.)
- [x] Step 4: Write comprehensive tests inside `PromptInjectionReflexTest` and `PromptInjectionReflexDisabledTest` due to `@TestProfile` limitations
- [x] Step 5: Test that `PromptInjectionReflex` shortcircuits before `PiiScanReflex`
- [x] Step 6: Update `application.yaml` with the `arcclaw.security.prompt-injection.enabled` block
- [x] Step 7: Fix regex boundary logic to properly capture optional trailing words
- [x] Step 8: Verify test suite and update documentation

---

## Phase 34 — Tool Schema & Agent Tooling
- [x] Step 1: Implement `parametersSchema()` default on `AgentTool`
- [x] Step 2: Override `parametersSchema()` in `GithubTool` with action/command/target
- [x] Step 3: Implement `ToolSpecification` builder parameter injection in `LangChainReasoningEngine`
- [x] Step 4: Implement `allowedTools` filtering in `LangChainReasoningEngine` (Schema Jailbreak fix)
- [x] Step 5: Fix T-4 truncated comment in `AgentTool.java`
- [x] Step 6: Verify full build suite (`mvn clean verify`)
- [x] Step 7: Update system documentation (PLAN, TASKS, TROUBLESHOOTING)

---

## Phase 35 — Immutable Audit Log (WORM)
- [x] Create `AuditEvent` JPA Entity (id, sessionId, userId, eventType, hmacFields, etc.)
- [x] Implement `AuditEventRepository` (HMAC chain generation, findLatestHash)
- [x] Create `AuditService` API for loop-level and security-level events
- [x] Implement `AuditVerifier` (Chain validator)
- [x] Create `AuditSchemaEnforcer` (SQLite triggers `prevent_audit_update/delete`)
- [x] Wire `AuditService` into:
  - [x] `ToolRegistry` (INVOKED/BLOCKED)
  - [x] `ReflexRegistry` (PROMPT_INJECTION/PII/APPROVAL)
  - [x] `SecretVaultImpl` (STORE/RETRIEVE)
  - [x] `StatefulAgentStoreImpl` (CHECKPOINT)
  - [x] `AgentLoop` (Loop completion)
  - [x] `TelegramAgentHandler` (LIFECYCLE)
  - [x] `ApiKeyAuthFilter` (AUTH)
- [x] Create `AuditResource` REST API (`GET /api/v1/audit/verify`)
- [x] Add monotonic `sequence` field for millisecond-safe HMAC chaining
- [x] Verify: All 4 audit test suites pass

---

## Phase 36 — OTel Fixes, REST HITL, Zombie Recovery & Scope Audit
- [x] Fix OTel token accumulation (ConcurrentHashMap + AtomicLong)
- [x] Fix OTel child span parenting to root session span
- [x] Implement REST HITL endpoints: `POST /api/v1/sessions/{id}/approve` and `/deny`
- [x] Implement `AWAITING_APPROVAL` zombie recovery in `WorkspaceManager.onStartup()`
- [x] Audit `WorkspaceScopeHolder.set()` call sites for try-finally `clear()`
- [x] Resolve split-package test compilation issues (moved tests to `arcclaw-core`)
- [x] Resolve Mockito OTel mock failures via standalone `@Alternative` HealthCheck pattern
- [x] Verify: Full build (`mvn clean verify`) passes with 71/71 tests

## Phase 36.1 — Post-Integration Hardening
- [x] Read-Only Enforcement for GithubTool (Schema + Java Guards)
- [x] AgentLoop Poisoned History Fix (JSON serialization)
- [x] AgentLoop LLM HTTP 400 Self-Healing (Retry mechanism)
- [x] MetricsChatModelListener NPE Suppression (Log level ERROR)
- [x] Documentation Sync (PLAN, TASKS, TROUBLESHOOTING, WALKTHROUGH)
- [x] Verify: GithubToolTest and AgentLoopTest pass

## Phase 36.2 — WorkspaceManager Persistence Fix
- [x] Clear JVM in-memory ChatMemory cache via `.clear()`
- [x] Update `terminateSession` and `resetSessionState` in `WorkspaceManager`
- [x] Add architectural comments for re-persistence behavior

## Phase 37 — Security Hardening, Caffeine & Optimistic Locking
- [x] Implement `StartupSecurityValidator` (Prod/Staging secret guards)
- [x] Replace `ConcurrentHashMap` with `Caffeine` in `ApiKeyRegistry` (Rate limit eviction)
- [x] Add `@Version` to `AgentCheckpoint` (Optimistic locking)
- [x] Add `@Version` to `EncryptedSecret` (Optimistic locking)
- [x] Optimize `WorkspaceManager.gcSweep()` (Bulk query for AWAITING_APPROVAL)
- [x] Verify: All 5 Phase 37 test suites pass (21 tests total)
- [x] Verify: Hibernate 6 Proxy compatibility in `WorkspaceManagerGcSweepTest`

---

## Phase 38 — Context Compaction
- [x] Create `CompactionSummaryEntity` (JPA/SQLite persistence without Panache)
- [x] Create `CompactionSummaryRepository`
- [x] Config `CompactionModelProducer` for `@Identifier("compaction")` LangChain model
- [x] Implement `ContextCompactionService` logic and prompt definition 
- [x] Integrate `ContextCompactionService` in `TelegramAgentHandler`
- [x] Fix: Camel thread blocking (move compaction inside `executor.submit`)
- [x] Modify `ReasoningEngine` and implementors to require `sessionId`
- [x] Update `LangChainReasoningEngine` to inject prior summaries
- [x] Inject `sessionId` parameter across all tests (`AgentResilienceTest`, `AgentLoopTest`, etc.)
- [x] Add `CONTEXT_COMPACTED` to `SecurityEventType`
- [x] Create `CompactionTestProfile` with mock `quarkus.langchain4j.groq.api-key`
- [x] Add unit tests `ContextCompactionServiceTest`
- [x] Add integration tests `ContextCompactionServiceIntegrationTest`
- [x] Verify: All related integration and unit tests pass
- [x] Hardening: Update `ChatLanguageModel` to object-oriented API (`UserMessage`)
- [x] Hardening: Standardize Groq config keys to MicroProfile dot-notation

# Phase 40 — Antigravity (Security & Budget) ✅
- [x] Implement destructive command override in `IntentClassifierService`
- [x] Create `BudgetWarningException` and integrate with `GovernanceLayer`
- [x] Update `AgentLoop` to intercept and log budget warnings
- [x] Extend `/status` reflex with real-time token/cost/memory metrics
- [x] Implement `/history` zero-cost reflex
- [x] Add AiMessage compliance filter to `PersistentChatMemoryStore`
- [x] Create `IntentClassifierServiceTest` and `HistoryReflexTest`
- [x] Update `GovernanceLayerTest` for budget warnings
- [x] Update `PersistentChatMemoryStoreTest` for compliance filtering
- [x] Verify: `mvn clean verify` passes all Phase 40 checks

# Phase 41: Resilience, Privacy & Observer Separation ✅
- [x] Podman Circuit Breaker (CLOSED/OPEN/HALF_OPEN) with failure-threshold.
- [x] AgentCheckpoint Field Privacy (Private fields + Accessors).
- [x] AgentCheckpoint @JsonIgnore on pendingToolParametersJson.
- [x] PII Pattern completeness (DOB, Passport-IN, GSTIN, IPv6, UPI VPA).
- [x] PII Rule Ordering fix (GSTIN/PAN, Phone/Aadhaar overlap).
- [x] GovernanceLayer decoupling from AgentObserver.
- [x] AgentLoop direct budget enforcement call.
- [x] Unit Tests: ProcessIsolationExecutorTest, AgentCheckpointTest, PiiScanReflexTest.
- [x] Verification: `mvn clean verify` passes all Phase 41 checks.

# Phase 42: ScopedValue & Virtual Thread Context Migration ✅
- [x] Migrate `WorkspaceScopeHolder` to `ScopedValue<String>`.
- [x] Implement `PROPAGATION_BRIDGE` (`ThreadLocal`) for MicroProfile compatibility.
- [x] Implement `run(sessionId, Runnable)` and `call(sessionId, Callable)` binding.
- [x] Implement `wrap(Runnable)` and `wrap(Callable)` for async propagation.
- [x] Re-introduce `set(sessionId)` and `clear()` as aliases for the bridge.
- [x] Fix: Checked exceptions handling in `ScopedValue.CallableOp` wrapper.
- [x] Verification: `mvn clean verify` passes all Phase 42 checks.

# Phase 43: Secure Secret Passing ✅
- [x] Implement `TmpfsSecretTransport` for memory-resident secret creation.
- [x] Refactor `ProcessIsolationExecutor` to use `TmpfsSecretTransport`.
- [x] Implement zero-copy `byte[]` passing and immediate memory zeroing.
- [x] Implement atomic POSIX permission setting (`rw-------`).
- [x] Implement graceful degradation to `java.io.tmpdir` for dev environments.
- [x] Hardening: Intercept Podman `exit 125` to force circuit breaker fallback.
- [x] Hardening: Standardize error propagation using `ToolExecutionException`.
- [x] Unit Tests: `TmpfsSecretTransportTest` for creation, zeroing, and cleanup.
- [x] Verification: `mvn clean verify` passes all Phase 43 checks.

---

## Phase 44 — Production Hardening & Tool Refinement
- [x] Apply `transaction_mode=IMMEDIATE` to production JDBC URL <!-- id: 115 -->
- [x] Propagate TCCL to `AsyncPersistenceEngine` consumer thread <!-- id: 116 -->
- [x] Implement automatic MDC `sessionId` propagation in `WorkspaceScopeHolder` <!-- id: 117 -->
- [x] Enhance `isSqliteBusy` to detect Hibernate lock exceptions <!-- id: 118 -->
- [x] Fix `gh gist list` command syntax in `GithubTool` <!-- id: 119 -->
- [x] Add guardrail for `gist view` in `GithubTool` <!-- id: 120 -->
- [x] Verify: `GithubToolTest` passes <!-- id: 121 -->

# Phase 45: Agent Memory RAG via SQLite FTS5 ✅
- [x] Initial Implementation: Isolated FTS schema, manual sync repository, and tenant-isolated search. <!-- id: 122 -->
- [x] Implement `FtsConnectionInterceptor` for thread-safe database schema attachment. <!-- id: 123 -->
- [x] Refactor `upsertSummary` and `compactIfNeeded` for explicit `userId` parameter passing. <!-- id: 124 -->
- [x] Convert `AuditSchemaEnforcer` seed logic to passive, idempotent `COUNT(*)` guard. <!-- id: 125 -->
- [x] Verify: All core and app integration tests pass with 0 regressions. <!-- id: 126 -->

---

# Phase 46: Proactive Autonomy via Agent-Writable Cron ✅
- [x] Create `agentcron` schema + DDL migrations (`CronSchemaEnforcer`) <!-- id: 127 -->
- [x] Implement split-transaction repository for SQLite WAL concurrency <!-- id: 128 -->
- [x] Implement `ScheduleTaskTool` + `ManageScheduleTool` with `cron-utils` <!-- id: 129 -->
- [x] Implement fast-path listing/deletion reflexes <!-- id: 130 -->
- [x] Implement `AgentCronScheduler` poll-fire cycle via virtual threads <!-- id: 131 -->
- [x] Implement Fork Bomb prevention (tool filtering in cron sessions) <!-- id: 132 -->
- [x] Add `BACKGROUND` autonomy level and security policy logic <!-- id: 133 -->
- [x] Enforce production Telegram allowlist check at startup <!-- id: 134 -->
- [x] Verify: Full system regression (248/248 tests pass) <!-- id: 135 -->

# Phase 47: Unified Capabilities & Context Modernization (Tier 1) ✅
- [x] Implement `ToolCapability` enum & `RoleDefinition` record <!-- id: 136 -->
- [x] Centralize permissions in `AgentRoleEnum` <!-- id: 137 -->
- [x] Refactor `RoleRegistry` for delegated capability lookup <!-- id: 138 -->
- [x] Implement `ArchitectAgent` role with GitHub+Memory Search tools <!-- id: 139 -->
- [x] Migrate `WorkspaceContext` from CDI to `ScopedValue` <!-- id: 140 -->
- [x] Update 6 production callers to `WorkspaceContext.run()` API <!-- id: 141 -->
- [x] Implement Bucket4j rate limiting in `ApiKeyRegistry` <!-- id: 142 -->
- [x] Remove dead path-guard code from `ApiKeyAuthFilter` <!-- id: 143 -->
- [x] Verify: Tier 1 regression suite (all tests green) <!-- id: 144 -->

# Phase 47: Message Pipeline & Sealed Interface Refactor (Tier 2A) ✅
- [x] Refactor `ChannelMessage` as a sealed interface <!-- id: 145 -->
- [x] Create `MessagePipeline` and `MessageHandler` architecture <!-- id: 146 -->
- [x] Implement `AllowlistHandler` and `SessionResolutionHandler` <!-- id: 147 -->
- [x] Implement `IntentClassificationHandler` and `CommandDispatchHandler` <!-- id: 148 -->
- [x] Implement `ReflexHandler` and `AgentDispatchHandler` <!-- id: 149 -->
- [x] Implement definitive CDI fix: move implementations to `arcclaw-core` <!-- id: 150 -->
- [x] Fix `arcclaw-core` test boot failures (dummy `GROQ_API_KEY`) <!-- id: 151 -->
- [x] Verify: Telegram integration via `TelegramAgentHandlerTest` <!-- id: 152 -->
- [x] Verify: Full project clean build `mvnw clean package` (all tests green) <!-- id: 153 -->
# Phase 47: Refined Routing & Templates (Tier 2B) ✅
- [x] Component 10: Decouple AgentCronScheduler via CDI Events <!-- id: 154 -->
- [x] Component 11: Migrate to Qute Prompt Templates <!-- id: 155 -->
- [x] Component 12: Dynamic IntentClassifierService Prompt <!-- id: 156 -->
- [x] Component 13: TelegramMarkdownV2Renderer & Replier Refactor <!-- id: 157 -->
- [x] Component 14: REST Adapter Exhaustive Pattern Matching & DTOs <!-- id: 158 -->
- [x] Verify: AgentResource exhaustive status mapping <!-- id: 159 -->
- [x] Verify: Full project clean build `mvnw clean package` (256/256 tests green) <!-- id: 160 -->
# Phase 47: Agent State Machine Refactor (Tier 3) ✅
- [x] Create `AgentStateMachineRunner.java` to encapsulate thought-loop logic <!-- id: 161 -->
- [x] Refactor `AgentLoop.java` to delegate to `AgentStateMachineRunner` <!-- id: 162 -->
- [x] Extract circuit breaker, budget guard, and HITL logic into the runner <!-- id: 163 -->
- [x] Verify: `AgentStateMachineRunnerTest` green <!-- id: 164 -->

# Phase 47: Agent Resilience & Engine Fixes (Tier 3A) ✅
- [x] Fix Engine Delegation: Accept `ReasoningEngine` in `AgentStateMachineRunner.run()` <!-- id: 165 -->
- [x] Fix Context Population: Populate `AgentExecutionContext` in all `AgentLoop.run()` methods <!-- id: 166 -->
- [x] Fix `AgentStateMachineRunnerTest` compilation failures <!-- id: 167 -->
- [x] Verify: `AgentResilienceTest` (arcclaw-app) GREEN <!-- id: 168 -->
- [x] Verify: Full project `mvnw clean verify` GREEN (269/269 tests) <!-- id: 169 -->

# Phase 48: Cron Concurrency, Semaphore Hardening, Telegram Bot Refactor & Intent, Governance Hardening, Implement HMAC-based constant-time API key verfication & MessageTag Compliance ✅
- [x] Fix Semaphore Permit Leak: Hoist `concurrencyLimiter.release()` to a master `finally` block on the virtual thread lambda. <!-- id: 170 -->
- [x] Fix MDC Pollution: Move `MDC.put/remove` outside `executeCronJob` to the outer dispatcher lambda. <!-- id: 171 -->
- [x] Harden Exception Handling: Implement `executionEntered` AtomicBoolean to handle Throwables that escape before job entry. <!-- id: 172 -->
- [x] Refactor Concurrency Tests: Replace unstable `CyclicBarrier` with triple-latch (`CountDownLatch`) synchronization for Windows stability. <!-- id: 173 -->
- [x] Add Test Hooks: Implement `resetConcurrencyLimit` and `getAvailablePermits` for deterministic test verification. <!-- id: 174 -->
- [x] Verify: `AgentCronSchedulerConcurrencyTest` and `AgentCronSchedulerTest` GREEN (13 tests). <!-- id: 175 -->
- [x] Verify: Full system regression (282/282 tests pass). <!-- id: 176 -->
- [x] Telegram Bot Refactor (Component-Level Token)
  - [x] Refactor `TelegramChannelRoute` to use component-level authorizationToken
  - [x] Update `application.yaml` with `camel.component.telegram.authorization-token`
  - [x] Verify all Telegram URIs are token-free
  - [x] Update/Fix `TelegramChannelRouteTest` with proper mocking/component binding
  - [x] Restore `ApproveReflex` confirmation message (fixed `ApproveReflexTest`)
- [x] Intent & Governance Hardening ✅
  - [x] Implement prompt injection defense with `<<<USER_INPUT_START/END>>>` delimiters in `IntentClassifierService` <!-- id: 177 -->
  - [x] Implement input sanitization to strip injection delimiters <!-- id: 178 -->
  - [x] Switch `IntentClassifierService` to synchronous execution with 3s SLA logging <!-- id: 179 -->
  - [x] Fix `ARCHITECT` role name sanitization in classifier <!-- id: 180 -->
  - [x] Implement pathological zero-token guard (0,0 -> 1,1) in `GovernanceLayer` <!-- id: 181 -->
  - [x] Ensure legitimate (N, 0) token counts are NOT inflated in `GovernanceLayer` <!-- id: 182 -->
  - [x] Disable Telegram route if bot token is missing or placeholder (`mock-token`) <!-- id: 183 -->
  - [x] Set `OTEL_SDK_DISABLED=true` by default in `application.yaml` <!-- id: 184 -->
  - [x] Add `ARCCLAW_DATA_DIR` to default configuration <!-- id: 185 -->
  - [x] Disable Quarkus console/reload in native profile <!-- id: 186 -->
  - [x] Update `MAC_SETUP_GUIDE.md` with OTel and CLI hardening args <!-- id: 187 -->
  - [x] Verify: All new hardening tests (zero-token, delimiters, sanitization) pass <!-- id: 188 -->
- [x] Implement HMAC-based constant-time API key comparison in `ApiKeyRegistry.java` <!-- id: 189 -->
- [x] Update safe API key truncation in `ApiKeyAuthFilter.java` to exactly 4 chars <!-- id: 190 -->
- [x] Split test classes into `ApiKeyRegistryTest` and `ApiKeyRegistryVaryingLengthKeyTest` <!-- id: 191 -->
- [x] Verify: All HMAC-specific and regression tests pass <!-- id: 192 -->
- [x] **Startup Security Hardening (Profile Guards)** <!-- id: 193 -->
  - [x] Invert profile guard in `StartupSecurityValidator.java` (`dev`/`test` exempt) <!-- id: 194 -->
  - [x] Enforce mandatory check order and exception types <!-- id: 195 -->
  - [x] Implement "Safe Boot + Proper Unwrap" JUnit strategy <!-- id: 196 -->
  - [x] Create exhaustive test suite: `ProdUnsafe`, `UnknownProfile`, `ProdSafe`, `DevExempt`, `StringOnlyAllowlist` <!-- id: 197 -->
  - [x] Verify: All 314 tests pass <!-- id: 198 -->
- [x] Create `MessageTag` utility with stable prefix `[ARCCLAW:2f4a9c]` and null-safe `isTagged()` check <!-- id: 199 -->
- [x] Refactor `LangChainReasoningEngine` to use `MessageTag.isTagged()` for AiMessage filtering <!-- id: 200 -->
- [x] Implement dual-filter in `PersistentChatMemoryStore` for backward compatibility <!-- id: 201 -->
- [x] Add Phase 49 TODOs for legacy `isComplianceInjection` regex removal <!-- id: 202 -->
- [x] Update `testApprovalMarkerMessagesAreFilteredFromHistory` with tagged seed data <!-- id: 203 -->
- [x] Add `testTaggedAiMessageIsFilteredFromHistory` to specifically verify deterministic filtering <!-- id: 204 -->
- [x] Create `MessageTagTest` pure unit test suite <!-- id: 205 -->
- [x] Verify: All Phase 51 tests (15/15) and regression suite pass <!-- id: 206 -->

# Phase 49 — MessageTag Regex Cleanup ✅
- [x] Delete `COMPLIANCE_INJECTION_PATTERN` and legacy `isComplianceInjection()` from `PersistentChatMemoryStore.java` <!-- id: 207 -->
- [x] Simplify `updateMessages()` filter to use `MessageTag.isTagged()` only <!-- id: 208 -->
- [x] Remove obsolete `testComplianceInjectionFiltering()` from `PersistentChatMemoryStoreTest.java` <!-- id: 209 -->
- [x] Audit source for remaining legacy filter references (Grep cleanup) <!-- id: 210 -->
- [x] Verify: All tests pass with 0 compilation errors or regressions <!-- id: 211 -->
# Phase 50 P2-1 — CLI Approval Flow Completion ✅
- [x] Wire approval persistence and heartbeats in `ChatCommand.java` <!-- id: 212 -->
  - [x] Record heartbeat at session start <!-- id: 213 -->
  - [x] Record heartbeat after each user input <!-- id: 214 -->
  - [x] Record heartbeat when `AwaitingApproval` is returned <!-- id: 215 -->
- [x] Implement AwaitingApproval UI in CLI <!-- id: 216 -->
  - [x] Display tool name and truncated parameters (200 chars) <!-- id: 217 -->
  - [x] Provide clear instructions for `/approve` or `/deny` <!-- id: 218 -->
- [x] Transition CLI Chat to `AutonomyLevel.SUPERVISED` <!-- id: 219 -->
- [x] Fix cross-module build stability items <!-- id: 220 -->
  - [x] Resolve `MessageTag` symbol absence in `arcclaw-app` tests <!-- id: 221 -->
  - [x] Fix `ReflexResult` constructor parameter ordering <!-- id: 222 -->
- [x] Verify: `ChatCommandApprovalTest` passes (mocked heartbeats and UI) <!-- id: 223 -->
- [x] Verify: `terminateSession()` is correctly called in `finally` block <!-- id: 224 -->

# Phase 50 P2-3 — MemorySearchTool FTS Null Safety + Empty Result Handling ✅
- [x] Update `MemorySearchTool` to gracefully handle null/empty results from `searchMemory`
- [x] Catch FTS "no such table" exceptions and return a successful tool response to continue without memory
- [x] Catch generic exceptions and return a 500 failure code
- [x] Verify: `testEmptyFtsResultReturnsGracefulMessage`
- [x] Verify: `testFtsUnavailableReturnsGracefulMessage`
- [x] Verify: `testFtsGenericExceptionReturnsFailure`

# Phase 50 P2-4 — CronStatus Enum (Replace Raw String Status) ✅
- [x] Create `CronStatus` enum with ACTIVE, RUNNING, COMPLETED, FAILED, AWAITING_APPROVAL <!-- id: 225 -->
- [x] Implement `CronStatusConverter` JPA `AttributeConverter` <!-- id: 226 -->
- [x] Update `AgentCronEntity` to use `CronStatus` and `@Convert` <!-- id: 227 -->
- [x] Update `DenyReflex` to use enum comparison instead of string equals <!-- id: 228 -->
- [x] Create `CronStatusTest` in `arcclaw-core` <!-- id: 229 -->
- [x] Verify: `mvnw clean test -pl arcclaw-app -am` (13 scheduler/repository tests green) <!-- id: 230 -->

# Phase 50 P2-5 — Remove Dual /deny Processing (ReflexHandler Dead Code) ✅
- [x] Delete `ReflexHandler.java` (Dead code, redundant with `CommandDispatchHandler`) <!-- id: 231 -->
- [x] Integrate `/deny` audit trail via `ReflexRegistry` exclusively <!-- id: 232 -->
- [x] Verify: `DenyPipelineTest` passes (handled CLI allowlist and dynamic session IDs) <!-- id: 233 -->

# Phase 51 — TelegramChannelRoute Self-Registration (P2-6) ✅
- [x] Implement `@Observes StartupEvent` in `TelegramChannelRoute.java` <!-- id: 234 -->
- [x] Use `camelContext.getRouteController().startRoute("telegram-bot")` for explicit activation <!-- id: 235 -->
- [x] Remove manual lifecycle management from `ServeCommand` and `AttachCommand` <!-- id: 236 -->
- [x] Verify: `TelegramChannelRouteStartupTest` passes <!-- id: 237 -->
- [x] Verify: `ServeCommandTest` passes (redundant injections removed) <!-- id: 238 -->

# Phase 50 P2-8 — Dynamic System Prompt Per Role (Persona Fix) ✅
- [x] Create `AgentPersonaRegistry.java` <!-- id: 239 -->
- [x] Implement `AgentPersonaRegistryTest.java` <!-- id: 240 -->
- [x] Modify `LangChainReasoningEngine.java` to inject registry and extract rules <!-- id: 241 -->
- [x] Implement `LangChainReasoningEnginePersonaTest.java` <!-- id: 242 -->
- [x] Correct `ShivaAgent` persona definition <!-- id: 243 -->
- [x] Verify: CLI identity check for all roles (Shiva, Vishnu, Saraswati, Brahma) <!-- id: 244 -->



# Phase 51 P3-13 — ArcClawCli Fail Fast ✅
- [x] Uncomment data directory path logging <!-- id: 245 -->
- [x] Catch directory creation exceptions and fail fast via System.exit(1) <!-- id: 246 -->
- [x] Print FATAL error message with path, cause, and fix <!-- id: 247 -->
- [x] Verify: Manual test with permission failure confirms exit code 1 <!-- id: 248 -->

# Phase 51 P3-4 — Compaction maxTokens Fix ✅
- [x] Implement configurable `arcclaw.memory.compaction.max-tokens` property in `CompactionModelProducer.java` <!-- id: 249 -->
- [x] Increase default `maxTokens` to 1024 <!-- id: 250 -->
- [x] Add property to `application.yaml` <!-- id: 251 -->
- [x] Verify: Grep check confirms hardcoded 256 is removed <!-- id: 252 -->

# Phase 51 P3-3 — Destructive Vocabulary Filter Regex & Typo Fix ✅
- [x] Split destructive regex into `DESTRUCTIVE_WORDS` and `RM_RF_PATTERN` <!-- id: 253 -->
- [x] Update `DestructiveVocabularyFilter.filter()` to check both patterns <!-- id: 254 -->
- [x] Correct "Brihaspati" typo in `IntentClassifierService.sanitize()` (with fallback) <!-- id: 255 -->
- [x] Correct "Brihaspati" typo in `AgentRoleEnum.fromString()` (with fallback) <!-- id: 256 -->
- [x] Create `DestructiveVocabularyFilterTest` (8 tests) <!-- id: 257 -->
- [x] Add `brihaspati` mapping tests to `IntentClassifierServiceTest` <!-- id: 258 -->
- [x] Verify: All 12 new/updated security and routing tests pass <!-- id: 259 -->

# Phase 51 P3-6 — MessagePipeline Exception Handling Strategy ✅
- [x] Implement error notification on handler crash in `MessagePipeline.process()` <!-- id: 260 -->
- [x] Add handler priority logging at initialization in `MessagePipeline` <!-- id: 261 -->
- [x] Create `MessagePipelineErrorTest.java` (3 tests) <!-- id: 262 -->
- [x] Update `testPipelineSwallowsExceptionAndStops` in `MessagePipelineTest` <!-- id: 263 -->
- [x] Verify: All 10 pipeline-related tests pass <!-- id: 264 -->

# Phase 51 — StatefulAgentStore & Telegram Resilience ✅
- [x] P2-12/13: Implement exponential backoff with jitter in `StatefulAgentStoreImpl.withRetry()` <!-- id: 265 -->
- [x] P3-2: Switch to canonical class names for `isSqliteBusy()` detection <!-- id: 266 -->
- [x] P3-2: Add `ORDER BY updatedAt DESC LIMIT 1` to `findBySessionAndRole()` for determinism <!-- id: 267 -->
- [x] P3-8: Implement async non-text payload feedback in `TelegramAgentHandler` <!-- id: 268 -->
- [x] P3-12: Implement per-chat sequential execution queues via `ConcurrentHashMap` <!-- id: 269 -->
- [x] Create `StatefulAgentStoreRetryTest.java` and internal `TelegramAgentHandlerTest` coverage <!-- id: 270 -->
- [x] Verify: All Phase 51 resilience tests pass (320/320 system tests total) <!-- id: 271 -->

## Hardening (Bot, Security) ✅
- [x] P3-12: Replace unbounded `ConcurrentHashMap` with Caffeine cache in `TelegramAgentHandler` <!-- id: 272 -->
- [x] Add `removalListener` to Caffeine cache for graceful executor shutdown <!-- id: 273 -->
- [x] Implement error notification in `TelegramAgentHandler.processPipeline()` catch block <!-- id: 274 -->
- [x] Verify: No `ContextNotActiveException` in logs during async execution <!-- id: 275 -->
- [x] Verify: All Telegram resilience tests pass after Phase 52 updates <!-- id: 276 -->

# Vault Key Caching (Argon2id on Hot Path) ✅
- [x] Create `VaultKeyCache.java` — `@WorkspaceScoped` bean with double-checked locking, `@PreDestroy` wipe, and safety fallback <!-- id: 277 -->
- [x] Update `AgentCronScheduler.java` — add `finally` block to terminate session scope and prevent key leaks <!-- id: 278 -->
- [x] Update `SecretVaultImpl.java` — inject `VaultKeyCache`, replace `keyDerivation.deriveSessionKey()` calls with `keyCache.getOrDerive()`, remove try-with-resources on the key <!-- id: 279 -->
- [x] Create `VaultKeyCacheTest.java` — plain JUnit5 unit tests with Mockito <!-- id: 280 -->
- [x] Verify no remaining direct `deriveSessionKey` calls in `SecretVaultImpl` via grep <!-- id: 281 -->
- [x] Run and confirm `VaultKeyCacheTest` pass <!-- id: 282 -->

# Phase 52 — DattatreyaAgent: The Synthesizer Role ✅
- [x] Add DATTATREYA enum constant to AgentRoleEnum with proper RoleDefinition <!-- id: 283 -->
- [x] Update AgentRoleEnum.fromString() with 'synthesizer', 'dattatreya', and 'datta' mappings <!-- id: 284 -->
- [x] Create DattatreyaAgent.java with @AgentRole('synthesizer') and synthesis-focused prompt <!-- id: 285 -->
- [x] Create role-synthesizer.txt Qute template for the prompt engine <!-- id: 286 -->
- [x] Update PromptTemplateEngine to inject and use the synthesizer template <!-- id: 287 -->
- [x] Update LangChainReasoningEngine Trimurti/Avadhuta architecture block <!-- id: 288 -->
- [x] Update IntentClassifierService for dynamic routing of synthesis tasks <!-- id: 289 -->
- [x] Add DattatreyaAgent to AgentPersonaRegistryTest setup <!-- id: 290 -->
- [x] Create DattatreyaAgentTest.java with comprehensive persona/tool tests <!-- id: 291 -->
- [x] Verify: All 320+ system tests pass GREEN <!-- id: 292 -->
# Phase 53 — AgentCronScheduler & WorkspaceManager SRP Refactor ✅
- [x] **Phase 53.1: WorkspaceManager Split** <!-- id: 300 -->
  - [x] Create `SessionHeartbeatTracker.java` (In-memory state) <!-- id: 301 -->
  - [x] Create `SessionReaper.java` (Scheduled GC & Zombie Recovery) <!-- id: 302 -->
  - [x] Slim `WorkspaceManager.java` (Termination & Disk only) <!-- id: 303 -->
  - [x] Update `AgentResource.java` & `ChatCommand.java` to use Tracker <!-- id: 304 -->
  - [x] Add `testSweepTimedOutApprovals` to `WorkspaceManagerGcSweepTest` <!-- id: 305 -->
  - [x] Verify: All tests pass (340+ system tests total) <!-- id: 306 -->
- [x] **Phase 53.2: AgentCronScheduler SRP Refactor** <!-- id: 293 -->
  - [x] Split `AgentCronScheduler.java` (Dispatcher) <!-- id: 294 -->
  - [x] Create `CronJobRunner.java` (Execution Context) <!-- id: 295 -->
  - [x] Create `CronResultHandler.java` (State & Telemetry) <!-- id: 296 -->
  - [x] Implement AWAITING_APPROVAL session preservation logic <!-- id: 297 -->
  - [x] Add audit logging for HITL pause events <!-- id: 298 -->
  - [x] Ensure `setDeliveryId` ordering before `WorkspaceContext.run()` <!-- id: 299 -->
  - [x] Verify: Concurrency and logic tests PASS <!-- id: 307 -->

---

# Phase 53.3 — Agent State Machine Refactor ✅
- [x] Create `ToolInvocationGuard.java` (Dedup, GitHub Guard, HITL Evaluation)
- [x] Create `ToolInvoker.java` (Unified invocation + exception handling)
- [x] Create `ChatMemoryWriter.java` (Centralized chat memory persistence)
- [x] Slim `AgentStateMachineRunner.java` to stay as orchestrator
- [x] Implement `persistApprovalCheckpoint()` helper in runner
- [x] Verify: Targeted unit tests for Guard, Invoker, and Runner pass (100% success)
- [x] Verify: Final surgical fixes for safe HITL serialization and test scoping

---

# Phase 53.4 — LangChainReasoningEngine Split ✅
- [x] Create `ReasoningStepParser.java` (Response parsing & token usage)
- [x] Create `ChatHistoryAssembler.java` (Compliance filtering & goal injection)
- [x] Create `SystemPromptBuilder.java` (Persona & security rule assembly)
- [x] Slim `LangChainReasoningEngine.java` to stay as orchestrator
- [x] Verify: Comprehensive unit tests for all new components pass
- [x] Verify: `AgentLoopTest` and `LangChainReasoningEngineTest` integration passes

---

# Phase 53.5: Package Reorganization ✅
- [x] Step 1: Move `loop` classes (`AgentLoop`, `AgentStateMachineRunner`, `AgentExecutionContext`)
- [x] Step 2: Move `reasoning` classes (`LangChainReasoningEngine`, `ReasoningEngine`, `ReasoningStep`, `PromptTemplateEngine`, `MessageTag`)
- [x] Step 3: Move `persona` classes (`Agent`, `AgentRole`, `AgentPersonaRegistry`, and 6 deity agents)
- [x] Step 4: Move `role` classes (`AgentRoleEnum`, `RoleDefinition`, `RoleRegistry`, `ToolCapability`)
- [x] Step 5: Move `result` classes (`AgentResult`, `AgentState`)
- [x] Verify: All 23 classes moved out of root `com.arcclaw.core.agent`
- [x] Verify: Full system regression (266/266 tests pass)

---

# Phase 53.6 — AgentLoop & AuditService Split ✅
- [x] Extract `CheckpointResumeHandler` from `AgentLoop`
- [x] Create `CheckpointResumeHandlerTest` and move `testResumedHistoryHasFlatToolArguments`
- [x] Splitting `AuditService` into `TelemetryAuditService` and `ComplianceAuditService`
- [x] Refactor `AuditService` into a facade delegating to the new tier services
- [x] Create `TelemetryAuditServiceTest` and `ComplianceAuditServiceTest`
- [x] Update `AuditServiceTest` to test delegation logic only
- [x] Verify: Full system regression passes

---

# Phase 53.9 - AgentResource Split ✅
- [x] Create `AgentResponseMapper` with top-level response records
- [x] Create `AgentSessionService` with moved business logic
- [x] Slim `AgentResource` to delegate only
- [x] Create `AgentResponseMapperTest` (Unit)
- [x] Create `AgentSessionServiceTest` (Unit)
- [x] Verify: `AgentResourceTest` passes unchanged
- [x] Verify: Full project regression passes

---

# Phase 54: Move misplaced JUnit tests ✅
- [x] Scan `arcclaw-app/src/test/java/` for test classes.
- [x] Identify 13 test files testing `arcclaw-core` pure subjects.
- [x] Move 10 tests to `arcclaw-core` matching package under `src/test/java/`.
- [x] Revert 3 tests (`SecretVaultImplTest`, `WorkspaceScopeTest`, `AuditServiceIntegrationTest`) back to `arcclaw-app` due to Maven cyclic dependency (`core` <-> `deployment`).
- [x] Keep `AgentResourceTest` and `PodmanHealthCheckTest` in `arcclaw-app` (HTTP/App layer).
- [x] Verify: `AgentResilienceTest` correctly moved to `com.arcclaw.core.agent.loop` in `arcclaw-core`.
- [x] Verify: `arcclaw-core` tests pass (350/350).
- [x] Verify: `arcclaw-app` tests pass (121/121) after fixing reactor compilation consistency (`-am`).

---

# Phase 55.1 — JDK Modernization Sweep ✅
- [x] Replace unused catch variables and try-with-resources variables with `_` (JEP 456). <!-- id: 307 -->
- [x] Migrate `.get(0)` to `.getFirst()` and `.get(size()-1)` to `.getLast()` (Sequenced Collections). <!-- id: 308 -->
- [x] Extract inline `Pattern.compile` calls in `AgentErrorMessageUtil.java` to static final fields. <!-- id: 309 -->
- [x] Remove redundant `synchronized(history)` wrapper blocks in `AgentStateMachineRunner.java`. <!-- id: 310 -->
- [x] Verify: `mvnw clean test -pl arcclaw-core,arcclaw-app -am` passes. <!-- id: 311 -->

---

# Phase 55.2 — JDK Modernization (ScopedValue & DI) ✅
- [x] Migrate `CliAgentObserver` from `static volatile boolean` to `ScopedValue<Boolean>` <!-- id: 312 -->
- [x] Update `AskCommand` to use `ScopedValue.where()` for agent execution <!-- id: 313 -->
- [x] Fix Telegram config namespace: `arcclaw.telegram.*` -> `arcclaw.channel.telegram.*` <!-- id: 314 -->
- [x] Refactor 10 beans to Constructor Injection (Rule 10):
  - [x] `SandboxSecretResolver` <!-- id: 315 -->
  - [x] `AuditService` facade <!-- id: 316 -->
  - [x] `ComplianceAuditService` <!-- id: 317 -->
  - [x] `TelemetryAuditService` <!-- id: 318 -->
  - [x] `ChatHistoryAssembler` <!-- id: 319 -->
  - [x] `ReasoningStepParser` <!-- id: 320 -->
  - [x] `SystemPromptBuilder` <!-- id: 321 -->
  - [x] `CheckpointResumeHandler` <!-- id: 322 -->
  - [x] `CronResultHandler` <!-- id: 323 -->
  - [x] `AgentSessionService` <!-- id: 324 -->
- [x] Update test files to use constructor injection and remove `@InjectMocks`:
  - [x] `SandboxSecretResolverTest` <!-- id: 325 -->
  - [x] `AgentStateMachineRunnerTest` <!-- id: 326 -->
  - [x] `SystemPromptBuilderTest` <!-- id: 327 -->
  - [x] `ChatHistoryAssemblerTest` <!-- id: 328 -->
  - [x] `ReasoningStepParserTest` <!-- id: 329 -->
  - [x] `AgentSessionServiceTest` <!-- id: 330 -->
- [x] Verify: `mvn clean test -pl arcclaw-core,arcclaw-app -am` (121 tests passed) <!-- id: 331 -->

---

# Phase 55.3 — AgentCheckpoint Normalization ✅
- [x] Create `ReasoningStepEntity` JPA entity <!-- id: 332 -->
- [x] Mark `AgentCheckpoint.stateJson` as `@Deprecated` and add `completedAt` <!-- id: 333 -->
- [x] Add `saveAndFinalize()` to `StatefulAgentStore` and implement in `Impl` <!-- id: 334 -->
- [x] Add `loadSteps()` to `StatefulAgentStore` and implement in `Impl` <!-- id: 335 -->
- [x] Replace `save() + markCompleted()` pair in `AgentLoop.java` with `saveAndFinalize()` <!-- id: 336 -->
- [x] Update `AgentSessionService.getStatus()` to use `loadSteps()` <!-- id: 337 -->
- [x] Mark `deserializeSteps()` as `@Deprecated` (DO NOT delete) <!-- id: 338 -->
- [x] Cleanup: Remove unused `ObjectMapper` from `AgentSessionService` <!-- id: 339 -->
- [x] Verify: `mvn clean test -pl arcclaw-core,arcclaw-app -am` (All 471 tests passed) <!-- id: 340 -->

---

# Phase 56 — Hardcoded String Constant Sweep ✅
- [x] Remediate Category 1 (P0 tool names) <!-- id: 341 -->
- [x] Remediate Category 2 (role strings) <!-- id: 342 -->
- [x] Remediate Category 3 (SQLite strings) <!-- id: 343 -->
- [x] Remediate Category 4 (crypto strings) <!-- id: 344 -->
- [x] Remediate Category 5 (HTTP headers) <!-- id: 345 -->
- [x] Remediate Category 6 (MDC keys) <!-- id: 346 -->
- [x] Remediate Category 7 (approval sentinels) <!-- id: 347 -->
- [x] Verify: Full system regression passes (121/121 tests) <!-- id: 348 -->

---

## Phase 57 — Rate Limit User Notification ✅
- [x] Fix 1: Immediate Telegram Acknowledgment (`⏳`) for free-text messages <!-- id: 349 -->
- [x] Fix 2: `IntentClassificationHandler` rate limit detection and user notification <!-- id: 350 -->
- [x] Create `IntentClassificationHandlerTest` to verify 429 handling and defaults <!-- id: 351 -->
- [x] Verify: All tests pass (including specific rate limit scenarios) <!-- id: 352 -->

---

## Phase 58 — System Metadata Registry
- [x] Create `SystemMetadataRegistry` (@ApplicationScoped, resolve environment) <!-- id: 310 -->
- [x] Create `SystemMetadataHealthCheck` (@Readiness) <!-- id: 311 -->
- [x] Create `SystemInfoResource` (`/info` endpoint) <!-- id: 312 -->
- [x] Update `ApiKeyAuthFilter` to exclude `/info` from authentication <!-- id: 313 -->
- [x] Add `appVersion` field to `AuditEvent` entity <!-- id: 314 -->
- [x] Update `TelemetryAuditService` to populate `appVersion` <!-- id: 315 -->
- [x] Update `ReflectionConfig` for `SystemInfo` native serialization <!-- id: 316 -->
- [x] Verify: All Phase 58 unit and integration tests pass (122/122 total) <!-- id: 317 -->

---

## Phase 59 — MCP Server + Client Setup ✅
- [x] Create `arcclaw-mcp` module with `quarkus-mcp-server-http` and `quarkus-mcp-server-stdio` <!-- id: 410 -->
- [x] Implement `PodmanMcpTransport` for stdio isolation via `SandboxCircuitBreaker` <!-- id: 411 -->
- [x] Implement `McpClientService` for HTTP/SSE with SmallRye FT guards <!-- id: 412 -->
- [x] Create `McpAdminResource` with Tool listing and status endpoints <!-- id: 413 -->
- [x] Expose MVP tools: `GITHUB` and `MEMORY_SEARCH` <!-- id: 414 -->
- [x] Implement `MCP_TOOL_INVOKED` audit event (isMandatory=true) <!-- id: 415 -->
- [x] Verify: `McpAdminResourceTest` passes with `@InjectMock ApiKeyRegistry` and `@TestProfile` <!-- id: 416 -->
- [x] Verify: `McpAdminDisabledTest` passes with explicit `arcclaw.mcp.server-enabled=false` <!-- id: 417 -->
- [x] **Phase 59 Extension: HTTP E2E & Protocol Hardening**
  - [x] Implement `McpServerE2ETest` for handshake & tool flow <!-- id: 418 -->
  - [x] Fix: Mandatory dual `Accept` header in `McpClientService` <!-- id: 419 -->
  - [x] Fix: Handle `202 Accepted` for initialization notifications <!-- id: 420 -->
  - [x] Verify: Full project regression (535/535 tests pass) <!-- id: 421 -->


---

## Phase 60 — Dual-Tier Skill Registry
- [x] Create `CompiledSkillEntry` JPA Entity for persistence <!-- id: 400 -->
- [x] Implement `AgentSkillRepository` with pure `EntityManager` <!-- id: 401 -->
- [x] Refactor `SkillLoader` to sync database skills into `SkillRegistry` <!-- id: 402 -->
- [x] Implement `SkillRegistry.reload()` with `LinkedHashMap` deduplication <!-- id: 403 -->
- [x] Create `SkillResource` REST endpoints with `@RunOnVirtualThread` <!-- id: 404 -->
- [x] Apply `@Transactional` to `SkillResource` for SQLite isolation safety <!-- id: 405 -->
- [x] Verify: `SkillApiTest` passes (5 tests) <!-- id: 406 -->
- [x] Verify: `SkillPersonaRoutingTest` passes (2 tests) <!-- id: 407 -->
- [x] Verify: Full project regression (173/173 tests) <!-- id: 408 -->


---

## Phase 60.1 Quarkus LTS Migration (3.23.0 -> 3.27.2) ✅
- [x] Update `quarkus.version` to `3.27.2` (Hibernate ORM 7.1) <!-- id: 450 -->
- [x] Migrate LangChain4j to 1.x and update config property renames <!-- id: 451 -->
- [x] Standardize `@Version` and `@Id` across all JPA entities <!-- id: 452 -->
- [x] Implement `ArcClawSQLiteDialect` to resolve ORM 7 DDL identity bug <!-- id: 453 -->
- [x] Move SQLite FTS attachment from `initial-sql` to runtime code (`SqliteWalConfigurer`) <!-- id: 454 -->
- [x] Register Caffeine-generated classes (`SSLMSA`) for native reflection <!-- id: 455 -->
- [x] Replace static `Random` with `ThreadLocalRandom` to fix Native Image heap leakage <!-- id: 456 -->
- [x] Pre-compile critical HQL queries for native image performance <!-- id: 457 -->
- [x] Annotate Qute template data records (`AgentResult`, `RoleDefinition`, `AgentRoleEnum`, `ReasoningStep`) with `@TemplateData` <!-- id: 458 -->
- [x] Clean up `application.yaml`: drop unsupported `langchain4j.groq` keys, remove duplicate `schema-management`, fix `%test` nesting, and increase tx timeout to 300s <!-- id: 459 -->
- [x] Verify: 535/535 tests pass and native build succeeds <!-- id: 460 -->

---

## Phase 61 — Semantic Vector RAG (Dual-Tier: sqlite-vec + InMemory) ✅
- [x] Create `arcclaw-rag` module with `langchain4j-core` and `embedding-model-openai` <!-- id: 461 -->
- [x] Configure `FtsConnectionInterceptor` for native `sqlite-vec` extension loading <!-- id: 462 -->
- [x] Implement `RagSchemaInitializer` for `vec_global` virtual table (Raw DDL) <!-- id: 463 -->
- [x] Implement Native Graceful Degradation: AtomicBoolean check for `vec0` availability <!-- id: 464 -->
- [x] Create `TwoTierMemoryService` with Reciprocal Rank Fusion (RRF) logic <!-- id: 465 -->
- [x] Integrate `RagContextEnricher` into `LangChainReasoningEngine` <!-- id: 466 -->
- [x] Register RAG classes for reflection in `AppReflectionConfig` <!-- id: 467 -->
- [x] Add `SQLITE_VEC_PATH` to `.env.example` and `application.yaml` <!-- id: 468 -->
- [x] Implement `IngestDocumentTool` for agent-driven ingestion <!-- id: 469 -->
- [x] Verify: `TwoTierMemoryServiceRrfTest` passes math validation <!-- id: 470 -->
- [x] Verify: Manual test with `vec0.dll` confirm semantic search is active <!-- id: 471 -->
- [x] Documentation: Update `PLAN.md`, `WALKTHROUGH.md`, and `TROUBLESHOOTING.md` <!-- id: 472 -->

---

## Phase 61 Bug Fixes — RAG Persistence & Merging ✅
- [x] Fix Bug 2A: Persist raw text in `GlobalKnowledgeBase` using `CompactionSummaryEntity` <!-- id: 503 -->
- [x] Fix Bug 2B: Update `TwoTierMemoryService` to merge non-empty text during RRF <!-- id: 504 -->
- [x] Verify: Run `TwoTierMemoryServiceRrfTest` for fusion integrity <!-- id: 505 -->

---

## Phase 62 - Build-Time BAML Schema Generation ?
- [x] Diagnose schema generation failure in `arcclaw-deployment`
- [x] Update `StructuredOutputEngine` to use Thread Context ClassLoader for loading resources
- [x] Fix Bug 1: Resolve Maven test failures due to split-package / duplicate tests (`WorkspaceScopeTest`)
- [x] Verify: `StructuredOutputEngineSchemaTest` passes, proving schema gets loaded correctly
- [x] Verify: Run full project tests with `mvn test`
- [x] Documentation: Update `PLAN.md`, `TASKS.md`, `WALKTHROUGH.md`, and `TROUBLESHOOTING.md`

---

## Phase 63-A — VaultKeyCache: ReentrantLock Refactor ✅
- [x] Refactor `VaultKeyCache.java`: Replace `synchronized(this)` with instance-field `ReentrantLock` for virtual thread safety <!-- id: 506 -->
- [x] Implement double-checked locking with `volatile` cachedKey for optimal performance <!-- id: 507 -->
- [x] Update `VaultKeyCacheTest.java`: Add `testConcurrencyWithVirtualThreads` using `CountDownLatch` <!-- id: 508 -->
- [x] Add `testIdempotency` and `testSafetyFallbackIfKeyDestroyedExternally` to the test suite <!-- id: 509 -->
- [x] Verify: Run `VaultKeyCacheTest` and confirm `BUILD SUCCESS` <!-- id: 510 -->
- [x] Documentation: Update `PLAN.md`, `TASKS.md`, `WALKTHROUGH.md`, and `TROUBLESHOOTING.md` <!-- id: 511 -->

---

## Phase 63-B — Resume Signal Mechanism Refactor ✅
- [x] Replace string-based resume checks with structured boolean `isResumeSignal` in `MessageContext`
- [x] Delete `RESUME_GOAL_PREFIX` from `AgentSentinels.java`
- [x] Introduce new constants `GOAL_TOOL_APPROVED`, `GOAL_RESUME_APPROVED`, and `GOAL_RESUME_DENIED` in `AgentSentinels`
- [x] Update `ReflexResult` factory methods to incorporate `MessageContext`
- [x] Update all `Reflex` implementations to handle new `ReflexResult` and `MessageContext` signatures
- [x] Update `ChatMemoryWriter.java` and `ChatHistoryAssembler.java` to use `AgentSentinels` constants
- [x] Refactor API usage for `ChannelUser`, `ChannelMessage`, and `ChannelSender` across codebase
- [x] Verify all core and app integration tests pass with 0 regressions

---

## Phase 63-C — EncryptedSecret Version Split & Concurrency Fixes ✅
- [x] Separate `version` field in `EncryptedSecret.java` into `ormVersion` (managed by Hibernate) and `encryptionKeyVersion` (application logic)
- [x] Update `SecretVaultImpl.java` to explicitly manage `encryptionKeyVersion` without conflicting with Hibernate optimistic locking
- [x] Update `EncryptedSecretVersionTest` to point to `getOrmVersion()`
- [x] Write `testConcurrentStore_NoConflictWithHibernateVersion` in `SecretVaultImplTest` using `CountDownLatch` and Virtual Threads
- [x] Remove `data-dir` configuration from `application.yaml` and update dev `sandbox.strategy` to `NONE`
- [x] Verify all core and app tests pass with 0 regressions

---

## Phase 63-D — PodmanMcpTransport: @PreDestroy Process Cleanup ✅
- [x] Add `@PreDestroy void cleanup()` to `PodmanMcpTransport`
- [x] Ensure cleanup handles process termination gracefully with `destroy()` and `destroyForcibly()`
- [x] Create test `testPreDestroyCleanup` in `PodmanMcpTransportTest` validating cleanup behavior

---

## Phase 63-E — Config Ordinal & System.nanoTime SLA Measurement ✅
- [x] Update `DotenvConfigSourceProvider` to ordinal `296` ensuring it beats SmallRye's default
- [x] Replace `System.currentTimeMillis()` with `System.nanoTime()` in `IntentClassifierService` for SLA accuracy
- [x] Add verification test `testOrdinalIsExplicitly296` to `DotenvConfigSourceProviderTest`

---

## Phase 63-F — ToolObservationSanitizer: Injection & PII Sanitization ✅
- [x] Add pure `redactPii(String)` method to `PiiScanReflex` matching all PII rules independent of `piiMode` config
- [x] Create `ToolObservationSanitizer` defining hard 16KiB truncation, prompt injection sanitization, and PII redaction
- [x] Inject `ToolObservationSanitizer` into `ToolInvoker` tracking and sanitizing raw `ToolResponse.output()`
- [x] Test rigorous failure structures with mocked sanitization via `ToolInvokerTest` and `ToolObservationSanitizerTest`

---

## Phase 63-G — Pepper as char[]: Zero-on-Use Secret Handling ✅
- [x] Build `@Priority(200)` `CharArrayConverter` supporting `@ConfigProperty char[] pepperChars` conversion
- [x] Register `CharArrayConverter` as a MicroProfile SPI Service within `META-INF/services/org.eclipse.microprofile.config.spi.Converter`
- [x] Update `Argon2idKeyDerivation` to unpack config injected `pepperChars` array securely inside `deriveSessionKey()`
- [x] Ensure both local byte/char arrays and component scoped property `pepperChars` are securely wiped using `Arrays.fill(..., 0)` inside a strong `finally` block
- [x] Configure GraalVM Native Image to support runtime reflection on `CharArrayConverter.class`
- [x] Adjust unit tests mapping Java reflection validations parsing `char[]` buffer memory state

---

## Phase 63-H — Security & Audit Hardening ✅
- [x] Implement deterministic HMAC key retrieval in `AuditHashUtil` from `SecretVault`. <!-- id: 512 -->
- [x] Bypass `VaultKeyCache` for "SYSTEM" session in `SecretVaultImpl` to support boot-time access. <!-- id: 513 -->
- [x] Seed `arcclaw-audit-hmac-key` in `DevSecretSeeder` for `dev` and `test` profiles. <!-- id: 514 -->
- [x] Validate `arcclaw-audit-hmac-key` presence in `StartupSecurityValidator` for non-dev profiles. <!-- id: 515 -->
- [x] Update `StartupSecurityValidator*Test` suites to use `@InjectMock SecretVault`. <!-- id: 516 -->
- [x] Refactor `AuditHashUtil` to use `Instance<SecretVault>` for lazy initialization, breaking circular dependency with `SecretVault`.
- [x] Suppress excessive 500-line stacktraces in logs by moving full traces to DEBUG level in `AuditHashUtil`, `MemorySearchTool`, `MessagePipeline`, `ReflexRegistry`, and `ToolInvoker`.
- [x] Fix data integrity bug in `Argon2idKeyDerivation` by switching from `.array()` to explicit byte extraction.
- [x] Add `@Priority(100)` to `DevSecretSeeder` to ensure early seeding of security keys during startup.
- [x] Verify: All related hardening tests pass with clean logs and 0 regressions. <!-- id: 517 -->

---

## Phase 63-H Extended — Log Noise Reduction & Startup Resilience ✅
- [x] Refactor Error Logging: Move full stack traces from ERROR to DEBUG level for `AgentStateMachineRunner`, `AsyncPersistenceEngine`, `ToolRegistry`, `StatefulAgentStoreImpl`, `WorkspaceManager`, and `SessionReaper`. <!-- id: 518 -->
- [x] Implement Eager Initialization in `AuditHashUtil`: Use `@StartupEvent` to prime HMAC keys during boot, preventing expensive Argon2id derivation from blocking the first transactional audit record. <!-- id: 519 -->
- [x] Implement SYSTEM Master Key Caching in `SecretVaultImpl`: Use `ReentrantLock` and volatile caching to avoid redundant, high-CPU key derivations during parallel execution. <!-- id: 520 -->
- [x] Harden Shutdown Logic: Add guards in `AsyncPersistenceEngine` and `AuditHashUtil` to detect `Arc.container() == null` shutdown races and suppress related `NullPointerException` logs. <!-- id: 521 -->
- [x] Refactor `IntentClassifierService`: Move LLM authentication/timeout stack traces to DEBUG while keeping WARN logs concise. <!-- id: 522 -->

## Phase 63-H Extended — Split-Package Cleanup (Quarkus Optimization) ✅
- [x] Resolve Split-Package Warnings: Relocate all `arcclaw-app` test classes from `com.arcclaw.core.*` namespace to `com.arcclaw.app.*`. <!-- id: 523 -->
- [x] Fixed Packages: `com.arcclaw.core.api`, `com.arcclaw.core.audit`, `com.arcclaw.core.health`, `com.arcclaw.core.scope`, `com.arcclaw.core.security`, `com.arcclaw.core.structured`. <!-- id: 524 -->
- [x] Verify: `mvn clean test` succeeds with 28+ app-layer tests passed and zero `SplitPackageProcessor` warnings. <!-- id: 525 -->
- [x] Result: Faster Quarkus augmentation and unambiguous classpath resolution for all test contexts. <!-- id: 526 -->
---

## Phase 63-I — TmpfsSecretTransport: @PostConstruct Stale File Sweep ✅
- [x] Implement `@PostConstruct void sweepStaleTmpFiles()` in `TmpfsSecretTransport.java` <!-- id: 527 -->
- [x] Ensure sweep is Linux-only by checking for `/dev/shm` presence <!-- id: 528 -->
- [x] Filter sweep to only remove files with `arcclaw-secrets-` prefix <!-- id: 529 -->
- [x] Handle non-Linux and non-existent directories gracefully without failing startup <!-- id: 530 -->
- [x] Add Linux-specific unit tests in `TmpfsSecretTransportTest.java` (using `@EnabledOnOs(OS.LINUX)`) <!-- id: 531 -->
- [x] Verify: All tests pass, including directory non-existence and cleanup logic <!-- id: 532 -->

---

## Phase 63 — Refactoring and Hardening (Prompts J-T) ✅
- [x] Prompt 63-J — `AsyncPersistenceEngine`: Two Bounded Queues with Synchronous Critical Fallback <!-- id: 533 -->
- [x] Prompt 63-K — `AgentCronScheduler`: Lookback Window to Prevent Restart Burst <!-- id: 534 -->
- [x] Prompt 63-L — `McpClientService` HTTP Pool Bound + `CompactionModelProducer` Dedicated Config <!-- id: 535 -->
- [x] Prompt 63-M — `SkillRegistry` + `AgentStateMachineRunner`: Per-Iteration Skill Snapshot <!-- id: 536 -->
- [x] Prompt 63-N — `CompactionHandler`: Fire Compaction Asynchronously via CDI Event <!-- id: 537 -->
- [x] Prompt 63-O — `PermissiveJsonExtractor`: Pre-Parse Size Cap + Duplicate Key Detection <!-- id: 538 -->
- [x] Prompt 63-P — `AgentStateMachineRunner`: Interface Injection + `isSqliteBusy` Deduplication <!-- id: 539 -->
- [x] Prompt 63-Q — `TwoTierMemoryService`: Fast Path When Vector Search Disabled <!-- id: 540 -->
- [x] Prompt 63-R — `NdjsonWriter`: Enforce MCP Newline Framing at the Type Level <!-- id: 541 -->
- [x] Prompt 63-S — Build-Time `@RunOnVirtualThread` Enforcement via `@BuildStep` <!-- id: 542 -->
- [x] Prompt 63-T — `AgentOtelContext`: Instrument Iteration and Tool Spans <!-- id: 543 -->

## Phase 63-U — Post-Implementation Verification Fixes ✅
- [x] Implement immediate `pepper` zeroing in `Argon2idKeyDerivation` (finally block). <!-- id: 544 -->
- [x] Replace `"error-missing-hmac-key"` returning with `IllegalStateException` throw in `AuditHashUtil`. <!-- id: 545 -->
- [x] Implement 3-retry loop for HMAC boot race in `AsyncPersistenceEngine.persistSynchronously()`. <!-- id: 546 -->
- [x] Add catch for `ComplianceViolationException` in `drainCritical()` to increment failure counter. <!-- id: 547 -->
- [x] Centralize magic literals into `VaultConstants.java`. <!-- id: 548 -->
- [x] Update `StartupSecurityValidator` to use `VaultConstants`. <!-- id: 549 -->
- [x] Fix visibility of `ChatMemoryWriter.SKIP_GOALS` for pinning tests. <!-- id: 550 -->
- [x] Resolve `AsyncPersistenceEngineTest` race condition using localized polling. <!-- id: 551 -->
- [x] Verify: All component tests (Argon2, Audit, Persistence, Vault) pass with zero raw literals. <!-- id: 552 -->
