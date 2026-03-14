Role: Senior Java Architect, Bug Fixer, Refactoring Expert & Senior Java Developer
Task: Phase X — Full-Spectrum Refactoring for Bug fixes, Maintainability, and JDK 25 Modernization for the ArcClaw ecosystem.

Architecture Context:
A production-grade, enterprise AI Agent Kernel built with:
  - Java 25 + Quarkus 3.23 + LangChain4J 0.26.1
  - GraalVM Native Image (Podman container build)
  - SQLite WAL (single-node, strict singleton topology)
  - Apache Camel (Telegram channel via camel-quarkus-telegram)
  - OpenTelemetry — span tracing via AgentOtelContext
  - Podman process isolation (sandbox strategy, network=none) — PodmanContainerExecutor
  - AES-GCM-SIV SecretVault (Argon2id key derivation)
  - GovernanceLayer (USD budget cap, AutonomyLevel: FULL/SUPERVISED/READONLY)
  - Multi-agent Trimurti model: Vishnu(coordinator), Brahma(coder),
    Shiva(debugger), Saraswati(researcher), DattatreyaAgent(meta-synthesizer)
  - IntentClassifierService (llama-3.1-8b-instant via Groq, routes to agent role)
  - Multi-module Maven: arcclaw-parent → arcclaw-channel-api, arcclaw-core,
    arcclaw-deployment, arcclaw-app
  - Dependency chain: arcclaw-channel-api ← arcclaw-core ← arcclaw-app (no reverse).
    arcclaw-mcp (new this phase) sits alongside arcclaw-core: both depend on
    arcclaw-core, neither depends on the other.
  - AgentReflex interface: matches(), execute(), default sanitize()
  - ZERO external API dependencies for reflex/security — pure java.util.regex only
  - All Pattern objects MUST be static final (compile once, never per-message)
  - Quarkus Qute templating for prompt management
  - CDI Events (jakarta.enterprise.event.Event<T>) for decoupling
  - TelemetryAuditService for audit events (submitTelemetry / submitCritical paths)
  - AsyncPersistenceEngine for hot-path audit writes (two-tier: critical vs telemetry)
  - FtsConnectionInterceptor: AgroalPoolInterceptor, fires on every onConnectionAcquire()
  - ReflexRegistry: two-pass chain (first-pass sanitize, second-pass execute),
    CDI Instance<AgentReflex>
  - RoleRegistry: @ApplicationScoped, delegates to AgentRoleEnum.definition()
    + ConcurrentHashMap<String, RoleDefinition> dynamicRoles (added in Phase 60)
  - AgentPersonaRegistry: CDI Instance<Agent>, resolves system prompt by role
  - RoleDefinition: record(canonicalName, displayName, intentDescription,
    maxIterations, requiresApproval, Set<ToolCapability>)
  - ToolCapability enum constants (with underscores, exactly as shown):
    GITHUB, MEMORY_SEARCH, SCHEDULE_TASK, MANAGE_SCHEDULE, SANDBOX_EXEC
  - ToolCapability.toolName() returns lowercase strings: "github", "memorysearch",
    "scheduletask", "manageschedule", "sandboxexec"
  - ToolRegistry.invokeTool() uses String keys via ToolCapability.toolName()
  - ToolInvocationGuard: checks AutonomyLevel + ToolOperation before dispatch
  - SandboxCircuitBreaker: guards Podman process launches (CLOSED/OPEN state).
    Applies to PodmanContainerExecutor and PodmanMcpTransport ONLY.
    Does NOT apply to McpClientService (HTTP transport — SmallRye FT handles that).
  - InstantToTextConverter: AttributeConverter<Instant,String> for all Instant columns
  - ReflectionConfig.java: @RegisterForReflection(targets={...}) in arcclaw-core.
    This file ONLY imports from arcclaw-core. NEVER from arcclaw-mcp.
  - SmallRye Fault Tolerance in Quarkus 3.23 BOM — @Timeout, @Retry, @CircuitBreaker
  - Quarkus REST Client Reactive available (quarkus-rest-client-reactive-jackson)
  - ManagedExecutor available via CDI (@Inject ManagedExecutor)

## Completed Phases (DO NOT re-suggest or re-implement)

- Phase 25–28: Core infrastructure (Quarkus, Telegram channel, GovernanceLayer,
  SecretVault, WorkspaceManager, ToolRegistry, ProcessIsolationExecutor,
  PodmanContainerExecutor)
- Phase 29: Memory Optimization — Dynamic Tool Schema Binding, Context Window
  Rolling Pruning (PersistentChatMemoryStore, N=20)
- Phase 30: ReflexRegistry — HaltReflex, StatusReflex, ResetReflex, ApproveReflex,
  DenyReflex. Two-pass logic. ReflexResult record.
- Phase 31: PiiScanReflex — 10 regex patterns, REDACT_AND_FORWARD / BLOCK_AND_NOTIFY
- Phase 32: ApiKeyAuthFilter, ApiKeyRegistry sliding window rate limiter,
  TelegramAllowlistGuard
