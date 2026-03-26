Now I have everything I need. Let me produce the Phase 65 plan.Good. I've read all the code and all four LLM reviews carefully. Here is my independent assessment before writing the prompts:

**What the other LLMs got wrong about the current code:**
- `TelegramMarkdownV2Renderer.heading` — **already fixed** in this version: `h.level()==1 ? "*__"+esc(h.text())+"__*" : "*"+esc(h.text())+"*"`. Grok/my earlier note was reviewing an older version.
- `esc()` serial replace — **already fixed**. Now uses single-pass `StringBuilder` + character loop.
- `ContextCompactionService` Unicode truncation — **already fixed** using `codePointCount`/`offsetByCodePoints`.
- `GlobalKnowledgeBase` still using `"rag:"` hack — **already fixed**. Now correctly uses `RagChunkEntity`.
- `CompactionSummaryRepository` stripping non-ASCII — **already fixed**. Now uses `sanitizeForFts5()` with proper FTS5 escaping.

**What is actually still broken (confirmed from live code + test run):**

Tier A — 5 confirmed correctness bugs live in production now.
Tier B — 3 security/correctness issues that will fail under load.
Tier C — 3 architectural unblocks (2 with SPI prompts).
Tier D — 3 quality/maintainability items.

---

Now the prompts:

---

# PROMPT — PHASE 65-A: Correctness & Runtime Stability (Tier A)

Role: Senior Java Architect, Bug Fixer & Senior Java Developer
Task: Phase 65-A — Fix 5 confirmed correctness defects in live production code.
All bugs below exist today and have been observed in production logs or test runs.
No architectural changes. Surgical fixes only.

---

## Architecture Context
Refer @TROUBLESHOOTING.md §26 (Phase 64 section) for prior audit chain fixes.
Refer @PLAN.md Phase 64-A/B for GovernanceLayer and AuditEventRepository context.
Refer @Architecture_Invariants.md
Refer @arcclaw_rg_cheatsheet.md

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. Every fix must be accompanied by a test that proves the bug existed and now does not.
2. No new dependencies. No structural package changes.
3. All existing 777 tests must remain green after every component.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. All fixes must be safe under JDK 25 (no preview features).
6. Test commands always use `clean` to prevent stale class false-negatives.

---

### Component A1 — AuditHashUtil.sha256hex() Returns Null on Exception

**File**: `arcclaw-core/src/main/java/com/arcclaw/core/audit/AuditHashUtil.java`
**Symptom**: Live production log shows:
```
ERROR: CRITICAL: hmacHash is null for session session-deny. HMAC production failed unexpectedly.
WARN: [SQLITE_CONSTRAINT_NOTNULL] NOT NULL constraint failed: audit_event.prevHash
```
The `prevHash` field is `NOT NULL` in the schema. It is computed by `sha256hex("GENESIS:" + sessionId)`.
`sha256hex()` must be returning `null` on exception — this is the only code path that sets `prevHash`.

**Root cause**: `AuditHashUtil.sha256hex()` has a try-catch that returns `null` on `NoSuchAlgorithmException`.
SHA-256 is a mandatory JCA algorithm — it cannot be absent on any JVM. But null-returning catch blocks
are a reliability hazard regardless.

**Fix**:
In `AuditHashUtil.sha256hex(String input)`:
1. Change the catch block to throw `IllegalStateException("SHA-256 unavailable — JVM is broken", e)`.
   Do NOT return null. SHA-256 absence means the JVM is fundamentally broken; there is no safe fallback.
2. In `AuditEventRepository.record()`: where `prevHash` is set, add a `Objects.requireNonNull(prevHash)`
   guard BEFORE `em.persist(event)` to catch any future null escape early with a clear message.

**Test**: `AuditHashUtilTest.testSha256HexNeverReturnsNull()` — verify that `sha256hex("test")` returns
a 64-character hex string and never null, even under concurrent invocation.

---

### Component A3 — AgentDispatchHandler Erases Exception Type

**File**: `arcclaw-core/src/main/java/com/arcclaw/core/pipeline/handlers/AgentDispatchHandler.java`
**Method**: `handle(MessageContext context)`
**Symptom**: `BudgetExceededException` thrown from `AgentLoop.run()` is caught and re-thrown as a
generic `RuntimeException`. The REST layer's `AgentSessionService` catches `BudgetExceededException`
specifically to return HTTP 429. After `AgentDispatchHandler` wraps it, the typed identity is lost
in the `getCause()` chain and the catch block doesn't fire — the user gets a 500 instead of 429.

