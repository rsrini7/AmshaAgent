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

The following are FORBIDDEN:

  ❌ StructuredTaskScope / Structured Concurrency (still preview in JDK 25)
  ❌ Flexible Constructor Bodies (preview in JDK 23/24 — treat as FORBIDDEN)
  ❌ Any feature requiring --enable-preview compiler flag
  ❌ value classes / primitive types (preview in JDK 25)

## Global Implementation Requirements (apply to ALL components)

1. Every new or modified class must compile cleanly under JDK 25 standard.
2. Every change must preserve ALL existing passing test states.
3. Use List.copyOf() for all defensive list copies.
4. All new sealed interfaces must implement a discriminant() method returning
   a stable String for SQLite serialization state resumption.
5. Config keys strictly follow arcclaw.channel.telegram.* namespace for all
   Telegram config — never arcclaw.telegram.* shortcut.
6. No magic string tool names — all tool references via ToolCapability.toolName().
7. No new SomeDomainPolicy() inside CDI beans — all policies are @Injectable.
8. All test commands use clean to prevent stale .class false-negatives:
   .\mvnw.cmd clean test -pl <module> -am "-Dtest=..." "-Dsurefire.failIfNoSpecifiedTests=false"
9. NEVER read source files via Files.readString(Path.of("src/main/java/..."))
   in tests. This pattern is CI-path-dependent and fragile. Use behavioral
   tests (@QuarkusTest exercising real execution paths) or reflection on
   compiled classes to verify structural constraints.
10. NEVER use @InjectMocks on @ApplicationScoped CDI beans. This bypasses CDI
    and is the same reflection anti-pattern banned in rule 9. For unit testing
    CDI beans: either use @QuarkusTest with @InjectMock for dependencies, or
    restructure the bean to use constructor injection so Mockito can inject
    through the constructor cleanly.
	
## Phase 55.1 — Mechanical Sweeps

```
Phase 55.1 — JDK modernization mechanical sweep.
No behavioral changes. Compile + test after each section before moving to the next.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GROUND TRUTH
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. JDK policy: only finalized features. StructuredTaskScope and
   Flexible Constructor Bodies are FORBIDDEN. Do not use them.
2. _ unnamed variables in switch cases are already applied manually.
   Scope here: catch blocks where the caught exception variable is
   declared but never referenced in the catch body.
3. getFirst()/getLast() replacements apply to PRODUCTION source only.
   Test files asserting .get(0) are explicit index assertions — leave
   them unchanged.
4. synchronized(history) removal applies ONLY to AgentStateMachineRunner
   and AgentLoop where history is a sequential ReAct loop local variable
   with no concurrent writers. DO NOT touch GovernanceLayer, 
   WorkspaceScopeContext, or VaultKeyCache synchronized blocks — those
   guard genuinely shared mutable state.
5. Pattern.compile inline fix applies ONLY to AgentErrorMessageUtil —
   all other Pattern usages are already static final.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SECTION A — _ unnamed variables in catch blocks
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Scan ALL .java files in arcclaw-core and arcclaw-app for:
  catch (SomeException e) { ... }
where `e` is never referenced inside the catch body
(no `e.getMessage()`, no `throw e`, no `log...e`, no `e.getCause()`).

Replace `e` with `_` in those catch clauses only.
Do NOT change catch blocks that use `e` in any way.
Do NOT change switch cases (already done).

Run: .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am
All tests must pass before proceeding to Section B.

Also replace unused try-with-resources bindings:
  try (var resource = something()) { ... }
where `resource` is never referenced in the body → replace with:
  try (var _ = something()) { ... }
Same JEP 456 scope as unused catch variables.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SECTION B — getFirst() / getLast()
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
In PRODUCTION source files only (src/main/java), replace:

  .get(0)           → .getFirst()
  .get(size() - 1)  → .getLast()

Known targets (verify each before changing):
  arcclaw-core/.../agent/reasoning/LangChainReasoningEngine.java
    aiMessage.toolExecutionRequests().get(0) → .getFirst()
  arcclaw-core/.../agent/AgentStateMachineRunner.java
    currentHistory.get(currentHistory.size() - 1) → currentHistory.getLast()
  arcclaw-core/.../audit/AuditEventRepository.java
    maxSeqResult.get(0) → maxSeqResult.getFirst()
    results.get(0) → results.getFirst()
  arcclaw-core/.../reflex/ApproveReflex.java
    pending.get(0) → pending.getFirst()
  arcclaw-core/.../reflex/DenyReflex.java
    pending.get(0) → pending.getFirst()
  arcclaw-core/.../reflex/StatusReflex.java
    checkpoints.get(0) → checkpoints.getFirst()
  arcclaw-core/.../state/StatefulAgentStoreImpl.java
    results.get(0) → results.getFirst()

SKIP: any .get(0) where the list is a raw SQL query result returning
a scalar (e.g. Long) — verify semantics match "first element" before
replacing. If uncertain, leave unchanged and note it.

Run: .\mvnw.cmd clean test -pl arcclaw-core -am
All tests must pass before proceeding to Section C.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SECTION C — static final Pattern in AgentErrorMessageUtil
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-core/.../util/AgentErrorMessageUtil.java

Three inline Pattern.compile calls are inside instance methods.
Extract each as private static final field:

  private static final Pattern TOKEN_LIMIT_PATTERN =
      Pattern.compile("Limit (\\d+), Used (\\d+), Requested (\\d+)");

  private static final Pattern RETRY_AFTER_PATTERN =
      Pattern.compile("Please try again in ([\\dhms.]+)");

  private static final Pattern DURATION_PATTERN =
      Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?");

Replace the three inline Pattern.compile(...).matcher(x) calls with
TOKEN_LIMIT_PATTERN.matcher(x), RETRY_AFTER_PATTERN.matcher(x),
DURATION_PATTERN.matcher(x) respectively.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SECTION D — Remove synchronized(history) from sequential loop
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Files:
  arcclaw-core/.../agent/AgentStateMachineRunner.java
  arcclaw-core/.../agent/AgentLoop.java (verify: only one block)

`history` is a local ArrayList passed through sequential ReAct loop
method calls on a single virtual thread. There are no concurrent
writers — the synchronized blocks are vestigial guards.

For each `synchronized (history) { <body> }` block:
  Replace with just `<body>` (remove synchronized wrapper only).
  Preserve all logic inside verbatim.

DO NOT touch:
  GovernanceLayer.addCost() / getTotalCost() — concurrent double accumulation
  WorkspaceScopeContext synchronized(store) — per-session store guard
  VaultKeyCache synchronized(this) — cache initialization guard

Run: .\mvnw.cmd clean test -pl arcclaw-core -am
All tests must pass.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
DEFINITION OF DONE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
- All catch (X e) where e is unused now use _
- All .get(0)/.get(size()-1) in production source replaced with
  .getFirst()/.getLast()
- AgentErrorMessageUtil has 3 static final Pattern fields, no inline compiles
- All synchronized(history) blocks removed from AgentStateMachineRunner
  and AgentLoop only
- GovernanceLayer, WorkspaceScopeContext, VaultKeyCache synchronized untouched
- Full test suite passes: .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am
```