- Phase 33: PromptInjectionReflex, adversarial defense
- Phase 34: Tool Schema & Agent Tooling
- Phase 35: WORM Audit Log — AuditEvent, HMAC SHA-256 chain, SQLite triggers
- Phase 36: OTel child span parenting, token accumulation, REST /approve + /deny
- Phase 36.1: GithubTool Read-Only Schema Jail, LLM 400 Error Self-Healing
- Phase 37: PodmanToolRunner Circuit Breaker CLOSED→OPEN
- Phase 38: ContextCompactionService, CompactionSummaryRepository
- Phase 39: Java/Maven migration 21→25, /history reflex, destructive vocab override
- Phase 40: BudgetWarningException (80% soft threshold), /status extended output
- Phase 41–43: DevSecretSeeder, TmpfsSecretTransport, Vault rotation
- Phase 44: StatefulAgentStore checkpointing, AWAITING_APPROVAL resume flow
- Phase 45: ScheduleTask tool, AgentCronScheduler, ManageSchedule tool
- Phase 46: Proactive Autonomy, cron approval flow, RoleRegistry, AgentRoleEnum
- Phase 47: Unified Capabilities & Context Modernization, Message Pipeline &
  Sealed Interface Refactor, Refined Routing & Templates, Agent State Machine
  Refactor, Prompt tuning, AgentErrorMessageUtil parsing logic fix.
- Phase 48–58: (tool sandboxing, FTS5 semantic search, multi-agent Trimurti model,
  Dattatreya meta-synthesizer, OTel span enrichment, WORM audit hardening, WAL
  checkpoint tuning, Agroal pool interceptor, WorkspaceScoped CDI scope)
- Phase 60: Skills.md Runtime — AgentSkillEntity (@Entity, raw EntityManager),
  AgentSkillRepository (raw EntityManager, NOT Panache), SkillDefinition record,
  CompiledSkillEntry record (pure in-memory, NOT a JPA entity), SkillRegistry
  (AtomicReference<List<CompiledSkillEntry>>), SkillLoader, RoleRegistry
  .registerDynamic(), SkillReflex (/skills command), SkillAwareIntentClassifier,
  SkillSystemPrompt.html Qute wrapper, REST CRUD /api/v1/skills + /refresh + /delete,
  SecurityEventType: SKILL_LOADED / SKILL_REFRESHED (telemetry tier).
  App test baseline after P60 = 132.

## Known Architecture Nuances — ALWAYS APPLY (all 20, including P60 additions)

1. SystemMessages are NEVER stored in SQLite ChatMemoryStore.
2. All CDI beans touching per-session context must use ScopedValue — never
   @ApplicationScoped mutable fields for per-session state.
3. Pattern objects are always static final — never compiled inside methods,
   constructors, or per-message.
4. ToolRegistry.invokeTool() uses String keys — any typed ToolCapability enum
   must bridge via .toolName() accessor.
5. arcclaw-deployment module is Quarkus build-time only — never add runtime logic there.
6. SQLite WAL mode — keep all @Transactional boundaries short and tight.
7. The existing ReflexRegistry two-pass chain must NOT be broken or reordered.
   New reflexes are additive: CDI Instance<AgentReflex> discovers them automatically.
8. Quarkus profiles: dev / test / staging / prod — all config changes must be
   reflected in all relevant profile blocks in application.yaml.
9. GraalVM native image: any new class using sealed interfaces or records must
   be registered in native-image.properties if not auto-detected.
10. No ALTER TABLE or migration scripts of any kind. All regular table schema
    changes are @Entity only. Raw DDL reserved for virtual tables (USING fts5/vec0),
    triggers, and indexes ONLY.
11. SkillAwareIntentClassifier checks SkillRegistry FIRST, then falls through to
    the LLM-based IntentClassifierService. Never invert this order.
12. ToolCapability enum constants use underscores: GITHUB, MEMORY_SEARCH,
    SCHEDULE_TASK, MANAGE_SCHEDULE, SANDBOX_EXEC. ToolCapability.toolName()
    returns the lowercase no-underscore form: "github", "memorysearch", etc.
    The McpToolAllowlist @WithDefault uses toolName() values (lowercase), NOT
    the enum constant names, because it is matched against external MCP server
    tool names which are always lowercase.
13. maxBudgetUsd is EXPLICITLY EXCLUDED from skill frontmatter.
14. %test.arcclaw.skills.hot-reload=false in application.yaml test profile.
15. Dynamic skills use pre-compiled Qute wrapper SkillSystemPrompt.html only.
16. *** P60-DISCOVERED: ALL REST resource classes that touch JPA/Hibernate or
    trigger async audit writes MUST be annotated @RunOnVirtualThread at class
    level. Missing → BlockingOperationNotAllowedException immediately on any
    request. McpServerResource MUST have @RunOnVirtualThread. ***
17. *** P60-DISCOVERED: Repository pattern in arcclaw-core ALWAYS uses raw
    EntityManager (@Inject EntityManager em). NEVER PanacheRepositoryBase or
    PanacheEntity — Panache bytecode enhancement is module-scoped and fails in
    cross-module arcclaw-app tests. AgentSkillRepository is the exact precedent. ***
