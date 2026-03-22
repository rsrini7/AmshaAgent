# ArcClaw — Phase 64 Implementation Prompts
> One file, four self-contained prompts. Send each tier separately to the code agent.

---

---

# PROMPT — PHASE 64-A: Production Correctness Fixes (Tier A)

Role: Senior Java Architect, Bug Fixer & Senior Java Developer  
Task: Phase 64-A — Fix 7 confirmed correctness defects in live production code.  
All bugs below exist today. No architectural changes. Surgical fixes only.

---

## Architecture Context
*(paste your full Architecture Context block here)*

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. Every fix must be accompanied by a test that proves the bug existed and now does not.
2. No new dependencies. No structural package changes.
3. All existing 535 tests must remain green after every component.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. All fixes must be safe under JDK 25 (no preview features).
6. Test commands always use `clean` to prevent stale class false-negatives.

---

### Component A1 — GovernanceLayer budget enforcement order + concurrency

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/governance/GovernanceLayer.java`

**Current Problem:**

1. **Wrong check order:** `recordLlmCall` checks the WARNING threshold before the HARD limit. A single massive call that blows past both thresholds fires only `BudgetWarningException`. `BudgetExceededException` is never thrown on that call — the session runs one call over budget with zero hard enforcement. Only the *next* call will throw `BudgetExceededException`.

2. **Concurrency gap:** `synchronized addCost(double cost)` and the `AtomicInteger` token adders (`budget.input.addAndGet(...)`, `budget.output.addAndGet(...)`) are under different monitors. Two concurrent tool results can interleave: thread A reads tokens after addAndGet but before addCost, thread B does the same — threshold check sees a stale total.

**Required Changes:**

1. In `recordLlmCall` (or equivalent cost-recording method), flip the check order: evaluate `hardLimit` FIRST, then `warningThreshold`.
2. Wrap the three operations — token add, cost add, threshold check — under a single `synchronized` block (or `ReentrantLock`) so they are atomic. The `AtomicInteger/AtomicLong` adders for token counters must be replaced with plain `long` fields read/written inside the same lock if they are checked alongside cost in the same critical section.
3. `BudgetExceededException` must be thrown BEFORE `BudgetWarningException` in all code paths. Never the reverse.

**TEST:** `arcclaw-core/src/test/java/com/arcclaw/core/governance/GovernanceLayerTest.java`

- Add: `testHardLimitEnforcedOnSingleMassiveCall()` — single call with tokens that exceed both soft and hard limit; assert `BudgetExceededException` is thrown (NOT `BudgetWarningException`).
- Add: `testConcurrentCostAccumulationIsAtomic()` — submit 10 concurrent `recordLlmCall` calls via `ManagedExecutor` virtual threads, each adding cost just below the threshold; assert final total matches exactly `10 × cost` with no lost updates (use `assertWithinBudget()` or read `getTotalCost()`).
- Update existing `testAtomicWarningSentConcurrency` if it relies on the old check order.

---

### Component A2 — AgentDispatchHandler: AutonomyLevel hardcoded to SUPERVISED

**File:** `arcclaw-app/src/main/java/com/arcclaw/app/pipeline/AgentDispatchHandler.java`  
*(adjust path if handler lives under a different sub-package — e.g. `routing` or `channel`)*

**Current Problem:**

`AgentDispatchHandler` passes `AutonomyLevel.SUPERVISED` as a hardcoded argument to `WorkspaceContext.run(...)`. Sessions created via REST with `AutonomyLevel.FULL` or `AutonomyLevel.READONLY` are silently degraded to `SUPERVISED` the moment any channel message arrives. The session configuration set at creation time is completely ignored at dispatch time.

**Required Changes:**

1. `SessionResolutionHandler` (the handler that runs at priority 40, before dispatch at 70) must load the stored `AutonomyLevel` from the checkpoint entity or session registry and push it into `MessageContext` via a new field or accessor (e.g. `messageContext.setAutonomyLevel(level)`).
2. `AgentDispatchHandler` reads `context.getAutonomyLevel()` (or equivalent `MessageContext` accessor) and passes it to `WorkspaceContext.run(...)`. Remove the hardcoded `AutonomyLevel.SUPERVISED` constant.
3. If no persisted level is found (new session, first message), default to `AutonomyLevel.SUPERVISED` explicitly with a log warning — never silently.
4. Add `autonomyLevel` as a nullable field to `MessageContext` (or a dedicated `SessionContext` record if one exists) with a getter.

**TEST:** `arcclaw-app/src/test/java/com/arcclaw/app/pipeline/AgentDispatchHandlerTest.java`  
*(create if it does not exist)*

- Add: `testFullAutonomySessionIsNotDowngradedToSupervised()` — create a session checkpoint with `AutonomyLevel.FULL`, run a message through the handler pipeline, capture the `AutonomyLevel` passed to `WorkspaceContext.run` (via mock or spy), assert it is `FULL` not `SUPERVISED`.
- Add: `testReadonlySessionIsNotUpgraded()` — same pattern with `READONLY`.
- Add: `testNewSessionDefaultsToSupervised()` — no checkpoint; assert default is `SUPERVISED`.

---

### Component A3 — CompactionSummaryRepository: non-ASCII FTS5 search silently empty

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/state/CompactionSummaryRepository.java`

**Current Problem:**

`searchMemory(...)` strips all non-ASCII characters before running FTS5 MATCH:

```java
String sanitized = query.replaceAll("[^a-zA-Z0-9 ]", "").trim();
if (sanitized.isBlank()) return List.of();
```

Any query in Japanese, Arabic, Hindi, Korean, or any language that uses non-Latin characters produces a blank `sanitized` string and returns an empty list — **silently**, with no error logged. Users querying in their native language have zero long-term memory.

**Required Changes:**

Replace the ASCII-only strip with a targeted FTS5 special-character escape. Only these characters have syntactic meaning in SQLite FTS5 MATCH expressions and need escaping: `"  *  -  ^  (  )`. All other characters, including all Unicode, are valid FTS5 input.

```java
// CORRECT replacement
private static final Pattern FTS5_SPECIAL = Pattern.compile("([\"*^()\\-])");

private String sanitizeForFts5(String query) {
    if (query == null || query.isBlank()) return null;
    String escaped = FTS5_SPECIAL.matcher(query.trim()).replaceAll("\\\\$1");
    return escaped.isBlank() ? null : escaped;
}
```

Return `List.of()` only when the result of `sanitizeForFts5` is `null`. Log a `TRACE` when input is blank; do not log for valid non-ASCII queries.

**TEST:** `arcclaw-core/src/test/java/com/arcclaw/core/state/CompactionSummaryRepositoryTest.java`  
*(update if exists; create if not)*

- Add: `testJapaneseQueryReturnsResults()` — insert a compaction summary containing Japanese text, search with a Japanese substring, assert non-empty result.
- Add: `testArabicQueryReturnsResults()` — same pattern with Arabic text.
- Add: `testFts5SpecialCharactersAreEscaped()` — query containing `"` and `*`, assert no SQLite exception is thrown and result is valid (empty or matching).
- Add: `testBlankQueryReturnsEmpty()` — null and blank inputs still return `List.of()`.

---

