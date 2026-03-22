Please go through my entire project end to end as a Senior Java Architect, Security Reviewer, Refactoring Expert & Senior Java Developer. My plan is the share your reviews with Claude/Gemini and its review with you to debate and solve. Make sure to deep dive and not just high level and not just only positive side. Not looking for any Constructive Feedback or Roadmap.

----

Role: Senior Java Architect, Bug Fixer, Refactoring Expert & Senior Java Developer
Task: Phase X - ..... to be filled.

Architecture Context:
A production-grade, enterprise AI Agent Kernel built with:
  - Java 25 + Quarkus 3.27.2 + LangChain4j core 1.5
    (via io.quarkus.platform:quarkus-langchain4j-bom:3.27.2, extension 1.2.0)
  - GraalVM Native Image (Podman container build)
  - SQLite WAL (single-node, strict singleton topology)
  - Apache Camel (Telegram channel via camel-quarkus-telegram)
  - OpenTelemetry — span tracing via AgentOtelContext
  - Podman process isolation (sandbox strategy, network=none) — PodmanContainerExecutor
  - AES-GCM-SIV SecretVault (Argon2id key derivation)
  - GovernanceLayer (USD budget cap, AutonomyLevel: FULL/SUPERVISED/READONLY)
  - Multi-agent Trimurti model:
      Vishnu (coordinator)       → AgentRoleEnum.COORDINATOR
      Brahma (coder)             → AgentRoleEnum.CODER
      Shiva (debugger)           → AgentRoleEnum.DEBUGGER
      Saraswati (researcher)     → AgentRoleEnum.RESEARCHER
      Brihaspati (architect)     → AgentRoleEnum.ARCHITECT  [display: "Brihaspati (System Designer)"]
      Dattatreya (synthesizer)   → AgentRoleEnum.SYNTHESIZER [display: "Dattatreya (Synthesizer)"]
    RULE: Enum CONSTANT = role name. Display name = deity + role in parens.
    RULE: NEVER use DATTATREYA as an enum constant. The constant is SYNTHESIZER.
    RULE: NEVER use BRIHASPATI as an enum constant. The constant is ARCHITECT.
  - IntentClassifierService (llama-3.1-8b-instant via Groq OpenAI-compatible API,
    accessed via quarkus.langchain4j.openai.* namespace — quarkus.langchain4j.groq.*
    namespace was removed during migration. CompactionModelProducer reads
    quarkus.langchain4j.openai.base-url and quarkus.langchain4j.openai.api-key.)
  - Multi-module Maven: arcclaw-parent → arcclaw-channel-api, arcclaw-core,
    arcclaw-rag, arcclaw-deployment, arcclaw-mcp, arcclaw-app
  - Dependency chain:
      arcclaw-channel-api ← arcclaw-core ← arcclaw-rag ← arcclaw-app (no reverse).
      arcclaw-mcp sits alongside arcclaw-core: both depend on arcclaw-core,
      neither depends on the other.
      arcclaw-deployment is Quarkus build-time augmentation ONLY — zero runtime logic.
  - AgentReflex interface: matches(), execute(), default sanitize()
  - ZERO external API dependencies for reflex/security — pure java.util.regex only
  - All Pattern objects MUST be static final (compile once, never per-message)
  - Quarkus Qute templating for prompt management
  - CDI Events (jakarta.enterprise.event.Event<T>) for decoupling
  - TelemetryAuditService for audit events (submitTelemetry / submitCritical paths)
  - AsyncPersistenceEngine for hot-path audit writes (two-tier: critical vs telemetry)
  - FtsConnectionInterceptor: AgroalPoolInterceptor, fires on every onConnectionAcquire()
    Loads sqlite-vec via SQLite load_extension(). Uses OS loader — NOT java.library.path.
    Path resolved via Path.of(vecExtensionPath).toAbsolutePath().normalize().toString()
    BEFORE passing to load_extension(). Relative path in config = %dev: ../lib/vec0
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
  - AppReflectionConfig.java: @RegisterForReflection(targets={...}) in arcclaw-app.
    This file imports from arcclaw-app and arcclaw-rag. NEVER from arcclaw-mcp.
  - SmallRye Fault Tolerance in Quarkus 3.27.2 BOM — @Timeout, @Retry, @CircuitBreaker
  - Quarkus REST Client Reactive available (quarkus-rest-client-reactive-jackson)
  - ManagedExecutor available via CDI (@Inject ManagedExecutor)
  - Hybrid RAG: arcclaw-rag module — TwoTierMemoryService (FTS5 + sqlite-vec),
    RagContextEnricher, GlobalKnowledgeBase, SessionKnowledgeBase (@WorkspaceScoped),
    EmbeddingModelProducer, ScoredChunk record, RRF (k=60) result merging.
    sqlite-vec graceful degradation: AtomicBoolean vectorSearchEnabled guards ALL
    vector operations. If vec0 binary missing → FTS5-only, no crash.
  - StructuredOutputEngine (arcclaw-core): loads schema resources via
    Thread.currentThread().getContextClassLoader().getResourceAsStream() ONLY.
    NEVER getClass().getResourceAsStream() — fails for in-memory generated resources
    in multi-module Quarkus test augmentation.
  - AgentFunctionProcessor (arcclaw-deployment): build-time Jandex processor.
    Generates JSON schemas from @AgentFunction SAM RETURN TYPE via SchemaFactoryWrapper.
    NEVER from ToolSpecifications.toolSpecificationFrom() — that generates INPUT schema.
    Output: GeneratedResourceBuildItem only. NEVER writes to filesystem.
  - PermissiveJsonExtractor: Jackson lenient parser for LLM JSON responses.
    Uses JsonReadFeature.ALLOW_SINGLE_QUOTED_STRINGS (NOT ALLOW_SINGLE_QUOTES — does not exist).
    Uses JsonReadFeature.ALLOW_TRAILING_COMMA at token level — NOT via regex.
    FAIL_ON_MISSING_CREATOR_PROPERTIES intentionally ABSENT — allows Nullable record
    fields to be omitted from JSON without throwing.
  - StructuredRetryLoop: single retry on JsonExtractionException.
    BudgetWarningException is CAUGHT — retry proceeds.
    BudgetExceededException is NOT CAUGHT — propagates to caller.
    STRUCTURED_OUTPUT_RETRY audit written AFTER retry succeeds, never before.
  - ArcClawSQLiteDialect extends SQLiteDialect: overrides getIdentityColumnSupport()
    with hasDataTypeInIdentityColumn() = true. Fixes Hibernate ORM 7 DDL omitting
    type for identity columns on SQLite.
  - Apache Camel TelegramChannelRoute (Quarkus dev mode critical nuance):
    camelContext.disableJMX() is guarded by isNew() in AbstractCamelContext.
    In dev mode, StartupEvent fires AFTER INITIALIZED state — disableJMX() is a
    SILENT NO-OP. Fix: use getLifecycleStrategies().removeIf(s -> s.getClass()
    .getName().contains("JmxManagement")) BEFORE camelContext.start().

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
- Phase 59: MCP Server + Client — arcclaw-mcp module, PodmanMcpTransport (stdio),
  McpClientService (HTTP/SSE), McpAdminResource, DynamicToolCapabilityRegistry,
  dual Accept header compliance, McpToolBridge, ExternalMcpToolAdapter.