**Current broken code** (line ~14686):
```java
} catch (Exception e) {
    log.errorf("[AgentDispatch] Error processing command '%s': %s", goal, e.getMessage());
    log.debug("Full agent dispatch failure stack trace:", e);
    throw new RuntimeException(e.getMessage(), e);  // ← loses BudgetExceededException type
}
```

**Fix**: Rethrow known typed exceptions before the generic catch:
```java
} catch (BudgetExceededException | BudgetWarningException e) {
    throw e;   // propagate typed — caller maps to 429
} catch (Exception e) {
    log.errorf("[AgentDispatch] Error processing command '%s': %s", goal, e.getMessage());
    log.debug("Full agent dispatch failure stack trace:", e);
    context.getSender().sendRaw(context.getUser(),
        "❌ An internal error occurred. Please try again.");
    return HandleResult.STOP;  // stop pipeline gracefully — do not throw
}
```
Note: For the Telegram pipeline path, rethrowing causes an unhandled exception in the Camel route.
Return `HandleResult.STOP` and send an error message instead.

**Test**: `AgentDispatchHandlerTest.testBudgetExceededPropagatesToCaller()` — mock `AgentLoop` to throw
`BudgetExceededException`, call `handler.handle(context)`, verify the exception propagates typed (not
wrapped) so callers can catch it specifically.

---

### Component A4 — CommandDispatchHandler Silently Swallows Failures

**File**: `arcclaw-core/src/main/java/com/arcclaw/core/pipeline/handlers/CommandDispatchHandler.java`
**Method**: `dispatch(MessageContext context, String input)`
**Symptom**: When a reflex (e.g., `ApproveReflex`, `DenyReflex`) throws an unexpected exception,
the catch block logs it but returns silently. The user receives zero response — the bot appears to
hang or ignore the command.

**Current broken code** (line ~14882):
```java
} catch (Exception e) {
    log.errorf("[CommandDispatch] Error processing command '%s': %s", input, e.getMessage());
    log.debug("Full command dispatch failure stack trace:", e);
    return;  // ← user gets nothing
}
```

**Fix**: After logging, send an error response to the user:
```java
} catch (Exception e) {
    log.errorf("[CommandDispatch] Error processing command '%s': %s", input, e.getMessage());
    log.debug("Full command dispatch failure stack trace:", e);
    context.getSender().sendRaw(context.getUser(),
        "⚠️ Command failed due to an internal error. Please try again.");
}
```
Apply the same fix to the `Unknown` branch's catch block.

**Test**: `CommandDispatchHandlerTest.testDispatchExceptionSendsErrorToUser()` — mock `ReflexRegistry`
to throw `RuntimeException`, call `handler.handle(context)`, verify `sender.sendRaw()` was called with
an error message and `HandleResult.STOP` was returned.

---

### Component A5 — ToolObservationSanitizer Truncates Mid-Surrogate-Pair

**File**: `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/ToolObservationSanitizer.java`
**Method**: `sanitize(String toolName, String rawObservation)`
**Symptom**: The 16 KiB hard cap uses `rawObservation.substring(0, MAX_OBSERVATION_BYTES)`.
`String.substring()` operates on `char` units (UTF-16 code units), not Unicode codepoints. A tool
returning text with emoji or CJK characters can be sliced mid-surrogate-pair, producing a
`String` that fails JSON serialization, corrupts the LLM context, or crashes downstream parsers.

**Current broken code**:
```java
if (rawObservation.length() > MAX_OBSERVATION_BYTES) {
    truncated = rawObservation.substring(0, MAX_OBSERVATION_BYTES) + TRUNCATION_MARKER;
}
```

**Fix**: Truncate at a safe codepoint boundary:
```java
private static final int MAX_OBSERVATION_CODEPOINTS = 16 * 1024;

if (rawObservation.codePointCount(0, rawObservation.length()) > MAX_OBSERVATION_CODEPOINTS) {
    int safeOffset = rawObservation.offsetByCodePoints(0, MAX_OBSERVATION_CODEPOINTS);
    LOG.warnf("Tool '%s' observation truncated from %d to %d codepoints",
        toolName, rawObservation.codePointCount(0, rawObservation.length()),
        MAX_OBSERVATION_CODEPOINTS);
    truncated = rawObservation.substring(0, safeOffset) + TRUNCATION_MARKER;
} else {
    truncated = rawObservation;
}
```
Rename the constant from `MAX_OBSERVATION_BYTES` to `MAX_OBSERVATION_CODEPOINTS` for clarity.