### Component A4 — TelegramMarkdownV2Renderer: headings render as escaped hashes

**File:** `arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram/TelegramMarkdownV2Renderer.java`

**Current Problem:**

The `Heading` case in the renderer passes `"## Title"` through `esc()`. The `esc()` method escapes `#` to `\#`. Telegram receives `\#\# Title` and renders literal backslash-hashes — not a heading. Telegram MarkdownV2 has **no heading syntax**. Every structured reply that includes a heading is visually broken for all users.

**Required Changes:**

Telegram MarkdownV2 cannot render headings natively. Map headings to the closest visual equivalent:

- `h1` (level 1): bold + underline → `*__` + `esc(h.text())` + `__*`
- `h2`–`h6`: bold only → `*` + `esc(h.text())` + `*`

```java
case ReplyNode.Heading h -> h.level() == 1
    ? "*__" + esc(h.text()) + "__*"
    : "*" + esc(h.text()) + "*";
```

Do NOT pass the `#` characters through `esc()`. The `#` prefix must be dropped entirely for Telegram output.

**TEST:** `arcclaw-app/src/test/java/com/arcclaw/app/channel/telegram/TelegramMarkdownV2RendererTest.java`

- Fix existing heading test(s) that currently assert escaped-hash output (`\\#\\# ...`) — update to assert bold output.
- Add: `testH1RendersAsBoldUnderline()` — `ReplyNode.Heading("Title", 1)` → assert output is `*__Title__*` (after `esc` of content).
- Add: `testH2RendersAsBold()` — `ReplyNode.Heading("Section", 2)` → assert output is `*Section*`.
- Add: `testH3ThroughH6RendersAsBold()` — levels 3–6 all produce bold only.
- Add: `testHeadingContentIsStillEscaped()` — heading text containing MarkdownV2 special chars (e.g. `_` or `*`) → assert the content portion is properly escaped inside the bold markers.

---

### Component A5 — ReasoningStepParser: JSON parse failure returns FINISH

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/ReasoningStepParser.java`  
*(adjust path if named differently — e.g. `AgentStepParser`, `StepParser`)*

**Current Problem:**

When the LLM returns malformed JSON for tool arguments, the catch block returns a `ReasoningStep` with `action = FINISH`. The agent state machine interprets this as a completed answer. The user receives the raw error string as the agent's response. The existing `llmSchemaErrorCount` retry mechanism in `AgentStateMachineRunner` is never activated — JSON parse failures bypass the retry path entirely.

**Required Changes:**

In the catch block for JSON parse exceptions:

1. Return a `ReasoningStep` with `action = OBSERVE` (or equivalent "stay in loop" action constant), not `FINISH`.
2. Set the `observation` field to a structured error hint that tells the LLM to retry with valid JSON: e.g. `"JSON parse error — respond with valid JSON only: " + e.getMessage()`.
3. Increment or signal `llmSchemaErrorCount` so `AgentStateMachineRunner`'s existing schema-error retry guard activates. Check how `InvalidRequestException` increments this counter and follow the same pattern.
4. Do NOT use `FINISH` or any "done" action in any catch block for parse errors.

```java
catch (Exception e) {
    log.warnf("JSON parse error for session [%s]: %s", sessionId, e.getMessage());
    // signal retry — NOT finish
    return ReasoningStep.schemaError(
        "JSON parse error — respond with valid JSON: " + e.getMessage(),
        inputTokens, outputTokens
    );
    // or equivalent factory method that sets action = OBSERVE and increments schema error count
}
```

**TEST:** `arcclaw-core/src/test/java/com/arcclaw/core/agent/loop/ReasoningStepParserTest.java`  
*(create if it does not exist)*

- Add: `testMalformedJsonProducesObserveNotFinish()` — feed a non-JSON string to the parser, assert returned step has `action != FINISH` and `action == OBSERVE` (or equivalent).
- Add: `testSchemaErrorCountIncrementedOnParseFailure()` — verify the `llmSchemaErrorCount` counter or equivalent retry signal is set so the state machine retries.
- Add: `testValidJsonParsesSuccessfully()` — regression: valid JSON still parses to the correct step.

---

### Component A6 — CompactionHandler: fireAsync result discarded, inFlight lock never released

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/CompactionHandler.java`  
*(or wherever `compactionEvent.fireAsync(...)` is called — may be in `ContextCompactionService` or a dedicated handler)*

**Current Problem:**

`compactionEvent.fireAsync(new CompactionRequest(...))` returns a `CompletableFuture<CompactionRequest>` that is immediately discarded. If the compaction observer throws any exception:

- The exception is silently swallowed — no log, no alert.
- `compactionInFlight` (or equivalent `AtomicBoolean` guard) remains `true` permanently.
- The session is stuck: all future compaction triggers are skipped because `inFlight` is never reset.
- Sessions grow unbounded in memory with no operator-visible signal.

**Required Changes:**

Attach an `exceptionally` handler to the `CompletableFuture` returned by `fireAsync`:

```java
compactionEvent.fireAsync(new CompactionRequest(sessionId, role))
    .exceptionally(ex -> {
        log.errorf(
            "Compaction observer failed for session [%s] — releasing inFlight lock. Cause: %s",
            sessionId, ex.getMessage()
        );
        compactionInFlight.set(false);   // ← critical: release the lock on failure
        return null;
    });
```

The `compactionInFlight.set(false)` inside `exceptionally` is the critical part. Without it, one observer failure permanently disables compaction for the session's lifetime.

Also verify: does the success path already reset `compactionInFlight` after the observer completes? If not, add a `.thenRun(() -> compactionInFlight.set(false))` before the `exceptionally`.

**TEST:** `arcclaw-core/src/test/java/com/arcclaw/core/agent/loop/CompactionHandlerTest.java`  
*(create or update)*

- Add: `testCompactionInFlightReleasedOnObserverFailure()` — mock the compaction event to throw, trigger compaction, assert `compactionInFlight` is `false` after the future completes (use `CompletableFuture.allOf` + short timeout).
- Add: `testCompactionInFlightReleasedOnSuccess()` — mock the observer to succeed, assert `inFlight` is `false` after completion.
- Add: `testSecondCompactionTriggeredAfterFirstFails()` — trigger compaction, observer fails, trigger again, assert second compaction is NOT skipped (i.e. the lock was released).

---

### Component A7 — ChannelType.fromString: silent fallback to TELEGRAM

**File:** `arcclaw-channel-api/src/main/java/com/arcclaw/channel/api/ChannelType.java`

**Current Problem:**

```java
public static ChannelType fromString(String value) {
    if (value == null || value.isBlank()) return TELEGRAM;  // ← wrong
    try { return ChannelType.valueOf(value.toUpperCase()); }
    catch (IllegalArgumentException e) { return TELEGRAM; } // ← wrong
}
```

Null or unknown channel type values silently become `TELEGRAM`. A Discord checkpoint loaded after a code update returns `TELEGRAM` from `fromString("DISCORD")` — cron notifications and approval timeouts are then routed to Telegram. Data corruption is masked as normal behavior.

**Required Changes:**

Change the return type to `Optional<ChannelType>` and propagate the absence to callers:

