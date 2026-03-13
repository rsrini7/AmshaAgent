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

### Phases 0–15: Foundation

| Phase | Name | Tests |
|-------|------|-------|
| 0 | Project Scaffolding | N/A |
| 1 | Core Scope Layer | 3/3 |
| 2 | Security Layer | 4/4 |
| 3 | Sandbox Layer | 3/3 |
| 4 | Tool Layer | 7/7 |
| 5 | Agent & Orchestration | 5/5 |
| 6 | State Layer (Checkpointing) | 4/4 |
| 7 | Lifecycle Layer | 4/4 |
| 8 | Governance Layer | 3/3 |
| 9 | Observer / OTel | 2/2 |
| 10 | Health Checks | 2/2 |
| 11 | REST API | 3/3 |
| 12 | LangChain4J Integration | 2/2 |
| 13 | Deployment Artifacts | N/A |
| 14 | Deployment Loops | Manual |
| 15 | Dual-Mode Binary | N/A |

***

### Sprint 2A + Phases 18–24: Telegram & Hardening

| Phase | Name | Tests |
|-------|------|-------|
| Sprint 2A | Telegram Channel Adapter | Manual |
| 18 | Telegram Approval Flow (HITL) | Manual |
| 19 | Day 2 Hardening | Manual |
| 20A | Persistent ChatMemoryStore | 48/48 |
| 20B | LLM-Based Intent Router | 48/48 |
| 20C | Prometheus Metrics | Manual |
| 20D | ShivaAgent Integration | Manual |
| 20E | CLI Hardening (`%cli` profile) | Manual |
| 21 | CLI Subcommands (Chat & Attach) | Manual |
| 22 | Final Pre-Release Hardening & UX | Manual |
| 23 | Sandbox Enforcement Centralization | 7/7 |
| 24 | Context Tool Bias Fix + `/reset` | 48/48 |

***

### Phases 25–47: Core Infrastructure (Previously Provided)

| Phase | Name | Tests |
|-------|------|-------|
| 25 | Sandbox Hardening | Manual |
| 26 | Security Hardening | Manual |
| 27 | Tool Flexibility & CDI Proxy Unwrapping | Manual |
| 28 | Intent Hardening & Auto-Cancellation | Manual |
| 29 | Memory Optimization — Rolling Window + Protocol Filtering | 13/13 |
| 30 | ReflexRegistry — HaltReflex, StatusReflex, ResetReflex, ApproveReflex, DenyReflex | Pass |
| 31 | PiiScanReflex — 10 patterns (expanded to 19+ in 41) | Pass |
| 32 | ApiKeyAuthFilter, Rate Limiter, TelegramAllowlistGuard | 68/68 |
| 33 | PromptInjectionReflex | Pass |
| 34 | Tool Schema & Agent Tooling | 68/68 |
| 35 | WORM Audit Log — HMAC SHA-256 chain, SQLite triggers | 12/12 |
| 36 | OTel child spans, token accumulation, REST /approve + /deny | 71/71 |
| 36.1 | GithubTool Read-Only Schema Jail, LLM 400 Error Self-Healing | Pass |
| 37 | Security Hardening — Caffeine Rate Limiter, Optimistic Locking | 21/21 |
| 38 | ContextCompactionService, CompactionSummaryRepository | Pass |
| 39 | Java/Maven migration 21→25, /history reflex, destructive vocab override | Pass |
| 40 | BudgetWarningException (80% soft), /status extended, HistoryReflex | 21/21 |
| 41 | Resilience, Privacy & Observer Separation — 19+ PII patterns, Circuit Breaker | 100% |
| 42 | ScopedValue Context Migration | 100% |
| 43 | TmpfsSecretTransport — RAM-backed secret passing, zero-copy byte[] | 100% |
| 44 | Production Hardening — SQLite IMMEDIATE, RoleRegistry refactor | 100% |
| 45 | Agent Memory RAG via SQLite FTS5 | 100% |
| 45.1 | FTS5 Hardening & Robustness | 100% |
| 46 | Proactive Autonomy via Agent-Writable Cron | 100% |
| 47.0 | Unified Capabilities & Context (Tier 1) — Bucket4j API Key refactor | 100% |
| 47.1 | Message Pipeline & Sealed Interface Refactor (Tier 2A) | 100% |
| 47.2 | Refined Routing & Templates — Qute system prompts (Tier 2B) | 100% |
| 47.3 | Agent State Machine Refactor (Tier 3/3A) | 100% |

***

### Phases 48–58: Post-Phase 47 (New from Walkthrough)

| Phase | Name | Tests |
|-------|------|-------|
| 48 | Telegram Bot Refactor — Removed tokens from URIs | 100% |
| 49 | Intent & Governance Hardening — Prompt injection delimiters, zero-token guard | 100% |
| 50 (P2-1) | CLI Approval Flow — Heartbeats, truncated params, SUPERVISED mode | 100% |
| 50 (P2-4) | CronStatus Enum — Type-safe cron status | 100% |
| 50 (P2-8) | Dynamic System Prompt Per Role — `AgentPersonaRegistry`, Shiva fix | 100% |
| 51 | MessageTag Compliance — UUID-derived prefix for AI compliance filtering | 100% |
| 51 | Fail Fast & Compaction Fix — Exit code 1 on permission failure | Manual |
| 51 | Telegram Route Self-Registration — `@Observes StartupEvent` | 100% |
| 51 | Destructive Regex & Typo Fix (P3-3) | 100% |
| 51 | MessagePipeline Exception Handling (P3-6) — Crash notifications | 100% |
| 51 | Stateful & Telegram Resilience — Exp. backoff, sequential queues | 100% |
| 52 | General Hardening — Cron, Bot, Intent, Startup Profile Guards | 100% |
| 52 | MessageTag Regex Cleanup — Removed legacy regex compliance filtering | 100% |
| 52 | Telegram Hardening (P3-12) — Caffeine chat queue cache | 100% |
| 52 | DattatreyaAgent: The Synthesizer — Meta-agent for specialist integration | 100% |
| 53 | Vault Key Caching (P3-1) — `@WorkspaceScoped` cache + `@PreDestroy` wipe | 100% |
| 53 | AgentCronScheduler SRP Refactor — Dispatcher, executor, handler split | 100% |
| 53.3 | Agent State Machine Refactor — Guard, Invoker, Writer extraction | 100% |
| 53.4 | LangChainReasoningEngine Split — Parser, Assembler, Builder | 100% |
| 53.5 | Package Reorganization — 23 classes into 5 sub-packages | 266/266 |
| 53.6 | AgentLoop & AuditService Split — ResumeHandler, Telemetry/Compliance tiers | 266/266 |
| 53.9 | AgentResource Split — `AgentResponseMapper`, `AgentSessionService` | 30/30 |
| 54 | Agent Test Refactor — Moved 10 tests to `arcclaw-core` | 471/471 |
| 55.1 | JDK Modernization Sweep — Unnamed vars, Sequenced Collections, Pattern extraction | 471/471 |
| 55.2 | ScopedValue & Constructor Injection — 10 beans migrated | 471/471 |
| 55.3 | AgentCheckpoint Normalization — Granular `reasoning_step` table | 471/471 |
| 56 | Hardcoded String Constant Sweep — `ToolCapability`, `AgentRoleEnum` constants | 121/121 |
| 57 | Rate Limit User Notification — 429 graceful fallback, `⏳` acknowledgment | 121/121 |
| 58 | System Metadata Registry — `/api/v1/info`, `SystemMetadataHealthCheck` | 122/122 |

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