---

## Phase 55.2 — Medium Complexity

```
Phase 55.2 — ScopedValue, constructor injection, config namespace.
No behavioral changes. Compile + test after each section.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GROUND TRUTH
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. ScopedValue is finalized in JDK 24 (JEP 487). SAFE to use.
2. StructuredTaskScope is FORBIDDEN — still preview in JDK 25.
3. @InjectMocks on @ApplicationScoped CDI beans is banned (Rule 10).
   Use constructor injection so Mockito can inject through the constructor.
4. Config namespace rule: all Telegram properties must be
   arcclaw.channel.telegram.* — never arcclaw.telegram.* shortcut.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SECTION A — CliAgentObserver: static volatile → ScopedValue
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-app/.../app/CliAgentObserver.java

Current:
  public static volatile boolean enabled = false;

Problem: static volatile is global — leaks across concurrent REST
requests when running in daemon/REST mode.

Replace with:
  public static final ScopedValue<Boolean> ENABLED =
      ScopedValue.newInstance();

In CliAgentObserver methods, replace:
  if (!enabled) return;
With:
  if (!ENABLED.isBound() || !ENABLED.get()) return;

File: arcclaw-app/.../app/AskCommand.java (or wherever enabled is set)
Find where `CliAgentObserver.enabled = true` is set and where it is
reset to false. Replace with ScopedValue.where() binding:

  ScopedValue.where(CliAgentObserver.ENABLED, true).run(() -> {
      // the existing body of the enabled=true...try...finally block
  });

Remove the enabled=false assignment in the finally block — ScopedValue
scope exits automatically.

Run: .\mvnw.cmd clean test -pl arcclaw-app -am

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SECTION B — Telegram config namespace fix
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-app/.../security/TelegramAllowlistGuard.java

Three config keys use wrong namespace — fix:
  arcclaw.telegram.allowed-users      → arcclaw.channel.telegram.allowed-users
  arcclaw.telegram.block-response     → arcclaw.channel.telegram.block-response
  arcclaw.telegram.silent-block       → arcclaw.channel.telegram.silent-block

Also check application.properties / application-*.properties files for
corresponding property definitions and rename them to match.

Verify: grep -r "arcclaw.telegram\." --include="*.java" --include="*.properties"
Result should be empty after fix.

Run: .\mvnw.cmd clean test -pl arcclaw-app -am

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SECTION C — Constructor injection for @InjectMocks CDI beans
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PRE-SCAN REQUIRED — do not make any code changes until confirmed.

Scan ALL test files for @InjectMocks on a class annotated with
@ApplicationScoped, @Singleton, or @RequestScoped in production.

For each violation found, print:
  - Test file name
  - Production class name
  - Production class CDI scope annotation
  - Number of injected fields that would need constructor params

Stop after printing the full list. Wait for confirmation before
making any constructor changes to production beans.

Pattern for restructured bean:
  @ApplicationScoped
  public class MyBean {
      private final DepA depA;
      private final DepB depB;

      MyBean() { /* CDI */ this.depA = null; this.depB = null; }

      MyBean(DepA depA, DepB depB) {   // for testing
          this.depA = depA;
          this.depB = depB;
      }
  }

Run: .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am
All tests must pass.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
DEFINITION OF DONE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
- CliAgentObserver uses ScopedValue<Boolean> ENABLED, no static volatile
- AskCommand uses ScopedValue.where().run() binding, no enabled=false in finally
- grep "arcclaw.telegram\." returns no results in .java or .properties files
- No @InjectMocks on any @ApplicationScoped/@Singleton CDI bean
- Full test suite passes: .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am
```