```java
public static Optional<ChannelType> fromString(String value) {
    if (value == null || value.isBlank()) return Optional.empty();
    try { return Optional.of(ChannelType.valueOf(value.toUpperCase())); }
    catch (IllegalArgumentException e) { return Optional.empty(); }
}
```

Update all call-sites:
- Callers that need a concrete default: `ChannelType.fromString(val).orElse(ChannelType.TELEGRAM)` — add an explicit comment explaining the fallback.
- Callers that must not silently route to Telegram (notification dispatchers, approval handlers): `ChannelType.fromString(val).orElseThrow(() -> new IllegalStateException("Unknown channel type: " + val))`.

Search for all usages of `ChannelType.fromString(` in the codebase and update each.

**TEST:** `arcclaw-channel-api/src/test/java/com/arcclaw/channel/api/ChannelTypeTest.java`  
*(create if it does not exist)*

- Add: `testNullReturnsEmpty()` — `fromString(null)` → `Optional.empty()`.
- Add: `testBlankReturnsEmpty()` — `fromString("")` and `fromString("  ")` → `Optional.empty()`.
- Add: `testUnknownValueReturnsEmpty()` — `fromString("DISCORD")` when DISCORD is not an enum constant → `Optional.empty()` (or the appropriate value once DISCORD is added).
- Add: `testKnownValueReturnsTelegram()` — `fromString("TELEGRAM")` and `fromString("telegram")` → `Optional.of(ChannelType.TELEGRAM)`.
- Add: `testCallerExplicitFallbackPattern()` — demonstrate `fromString("UNKNOWN").orElse(TELEGRAM)` works without throwing.

---

### Testing Requirements (Tier A)

Run each component's test in isolation first, then the full suite:

```powershell
# Per-component (replace test class name)
.\mvnw.cmd clean test -pl arcclaw-core -am "-Dtest=GovernanceLayerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-app -am "-Dtest=AgentDispatchHandlerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-core -am "-Dtest=CompactionSummaryRepositoryTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-app -am "-Dtest=TelegramMarkdownV2RendererTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-core -am "-Dtest=ReasoningStepParserTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-core -am "-Dtest=CompactionHandlerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-channel-api -am "-Dtest=ChannelTypeTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# Full suite — must stay at 535+ green
.\mvnw.cmd clean test
```

**Gate:** Do not proceed to Phase 64-B until all Tier A tests are green and the full suite count has not decreased.

---

## Once all done, update the following md files

**TROUBLESHOOTING.md** — add a "Phase 64-A" section documenting:
- The budget check order reversal and what symptoms it produced (session over-budget by one call).
- The compactionInFlight permanent lock symptom (sessions growing unbounded with no log).
- The FTS5 non-ASCII silent empty — how to identify affected users (check search audit logs for zero-result queries containing non-ASCII).

---

---

# PROMPT — PHASE 64-B: Security Gap Remediation (Tier B)

Role: Senior Java Security Reviewer, Bug Fixer & Senior Java Developer  
Task: Phase 64-B — Close 3 confirmed security gaps. All exist in production today.

---

## Architecture Context
*(paste your full Architecture Context block here)*

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. No new external dependencies.
2. All security fixes must be backward compatible with existing data (no data migration, no schema drop).
3. Nuance #10: No `ALTER TABLE`. New tables require `@Entity` (standard) or raw DDL (virtual tables, indexes, triggers only).
4. Nuance #35: Vault pepper is `char[]`, zeroed after use. Never `String`.
5. Nuance #29: `EntityManager` inside `QuarkusTransaction.requiringNew()` — extract into `@Transactional(TxType.REQUIRES_NEW)` methods.
6. All existing 535 tests must remain green.

---

### Component B1 — AuditEventRepository: TOCTOU race in HMAC chain sequence

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/audit/AuditEventRepository.java`  
**New File:** `arcclaw-core/src/main/java/com/arcclaw/core/audit/SessionAuditSequencer.java`  
*(new @ApplicationScoped bean — sequence coordinator)*

**Current Problem:**

Sequence generation and `prevHash` lookup are not atomic:

```java
List<Long> maxSeqResult = em.createQuery("SELECT COALESCE(MAX(a.sequence), 0) ...").getResultList();
event.sequence = maxSeq + 1;
// ← TOCTOU window: another thread reads same maxSeq here
em.persist(event);
```

Two concurrent tool executions finishing simultaneously for the same session read the same `MAX(sequence)`, both write `maxSeq + 1`, and the HMAC chain permanently forks. The WORM tamper-proof audit guarantee is broken under any real concurrency.

**Required Changes:**

Because SQLite does not support `SELECT ... FOR UPDATE`, serialize concurrent audit writes for the same session at the application layer using a `ConcurrentHashMap<String, ReentrantLock>` keyed by `sessionId`:

**New class `SessionAuditSequencer`:**

```java
@ApplicationScoped
public class SessionAuditSequencer {
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    public ReentrantLock lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, _ -> new ReentrantLock(true));
    }

    public void releaseStaleSession(String sessionId) {
        sessionLocks.remove(sessionId);
    }
}
```

In `AuditEventRepository.persist(AuditEvent event)`:

```java
ReentrantLock lock = sequencer.lockFor(event.sessionId);
lock.lock();
try {
    // existing MAX(sequence) query + HMAC chain + persist — all inside the lock
} finally {
    lock.unlock();
}
```

The lock must wrap the entire: (1) MAX query, (2) prevHash lookup, (3) HMAC computation, (4) sequence assignment, (5) `em.persist`. Nothing outside this boundary.

Call `sequencer.releaseStaleSession(sessionId)` from `SessionReaper` when a session is reaped — otherwise the lock map grows unbounded.

**TEST:** `arcclaw-core/src/test/java/com/arcclaw/core/audit/AuditEventRepositoryTest.java`

- Add: `testConcurrentAuditWritesSameSessionProduceUniqueSequences()` — submit 20 concurrent audit writes for the same session via virtual threads; assert all 20 have unique `sequence` values and that the HMAC chain is unbroken (each event's `prevHash` matches the prior event's `hash`).
- Add: `testDifferentSessionsDoNotBlockEachOther()` — 10 writes on session A and 10 on session B concurrently; assert both complete without deadlock within 2 seconds (use `CountDownLatch` + timeout).

---

### Component B2 — DevSecretSeeder: injectable into production via config flag

**File:** `arcclaw-app/src/main/java/com/arcclaw/app/cli/ChatCommand.java`  
*(remove seeder injection and call from here)*  
**New File:** `arcclaw-app/src/main/java/com/arcclaw/app/security/DevSecretSeederStartup.java`  
*(new observer — dev profile only)*

**Current Problem:**

`ChatCommand` injects `DevSecretSeeder` and calls `devSeeder.seedDevSecrets()` guarded only by a runtime config flag `arcclaw.dev.auto-seed-secrets`. A production deployment that accidentally has this flag set to `true` silently seeds development credentials. This is a runtime config guard on a security boundary — it must be a compile-time exclusion.

**Required Changes:**

1. Remove `@Inject DevSecretSeeder devSeeder` and the `devSeeder.seedDevSecrets()` call from `ChatCommand` entirely. No conditional, no flag check — remove completely.

2. Create a new `@ApplicationScoped` startup observer in `arcclaw-app/src/main/java/com/arcclaw/app/security/DevSecretSeederStartup.java`:

```java
@ApplicationScoped
@IfBuildProfile("dev")           // compile-time excluded from prod/staging/test builds
public class DevSecretSeederStartup {

