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