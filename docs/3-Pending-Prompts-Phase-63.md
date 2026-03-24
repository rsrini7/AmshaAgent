# PROMPT — PHASE 65-PREP: Pre-SPI Hardening (All Remaining Items)

Role: Senior Java Architect, Bug Fixer & Senior Java Developer
Task: Phase 65-Prep — Close all remaining Phase 64 partials, quality sweeps,
and reviewer findings before the ChannelPlugin SPI phase begins.
All items are confirmed defects, partial implementations, or missed improvements.
Surgical fixes only unless stated. No D8 (SPI). No D9 (MCP SDK).
SANDBOX_EXEC and RATE_LIMIT are used elsewhere — do NOT remove them.

---

## Architecture Context and Other References
Refer @Architecture_Nuances.md
Refer @arcclaw_rg_cheatsheet.md

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. Every fix must be accompanied by a test that proves the bug existed and now does not.
2. No new dependencies. No structural package changes.
3. All existing 720+ tests must remain green after every component.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. All fixes must be safe under JDK 25 (no preview features).
6. Test commands always use `clean` to prevent stale class false-negatives.
7. Components marked [NO NEW TEST NEEDED] must update an existing test instead.
8. Do NOT remove `ToolCapability.SANDBOX_EXEC` or
   `AgentResult.FailureCategory.RATE_LIMIT` — both are intentionally retained.

### Execution Gate

Complete components in this exact order. Do not start the next component until
the current one has passing tests:

  Group 1 — Correctness completions   : A2, A5
  Group 2 — Security last mile        : B2
  Group 3 — Structural fixes          : R4, R1
  Group 4 — Quick defect fixes        : R2, R3
  Group 5 — Performance & dead code   : R5, D7
  Group 6 — Quality sweep             : D1, D2, D5, D6
  Group 7 — OCP pre-SPI prep          : R6, R7, R8

---

## Component A2 — AutonomyLevel flowing from REST session through Telegram dispatch

**Files:**
- `arcclaw-core/src/main/java/com/arcclaw/core/pipeline/handlers/AgentDispatchHandler.java`
- `arcclaw-core/src/main/java/com/arcclaw/core/pipeline/handlers/SessionResolutionHandler.java`
- `arcclaw-core/src/main/java/com/arcclaw/core/pipeline/MessageContext.java`

**Current Problem:**
`AgentDispatchHandler` calls `WorkspaceContext.run(...)` with a hardcoded
`AutonomyLevel.SUPERVISED`, regardless of what was stored when the session was
created via REST. A session created as `FULL` via `POST /sessions` degrades
silently to `SUPERVISED` the moment a Telegram message arrives, because
`SessionResolutionHandler` never pushes the stored autonomy into `MessageContext`
and `AgentDispatchHandler` never reads it back.

**Required Changes:**

1. In `SessionResolutionHandler` (priority 40): after resolving the session ID,
   look up the stored autonomy level using
   `heartbeatTracker.getAutonomyLevel(sessionId)`. If present, call
   `context.setAutonomyLevel(level)`. If absent, default to `SUPERVISED` and log
   a WARN.
2. In `AgentDispatchHandler` (priority 70): replace the hardcoded
   `AutonomyLevel.SUPERVISED` with `context.getAutonomyLevel()`. If
   `context.getAutonomyLevel()` is null (pre-P65 sessions), fall back to
   `SUPERVISED` explicitly — never silently.
3. `MessageContext` must already have `getAutonomyLevel()` / `setAutonomyLevel()`
   from P64. If missing, add them now.

**TEST:**
`arcclaw-app/src/test/java/com/arcclaw/app/channel/telegram/
    AgentDispatchHandlerAutonomyTest.java`  (new file)

```
@QuarkusTest
class AgentDispatchHandlerAutonomyTest {

    // Test 1: Create a FULL session via REST, simulate a Telegram pipeline run,
    // assert WorkspaceContext.autonomy == FULL inside the agent loop invocation.
    // Use Mockito @InjectMock on AgentLoop to capture the AgentExecutionContext
    // passed to stateMachineRunner.run(...) and assert .autonomyLevel() == FULL.
    @Test
    void telegramPipelinePreservesFULLAutonomyFromRestSession() { ... }

    // Test 2: No session exists (new Telegram DM) → defaults to SUPERVISED,
    // no NPE, no IllegalStateException.
    @Test
    void telegramPipelineDefaultsToSupervisedForUnknownSession() { ... }
}
```

**Test command:**
```
mvn clean test -pl arcclaw-app -Dtest=AgentDispatchHandlerAutonomyTest
```

---

## Component A5 — ReasoningStepParser JSON parse error returns FINISH

**File:**
`arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/
    ReasoningStepParser.java`

**Current Problem:**
When the LLM returns malformed JSON for tool arguments, the catch block in
`ReasoningStepParser.parse()` returns a `ReasoningStep` with `action=FINISH` and
the raw error string as `thought`. The agent loop treats this as a completed
answer and the user receives the raw parse error as the agent's response.
The existing `llmSchemaErrorCount` retry path in `AgentStepProcessor` is never
activated for JSON parse failures — only for `InvalidRequestException`.

**Required Changes:**

1. In `ReasoningStepParser.parse()`, locate the catch block that catches JSON
   parse / deserialization exceptions (look for `JsonProcessingException`,
   `IllegalArgumentException`, or a generic `Exception` wrapping Jackson errors).
2. Replace the current `return ReasoningStep` with `action=FINISH` with:

```java
catch (Exception e) {
    log.warnf("JSON parse error for session %s: %s", sessionId, e.getMessage());
    return ReasoningStep.pending(
        "JSON parse error — retrying with valid format",
        ReasoningStep.OBSERVE,          // ← not FINISH
        Map.of("error", "Invalid JSON: " + e.getMessage()),
        inputTokens, outputTokens
    ).withObservation(
        "System: Your previous response contained invalid JSON. " +
        "Correct the format and retry. Error: " + e.getMessage()
    );
}
```