**Test**: `ToolObservationSanitizerTest.testTruncationDoesNotSplitSurrogatePair()` — create a string
with emoji (each 2 chars in UTF-16) at position ~16000-16010, truncate, verify the result is a valid
String (no unpaired surrogates) by checking `Character.isLowSurrogate(result.charAt(result.length()-1))`
is false.

---

## Verification

After all 5 fixes:
```bash
./mvnw clean test -pl arcclaw-core,arcclaw-app -am -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -5
```
Expected: 777+ tests, 0 failures, JVM exits cleanly (no 30s Surefire kill).

---

# PROMPT — PHASE 65-B: Security & Concurrency Hardening (Tier B)

Role: Senior Java Architect & Security Engineer
Task: Phase 65-B — Fix 3 security and concurrency defects.
These do not cause daily test failures but will produce data corruption or
security leakage under production load or future code changes.

---

## Architecture Context
Refer @TROUBLESHOOTING.md §25 (Phase 63-U) for DevSecretSeeder production path history.
Refer @PLAN.md Phase 64-B for SessionRegistry and DevSecretSeederStartup context.
Refer @Architecture_Invariants.md
Refer @arcclaw_rg_cheatsheet.md

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. Every fix must be accompanied by a test that proves the bug existed and now does not.
2. No new dependencies. No structural package changes.
3. All existing tests (777+) must remain green.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. All fixes safe under JDK 25.

---

### Component B1 — DevSecretSeeder Injected Into Production CronJobRunner

**File**: `arcclaw-app/src/main/java/com/arcclaw/app/scheduler/CronJobRunner.java`
**Problem**: `CronJobRunner` injects `DevSecretSeeder` and calls `devSeeder.seedDevSecrets()` before
every cron job execution. `DevSecretSeeder` is annotated `@IfBuildProfile("dev")` — but the injection
point in `CronJobRunner` is NOT guarded. On a prod build, CDI resolves `DevSecretSeeder` as present
(because `DevSecretSeeder.java` itself has no profile annotation; only `DevSecretSeederStartup` does).
This means `seedDevSecrets()` is callable in production, guarded only by a runtime config flag
that can be overridden by an environment variable.

The fix established in Phase 64-B was: `DevSecretSeederStartup` (`@IfBuildProfile("dev")`) owns the
seeding lifecycle. No other class should call `devSeeder.seedDevSecrets()` directly.

**Files to change**:
1. `CronJobRunner.java` — remove the `@Inject DevSecretSeeder devSeeder` field and the
   `devSeeder.seedDevSecrets()` call entirely. The `@IfBuildProfile("dev")` `DevSecretSeederStartup`
   seeds at startup once — cron jobs execute after startup and will have secrets already seeded.
2. Verify `AgentSessionService.java` — confirm it also no longer calls `devSeeder.seedDevSecrets()`.
   If it does, remove it there too for the same reason.

**Test**: `CronJobRunnerTest.testDevSecretSeederNotInvocable()` — use reflection to verify that
`CronJobRunner` has no field of type `DevSecretSeeder`. This prevents the injection point from
silently reappearing in future refactors.

---

### Component B2 — DynamicToolCapabilityRegistry ConcurrentModificationException

**File**: Locate via: `arcclaw-mcp/src/main/java/com/arcclaw/mcp/tool/DynamicToolCapabilityRegistry.java`
**Problem**: `McpConnectResource` calls `registry.getAll().stream()...forEach(registry::deregister)`
in a collect-then-deregister pattern. If the underlying collection in `DynamicToolCapabilityRegistry`
is an `ArrayList` or any non-concurrent list, another virtual thread simultaneously calling
`registry.register()` during the `.stream()` traversal produces a `ConcurrentModificationException`
that terminates the MCP connect/disconnect lifecycle.

**Fix**:
1. In `DynamicToolCapabilityRegistry`, change the internal tool collection from any `ArrayList`/`List`
   to `CopyOnWriteArrayList<DynamicToolCapability>`. `CopyOnWriteArrayList` takes a snapshot on
   iteration — concurrent `register()` calls during `stream()` are safe.
2. Alternatively if the registry already uses a `Map`, ensure it uses `ConcurrentHashMap`.
3. The collect-then-deregister pattern in `McpConnectResource` is already defensive and can remain.

**Test**: `DynamicToolCapabilityRegistryTest.testConcurrentRegisterAndDeregisterNoCme()` — run 50
concurrent threads, half calling `register()` and half calling `getAll().stream().count()`, verify
no `ConcurrentModificationException` is thrown.

---

