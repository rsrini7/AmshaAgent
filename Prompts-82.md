╔══════════════════════════════════════════════════════════════════╗
║          ARCCLAW — PHASE 82: FINAL STRUCTURAL HARDENING          ║
║          Branch: P82 (cut from P81)                              ║
╚══════════════════════════════════════════════════════════════════╝

Phase 82 closes the last four structural debts before new feature
work begins. No new features in this phase. All 1012 existing tests
must remain green after every item. Commit prefix: fix(p82):

═══════════════════════════════════════════════════════════════════
P82-A  COLLAPSE 6 DEITY PERSONA SUBCLASSES  (High — 1 day)
═══════════════════════════════════════════════════════════════════

CURRENT STATE (code-verified):
  arcclaw-core/.../agent/persona/
    BrahmaAgent.java     (54 lines, role=coder)
    BrihaspatiAgent.java (role=coordinator)
    DattatreyaAgent.java (role=dattatreya — latest, 6 hrs ago)
    SaraswatiAgent.java  (role=researcher)
    ShivaAgent.java      (role=reviewer)
    VishnuAgent.java     (role=manager)

Each file is IDENTICAL in structure:
  @ApplicationScoped
  @AgentRole("<role>")
  public class XxxAgent implements Agent {
    private static final String SYSTEM_PROMPT = "...";
    public String role()         { return AgentRoleEnum.XXX.toScopedName(); }
    public String systemPrompt() { return SYSTEM_PROMPT; }
    public List<String> allowedTools() {
        return List.copyOf(AgentRoleEnum.XXX.definition().toolNames());
    }
  }

The ONLY thing that varies is: (1) role id, (2) system prompt text.
allowedTools() already delegates to AgentRoleEnum — fully data-driven.

REQUIRED CHANGE:

Step 1 — Extract each agent's SYSTEM_PROMPT string to a Qute template.
  Create files:
    arcclaw-core/src/main/resources/templates/prompts/agent-coder.txt
    arcclaw-core/src/main/resources/templates/prompts/agent-coordinator.txt
    arcclaw-core/src/main/resources/templates/prompts/agent-researcher.txt
    arcclaw-core/src/main/resources/templates/prompts/agent-reviewer.txt
    arcclaw-core/src/main/resources/templates/prompts/agent-manager.txt
    arcclaw-core/src/main/resources/templates/prompts/agent-dattatreya.txt
  Each file contains only the raw system prompt text (no Qute syntax needed
  unless the prompt needs dynamic content — keep it static for now).

  Naming convention: agent-{roleId}.txt where roleId matches
  AgentRoleEnum.toScopedName() stripped of "arcclaw:" prefix.