3. The returned step's `action` must be `OBSERVE` (not `FINISH`) so that
   `AgentStepProcessor.processEngineStep` increments `llmSchemaErrorCount` and
   routes to the existing schema-error retry path.
4. If `ReasoningStep.pending(...)` factory method does not accept `inputTokens`
   and `outputTokens`, use whichever constructor matches the existing API — check
   `ReasoningStep.java` before modifying.

**TEST:**
`arcclaw-core/src/test/java/com/arcclaw/core/agent/reasoning/
    ReasoningStepParserTest.java`  (add to existing file)

```
// Test: LLM response with malformed JSON in tool arguments must return a step
// with action == OBSERVE, not FINISH. The observation must contain the word
// "JSON" so the retry correction is informative.
@Test
void malformedJsonToolArgsReturnsObserveNotFinish() {
    // Arrange: craft a ChatResponse whose AiMessage has a tool call
    // with arguments = "{ broken json }" (not valid JSON)
    // Act: parse it
    // Assert: step.action == ReasoningStep.OBSERVE
    //         step.observation contains "JSON"
    //         step.action != ReasoningStep.FINISH
}
```

**Test command:**
```
mvn clean test -pl arcclaw-core -Dtest=ReasoningStepParserTest
```

---

## Component B2 — DevSecretSeeder last mile: remove injection from AskCommand

**File:**
`arcclaw-app/src/main/java/com/arcclaw/app/AskCommand.java`

**Current Problem:**
`AskCommand` still directly injects `DevSecretSeeder` and calls
`devSeeder.seedDevSecrets()` with a runtime comment "no-op when
auto-seed-secrets=false". `DevSecretSeederStartup.java` (added in P64) is the
correct `@IfBuildProfile("dev")` compile-time gated startup observer. Having the
injection in `AskCommand` also means the `DevSecretSeeder` bean is present and
callable in native production builds — exactly what the B2 fix intended to
eliminate.

**Required Changes:**

1. In `AskCommand.java`:
   - Remove the `@Inject DevSecretSeeder devSeeder;` field.
   - Remove the `devSeeder.seedDevSecrets();` call from `run()`.
   - Add a comment: `// Dev seeding handled by DevSecretSeederStartup
     (@IfBuildProfile("dev") — compile-time excluded from prod builds).`
2. Verify `DevSecretSeederStartup.java` already exists in
   `arcclaw-app/src/main/java/com/arcclaw/app/security/DevSecretSeederStartup.java`
   and is annotated `@IfBuildProfile("dev")`. If not, create it now:

```java
@ApplicationScoped
@IfBuildProfile("dev")
public class DevSecretSeederStartup {
    @Inject DevSecretSeeder seeder;

    void onStart(@Observes StartupEvent ev) {
        seeder.seedDevSecrets();
    }
}
```

3. `AgentSessionService` also injects `DevSecretSeeder` and calls
   `devSeeder.seedDevSecrets()` in `runSync` / `runAsync`. Apply the same
   removal there — the `StartupEvent` observer covers the startup seeding.
   REST paths do not need to call it on every request.

**TEST:** [NO NEW TEST NEEDED]
Update `AskCommandTest` (if it exists) to assert no `DevSecretSeeder` field is
present on `AskCommand`. The startup-event path is tested by
`DevSecretSeederStartupTest` (create if missing):

```
@QuarkusTest
@TestProfile(DevProfile.class)
class DevSecretSeederStartupTest {
    @Test
    void startsUpWithoutException() {
        // @IfBuildProfile("dev") means this only runs in dev profile.
        // Simply booting the Quarkus test context in dev profile and
        // reaching this test body proves DevSecretSeederStartup ran
        // without throwing.
    }
}
```

**Test command:**
```
mvn clean test -pl arcclaw-app -Dtest="AskCommandTest,DevSecretSeederStartupTest"
```

---

## Component R4 — AgentLoop channel-stamp JPA mutation inside OTel try/finally

**File:**
`arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/AgentLoop.java`

**Current Problem:**
In `AgentLoop.runWithRole`, the channel info stamp (setting
`channelPlatformId` and `channelType` on the checkpoint) happens inside the
`try` block that also manages OpenTelemetry spans and maps all exceptions to
`SYSTEM_ERROR FAILED` results. If `agentStore.save(cp)` throws `SQLITE_BUSY`
during this channel-stamp, the entire `AwaitingApproval` result is discarded and
the session is falsely marked `SYSTEM_ERROR`. Approval timeout notifications then
never fire because `channelPlatformId` was never written.

**Current code (inside try block):**
```java
if (result instanceof AgentResult.AwaitingApproval awaiting) {
    if (context != null && context.getUser() != null) {
        agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
            cp.setChannelPlatformId(context.getUser().platformId());
            cp.setChannelType(context.getUser().channel().name());
            agentStore.save(cp);   // ← SQLITE_BUSY here → SYSTEM_ERROR
        });
    }
}
```

**Required Changes:**

1. Extract channel-info stamping into a private `tryStampChannelInfo` method
   that catches `Exception` independently and logs WARN without re-throwing:

```java
private void tryStampChannelInfo(String sessionId, String canonicalRole,
                                  MessageContext context) {
    if (context == null || context.getUser() == null) {
        log.warnf("No MessageContext user for session %s — " +
                  "approval timeout notification will be skipped.", sessionId);
        return;
    }
    try {
        agentStore.load(sessionId, canonicalRole).ifPresent(cp -> {
            cp.setChannelPlatformId(context.getUser().platformId());
            cp.setChannelType(context.getUser().channel().name());
            agentStore.save(cp);
        });
        log.debugf("Stamped channel info on checkpoint session=%s channel=%s platformId=%s",
                   sessionId, context.getUser().channel(), context.getUser().platformId());
    } catch (Exception e) {
        log.warnf("Failed to stamp channel info for session %s — " +
                  "timeout notification may be skipped. Cause: %s",
                  sessionId, e.getMessage());
        // Never re-throw — channel stamping must not corrupt the
        // AwaitingApproval result.
    }
}
```