---

Phase 55.3 — AgentCheckpoint normalization + saveAndFinalize() collapse.
Development mode — schema applied via Hibernate drop-and-create (delete
data folder before starting). No Flyway migration required.
HIGHEST RISK — run full test suite after each section.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GROUND TRUTH — verified from source scan
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. Only ONE save() + markCompleted() pair exists — in AgentLoop.java:
     var cp = agentStore.save(sessionId, canonicalRole, result.steps());
     agentStore.markCompleted(cp.getId());
   This pair becomes saveAndFinalize(). All other save() calls are
   intermediate state saves — leave them unchanged.

2. stateJson has exactly ONE production reader outside the store:
     AgentSessionService.getStatus() (was AgentResource.java) —
     reads cp.getStateJson() and parses to List<ReasoningStep>.
   This migrates to loadSteps() in Section D.

3. StatefulAgentStoreImpl.deserializeSteps() also reads stateJson —
   keep it working during transition (stateJson still populated).
   Mark @Deprecated but do not remove.

4. AgentCheckpointTest has a reflection test checking stateJson field
   is private — update it to also accept the field being @Deprecated,
   or leave it unchanged if it still passes.

5. No Flyway. Schema applied via Hibernate. In application.properties
   (dev profile): quarkus.hibernate-orm.database.generation=drop-and-create
   Delete the data folder before starting the app after this change.

6. SessionStatusResponse must still return steps in insertion order
   (sequenceNum ascending).

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SECTION A — Scan and confirm before any changes
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Confirm the following from source (do not implement yet):

1. AgentLoop.java — save() + markCompleted() pair (line numbers)
2. All other save() call sites — confirm none pair with markCompleted()
3. AgentSessionService.getStatus() — confirm stateJson parse location
4. StatefulAgentStoreImpl.deserializeSteps() — confirm stateJson read
5. Confirm no other stateJson readers outside the above

Print confirmation then STOP. Do not write any code until told to proceed.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SECTION B — ReasoningStepEntity
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Create: arcclaw-core/.../state/ReasoningStepEntity.java

  @Entity
  @Table(name = "reasoning_step")
  public class ReasoningStepEntity {
      @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
      private Long id;

      @Column(name = "checkpoint_id", nullable = false)
      private Long checkpointId;

      @Column(name = "sequence_num", nullable = false)
      private int sequenceNum;

      @Column(columnDefinition = "TEXT")
      private String thoughtJson;

      @Column(length = 128)
      private String action;

      @Column(columnDefinition = "TEXT")
      private String actionInputJson;

      @Column(columnDefinition = "TEXT")
      private String observation;

      @Column(name = "input_tokens")
      private int inputTokens;

      @Column(name = "output_tokens")
      private int outputTokens;
  }

Mark stateJson on AgentCheckpoint with @Deprecated — do NOT remove it.

Run: .\mvnw.cmd clean test -pl arcclaw-core -am

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SECTION C — saveAndFinalize() in StatefulAgentStore
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Add to StatefulAgentStore interface:
  AgentCheckpoint saveAndFinalize(String sessionId, String agentRole,
                                  List<ReasoningStep> steps,
                                  CheckpointStatus finalStatus);

Implement in StatefulAgentStoreImpl:
  @Transactional
  - Call existing save(sessionId, agentRole, steps) logic to upsert checkpoint
  - Persist one ReasoningStepEntity per step with sequenceNum = list index
    (use objectMapper to serialize step.thought() to thoughtJson,
     step.actionInput() to actionInputJson)
  - Set checkpoint.status = finalStatus
  - Set checkpoint.completedAt = Instant.now() if finalStatus == COMPLETED
  - Set checkpoint.failedAt = Instant.now() if finalStatus == FAILED
  - Single transaction — checkpoint + all step rows commit together
  - Still populate stateJson (keep existing serialization) — dual-write
    during transition so deserializeSteps() keeps working

In AgentLoop.java — replace the one paired call:
  // BEFORE:
  var cp = agentStore.save(sessionId, canonicalRole, result.steps());
  agentStore.markCompleted(cp.getId());
  // AFTER:
  agentStore.saveAndFinalize(sessionId, canonicalRole,
      result.steps(), CheckpointStatus.COMPLETED);