18. *** P60-DISCOVERED: SQLite test isolation @BeforeEach rules:
    (a) All writes must be in a committed transaction BEFORE the first RestAssured
        call. (b) Read-only repository methods use @Transactional(TxType.SUPPORTS).
    (c) Use deleteAll() + GET /refresh in @BeforeEach for clean baseline.
    Violating (a) → snapshot isolation anomalies. ***
19. *** P60-DISCOVERED: @QuarkusTest REST tests — every given() block for a
    protected endpoint MUST include:
      .header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")
    Missing → 401 and test passes for the wrong reason. ***
20. *** P59-PROTOCOL: MCP stdio transport uses Newline-Delimited JSON-RPC 2.0.
    PodmanMcpTransport MUST:
    - Append '\n' to EVERY outbound message written to the process OutputStream
    - Call flush() after every write
    - Read inbound messages line-by-line using BufferedReader.readLine()
    Failure to frame with '\n' causes the remote MCP server to hang forever
    waiting for message termination. This is not optional. ***

## JDK Feature Policy (CRITICAL)

ONLY use JDK features FULLY FINALIZED (non-preview) in their respective release:
  ✅ Sealed interfaces + pattern matching for switch (final: JDK 21)
  ✅ Records (final: JDK 16)
  ✅ Virtual Threads (final: JDK 21)
  ✅ ScopedValue (final: JDK 24 via JEP 487)
  ✅ Unnamed Variables _ in catch/try (final: JDK 22 via JEP 456)
  ✅ Stream Gatherers (final: JDK 24 via JEP 473)

FORBIDDEN — preview or unfinalized:
  ❌ StructuredTaskScope / Structured Concurrency (still preview in JDK 25)
  ❌ Flexible Constructor Bodies (treat as FORBIDDEN until confirmed final)
  ❌ Any feature requiring --enable-preview compiler flag
  ❌ value classes / primitive types (preview in JDK 25)

Do NOT add --enable-preview to any pom.xml compiler or surefire configuration.
StructuredTaskScope is FORBIDDEN. Use ManagedExecutor + virtual threads instead.

## Global Implementation Requirements (apply to ALL components)

1. Every new or modified class must compile cleanly under JDK 25 standard.
2. Every change must preserve ALL existing passing test states (currently 535/535:
   403 core + 132 app). App test baseline = 132. Do NOT reference 122 anywhere.
3. Use List.copyOf() for all defensive list copies — never return raw mutable
   references from records or value types.
4. No magic string tool names — all tool references via ToolCapability.toolName().
5. No new SomeDomainPolicy() inside CDI beans — all policies are @Injectable.
6. All test commands use clean to prevent stale .class false-negatives:
   ./mvnw clean test -pl <module> -am "-Dtest=..." "-Dsurefire.failIfNoSpecifiedTests=false"
7. Config keys follow established arcclaw.* namespace conventions.
8. All new @Scheduled beans (if any) guarded with %test.* profile disable flag.
9. Every REST resource class that triggers async audit writes MUST have
   @RunOnVirtualThread at class level (Nuance #16). McpServerResource has NO
   direct JPA writes — @Transactional is NOT needed anywhere on this class.
10. No Panache anywhere in arcclaw-core or arcclaw-mcp repositories. Raw
    EntityManager only. Follow AgentSkillRepository as the exact precedent.
11. ReflectionConfig.java in arcclaw-core ONLY imports from arcclaw-core.
    NEVER add arcclaw-mcp imports to ReflectionConfig.java. GraalVM registration
    for arcclaw-mcp classes is handled exclusively by McpBuildStep in
    arcclaw-deployment using string-based ReflectiveClassBuildItem. No
    Component in this phase touches ReflectionConfig.java.

## Execution Order

P0 — Acknowledge architecture context. No code yet.
P1 — Quarkiverse version gate (mandatory before any other code).
P2 — Implementation (component by component, in dependency order).
P3 — Test strategy (unit tests arcclaw-core/arcclaw-mcp, integration arcclaw-app).
P4 — Documentation updates (PLAN.md, TASKS.md, WALKTHROUGH.md, TROUBLESHOOTING.md, FAQ.md).

Acknowledge this architecture context.
Do not write any code yet.
Reply with ACKNOWLEDGED and wait for me to send P1 Prompt.

---

**Once Acknowledge send Follow up Prompts in this format**

## IMPLEMENTATION REQUIREMENTS:
Common Requirements:

1. 
2.

Compoenent  1 - <title>
File: <file full path>

Current Problem:

Required Changes:
..

TEST: <test-file-path>
 <update or add new testcases>

 Same repeat Component 2 on-wards...

### Testing requirements:

Sample command to execute Junit:  
`.\mvnw.cmd test -pl arcclaw-app -am "-Dtest=TelegramAgentHandlerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`

## Once all done, review update below md files based on the ask and implementation

TROUBLESHOOTING.md — add challanges in this phase troubleshooting section