2. In `runWithRole`, replace the inline stamp block with:
```java
if (result instanceof AgentResult.AwaitingApproval) {
    tryStampChannelInfo(sessionId, canonicalRole, context);
}
```

3. The call must remain AFTER the state-machine result is captured but BEFORE
   the `finally` block runs (OTel span cleanup).

**TEST:**
`arcclaw-core/src/test/java/com/arcclaw/core/agent/loop/AgentLoopTest.java`
(add to existing file)

```
// Test: If agentStore.save throws during channel stamp,
// the AwaitingApproval result is still returned (not swallowed as SYSTEM_ERROR).
@Test
void channelStampFailureDoesNotCorruptAwaitingApprovalResult() {
    // Arrange: mock agentStore.save to throw RuntimeException on second call
    //          (first call is checkpoint load, second is stamp save)
    // Act: run loop to a point where AwaitingApproval is triggered
    // Assert: result instanceof AgentResult.AwaitingApproval
    //         result is NOT AgentResult.Failed
}
```

**Test command:**
```
mvn clean test -pl arcclaw-core -Dtest=AgentLoopTest
```

---

## Component R1 — TelegramAgentHandler per-chat executor anti-pattern

**File:**
`arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram/
    TelegramAgentHandler.java`

**Current Problem:**
`TelegramAgentHandler` maintains a Caffeine cache of
`Executors.newSingleThreadExecutor()` instances keyed by `chatId`. This is
VirtualThread-hostile: dedicated `ExecutorService` per chat defeats the purpose
of Virtual Threads and ManagedExecutor. When the cache evicts an entry,
`executor.shutdown()` does not interrupt LLM-blocked virtual threads — only
`shutdownNow()` does, and even then HTTP clients must cooperate. Under load
(10,000+ active chats), this creates 10,000 thread pools.

**Required Changes:**

1. Remove the `Caffeine` chat-queue cache and all `ExecutorService` per-chatId
   logic from `TelegramAgentHandler`.
2. Replace with a `ConcurrentHashMap<String, AtomicBoolean>` named
   `processingFlags` to track whether a given chatId is already being processed:

```java
private final ConcurrentHashMap<String, AtomicBoolean> processingFlags =
    new ConcurrentHashMap<>();
```

3. In `receiveMessage(Exchange exchange)`, guard with the flag:

```java
AtomicBoolean flag = processingFlags.computeIfAbsent(chatId,
    id -> new AtomicBoolean(false));

if (!flag.compareAndSet(false, true)) {
    sender.sendRaw(user,
        "⏳ Still processing your previous message. Please wait.");
    return;
}

executor.execute(() -> {          // ← the already-injected ManagedExecutor
    try {
        processPipeline(context);
    } finally {
        flag.set(false);
        // Clean up stale entries — prevent unbounded map growth
        processingFlags.remove(chatId, flag);
    }
});
```

4. Keep the existing `@Inject ManagedExecutor executor` — it is already present
   and correct. Do NOT add any new executor injection.
5. The `processPipeline` method body remains unchanged.

**TEST:**
`arcclaw-app/src/test/java/com/arcclaw/app/channel/telegram/
    TelegramAgentHandlerConcurrencyTest.java`  (new file)

```
// Test 1: Two concurrent messages for the same chatId — second must receive
// the "Still processing" reply, not trigger a second pipeline invocation.
@Test
void concurrentMessagesForSameChatIdAreQueuedNotDoubleProcessed() { ... }

// Test 2: After first message completes, a second message for the same chatId
// is processed normally (flag is released).
@Test
void flagIsReleasedAfterPipelineCompletes() { ... }

// Test 3: Two concurrent messages for DIFFERENT chatIds are both processed
// (independent flags, no cross-chat blocking).
@Test
void differentChatIdsProcessConcurrently() { ... }
```

**Test command:**
```
mvn clean test -pl arcclaw-app -Dtest=TelegramAgentHandlerConcurrencyTest
```

---

## Component R2 — ChatHistoryAssembler COMPLIANCE_OVERRIDE_PATTERN backtracking

**File:**
`arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/
    ChatHistoryAssembler.java`

**Current Problem:**
`COMPLIANCE_OVERRIDE_PATTERN` contains `.{0,40}` quantifiers inside alternation
branches. The pattern:
```
i (?:will not|won't) (?:answer|write|discuss|provide).{0,40}(?:questions) going forward
```
…is a catastrophic backtracking candidate. On a crafted or long LLM response
(e.g., a 2,000-char assistant message with many word-boundary retries), this
pattern can cause exponential regex backtracking, pinning a virtual thread for
seconds per message evaluated.

**Required Changes:**

1. Replace `.{0,40}` with possessive quantifier `.{0,40}+` in ALL branches of
   `COMPLIANCE_OVERRIDE_PATTERN`. Possessive quantifiers do not backtrack:

```java
private static final Pattern COMPLIANCE_OVERRIDE_PATTERN = Pattern.compile(
    "(?i)as per our (?:agreement|request,?) i (?:will provide|i'll provide)" +
        ".{0,30}+instead of" +
    "|i'll (?:give|provide).{0,30}+equivalent" +
    "|i cannot provide information on" +
    "|i (?:will not|won't) (?:answer|write|discuss|provide).{0,40}+" +
        "(?:questions) going forward" +
    "|i will only"
);
```

2. Also guard the `.matcher(aiText).find()` call with a length pre-check —
   skip the pattern entirely for messages longer than 4,000 characters to bound
   worst-case cost:

```java
if (aiText != null && aiText.length() <= 4_000
        && COMPLIANCE_OVERRIDE_PATTERN.matcher(aiText).find()) {
    skip = true;
}
```

**TEST:**
`arcclaw-core/src/test/java/com/arcclaw/core/agent/reasoning/
    ChatHistoryAssemblerTest.java`  (add to existing file)