Step 2 — Add systemPromptTemplate() to RoleDefinition (or create
  AgentPromptLoader):
  Option A (preferred): Add a lookup in AgentPersonaRegistry:
    private String loadPromptTemplate(String roleId) {
        // Use Qute.fmt() or raw resource load:
        String resourcePath = "templates/prompts/agent-" + roleId + ".txt";
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalStateException(
                "No agent prompt template for role: " + roleId
                + " — create " + resourcePath);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

Step 3 — Create DefaultAgent.java:
  package com.arcclaw.core.agent.persona;
  // NOT @ApplicationScoped — instances produced by AgentPersonaFactory
  public final class DefaultAgent implements Agent {
    private final String roleId;
    private final String systemPrompt;
    private final List<String> allowedTools;

    public DefaultAgent(String roleId, String systemPrompt,
                        List<String> allowedTools) {
        this.roleId = roleId;
        this.systemPrompt = systemPrompt;
        this.allowedTools = List.copyOf(allowedTools);
    }
    @Override public String role()         { return roleId; }
    @Override public String systemPrompt() { return systemPrompt; }
    @Override public List<String> allowedTools() { return allowedTools; }
  }

Step 4 — Create AgentPersonaFactory.java:
  @ApplicationScoped
  public class AgentPersonaFactory {
    @Inject AgentPersonaRegistry registry;  // or inject prompt loader
    @Produces @ApplicationScoped @AgentRole("coder")
    public Agent brahma() { return buildAgent(AgentRoleEnum.CODER); }
    @Produces @ApplicationScoped @AgentRole("coordinator")
    public Agent brihaspati() { return buildAgent(AgentRoleEnum.COORDINATOR); }
    // ... one @Produces per AgentRoleEnum value ...

    private DefaultAgent buildAgent(AgentRoleEnum role) {
        String roleId = role.toScopedName();
        String prompt = loadPromptTemplate(role.id()); // id() = "coder" etc
        return new DefaultAgent(roleId, prompt,
                                role.definition().toolNames());
    }
    private String loadPromptTemplate(String roleId) { /* see Step 2 */ }
  }

  NOTE: @Produces + @AgentRole qualifier requires the @AgentRole
  annotation to have @Qualifier on it. Verify this is already the case.
  If not, add: @Qualifier @Retention(RUNTIME) @Target({...}) to @AgentRole.

Step 5 — Delete all 6 XxxAgent.java files.

Step 6 — Verify AgentPersonaRegistry still works:
  It currently does Instance<Agent> injection. After the factory change,
  CDI will still find all 6 produced beans via @AgentRole qualifier.
  No change needed in AgentPersonaRegistry if it uses Instance<Agent>.

ACCEPTANCE CRITERIA:
  □ 6 XxxAgent.java files deleted
  □ 6 agent-*.txt template files created with EXACT original prompts
  □ DefaultAgent.java + AgentPersonaFactory.java created
  □ All 1012 existing tests still green
  □ New test AgentPersonaCollapseTest.java:
      for each AgentRoleEnum value:
        - factory produces a non-null Agent
        - agent.role() equals AgentRoleEnum.toScopedName()
        - agent.systemPrompt() is non-blank and > 50 chars
        - agent.allowedTools() is non-empty and matches role definition
  □ Add to Architecture_Invariants.md §8.1:
      "Agent personas are data-driven. System prompts live in
       templates/prompts/agent-{roleId}.txt. No Agent subclass
       should ever be created — use AgentPersonaFactory."

═══════════════════════════════════════════════════════════════════
P82-B  HITL APPROVAL STATE MACHINE CONSOLIDATION  (High — 4 hrs)
═══════════════════════════════════════════════════════════════════

CURRENT STATE (code-verified from P81 tree):
  arcclaw-channel-telegram/.../telegram/
    BatchHitlKeyboardBuilder.java   — builds Telegram inline keyboards
    TelegramCallbackHandler.java    — handles callback_query from Telegram
    TelegramStaleApprovalObserver.java — observes stale events

  The approval DECISION logic (is this session approved/rejected/expired?)
  is duplicated across these files AND in whatever replaced
  CheckpointApprovalService (not found in arcclaw-core/state/ on P81 —
  locate its current package before proceeding).

REQUIRED CHANGE:

Step 1 — Locate the current approval decision entry point:
  grep -rn "approveTool\|rejectTool\|AWAITING_APPROVAL" \
    --include="*.java" . | grep -v test

  This will reveal where approval state transitions live.

Step 2 — Create ApprovalStateMachine.java in arcclaw-core:
  package com.arcclaw.core.hitl;

  @ApplicationScoped
  public class ApprovalStateMachine {
    /**
     * Single entry point for all approval decisions.
     * All callers (Telegram callback, Web UI, CLI reflex) MUST go through here.
     * Never call StatefulAgentStore approval methods directly from channel code.
     */
    public ApprovalResult decide(String sessionId, String toolName,
                                 ApprovalDecision decision, String reason) {
        // 1. Load checkpoint — throw CheckpointNotFoundException if absent
        // 2. Verify session is in AWAITING_APPROVAL state
        // 3. Verify toolName is in the pending tool list
        // 4. Check expiry via StatefulAgentStore.isCheckpointExpired()
        //    → if expired: return ApprovalResult.EXPIRED
        // 5. Record decision (approve/reject per tool)
        // 6. If all tools decided: transition state → APPROVED or REJECTED
        // 7. Fire ApprovalDecisionEvent CDI event for channel observers
        // 8. Return ApprovalResult with outcome + remaining tools
    }

    public record ApprovalResult(
        ApprovalOutcome outcome,
        String sessionId,
        String toolName,
        int remainingPendingTools
    ) {}

    public enum ApprovalOutcome { APPROVED, REJECTED, EXPIRED,
                                  PARTIAL_STILL_PENDING, NOT_FOUND }
    public enum ApprovalDecision { APPROVE, REJECT }
  }

Step 3 — Refactor TelegramCallbackHandler:
  Remove all approval state-transition logic.
  Inject ApprovalStateMachine.
  The handler only: parses the callback_data, calls
  approvalStateMachine.decide(...), then formats the Telegram
  response message based on ApprovalResult.outcome.
  No approval logic — only Telegram I/O.

Step 4 — Refactor BatchHitlKeyboardBuilder:
  Keep keyboard construction logic (it is Telegram-specific and correct).
  Remove any approval state reads — it should only receive tool lists
  and build keyboards. No StatefulAgentStore access.

Step 5 — Verify TelegramStaleApprovalObserver:
  It should observe CDI events from ApprovalStateMachine, not directly
  query the store. Update if needed.

ACCEPTANCE CRITERIA:
  □ ApprovalStateMachine.java created in arcclaw-core (not channel module)
  □ TelegramCallbackHandler delegates ALL decisions to ApprovalStateMachine
  □ No approval state transition logic remains in channel-telegram
  □ New ApprovalStateMachineTest.java with:
      - approve → APPROVED result
      - reject → REJECTED result
      - expired session → EXPIRED result (mock isCheckpointExpired=true)
      - unknown session → NOT_FOUND result
      - partial approval (3 tools, 2 approved, 1 pending) → PARTIAL result
  □ All 1012 existing tests still green

═══════════════════════════════════════════════════════════════════
P82-C  BROADEN ARCHUNIT RULE  (Medium — 30 min)
═══════════════════════════════════════════════════════════════════

CURRENT STATE:
  WebChannelArchitectureTest blocks only:
    "com.arcclaw.core.lifecycle.."
    "com.arcclaw.core.pipeline.."
  This does NOT prevent future imports from core.agent, core.governance,
  core.state, core.hitl, etc.

REQUIRED CHANGE:
  Replace the two specific package rules with one broad rule:

  @ArchTest
  static final ArchRule web_channel_must_not_depend_on_core_internals =
    noClasses()
      .that().resideInAPackage("com.arcclaw.channel.web..")
      .should().dependOnClassesThat()
      .resideInAPackage("com.arcclaw.core..")
      .because("Web channel must only depend on arcclaw-channel-api SPI. " +
               "Add new contracts to ChannelServices if core access is needed.");

  IMPORTANT: Before applying this rule, verify that no existing
  arcclaw-channel-web class imports from com.arcclaw.core.* EXCEPT
  through com.arcclaw.channel.api.*. Run the test first — if it fails,
  fix the leaking imports before making the rule permanent.

  Also add the same broad rule for arcclaw-channel-telegram:
  @ArchTest
  static final ArchRule telegram_channel_must_not_depend_on_core_internals =
    noClasses()
      .that().resideInAPackage("com.arcclaw.channel.telegram..")
      .should().dependOnClassesThat()
      .resideInAPackage("com.arcclaw.core..")
      .because("Telegram channel must only depend on arcclaw-channel-api SPI.");

  Place the Telegram ArchUnit rule in a new test file:
    arcclaw-channel-telegram/src/test/java/.../
      TelegramChannelArchitectureTest.java

ACCEPTANCE CRITERIA:
  □ WebChannelArchitectureTest broadened to block all com.arcclaw.core..*
  □ TelegramChannelArchitectureTest created with same rule
  □ Both rules pass (green) — if they fail, fix the leaking imports first
  □ All 1012 tests still green

═══════════════════════════════════════════════════════════════════
P82-D  FIX TEST QUALITY GAPS  (Medium — 2 hrs)
═══════════════════════════════════════════════════════════════════

GAP 1 — SessionRegistryTest.testRegistryBoundaries is cosmetically weak:
  It creates 1000 sessions and asserts the registry still works.
  It does NOT assert that the Caffeine cache doesn't grow unboundedly.

  ADD this assertion to the existing test:
    // After 10_000 unique keys, cache must not hold all of them
    // (Caffeine's maximumSize=50_000 means this won't evict at 10k,
    //  but we can verify the structure is bounded-type, not raw HashMap)
    assertThat(registry).isInstanceOfSatisfying(SessionRegistry.class, r -> {
        // Access the cache field via reflection or add a package-private
        // estimatedSize() method to SessionRegistry:
        //   long estimatedSize() { return userLocks.estimatedSize(); }
        // Then assert it's a Caffeine cache, not a raw map.
    });

  SIMPLEST FIX: Add a package-private method to SessionRegistry:
    long lockCacheEstimatedSize() { return userLocks.estimatedSize(); }
  Then in the test, after inserting 1000 sessions:
    assertTrue(registry.lockCacheEstimatedSize() <= 1000,
        "Caffeine cache must not exceed insert count");
  And add a comment explaining why 50_000 eviction can't be tested
  deterministically in a unit test.

GAP 2 — ParallelToolCallTest.testSerializationConsistency is tautological:
  It passes paramsJson in and asserts it comes back out. Proves nothing.

  REPLACE with a real test (requires @QuarkusTest or mock CDI ObjectMapper):
    @Test
    void instantParamSerializesAsIso8601() throws Exception {
        // Arrange: create ObjectMapper with JavaTimeModule (same config as CDI)
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Instant now = Instant.parse("2026-04-01T10:15:30Z");
        Map<String, Object> params = Map.of("scheduledAt", now);

        // Act: simulate what ReasoningStepParser does
        String paramsJson = mapper.writeValueAsString(params);
        ParallelToolCall call = ParallelToolCall.pending(
            "call-1", "schedule_task", params, paramsJson);

        // Assert: JSON contains ISO-8601, not epoch millis
        assertThat(call.paramsJson()).contains("2026-04-01T10:15:30Z");
        assertThat(call.paramsJson()).doesNotContain("1743502530");
    }

GAP 3 — PromptTemplateEngineTest missing negative path for real roles:
  The existing test verifies unknown roles throw ISE. Also add:
    @Test
    void allDefinedRolesHaveTemplates() {
        PromptTemplateEngine engine = new PromptTemplateEngine(); // or inject
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            assertDoesNotThrow(() -> engine.templateFor(role),
                "Missing template for role: " + role.name());
        }
    }
  This will catch any newly added AgentRoleEnum value that doesn't
  have a corresponding template — before it hits production.