    @Inject DevSecretSeeder seeder;

    void onStart(@Observes @Priority(150) StartupEvent ev) {
        // Priority 150 fires AFTER DevSecretSeeder's own @Priority(100) observer
        seeder.seedDevSecrets();
    }
}
```

Note: `@IfBuildProfile("dev")` is `io.quarkus.arc.profile.IfBuildProfile`. This annotation causes the bean to be completely absent from the CDI container in non-dev builds — it cannot be accidentally activated by config.

3. Verify that the existing `DevSecretSeeder` itself still observes `StartupEvent` at `@Priority(100)` per Nuance #37. The new `DevSecretSeederStartup` wraps it at 150, not 100.

4. In `application.yaml`, remove the `arcclaw.dev.auto-seed-secrets` config key from all non-dev profile blocks if it appears there. It should only exist under `%dev:`.

**TEST:** `arcclaw-app/src/test/java/com/arcclaw/app/security/DevSecretSeederTest.java`  
*(update existing or create)*

- Add: `testDevSeederNotAccessibleInTestProfile()` — in a `@QuarkusTest` (which uses `test` profile, not `dev`), assert that `DevSecretSeederStartup` is not in the CDI container (use `Arc.container().instance(DevSecretSeederStartup.class).isAvailable()` → `false`).
- Retain: any existing tests for `DevSecretSeeder` seeding logic itself — those remain valid.

---

### Component B3 — SessionResolutionHandler: session ID uses UUID v3 (MD5)

**File:** `arcclaw-app/src/main/java/com/arcclaw/app/pipeline/SessionResolutionHandler.java`  
*(or wherever `UUID.nameUUIDFromBytes(...)` is called for session derivation)*  
**New File:** `arcclaw-app/src/main/java/com/arcclaw/app/session/SessionRegistry.java`  
*(new @ApplicationScoped persistent session registry)*  
**New File:** `arcclaw-app/src/main/java/com/arcclaw/app/session/SessionRegistryEntry.java`  
*(new @Entity for the registry table)*

**Current Problem:**

```java
UUID.nameUUIDFromBytes((chatId + ":" + userId).getBytes(StandardCharsets.UTF_8))
```

`UUID.nameUUIDFromBytes` uses MD5 internally (UUID version 3). MD5 has known collision weaknesses and is inconsistent with the AES-GCM-SIV + Argon2id security posture used everywhere else. Additionally, the current key `(chatId, userId)` has no `channelType` component — all platforms sharing a `chatId` value would collide to the same session (pre-emptive fix for C1).

**Required Changes:**

Replace deterministic MD5-based session ID derivation with a persistent registry backed by `UUID.randomUUID()`.

**New entity `SessionRegistryEntry`:**

```java
@Entity
@Table(name = "session_registry",
       uniqueConstraints = @UniqueConstraint(columnNames = {"channel_type","channel_id","user_id"}))