```
// Test: A 3,000-character string designed to maximally backtrack the old
// pattern must complete in < 100ms with the new possessive version.
// Use assertTimeout(Duration.ofMillis(100), () -> pattern.matcher(evil).find()).
@Test
void compliancePatternDoesNotBacktrackOnCraftedInput() {
    String evil = "i will not ".repeat(200) + "discuss some questions going forward";
    assertTimeout(Duration.ofMillis(100),
        () -> COMPLIANCE_OVERRIDE_PATTERN.matcher(evil).find());
}
```

Note: `COMPLIANCE_OVERRIDE_PATTERN` is `private static final` — expose it as
package-private for the test or test via `assembler.assemble(sessionId, goal, history)`
with a crafted history entry.

**Test command:**
```
mvn clean test -pl arcclaw-core -Dtest=ChatHistoryAssemblerTest
```

---

## Component R3 — ChannelCommand.parse double-space tokenization bug

**File:**
`arcclaw-channel-api/src/main/java/com/arcclaw/channel/api/ChannelCommand.java`

**Current Problem:**
The `cron delete <id>` branch uses `text.trim().split(" ")` with a literal
single space. Input like `/cron  delete  id123` (double spaces from copy-paste
or mobile keyboards) produces `["", "cron", "", "delete", "", "id123"]` after
split. Index `[2]` resolves to an empty string `""`, not `"id123"`. The
`cronId` is silently empty, and the cron delete fails with a confusing "not
found" error rather than using the user's intended ID.

**Required Changes:**

1. Replace `text.trim().split(" ")` with `text.trim().split("\\s+")` in ALL
   places within `ChannelCommand.parse(String text)`. The `\\s+` regex collapses
   any run of whitespace into a single split boundary.
2. Add a null/blank guard: if the extracted `cronId` is blank after split, treat
   it as absent (`Optional.empty()`) rather than passing a blank string to
   the repository.

**Before:**
```java
case "cron" -> delete.equalsIgnoreCase(arg)
    ? new DeleteCron(Optional.ofNullable(
        text.trim().split(" ").length > 2
            ? text.trim().split(" ") : null)) [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/85542380/c49f785d-6096-47be-b9f5-a516b4954e91/1-Review-Post-Phase-63-2.md)
    : new CronTasks();
```

**After:**
```java
case "cron" -> {
    String[] parts = text.trim().split("\\s+");
    if (delete.equalsIgnoreCase(arg)) {
        String cronId = parts.length > 2 ? parts.strip() : null; [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/85542380/c49f785d-6096-47be-b9f5-a516b4954e91/1-Review-Post-Phase-63-2.md)
        yield new DeleteCron(
            Optional.ofNullable(cronId == null || cronId.isBlank()
                ? null : cronId));
    }
    yield new CronTasks();
}
```

**TEST:**
`arcclaw-channel-api/src/test/java/com/arcclaw/channel/api/
    ChannelCommandParseTest.java`  (add to existing file)

```
@Test
void doubleSpaceCronDeleteExtractsCronId() {
    var cmd = ChannelCommand.parse("/cron  delete  abc-123");
    assertInstanceOf(ChannelCommand.DeleteCron.class, cmd);
    assertEquals(Optional.of("abc-123"),
        ((ChannelCommand.DeleteCron) cmd).cronId());
}

@Test
void missingCronIdYieldsEmptyOptional() {
    var cmd = ChannelCommand.parse("/cron delete");
    assertInstanceOf(ChannelCommand.DeleteCron.class, cmd);
    assertTrue(((ChannelCommand.DeleteCron) cmd).cronId().isEmpty());
}
```

**Test command:**
```
mvn clean test -pl arcclaw-channel-api -Dtest=ChannelCommandParseTest
```

---

## Component R5 — ToolRegistry reflection penalty on every invocation

**File:**
`arcclaw-core/src/main/java/com/arcclaw/core/tools/ToolRegistry.java`

**Current Problem:**
`extractRequiredSecrets(AgentTool tool)` uses reflection
(`actualClass.getMethod(...)`, `method.getAnnotation(...)`) on every single
tool invocation. For a session with 20 tool calls, this is 20 reflection
traversals on the same classes. Reflection is not free — it bypasses JIT
inlining and is measurable latency at high throughput.

**Required Changes:**

1. Add a `@PostConstruct` method that iterates all registered tools and
   pre-computes their secret requirements into an immutable cache map:

```java
// Add field:
private Map<String, Map<String, String>> secretsCache;

@PostConstruct
void buildSecretsCache() {
    Map<String, Map<String, String>> cache = new HashMap<>();
    for (AgentTool tool : /* all registered tools */ tools.values()) {
        cache.put(tool.name(), extractRequiredSecretsEager(tool));
    }
    this.secretsCache = Collections.unmodifiableMap(cache);
}
```

2. In `extractRequiredSecrets(AgentTool tool)`, replace the reflection logic
   with a cache lookup:

```java
public Map<String, String> extractRequiredSecrets(AgentTool tool) {
    return secretsCache.getOrDefault(tool.name(), Map.of());
}
```

3. The eager `extractRequiredSecretsEager(AgentTool tool)` private method
   contains exactly what the current `extractRequiredSecrets` does today — move
   the reflection there. It is only called once at startup.
4. If tools are registered dynamically (MCP dynamic tools), the cache lookup
   must fall back gracefully to live reflection for unknown tool names:

```java
public Map<String, String> extractRequiredSecrets(AgentTool tool) {
    Map<String, String> cached = secretsCache.get(tool.name());
    if (cached != null) return cached;
    // Dynamic / MCP tool not in cache — fall back to live reflection
    return extractRequiredSecretsEager(tool);
}
```

**TEST:** [NO NEW TEST NEEDED]
Existing `ToolRegistryTest` verifies secret extraction. Add one performance
assertion to confirm the cache is populated at startup:

```
@Test
void secretsCachePrePopulatedAtStartup() {
    // Inject ToolRegistry, call extractRequiredSecrets on a known tool twice.
    // Both calls must return the same Map instance (reference equality for
    // cache hits) OR equal values — proves no new reflection per call.
    var first  = registry.extractRequiredSecrets(githubTool);
    var second = registry.extractRequiredSecrets(githubTool);
    assertEquals(first, second);  // content equality is the minimum bar
}
```

**Test command:**
```
mvn clean test -pl arcclaw-core -Dtest=ToolRegistryTest
```

---

## Component D7 — Remove System.out.println from AgentFunctionProcessor

**File:**
`arcclaw-deployment/src/main/java/com/arcclaw/deployment/
    AgentFunctionProcessor.java`

**Current Problem:**
`AgentFunctionProcessor` (Quarkus build-time annotation processor) contains
`System.out.println(...)` calls for debug logging. These appear in native image
compilation output and in CI logs alongside Maven output, making both noisy and
unparseable. Build-time processors must use the Quarkus build-step logging API.

**Required Changes:**

1. Replace every `System.out.println(...)` and `System.err.println(...)` in
   `AgentFunctionProcessor.java` with the appropriate Quarkus build-step logger:

```java
// Add field at class top:
private static final Logger log =
    Logger.getLogger(AgentFunctionProcessor.class);

// Replace:
System.out.println("AgentFunctionProcessor: processing " + className);
// With:
log.debugf("AgentFunctionProcessor: processing %s", className);

// Replace System.err.println (errors):
System.err.println("AgentFunctionProcessor ERROR: " + msg);
// With:
log.errorf("AgentFunctionProcessor: %s", msg);
```

2. Use `Logger.getLogger` from `io.quarkus.logging.Log` or
   `org.jboss.logging.Logger` — whichever is already in scope in the
   `arcclaw-deployment` module pom. Do NOT add a new logging dependency.

**TEST:** [NO NEW TEST NEEDED]
Verify via build output — the string `"System.out"` must not appear anywhere in
`AgentFunctionProcessor.java` after this fix. Add a Maven Enforcer rule or a
grep-based check in the module's test phase if your CI supports it.

**Test command:**
```
mvn clean package -pl arcclaw-deployment -q 2>&1 | grep -c "AgentFunctionProcessor" || true
# Expected: 0 println lines in build output
```

---

## Component D1 — Unicode-safe truncation in ContextCompactionService

**File:**
`arcclaw-core/src/main/java/com/arcclaw/core/state/
    ContextCompactionService.java`

**Current Problem:**
```java
newSummary = newSummary.substring(0, 1985) + "...[truncated]";
```
`String.substring()` operates on UTF-16 code units. Emoji (U+1F600 etc.) and
CJK Supplementary characters are stored as surrogate pairs (2 code units). A
cut at position 1985 can land between the high and low surrogate of a pair,
producing a malformed string that will corrupt the compaction summary stored
in SQLite and cause JSON serialization errors downstream.

**Required Changes:**

Replace the `substring` call with a codepoint-safe truncation:

```java
if (newSummary.codePointCount(0, newSummary.length()) > 1985) {
    int cutOffset = newSummary.offsetByCodePoints(0, 1985);
    newSummary = newSummary.substring(0, cutOffset) + "...[truncated]";
}
```

The `offsetByCodePoints(0, 1985)` call returns the char index of the 1985th
codepoint boundary — never mid-surrogate. No new dependencies required.

**TEST:**
`arcclaw-core/src/test/java/com/arcclaw/core/state/
    ContextCompactionServiceTest.java`  (add to existing file)

```
@Test
void truncationDoesNotSliceEmojiSurrogatePair() {
    // Build a string of exactly 1984 ASCII chars + a 4-byte emoji (U+1F600)
    // + more text. After truncation, the result must:
    // 1. Not throw when passed to new String(bytes, UTF_8).
    // 2. End with "...[truncated]".
    // 3. NOT contain a lone surrogate (\uD83D or \uDE00 without its pair).
    String base = "A".repeat(1984) + "\uD83D\uDE00" + "EXTRA";
    String result = compact(base);  // call via service or helper
    assertFalse(result.chars().anyMatch(c ->
        (c >= 0xD800 && c <= 0xDBFF) || (c >= 0xDC00 && c <= 0xDFFF)
        && !isSurrogatePair(result, result.indexOf(c))
    ));
    assertTrue(result.endsWith("...[truncated]"));
}
```

**Test command:**
```
mvn clean test -pl arcclaw-core -Dtest=ContextCompactionServiceTest
```

---

## Component D2 — GlobalKnowledgeBase float serialization locale issue

**File:**
`arcclaw-rag/src/main/java/com/arcclaw/rag/GlobalKnowledgeBase.java`

**Current Problem:**
`Arrays.toString(float[])` uses the JVM default locale for number formatting.
On German (`de_DE`), French (`fr_FR`), or any locale that uses a comma as
decimal separator, `0.1f` serializes as `"0,1"` instead of `"0.1"`.
The `vec_global` vector store receives corrupted embedding vectors, causing
silently wrong or zero cosine-similarity results for all RAG lookups — with
no error thrown.

**Required Changes:**

1. Replace `Arrays.toString(float[] embedding)` with a locale-safe serializer:

```java
private static String floatArrayToJson(float[] embedding) {
    if (embedding == null || embedding.length == 0) return "[]";
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < embedding.length; i++) {
        if (i > 0) sb.append(',');
        sb.append(String.format(Locale.ROOT, "%s",
            Float.toString(embedding[i])));
    }
    sb.append(']');
    return sb.toString();
}
```

`Float.toString(float)` always uses `'.'` as decimal separator regardless of
locale. `Locale.ROOT` in `String.format` is an additional safeguard.

2. Replace ALL call sites of `Arrays.toString(embedding)` in
   `GlobalKnowledgeBase.java` with `floatArrayToJson(embedding)`.

3. Same fix applies to `SessionKnowledgeBase.java` if it serializes embeddings
   using `Arrays.toString`. Check and fix in the same PR.