### Component B3 — IntentClassifierService Hardcodes Role Synonyms

**File**: `arcclaw-app/src/main/java/com/arcclaw/app/routing/IntentClassifierService.java`
**Method**: `sanitize(String raw)`
**Problem**: The method hardcodes deity-to-role synonym mapping:
```java
if (lowerRaw.contains("shiva") || lowerRaw.contains(AgentRoleEnum.DEBUGGER.toScopedName()))
    return AgentRoleEnum.DEBUGGER.toScopedName();
if (lowerRaw.contains("brahma") || lowerRaw.contains(AgentRoleEnum.CODER.toScopedName()))
    return AgentRoleEnum.CODER.toScopedName();
```
Adding a new agent role requires modifying `IntentClassifierService.sanitize()`.
`AgentRoleEnum` already contains `displayName` (which includes "Shiva", "Brahma", etc.).
The synonyms are already implicitly encoded there — they just need to be extracted programmatically.

**Fix**: Replace the hardcoded switch in `sanitize()` with dynamic resolution driven by `AgentRoleEnum`:
```java
private String sanitize(String raw) {
    String lowerRaw = raw.toLowerCase();
    for (AgentRoleEnum role : AgentRoleEnum.values()) {
        // Match canonical name (e.g. "debugger")
        if (lowerRaw.contains(role.toScopedName())) return role.toScopedName();
        // Match deity name from displayName — extract first word before " ("
        String displayName = role.definition().displayName();
        String deityName = displayName.contains("(")
            ? displayName.substring(0, displayName.indexOf("(")).trim().toLowerCase()
            : displayName.toLowerCase();
        if (!deityName.isBlank() && lowerRaw.contains(deityName)) return role.toScopedName();
    }
    return AgentRoleEnum.COORDINATOR.toScopedName(); // safe default
}
```
This means: when a new role is added to `AgentRoleEnum` with a proper `displayName`, the classifier
automatically recognizes both its canonical name and its deity alias — no code change in
`IntentClassifierService` required.

**Test**: `IntentClassifierServiceTest.testSanitizeResolvesAllRolesByDeityName()` — for each
`AgentRoleEnum` value, verify that `sanitize(role.definition().displayName())` returns
`role.toScopedName()`. This test will fail if the hardcoded approach is accidentally re-introduced.

---

## Verification

```bash
./mvnw clean test -pl arcclaw-core,arcclaw-app,arcclaw-mcp -am \
  -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -5
```
Expected: 777+ tests, 0 failures.

---

# PROMPT — PHASE 65-C: Architectural Unblocks & SPI Completion (Tier C)

Role: Senior Java Architect & Platform Engineer
Task: Phase 65-C — 3 architectural changes that unblock future extensibility.
C1 is a data-model fix. C2 and C3 are SPI introductions that follow the exact
same CDI pattern already established by ChannelJobNotifier and IntentRoutingProvider.

---

## Architecture Context
Refer @PLAN.md Phase 64-C for ChannelJobNotifier SPI precedent.
Refer @TROUBLESHOOTING.md §43 (Phase 47) for IntentRoutingProvider SPI precedent.
All new SPIs must follow the same CDI @Any Instance<T> discovery pattern already in use.
Refer @Architecture_Invariants.md
Refer @arcclaw_rg_cheatsheet.md

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. No breaking changes to existing bean names, config keys, or REST API contracts.
2. All existing tests (777+) remain green.
3. New SPI interfaces live in `arcclaw-core` (or `arcclaw-channel-api` where appropriate).
4. New implementations live in `arcclaw-app`.
5. Fallback/default implementations use `@DefaultBean` or `@Priority(0)` as appropriate.
6. Each component must include tests that prove the SPI is discoverable by CDI.

---

### Component C1 — CompactionSummaryEntity Public Fields

**File**: `arcclaw-core/src/main/java/com/arcclaw/core/state/CompactionSummaryEntity.java`
**Problem**: All fields are `public`:
```java
public String sessionId;
public String summary;
public Instant compactedAt;
public int messagesCompacted;
public String userId;
public Instant createdAt;
```
JPA requires Hibernate to generate bytecode proxies for lazy-loading and dirty-checking.
Hibernate 7's enhanced proxy model accesses fields through generated accessor bridges. Public fields
bypass the bridge and allow direct field writes that skip dirty-tracking, causing silent update loss
under certain Hibernate cache states. All other entities in the project use private fields.