public class SessionRegistryEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "channel_type", nullable = false) public String channelType;
    @Column(name = "channel_id",   nullable = false) public String channelId;
    @Column(name = "user_id",      nullable = false) public String userId;
    @Column(name = "session_id",   nullable = false, unique = true) public String sessionId;

    @Convert(converter = InstantToTextConverter.class)
    @Column(name = "created_at",   nullable = false) public Instant createdAt;
}
```

**New `@ApplicationScoped SessionRegistry`:**

Uses raw `EntityManager` (Nuance #17). Provides `resolveOrCreate(channelType, channelId, userId)`:

```java
@Transactional(TxType.REQUIRES_NEW)
public String resolveOrCreate(String channelType, String channelId, String userId) {
    // 1. SELECT WHERE channel_type=? AND channel_id=? AND user_id=?
    // 2. If found, return existing sessionId
    // 3. If not found, insert new row with UUID.randomUUID().toString(), return it
}
```

Use `em.createQuery(...)` with `getSingleResult()` wrapped in try-catch `NoResultException` — not `getSingleResultOrNull()` for compatibility.

**In `SessionResolutionHandler`:**

Replace `UUID.nameUUIDFromBytes(...)` with `sessionRegistry.resolveOrCreate(channelType, channelId, userId)`.

Extract `channelType` from `ChannelMessage` (it's available from `ChannelUser.channel()`). Use `channelType.name()` as the registry key string.

**Backward compatibility:** Existing Telegram sessions have IDs derived from MD5. On first message after deployment, the registry will not find the old MD5-derived session ID and will create a new random one. This means existing sessions start fresh. This is acceptable — document it in TROUBLESHOOTING.md. Do NOT attempt to migrate old session IDs.

**TEST:** `arcclaw-app/src/test/java/com/arcclaw/app/session/SessionRegistryTest.java`  
*(create new @QuarkusTest)*

- Add: `testNewSessionCreatesRandomUuid()` — call `resolveOrCreate` for a new triplet; assert returned ID is a valid UUID, is not the MD5-derived value of the inputs.
- Add: `testSameInputReturnsSameSessionId()` — call twice with identical `(channelType, channelId, userId)`; assert both return the same session ID.
- Add: `testDifferentChannelTypeProducesDifferentSession()` — same `channelId` + `userId` but `channelType = TELEGRAM` vs hypothetical `DISCORD`; assert different session IDs (pre-emptive multi-tenancy fix).
- Add: `testConcurrentCreateDoesNotDuplicateSession()` — 5 concurrent calls for the same triplet; assert exactly one row exists in the DB and all 5 return the same session ID.

---

### Testing Requirements (Tier B)

```powershell
.\mvnw.cmd clean test -pl arcclaw-core -am "-Dtest=AuditEventRepositoryTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-app -am "-Dtest=DevSecretSeederTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-app -am "-Dtest=SessionRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# Full suite
.\mvnw.cmd clean test
```

**Gate:** Tier B complete when full suite is green and no new `@IfBuildProfile` annotations are used anywhere except `dev` profile beans.

---

## Once all done, update the following md files

**TROUBLESHOOTING.md** — add a "Phase 64-B" section documenting:
- The audit TOCTOU symptom: HMAC chain verification failure on sessions with concurrent tool calls — how to detect (compare consecutive `sequence` values; duplicates indicate the race fired).
- Session ID migration note: existing Telegram sessions will start fresh after B3 deployment. Users will lose session context on first message post-deploy. Communicate this as a known one-time side effect.
- `@IfBuildProfile` vs config flag: why the former is used for security gates.

---

---

# PROMPT — PHASE 64-C: Architectural Unblocks (Tier C)

Role: Senior Java Architect & Senior Java Developer  
Task: Phase 64-C — 3 targeted architectural changes required before any new channel (Discord, Slack, etc.) can be added safely.

---

## Architecture Context
*(paste your full Architecture Context block here)*

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. These changes must not break the existing Telegram channel. Telegram is the only live channel and must remain 100% functional after each component.
2. No new external dependencies.
3. Nuance #10: No `ALTER TABLE`. New tables via `@Entity` or raw DDL for virtual/index only.
4. Nuance #17: Raw `EntityManager` in all repository classes in `arcclaw-core`.
5. These changes build on top of Tier B (B3 introduces the `SessionRegistryEntry` with `channelType`) — Tier B must be merged first.
6. All existing 535 tests must remain green throughout.

---

### Component C1 — Session key: enforce (channelType, channelId, userId) triplet

**File:** `arcclaw-app/src/main/java/com/arcclaw/app/pipeline/SessionResolutionHandler.java`  
**File:** `arcclaw-app/src/main/java/com/arcclaw/app/session/SessionRegistry.java` *(introduced in B3)*

**Current Problem (after B3):**

B3 introduced `SessionRegistry.resolveOrCreate(channelType, channelId, userId)` with the full triplet. However, `SessionResolutionHandler` must be verified to always pass all three components correctly — especially `channelType`, which must come from `ChannelMessage` (via `ChannelUser.channel().name()`), not from a config default or hardcoded string.

For Telegram, `chatId` is used as both `channelId` and is inherently per-user (DMs). For future group-channel platforms (Discord, Slack), `channelId` is the channel/room ID shared by multiple users — `userId` separates them. The full triplet is what makes the session key unique per-user-per-channel.

**Required Changes:**

1. In `SessionResolutionHandler`, verify the session resolution call is:
   ```java
   String sessionId = sessionRegistry.resolveOrCreate(
       channelMessage.user().channel().name(),  // channelType — from message, never hardcoded
       channelMessage.user().channelId(),        // channelId  — platform channel/chat ID
       channelMessage.user().platformId()        // userId     — platform user ID
   );
   ```
2. If `ChannelUser` does not currently expose a `channelId()` accessor separate from `platformId()`, add it. For Telegram, `channelId` = `chatId` (numeric string). For CLI, `channelId` = `"cli"`. Ensure `ChannelMessage.TelegramMessage` and `ChannelMessage.CliMessage` correctly populate `channelId`.
3. Add a `channelId` field to `ChannelUser` record if it doesn't exist:
   ```java
   public record ChannelUser(ChannelType channel, String platformId, String channelId, String displayName) { ... }
   ```
   Update all construction sites for `ChannelUser`.

**TEST:** Update `arcclaw-channel-api/src/test/java/com/arcclaw/channel/api/ChannelUserTest.java`

- Add: `testChannelUserHasChannelId()` — assert `ChannelUser` constructor and record accessor expose `channelId` distinct from `platformId`.
- Add: `testTelegramChannelUserChannelIdEqualsChatId()` — Telegram message construction sets `channelId` = `chatId`.
- Update existing `ChannelUserTest` cases to include `channelId` field in record construction.

---

### Component C2 — Channel notification dispatcher: replace Telegram-only observers

**Current Problem:**

Both `TelegramCronNotificationObserver` and `ApprovalTimeoutNotifier` are hardcoded to Telegram:

```java
// TelegramCronNotificationObserver
ChannelType type = event.channelType() != null ? event.channelType() : ChannelType.TELEGRAM;
if (type != ChannelType.TELEGRAM) { log.debug("skipped"); return; }
// only calls telegram sender
```

Adding Discord requires copying both classes entirely. The dispatch logic — "find the right sender for this channel type and call it" — is duplicated everywhere.

**New Files:**

`arcclaw-app/src/main/java/com/arcclaw/app/channel/ChannelJobNotifier.java`  
`arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram/TelegramJobNotifier.java`  
`arcclaw-app/src/main/java/com/arcclaw/app/channel/ChannelCronNotificationDispatcher.java`  
`arcclaw-app/src/main/java/com/arcclaw/app/channel/ChannelApprovalTimeoutDispatcher.java`

**Files to DELETE (or hollow out to empty @ApplicationScoped stubs that log a deprecation warning):**  
`arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram/TelegramCronNotificationObserver.java`  
`arcclaw-app/src/main/java/com/arcclaw/app/channel/ApprovalTimeoutNotifier.java`

**Required Changes:**

**Interface `ChannelJobNotifier`:**

```java
public interface ChannelJobNotifier {
    boolean supports(ChannelType channelType);
    void notifyJobCompleted(String recipient, String renderedMessage, String sessionId);
    void notifyApprovalTimeout(String recipient, String sessionId);
}
```

**`TelegramJobNotifier implements ChannelJobNotifier`:**

Extracts all existing Telegram notification logic from `TelegramCronNotificationObserver` and `ApprovalTimeoutNotifier`. Inject `TelegramChannelSender` (or equivalent). `supports(ChannelType t)` → `return t == ChannelType.TELEGRAM`.

**`ChannelCronNotificationDispatcher` (replaces `TelegramCronNotificationObserver`):**

```java
@ApplicationScoped
public class ChannelCronNotificationDispatcher {

    @Inject @Any Instance<ChannelJobNotifier> notifiers;

    void onJobCompleted(@Observes AgentJobCompleted event) {
        if (event.channelType() == null) return;
        notifiers.stream()
            .filter(n -> n.supports(event.channelType()))
            .findFirst()
            .ifPresentOrElse(
                n -> n.notifyJobCompleted(event.recipient(), event.renderedAnswer(), event.sessionId()),
                () -> log.warnf("No ChannelJobNotifier found for type [%s]", event.channelType())
            );
    }
}
```

**`ChannelApprovalTimeoutDispatcher` (replaces `ApprovalTimeoutNotifier`):**

Same pattern: observes `ApprovalTimeout` event (or equivalent), dispatches via `ChannelJobNotifier.notifyApprovalTimeout(...)`.

**Result:** Adding Discord = create `DiscordJobNotifier implements ChannelJobNotifier`. Zero changes to either dispatcher.

**TEST:** `arcclaw-app/src/test/java/com/arcclaw/app/channel/ChannelCronNotificationDispatcherTest.java`

- Add: `testTelegramJobNotifierSelectedForTelegramEvent()` — fire `AgentJobCompleted` with `channelType = TELEGRAM`; assert `TelegramJobNotifier.notifyJobCompleted` is called.
- Add: `testUnknownChannelTypeLogsWarning()` — fire event with a channel type for which no `ChannelJobNotifier` is registered; assert a warning is logged and no exception is thrown.
- Add: `testNullChannelTypeIsIgnored()` — fire event with `channelType = null`; assert no notification is attempted.

Update `arcclaw-app/src/test/java/com/arcclaw/app/channel/telegram/TelegramCronNotificationObserverTest.java` — if the class is removed, remove the test. If hollowed to a stub, update accordingly.

---

### Component C3 — GlobalKnowledgeBase: replace "rag:" prefix hack with dedicated table

**Files:**  
`arcclaw-rag/src/main/java/com/arcclaw/rag/GlobalKnowledgeBase.java`  
`arcclaw-rag/src/main/java/com/arcclaw/rag/RagSchemaInitializer.java`  
**New File:** `arcclaw-core/src/main/java/com/arcclaw/core/state/RagChunkEntity.java`  
*(new @Entity for rag_chunk table)*

**Current Problem:**

RAG chunks are stored in `CompactionSummaryEntity` using a string-prefix hack:

```java
// in GlobalKnowledgeBase
entity.sessionId  = "rag:" + chunkId;   // primary key collision avoidance hack
entity.summary    = text;
entity.messagesCompacted = 0;            // semantically meaningless for RAG
entity.compactedAt = Instant.now();      // semantically meaningless for RAG
```

Issues:
1. Any query that sweeps `compaction_summary` rows will include RAG chunks mixed with real session summaries.
2. `messagesCompacted`, `compactedAt`, `userId` fields are meaningless for a RAG chunk — schema is semantically corrupted.
3. FTS5 search on `compaction_summary` may return RAG chunks as "session memory" — factual contamination.

**Required Changes:**

**New `@Entity RagChunkEntity` in arcclaw-core:**

```java
@Entity
@Table(name = "rag_chunk")
public class RagChunkEntity {
    @Id
    public String chunkId;               // externally assigned, no @GeneratedValue