**TEST:**
`arcclaw-rag/src/test/java/com/arcclaw/rag/GlobalKnowledgeBaseTest.java`
(add to existing file)

```
@Test
void embeddingSerializationIsLocaleInvariant() {
    float[] embedding = {0.1f, 0.2f, 0.33f};
    // Temporarily set locale to German decimal-comma locale
    Locale original = Locale.getDefault();
    try {
        Locale.setDefault(Locale.GERMANY);
        String json = GlobalKnowledgeBase.floatArrayToJson(embedding);
        // Must use '.' not ',' as decimal separator
        assertFalse(json.contains(",0"), "Locale leaked into float format");
        assertTrue(json.startsWith("[0.1,"));
    } finally {
        Locale.setDefault(original);
    }
}
```

Make `floatArrayToJson` package-private to enable direct test access.

**Test command:**
```
mvn clean test -pl arcclaw-rag -Dtest=GlobalKnowledgeBaseTest
```

---

## Component D5 — TelegramMarkdownV2Renderer.esc() single-pass rewrite

**File:**
`arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram/
    TelegramMarkdownV2Renderer.java`

**Current Problem:**
The current `esc(String text)` method chains 18 sequential `String.replace()`
calls. Each call allocates a new `String` object on the heap. For a typical
Telegram reply with 30 text nodes, this is 540 intermediate allocations per
response — measurable GC pressure at sustained load.

**Required Changes:**

Replace the 18-chain `replace()` calls with a single-pass `StringBuilder`:

```java
// Static set of all characters that must be escaped in MarkdownV2
private static final Set<Character> ESCAPE_CHARS = Set.of(
    '_', '*', '[', ']', '(', ')', '~', '`', '>',
    '#', '+', '-', '=', '|', '{', '}', '.', '!'
);

private String esc(String text) {
    if (text == null || text.isEmpty()) return "";
    StringBuilder sb = new StringBuilder(text.length() + 16);
    for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (ESCAPE_CHARS.contains(c)) sb.append('\\');
        sb.append(c);
    }
    return sb.toString();
}
```

**Important:** The set must match EXACTLY the 18 characters in the current
replace chain. Verify by counting the existing `.replace("\\", "\\\\")` etc.
calls before deleting them. `\` (backslash) itself must still be the FIRST
escaped — list it first, or handle it with:

```java
if (c == '\\') { sb.append('\\').append('\\'); continue; }
```

**TEST:**
`arcclaw-app/src/test/java/com/arcclaw/app/channel/telegram/
    TelegramMarkdownV2RendererTest.java`  (add to existing file)

```
// All 18 special characters must be escaped with a backslash prefix.
@ParameterizedTest
@ValueSource(chars = {'_','*','[',']','(',')',
    '~','`','>','#','+','-','=','|','{','}','.','!'})
void escapesSingleSpecialChar(char specialChar) {
    String input = String.valueOf(specialChar);
    String result = renderer.esc(input);    // expose esc() as package-private
    assertEquals("\\" + specialChar, result);
}

// Backslash itself must be escaped first (\\)
@Test
void escapesBackslash() {
    assertEquals("\\\\", renderer.esc("\\"));
}

// Normal ASCII must pass through unchanged
@Test
void normalTextPassesThrough() {
    assertEquals("hello world", renderer.esc("hello world"));
}
```

**Test command:**
```
mvn clean test -pl arcclaw-app -Dtest=TelegramMarkdownV2RendererTest
```

---

## Component D6 — BudgetWarning silently swallowed in structured output path

**File:**
`arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/
    LangChainReasoningEngine.java`

**Current Problem:**
`chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt)`
calls `governanceLayer.recordLlmCall(...)` inside a `try` block but catches
`BudgetWarningException` silently (or the exception is simply not caught and
falls through to a generic `catch (Exception e)` that swallows it). The
Dattatreya synthesizer and `StructuredRetryLoop` can run a session all the way
to the hard limit with zero user-visible warning, because warnings only surface
through the main `AgentStepProcessor.processEngineStep` path.

**Required Changes:**

1. In `chatForStructuredOutput`, after the `governanceLayer.recordLlmCall()`
   call, propagate `BudgetWarningException` to the caller:

```java
try {
    governanceLayer.recordLlmCall(sessionId, inputTokens, outputTokens);
} catch (BudgetWarningException bw) {
    // Do NOT swallow — re-throw so AgentStateMachineRunner can
    // surface "approaching budget limit" to the user.
    log.warnf("Budget warning in structured output for session %s: %s",
              sessionId, bw.getMessage());
    throw bw;
} catch (BudgetExceededException be) {
    log.warnf("Budget exceeded in structured output for session %s: %s",
              sessionId, be.getMessage());
    throw be;
}
```

2. Ensure callers of `chatForStructuredOutput` (e.g., `DattatreyaAgent`,
   `StructuredRetryLoop`) already handle both exceptions — they should, since
   the main loop does. If any caller swallows them, fix the caller too.

**TEST:**
`arcclaw-core/src/test/java/com/arcclaw/core/agent/reasoning/
    LangChainReasoningEngineTest.java`  (add to existing file)

```
@Test
void structuredOutputPathPropagatesBudgetWarning() {
    // Arrange: mock GovernanceLayer.recordLlmCall to throw BudgetWarningException
    // Act: call engine.chatForStructuredOutput(...)
    // Assert: BudgetWarningException propagates to caller
    //         (assertThrows(BudgetWarningException.class, () -> ...))
    assertThrows(BudgetWarningException.class, () ->
        engine.chatForStructuredOutput(sessionId, agentRole, schemaPrompt));
}
```

**Test command:**
```
mvn clean test -pl arcclaw-core -Dtest=LangChainReasoningEngineTest
```

---

## Component R6 — IntentClassifierService hardcoded routing patterns (OCP)

**Files:**
- `arcclaw-app/src/main/java/com/arcclaw/app/routing/
    IntentClassifierService.java`
- New: `arcclaw-core/src/main/java/com/arcclaw/core/routing/
    IntentRoutingProvider.java`

**Current Problem:**
`COORDINATOR_FORCE_PATTERN`, `CODER_FORCE_PATTERN`, and
`SCHEDULING_GUARD_PATTERN` are hardcoded constants in `IntentClassifierService`.
The system prompt is assembled via a hardcoded `StringBuilder` injecting rules
specifically for GitHub, cron scheduling, ingestion, etc. Adding a new tool or
persona requires modifying the classifier directly — a clear Open/Closed
Principle violation. Each new tool also silently duplicates test combinations.

**Required Changes:**

1. Create an SPI interface in `arcclaw-core`:

```java
// arcclaw-core/.../core/routing/IntentRoutingProvider.java
public interface IntentRoutingProvider {
    /**
     * Zero or more fast-path trigger patterns for this provider.
     * If any matches, routing skips LLM classification.
     */
    List<Pattern> fastPathTriggers();

