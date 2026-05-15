 ArcClaw -- Complete Codebase Understanding

  What It Is

  ArcClaw is a production-grade multi-agent AI platform built on Java 25, Quarkus 3.27.2, and LangChain4j 1.x. It implements a ReAct
  (Reason-Act-Observe) agent loop with Human-in-the-Loop (HITL) governance, sandboxed tool execution (Podman), encrypted secret
  management (AES-256-GCM-SIV), tamper-evident audit logging, and multi-channel communication (CLI, Telegram, WebSocket).

  ---
  Module Architecture (14 modules)

  arcclaw-persistence-api   arcclaw-channel-api     (pure SPIs, zero impl)
           |                        |
           +--------+---------------+
                    |
               arcclaw-core          (~370 files, the kernel)
                    |
      +------+------+------+------+------+
      |      |      |      |      |      |
    sqlite  postgres  fs    rag    mcp  onboarding
      |      |      |      |      |      |
      +------+------+------+------+------+
                    |
    channel-telegram  channel-web  tool-playwright
                    |
               arcclaw-app          (CLI, REST, wiring)
                    |
          arcclaw-deployment        (build-time only)
          arcclaw-ui                (frontend)

  Hexagonal architecture enforced by ArchUnit tests -- core never imports JPA, channels, or adapters. All cross-boundary
  communication goes through port interfaces.

  ---
  Core Domain Model

  6 Agent Personas (Hindu deity-named)

  ┌────────────┬─────────────┬───────────────────────────────────────────┐
  │  Persona   │   Role ID   │                  Purpose                  │
  ├────────────┼─────────────┼───────────────────────────────────────────┤
  │ VISHNU     │ coordinator │ Orchestrates sub-agents, manages sessions │
  ├────────────┼─────────────┼───────────────────────────────────────────┤
  │ BRIHASPATI │ architect   │ System architecture, trade-offs, ADRs     │
  ├────────────┼─────────────┼───────────────────────────────────────────┤
  │ SARASWATI  │ researcher  │ Information retrieval, web searches       │
  ├────────────┼─────────────┼───────────────────────────────────────────┤
  │ BRAHMA     │ coder       │ New code, feature implementations         │
  ├────────────┼─────────────┼───────────────────────────────────────────┤
  │ SHIVA      │ debugger    │ Stack trace analysis, bug fixing          │
  ├────────────┼─────────────┼───────────────────────────────────────────┤
  │ DATTATREYA │ synthesizer │ Cross-domain synthesis                    │
  └────────────┴─────────────┴───────────────────────────────────────────┘

  All personas share a single DefaultAgent implementation -- system prompts loaded from Qute templates. Class-per-persona is
  forbidden.

  Key Sealed Types

  - AgentResult: Finished | StructuredFinished | AwaitingApproval | AwaitingRetry | LoopExceeded | Failed
  - ReasoningStep: ToolCallStep | ObservationStep | FinishStep | ParallelStep
  - CheckpointStatus: Active states (NOT_STARTED, IN_PROGRESS, AWAITING_APPROVAL, RESUMING, APPROVED) + terminal states (COMPLETED,
  FAILED, LOOP_EXCEEDED, DENIED, COMPROMISED, ORPHANED)

  ---
  Execution Flow

  1. Message Pipeline (priority-ordered handlers)

  ┌──────────┬─────────────────────────────┬────────────────────────────────────────────────┐
  │ Priority │           Handler           │                    Purpose                     │
  ├──────────┼─────────────────────────────┼────────────────────────────────────────────────┤
  │ 10       │ AllowlistHandler            │ User authorization                             │
  ├──────────┼─────────────────────────────┼────────────────────────────────────────────────┤
  │ 15       │ SessionResolutionHandler    │ Session binding, autonomy loading              │
  ├──────────┼─────────────────────────────┼────────────────────────────────────────────────┤
  │ 30       │ CommandDispatchHandler      │ Reflex fast-path (zero LLM tokens)             │
  ├──────────┼─────────────────────────────┼────────────────────────────────────────────────┤
  │ 45       │ SessionResolutionHandler    │ Session ID resolution                          │
  ├──────────┼─────────────────────────────┼────────────────────────────────────────────────┤
  │ 50       │ IntentClassificationHandler │ Skill routing, then LLM classification         │
  ├──────────┼─────────────────────────────┼────────────────────────────────────────────────┤
  │ 60       │ CompactionHandler           │ Async context compaction if threshold exceeded │
  ├──────────┼─────────────────────────────┼────────────────────────────────────────────────┤
  │ 70       │ AgentDispatchHandler        │ Delegates to AgentLoop                         │
  └──────────┴─────────────────────────────┴────────────────────────────────────────────────┘

  2. Reflex System (Pre-LLM, Deterministic)

  Two-pass architecture in ReflexRegistry:
  - Pass 1 -- Matching: First reflex whose matches() returns true gets executed. If it returns a commandResponse, the LLM is never
  called.
  - Pass 2 -- Sanitization: All reflexes' sanitize() methods run sequentially (PII redaction, injection stripping).

  Key reflexes by priority: PromptInjectionReflex (10) > PiiScanReflex (20) > ApproveReflex (100) > DenyReflex (200) >
  IdentityExtractionReflex (250) > StatusReflex/HaltReflex/ResetReflex/StoreReflex (500)

  3. Agent Loop (Thought-Plan-Act-Observe)

  AgentLoop -> AgentStateMachineRunner iterates up to maxIterations (default 5):

  1. EngineStepHandler: Calls LLM via ReasoningEngine.nextStep(), classifies exceptions (rate limit, auth, content filter, malformed
  response)
  2. StepDecisionRouter: Routes the step -- FinishStep goes to quality guards, tool calls go to validation/invocation
  3. ToolStepHandler (4-phase):
    - Guard: ToolInvocationGuard checks dedup, role restrictions, autonomy, HITL approval
    - Invoke: ToolRegistry.invokeTool() with governance and sandbox routing
    - Observe: Build observation, append to history
    - Resolve: Final decision routing

  4. HITL Approval Flow

  When a tool requires approval:
  1. ApprovalCheckpointManager persists an AWAITING_APPROVAL checkpoint with tool metadata
  2. SSE HitlEvent published for UI tracking
  3. AgentResult.awaitingApproval() returned to pause the loop
  4. User sends /approve or /deny -> ApproveReflex/DenyReflex processes it
  5. ApprovalStateMachine.decide() records per-tool decisions
  6. CheckpointApprovalService.triggerResume() resumes the agent loop

  ---
  Security Model

  Autonomy Levels

  READONLY -> BACKGROUND (cron) -> SUPERVISED (HITL, default) -> FULL (sandboxed, budget-limited)

  Security Layers

  - API Key Auth: HMAC-based constant-time comparison, per-key rate limiting, session ownership isolation
  - Prompt Injection Detection: 20+ regex patterns across 5 categories (governance bypass, exfiltration, identity hijacking, direct
  override, context wipe)
  - PII Redaction: Emails, phones, SSN, Aadhaar, PAN, GSTIN, credit cards, IPs, JWTs, DOB, passport, UPI
  - SecretVault: AES-256-GCM-SIV with Argon2id key derivation, AutoCloseable key wrappers that zero memory
  - Startup Security Validator: Rejects dev-default peppers and missing HMAC keys in prod/staging

  Process Isolation (3-tier)

  1. Podman rootless containers (production) -- memory/CPU/pids limits, tmpfs secret transport
  2. Linux namespace fallback (unshare) -- after 3 Podman circuit breaker failures
  3. Direct execution (test/Windows) -- no isolation

  ---
  Persistence

  3 Backends

  - SQLite (primary): WAL mode, FTS5 full-text search, sqlite-vec vector search, advisory locks, SqliteRetryTemplate (5 retries, 50ms
   base)
  - PostgreSQL: Production-grade, pg_try_advisory_lock, ZombieRecoveryService
  - Filesystem: JSON files on disk, lightweight alternative

  13 Port Interfaces

  CheckpointStorePort (composed of 4 sub-ports via Interface Segregation), ChatMemoryStorePort, CompactionStorePort,
  MemoryRetrievalPort, SecretStorePort, AdvisoryLockPort, AuditEventStorePort, CronStorePort, SkillStorePort, AuditSchemaPort,
  PersistenceAdapterPort, BackendCapabilities

  Compaction

  Triggered when message count > 10, chars > 96K, or tokens > 32K. Splits history into "compact" and "keep" (last 5), summarizes via
  LLM, persists via FTS5, truncates to 1985 codepoints max. Concurrency controlled by CompactionInFlightFlag (per-session
  AtomicBoolean) and CompactionEventBridge (CountDownLatch with self-deadlock prevention).

  ---
  LLM Routing

  Provider SPIs

  - RoutingChatModelProvider (internal routing): OpenAI, Groq, OpenRouter, Ollama, NVIDIA, Cerebras, Gemini, Claude Code
  - AgentChatModelProvider (agent reasoning): Same providers, different interface

  LlmModelFactory

  Central CDI-based factory that discovers providers by providerId. Can infer provider from URL patterns.

  FallbackChatModel

  Tiered routing: Write-intent (primary first) -> Compact/chitchat (fallback first) -> Large history (preemptive fallback) -> Normal
  (primary with exception-based fallback). Catches 429, safety blocks, 5xx, content filters.

  ---
  Build & Deploy

  - Java 25, Maven multi-module, Quarkus 3.27.2 BOM
  - Checkstyle: max 500 lines/file, max 80 lines/method
  - 535+ tests: Unit (Mockito), @QuarkusTest, @QuarkusComponentTest, REST Assured integration, ArchUnit boundary enforcement
  - 5 Quarkus profiles: default, %cli, %dev, %test, %staging, %prod
  - Native image: GraalVM via Mandrel JDK-25 builder
  - Docker Compose: arcclaw + ollama + otel-collector + jaeger + prometheus