    @Column(nullable = false, columnDefinition = "TEXT")
    public String text;

    @Column
    public String source;                // origin URL, filename, or description

    @Convert(converter = InstantToTextConverter.class)
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
```

No `@GeneratedValue` — `chunkId` is assigned by `GlobalKnowledgeBase` (UUID or hash). This avoids the `ArcClawSQLiteDialect` identity column issue (Nuance #30) since we're using a natural PK.

**`RagSchemaInitializer` changes:**

Add DDL to create `vec_chunk` virtual table (parallel to existing `vec_global`) if vec_global was chunk-specific. If `vec_global` is the correct FTS5 companion for RAG chunks, verify its schema. Ensure the FTS5 companion table for RAG is named distinctly from the compaction FTS5 table to prevent cross-contamination. Raw DDL is allowed here (Nuance #10 — virtual tables).

**`GlobalKnowledgeBase` changes:**

1. Inject raw `EntityManager` (Nuance #17).
2. Replace all `CompactionSummaryEntity` usage with `RagChunkEntity`.
3. `ingest(String chunkId, String text, String source)`:
   - Create and persist a `RagChunkEntity`.
   - Also insert into the `vec_global` (or `vec_chunk`) virtual table for vector search as before.
4. `search(String query, int topK)`:
   - Query `rag_chunk` table (FTS5 or vector search), not `compaction_summary`.
5. Remove the `"rag:"` prefix from all strings.

**Remove from `CompactionSummaryRepository.searchMemory`:** any logic that accidentally includes `sessionId LIKE 'rag:%'` rows — these will no longer exist after the migration.

**Backward compatibility:** Existing `"rag:"` prefixed rows in `compaction_summary` will be orphaned after deployment. They will not be queried and will not cause errors. Document in TROUBLESHOOTING.md. Do NOT attempt to migrate them — they are test/dev data only.

**TEST:** `arcclaw-rag/src/test/java/com/arcclaw/rag/GlobalKnowledgeBaseTest.java`  
*(update or create)*

- Add: `testIngestedChunkStoredInRagChunkTable()` — call `ingest(...)`, verify `RagChunkEntity` row exists with correct `chunkId`, `text`, `source`.
- Add: `testSearchReturnsRagChunksNotCompactionSummaries()` — insert both a RAG chunk and a compaction summary containing the same keyword; search returns only the RAG chunk.
- Add: `testCompactionSummaryTableNotContaminatedByRagChunks()` — after ingest, query `compaction_summary` directly; assert no rows with `sessionId LIKE 'rag:%'` exist.
- Add: `testRagPrefixHackNoLongerPresent()` — assert no string containing `"rag:"` prefix appears as a session ID anywhere.

---

### Testing Requirements (Tier C)

```powershell
.\mvnw.cmd clean test -pl arcclaw-channel-api -am "-Dtest=ChannelUserTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-app -am "-Dtest=ChannelCronNotificationDispatcherTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-rag -am "-Dtest=GlobalKnowledgeBaseTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# Telegram channel must still work end-to-end — run the full Telegram test suite
.\mvnw.cmd clean test -pl arcclaw-app -am "-Dtest=TelegramAgentHandlerTest,TelegramChannelRouteTest,TelegramChannelSenderTest,TelegramCronNotificationObserverTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# Full suite
.\mvnw.cmd clean test
```

**Gate:** Tier C complete when:
1. Full suite is green.
2. An existing Telegram session can be created, receive a message, and complete a cron notification end-to-end in dev mode.
3. `CompactionSummaryRepository.searchMemory` returns zero rows containing `"rag:"` prefix.

---

## Once all done, update the following md files

**TROUBLESHOOTING.md** — add a "Phase 64-C" section documenting:
- RAG chunk migration note: any `"rag:"`-prefixed rows in `compaction_summary` are orphaned after C3. They are harmless but can be manually cleaned with `DELETE FROM compaction_summary WHERE session_id LIKE 'rag:%'` in SQLite CLI — document this as an optional cleanup step, not a required one.
- Session ID migration note (cross-reference from 64-B): existing Telegram sessions restart. First message post-deploy creates a new session via `SessionRegistry`.
- Channel notification dispatcher: how to add a new channel (Discord example) — create one class implementing `ChannelJobNotifier`, zero other changes needed.

---

---

# PROMPT — PHASE 64-D: Code Quality & Cleanup (Tier D)

Role: Senior Java Developer & Refactoring Expert  
Task: Phase 64-D — 7 code quality improvements and dead code cleanup. No correctness fixes (those are in 64-A), no security changes (64-B), no new architecture (64-C). Pure improvement.

---

## Architecture Context
*(paste your full Architecture Context block here)*

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. No behavior changes — these are internal quality improvements only.
2. Every change must leave all existing tests green and must not require new infrastructure.
3. Nuance #3: All `Pattern` objects must be `static final`. Any new regex introduced here must comply.
4. No new external dependencies.
5. All existing 535 tests must remain green throughout.
6. Each component is independent — they can be done in any order.

---

### Component D1 — ContextCompactionService: Unicode-safe truncation

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/ContextCompactionService.java`  
*(adjust path if compaction lives under a different sub-package)*

**Current Problem:**

```java
if (newSummary.length() > 2000) {
    newSummary = newSummary.substring(0, 1985) + "...[truncated]";
}
```

`String.substring()` operates on UTF-16 code units, not Unicode codepoints. A summary containing emoji (2 code units each) or supplementary CJK characters can be sliced mid-surrogate-pair, producing a malformed string that some downstream consumers (Telegram, LLM APIs) may reject.

**Required Changes:**

```java
private static final int MAX_SUMMARY_CODEPOINTS = 1985;
private static final String TRUNCATION_SUFFIX = "...[truncated]";

private String truncateSafely(String text) {
    if (text == null) return "";
    long cpCount = text.codePoints().count();
    if (cpCount <= MAX_SUMMARY_CODEPOINTS) return text;
    return text.codePoints()
               .limit(MAX_SUMMARY_CODEPOINTS)
               .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
               .append(TRUNCATION_SUFFIX)
               .toString();
}
```

Replace the inline `substring` call with `truncateSafely(newSummary)`.

**TEST:** `arcclaw-core/src/test/java/com/arcclaw/core/agent/loop/ContextCompactionServiceTest.java`

- Add: `testTruncationPreservesSurrogatePairs()` — construct a string of 2000 ASCII chars followed by a 4-byte emoji (surrogate pair); assert `truncateSafely` does not produce a malformed string (verify with `Character.isHighSurrogate` check on last char of truncated result).
- Add: `testShortTextIsNotTruncated()` — text under 1985 codepoints returns unchanged.
- Add: `testTruncationSuffixPresent()` — text over limit ends with `"...[truncated]"`.

---

### Component D2 — GlobalKnowledgeBase: float vector serialization locale safety

**File:** `arcclaw-rag/src/main/java/com/arcclaw/rag/GlobalKnowledgeBase.java`

**Current Problem:**

```java
.setParameter(2, Arrays.toString(vector))
```

`Arrays.toString(float[])` uses the JVM default locale. On JVMs configured with a non-English locale (German, French, Czech), `0.1f` serializes as `"0,1"` (comma decimal separator) instead of `"0.1"` (dot). sqlite-vec's text parser expects dot separators — all vectors are silently corrupted on non-English JVMs.

**Required Changes:**

Add a private static helper that serializes float arrays with `Locale.ROOT`:

```java
private static String toVecLiteral(float[] vector) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < vector.length; i++) {
        if (i > 0) sb.append(',');
        sb.append(String.format(Locale.ROOT, "%f", vector[i]));
    }
    sb.append(']');
    return sb.toString();
}
```

Replace all `Arrays.toString(vector)` call-sites with `toVecLiteral(vector)`.

**TEST:** `arcclaw-rag/src/test/java/com/arcclaw/rag/GlobalKnowledgeBaseTest.java`

- Add: `testVectorSerializationUsesRootLocale()` — temporarily set JVM default locale to `Locale.GERMAN`, serialize `float[] {0.1f, 0.2f}`, assert result contains `"0.1"` not `"0,1"`, restore locale in `@AfterEach`.

---

### Component D3 — SqliteRetryTemplate: consolidate duplicated BUSY retry

**New File:** `arcclaw-core/src/main/java/com/arcclaw/core/persistence/SqliteRetryTemplate.java`  
**Files to update:** `SessionReaper.java`, `WorkspaceManager.java`, `StatefulAgentStoreImpl.java`, `AgentCronRepository.java`  
*(actual paths in arcclaw-core and arcclaw-app — locate by searching for `SqliteErrorStrings.isBusy` or `"database is locked"`)*

**Current Problem:**

SQLITE_BUSY retry logic appears independently in at least four places, each with different backoff values, different exception type checks, and different log messages. They will diverge. Some check `SqliteErrorStrings.isBusy()`, others check the exception message directly.

**Required Changes:**

**New `SqliteRetryTemplate`:**

```java
@ApplicationScoped
public class SqliteRetryTemplate {

    private static final Logger log = Logger.getLogger(SqliteRetryTemplate.class);

    public <T> T execute(String operationName, int maxAttempts, long backoffMs,
                         Callable<T> action) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                if (!SqliteErrorStrings.isBusy(e)) throw e;   // not a BUSY error — rethrow immediately
                lastException = e;
                log.warnf("SQLite BUSY on [%s], attempt %d/%d — retrying in %dms",
                          operationName, attempt, maxAttempts, backoffMs);
                if (attempt < maxAttempts) Thread.sleep(backoffMs);
            }
        }
        throw new RuntimeException("SQLite BUSY after " + maxAttempts + " attempts on [" + operationName + "]",
                                   lastException);
    }

    public void executeVoid(String operationName, int maxAttempts, long backoffMs,
                            ThrowingRunnable action) throws Exception {
        execute(operationName, maxAttempts, backoffMs, () -> { action.run(); return null; });
    }

    @FunctionalInterface
    public interface ThrowingRunnable { void run() throws Exception; }
}
```

Replace all four duplicated retry blocks with `sqliteRetryTemplate.execute(...)` or `executeVoid(...)`. Use the same default values that the majority of existing call-sites use (typically 5 attempts, 50ms backoff — check the actual values in each file).

**TEST:** `arcclaw-core/src/test/java/com/arcclaw/core/persistence/SqliteRetryTemplateTest.java`

- Add: `testSucceedsOnFirstAttempt()` — action succeeds immediately; assert called exactly once.
- Add: `testRetriesOnBusyAndEventuallySucceeds()` — action fails with a BUSY exception twice, then succeeds; assert called 3 times, returns value.
- Add: `testNonBusyExceptionNotRetried()` — action throws a non-BUSY exception; assert rethrown immediately after 1 attempt.
- Add: `testExhaustedRetriesThrowsWrapped()` — action always throws BUSY; assert `RuntimeException` with original cause after `maxAttempts`.

---

### Component D4 — LlmModelFactory: deduplicate provider switch

**New File:** `arcclaw-app/src/main/java/com/arcclaw/app/routing/LlmModelFactory.java`  
**Files to update:**  
`arcclaw-app/src/main/java/com/arcclaw/app/routing/ModelProducer.java`  
`arcclaw-app/src/main/java/com/arcclaw/app/compaction/CompactionModelProducer.java`

**Current Problem:**

Both `ModelProducer` and `CompactionModelProducer` independently implement:

```java
return switch (provider.toLowerCase()) {
    case "groq"   -> OpenAiChatModel.builder()...build();
    case "openai" -> OpenAiChatModel.builder()...build();
    case "ollama" -> OllamaChatModel.builder()...build();
    default -> throw new IllegalStateException("Unknown LLM provider: " + provider);
};
```

Two copies of the same switch. When a new provider (e.g. Anthropic direct, Mistral) is added, both must be updated — and they will diverge.

**Required Changes:**

**New `@ApplicationScoped LlmModelFactory`:**

```java
@ApplicationScoped
public class LlmModelFactory {

    public ChatModel build(LlmConfig config) {
        return switch (config.provider().toLowerCase(Locale.ROOT)) {
            case "groq", "openai" -> OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .maxTokens(config.maxTokens())
                .build();
            case "ollama" -> OllamaChatModel.builder()
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .build();
            default -> throw new IllegalStateException("Unknown LLM provider: " + config.provider());
        };
    }

    public record LlmConfig(String provider, String baseUrl, String apiKey,
                            String modelName, int maxTokens) {}
}
```

Both `ModelProducer` and `CompactionModelProducer` inject `LlmModelFactory` and delegate their `@Produces` methods to it, passing different `LlmConfig` instances (compaction uses a smaller context window / different model name).

**TEST:** `arcclaw-app/src/test/java/com/arcclaw/app/routing/LlmModelFactoryTest.java`

- Add: `testGroqProviderBuildsOpenAiModel()` — `LlmConfig("groq", ...)` → assert `ChatModel` instance is not null.
- Add: `testOllamaProviderBuildsOllamaModel()` — `LlmConfig("ollama", ...)` → assert model constructed.
- Add: `testUnknownProviderThrows()` — `LlmConfig("vertex", ...)` → assert `IllegalStateException`.
- Add: `testProviderNameIsCaseInsensitive()` — `"GROQ"`, `"Groq"`, `"groq"` all build successfully.

---

### Component D5 — TelegramMarkdownV2Renderer.esc(): single-pass rewrite

**File:** `arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram/TelegramMarkdownV2Renderer.java`

**Current Problem:**

```java
return text.replace("_", "\\_")
        .replace("*", "\\*")
        .replace("[", "\\[")
        // ... 15 more sequential replace() calls
```

18 sequential `String.replace()` calls create 18 intermediate `String` objects per text node. For a long reply with 50+ `ReplyNode.Text` segments, this is measurable GC pressure at scale.

**Required Changes:**

Replace all 18 chained calls with a single-pass `StringBuilder` loop:

```java
private static final Set<Character> MARKDOWN_V2_ESCAPE_CHARS = Set.of(
    '_','*','[',']','(',')','~','`','>','#','+','-','=','|','{','}','.','!'
);