ACCEPTANCE CRITERIA:
  □ SessionRegistry.lockCacheEstimatedSize() added + test updated
  □ ParallelToolCallTest replaced with real ISO-8601 assertion
  □ PromptTemplateEngineTest.allDefinedRolesHaveTemplates added
  □ All new tests green

═══════════════════════════════════════════════════════════════════
P82-E  STALE APPROVAL IDEMPOTENCY GUARD  (Medium — 1 hr)
═══════════════════════════════════════════════════════════════════

CURRENT STATE:
  TelegramCallbackHandler processes callback_query from Telegram.
  A user can click an approval button multiple times (network retry,
  double-tap). There is no guard preventing the same callback from
  being processed twice, which could cause duplicate state transitions.

REQUIRED CHANGE:
  In ApprovalStateMachine.decide() (created in P82-B):
    Before processing, check if this (sessionId, toolName) decision
    was already recorded:
      if (checkpoint.toolDecisionExists(toolName)) {
          return ApprovalResult.alreadyDecided(sessionId, toolName);
      }
    ApprovalOutcome.ALREADY_DECIDED must be added to the enum.

  In TelegramCallbackHandler (after P82-B refactor):
    On ALREADY_DECIDED: answer the callback query with a silent ack
    (no visible message) — the user sees their original button state.
    Do not throw, do not log at WARN — log at DEBUG only.

  Add to Architecture_Invariants.md §7.35:
    "HITL callback idempotency: duplicate callback_query for the same
     (sessionId, toolName) pair are silently acknowledged.
     ApprovalStateMachine.decide() is the single idempotency gate."