    /**
     * Directives injected into the classifier system prompt.
     * Return empty list if none.
     */
    List<String> promptDirectives();

    /**
     * The target agent role if a fast-path trigger matches.
     * Return Optional.empty() if this provider does not own a fast path.
     */
    Optional<String> fastPathRole();
}
```

2. Create implementations for existing hardcoded rules:
   - `GithubIntentProvider` (owns `CODER_FORCE_PATTERN` + GitHub prompt rules)
   - `SchedulingIntentProvider` (owns `SCHEDULING_GUARD_PATTERN` + cron rules)
   - `CoordinatorIntentProvider` (owns `COORDINATOR_FORCE_PATTERN`)

   Place these in `arcclaw-app/src/main/java/com/arcclaw/app/routing/providers/`.

3. Refactor `IntentClassifierService` to inject
   `@Any Instance<IntentRoutingProvider> providers` and aggregate:

```java
@PostConstruct
void buildSystemPrompt() {
    StringBuilder sb = new StringBuilder(BASE_PROMPT);
    providers.forEach(p ->
        p.promptDirectives().forEach(d -> sb.append("\n- ").append(d)));
    this.assembledPrompt = sb.toString();

    this.fastPaths = providers.stream()
        .filter(p -> p.fastPathRole().isPresent())
        .collect(Collectors.toList());
}

public ClassificationResult classify(String message, String sessionId) {
    for (IntentRoutingProvider p : fastPaths) {
        for (Pattern trigger : p.fastPathTriggers()) {
            if (trigger.matcher(message).find()) {
                return new ClassificationResult(
                    p.fastPathRole().get(), ClassificationSource.FAST_PATH);
            }
        }
    }
    // ... existing LLM classification path unchanged
}
```

4. All existing `static final Pattern` fields for fast paths must move into
   their respective provider classes. The constants may remain `package-private`
   in the providers for test access.

5. **Do NOT change the `ClassificationResult` contract or the LLM prompt
   content** — only restructure where they come from.

**TEST:**
`arcclaw-app/src/test/java/com/arcclaw/app/routing/
    IntentClassifierServiceTest.java`  (add to existing file)

```
// Test: A provider registered via CDI contributes its directive to the prompt.
@Test
void newProviderDirectiveAppearsInAssembledPrompt() {
    // Register a test IntentRoutingProvider with one directive
    // Assert assembled prompt contains that directive string
}

// Test: All existing fast-path classifications still work
// (coordinator, coder, scheduling) — regression guard.
@ParameterizedTest
@CsvSource({
    "schedule a daily report, coordinator",
    "write a function to parse JSON, coder",
    "run github pr list, coder"
})
void fastPathClassificationUnchanged(String input, String expectedRole) { ... }
```

**Test command:**
```
mvn clean test -pl arcclaw-app -Dtest=IntentClassifierServiceTest
```

---

## Component R7 — ToolInvocationGuard explicit GithubTool name check (OCP)

**Files:**
- `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/
    ToolInvocationGuard.java`
- New: `arcclaw-core/src/main/java/com/arcclaw/core/tool/
    ToolGuardInterceptor.java`

**Current Problem:**
`ToolInvocationGuard.evaluate(...)` contains an explicit
`if (GithubTool.NAME.equals(toolName))` conditional for GitHub-specific guard
logic. The core guard layer imports and references a concrete tool class —
inverting the dependency direction. Adding another tool with custom guard logic
requires modifying the core guard, which must know nothing about specific tools.

**Required Changes:**

1. Create an interceptor SPI in `arcclaw-core`:

```java
// arcclaw-core/.../core/tool/ToolGuardInterceptor.java
public interface ToolGuardInterceptor {
    /** Tool names this interceptor handles. Empty = handles all. */
    Set<String> handledToolNames();

    /**
     * Evaluate guard for this specific tool.
     * Return Optional.empty() to pass through to the next interceptor.
     * Return Optional.of(decision) to halt and use this decision.
     */
    Optional<ToolInvocationGuard.GuardDecision> evaluate(
        String sessionId, String agentRole, String toolName,
        Map<String, Object> params, List<ReasoningStep> history,
        String goal, List<CompiledSkillEntry> skillSnapshot);
}
```

2. Create `GithubToolGuardInterceptor` in `arcclaw-core`
   (or `arcclaw-app` if it depends on `GithubTool`):
   - Move the existing GitHub-specific guard logic from `ToolInvocationGuard`
     into this class.
   - Implement `handledToolNames()` returning `Set.of(GithubTool.NAME)`.

3. Refactor `ToolInvocationGuard.evaluate(...)`:

```java
@Inject @Any Instance<ToolGuardInterceptor> interceptors;

