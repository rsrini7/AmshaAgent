Role: Senior Java Architect, Bug Fixer, Refactoring Expert & Senior Java Developer
Task: Phase X — Full-Spectrum Refactoring for Bug fixes, Maintainability, and JDK 25 Modernization for the ArcClaw ecosystem.

Architecture Context:
A production-grade, enterprise AI Agent Kernel built with:
  - Java 25 + Quarkus 3.23 + LangChain4J 0.26.1
  - GraalVM Native Image (Podman container build)
  - SQLite WAL (single-node, strict singleton topology)
  - Apache Camel (Telegram channel via camel-quarkus-telegram)
  - OpenTelemetry — span tracing via AgentOtelContext
  - Podman process isolation (sandbox strategy, network=none)
  - AES-GCM-SIV SecretVault (Argon2id key derivation)
  - GovernanceLayer (USD budget cap, AutonomyLevel: FULL/SUPERVISED/READONLY)
  - Multi-agent Trimurti model: Vishnu(coordinator), Brahma(coder),
    Shiva(debugger), Saraswati(researcher)
  - IntentClassifierService (llama-3.1-8b-instant via Groq, routes to agent role)
  - Multi-module Maven: arcclaw-parent → arcclaw-core, arcclaw-deployment, arcclaw-app
  - AgentReflex interface: matches(), execute(), default sanitize()
  - ZERO external API dependencies for reflex/security — pure java.util.regex only
  - All Pattern objects MUST be static final (compile once, never per-message)
  - Quarkus Qute templating available for prompt management
  - Bucket4j available for rate limiting (replaces hand-rolled sliding window)
  - CDI Events (jakarta.enterprise.event.Event<T>) available for decoupling

## Completed Phases (DO NOT re-suggest or re-implement)

- Phase 25–28: Core infrastructure (Quarkus, Telegram channel, GovernanceLayer,
  SecretVault, WorkspaceManager, ToolRegistry, ProcessIsolationExecutor)
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
- Phase 47: Unified Capabilities & Context Modernization, Message Pipeline & Sealed Interface Refactor, Refined Routing & Templates, Agent State Machine Refactor, Prompt tuning for ScheduleTask and AgentErrorMessageUtil parsing logic fix.

## Known Architecture Nuances (ALWAYS apply these)

1. SystemMessages are NEVER stored in SQLite ChatMemoryStore.
2. All CDI beans touching per-session context must use ScopedValue — never
   @ApplicationScoped mutable fields for per-session state.
3. Pattern objects are always static final — never compiled inside methods
   or constructors.
4. ToolRegistry.invokeTool() uses String keys — any typed ToolCapability enum
   must bridge via .toolName() accessor.
5. AgentCheckpoint.stateJson is currently a single blob — this phase introduces
   ReasoningStepEntity to normalize it into rows.
6. The arcclaw-deployment module is Quarkus build-time only — never add runtime
   logic there.
7. SQLite WAL mode — keep all @Transactional boundaries short and tight.
8. The existing ReflexRegistry two-pass chain must NOT be broken or reordered.
9. Quarkus profiles: dev / test / staging / prod — all config changes must be
   reflected in all relevant profile blocks in application.yaml.
10. GraalVM native image: any new class using sealed interfaces or records must
    be verified for auto-detection; register in native-image.properties if not.
11. The data folder is always fresh — no ALTER TABLE or migration scripts of
    any kind are needed. All schema changes are expressed solely as updated
    @Entity class definitions. Hibernate will create the correct schema on
    first boot.

## Known Architecture Nuances (ALWAYS apply these)
1. SystemMessages are NEVER stored in SQLite Chat    

## JDK Feature Policy (CRITICAL — READ BEFORE ANY CODE)

ONLY use JDK features that are FULLY FINALIZED (non-preview) in their
respective release. The following are PERMITTED:

  ✅ Sealed interfaces + pattern matching for switch (final: JDK 21)
  ✅ Records (final: JDK 16)
  ✅ Virtual Threads (final: JDK 21)
  ✅ SequencedCollection — getFirst(), getLast() (final: JDK 21)
  ✅ Pattern Matching instanceof (final: JDK 16)
  ✅ Text Blocks (final: JDK 15)
  ✅ ScopedValue (final: JDK 24 via JEP 487)
  ✅ Unnamed Variables _ in catch/try (final: JDK 22 via JEP 456)
  ✅ Stream Gatherers (final: JDK 24 via JEP 473)
  ✅ Record deconstruction patterns (final: JDK 21)

The following are FORBIDDEN — they are preview or were preview in recent JDKs:

  ❌ StructuredTaskScope / Structured Concurrency (still preview in JDK 25)
  ❌ Flexible Constructor Bodies / statements before super() (JEP 482 —
     was preview in JDK 23 and JDK 24; confirm final status before use,
     treat as FORBIDDEN until confirmed)
  ❌ Any feature requiring --enable-preview compiler flag
  ❌ value classes / primitive types (preview in JDK 25)

Do NOT add --enable-preview to any pom.xml compiler or surefire configuration.
The project already targets Java 25 with standard final features only.

## Global Implementation Requirements (apply to ALL components)

1. Every new or modified class must compile cleanly under JDK 25 standard
   (no --enable-preview).
2. Every change must preserve ALL existing passing test states.
3. Use List.copyOf() for all defensive list copies — never return raw mutable
   references from records or value types.
4. All new sealed interfaces must implement a discriminant() method returning
   a stable String — used for SQLite serialization state resumption.
5. Config keys strictly follow arcclaw.channel.telegram.* namespace for all
   Telegram config — never arcclaw.telegram.* shortcut.
6. No magic string tool names in AgentLoop, AgentStateMachineRunner, or
   ToolInvocationPipeline — all tool references via ToolCapability.toolName().
7. No new SomeDomainPolicy() inside CDI beans — all policies are @Injectable.
8. All test commands use clean to prevent stale .class false-negatives:
   .\mvnw.cmd clean test -pl <module> -am "-Dtest=..." "-Dsurefire.failIfNoSpecifiedTests=false"

## Execution Order

P0 -
P1 -
P2 -

Acknowledge this architecture context.
Do not write any code yet.
Reply with ACKNOWLEDGED and wait for me to send P0 Prompt.


Once Acknowledge send Follow up Prompts in this format:

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
PLAN.md — update this Phase Plans 
TASKS.md — tick off Phase tasks
WALKTHROUGH.md — add this Phase architecture walkthrough