**Fix**:
1. Change all fields in `CompactionSummaryEntity` to `private`.
2. Generate standard getters and setters for all fields.
3. Update all call sites — primarily `CompactionSummaryRepository`, `ContextCompactionService`,
   and `TwoTierMemoryService` — to use getters/setters instead of direct field access.
4. Confirm `CompactionSummaryRepositoryTest` still passes (it accesses fields in assertions).

**Test**: `CompactionSummaryEntityTest.testFieldsArePrivate()` — use reflection to verify that
all declared fields in `CompactionSummaryEntity` have `private` modifier. This prevents accidental
regression to public fields in future.

---

### Component C2 — AttachCommand CDI-Based Channel Discovery (SPI)

**File**: `arcclaw-app/src/main/java/com/arcclaw/app/cli/AttachCommand.java`
**Problem**: `AttachCommand.run()` has a hardcoded switch:
```java
switch (channel.toLowerCase()) {
    case "telegram" -> { /* start Telegram polling */ }
    case "discord"  -> { System.out.println("not supported yet"); }
    default         -> { System.out.println("unknown channel: " + channel); }
}
```
Adding a new channel (Discord, Slack, WhatsApp) requires modifying `AttachCommand`. This is the last
place in the app where channel names are hardcoded at the CLI entry point.

**SPI Design**:

**Step 1 — Define interface in `arcclaw-core`**:
```java
// arcclaw-core/.../pipeline/ChannelDaemon.java
public interface ChannelDaemon {
    /** Canonical lowercase channel ID, e.g. "telegram", "discord". */
    String channelId();
    /** Starts the channel daemon (blocking until stopped). */
    void start() throws Exception;
    /** Returns true if this channel is properly configured and can start. */
    boolean isConfigured();
}
```

**Step 2 — Implement for Telegram in `arcclaw-app`**:
```java
// arcclaw-app/.../channel/telegram/TelegramChannelDaemon.java
@ApplicationScoped
public class TelegramChannelDaemon implements ChannelDaemon {
    @Inject CamelContext camelContext;
    @Inject TelegramChannelRoute route;
    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token", defaultValue = "")
    String botToken;

    @Override public String channelId() { return "telegram"; }
    @Override public boolean isConfigured() { return botToken != null && !botToken.isBlank()
        && !botToken.startsWith("mock"); }
    @Override public void start() throws Exception {
        // Move existing Telegram start logic from AttachCommand here
        camelContext.addRoutes(route);
        Quarkus.waitForExit();
    }
}
```

**Step 3 — Refactor `AttachCommand.run()`**:
```java
@Inject @Any Instance<ChannelDaemon> daemons;

@Override
public void run() {
    String target = channel.toLowerCase();
    Optional<ChannelDaemon> daemon = daemons.stream()
        .filter(d -> d.channelId().equalsIgnoreCase(target))
        .findFirst();

    if (daemon.isEmpty()) {
        System.out.printf("Unknown channel '%s'. Available: %s%n",
            channel,
            daemons.stream().map(ChannelDaemon::channelId).collect(Collectors.joining(", ")));
        return;
    }
    if (!daemon.get().isConfigured()) {
        System.out.printf("Channel '%s' is not configured. Check required environment variables.%n", channel);
        return;
    }
    try {
        daemon.get().start();
    } catch (Exception e) {
        log.errorf("Channel daemon '%s' failed: %s", channel, e.getMessage());
    }
}
```

**Files to create/modify**:
- NEW: `arcclaw-core/.../pipeline/ChannelDaemon.java` (interface)
- NEW: `arcclaw-app/.../channel/telegram/TelegramChannelDaemon.java`
- MODIFY: `arcclaw-app/.../cli/AttachCommand.java`

**Test**: `AttachCommandTest.testUnknownChannelPrintsAvailableOptions()` — verify that providing
"whatsapp" as channel prints a helpful message containing the available channel IDs.
`ChannelDaemonSpiTest.testTelegramChannelDaemonDiscoveredByCdi()` — verify that CDI discovers
exactly one `ChannelDaemon` with `channelId() == "telegram"`.

---

### Component C3 — ChannelType Enum → Extensible Record (SPI)

**File**: `arcclaw-channel-api/src/main/java/com/arcclaw/channel/api/ChannelType.java`
**Problem**: `ChannelType` is a Java `enum`. Enums are closed sets — a third-party module adding
Discord cannot introduce `ChannelType.DISCORD` without modifying `arcclaw-channel-api`. This is
an Open/Closed Principle violation that blocks the plugin architecture all LLM reviewers flagged.

⚠️ **This is a breaking change in a shared API module. Execute carefully.**

**Migration Design**:

**Step 1 — Convert `ChannelType` to a record with predefined constants**:
```java
// arcclaw-channel-api/.../ChannelType.java
public record ChannelType(String id) {

    // Compact constructor — normalizes id to lowercase
    public ChannelType {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("ChannelType id must not be null or blank");
        id = id.toLowerCase().trim();
    }

    // Predefined constants — exactly replaces old enum values
    public static final ChannelType TELEGRAM   = new ChannelType("telegram");
    public static final ChannelType DISCORD    = new ChannelType("discord");
    public static final ChannelType SLACK      = new ChannelType("slack");
    public static final ChannelType WEBSOCKET  = new ChannelType("websocket");
    public static final ChannelType REST_API   = new ChannelType("rest_api");
    public static final ChannelType CLI        = new ChannelType("cli");

    // Replaces ChannelType.valueOf() and fromString()
    public static Optional<ChannelType> fromString(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        return Optional.of(new ChannelType(value)); // any value is valid now
    }

    // For switch expressions that previously switched on enum — provide id() for matching
    @Override
    public String toString() { return id; }
}
```

**Step 2 — Migrate all switch statements** that previously switched on `ChannelType` enum to
switch on `channelType.id()`:
```java
// BEFORE
return switch (channelType) {
    case TELEGRAM -> "...";
    case CLI -> "...";
};

// AFTER
return switch (channelType.id()) {
    case "telegram" -> "...";
    case "cli" -> "...";
    default -> throw new IllegalArgumentException("Unsupported: " + channelType.id());
};
```

**Step 3 — Migrate all enum comparison** (`==`, `.equals()`, `if (type == TELEGRAM)`) to
`channelType.equals(ChannelType.TELEGRAM)` — records implement value equality by default
on all components, so this works correctly.

**Step 4 — Update `ChannelJobNotifier.supports(ChannelType)`** — no change needed; record equality
means `type.equals(ChannelType.TELEGRAM)` continues to work correctly.

**Step 5 — Verify `@RegisterForReflection`** in reflection config includes `ChannelType.class`
for native image builds.

**Files affected** (search for `ChannelType` across all modules):
`ChannelType.java`, `TelegramJobNotifier.java`, `TelegramChannelSender.java`,
`TelegramMarkdownV2Renderer.java`, `ChannelCronNotificationDispatcher.java`,
`ChannelApprovalTimeoutDispatcher.java`, `AgentSessionService.java`, `CronJobRunner.java`,
`AgentDispatchHandler.java`, `SessionResolutionHandler.java`, and any switch on `ChannelType`.

**Test**:
`ChannelTypeTest.testCustomChannelTypeCreatable()` — verify `new ChannelType("whatsapp")` is valid
and `ChannelType.fromString("whatsapp").get().equals(new ChannelType("whatsapp"))` is true.
`ChannelTypeTest.testPredefinedConstantsMatchById()` — verify all 6 predefined constants have the
expected `id()` value.
`ChannelTypeTest.testValueEqualityOnRecordComponents()` — verify `new ChannelType("telegram")
.equals(ChannelType.TELEGRAM)` is true.

---

## Verification

```bash
./mvnw clean test -pl arcclaw-channel-api,arcclaw-core,arcclaw-app -am \
  -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -5
```
Expected: 777+ tests, 0 failures.
Note: C3 will produce compilation errors in all modules that switch on `ChannelType` enum —
fix each one by migrating to `switch (channelType.id())` as shown above.

---

# PROMPT — PHASE 65-D: Quality, Maintainability & SPI Completion (Tier D)

Role: Senior Java Architect & Platform Engineer
Task: Phase 65-D — 3 quality improvements that complete the SPI architecture
and reduce long-term maintenance burden.

---

## Architecture Context
Refer @PLAN.md Phase 64-C for ContextCompactionService history and CompactionRequest event design.
Refer @PLAN.md Phase 65-C for EmbeddingModelProducer context (C3 established LlmModelFactory pattern).
Refer @Architecture_Invariants.md
Refer @arcclaw_rg_cheatsheet.md

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. No breaking changes to existing config keys or REST API contracts.
2. All existing tests (777+) remain green.
3. New SPI interfaces follow the `@Any Instance<T>` CDI pattern established by
   `IntentRoutingProvider` and `ChannelJobNotifier`.
4. Each component includes tests proving CDI discoverability.

---

### Component D1 — ContextCompactionService SRP Split

