## PRE-PROMPT FINDINGS SUMMARY

**A10 and A3 still not applied** — confirmed from `TelegramAgentHandler.java` and `TelegramCallbackHandler.java`. These are Phase 80-A items reported complete but skipped. They go in as the first two components of this prompt — they must be fixed before the 80-B items.

**B10 cache injection point confirmed** — `LangChainReasoningEngine.nextStep()` calls `promptBuilder.build(agentRole, sessionId, goal)` on every invocation. `SystemPromptBuilder.build()` calls `compactionSummaryRepository.findLatestBySessionId(sessionId)` every time. The compaction summary does not change during a single agent loop run. Cache at `nextStep()` call site — pass the pre-fetched summary as a parameter into `build()`.

**StaleApprovalExpired race (B3)** — `WorkspaceManager` has no HITL expiry GC scheduler. `TelegramStaleApprovalObserver` only sends the Telegram notification after the event fires. The `StaleApprovalExpired` event source is not in these files — but the fix for B3 is in `TelegramCallbackHandler.handleCallback()` where an expired callback tap must be rejected. The checkpoint has `updatedAt` — compare against `hitlTimeoutHours` before processing.

**B8 confirmed no bug** — `formatHitlMessage` produces plain text, `sendWithKeyboard` receives `renderer.render(nodes)` output not formatter output. No double-escape path exists.

**B9 already done** — `IntentClassifierService` already sanitizes delimiters.

**B1, B2, B7 already done or not needed** — confirmed from files.

***