Run: .\mvnw.cmd clean test -pl arcclaw-core -am

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SECTION D — loadSteps() + migrate reader
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Add to StatefulAgentStore interface:
  List<ReasoningStep> loadSteps(Long checkpointId);

Implement in StatefulAgentStoreImpl:
  - JPQL: SELECT r FROM ReasoningStepEntity r
          WHERE r.checkpointId = :id ORDER BY r.sequenceNum ASC
  - Reconstruct ReasoningStep from entity fields
    (deserialize thoughtJson, actionInputJson via objectMapper)
  - Return List.copyOf(result)

Update AgentSessionService.getStatus():
  Replace:
    if (cp.getStateJson() != null && !cp.getStateJson().isBlank()) {
        steps = objectMapper.readValue(cp.getStateJson(), ...);
    }
  With:
    steps = agentStore.loadSteps(cp.getId());

Verify: SessionStatusResponse returns steps ordered by sequenceNum.

Do NOT remove stateJson field or @Deprecated deserializeSteps().
That is deferred to Phase 55.4.

Run: .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
DEFINITION OF DONE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
- ReasoningStepEntity class exists, Hibernate creates table on startup
- saveAndFinalize() is @Transactional, used in AgentLoop for the
  one save+markCompleted pair — no other callers changed
- stateJson still populated (dual-write) — @Deprecated but not removed
- AgentSessionService.getStatus() reads from loadSteps(), not stateJson
- SessionStatusResponse returns steps in sequenceNum order
- deserializeSteps() kept as-is (@Deprecated) for Phase 55.4 removal
- Full test suite passes: .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am
- Dev note: delete data folder and restart app to apply schema change
```

---

Phase 56 — Hardcoded String Constant Sweep

SCAN ONLY in Phase 56.1. No code changes until the plan is approved.
Implementation in Phase 56.2 after category-by-category confirmation.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GROUND TRUTH — READ BEFORE SCANNING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. Rule 6 (already in policy): No magic string tool names — all tool
   references must use ToolCapability.toolName(). Any violation of
   this rule is P0 regardless of how many files it appears in.

2. AgentRoleEnum already exists with toScopedName(). Role strings
   ("coordinator", "coder", "debugger", etc.) in production code that
   are not going through AgentRoleEnum.fromString() are violations.

3. Test-only strings (session IDs, user IDs, goal text used as test
   fixtures) are NOT candidates for shared constants — they are
   intentional test data. Do not extract them.

4. Config key strings in @ConfigProperty annotations are NOT candidates
   — Quarkus injects them directly and they are already the canonical
   definition. Only duplicated config key strings in non-annotation
   contexts (e.g. System.getProperty, hardcoded in logic) are candidates.

5. Regex patterns and prompt text are NOT in scope for this phase —
   covered in separate phases.

6. DO NOT change any constant that is part of a public API contract
   (e.g. HTTP header names, JSON field names returned to clients) without
   explicit approval — these are breaking changes.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE 56.1 — SCAN AND CATEGORIZE (no code changes)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Scan all .java production files (src/main/java only) across
arcclaw-core, arcclaw-app, arcclaw-channel-api for string literals
that appear in 2+ locations and fit one of the categories below.

For each finding print: string value, category, files affected,
proposed home (class + field name), and approach.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CATEGORY 1 — Tool name violations (P0 — Rule 6)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Known violations from source scan:
  "github"         — appears in production code outside ToolCapability
  "scheduletask"   — appears in LangChainReasoningEngine, AgentStateMachineRunner
  "manageschedule" — appears in LangChainReasoningEngine, AgentStateMachineRunner
  "memory_search"  — appears in MemorySearchTool and production callers

Approach: replace ALL with ToolCapability.GITHUB.toolName(),
ToolCapability.SCHEDULE_TASK.toolName(), etc.
Verify ToolCapability enum has entries for each before replacing.
If an entry is missing from ToolCapability, add it first.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CATEGORY 2 — Agent role strings in production code
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Before replacing any role string, verify AgentRoleEnum has a matching
entry for that role. If missing, add the enum constant first before
any replacement. Apply same discipline as Category 1.

Known violations — role name literals in production src/main/java
that bypass AgentRoleEnum:
  "coordinator", "coder", "debugger", "researcher", "architect",
  "brahma", "saraswati", "shiva", "brihaspati", "synthesizer"

Approach: replace with AgentRoleEnum.COORDINATOR.toScopedName()
(or .name().toLowerCase() if toScopedName() is not appropriate).
AgentRoleEnum.fromString() already exists — use it at parse boundaries.
Do NOT replace role strings in @DefaultValue annotations or
application.properties — Quarkus needs literal strings there.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CATEGORY 3 — SQLite retry detection strings
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Known violations — same strings scattered across:
  WorkspaceManager, AsyncPersistenceEngine, StatefulAgentStoreImpl:
  "database is locked"
  "sqlite_busy"
  "busy"
  "SQLiteException"
  "snapshot"

Approach: create
  arcclaw-core/.../persistence/SqliteErrorStrings.java
  (package-private final class, no CDI)
  static final String DATABASE_LOCKED = "database is locked";
  static final String SQLITE_BUSY     = "sqlite_busy";
  static final String BUSY            = "busy";
  static final String SQLITE_EXCEPTION = "SQLiteException";
  static final String SNAPSHOT        = "snapshot";

All three retry-detection sites reference SqliteErrorStrings.*

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CATEGORY 4 — Security / crypto algorithm strings
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Known violations:
  "HmacSHA256" — ApiKeyRegistry, AuditEventRepository, AuditVerifier

Approach: create
  arcclaw-core/.../security/CryptoConstants.java
  (package-private final class, no CDI)
  static final String HMAC_ALGORITHM = "HmacSHA256";

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CATEGORY 5 — HTTP / API contract strings
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Known violations:
  "X-ArcClaw-API-Key" — ApiKeyAuthFilter, ApiKeyAuthFilterTest,
                         AgentResourceTest

Approach: create
  arcclaw-app/.../security/ArcClawHttpHeaders.java
  (public final class, no CDI)
  public static final String API_KEY_HEADER = "X-ArcClaw-API-Key";

NOTE: this is a public API contract string — flag it for explicit
confirmation before replacing. Changing the header name is a
breaking change; only extract to constant, do not rename.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CATEGORY 6 — MDC key strings
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Known violations:
  "userId"    — AgentLoop, AgentSessionService, ApiKeyAuthFilter,
                AgentCronScheduler, WorkspaceScopeHolder
  "sessionId" — AgentLoop, AuditResource, AgentOtelContext
  "agentRole" — AgentLoop, AgentCheckpoint, ToolRegistry
  "role"      — MetricsAgentObserver, StatefulAgentStoreImpl

Approach: create
  arcclaw-core/.../observability/MdcKeys.java
  (public final class, no CDI)
  public static final String USER_ID    = "userId";
  public static final String SESSION_ID = "sessionId";
  public static final String AGENT_ROLE = "agentRole";
  public static final String ROLE       = "role";

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CATEGORY 7 — Approval flow sentinel strings
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Known violations:
  "Resuming task after approval" — AgentStateMachineRunner,
    LangChainReasoningEngine, ApproveReflex (3 production files)
  "Human denied the tool execution." — DenyReflex, tests
  "as per our agreement" is embedded inside the COMPLIANCE_OVERRIDE_PATTERN
  regex in ChatHistoryAssembler. Only extract as AgentSentinels.COMPLIANCE_TAG
  if it also appears as a standalone string comparison outside the regex.
  If it only appears inside the regex pattern, leave it in place — extracting
  a substring out of a regex pattern makes the pattern harder to read and
  is non-standard.
  
RESUME_AFTER_APPROVAL = "Resuming task after approval" covers the full-string
usage in AgentStateMachineRunner only.

CheckpointResumeHandler uses goal.toLowerCase().startsWith("resuming task") —
a prefix check, different pattern. Either leave it as-is or add a separate:
  static final String RESUME_GOAL_PREFIX = "resuming task";
Do NOT replace the startsWith() call with RESUME_AFTER_APPROVAL — they are
not interchangeable.  

Approach: add to existing class or create
  arcclaw-core/.../agent/loop/AgentSentinels.java
  package com.arcclaw.core.agent.loop;
  (package-private final class, no CDI)
  static final String RESUME_AFTER_APPROVAL = "Resuming task after approval";
  static final String HUMAN_DENIED          = "Human denied the tool execution.";
  static final String COMPLIANCE_TAG        = "as per our agreement";

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CATEGORY 8 — Config key strings in non-annotation contexts
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Scan for config key strings used outside @ConfigProperty annotations:
  "arcclaw.workspace.root-dir"
  "arcclaw.sandbox.podman-path"
  "arcclaw.audit.hmac-key"
  "arcclaw.vault.pepper"

If any appear in System.getProperty(), string comparisons, or log
messages (not in @ConfigProperty), extract to a constants class.
If they only appear in @ConfigProperty annotations, skip — no action.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE 56.1 OUTPUT FORMAT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
For each category print a table:

Category N — <name>
| String          | Files (prod only) | Proposed constant     | Action |
|-----------------|-------------------|-----------------------|--------|
| "github"        | 3                 | ToolCapability.GITHUB | P0     |
...

Then STOP. Do not implement anything.
Wait for category-by-category approval before Phase 56.2.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE 56.2 — IMPLEMENTATION (after approval)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Execute approved categories in this order:
1. Category 1 (P0 tool names) — highest risk if wrong, fix first
2. Category 2 (role strings)
3. Category 3 (SQLite strings)
4. Category 4 (crypto strings)
5. Category 5 (HTTP headers — explicit confirmation required)
6. Category 6 (MDC keys)
7. Category 7 (approval sentinels)
8. Category 8 (config key strings if any found)

Run after each category:
  .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
DEFINITION OF DONE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
- No magic tool name strings in production code — all via ToolCapability
- No raw role name strings in production code — all via AgentRoleEnum
- SqliteErrorStrings, CryptoConstants, MdcKeys, AgentSentinels classes created
- ArcClawHttpHeaders created with API_KEY_HEADER constant
- All replaced sites compile and tests pass per category
- Full suite passes: .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am
- Test fixture strings (session IDs, goal text) left unchanged


---

Phase 57 — Rate Limit User Notification

Two bugs observed in production:
1. Intent classifier rate limit (llama-3.1-8b-instant) causes silent
   ~4-8s delay — user gets no signal while RetryUtils retries internally
2. AgentStateMachineRunner 429 handler formats message with ⏳ but
   AgentResultFormatter wraps it with ❌ — contradictory output

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GROUND TRUTH — READ BEFORE IMPLEMENTING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. Rate limit retries happen INSIDE RetryUtils within
   IntentClassifierService.classify(). By the time the exception
   reaches IntentClassificationHandler, retries are already exhausted.
   The handler currently catches ALL exceptions silently and defaults
   to "coordinator" — rate limit cause is completely swallowed.

2. AgentStateMachineRunner catches HttpException 429 and returns:
   AgentResult.failed(humanMessage, SYSTEM_ERROR, ...)
   AgentErrorMessageUtil.sanitizeErrorMessage() already formats this
   as "⏳ I'm temporarily throttled. Please try again in X."
   But AgentResultFormatter.format() prefixes ALL Failed results with
   "❌ " — so the user sees "❌ ⏳ I'm temporarily throttled" which
   is contradictory. Fix the formatter, not the sanitizer.

3. TelegramAgentHandler.receiveMessage() submits to a per-chat queue
   and returns immediately — no acknowledgment is sent to the user.
   For any message that takes >2s to process (rate limit retries,
   heavy agent work), the user sees complete silence.

4. context.getSender().sendRaw(context.getUser(), message) is the
   correct way to send a notification from any pipeline handler.
   This is already used in AllowlistHandler and MessagePipeline error
   handling.

5. AgentResult.FailureCategory.RATE_LIMIT already exists but is
   NOT used by AgentStateMachineRunner — it returns SYSTEM_ERROR for
   429. Fix this to use RATE_LIMIT so callers can distinguish it.

6. DO NOT change AgentErrorMessageUtil.sanitizeErrorMessage() —
   it already produces correct human-readable rate limit messages.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FIX 1 — Immediate acknowledgment in TelegramAgentHandler
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-app/.../channel/telegram/TelegramAgentHandler.java

In receiveMessage(), after extracting chatId and validating msg,
send a typing acknowledgment before submitting to the queue:

  // Only for non-command free-text messages that go to the agent
  // (commands like /status, /cron are fast — skip acknowledgment)
  if (msg.getText() != null && !msg.getText().trim().startsWith("/")) {
      ChannelUser user = /* build from msg.getFrom() */;
      sender.sendRaw(user, "⏳");
  }

Then submit the queue task as normal.

The ⏳ alone is sufficient — no text. It signals the bot received
the message and is working. No changes to command handling paths.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FIX 2 — Rate limit notification in IntentClassificationHandler
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-core/.../pipeline/handlers/IntentClassificationHandler.java

Replace the single catch (Exception e) block with two catches:

Before implementing, verify dev.langchain4j.exception.RateLimitException
exists in the current langchain4j dependency (check pom.xml version
and the exception class in the jar or source).
If RateLimitException exists → catch it specifically as shown.
If it does NOT exist → use this instead:
  } catch (dev.langchain4j.exception.HttpException e) {
      if (e.statusCode() == 429 || (e.getMessage() != null
              && e.getMessage().contains("rate limit"))) {
          // rate limit path — notify and default
      } else {
          throw e; // re-throw non-rate-limit HTTP errors
      }
  }

  } catch (dev.langchain4j.exception.RateLimitException e) {
      log.warnf("Intent classifier rate limited for chat %s — "
          + "defaulting to coordinator: %s",
          context.getChatId(), e.getMessage());
      context.getSender().sendRaw(context.getUser(),
          "⏳ Rate limited on classification — defaulting to coordinator.");
      context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
  } catch (Exception e) {
      log.warnf("Intent classification failed, defaulting to coordinator: %s",
          e.getMessage());
      context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
  }

This only fires when RetryUtils has exhausted all attempts. The
user is notified of the fallback and processing continues normally.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FIX 3 — Use RATE_LIMIT category in AgentStateMachineRunner
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-core/.../agent/loop/AgentStateMachineRunner.java

The synchronized(history) wrapper was removed in Phase 55.1.
Find the current 429 handler by searching for `statusCode() == 429`
in AgentStateMachineRunner — the structure may differ from what is
shown above. Change ONLY `AgentResult.FailureCategory.SYSTEM_ERROR`
→ `AgentResult.FailureCategory.RATE_LIMIT`. Preserve everything else
in that block exactly as-is.

Change SYSTEM_ERROR to RATE_LIMIT:
  AgentResult.FailureCategory.RATE_LIMIT

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FIX 4 — Fix AgentResultFormatter contradictory prefix
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-core/.../util/AgentResultFormatter.java

Current switch for Failed:
  case AgentResult.Failed f -> "❌ " + AgentErrorMessageUtil.sanitizeErrorMessage(f.reason())

Replace with:
  case AgentResult.Failed f -> {
      String msg = AgentErrorMessageUtil.sanitizeErrorMessage(f.reason());
      // Rate limit messages already contain ⏳ — don't double-prefix
      yield (f.category() == AgentResult.FailureCategory.RATE_LIMIT
          || msg.startsWith("⏳"))
          ? msg
          : "❌ " + msg;
  }

Same fix applies to AgentDispatchHandler if it formats Failed results
directly — check and apply same pattern.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FIX 5 — AgentSessionService mapFailedResponse (REST path)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-core/.../api/AgentResponseMapper.java

The RATE_LIMIT case currently returns 500 SYSTEM error:
  case RATE_LIMIT -> Response.serverError()
      .entity(new ErrorResponse(f.reason(), "RATE_LIMIT")).build();

Change to 429:
  case RATE_LIMIT -> Response.status(429)
      .entity(new ErrorResponse(f.reason(), "RATE_LIMIT")).build();

This aligns REST response code with the actual error type.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
EXECUTION ORDER
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. Fix 3 (AgentStateMachineRunner RATE_LIMIT category)
2. Fix 4 (AgentResultFormatter prefix fix)
3. Fix 5 (AgentResponseMapper 429 status)
4. Fix 2 (IntentClassificationHandler split catch)
5. Fix 1 (TelegramAgentHandler ⏳ acknowledgment)

Run after each fix:
  .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
DEFINITION OF DONE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
- AgentStateMachineRunner uses RATE_LIMIT (not SYSTEM_ERROR) for 429
- AgentResultFormatter never prefixes ⏳ messages with ❌
- AgentResponseMapper returns HTTP 429 for RATE_LIMIT failures
- IntentClassificationHandler notifies user when rate limit exhausts
  retries, then falls back to coordinator
- TelegramAgentHandler sends ⏳ for non-command messages before
  queuing — commands (/status, /cron etc.) get no acknowledgment
- No changes to AgentErrorMessageUtil.sanitizeErrorMessage()
- AgentResponseMapperTest.testMapResult_failed_rateLimit_returns429()
  updated: assert HTTP 429, not 500
- AgentStateMachineRunnerTest: new test case verifying that an
  HttpException with statusCode 429 produces AgentResult.Failed
  with category == RATE_LIMIT
- AgentResultHandlerTest (if exists): verify Failed result with
  ⏳-prefixed message is NOT further prefixed with ❌
- Full test suite passes:
  .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am
  

---

Phase 58 — SystemMetadataRegistry

Add a central registry exposing app version, build info, and
environment — surfaced in /q/health responses, audit logs,
and a new /info endpoint.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GROUND TRUTH — READ BEFORE IMPLEMENTING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. application.yaml already has:
     quarkus.application.name: arcclaw
     quarkus.application.version: "1.0.0"
   Use @ConfigProperty to read these — do NOT hardcode them.

2. LaunchMode.current() is a static runtime API returning
   DEVELOPMENT, TEST, or NORMAL. Do NOT @Inject LaunchMode —
   call it statically inside a package-private helper method
   (see Step 1 for the exact pattern).

3. quarkus-smallrye-health is already in arcclaw-app/pom.xml.
   Existing health checks: PodmanHealthCheck (@Liveness),
   PodmanImageHealthCheck (@Readiness). Do NOT modify them.
   Add SystemMetadataHealthCheck as a separate @Readiness check.

4. SystemMetadataRegistry lives in arcclaw-core (not arcclaw-app)
   so it can be referenced from audit logging and REST resources
   without cross-module dependency.

5. AgentResource uses @Path("/sessions") and AuditResource uses
   @Path("/audit") — no /api/v1 prefix at class level. Use
   @Path("/info") for SystemInfoResource consistently.

6. Check ApiKeyAuthFilter for the path exclusion list (same place
   /q/health is excluded). Add "/info" to that list
   unconditionally — this endpoint is intentionally public.

7. DO NOT add git commit hash in this phase. Use only what is
   available from Quarkus config and LaunchMode at runtime.

8. AuditEvent.appVersion has no @NotNull — existing tests that
   construct AuditEvent without it compile and run unchanged.
   Only TelemetryAuditServiceTest needs a new assertion verifying
   the field is populated when record() is called.

9. Data folder will be deleted before restart — Hibernate
   drop-and-create handles the new app_version column.
   No Flyway migration needed.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
STEP 1 — SystemMetadataRegistry.java (NEW)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Package: com.arcclaw.core.config
File: arcclaw-core/.../config/SystemMetadataRegistry.java
@ApplicationScoped

Injected fields:
  @ConfigProperty(name = "quarkus.application.name",
      defaultValue = "arcclaw")
  String appName;

  @ConfigProperty(name = "quarkus.application.version",
      defaultValue = "unknown")
  String appVersion;

Computed field (set in @PostConstruct):
  private String startedAt;  // Instant.now().toString()

Methods:

  @PostConstruct
  void init() {
      startedAt = Instant.now().toString();
  }

  public String getAppName()    { return appName; }
  public String getAppVersion() { return appVersion; }
  public String getStartedAt()  { return startedAt; }

  // Package-private for unit testing — receives LaunchMode as arg
  // so tests can call resolveEnvironment(LaunchMode.DEVELOPMENT)
  // without needing to mock the static LaunchMode.current() call.
  String resolveEnvironment(LaunchMode mode) {
      return switch (mode) {
          case DEVELOPMENT -> "dev";
          case TEST        -> "test";
          case NORMAL      -> "prod";
      };
  }

  // Production entry point — calls static LaunchMode.current()
  public String getEnvironment() {
      return resolveEnvironment(LaunchMode.current());
  }

  public SystemInfo toInfo() {
      return new SystemInfo(appName, appVersion,
          getEnvironment(), startedAt);
  }

  public record SystemInfo(
      String name,
      String version,
      String environment,
      String startedAt
  ) {}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
STEP 2 — SystemMetadataHealthCheck.java (NEW)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Package: com.arcclaw.core.health
File: arcclaw-core/.../health/SystemMetadataHealthCheck.java

@Readiness
@ApplicationScoped
implements HealthCheck

Inject: SystemMetadataRegistry registry

  @Override
  public HealthCheckResponse call() {
      return HealthCheckResponse
          .named("System Metadata")
          .up()
          .withData("version",     registry.getAppVersion())
          .withData("environment", registry.getEnvironment())
          .withData("startedAt",   registry.getStartedAt())
          .build();
  }

Always returns UP — informational only, not a failure gate.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
STEP 3 — SystemInfoResource.java (NEW) + ApiKeyAuthFilter
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Package: com.arcclaw.core.api
File: arcclaw-core/.../api/SystemInfoResource.java

@ApplicationScoped
@Path("/info")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread

Inject: SystemMetadataRegistry registry

  @GET
  public Response getInfo() {
      return Response.ok(registry.toInfo()).build();
  }

Response shape:
  {
    "name": "arcclaw",
    "version": "1.0.0",
    "environment": "dev",
    "startedAt": "2026-03-07T13:37:00.000Z"
  }

Also in this step:
  Open ApiKeyAuthFilter and find the path exclusion list
  (where /q/health and similar paths are excluded from auth).
  Add "/info" to that list unconditionally.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
STEP 4 — AuditEvent + TelemetryAuditService (MODIFY)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-core/.../audit/AuditEvent.java
Add field:
  @Column(name = "app_version", length = 32)
  public String appVersion;

File: arcclaw-core/.../audit/TelemetryAuditService.java
Inject: SystemMetadataRegistry registry

In record() where AuditEvent is constructed, add:
  event.appVersion = registry.getAppVersion();

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
EXECUTION ORDER
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. Create SystemMetadataRegistry
2. Create SystemMetadataHealthCheck
3. Create SystemInfoResource + ApiKeyAuthFilter exclusion
4. Modify AuditEvent + TelemetryAuditService

Run after each step:
  .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TESTS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SystemMetadataRegistryTest (NEW — plain Mockito, no CDI):
  Instantiate SystemMetadataRegistry directly (constructor injection
  or field injection via Mockito — no @QuarkusTest needed).

  testResolveEnvironment_development_returnsDev()
    — registry.resolveEnvironment(LaunchMode.DEVELOPMENT) == "dev"

  testResolveEnvironment_test_returnsTest()
    — registry.resolveEnvironment(LaunchMode.TEST) == "test"

  testResolveEnvironment_normal_returnsProd()
    — registry.resolveEnvironment(LaunchMode.NORMAL) == "prod"

  testToInfo_populatesAllFields()
    — set appName/appVersion via @ConfigProperty field or constructor
    — call toInfo() and assert all 4 fields non-null

  testStartedAt_isSetAtStartup_notNull()
    — call init() manually, assert getStartedAt() != null

SystemInfoResourceTest (NEW — @QuarkusTest + RestAssured):
  testGetInfo_returns200WithCorrectShape()
    — GET /info → status 200
    — body.name != null
    — body.version != null
    — body.environment in ["dev", "test", "prod"]
    — body.startedAt != null

TelemetryAuditServiceTest (MODIFY):
  Add assertion in the record() test that event.appVersion
  equals registry.getAppVersion() after record() is called.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
DEFINITION OF DONE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
- GET /info returns 200 with name, version, environment, startedAt
- /q/health readiness response includes "System Metadata" check
  with version, environment, startedAt in withData fields
- AuditEvent has appVersion field, populated by TelemetryAuditService
- No hardcoded version strings — all from quarkus.application.* config
- LaunchMode.current() called via resolveEnvironment() helper —
  no @Inject LaunchMode field anywhere
- /info is in ApiKeyAuthFilter exclusion list — no auth required
- SystemMetadataRegistry has no CDI circular dependency
- All test suite passes:
  .\mvnw.cmd clean test -pl arcclaw-core,arcclaw-app -am
- Delete data folder before restarting app to apply schema change