private String esc(String text) {
    if (text == null || text.isEmpty()) return "";
    StringBuilder sb = new StringBuilder(text.length() + 16);
    for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (MARKDOWN_V2_ESCAPE_CHARS.contains(c)) sb.append('\\');
        sb.append(c);
    }
    return sb.toString();
}
```

The `Set<Character>` must be `private static final` (Nuance #3 equivalent for constants).

**TEST:** `arcclaw-app/src/test/java/com/arcclaw/app/channel/telegram/TelegramMarkdownV2RendererTest.java`

- Verify all 18 characters in the Telegram MarkdownV2 spec are in `MARKDOWN_V2_ESCAPE_CHARS` (check against official Telegram docs: `_ * [ ] ( ) ~ ` > # + - = | { } . !`).
- Add: `testAllSpecialCharsAreEscaped()` — input string containing all 18 special chars; assert all are prefixed with `\`.
- Add: `testPlainTextUnchanged()` — text with no special chars passes through unchanged.
- Add: `testNullAndEmptyReturnEmpty()` — null → `""`, `""` → `""`.
- Existing tests that check `esc()` output must all still pass.

---

### Component D6 — LangChainReasoningEngine: BudgetWarning absorbed in structured output

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/LangChainReasoningEngine.java`  
*(or wherever `chatForStructuredOutput(...)` is implemented)*