ACCEPTANCE CRITERIA:
  □ ApprovalStateMachine.decide() checks for existing decision
  □ ApprovalOutcome.ALREADY_DECIDED handled in TelegramCallbackHandler
  □ Test: ApprovalStateMachineTest.duplicateApproval_isIdempotent
      - approve tool X → APPROVED
      - approve tool X again → ALREADY_DECIDED (no exception)
  □ Architecture_Invariants.md §7.35 updated

═══════════════════════════════════════════════════════════════════
P82-F  ARCHITECTURE INVARIANTS DOCUMENTATION  (Low — 30 min)
═══════════════════════════════════════════════════════════════════

Add the following entries to Architecture_Invariants.md:

§8.1  Agent Persona Design (new section)
  "All agent personas are produced by AgentPersonaFactory from
   AgentRoleEnum definitions + agent-{roleId}.txt templates.
   No Agent subclass should ever be created directly.
   Adding a new agent role requires: (1) add AgentRoleEnum value,
   (2) add RoleDefinition in AgentRoleEnum, (3) create
   templates/prompts/agent-{roleId}.txt, (4) add @Produces method
   in AgentPersonaFactory. The PromptTemplateEngineTest
   allDefinedRolesHaveTemplates test will catch missing templates."

§9.1  HITL State Machine (update existing)
  "ApprovalStateMachine is the SINGLE entry point for all HITL
   approval decisions. Channel modules (Telegram, Web) must never
   call StatefulAgentStore approval methods directly. They call
   ApprovalStateMachine.decide() and react to ApprovalResult.
   Duplicate callbacks are idempotent (§7.35)."

