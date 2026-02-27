Role: Senior Security Architect, Security Reviewer & Java Developer
Task: <tobe filled> for ArcClaw ecosystem.

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
  - AgentReflex interface has: matches(), execute(), default sanitize()
  - ZERO external API dependencies — pure java.util.regex only
  - All Pattern objects MUST be static final (compile once)

## Completed Phases (DO NOT re-suggest these)

Prior Phases Completed (DO NOT re-suggest or re-implement):
- Phase 25–28: Core infrastructure (Quarkus setup, Telegram channel,
  GovernanceLayer, SecretVault, WorkspaceManager, ToolRegistry,
  ProcessIsolationExecutor/Podman sandbox, IntentClassifierService)
- Phase 29: Memory Optimization & History Filtering
    Dynamic Tool Schema Binding (LangChainReasoningEngine)
    Context Window Rolling Pruning (PersistentChatMemoryStore, N=20)
- Phase 30: ReflexRegistry with AgentReflex interface — HaltReflex, StatusReflex,
  ResetReflex, ApproveReflex, DenyReflex all implemented and tested.
  ReflexRegistry.processIfReflex() intercepts input BEFORE IntentClassifier/AgentLoop.
  Two-pass logic: Pass 1 = command short-circuit, Pass 2 = sanitization chain.
  ReflexResult record replaces Optional<String>.
- Phase 31: PiiScanReflex (Local PII Redaction)
    PiiScanReflex with 10 regex patterns, configurable via arcclaw.pii.mode:
    REDACT_AND_FORWARD (default) — sanitize and forward to LLM
    BLOCK_AND_NOTIFY — short-circuit in Pass 1, never reaches LLM
    containsPii() / detectsPii() / matches() separation
- Phase 32: REST API Authentication & Telegram Security
    ApiKeyAuthFilter (@Provider, Priorities.AUTHENTICATION)
    ApiKeyRegistry with ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>>
    sliding window rate limiter, ClockProducer for CDI Clock injection
    TelegramAllowlistGuard — null username safe, @ prefix stripping,
    case-insensitive, empty allowlist = open access
- Phase 33 — Prompt Injection & Adversarial Defense
- Phase 34 — Tool Schema & Agent Tooling
- Phase 35: Immutable WORM Audit Log (AuditEvent, AuditEventRepository insert-only,
            HMAC SHA-256 chain, SQLite triggers)
- Phase 36: OTel child span parenting, token accumulation, REST /approve + /deny
- Phase 36.1: GithubTool Read-Only Schema Jail, LLM 400 Error Self-Healing,
              in-memory ChatMemory clear() on reset
- Phase 37: PodmanToolRunner Circuit Breaker CLOSED→OPEN (3 failures), breaker
            blocks all calls in OPEN state — no half-open/reset logic yet
- Phase 38: Context Compaction (ContextCompactionService, CompactionSummaryRepository,
            rolling summary, virtual-thread safe)
- Phase 39: Java/Maven migration 21→25, /history zero-cost reflex, destructive
            vocab override, AiMessage compliance filter
- Phase 40: Budget warning at 80% soft threshold (BudgetWarningException),
            /status extended output (cost, tokens, compaction state, session ID),
            GovernanceLayer warning-before-hard-limit ordering confirmed

## Known Architecture Nuances (ALWAYS apply these)
1. SystemMessages are NEVER stored in SQLite Chat

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