**Current Problem:**

```java
catch (BudgetWarningException w) {
    log.warn("Budget warning for session %s structured call: %s", sessionId, w.getMessage());
    // absorb soft warning and return normally
}
```

`BudgetWarningException` is silently swallowed in `chatForStructuredOutput`. The synthesizer (`DattatreyaAgent`) and any other caller of the structured output path can run the session all the way to the hard limit with zero user-visible warning. The warning only surfaces through the main reasoning loop — the structured output path is blind to budget pressure.

**Required Changes (Nuance #28 compliant):**

Per Nuance #28: `BudgetWarningException` → catch internally, log, **and propagate the signal to the caller** as part of the structured output result. Do NOT re-throw (re-throwing would break the structured retry loop). Instead, add a `warningIssued` flag or return a wrapper result:

Option A — add `warningIssued` to the structured output result type:
```java
// If StructuredOutputResult is a record, add:
record StructuredOutputResult<T>(T value, boolean budgetWarningIssued) {}
```

Option B — log the warning AND re-throw `BudgetWarningException` AFTER extracting the result (caller handles it). Per Nuance #28, the retry loop catches and proceeds — the signal propagates up to `AgentStateMachineRunner` which can then surface it in the next turn.

Use whichever approach is less disruptive to the existing `StructuredRetryLoop` and `DattatreyaAgent` call-sites. Document the choice in the code comment.

**TEST:** `arcclaw-core/src/test/java/com/arcclaw/core/agent/loop/LangChainReasoningEngineTest.java`  
*(create or update)*

- Add: `testBudgetWarningIsNotSilentlySwallowed()` — mock the LLM to throw `BudgetWarningException` during structured output; assert the caller (or result wrapper) receives the warning signal and it does not disappear.

---

### Component D7 — Dead code cleanup

**Files:**  
`arcclaw-core/src/main/java/com/arcclaw/core/tool/ToolCapability.java`  
`arcclaw-core/src/main/java/com/arcclaw/core/agent/AgentResult.java`  
*(locate by searching for `SANDBOX_EXEC`, `RATE_LIMIT`, `System.out.println`)*  
`arcclaw-deployment/src/main/java/com/arcclaw/deployment/AgentFunctionProcessor.java`

**Items to remove:**

**D7a — `ToolCapability.SANDBOX_EXEC`:**

The `SANDBOX_EXEC` enum constant is marked "Phase 48 deferred" and is never used in any live code path. It appears in `toolName()` switch expressions and may appear in tool capability sets. Remove the constant. If any role's `Set<ToolCapability>` contains it, remove from that set too.

Check: if removing `SANDBOX_EXEC` causes any exhaustive switch to fail to compile (the safety net, per Global Requirement #12) — that is a discovery that some switch was not exhaustive. Fix those switches.

**D7b — `AgentResult.FailureCategory.RATE_LIMIT`:**

Search the entire codebase for assignments of `FailureCategory.RATE_LIMIT`. If none exist (the rate-limit path in `IntentClassificationHandler` absorbs the exception and routes to the coordinator without creating a `Failed` result), remove the enum constant. If any code path does assign it, wire it up properly instead of removing.

**D7c — `System.out.println` in `AgentFunctionProcessor`:**

```java
System.out.println("AgentFunctionProcessor CLASS LOADED");
System.out.println("AgentFunctionProcessor: Processing agent functions...");
```

These print to `stdout` during native image compilation — they are build-time processor side effects visible in CI output. Replace with proper JBoss logging:

```java
private static final Logger log = Logger.getLogger(AgentFunctionProcessor.class);
// then:
log.debug("AgentFunctionProcessor: processing agent functions...");
```

Note: build-time processors use JBoss logging via `org.jboss.logging.Logger` — NOT `java.util.logging` or SLF4J.

**TEST:** No new tests needed for D7. Verify existing tests remain green (the dead code removal is pure deletion — no behavior change). Run:

```powershell
.\mvnw.cmd clean test -pl arcclaw-core -am -DskipTests=false
```

Assert: compilation succeeds (no `SANDBOX_EXEC` reference compile errors), all tests pass.

---

### Testing Requirements (Tier D)

```powershell
# Individual components
.\mvnw.cmd clean test -pl arcclaw-core -am "-Dtest=ContextCompactionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-rag -am "-Dtest=GlobalKnowledgeBaseTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-core -am "-Dtest=SqliteRetryTemplateTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-app -am "-Dtest=LlmModelFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-app -am "-Dtest=TelegramMarkdownV2RendererTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd clean test -pl arcclaw-core -am "-Dtest=LangChainReasoningEngineTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# Full suite — must be green and count must not decrease
.\mvnw.cmd clean test
```

---

## Once all done, update the following md files

**TROUBLESHOOTING.md** — add a "Phase 64-D" section documenting:
- `SANDBOX_EXEC` removal: if any third-party skill YAML references `sandboxexec` as a tool capability, it will now be rejected at skill load time with an `IllegalArgumentException`. Expected behavior — update the skill YAML.
- Locale-safe vector serialization: if vectors ingest on a non-English CI runner looked correct before but returned no results, this is the cause. The fix is D2.
- `SqliteRetryTemplate` default values: the consolidated defaults used (attempts, backoff) — document so they can be tuned via config in a future phase.