```
Role: Senior Java Architect, Bug Fixer, Senior Java Developer
Task: Phase 80-B — Fix 7 confirmed defects in live production code.
Components A10-FIX and A3-FIX complete Phase 80-A work that was
reported done but not applied. Components B3–B6 and B10 are the
Phase 80-B stabilization items.

--- PROMPT PHASE 80-B ......Tier A ---

--- PROMPT PHASE 80-B ......Tier A - Architecture Context and Other References ---

Refer ArchitectureInvariants.md

1. Every fix must be accompanied by a test that proves the bug existed
   and now does not.
2. No new dependencies. No structural package changes.
3. All existing 988 tests must remain green after every component.
4. Use static final Pattern for any new regex — never compiled inline.
5. All fixes must be safe under JDK 25 — no preview features.
6. Test commands always use clean to prevent stale class false-negatives.

--- PROMPT PHASE 80-B ......Tier A - IMPLEMENTATION REQUIREMENTS - Common Requirements ---

- Surgical fix only: change the minimal lines required to correct the defect.
- Every component is independently compilable and independently testable.
- No renaming, no package moves, no interface additions unless the fix
  strictly requires it.
- All new test classes go in the same module as the class under fix.
- Test method names must follow: void bugName_condition_expectedOutcome().

--- PROMPT PHASE 80-B ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A10-FIX ---

CLASS: com.arcclaw.channel.telegram.TelegramAgentHandler
BUG: processPipeline() contains:
  if ("Pipeline crash".equals(e.getMessage())) {
      log.warn("Expected test exception...");
  } else {
      log.warn(e, "Uncaught exception...");
      sender.sendRaw(context.getUser(), "Sorry...");
  }
This sentinel string was a Phase 80-A required fix (A10) that was
reported complete but not applied. Any real exception with message
"Pipeline crash" is silently swallowed — user never notified, operator
never alerted. This is a production correctness defect.
FIX: Remove the if/else branch entirely. Replace with a single
unconditional path:
  log.warn(e, "Uncaught exception in message pipeline for chat {}",
      context.getChatId());
  try {
      sender.sendRaw(context.getUser(),
          "Sorry, an internal error occurred while processing your message.");
  } catch (Exception sendEx) {
      log.errorf(sendEx,
          "Failed to send error notification to chat %s",
          context.getChatId());
  }
In tests that relied on the sentinel to suppress noise: the test must
throw a test-only exception type (e.g. a package-private
PipelineCrashException extends RuntimeException in src/test/java only)
and assert that sender.sendRaw IS called — proving suppression is gone.
PipelineCrashException must NEVER appear in src/main/java.
TEST 1 — Suppression gone: mock MessagePipeline to throw
  new RuntimeException("Pipeline crash"). Invoke processPipeline().
  Assert sender.sendRaw IS called with the user error message.
TEST 2 — Normal exception path: mock MessagePipeline to throw
  new RuntimeException("DB connection lost"). Invoke processPipeline().
  Assert sender.sendRaw IS called. Assert log.warn was called with
  the exception (verify via log capture or spy).

--- PROMPT PHASE 80-B ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A3-FIX ---

CLASS: com.arcclaw.channel.telegram.TelegramCallbackHandler
BUG: answerCallback(String callbackQueryId) calls
  HttpClient.newHttpClient().sendAsync(...)
This creates a new thread pool, connection pool, and selector thread
on every single Telegram callback. They are never closed. The
CompletableFuture result is fully discarded — failures are invisible,
leaving Telegram's button loading spinner active forever.
This was a Phase 80-A required fix (A3) that was reported complete
but not applied.
FIX: Promote HttpClient to a private static final field initialized
once at class load time:
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
Replace HttpClient.newHttpClient() in answerCallback with HTTP_CLIENT.
Add a .exceptionally(ex -> { ... }) handler on the CompletableFuture
that logs at WARN level with the callbackQueryId:
  .exceptionally(ex -> {
      log.warnf("Failed to answer Telegram callback %s: %s",
          callbackQueryId, ex.getMessage());
      return null;
  });
Do NOT add any other changes to TelegramCallbackHandler in this
component — A4 (formCommandText exhaustive switch) is already applied.
TEST 1 — Single instance: use reflection to read the HTTP_CLIENT
  static field. Call answerCallback 5 times. Assert all 5 calls use
  the same HttpClient instance (identity equality ==).
TEST 2 — Failure visibility: inject a mock or stub HttpClient that
  returns a failed CompletableFuture. Call answerCallback. Assert
  log.warn is called with the callbackQueryId in the message.
  (Use a log capture appender or Mockito spy on the Logger.)

--- PROMPT PHASE 80-B ......Tier A - IMPLEMENTATION REQUIREMENTS - Component B3 ---

CLASS: com.arcclaw.channel.telegram.TelegramCallbackHandler
        + com.arcclaw.core.state.StatefulAgentStore (interface + impl)
BUG: When a user clicks an expired Telegram HITL approval button
(checkpoint older than arcclaw.hitl.timeout-hours), handleCallback()
still processes the approval because there is no timestamp check.
Race: the WorkspaceManager GC scheduler fires StaleApprovalExpired
and marks checkpoints FAILED, but the Telegram callback can arrive
in the window AFTER the user clicks but BEFORE the GC runs.
Result: an expired approval resumes an agent session that should have
been auto-denied — violating Invariant §7.26.
FIX PART 1 — StatefulAgentStore: add a method to check expiry:
  boolean isCheckpointExpired(String sessionId, long timeoutHours);
Implementation in StatefulAgentStoreImpl: query the checkpoint's
updatedAt field. If Instant.now() minus updatedAt exceeds timeoutHours,
return true. If no AWAITING_APPROVAL checkpoint exists for the session,
return true (treat missing as expired — safe default).
FIX PART 2 — TelegramCallbackHandler.handleCallback(): inject
  @ConfigProperty(name = "arcclaw.hitl.timeout-hours", defaultValue = "24")
  long hitlTimeoutHours;
After extracting sessionId from the callback event and BEFORE routing
to any reflex, add:
  if (agentStore.isCheckpointExpired(sessionId, hitlTimeoutHours)) {
      log.warnf("HITL-EXPIRED: callback for session %s rejected — " +
          "approval window has expired", sessionId);
      answerCallback(query.getId()); // dismiss Telegram spinner
      sender.sendRaw(user,
          "⏱ This approval request has expired. " +
          "Please start a new request.");
      return;
  }
This guard must fire BEFORE the reflex loop. It does NOT replace
the WorkspaceManager GC — both defenses are needed (belt and
suspenders for the race window).
Add Architecture Invariants §7.35:
  "TelegramCallbackHandler MUST reject any callback whose sessionId
  maps to a checkpoint older than arcclaw.hitl.timeout-hours before
  routing to any reflex. This guard is independent of and complementary
  to the WorkspaceManager GC auto-deny scheduler (§7.26)."
TEST 1 — Expired checkpoint rejected: mock agentStore to return
  true from isCheckpointExpired. Call handleCallback with a valid
  BATCH_APPROVE event. Assert no reflex is invoked. Assert
  sender.sendRaw is called with an expiry message.
TEST 2 — Non-expired checkpoint proceeds: mock agentStore to return
  false from isCheckpointExpired. Assert the reflex IS invoked normally.
TEST 3 — isCheckpointExpired returns true when updatedAt is older
  than timeoutHours: set checkpoint updatedAt to
  Instant.now().minus(25, HOURS) with hitlTimeoutHours=24.
  Assert isCheckpointExpired returns true.
TEST 4 — isCheckpointExpired returns false for fresh checkpoint:
  set updatedAt to Instant.now().minus(1, HOURS). Assert false.

--- PROMPT PHASE 80-B ......Tier A - IMPLEMENTATION REQUIREMENTS - Component B4 ---

CLASSES: com.arcclaw.core.routing.SchedulingRoutingProvider
          com.arcclaw.core.routing.IngestRoutingProvider
BUG: Both providers use regex fast-path matching that fires on
negations of the intent they are supposed to detect.
SCENARIO 1 — SchedulingRoutingProvider: user writes "please do NOT
  schedule this" or "I don't want to schedule anything". The
  SCHEDULING_GUARD_PATTERN matches "schedule" and routes to coordinator
  which then calls scheduletask — directly opposite of user intent.
SCENARIO 2 — IngestRoutingProvider: user writes "please do NOT store
  this in memory" or "don't remember this". COORDINATOR_FORCE_PATTERN
  matches "store this" and routes to ingest — directly opposite of
  user intent.
FIX — SchedulingRoutingProvider: add a static final negation pattern:
  private static final Pattern SCHEDULING_NEGATION_PATTERN =
      Pattern.compile(
          "(?i)(don'?t|do not|not|never|stop|cancel|remove|"
          + "disable|no longer)\\s{0,10}"
          + "(want to |need to |going to )?"
          + "(schedule|remind|set.{0,15}reminder|add.{0,15}task)",
          Pattern.CASE_INSENSITIVE);
In fastPathTriggers(), check: if SCHEDULING_NEGATION_PATTERN matches
the input, skip this provider (return no match). The negation check
must run BEFORE the scheduling pattern check.
Override the matches() logic: in the existing fastPath evaluation in
IntentClassifierService, the negation cannot be checked because the
SPI only exposes patterns. Therefore add an override method to the
SPI interface with a default no-op implementation:
  default boolean shouldSkip(String userMessage) { return false; }
SchedulingRoutingProvider overrides shouldSkip:
  @Override
  public boolean shouldSkip(String userMessage) {
      return SCHEDULING_NEGATION_PATTERN.matcher(userMessage).find();
  }
IntentClassifierService.classify() checks shouldSkip before
evaluating fastPathTriggers for each provider:
  for (IntentRoutingProvider provider : sortedProviders) {
      if (provider.shouldSkip(userMessage)) continue;
      for (Pattern p : provider.fastPathTriggers()) { ... }
  }
FIX — IngestRoutingProvider: same pattern — add:
  private static final Pattern INGEST_NEGATION_PATTERN =
      Pattern.compile(
          "(?i)(don'?t|do not|not|never|stop|no longer)\\s{0,10}"
          + "(want to |need to )?"
          + "(store|save|remember|ingest|add to|index|learn)",
          Pattern.CASE_INSENSITIVE);
Override shouldSkip in IngestRoutingProvider using INGEST_NEGATION_PATTERN.
TEST 1 — Scheduling negation skipped: call classify("please do NOT
  schedule this reminder"). Assert result is NOT "coordinator" from
  the scheduling fast-path. (The LLM classifier will handle it —
  assert the fast-path did not fire by verifying
  schedulingProvider.shouldSkip("please do NOT schedule...") == true.)
TEST 2 — Normal scheduling still routes: call classify("schedule a
  reminder in 30 minutes"). Assert shouldSkip returns false and
  fast-path returns "coordinator".
TEST 3 — Ingest negation skipped: verify
  ingestProvider.shouldSkip("please do NOT store this in memory")
  == true.
TEST 4 — Normal ingest still routes: verify
  ingestProvider.shouldSkip("store this in memory") == false.
TEST 5 — IntentClassifierService integration: mock the providers.
  Verify that when shouldSkip returns true, the provider's
  fastPathTriggers are never evaluated (verify zero interactions
  with the pattern matchers for that provider).

--- PROMPT PHASE 80-B ......Tier A - IMPLEMENTATION REQUIREMENTS - Component B5 ---

CLASS: com.arcclaw.app.security.ApiKeyRegistry
BUG: getKeyName(String key) and isValidKey(String key) both call
hmac(key) to compute the incoming HMAC, then iterate ALL entries in
storedKeyHmacs with MessageDigest.isEqual() — O(N) per call.
Both methods are called on the authentication hot path for EVERY
incoming API request and SSE stream keepalive. The storedKeyHmacs
map is already built at @PostConstruct as Map<String, byte[]>
(keyName → hmac). The fix is to add a reverse lookup map built
at @PostConstruct time.
FIX: In initHmacs(), after building storedKeyHmacs, build a
reverse lookup map keyed by the Base64 or hex encoding of the HMAC
bytes so it is Map-lookup safe:
  private Map<String, String> hmacToKeyName;
  @PostConstruct
  void initHmacs() {
      Map<String, byte[]> computed = new LinkedHashMap<>();
      Map<String, String> reverse = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : apiKeys.entrySet()) {
          byte[] h = hmac(entry.getValue());
          computed.put(entry.getKey(), h);
          // Use hex encoding as map key — safe, unique, constant-time lookup
          reverse.put(toHex(h), entry.getKey());
      }
            this.storedKeyHmacs = Collections.unmodifiableMap(computed);
      this.hmacToKeyName  = Collections.unmodifiableMap(reverse);
  }

  private static String toHex(byte[] bytes) {
      StringBuilder sb = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
          sb.append(String.format("%02x", b));
      }
      return sb.toString();
  }

Replace getKeyName() with:
  public String getKeyName(String key) {
      if (key == null) return null;
      return hmacToKeyName.get(toHex(hmac(key)));
  }

Replace isValidKey() with:
  public boolean isValidKey(String key) {
      if (key == null) return false;
      return hmacToKeyName.containsKey(toHex(hmac(key)));
  }

Both methods are now O(1) — one hmac() computation and one map lookup.
The isAllowed() rate-limiter method is NOT changed.
The hmac() private method is NOT changed.
Do NOT change the storedKeyHmacs field — it may be used by other
callers. Only add the new hmacToKeyName reverse map alongside it.
TEST 1 — O(1) correctness: register 3 API keys in initHmacs via
  test config. Call getKeyName with each valid key value. Assert
  each returns the correct key name with zero iteration.
TEST 2 — Unknown key: call getKeyName with a key not in the registry.
  Assert null is returned.
TEST 3 — isValidKey true/false: assert isValidKey returns true for
  a registered key and false for an unregistered key.
TEST 4 — Performance contract: create a registry with 1000 keys.
  Call getKeyName 1000 times. Assert total wall time < 50ms
  (proving O(1) not O(N*N)). This is a safety net test — if
  someone reverts to O(N) the test catches it.

--- PROMPT PHASE 80-B ......Tier A - IMPLEMENTATION REQUIREMENTS - Component B6 ---

CLASSES: com.arcclaw.channel.telegram.TelegramAgentHandler
          com.arcclaw.app.scheduler.CronJobRunner
          com.arcclaw.app.scheduler.AgentCronScheduler
BUG: MDC key clearing logic is copy-pasted across these three classes.
Each manages its own finally block to clear MDC keys. When a new MDC
key is added (e.g. correlationId, traceId, agentRole) it must be
added to all three finally blocks manually. One will inevitably be
missed, causing MDC key leakage into subsequent log entries on
thread pool reuse. This is a DRY violation with real production
consequence for log correlation.
Current MDC usage per class:
  TelegramAgentHandler: sets no MDC directly but relies on
    WorkspaceScopeHolder.clear() in its finally block.
  CronJobRunner: does not set MDC directly — context is set by
    WorkspaceContext.run() internally.
  AgentCronScheduler: sets MDC.put(MdcKeys.USER_ID, job.userId) in
    the dispatch lambda and clears it in the lambda finally block
    with MDC.remove(MdcKeys.USER_ID).
  ParallelToolStepExecutor: sets PARALLEL_BATCH_SIZE and
    PARALLEL_TOOLS in MDC and clears both in its own finally.
FIX: Create a utility class in arcclaw-core:
  package com.arcclaw.core.util;
  public final class MdcCleaner {
      private MdcCleaner() {}

      // Removes ALL known ArcClaw MDC keys in one call.
      // Add new keys here when MdcKeys grows — one place only.
      public static void clearAll() {
          MDC.remove(MdcKeys.USER_ID);
          MDC.remove(MdcKeys.SESSION_ID);
          MDC.remove(MdcKeys.AGENT_ROLE);
          MDC.remove(MdcKeys.CHAT_ID);
          MDC.remove(MdcKeys.PARALLEL_BATCH_SIZE);
          MDC.remove(MdcKeys.PARALLEL_TOOLS);
          // Add new MdcKeys entries here only — never in individual classes
      }

      // Removes a specific subset of MDC keys.
      // Use when clearing ALL would be too broad (e.g. nested scopes).
      public static void clear(String... keys) {
          for (String key : keys) MDC.remove(key);
      }
  }
In AgentCronScheduler.pollAndDispatch() dispatch lambda:
  Replace:
    MDC.put(MdcKeys.USER_ID, job.userId);
    try { jobRunner.execute(job); }
    finally { MDC.remove(MdcKeys.USER_ID); }
  With:
    MDC.put(MdcKeys.USER_ID, job.userId);
    try { jobRunner.execute(job); }
    finally { MdcCleaner.clear(MdcKeys.USER_ID); }
In ParallelToolStepExecutor.execute() MDC block:
  Replace the MDC.remove calls in the finally block with:
    finally { MdcCleaner.clear(
        MdcKeys.PARALLEL_BATCH_SIZE, MdcKeys.PARALLEL_TOOLS); }
TelegramAgentHandler and CronJobRunner do NOT need changes —
  their cleanup is already handled at a higher level
  (WorkspaceScopeHolder.clear() and WorkspaceContext.run() boundary).
  Do NOT add MdcCleaner.clearAll() to these two classes unless a
  specific leaked key is identified.
Add a Javadoc comment to MdcKeys class (or wherever MDC key constants
are defined):
  // When adding a new MDC key constant, also add a corresponding
  // MDC.remove() call in MdcCleaner.clearAll(). This is enforced
  // by MdcCleanerContractTest.
TEST 1 — clearAll removes all known keys: set all known MDC keys
  to test values. Call MdcCleaner.clearAll(). Assert all keys
  are null in MDC after the call.
TEST 2 — clear(keys) removes only specified keys: set 3 MDC keys.
  Call MdcCleaner.clear(key1, key2). Assert key1 and key2 are null.
  Assert key3 is still set.
TEST 3 — Contract test (MdcCleanerContractTest): use reflection to
  read all public static final String fields from MdcKeys. Assert
  that every field value appears in the MdcCleaner.clearAll() method
  body (read via source or verify via test that calls clearAll on a
  map pre-populated with all MdcKey values and asserts all are cleared).
  This test will FAIL if a new MdcKeys constant is added without
  updating MdcCleaner — enforcing the contract at build time.
TEST 4 — AgentCronScheduler uses MdcCleaner: verify the dispatch
  lambda in pollAndDispatch no longer contains a raw MDC.remove call
  (static code assertion via grep or ArchUnit string check).

--- PROMPT PHASE 80-B ......Tier A - IMPLEMENTATION REQUIREMENTS - Component B10 ---

CLASSES: com.arcclaw.core.agent.reasoning.LangChainReasoningEngine
          com.arcclaw.core.agent.reasoning.SystemPromptBuilder
BUG: LangChainReasoningEngine.nextStep() calls
  promptBuilder.build(agentRole, sessionId, goal)
on every single invocation. SystemPromptBuilder.build() calls
  compactionSummaryRepository.findLatestBySessionId(sessionId)
on every call. For a 7-iteration agent loop, this is 7 SQLite reads
for compaction summary data that does not change mid-loop. For 10
concurrent sessions each with 7 iterations, this is 70 unnecessary
SQLite reads per second under normal load. The compaction summary
is written only at session compaction time — it never changes during
a loop run.
FIX PART 1 — SystemPromptBuilder: add an overloaded build() method
that accepts a pre-fetched compaction summary string, bypassing the
repository call:
  public String build(String agentRole, String sessionId,
                      String goal, String compactionSummary) {
      // identical to build(agentRole, sessionId, goal) except:
      // replace compactionSummaryRepository.findLatestBySessionId(sessionId)
      // with the provided compactionSummary (may be null — same semantics)
  }
Keep the existing build(String agentRole, String sessionId, String goal)
method intact and unchanged — it is used in chatForStructuredOutput()
and potentially other callers. It must continue to call the repository
internally. Do NOT break existing callers.
FIX PART 2 — LangChainReasoningEngine.nextStep(): fetch the
compaction summary ONCE before the first call, then pass it through:
  public ReasoningStep nextStep(String sessionId, String agentRole,
                                String goal, List<ReasoningStep> history) {
      // Fetch compaction summary once per nextStep invocation.
      // The caller (AgentLoop.runWithRole) calls nextStep in a loop —
      // this still fetches once per LLM call, not once per loop.
      // To fetch truly once per loop, the summary must be passed in
      // from AgentLoop — see NOTE below.
      String compactionSummary =
          compactionSummaryRepository.findLatestBySessionId(sessionId)
              .map(CompactionSummaryEntity::getSummary)
              .orElse(null);
      String systemPrompt =
          promptBuilder.build(agentRole, sessionId, goal, compactionSummary);
      ...
  }
NOTE: The correct long-term fix is to pass compactionSummary from
AgentLoop.runWithRole() into nextStep() so it is fetched exactly once
per loop execution (not once per LLM call). However, changing the
ReasoningEngine interface signature is an architectural change deferred
to Phase 81. The Phase 80-B fix moves the fetch from inside
SystemPromptBuilder (where it is hidden and uncontrollable) to
LangChainReasoningEngine (where it is visible and can be cached at
the call site). This alone reduces the database load by eliminating
the per-system-prompt-build read — the read now happens once per
nextStep() call rather than potentially multiple times if build() is
called from multiple paths in the same turn.
Inject CompactionSummaryRepository into LangChainReasoningEngine:
  @Inject CompactionSummaryRepository compactionSummaryRepository;
TEST 1 — Repository called once per nextStep: mock
  compactionSummaryRepository. Call nextStep once. Verify
  findLatestBySessionId is called exactly once (not twice or more).
TEST 2 — Null compaction summary handled: mock repository to return
  Optional.empty(). Assert nextStep completes without exception
  and the system prompt is built correctly (compactionSummary == null
  behaves identically to the pre-fix path).
TEST 3 — SystemPromptBuilder.build(4-arg) matches build(3-arg) output:
  call both overloads with the same pre-fetched compaction summary
  string. Assert the returned system prompt strings are identical.
  This proves the overload is a pure refactor with no behavioral change.
TEST 4 — chatForStructuredOutput still uses 3-arg build: verify that
  chatForStructuredOutput() still calls promptBuilder.build(agentRole,
  sessionId, "") (3-arg) and does NOT call the 4-arg overload — it has
  no loop context so the 3-arg path with internal repository call is
  correct for that code path.

--- PROMPT PHASE 80-B ......Tier A - PRIORITIZATION MODEL ---

HIGH PRIORITY — P80-B-A (Correctness / Security / Production risk):

  A10-FIX  "Pipeline crash" sentinel — real exceptions silently
           swallowed, user never notified. Reported done in Phase 80-A
           but not applied. Fix first.
  A3-FIX   HttpClient per-callback resource leak — thread pool and
           connection pool created and abandoned on every button tap.
           Reported done in Phase 80-A but not applied. Fix first.
  B3       Stale HITL callback accepted after expiry — expired
           approval resumes a session that should have been auto-denied.
           Violates Invariant §7.26. Security boundary bypass.
  B4       Negation false positive in intent routing — "do NOT
           schedule this" routes to coordinator which schedules.
           Direct inversion of user intent. Correctness defect.

MEDIUM PRIORITY — P80-B-B (Performance / Observability / Maintainability):

  B5       ApiKeyRegistry O(N) HMAC scan — every API request and
           SSE keepalive triggers linear scan of cryptographic hashes.
           O(1) fix is trivial and high value.
  B10      compactionSummaryRepository per LLM turn — 7 unnecessary
           SQLite reads per agent loop iteration per session.
  B6       MDC clearing DRY — copy-pasted finally blocks, new MDC
           key additions will inevitably miss one class.

--- PROMPT PHASE 80-B ......Tier A - EXECUTION ORDER ---

Execute components in this exact order:

  A10-FIX → A3-FIX → B5 → B6 → B4 → B10 → B3

Rationale:
  A10-FIX first — single class change, zero dependencies, closes
    the open Phase 80-A item immediately.
  A3-FIX second — single class change, zero dependencies, closes
    the open Phase 80-A item. Both A-FIX items are in channel-telegram
    module — do them back to back before touching core.
  B5 third — ApiKeyRegistry is in arcclaw-app, isolated, no
    dependencies on other components in this list. Simple add-field
    refactor. Low risk warm-up before the harder components.
  B6 fourth — MdcCleaner is a new class in arcclaw-core util package.
    ParallelToolStepExecutor is updated to use it. Zero functional
    behavior change. Do before B4 and B10 which touch more complex
    classes.
  B4 fifth — adds shouldSkip() default method to IntentRoutingProvider
    SPI interface and updates two routing providers plus
    IntentClassifierService. Does not touch core agent loop. Must be
    done before B10 because B10 touches LangChainReasoningEngine which
    has an @Inject IntentClassifierService path indirectly via
    SystemPromptBuilder — though not directly coupled, keeping them
    in separate steps isolates any potential CDI context issues.
  B10 sixth — adds 4-arg overload to SystemPromptBuilder and injects
    CompactionSummaryRepository into LangChainReasoningEngine. Touches
    core reasoning engine but only adds an overload and one new inject —
    no behavioral change to existing paths.
  B3 last — widest change: adds isCheckpointExpired to
    StatefulAgentStore interface and impl, adds expiry guard to
    TelegramCallbackHandler, adds Invariant §7.35. Saved for last
    because it touches the most files and benefits from all prior
    components being green first.

--- PROMPT PHASE 80-B ......Tier A - VERIFICATION GATE ---

After ALL 7 components are complete, run the full verification:

  ./mvnw clean verify \
    -pl arcclaw-channel-api,arcclaw-channel-telegram,\
  arcclaw-channel-web,arcclaw-core,arcclaw-app \
    -am

Expected outcome:
  BUILD SUCCESS
  Minimum 1009 tests green
  (988 existing + 21 new minimum, 3 per component across 7 components).
  Realistic target given 3-4 tests per component: 1009-1016.
  Zero compiler warnings introduced by any fix across all modules.

Per-component isolated verification (run after each component before
proceeding to next):

  A10-FIX: ./mvnw clean test -pl arcclaw-channel-telegram
               -Dtest=TelegramAgentHandlerPipelineSentinelTest -am

  A3-FIX:  ./mvnw clean test -pl arcclaw-channel-telegram
               -Dtest=TelegramCallbackHandlerHttpClientTest -am

  B5:      ./mvnw clean test -pl arcclaw-app
               -Dtest=ApiKeyRegistryLookupTest -am

  B6:      ./mvnw clean test -pl arcclaw-core
               -Dtest="MdcCleanerTest,MdcCleanerContractTest" -am

  B4:      ./mvnw clean test -pl arcclaw-core,arcclaw-app
               -Dtest="SchedulingRoutingProviderNegationTest,\
  IngestRoutingProviderNegationTest,\
  IntentClassifierServiceNegationTest" -am

  B10:     ./mvnw clean test -pl arcclaw-core
               -Dtest="SystemPromptBuilderOverloadTest,\
  LangChainReasoningEngineCompactionCacheTest" -am

  B3:      ./mvnw clean test -pl arcclaw-core,arcclaw-channel-telegram
               -Dtest="StatefulAgentStoreExpiryTest,\
  TelegramCallbackHandlerExpiryGuardTest" -am

--- PROMPT PHASE 80-B ......Tier A - ARCHITECTURE INVARIANT COMPLIANCE CHECKLIST ---

Before submitting each component for review, verify ALL boxes checked:

  □ A10-FIX — "Pipeline crash" string literal does NOT appear anywhere
               in src/main/java after fix. PipelineCrashException exists
               ONLY in src/test/java. The catch block has exactly one
               unconditional path — no if/else on exception message.

  □ A3-FIX  — HTTP_CLIENT is private static final at class level.
               HttpClient.newHttpClient() does NOT appear inside any
               method in TelegramCallbackHandler after fix. sendAsync
               has a .exceptionally() handler that logs at WARN with
               callbackQueryId. No synchronous blocking call introduced.

  □ B3      — isCheckpointExpired added to StatefulAgentStore interface
               with a Javadoc explaining the safe-default (missing
               checkpoint = expired = true). Implementation uses
               updatedAt field on AgentCheckpoint. The expiry guard in
               TelegramCallbackHandler fires BEFORE the reflex loop —
               not inside it. Invariant §7.35 added to
               ArchitectureInvariants.md. ConfigProperty injection for
               hitlTimeoutHours uses defaultValue "24" matching
               TelegramStaleApprovalObserver's existing default.

  □ B4      — shouldSkip() is a default method on IntentRoutingProvider
               with default return false — existing providers not
               implementing it compile without changes. SCHEDULING_
               NEGATION_PATTERN and INGEST_NEGATION_PATTERN are
               private static final Pattern — never compiled inline.
               IntentClassifierService checks shouldSkip BEFORE
               evaluating fastPathTriggers for the same provider.
               The existing fastPathTriggers patterns are NOT changed —
               only the skip gate is added around them.

  □ B5      — hmacToKeyName is private, initialized in @PostConstruct
               alongside storedKeyHmacs, declared as
               Map<String, String> (not Map<String, byte[]>).
               toHex() is private static. storedKeyHmacs field                is NOT removed or renamed — existing callers of
               storedKeyHmacs are unaffected. isValidKey() and
               getKeyName() both use toHex(hmac(key)) for O(1) lookup.
               The hmac() private method signature is NOT changed.
               isAllowed() rate-limiter method is NOT changed.

  □ B6      — MdcCleaner class is in com.arcclaw.core.util package.
               MdcCleaner is final with private constructor — not
               instantiable. clearAll() lists every MdcKeys constant
               explicitly — no wildcard or reflection-based clearing.
               AgentCronScheduler dispatch lambda uses
               MdcCleaner.clear(MdcKeys.USER_ID) — raw MDC.remove
               does NOT appear in that lambda after fix.
               ParallelToolStepExecutor finally block uses
               MdcCleaner.clear(PARALLEL_BATCH_SIZE, PARALLEL_TOOLS).
               TelegramAgentHandler and CronJobRunner are NOT touched.
               MdcCleanerContractTest uses reflection on MdcKeys to
               assert full coverage — this test is permanent and runs
               on every build.

  □ B10     — SystemPromptBuilder 3-arg build() method is NOT changed.
               New 4-arg build() overload accepts String compactionSummary
               as the last parameter (nullable). chatForStructuredOutput()
               in LangChainReasoningEngine still calls 3-arg build() —
               NOT the 4-arg overload. nextStep() calls 4-arg build()
               with the pre-fetched summary. CompactionSummaryRepository
               is injected into LangChainReasoningEngine via @Inject —
               not passed as constructor parameter. The ReasoningEngine
               interface signature is NOT changed — nextStep() method
               signature remains identical to current definition.

--- PROMPT PHASE 80-B ......Tier A - WHAT IS OUT OF SCOPE FOR THIS PHASE ---

The following items are explicitly deferred and must NOT be touched
in Phase 80-B. Touching them violates the surgical-fix contract and
risks destabilizing the 988 tests already green.

  CONFIRMED NOT NEEDED — removed from all future phases:

  - ToolInvoker God-class / ToolInvocationStrategy extraction (Grok-G1)
    REASON: ToolInvoker.java reviewed — it is already lean (3 deps,
    single method, ~40 lines). The refactoring was done in prior phases.
    This item is CLOSED. Do not reopen.

  - OTel context propagation to virtual threads in ParallelToolStepExecutor
    (Grok-G8, Invariant §7.13)
    REASON: ParallelToolStepExecutor.java reviewed — OTel propagation
    via Context.current() capture and otelScope.makeCurrent() inside
    each virtual thread CompletableFuture is ALREADY IMPLEMENTED.
    This item is CLOSED. Do not reopen.

  - AgentResultFormatter.formatHitlMessage MarkdownV2 double-escape
    (Grok-G3)
    REASON: Traced the full flow. formatHitlMessage produces plain text.
    sendWithKeyboard receives renderer.render() output, not formatter
    output. No double-escape path exists. Bug does not exist.
    This item is CLOSED. Do not reopen.

  - IntentClassifier delimiter injection strip (Gemini-P10)
    REASON: IntentClassifierService.java reviewed — delimiter stripping
    via .replace(DELIM_OPEN, "").replace(DELIM_CLOSE, "") is ALREADY
    IMPLEMENTED. This item is CLOSED. Do not reopen.

  - CronJobRunner deliveryId written before WorkspaceContext established
    (Gemini-P12)
    REASON: CronJobRunner.java reviewed — the ordering is intentional
    and architecturally necessary. The comment in the code correctly
    documents why: ApproveReflex.handleCronApproval uses deliveryId
    to locate the checkpoint. Moving setDeliveryId inside
    WorkspaceContext.run would break cron approval routing if context
    init fails. This item is CLOSED. Do not reopen.

  DEFERRED TO PHASE 81:

  - Deity persona boilerplate collapse → DefaultAgent + Qute template.
    Personas are functionally correct. Maintainability debt tolerable
    for one more phase. Fix during Phase 81 when skill/persona system
    is touched for onboarding.

  - ChatHistoryAssembler O(n) per-turn String allocation optimization.
    Performance, not correctness. Phase 81.

  - COMPLIANCE_OVERRIDE_PATTERN false-positive rewrite → ComplianceFilter
    SPI. Requires new interface and behavioral spec. Phase 81.

  - ReplyNode permit count ArchUnit enforcement test. Requires ArchUnit
    dependency decision. Phase 81.

  - arcclaw-ui maven-antrun bundling → proper WebJar. Fix during Phase
    81 when frontend is touched for onboarding Lit component.

  - compactionSummaryRepository once-per-loop (not once-per-LLM-call)
    optimization. Requires ReasoningEngine interface signature change.
    B10 moves the fetch out of SystemPromptBuilder into
    LangChainReasoningEngine (visible, controllable). The true
    once-per-loop optimization requires passing the summary through
    AgentLoop.runWithRole() into nextStep() — an interface change
    deferred to Phase 81 when AgentLoop is being touched for
    onboarding context setup.

  - TelegramAllowlistGuard List→Set pre-build at @PostConstruct
    (full perf fix). A6 added the open-access warning. Set conversion
    is a follow-on in Phase 81.

  DEFERRED TO PHASE 82+:

  - SessionRegistry multi-pod JVM-local lock → distributed lock.
    Valid concern for distributed deployments. Not a current blocker
    for SQLite single-node. Phase 82+.

  - Invariants 7.8 vs 7.19 callId contradiction (call0 vs UUID for
    Ollama). Requires provider-specific reconciliation spec. Phase 82+.

  - AgentRoleEnum static God-initializer Map.of() → validated startup
    registry with SPI and duplicate guard. Architectural refactor.
    Phase 82+.

  - SynthesisResult Map<String, Object> additionalInfo unbounded heap
    risk → schema validation layer. Phase 82+.

  - DelegateToAgentTool Option B (bounded execution mask). Follow-on
    to Phase 80-A A17 strict Option A. Phase 82+.

  - SystemPromptBuilder God class full decomposition. Architectural
    refactor requiring multiple new interfaces. Phase 82+.

  - AgentRoleEnum Map.of() NoClassDefFoundError on duplicate key →
    validated startup map with explicit duplicate check. Phase 82+.

  - TelegramCallbackHandler updateOrRemoveKeyboard snapshot isolation
    gap → checkpoint read under optimistic lock. Phase 82+.

--- PROMPT PHASE 80-B ......Tier A - COMBINED PHASE 80 FINAL COMPLETION CRITERIA ---

Phase 80 (A1–A18 + A10-FIX + A3-FIX + B3–B6 + B10) is considered
FULLY COMPLETE when ALL of the following are simultaneously true:

  □ All 25 components green:
    A1–A18 from Phase 80-A and 80-A-EXT (988 tests confirmed)
    A10-FIX, A3-FIX, B3, B4, B5, B6, B10 from Phase 80-B

  □ Full verification passes with zero failures:
    ./mvnw clean verify \
      -pl arcclaw-channel-api,arcclaw-channel-telegram,\
    arcclaw-channel-web,arcclaw-core,arcclaw-app \
      -am

  □ Minimum 1009 tests green. Realistic target 1009–1016.

  □ ArchitectureInvariants.md updated with:
    §7.35 (TelegramCallbackHandler expiry guard — B3)
    Closure notes for B1, B2, B7, B8, B9 confirming those items
    are already implemented or confirmed non-bugs — so they are
    never reopened in future phases.

  □ The following strings do NOT appear anywhere in src/main/java:
    "Pipeline crash"               (A10-FIX)
    "HttpClient.newHttpClient()"   inside TelegramCallbackHandler
                                   (A3-FIX)
    "MDC.remove" inside AgentCronScheduler dispatch lambda (B6)
    "MDC.remove" inside ParallelToolStepExecutor finally block (B6)

  □ The following are confirmed present in src/main/java:
    private static final HttpClient HTTP_CLIENT in
      TelegramCallbackHandler                   (A3-FIX)
    Map<String, String> hmacToKeyName in
      ApiKeyRegistry                            (B5)
    MdcCleaner.clearAll() method in
      com.arcclaw.core.util.MdcCleaner         (B6)
    shouldSkip() default method on
      IntentRoutingProvider                    (B4)
    isCheckpointExpired() on
      StatefulAgentStore interface             (B3)
    build(String, String, String, String)
      overload on SystemPromptBuilder          (B10)

  □ Zero compiler warnings introduced across all modules.

  □ MdcCleanerContractTest is part of the permanent test suite —
    it will catch any future MdcKeys addition that is not reflected
    in MdcCleaner.clearAll().

--- PROMPT PHASE 80-B ......Tier A - GATEWAY TO PHASE 81 ---

Phase 81 (arcclaw-onboarding backend + arc-onboarding.ts Lit component)
is UNBLOCKED once Phase 80-B completion criteria above are met.

Phase 81 entry conditions confirmed satisfied by Phase 80:
  1. A18 — arcclaw-channel-web SPI-compliant (done in 80-A-EXT)
  2. A12+A13 — CheckpointApprovalService domain API exists (done)
  3. A11 — ScopedValue full context propagation correct (done)
  4. A17 — Delegation capability escalation blocked (done)
  5. A14 — Pipe separator in callback data (done)
  6. A10-FIX — No sentinel suppression in production (80-B)
  7. A3-FIX — No HttpClient leak on callback path (80-B)
  8. B3 — Stale HITL callback rejected before routing (80-B)
  9. B4 — Negation false positive in routing fixed (80-B)

Phase 81 may begin while B5, B6, B10 are still in progress —
those are performance and maintainability items that do not block
new feature development. However they must be completed before
Phase 82 (Playwright browser tool) begins.