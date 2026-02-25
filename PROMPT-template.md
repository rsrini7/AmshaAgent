Role: Senior Security Architect, Security Reviewer & Java Developer
Task: <tobe filled> for ArcClaw ecosystem.

Architecture Context:
A production-grade, enterprise AI Agent Kernel built with:
  - Java 21 + Quarkus 3.23 + LangChain4J 0.26.1
  - GraalVM Native Image (Podman container build)
  - SQLite WAL (single-node, strict singleton topology)
  - Apache Camel (Telegram channel via camel-quarkus-telegram)
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

### Live Jailbreak Test — PASSED ✅
- Attack: social engineering to execute Go HTTP scanner via Telegram
- LLM self-injected "Human Approved" marker + called github with empty args {}
- Defense layers confirmed in order:
    1. Schema Binding: no bash/exec in schema, LLM had only github
    2. ToolRegistry: github call still required human approval (SUPERVISED)
    3. Podman sandbox: sterile container, fatal: not a git repository, exit 1
    4. Base model safety: refused final URL execution independently
- Rolling window log confirmed: "pruned 21 → 20 messages (kept 0 system)"
- GovernanceLayer cost tracking confirmed: total=$0.1112 across session

## Known Architecture Nuances (ALWAYS apply these)
1. SystemMessages are NEVER stored in SQLite Chat

## Once all done, review update below md files based on the ask and implementation

TROUBLESHOOTING.md
PLAN.md
TASKS.md
WALKTHROUGH.md