- Phase 60: Skills.md Runtime — AgentSkillEntity (@Entity, raw EntityManager),
  AgentSkillRepository (raw EntityManager, NOT Panache), SkillDefinition record,
  CompiledSkillEntry record (pure in-memory, NOT a JPA entity), SkillRegistry
  (AtomicReference<List<CompiledSkillEntry>>), SkillLoader, RoleRegistry
  .registerDynamic(), SkillReflex (/skills command), SkillAwareIntentClassifier,
  SkillSystemPrompt.html Qute wrapper, REST CRUD /api/v1/skills + /refresh + /delete,
  SecurityEventType: SKILL_LOADED / SKILL_REFRESHED (telemetry tier).
- Phase 60.1: Quarkus 3.23.0 → 3.27.2 LTS migration. ArcClawSQLiteDialect
  (hasDataTypeInIdentityColumn=true), LangChain4j 1.x API rename (ChatLanguageModel
  → ChatModel, .from() → static factories), Qute @TemplateData gates for native image,
  ThreadLocalRandom replacing static Random, 535/535 tests green.
- Phase 61: Semantic Vector RAG (Dual-Tier: sqlite-vec + InMemory).
  arcclaw-rag module: TwoTierMemoryService (RRF k=60), FtsConnectionInterceptor
  (vec0 native extension loading, AtomicBoolean graceful degradation),
  RagSchemaInitializer (vec_global virtual table DDL), RagContextEnricher,
  GlobalKnowledgeBase (CompactionSummaryEntity persistence), SessionKnowledgeBase
  (@WorkspaceScoped in-memory), IngestDocumentTool, EmbeddingModelProducer.
  Bug fix: GlobalKnowledgeBase persists raw text via CompactionSummaryEntity.
  Bug fix: TwoTierMemoryService merges non-empty text during RRF.
  Registered in AppReflectionConfig. SQLITE_VEC_PATH in .env + application.yaml.