**File**: `arcclaw-core/src/main/java/com/arcclaw/core/state/ContextCompactionService.java`
**Problem**: This class serves two completely different responsibilities:
1. **Event listener**: `onCompactionRequested(@ObservesAsync CompactionRequest)` — CDI event observer
2. **Business logic**: `compact(String sessionId, String userId)` — the actual compaction algorithm

This is a Single Responsibility Principle violation. Mixing the async CDI observer with the
stateful compaction algorithm makes unit testing harder (you cannot test the algorithm without
triggering the CDI event infrastructure) and couples the two concerns so neither can evolve
independently.

**Fix — Split into two classes**:

**Class 1 — `CompactionEventBridge.java`** (new, in same package):
```java
@ApplicationScoped
public class CompactionEventBridge {

    @Inject CompactionService compactionService;
    @Inject CompactionInFlightFlag compactionInFlight;
    private static final Logger log = Logger.getLogger(CompactionEventBridge.class);

    public void onCompactionRequested(@ObservesAsync CompactionRequest request) {
        try {
            compactionService.compact(request.sessionId(), request.userId());
        } catch (Exception e) {
            log.errorf("Compaction failed for session %s: %s", request.sessionId(), e.getMessage());
            log.debug("Full compaction failure stack trace:", e);
        } finally {
            compactionInFlight.set(false);
        }
    }
}
```

**Class 2 — `CompactionService.java`** (rename `ContextCompactionService` → `CompactionService`):
- Retain ALL business logic: `shouldCompact()`, `compact()`, `truncateSafely()`, prompt template.
- Remove the `@ObservesAsync` method entirely.
- Remove the `CompactionInFlightFlag` injection (that belongs in the bridge).
- Keep `@ApplicationScoped`.

**Migration**:
- Delete `ContextCompactionService.java`.
- Create `CompactionService.java` and `CompactionEventBridge.java`.
- Update `CompactionHandler.java` which injects `ContextCompactionService` → inject `CompactionService`.
- Update all tests that reference `ContextCompactionService`.

**Test**: `CompactionServiceTest` — verify `compact()` can be called directly without needing CDI
event infrastructure. `CompactionEventBridgeTest` — verify `onCompactionRequested` delegates to
`CompactionService.compact()` and always releases `compactionInFlight` in finally.

---

### Component D2 — EmbeddingModelProducer SPI (Open/Closed)

**File**: `arcclaw-rag/src/main/java/com/arcclaw/rag/EmbeddingModelProducer.java`
**Problem**: Adding a new embedding provider (Cohere, Voyage AI, Mistral Embed) requires modifying
`EmbeddingModelProducer.java`'s switch statement — same OCP violation that `LlmModelFactory` was
introduced to fix for chat models. This was not addressed in Phase 64.

**SPI Design** — follows exact same pattern as `LlmModelFactory`:

**Step 1 — Define interface in `arcclaw-rag`**:
```java
// arcclaw-rag/.../EmbeddingProviderFactory.java
public interface EmbeddingProviderFactory {
    /** Returns the provider name this factory handles, e.g. "openai", "jina", "ollama". */
    String providerId();
    /** Builds the EmbeddingModel for this provider using the given config. */
    EmbeddingModel create(EmbeddingModelConfig config);
}

// Config record — all providers get the same config bundle
public record EmbeddingModelConfig(
    String baseUrl,
    String apiKey,
    String modelName,
    int dimensions
) {}
```

**Step 2 — Create per-provider implementations**:
```java
// OpenAiEmbeddingProviderFactory.java
@ApplicationScoped
public class OpenAiEmbeddingProviderFactory implements EmbeddingProviderFactory {
    @Override public String providerId() { return "openai"; }
    @Override public EmbeddingModel create(EmbeddingModelConfig cfg) {
        return OpenAiEmbeddingModel.builder()
            .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
            .modelName(cfg.modelName()).build();
    }
}
// JinaEmbeddingProviderFactory.java — same pattern
// OllamaEmbeddingProviderFactory.java — same pattern
```

**Step 3 — Refactor `EmbeddingModelProducer`**:
```java
@ApplicationScoped
public class EmbeddingModelProducer {
    @Inject @Any Instance<EmbeddingProviderFactory> factories;
    @ConfigProperty(name = "arcclaw.rag.embedding.provider") String provider;
    // ... config fields

    @Produces @ApplicationScoped
    public EmbeddingModel produceEmbeddingModel() {
        return factories.stream()
            .filter(f -> f.providerId().equalsIgnoreCase(provider))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No EmbeddingProviderFactory for provider: " + provider +
                ". Available: " + factories.stream()
                    .map(EmbeddingProviderFactory::providerId)
                    .collect(Collectors.joining(", "))))
            .create(new EmbeddingModelConfig(baseUrl, apiKey, modelName, dimensions));
    }
}
```