public GuardDecision evaluate(...) {
    // 1. Run registered interceptors for this tool
    for (ToolGuardInterceptor interceptor : interceptors) {
        if (interceptor.handledToolNames().isEmpty()
                || interceptor.handledToolNames().contains(toolName)) {
            Optional<GuardDecision> decision =
                interceptor.evaluate(sessionId, agentRole, toolName,
                                     params, history, goal, skillSnapshot);
            if (decision.isPresent()) return decision.get();
        }
    }
    // 2. Generic guard logic (dedup, approval, vocabulary filter) — unchanged
    ...
}
```

4. Remove the `if (GithubTool.NAME.equals(toolName))` block from
   `ToolInvocationGuard` entirely. The guard must not import `GithubTool`.

**TEST:**
`arcclaw-core/src/test/java/com/arcclaw/core/agent/loop/
    ToolInvocationGuardTest.java`  (add to existing file)

```
// Test: A registered ToolGuardInterceptor returning Optional.of(Blocked)
// for "mytool" must cause evaluate() to return Blocked for "mytool".
@Test
void registeredInterceptorCanBlockTool() { ... }

// Test: An interceptor returning Optional.empty() passes through to
// the next interceptor (chain continues).
@Test
void interceptorPassThroughContinuesChain() { ... }

// Regression: GitHub-specific guard still blocks destructive operations
// via GithubToolGuardInterceptor (not via inline if-check).
@Test
void githubDestructiveOperationsStillBlocked() { ... }
```

**Test command:**
```
mvn clean test -pl arcclaw-core -Dtest=ToolInvocationGuardTest
```

---

## Component R8 — MessageContext mutable shared object: ordering by priority only

**File:**
`arcclaw-core/src/main/java/com/arcclaw/core/pipeline/MessageContext.java`

**Current Problem:**
`MessageContext` exposes 8+ setter methods and is passed by reference through
all pipeline handlers. The contract "SessionResolutionHandler (priority 40)
must populate `sessionId` before AgentDispatchHandler (priority 70) reads it"
is enforced only by integer priority values — not the type system. A future
handler inserted at priority 55 that calls `context.getSessionId().orElseThrow()`
will throw `IllegalStateException` with no compile-time warning. The shared
mutable object also makes parallel handler execution impossible.

**Required Changes:**

1. Introduce an immutable `SessionContext` record that holds the values
   populated by `SessionResolutionHandler`:

```java
// arcclaw-core/.../core/pipeline/SessionContext.java
public record SessionContext(
    String sessionId,
    ChannelUser user,
    AutonomyLevel autonomyLevel,
    boolean isResumeSignal
) {}
```

2. Add `setSessionContext(SessionContext ctx)` and
   `Optional<SessionContext> getSessionContext()` to `MessageContext`.

3. Update `SessionResolutionHandler` to build and set the `SessionContext`
   on the `MessageContext` at the end of its `handle()` method.

4. Update `AgentDispatchHandler` to call
   `context.getSessionContext().orElseThrow(...)` — the `orElseThrow` makes the
   ordering contract explicit and produces a meaningful error message:

```java
SessionContext session = context.getSessionContext()
    .orElseThrow(() -> new IllegalStateException(
        "AgentDispatchHandler requires SessionContext to be populated " +
        "by SessionResolutionHandler (priority 40) first. " +
        "Check handler priority ordering."));
```

5. **Keep all existing setter methods on `MessageContext` for backward
   compatibility** — do not break existing handlers. This is an additive
   change: the `SessionContext` record is an additional typed view alongside
   the existing flat setters. A full migration to immutable `MessageContext`
   is Phase 66.

6. Do NOT change `MessageContext` constructor signatures — it is constructed
   in `TelegramAgentHandler`, `AgentSessionService`, `AskCommand`, etc.

**TEST:**
`arcclaw-core/src/test/java/com/arcclaw/core/pipeline/
    MessageContextTest.java`  (new file or add to existing)

```
@Test
void agentDispatchHandlerThrowsMeaningfulErrorIfSessionContextMissing() {
    MessageContext ctx = new MessageContext(...);
    // Do NOT call setSessionContext
    AgentDispatchHandler handler = new AgentDispatchHandler(...);
    IllegalStateException ex = assertThrows(
        IllegalStateException.class,
        () -> handler.handle(ctx));
    assertTrue(ex.getMessage().contains("SessionResolutionHandler"));
}

@Test
void sessionContextIsImmutableAfterSet() {
    SessionContext sc = new SessionContext(sessionId, user,
        AutonomyLevel.FULL, false);
    ctx.setSessionContext(sc);
    assertSame(sc, ctx.getSessionContext().get());
    // Records are immutable by definition — no additional assertion needed
}
```

**Test command:**
```
mvn clean test -pl arcclaw-core -Dtest="MessageContextTest,SessionResolutionHandlerTest"
```

---

## Final Verification

After all 16 components pass:

```bash
# Full build — all modules, all tests
mvn clean verify -pl arcclaw-channel-api,arcclaw-core,arcclaw-mcp,arcclaw-rag,arcclaw-app

# Confirm test count has grown from 720+ baseline
mvn clean test | grep -E "Tests run:|BUILD"
```

Expected outcomes:
- Zero compilation errors across all modules
- Zero test regressions on existing 720+ tests
- New test count ≥ 740 (16 components × ~1–3 new tests each)
- No `System.out.println` in `AgentFunctionProcessor`
- No `DevSecretSeeder` injection in `AskCommand` or `AgentSessionService`
- `TelegramAgentHandler` contains zero references to `newSingleThreadExecutor`
- `ToolInvocationGuard` contains zero imports of `GithubTool`

---

## What is intentionally OUT OF SCOPE for this phase

| Item | Reason |
|---|---|
| D8 — ChannelPlugin SPI | Phase 65 — requires Discord proof-of-concept first |
| D9 — MCP Java SDK migration | Phase 65+ — awaiting SDK stability |
| ToolCapability.SANDBOX_EXEC removal | Used in multiple places — retained |
| AgentResult.FailureCategory.RATE_LIMIT removal | Used in multiple places — retained |
| R9 — AgentRoleEnum OCP | Rolls into Phase 65 RoleDefinition provider work |
| MessageContext full immutability | Phase 66 — R8 is the typed-view first step |

---

*Generated March 2026 · ArcClaw Phase 65-Prep · Pre-SPI Hardening*
```