- Phase 62: Build-Time BAML Schema Generation.
  AgentFunctionProcessor (arcclaw-deployment): Jandex build-step, generates JSON
  schema from @AgentFunction SAM RETURN TYPE via SchemaFactoryWrapper.
  StructuredOutputEngine: Thread Context ClassLoader for resource loading.
  PermissiveJsonExtractor, StructuredRetryLoop, StructuredOutputException (unchecked),
  JsonExtractionException (unchecked), SynthesisFunction, SynthesisResult,
  AgentContribution, ConflictSummary records. DattatreyaAgent wired to
  StructuredOutputEngine. GovernanceLayer.assertWithinBudget() added.
  SessionBudget.warningSent refactored to AtomicBoolean (CAS thread safety).
  SecurityEventType.STRUCTURED_OUTPUT_RETRY added (isMandatory=false).
  AgentStateMachineRunner catches StructuredOutputException → FailureCategory.SCHEMA_ERROR.
  Split-package fix: duplicate WorkspaceScopeTest.java removed from arcclaw-app.

## Known Architecture Nuances — ALWAYS APPLY

## Known Architecture Nuances — ALWAYS APPLY

**Nuance #1** — SystemMessages are NEVER stored in SQLite ChatMemoryStore.

**Nuance #2** — All CDI beans touching per-session context must use ScopedValue — never `@ApplicationScoped` mutable fields for per-session state.

**Nuance #3** — Pattern objects are always `static final` — never compiled inside methods, constructors, or per-message.

**Nuance #4** — `ToolRegistry.invokeTool()` uses String keys — any typed `ToolCapability` enum must bridge via `.toolName()` accessor.

**Nuance #5** — `arcclaw-deployment` module is Quarkus build-time ONLY — never add runtime logic there.

**Nuance #6** — SQLite WAL mode — keep all `@Transactional` boundaries short and tight.

**Nuance #7** — The existing `ReflexRegistry` two-pass chain must NOT be broken or reordered. New reflexes are additive: `CDI Instance<AgentReflex>` discovers them automatically.

**Nuance #8** — Quarkus profiles: `dev` / `test` / `staging` / `prod` — all config changes must be reflected in all relevant profile blocks in `application.yaml`.

**Nuance #9** — GraalVM native image: any new class using sealed interfaces or records must be registered in `native-image.properties` if not auto-detected.

**Nuance #10** — No `ALTER TABLE` or migration scripts of any kind. All regular table schema changes are `@Entity` only. Raw DDL reserved for virtual tables (`USING fts5/vec0`), triggers, and indexes ONLY.

**Nuance #11** — `SkillAwareIntentClassifier` checks `SkillRegistry` FIRST, then falls through to the LLM-based `IntentClassifierService`. Never invert this order.

**Nuance #12** — `ToolCapability` enum constants use underscores: `GITHUB`, `MEMORY_SEARCH`, `SCHEDULE_TASK`, `MANAGE_SCHEDULE`, `SANDBOX_EXEC`. `ToolCapability.toolName()` returns the lowercase no-underscore form: `"github"`, `"memorysearch"`, etc. `McpToolAllowlist @WithDefault` uses `toolName()` values (lowercase), NOT the enum constant names, because it is matched against external MCP server tool names which are always lowercase.

**Nuance #13** — `maxBudgetUsd` is EXPLICITLY EXCLUDED from skill frontmatter.

**Nuance #14** — In `application.yaml` test profile:
```yaml
%test:
  arcclaw:
    skills:
      hot-reload: false
```

**Nuance #15** — Dynamic skills use pre-compiled Qute wrapper `SkillSystemPrompt.html` only.

**Nuance #16** — ALL REST resource classes that touch JPA/Hibernate or trigger async audit writes MUST be annotated `@RunOnVirtualThread` at class level. Missing → `BlockingOperationNotAllowedException` immediately on any request. `McpAdminResource` MUST have `@RunOnVirtualThread`.

**Nuance #17** — Repository pattern in `arcclaw-core` ALWAYS uses raw `EntityManager` (`@Inject EntityManager em`). NEVER `PanacheRepositoryBase` or `PanacheEntity` — Panache bytecode enhancement is module-scoped and fails in cross-module `arcclaw-app` tests. `AgentSkillRepository` is the exact precedent.