**Test**: `EmbeddingModelProducerTest.testAllExistingProvidersDiscoverable()` — verify that CDI
discovers factory beans for all three existing providers ("openai", "jina", "ollama") and that
each can construct an `EmbeddingModel` instance with mock config.

---

### Component D3 — AuditEventRepository SHA-256 Hardening (Follow-on to A1)

**File**: `arcclaw-core/src/main/java/com/arcclaw/core/audit/AuditEventRepository.java`
**Context**: A1 fixed `sha256hex()` to throw instead of returning null. This component hardens the
audit chain further by eliminating the last null-escape path at the persist boundary.

**Problem**: Even with A1 fixed, `AuditEventRepository.persistSynchronously()` calls HMAC operations
in sequence without any null-guard contract between steps. If a future code change re-introduces
a null path (e.g., a new `hashUtil` implementation), the NOT NULL constraint error returns.

**Fix — Defensive pre-persist validation**:
Add a dedicated validation method called just before `em.persist()`:
```java
private void validateAuditEventBeforePersist(AuditEvent event) {
    if (event.sessionId == null || event.sessionId.isBlank())
        throw new IllegalStateException("AuditEvent.sessionId must not be null/blank");
    if (event.prevHash == null || event.prevHash.isBlank())
        throw new IllegalStateException("AuditEvent.prevHash must not be null — genesis hash failed");
    if (event.hmacHash == null || event.hmacHash.isBlank())
        throw new IllegalStateException("AuditEvent.hmacHash must not be null — HMAC failed");
    if (event.sequence < 0)
        throw new IllegalStateException("AuditEvent.sequence must be >= 0");
}
```
Call `validateAuditEventBeforePersist(event)` immediately before `em.persist(event)`.

Also: remove the null-fallback `hmacHash` assignment introduced as a workaround:
```java
// REMOVE this block — it papers over a real bug rather than failing fast:
if (event.hmacHash == null) {
    Logger.getLogger(...).errorf("CRITICAL: hmacHash is null...");
    event.hmacHash = "error-null-fallback-" + UUID.randomUUID();
}
```
With A1 fixing `sha256hex()` to throw, and this validation catching any regression,
the null-fallback string is no longer needed and misleads future readers.

**Test**: `AuditEventRepositoryTest.testPersistWithNullPrevHashThrows()` — construct an `AuditEvent`
with `prevHash = null`, call `validateAuditEventBeforePersist()`, verify `IllegalStateException` is
thrown with a message containing "prevHash". This test proves the guard is in place.

---

## Final Verification — Full Phase 65 Suite

After all 4 tiers (A+B+C+D) are complete:
```bash
./mvnw clean test -pl arcclaw-channel-api,arcclaw-core,arcclaw-mcp,arcclaw-rag,arcclaw-app -am \
  -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -10
```
Expected: 800+ tests (Phase 65 adds ~30 new tests), 0 failures, JVM exits cleanly.

Update PLAN.md, TASKS.md, TROUBLESHOOTING.md, and WALKTHROUGH.md to mark Phase 65 complete.

---

**My prioritization rationale as final decider:**

Tier A must ship first — A1 and A2 are causing live failures right now (null prevHash crash, 30s test hang). A3-A5 will cause failures under load.

Tier B second — B3 (sanitize driven by RoleDefinition) is the minimal prerequisite for adding new agent roles without touching the classifier. B1 removes a security landmine. B2 prevents a CME under concurrent MCP load.

Tier C third — C3 (`ChannelType` record) is the architectural heart all four LLMs agreed on. Once done, adding Discord is truly one file. C2 (ChannelDaemon SPI) connects the CLI to the SPI model C3 establishes. C1 is a prerequisite data-model fix.

Tier D last — D1 (compaction split) and D2 (embedding SPI) complete the open/closed story. D3 is defensive hardening following A1.

**What I deliberately excluded from Phase 65:**
- `ChannelPlugin` mega-SPI (Grok's suggestion) — premature. The CDI-based `ChannelDaemon` + `ChannelJobNotifier` + `ChannelSender` already provides the same seam without the complexity of a module system.
- `ServiceLoader` plugin system (ChatGPT's suggestion) — not needed yet. CDI `@Any Instance<T>` is already ServiceLoader-equivalent within Quarkus.
- Graph execution model — Phase 67+ territory.
- `arcclaw-channel-telegram` separate module — too disruptive before the ChannelType record is stable.