§2.1  Deployment Constraint (new entry)
  "SQLite journal_mode=WAL requires block storage (local NVMe or
   cloud EBS). NEVER deploy with a shared NFS/EFS mount for
   ARCCLAW_DATA_DIR when WAL is enabled — WAL uses shared memory
   (-shm files) that do not synchronize across network nodes.
   ArcClaw's Option B architecture (SQLite-per-user) is inherently
   single-node per data directory. Multi-region requires Litestream
   replication or Turso, not shared mounts."

═══════════════════════════════════════════════════════════════════
EXECUTION ORDER (do not skip steps)
═══════════════════════════════════════════════════════════════════

1. P82-C first (ArchUnit rule broadening) — run and fix any leaks
   this reveals BEFORE making any other changes. The leaks it finds
   are your hit-list for P82-A and P82-B.

2. P82-B (ApprovalStateMachine) — creates the new core abstraction
   that P82-E depends on.

3. P82-E (idempotency guard) — builds on P82-B, must be done together.

4. P82-A (deity persona collapse) — independent, can run in parallel
   with P82-B+E if two engineers available, otherwise do after.

5. P82-D (test gaps) — fill in test gaps as each item above completes.

6. P82-F (invariants doc) — last, after all code is merged.

═══════════════════════════════════════════════════════════════════
FINAL VERIFICATION
═══════════════════════════════════════════════════════════════════

./mvnw clean verify \
  -pl arcclaw-channel-api,arcclaw-channel-telegram,\
arcclaw-channel-web,arcclaw-core,arcclaw-app \
  -am

Expected:
  Tests run: 1025+ | Failures: 0 | Errors: 0 | Skipped: 1
  (1012 existing + ~13 new tests from P82-A,B,D,E)

After green build, Phase 82 is closed. ArcClaw is production-ready
for public GitHub release as the definitive Java-native agent kernel.
Phase 83 = new features: onboarding backend JSON + Playwright tool.

***

**Why this order matters:** P82-C runs first because the broadened ArchUnit rule will surface any remaining core imports that slipped through the P81 review. Those leaks become the exact fix list for P82-A and P82-B. Running it first means you don't do the refactoring twice.