**Nuance #18** — SQLite test isolation `@BeforeEach` rules: (a) All writes must be in a committed transaction BEFORE the first RestAssured call. (b) Read-only repository methods use `@Transactional(TxType.SUPPORTS)`. (c) Use `deleteAll()` + `GET /refresh` in `@BeforeEach` for clean baseline. Violating (a) → snapshot isolation anomalies.

**Nuance #19** — `@QuarkusTest` REST tests — every `given()` block for a protected endpoint MUST include: `.header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")`. Missing → 401 and test passes for the wrong reason.

**Nuance #20** — MCP stdio transport uses Newline-Delimited JSON-RPC 2.0. `PodmanMcpTransport` MUST: append `'\n'` to EVERY outbound message written to the process `OutputStream`, call `flush()` after every write, and read inbound messages line-by-line using `BufferedReader.readLine()`. Failure to frame with `'\n'` causes the remote MCP server to hang forever.

**Nuance #21** — In native image, EVERY class, record, or enum passed as data to a Qute template via `.data("key", object)` MUST be annotated `@TemplateData` (`io.quarkus.qute.TemplateData`). Without it, ANY property access `{var.field}` throws `TemplateException` at runtime — NOT at build time. This is silent in JVM mode and only surfaces in native. `@TemplateData` is SEPARATE from `@RegisterForReflection` — both may be needed.

**Nuance #22** — `AgentRoleEnum` constants are ROLE NAMES not deity names: `COORDINATOR`, `RESEARCHER`, `CODER`, `DEBUGGER`, `ARCHITECT`, `SYNTHESIZER`. Display names carry deity: `"Brihaspati (System Designer)"`, `"Dattatreya (Synthesizer)"`. The old `DATTATREYA` constant no longer exists — it is `SYNTHESIZER`. `fromString()` aliases: `"dattatreya"`, `"datta"`, `"synthesizer"` all → `SYNTHESIZER`. `PromptTemplateEngine.templateFor()` switch MUST be exhaustive across all 6 roles.

**Nuance #23** — `sqlite-vec` `load_extension()` uses the OS dynamic linker (Windows `LoadLibrary` / Linux `dlopen`). It IGNORES `java.library.path` entirely. Relative paths are resolved against the JVM working directory — which differs between dev mode (`arcclaw-app/`) and JAR mode (project root/). ALWAYS resolve via `Path.of(vecExtensionPath).toAbsolutePath().normalize().toString()` in `FtsConnectionInterceptor` before passing to `load_extension()`. `%dev` profile config: `arcclaw.rag.vec-extension-path: "../lib/vec0"`.

**Nuance #24** — Apache Camel dev mode lifecycle — CRITICAL: `camelContext.disableJMX()` is guarded by `isNew()` check in `AbstractCamelContext`. In Quarkus dev mode, `StartupEvent` fires AFTER the `INITIALIZED` state. `disableJMX()` becomes a SILENT NO-OP — `JmxManagementLifecycleStrategy` remains registered. Calling `camelContext.start()` then NPEs in `onContextStarting():189`. CORRECT FIX before `camelContext.start()`:
```java
camelContext.getLifecycleStrategies()
    .removeIf(s -> s.getClass().getName().contains("JmxManagement"));
```
This bypasses the `isNew()` guard and removes the strategy directly. JAR/native unaffected.

**Nuance #25** — `StructuredOutputEngine` resource loading: ALWAYS use `Thread.currentThread().getContextClassLoader().getResourceAsStream()`. NEVER use `getClass().getResourceAsStream()` — fails for in-memory resources generated by Quarkus augmentation in multi-module test execution.

**Nuance #26** — `AgentFunctionProcessor` schema generation: Schema must be generated from the SAM RETURN TYPE using `SchemaFactoryWrapper.acceptJsonFormatVisitor(returnTypeClass, visitor)`. NEVER use `ToolSpecifications.toolSpecificationFrom()` — that generates INPUT parameter schema, not the OUTPUT schema. Generated resource path: `schemas/functions/{InterfaceSimpleName}.json`. Emit `GeneratedResourceBuildItem` ONLY — never write to filesystem.

**Nuance #27** — `PermissiveJsonExtractor` Jackson configuration:
- CORRECT: `JsonReadFeature.ALLOW_SINGLE_QUOTED_STRINGS`
- WRONG: `JsonReadFeature.ALLOW_SINGLE_QUOTES` (does not exist — compile error)
- CORRECT: `JsonReadFeature.ALLOW_TRAILING_COMMA` (token-level, preserves commas in strings)
- WRONG: regex comma stripping (corrupts `"He left, she stayed"` → `"He left"`)
- ABSENT (intentional): `FAIL_ON_MISSING_CREATOR_PROPERTIES` — allows `@Nullable` fields to be absent from JSON without throwing.