---

**P0 — Critical:**
- 4 JAX-RS resource classes live inside `arcclaw-core` (`AgentResource`, `AuditResource`, `SystemInfoResource`, `ToolRegistryResource`) — violates hexagonal architecture; should move to `arcclaw-app`
- No Flyway/Liquibase — schema managed by Hibernate `strategy: update` with `halt-on-error: false`, no rollback capability

**P1 — Important:**
- 4 entity classes duplicated between `arcclaw-persistence-sqlite` and `arcclaw-persistence-postgres` (`AgentCheckpointEntity` 398 lines each, `AgentCronEntity`, `AuditEventEntity`, `ReasoningStepEntity`)
- Key untested core classes: `ApprovalCheckpointManager`, `CheckpointResumeCoordinator`, `ParallelToolStepExecutor`, `ToolStepHandler`, `ToolResultProcessor`
- `arcclaw-persistence-postgres` has only 2 tests, `arcclaw-persistence-fs` has 1
- 9 empty/swallowed catch blocks (e.g., `BootstrapOrchestrator`, `ChatHistoryAssembler`)

**P2 — Moderate:**
- God classes: `AgentStateMachineRunner` (15 injects), `AgentSessionService` (13), `ParallelToolStepExecutor` (13)
- 5 files above 400 lines (limit 500): `CliUtils` 468, `McpClientService` 464, `CheckpointResumeHandler` 420
- Duplicated `REST_USER_ID`/`REST_USER_NAME` constants in `AgentSessionService` and `SessionCleanupService`

