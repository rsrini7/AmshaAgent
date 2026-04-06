--- PROMPT PHASE 80-A-EXT ......Tier A - WHAT IS OUT OF SCOPE FOR THIS EXTENSION ---

The following Grok/Gemini findings are explicitly deferred and must NOT be touched
in Phase 80-A-EXT. Touching them in this phase violates the surgical-fix contract
and risks destabilizing the 977+ tests already green from Phase 80-A (A1–A10).

  DEFERRED TO PHASE 80-B (should fix before Phase 82 Playwright if bandwidth allows):

  - ToolInvoker God-class → extract ToolInvocationStrategy interface with
    SingleToolStrategy and ParallelBatchStrategy implementations (Grok-G1).
    Reason for deferral: this is a structural refactor touching the most critical
    class in the agent loop. Requires its own dedicated phase with full loop
    regression coverage. Do NOT attempt as part of A11–A18.

  - OTel span context propagation to virtual threads in ParallelToolStepExecutor
    (Grok-G8, Invariant §7.13). Reason for deferral: A11 fixes the WorkspaceContext
    ScopedValue propagation. OTel propagation is a separate concern using
    io.opentelemetry.context.Context.current() — a different API from ScopedValue.
    These must be fixed sequentially, not simultaneously, to isolate regression risk.
    Fix A11 first, verify green, then address OTel in Phase 80-B.

  - StaleApprovalExpired race — idempotency guard when callback arrives after
    expiry but before checkpoint cleaned (Grok-G6). Reason for deferral: this
    requires adding an isExpired() timestamp check in TelegramCallbackHandler and
    coordinating with the WorkspaceManager GC scheduler. The interaction surface
    is wide enough to warrant its own component. Phase 80-B.

  - AgentResultFormatter.formatHitlMessage MarkdownV2 double-escape (Grok-G3).
    Reason for deferral: requires verification that the bug actually exists in
    current code before writing a fix. Review formatHitlMessage output against
    sendWithKeyboard escaping in isolation. If confirmed, fix in Phase 80-B.
    Do NOT fix speculatively.

  - SchedulingRoutingProvider / IngestRoutingProvider negation false positive
    (Gemini-P4). Reason for deferral: the regex fix (add negative lookahead for
    don't / do not / not within N tokens of trigger keyword) is a behavioral
    change to intent routing. Intent routing changes require LLM-level regression
    testing across multiple message scenarios — not just unit tests. Phase 80-B
    with a dedicated routing regression suite.

  - ApiKeyRegistry.getKeyName O(N) HMAC scan → O(1) precomputed map (Gemini-P7).
    Reason for deferral: performance optimization, not a correctness defect. The
    current code is functionally correct. The optimization is straightforward but
    should be batched with other authentication layer improvements. Phase 80-B.

  - MDC clearing copy-pasted across TelegramAgentHandler, CronJobRunner,
    AgentCronScheduler (Gemini-P5). Reason for deferral: DRY refactor touching
    three classes that each have existing tests. Low correctness risk. Phase 80-B.

  - CronJobRunner deliveryId written before WorkspaceContext established
    (Gemini-P12). Reason for deferral: audit ordering issue — not a data loss
    risk under normal execution, only under context init failure which is already
    logged. Phase 80-B.

  - IntentClassifier delimiter injection — strip delimiter strings from user input
    (Gemini-P10). Reason for deferral: security hardening, not a live correctness
    defect. One-line fix deferred to Phase 80-B to batch with other
    IntentClassifierService changes (negation false positive fix from Gemini-P4).

  DEFERRED TO PHASE 81 (after onboarding backend is complete):

  - Deity persona boilerplate collapse → DefaultAgent + Qute template (Grok-G5,
    Comet-MAINT-1). The personas are functionally correct. The maintainability
    debt is real but tolerable for one more phase. Collapsing personas during
    Phase 81 when the skill/persona system is already being touched for onboarding
    makes more sense than a standalone persona refactor now.

  - ChatHistoryAssembler O(n) per-turn String allocation optimization (Comet).
    Phase 81 — performance optimization, not correctness.

  - COMPLIANCE_OVERRIDE_PATTERN false-positive logic rewrite → ComplianceFilter
    SPI (Comet). Phase 81 — requires new interface and behavioral spec.

  - ReplyNode permit count ArchUnit enforcement (Comet). Phase 81 — requires
    ArchUnit dependency decision and test wiring.

  - arcclaw-ui maven-antrun bundling → proper WebJar or Quarkus static resource
    extension (Grok-G9). Phase 81 — fix when frontend surface is being touched
    for the onboarding Lit component anyway.

  - compactionSummaryRepository called per LLM turn → request-scoped cache or
    loop-start prefetch (Comet). Phase 81 — performance, not correctness.

  DEFERRED TO PHASE 82+ (after Playwright browser tool):

  - SessionRegistry multi-pod JVM-local lock → distributed lock or DB-level
    upsert with conflict resolution (Gemini-P3). Valid concern for distributed
    deployments. Not a current blocker for SQLite single-node. Phase 82+ when
    deployment model is re-evaluated.

  - Invariants 7.8 vs 7.19 callId contradiction (call0 pattern vs UUID fallback
    for Ollama) — requires provider-specific reconciliation spec. Phase 82+.

  - AgentRoleEnum static God-initializer Map.of(...) → validated startup registry
    with SPI and duplicate guard (Comet). Phase 82+ — architectural refactor.

  - SynthesisResult Map<String, Object> additionalInfo unbounded heap risk →
    schema validation layer (Comet). Phase 82+.

  - ToolInvoker full SRP decomposition beyond what Phase 80-B partial extract
    achieves (Grok-G1 full scope). Phase 82+.

  - DelegateToAgentTool Option B (bounded execution mask — intersection of parent
    and child capabilities) as follow-on to A17 strict Option A. Phase 82+.

--- PROMPT PHASE 80-A-EXT ......Tier A - COMBINED PHASE 80-A COMPLETION CRITERIA ---

Phase 80 (A1–A18 combined across both prompts) is considered COMPLETE when ALL of
the following are true simultaneously:

  □ All 18 components (A1–A10 from Phase 80-A + A11–A18 from Phase 80-A-EXT)
    have their individual isolated test passing green.

  □ Full verification command passes with zero failures:
    ./mvnw clean verify -pl arcclaw-channel-api,arcclaw-channel-telegram,
        arcclaw-channel-web,arcclaw-core,arcclaw-app -am

  □ Minimum test count: 985 green tests
    (967 original + 18 new minimum, one per component).
    Realistic target given 3–4 tests per component: 1001–1009.

  □ ArchitectureInvariants.md has been updated with:
    - New §2.7 (ScopedValue full context propagation — A11)
    - New §4.10 (DelegateToAgentTool capability escalation prevention — A17)
    - New §7.34 (Web UI per-tool approval granularity — A13)
    - Updated §7.25 (pipe separator format, colon forbidden — A14)

  □ Zero compiler warnings introduced by any fix across all modules.

  □ ArchUnit test from A18 is part of the permanent test suite and passes.

  □ The following strings do NOT appear anywhere in src/main/java after Phase 80:
    - "Pipeline crash"          (removed by A10)
    - "split(":")"              (removed by A14 — separator changed to pipe)
    - "HttpClient.newHttpClient()" inside TelegramCallbackHandler  (removed by A3)
    - "Collections.emptyList()" as the no-arg constructor value in
      AgentPersonaRegistry     (removed by A8)

  □ The following Architecture Invariant violations no longer exist:
    - §2.3 violation (tool path using WorkspaceScopeHolder) — fixed by A11
    - §1.4 violation (channel-web accessing core internals) — fixed by A18
    - §4.5 violation (channel plugin bypassing SPI) — fixed by A18
    - §7.25 violation (colon separator breaking namespaced tools) — fixed by A14

--- PROMPT PHASE 80-A-EXT ......Tier A - GATEWAY TO PHASE 81 ---

Phase 81 (arcclaw-onboarding backend + arc-onboarding.ts Lit component) MUST NOT
begin until the Phase 80 completion criteria above are fully met.

Specific pre-conditions for Phase 81 that Phase 80 directly enables:

  1. A18 complete → arcclaw-channel-web is SPI-compliant. The arcclaw-onboarding
     backend can now implement ChannelPlugin cleanly, copying the corrected web
     channel as its reference implementation — not the broken pre-A18 version.

  2. A12 + A13 complete → CheckpointApprovalService exists as a proper domain API.
     The onboarding frontend approval UX can call this service directly through
     the REST layer without creating fake MessageContexts.

  3. A11 complete → ScopedValue full context propagation is correct. Any async
     work the onboarding backend performs (e.g., streaming session setup,
     background credential validation) will correctly carry user identity and
     autonomy level from the start.

  4. A17 complete → Delegation capability escalation is blocked. The onboarding
     flow may use coordinator-delegated agents for initial workspace setup — those
     delegations will be governance-safe.

  5. A14 complete → Pipe separator in callback data. If the onboarding backend
     ever triggers HITL (e.g., approval to create a workspace with elevated
     permissions), the callback parsing will correctly handle any tool name
     including future namespaced ones.

Phase 81 entry is BLOCKED if any of A11, A12, A13, A14, A17, A18 are incomplete.
Phase 81 entry is PERMITTED if only Phase 80-B items (OTel, ToolInvoker strategy,
stale approval race, negation regex, MDC DRY, ApiKey O(1)) remain open — those
are performance and maintainability items, not correctness blockers for new feature
development.

The following issues were identified in the P79 deep review but are explicitly deferred.
Do NOT address these in Phase 80. Touching them in this phase violates the surgical-fix
contract and risks breaking the 967 existing tests.

  DEFERRED TO PHASE 81:
  - VishnuAgent hardcoded stale team descriptions → requires dynamic assembly from
    AgentPersonaRegistry at startup; architectural change
  - COMPLIANCE_OVERRIDE_PATTERN false-positive logic rewrite → requires dedicated
    ComplianceFilter SPI with auditable behavior; new interface
  - ReplyNode permit count ArchUnit enforcement test → requires ArchUnit dependency
    decision and module test wiring
  - compactionSummaryRepository called per LLM turn → requires request-scoped cache
    or loop-start prefetch pattern; touches AgentLoop contract
  - TelegramAllowlistGuard List→Set pre-build at @PostConstruct (full perf fix) →
    A6 adds the warning; the Set conversion is a follow-on in Phase 81
  - ChatHistoryAssembler O(n) per-turn String allocation optimization → requires
    incremental assembly design; Phase 81

  DEFERRED TO PHASE 82+:
  - SystemPromptBuilder God class decomposition → architectural refactor; multiple
    new interfaces required; Phase 82
  - AgentRoleEnum DEFINITIONS static God-initializer refactor → SPI registry
    migration; breaking change to role registration contract; Phase 82
  - Invariants 7.8 vs 7.19 callId contradiction (call0 pattern vs UUID fallback for
    Ollama) → requires provider-specific reconciliation spec to be written first;
    then a Phase 83 implementation pass
  - SynthesisResult Map<String, Object> additionalInfo unbounded heap risk → requires
    schema validation layer design; Phase 82
  - AgentRoleEnum Map.of() NoClassDefFoundError on duplicate key → convert to
    validated startup map; Phase 82
  - TelegramCallbackHandler updateOrRemoveKeyboard snapshot isolation gap → requires
    checkpoint read under optimistic lock or dedicated checkpoint query method;
    Phase 82