**Nuance #28** — `StructuredRetryLoop` budget handling: `BudgetWarningException` → CATCH internally, log, proceed with retry (soft signal). `BudgetExceededException` → DO NOT CATCH, propagate to `AgentStateMachineRunner`. `assertWithinBudget()` threshold check MUST precede `compareAndSet` CAS — if CAS fires at 79% budget, `warningSent` is permanently true and the real 80% boundary warning is forever suppressed.

**Nuance #29** — Hibernate ORM 7 + `QuarkusTransaction` lambda scope: `EntityManager` proxy does NOT correctly bind to a new transaction inside a `QuarkusTransaction.requiringNew().run(lambda)`. Extract lambda bodies into dedicated `@Transactional(TxType.REQUIRES_NEW)` methods for correct proxy binding. `em.merge()` on an already-managed entity is a NO-OP in ORM 7 — rely on dirty checking instead. `em.find()` outside `@Transactional` returns null.

**Nuance #30** — Hibernate ORM 7 + SQLite DDL identity columns: Without `ArcClawSQLiteDialect.hasDataTypeInIdentityColumn()=true`, Hibernate ORM 7 emits DDL like `"checkpoint_id,"` (no type), breaking SQLite auto-increment rowid. `ArcClawSQLiteDialect` is mandatory for all entity tables with `@GeneratedValue`.

**Nuance #31** — `ToolObservationSanitizer.sanitize()` must wrap every tool response
before `history` append. Call site: `ToolInvoker.invoke()`. Never bypass for any tool
type including GitHub, sandbox, or MCP tools.

**Nuance #32** — `AsyncPersistenceEngine.submitCritical()` is bounded at 500. On
overflow → synchronous persist on the calling thread. A mandatory event
(`isMandatory=true`) is NEVER dropped. `submitTelemetry()` may drop on overflow —
increment Micrometer counter.

**Nuance #33** — `System.nanoTime()` for all elapsed-time measurements.
`System.currentTimeMillis()` is wall-clock, goes backward on NTP correction or
leap-second, and is FORBIDDEN for duration calculation.

**Nuance #34** — `EncryptedSecret.ormVersion` is Hibernate-managed.
`EncryptedSecret.encryptionKeyVersion` is application-managed. Application code sets
only `encryptionKeyVersion`. NEVER set or read `ormVersion` from application code.

**Nuance #35** — Vault pepper is `char[]`, zeroed immediately after use via
`Arrays.fill(pepperChars, '\0')` inside `deriveSessionKey()`. Never store as `String`.
`CharArrayConverter` registered in
`META-INF/services/org.eclipse.microprofile.config.spi.Converter` handles
MicroProfile injection of `char[]` config values.

**Nuance #36** — System-level SecretVault operations that must execute before any
WorkspaceScoped session is active use `VaultConstants.SYSTEM_SESSION_ID` as the
session identifier. This bypasses `VaultKeyCache`. The constant must never be a raw
inline string literal `"SYSTEM"`.

**Nuance #37** — `DevSecretSeeder` observes `StartupEvent` at `@Priority(100)`.
All beans that depend on secrets seeded by `DevSecretSeeder` must observe
`StartupEvent` at a higher number (e.g., no `@Priority` = default APPLICATION 2000,
or explicit value > 100). Lower number fires first.

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
   @RunOnVirtualThread at class level (Nuance #16).
10. No Panache anywhere in arcclaw-core or arcclaw-mcp repositories. Raw
    EntityManager only. Follow AgentSkillRepository as the exact precedent.
11. AppReflectionConfig.java in arcclaw-app ONLY imports from arcclaw-app/arcclaw-rag.
    NEVER add arcclaw-mcp imports. GraalVM registration for arcclaw-mcp classes is
    handled exclusively by McpBuildStep in arcclaw-deployment via string-based
    ReflectiveClassBuildItem.
12. Any new AgentRoleEnum constant must be added to ALL switch expressions:
    PromptTemplateEngine.templateFor(), AgentStateMachineRunner routing,
    IntentClassifierService roles list, and sanitize() fallback map.
    Exhaustive switch = compile error on missing case. Use this as the safety net.

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

Component 1 - <title>
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

TROUBLESHOOTING.md — add challenges in this phase troubleshooting section