**P3 — Low:**
- Flat exception hierarchy — 23 custom exceptions, most extend `RuntimeException` directly, no common base
- No formal domain event catalog (CDI events used but scattered)

---

 Architecture Review -- Findings & Improvement Opportunities

  1. Hexagonal Architecture Violation: REST Resources in Core

  Severity: High

  4 JAX-RS resource classes live inside arcclaw-core:
  - arcclaw-core/.../api/AgentResource.java (@Path("/sessions"))
  - arcclaw-core/.../api/AuditResource.java (@Path("/audit"))
  - arcclaw-core/.../api/SystemInfoResource.java (@Path("/info"))
  - arcclaw-core/.../api/ToolRegistryResource.java (@Path("/tools"))

  Core is supposed to be delivery-mechanism agnostic. These should move to arcclaw-app, with core providing only service interfaces.

  2. No Database Migration Strategy

  Severity: High

  Schema is managed by Hibernate strategy: update (auto-DDL). There's no Flyway or Liquibase. This is risky for production:
  - No version-controlled schema changes
  - No rollback capability
  - update can silently fail or produce unexpected diffs on complex column changes
  - halt-on-error: false masks schema problems

  3. Duplicated Entity Classes Across Persistence Modules

  Severity: Medium

  4 entity class names are duplicated between arcclaw-persistence-sqlite and arcclaw-persistence-postgres:
  - AgentCheckpointEntity (398 lines in each)
  - AgentCronEntity
  - AuditEventEntity
  - ReasoningStepEntity

  These are near-identical JPA entities with the same fields. Consider extracting a shared arcclaw-persistence-common module with
  base entity classes.

  4. God Classes / High Coupling

  Severity: Medium

  Classes with excessive constructor injection (all @Inject):

  ┌──────────────────────────┬───────────────┐
  │          Class           │ Injected Deps │
  ├──────────────────────────┼───────────────┤
  │ AgentStateMachineRunner  │ 15            │
  ├──────────────────────────┼───────────────┤
  │ AgentSessionService      │ 13            │
  ├──────────────────────────┼───────────────┤
  │ ParallelToolStepExecutor │ 13            │
  ├──────────────────────────┼───────────────┤
  │ LangChainReasoningEngine │ 12            │
  ├──────────────────────────┼───────────────┤
  │ SystemPromptBuilder      │ 11            │
  └──────────────────────────┴───────────────┘

  AgentCheckpointRecord has 92 method-accessible members (wither methods for each field). This is a consequence of the immutable
  record pattern but creates a very wide API surface.

  5. Files Approaching Checkstyle Limit

  Severity: Low-Medium

  5 files are above 400 lines (limit is 500):

  ┌───────────────────────────────┬───────┐
  │             File              │ Lines │
  ├───────────────────────────────┼───────┤
  │ CliUtils.java                 │ 468   │
  ├───────────────────────────────┼───────┤
  │ McpClientService.java         │ 464   │
  ├───────────────────────────────┼───────┤
  │ CheckpointResumeHandler.java  │ 420   │
  ├───────────────────────────────┼───────┤
  │ FsCheckpointStoreAdapter.java │ 411   │
  ├───────────────────────────────┼───────┤
  │ AgentSessionService.java      │ 407   │
  └───────────────────────────────┴───────┘

  6. Swallowed Exceptions

  Severity: Medium

  9 empty or near-empty catch blocks found:

  ┌───────────────────────────────────┬───────────────────────────────────────────────────────────────────────────────────────────┐
  │               File                │                                           Issue                                           │
  ├───────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────┤
  │ BootstrapOrchestrator.java:77     │ catch (Exception _) {} -- completely silent                                               │
  ├───────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────┤
  │ BootstrapOrchestrator.java:138    │ catch (IOException _) {} -- completely silent                                             │
  ├───────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────┤
  │ EnvConfigInitializer.java:71      │ catch (IllegalArgumentException _) {}                                                     │
  ├───────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────┤
  │ EnvConfigInitializer.java:157     │ catch (IOException _) {}                                                                  │
  ├───────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────┤
  │ ChatHistoryAssembler.java:165,211 │ catch (JsonProcessingException e) { argsJson = "{}"; } -- silently replaces with empty    │
  │                                   │ JSON                                                                                      │
  ├───────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────┤
  │ SerperWebSearchTool.java:177      │ catch (NumberFormatException _) {}                                                        │
  └───────────────────────────────────┴───────────────────────────────────────────────────────────────────────────────────────────┘

  At minimum, these should log at DEBUG level.

  7. Duplicated Constants

  Severity: Low

  REST_USER_ID = "rest:api" and REST_USER_NAME = "REST API User" are duplicated in both:
  - AgentSessionService.java:74-75
  - SessionCleanupService.java:41-42

  Should be extracted to a shared constant.

  8. Test Coverage Gaps

  Severity: Medium

  271 total test files, but 254 core classes have no matching test file. Key untested classes include:

  - ApprovalCheckpointManager -- critical HITL flow
  - CheckpointResumeCoordinator -- resume logic
  - ParallelToolStepExecutor -- concurrent tool execution
  - ToolStepHandler -- tool validation/invocation pipeline
  - ToolResultProcessor -- result resolution
  - SequentialToolExecutor -- sequential tool execution
  - AgentProducer -- agent creation
  - CompactionSummaryCache -- compaction caching

  Persistence adapter modules are particularly thin:
  - arcclaw-persistence-postgres: 2 tests
  - arcclaw-persistence-fs: 1 test

  9. Exception Hierarchy Inconsistency

  Severity: Low-Medium

  23 custom exceptions, but the hierarchy is flat:
  - Most extend RuntimeException directly
  - Only the LlmProviderException subtree has proper inheritance (6 subclasses)
  - PodmanUnavailableException extends IOException (inconsistent with others)
  - No common ArcClawException base for framework-level exceptions
  - BudgetExceededException and BudgetWarningException are both unchecked -- the warning one could arguably be checked to force
  callers to handle it

  10. No Shared Domain Event Infrastructure

  Severity: Low

  CDI events are used for cross-cutting notifications (ApprovalDecisionEvent, CompactionRequest, etc.), but there's no formal event
  bus abstraction. Events are scattered as individual CDI observer methods. A lightweight DomainEventBus would make the event
  vocabulary discoverable and enforce consistent handling.

  11. Potential Thread Safety Concerns

  Severity: Low

  AgentStateMachineRunner has 15 injected dependencies and mutable iteration state. The AgentExecutionContext has mutable hot-path
  caches (checkpointStatus, forceFinishRetry, truncationRetryCount) that are accessed across the loop iteration. These are likely
  safe because the state machine runs on a single virtual thread, but the mutable state isn't explicitly confined.

  ---
  Summary of Recommendations (Prioritized)

  ┌──────────┬───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
  │ Priority │                                                  Recommendation                                                   │
  ├──────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ P0       │ Add Flyway/Liquibase for schema migration management                                                              │
  ├──────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ P0       │ Move REST resources from arcclaw-core to arcclaw-app                                                              │
  ├──────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ P1       │ Extract shared base entities to reduce duplication across persistence modules                                     │
  ├──────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ P1       │ Add tests for critical untested classes (ApprovalCheckpointManager, CheckpointResumeCoordinator, ToolStepHandler) │
  ├──────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ P1       │ Replace empty catch blocks with at least DEBUG logging                                                            │
  ├──────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ P2       │ Extract REST_USER_ID/REST_USER_NAME to shared constants                                                           │
  ├──────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ P2       │ Refactor god classes (AgentStateMachineRunner, AgentSessionService) by extracting concerns                        │
  ├──────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ P2       │ Add arcclaw-persistence-fs and arcclaw-persistence-postgres test coverage                                         │
  ├──────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ P3       │ Establish a common exception base class hierarchy                                                                 │
  ├──────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ P3       │ Formalize domain events into a discoverable event catalog                                                         │
  └──────────┴───────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

