Role: Senior Java Architect, Bug Fixer, Senior Java Developer
Task: Phase 80-A — Fix 10 confirmed correctness defects in live production code.
All bugs below exist today. No architectural changes. Surgical fixes only.

--- PROMPT PHASE 80-A ......Tier A ---

--- PROMPT PHASE 80-A ......Tier A - Architecture Context and Other References ---

Refer ArchitectureInvariants.md

1. Every fix must be accompanied by a test that proves the bug existed and now does not.
2. No new dependencies. No structural package changes.
3. All existing 967 tests must remain green after every component.
4. Use static final Pattern for any new regex — never compiled inline.
5. All fixes must be safe under JDK 25 — no preview features.
6. Test commands always use `clean` to prevent stale class false-negatives.

--- PROMPT PHASE 80-A ......Tier A - IMPLEMENTATION REQUIREMENTS - Common Requirements ---

- Surgical fix only: change the minimal lines required to correct the defect.
- Every component is independently compilable and independently testable.
- No renaming, no package moves, no interface additions unless the fix strictly requires it.
- If a fix touches a class that also contains a separate unrelated bug from this list, fix only the component-specific defect in that component — do not fix ahead.
- All new test classes go in the same module as the class under fix.
- Test method names must follow: `void bugName_condition_expectedOutcome()`.

--- PROMPT PHASE 80-A ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A1 ---

CLASS: `com.arcclaw.channel.telegram.TelegramAgentHandler`
BUG: Caffeine `expireAfterAccess(10 min)` evicts the `AtomicBoolean` processing flag during long-running agent loops (>10 min). After eviction, `processingFlags.get(chatId, id -> new AtomicBoolean(false))` creates a fresh flag, bypassing the in-flight guard. Two concurrent pipeline executions can run for the same chatId, corrupting session state and checkpoint writes.
FIX: Replace `expireAfterAccess` with `expireAfterWrite` and tie the TTL to the configured agent max-loop duration plus a fixed 2-minute safety buffer. Inject `@ConfigProperty(name = "arcclaw.agent.max-loop-seconds", defaultValue = "600")` long maxLoopSeconds and compute TTL as `Duration.ofSeconds(maxLoopSeconds + 120)`. No other changes to the flag logic.
TEST: Simulate TTL expiry by using a very short TTL (100 ms) via test config override. Send a first message, verify processing=true, artificially sleep past TTL, send a second message on the same chatId, verify the guard correctly blocks the second message (i.e., the same AtomicBoolean object is still held by the first execution and still reads `true`). Assert `isProcessing(chatId) == true` throughout.

--- PROMPT PHASE 80-A ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A2 ---

CLASS: `com.arcclaw.channel.telegram.BatchHitlKeyboardBuilder`
BUG: `truncateCb(String cbInfo)` uses `cbInfo.length()` (char count) to enforce Telegram's 64-*byte* limit. For session IDs with multi-byte UTF-8 characters the check under-counts, producing callback data that silently exceeds Telegram's limit. Additionally, `substring(0, 64)` can cut mid-session-ID, making `parseCallback` produce a corrupted sessionId that matches no active session, causing silent approval no-ops.
FIX: Change the check to `cbInfo.getBytes(StandardCharsets.UTF_8).length > 64`. For the truncation, truncate the UTF-8 byte array to 64 bytes and reconstruct via `new String(bytes, 0, 64, StandardCharsets.UTF_8)` — but also add a `log.error` (not `log.warn`) stating the full original value and that silent approval failure is now possible, so operators are forced to act.
TEST: Construct a callback string whose char-count is ≤64 but byte-count is >64 (use a session ID containing e.g. Japanese characters). Assert that `truncateCb` logs at ERROR level and that the returned byte length ≤ 64. Assert `parseCallback` on the truncated string returns `Optional.empty()` (correct failure behavior) rather than returning a garbled sessionId match.

--- PROMPT PHASE 80-A ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A3 ---

CLASS: `com.arcclaw.channel.telegram.TelegramCallbackHandler`
BUG: `answerCallback(String callbackQueryId)` calls `HttpClient.newHttpClient()` per invocation. This creates a new thread pool, connection pool, and selector thread on every Telegram callback — they are never closed. Under any real callback rate this is a resource leak. Additionally, `sendAsync(...)` is called with `BodyHandlers.discarding()` and the `CompletableFuture` is completely discarded — failures are invisible, leaving Telegram's button spinner active permanently.
FIX: Promote the `HttpClient` to a `private static final` field initialized once (or inject an existing `@ApplicationScoped` `HttpClient` producer if one exists in the module — check first before creating a new static). Log at `WARN` level inside a `.exceptionally(...)` handler on the `CompletableFuture` so failures are observable.
TEST: Use a mock/stub HttpClient. Verify that across 100 `answerCallback` invocations, exactly one HttpClient instance is reused (not 100 created). Verify that a simulated `CompletableFuture` failure triggers a `log.warn` with the callbackQueryId.

--- PROMPT PHASE 80-A ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A4 ---

CLASS: `com.arcclaw.channel.telegram.TelegramCallbackHandler`
BUG: `formCommandText(BatchCallbackEvent event)` uses a non-exhaustive `if-instanceof` chain and returns `null` for any unrecognized `BatchCallbackEvent` subtype. Since `BatchCallbackEvent` is a sealed interface, new permits added to it will silently cause `formCommandText` to return `null`, leading to a `null` `commandText` being injected into `MessageContext` and eventually a NullPointerException or silent no-op in the reflex loop. `getSessionId(event)` correctly uses an exhaustive `switch` — `formCommandText` must match.
FIX: Replace the `if-instanceof` chain in `formCommandText` with an exhaustive `switch` expression over the sealed `BatchCallbackEvent` type — identical structural style to `getSessionId`. The compiler will then enforce exhaustiveness at build time for any future permit additions.
TEST: Verify all 4 current permits (`ApproveTool`, `DenyTool`, `ApproveAll`, `DenyAll`) return the correct command string. Add a compile-time-enforced note (comment in test) that a 5th permit added without updating this switch will cause a compile error — demonstrating the fix's enforcement property.

--- PROMPT PHASE 80-A ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A5 ---

CLASS: `com.arcclaw.channel.api.structured.SynthesisResult`
BUG: `@JsonInclude(JsonInclude.Include.NON_NULL)` is declared at record level, and `@JsonProperty(required = true)` is declared on `contributions` and `conflictSummary`. These annotations directly conflict: `NON_NULL` omits null fields during serialization, but `required = true` throws `MissingRequiredCreatorPropertiesException` during deserialization if those fields are absent. When the LLM synthesizer returns a valid single-domain answer with no multi-agent contributions (null contributions), Jackson throws, the `StructuredOutputEngine` propagates a `SCHEMA_ERROR` `AgentResult.Failed`, and Dattatreya's response is silently lost.
FIX: Remove `required = true` from `contributions` and `conflictSummary` — keep it only on `finalResolution` which is the only field that is semantically mandatory for all synthesis cases. Adjust `DattatreyaAgent.synthesize()` to handle `null` contributions gracefully with `result.contributions() != null ? result.contributions().size() : 0`.
TEST: Construct a JSON payload where `contributions` and `conflictSummary` are absent but `finalResolution` is present. Deserialize into `SynthesisResult` — assert no exception thrown and `finalResolution` is correctly populated. Also test the reverse: absent `finalResolution` must still throw.

--- PROMPT PHASE 80-A ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A6 ---

CLASS: `com.arcclaw.channel.telegram.TelegramAllowlistGuard`
BUG: When neither `arcclaw.channel.telegram.allowed-user-ids` nor any valid `arcclaw.channel.telegram.allowed-users` entry is configured, `isAllowed()` silently returns `true` (open access to all users). This is a security footgun: an accidental config omission (e.g., config file corruption, missing env var in production) opens the bot to the world with zero log output at runtime and no startup warning.
FIX: In the open-access branch, add `log.warn("SECURITY WARNING: Telegram allowlist is OPEN — no allowed-user-ids or allowed-users configured. All users are permitted. Set arcclaw.channel.telegram.allowed-user-ids to restrict access.")`. This must fire on every `isAllowed()` call in the open-access path so that log monitoring can alert on the condition. Add a `@PostConstruct void validateAllowlist()` that emits the same warning once at startup at `WARN` level.
TEST: Configure the guard with no allowed IDs and no allowed users. Call `isAllowed("anyuser", "anyname")`. Assert it returns `true` AND that the warning log was emitted. Assert the `@PostConstruct` warning fires during bean initialization.

--- PROMPT PHASE 80-A ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A7 ---

CLASS: `com.arcclaw.core.agent.reasoning.ChatHistoryAssembler`
BUG: `COMPLIANCE_OVERRIDE_PATTERN` silently drops AI messages from history with no audit log, no metric, and no debug visibility. A false-positive match (e.g., a legitimate LLM reasoning turn containing "going forward, I will only focus on...") causes the agent loop to lose its own prior reasoning, re-derive conclusions, repeat tool calls, and burn token budget — all invisibly. Operators cannot diagnose why an agent is looping.
FIX: Add `log.debug("ChatHistoryAssembler: filtered compliance-override message at history position {} sessionId={} preview='{}'", msgIndex, sessionId, aiText.substring(0, Math.min(80, aiText.length())))`. Also increment a Micrometer counter `arcclaw.history.compliance_filter` with tag `sessionId` when a message is dropped. Do NOT change the filtering logic itself in this component — that is a separate Phase 81 concern.
TEST: Build a `ChatHistoryAssembler` with a mocked `ChatMemoryStore` that returns one AI message matching the pattern. Call `assemble()`. Assert the message is excluded from the result. Assert the debug log was emitted (capture via log appender). Assert the Micrometer counter is incremented by 1.

--- PROMPT PHASE 80-A ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A8 ---

CLASS: `com.arcclaw.core.agent.persona.AgentPersonaRegistry`
BUG: The no-arg CDI proxy constructor sets `this.agents = Collections.emptyList()`. The `getPersonaPrompt()` method checks `if (agents == null)` — but `agents` will never be `null` via the no-arg path (it's an empty list), so the null check never fires. More critically, `agents` is typed as `Iterable<Agent>`, but the injected value is `Instance<Agent>`. In test environments where the no-arg constructor path is taken and code calls `agents.stream()`, this works only because `Collections.emptyList()` is also `Collection` — but any code that casts `agents` to `Instance<Agent>` (e.g., to call `isUnsatisfied()`) will throw `ClassCastException`. The `null` guard is a dead condition masking a real structural mismatch.
FIX: Change the no-arg constructor to set `this.agents = Collections::emptyIterator` (a proper `Iterable<Agent>` lambda), remove the `if (agents == null)` dead null-check throughout, and add a package-private `AgentPersonaRegistry(Iterable<Agent> agents)` constructor explicitly for tests — removing the ambiguity. Add a Javadoc comment on the field declaring: `// Never null; no-arg CDI proxy path yields empty iterable; inject path yields CDI Instance<Agent>`.
TEST: Instantiate `AgentPersonaRegistry` via the no-arg constructor. Call `getPersonaPrompt(AgentRoleEnum.COORDINATOR)`. Assert it returns the hardcoded fallback string without throwing. Assert the `agents == null` branch is dead by verifying it is never reached (coverage assertion or comment).

--- PROMPT PHASE 80-A ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A9 ---

CLASS: `com.arcclaw.core.agent.persona.AgentRoleRegistry`
BUG: `resolveByName(String roleName)` calls `AgentRoleEnum.fromString(roleName)` which returns `COORDINATOR` as a fallback for any unrecognized role string. So `resolveByName("nonexistent-role")` resolves to the coordinator `Agent` bean and returns `Optional.of(coordinatorAgent)` — a non-empty Optional for a role that does not exist. Any caller using `isPresent()` to guard specialist dispatch will silently use the coordinator for an unrecognized role name, producing wrong behavior with no error.
FIX: Add `AgentRoleEnum.fromStringOrNull(String roleName)` — a new static method that returns `null` (or `Optional.empty()` variant) instead of `COORDINATOR` fallback for unknown strings. Update `resolveByName` to use `Optional.ofNullable(AgentRoleEnum.fromStringOrNull(roleName)).flatMap(this::resolve)`. The existing `AgentRoleEnum.fromString` fallback behavior must not change (it is used by other callers that depend on the coordinator default).
TEST: Call `resolveByName("completely-unknown-role")`. Assert `Optional.empty()` is returned. Call `resolveByName("coordinator")`. Assert the coordinator `Agent` bean is returned. Call `resolveByName("coder")`. Assert the coder agent is returned.

--- PROMPT PHASE 80-A ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A10 ---

CLASS: `com.arcclaw.channel.telegram.TelegramAgentHandler`
BUG: `processPipeline()` contains `if ("Pipeline crash".equals(e.getMessage()))` — a sentinel
exception message string hardcoded into production code to suppress test-injected exceptions.
This is test-bleed into production: any real exception whose message happens to be
"Pipeline crash" (possible from downstream libraries or future refactors) is silently
swallowed as a `log.warn` instead of notifying the user via `sender.sendRaw`, constituting
a silent production failure with zero user notification and zero operator alert.
FIX: Remove the `"Pipeline crash"` sentinel check and the entire `if/else` branch split
entirely. The catch block becomes a single unconditional path:
  log.warn(e, "Uncaught exception in message pipeline for chat {}", context.getChatId());
  try {
      sender.sendRaw(context.getUser(), "Sorry, an internal error occurred...");
  } catch (Exception sendEx) {
      log.error(sendEx, "Failed to send error notification to chat {}", context.getChatId());
  }
In tests that previously relied on the sentinel to suppress noise: replace the thrown
`RuntimeException("Pipeline crash")` with a test-only `PipelineCrashException` declared
in `src/test/java` only — never in `src/main/java`. `PipelineCrashException` extends
`RuntimeException`. It is never referenced from production code. Tests can assert on it
being thrown through the pipeline without production suppression logic.
TEST 1 — Prove suppression is gone: mock `MessagePipeline` to throw
`new RuntimeException("Pipeline crash")`. Invoke `processPipeline()`. Assert
`sender.sendRaw(...)` IS called with the user error message — proving the exception is
no longer silently suppressed.
TEST 2 — Prove test isolation still works: declare `PipelineCrashException` in test scope.
Mock `MessagePipeline` to throw it. Invoke `processPipeline()`. Assert `sender.sendRaw`
IS called (same path — no special casing). Assert the exception type is
`PipelineCrashException` in the caught log output. This proves test-side usage works
without any production sentinel.

--- PROMPT PHASE 80-A ......Tier A - PRIORITIZATION MODEL ---

HIGH PRIORITY — P80-A (Production risk / Data correctness / Concurrency / Architecture blockers):

  P80-A1  Caffeine expireAfterAccess race → concurrent pipeline execution, session and
          checkpoint state corruption for same chatId
  P80-A2  truncateCb byte vs. char → silent mid-sessionId truncation, Telegram callback
          approval permanently no-ops, button spinner stuck
  P80-A3  HttpClient per-callback instantiation → unclosed thread pool and connection
          pool leak, sendAsync failures invisible to operators
  P80-A4  formCommandText non-exhaustive instanceof chain → null commandText injected
          into MessageContext, silent no-op or NPE when sealed interface gains new permit
  P80-A5  SynthesisResult @JsonInclude(NON_NULL) + @JsonProperty(required=true) direct
          conflict → Dattatreya silently returns SCHEMA_ERROR AgentResult.Failed for all
          single-domain synthesis tasks

MEDIUM PRIORITY — P80-B (Maintainability / Security posture / Observability):

  P80-A6  TelegramAllowlistGuard silent open-access on config omission → bot exposed to
          all users with zero warning, zero audit, zero startup alert
  P80-A7  ChatHistoryAssembler compliance filter silent message drop → agent looping
          invisibly, token budget burned, no Micrometer counter, no debug log
  P80-A8  AgentPersonaRegistry dead null-check masking structural constructor mismatch
          → test fragility, potential ClassCastException on Instance<Agent> cast
  P80-A9  AgentRoleRegistry resolveByName silent coordinator fallback for unknown roles
          → wrong specialist dispatched with isPresent() returning true for nonexistent role

LOW PRIORITY — P80-C (Code hygiene / Test bleed / Minor risks):

  P80-A10 "Pipeline crash" sentinel string in production processPipeline() → test concern
          in production code, real exceptions with that message silently suppressed

--- PROMPT PHASE 80-A ......Tier A - EXECUTION ORDER ---

Execute components in this exact order to minimize merge conflicts and ensure each
component compiles and tests independently before the next begins:

  A5 → A9 → A8 → A4 → A2 → A3 → A1 → A6 → A7 → A10

Rationale:
  A5  first — touches only SynthesisResult record in arcclaw-channel-api; zero downstream
      dependency; safest starting point
  A9  second — adds AgentRoleEnum.fromStringOrNull() to arcclaw-core; this new method is
      consumed by A8 so A9 must land first
  A8  third — updates AgentPersonaRegistry using fromStringOrNull(); depends on A9
  A4  fourth — replaces if-instanceof with switch in TelegramCallbackHandler; isolated
      to arcclaw-channel-telegram; no dependency on prior steps
  A2  fifth — fixes truncateCb in BatchHitlKeyboardBuilder; isolated to
      arcclaw-channel-telegram; no conflict with A4 (different class)
  A3  sixth — promotes HttpClient to static final in TelegramCallbackHandler; same class
      as A4 but different method; execute after A4 so both edits land in one review pass
      on TelegramCallbackHandler
  A1  seventh — fixes Caffeine TTL in TelegramAgentHandler; isolated class; no conflict
  A6  eighth — adds @PostConstruct and open-access warn to TelegramAllowlistGuard;
      isolated class
  A7  ninth — adds Micrometer counter and debug log to ChatHistoryAssembler; arcclaw-core;
      isolated method
  A10 last — removes sentinel from TelegramAgentHandler; same class as A1; execute last
      so both TelegramAgentHandler edits (A1 + A10) can be reviewed together in one pass

--- PROMPT PHASE 80-A ......Tier A - VERIFICATION GATE ---

After ALL 10 components are complete, run the full verification:

  ./mvnw clean verify -pl arcclaw-channel-api,arcclaw-channel-telegram,arcclaw-core -am

Expected outcome:
  BUILD SUCCESS
  Minimum 977 tests green (967 existing + 10 new, one per component minimum)
  Zero new compiler warnings introduced
  Zero Checkstyle violations if Checkstyle is active in the build

Per-component isolated verification (run after each component before proceeding to next):

  A1:  ./mvnw clean test -pl arcclaw-channel-telegram
           -Dtest=TelegramAgentHandlerCaffeineRaceTest -am
  A2:  ./mvnw clean test -pl arcclaw-channel-telegram
           -Dtest=BatchHitlKeyboardBuilderTruncateTest -am
  A3:  ./mvnw clean test -pl arcclaw-channel-telegram
           -Dtest=TelegramCallbackHandlerHttpClientTest -am
  A4:  ./mvnw clean test -pl arcclaw-channel-telegram
           -Dtest=TelegramCallbackHandlerFormCommandTest -am
  A5:  ./mvnw clean test -pl arcclaw-channel-api
           -Dtest=SynthesisResultDeserializationTest -am
  A6:  ./mvnw clean test -pl arcclaw-channel-telegram
           -Dtest=TelegramAllowlistGuardOpenAccessTest -am
  A7:  ./mvnw clean test -pl arcclaw-core
           -Dtest=ChatHistoryAssemblerComplianceFilterObservabilityTest -am
  A8:  ./mvnw clean test -pl arcclaw-core
           -Dtest=AgentPersonaRegistryNoArgConstructorTest -am
  A9:  ./mvnw clean test -pl arcclaw-core
           -Dtest=AgentRoleRegistryResolveByNameTest -am
  A10: ./mvnw clean test -pl arcclaw-channel-telegram
           -Dtest=TelegramAgentHandlerPipelineSentinelTest -am

--- PROMPT PHASE 80-A ......Tier A - ARCHITECTURE INVARIANT COMPLIANCE CHECKLIST ---

Before submitting each component for review, verify ALL boxes are checked:

  □ A1  — expireAfterWrite replaces expireAfterAccess; TTL computed from injected config
          not hardcoded; no new @ApplicationScoped mutable state introduced;
          existing Caffeine field name unchanged
  □ A2  — truncateCb uses getBytes(StandardCharsets.UTF_8).length for the >64 check;
          log level changed from WARN to ERROR; method remains package-private;
          no interface changes to BatchHitlKeyboardBuilder
  □ A3  — HttpClient is static final or @ApplicationScoped produced; NOT stored in a
          request-scoped or dependent-scoped bean; sendAsync has .exceptionally() handler
          logging at WARN with callbackQueryId; no synchronous blocking call introduced
  □ A4  — switch uses expression switch with -> style; all 4 current BatchCallbackEvent
          permits are covered; return type remains String (no Optional wrapping);
          compiler enforces exhaustiveness going forward
  □ A5  — SynthesisResult remains a record (no class conversion); @JsonInclude(NON_NULL)
          retained at record level; required=true removed from contributions and
          conflictSummary only; required=true retained on finalResolution;
          DattatreyaAgent.synthesize() null-guards contributions size access
  □ A6  — @PostConstruct method is void, no-arg, non-static; logs at WARN not INFO;
          isAllowed() open-access path also logs at WARN on every invocation;
          no behavior change to the allow/deny return values
  □ A7  — Micrometer counter name exactly "arcclaw.history.compliance_filter";
          tag key exactly "sessionId" lowercase; debug log includes sessionId and
          first 80 chars of aiText; filtering logic itself is NOT changed
  □ A8  — @Inject constructor signature unchanged; no-arg constructor remains protected;
          null check if (agents == null) removed; package-private test constructor
          added with Javadoc; field Javadoc added explaining dual-path contract
  □ A9  — AgentRoleEnum.fromString() existing fallback behavior is NOT changed;
          new fromStringOrNull() returns null for unknown strings;
          resolveByName() updated to use fromStringOrNull() via Optional.ofNullable;
          existing callers of fromString() are NOT touched
  □ A10 — PipelineCrashException exists ONLY in src/test/java; zero references in
          src/main/java; catch block in processPipeline() is a single unconditional
          path with no if/else on exception message; sentinel string literal
          "Pipeline crash" does not appear anywhere in src/main/java after fix

--- PROMPT PHASE 80-A ......Tier A - WHAT IS OUT OF SCOPE FOR THIS PHASE ---


You need the prompt only for the **8 new items (80-A11 through 80-A18)** that came from Grok/Gemini and were not already in my original Phase 80-A prompt (which covered A1–A10). Here it is:

***

```
Role: Senior Java Architect, Bug Fixer, Senior Java Developer
Task: Phase 80-A (Extension) — Fix 8 additional confirmed correctness defects
identified via cross-review (Grok + Gemini). All bugs below exist today in live
production code. No architectural changes. Surgical fixes only.

--- PROMPT PHASE 80-A-EXT ......Tier A ---

--- PROMPT PHASE 80-A-EXT ......Tier A - Architecture Context and Other References ---

Refer ArchitectureInvariants.md

1. Every fix must be accompanied by a test that proves the bug existed and now does not.
2. No new dependencies. No structural package changes.
3. All existing tests (967 + 10 from Phase 80-A components A1–A10) must remain green
   after every component.
4. Use static final Pattern for any new regex — never compiled inline.
5. All fixes must be safe under JDK 25 — no preview features.
6. Test commands always use `clean` to prevent stale class false-negatives.

--- PROMPT PHASE 80-A-EXT ......Tier A - IMPLEMENTATION REQUIREMENTS - Common Requirements ---

- Surgical fix only: change the minimal lines required to correct the defect.
- Every component is independently compilable and independently testable.
- No renaming, no package moves, no interface additions unless the fix strictly requires it.
- If a fix touches a class that also contains a separate unrelated bug from this list,
  fix only the component-specific defect in that component — do not fix ahead.
- All new test classes go in the same module as the class under fix.
- Test method names must follow: void bugName_condition_expectedOutcome().

--- PROMPT PHASE 80-A-EXT ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A11 ---

CLASSES: WorkspaceScopeHolder + ContextAwareExecutorService
         (and any Runnable/Callable wrapping utilities in the same package)
BUG: WorkspaceScopeHolder.wrap(Runnable) and ContextAwareExecutorService only capture
and propagate WORKSPACE_ID (sessionId) into virtual threads. They completely fail to
carry USER_ID, CHAT_ID, AUTONOMY_LEVEL, AGENT_ROLE, and CHANNEL_TYPE. Any parallel
tool execution or background task handed to a virtual thread silently loses user
identity and security clearance. ToolGuardInterceptor reading WorkspaceContext.autonomy()
inside a virtual thread gets null or the default SUPERVISED level, bypassing governance
entirely. Audit logs for parallel tool runs record under null/unknown user.
This is the most severe security boundary violation in the codebase — a governance bypass
on every parallel tool execution today.
Refer Architecture Invariants §2.2, §2.3, §2.4, §2.5 for the two-layer contract.
FIX: Identify every ScopedValue or ThreadLocal field in WorkspaceContext that carries
user identity and session security context (USER_ID, CHAT_ID, AUTONOMY_LEVEL,
AGENT_ROLE, CHANNEL_TYPE). In WorkspaceScopeHolder.wrap(Runnable) and in
ContextAwareExecutorService's task wrapping, capture ALL of these values from the
calling thread BEFORE submitting to the executor. Restore all of them inside each
virtual thread before the task body executes. Clear all of them in a finally block
after the task body completes. Do NOT change the ScopedValue binding contract for the
non-parallel (main thread) path — that path must remain unaffected.
Add Architecture Invariants §2.7 entry: "ALL WorkspaceContext fields (sessionId,
userId, chatId, autonomyLevel, agentRole, channelType) MUST be propagated when
wrapping Runnables or Callables for virtual thread execution. Propagating sessionId
alone is an invariant violation."
TEST 1 — Prove the gap existed: submit a Callable via ContextAwareExecutorService from
a bound WorkspaceContext (all 6 fields set). Inside the Callable, read all 6 fields
from WorkspaceContext. Assert all 6 are non-null and match the caller's values.
Without the fix this test must fail on the 5 non-sessionId fields.
TEST 2 — Prove governance is enforced: submit a parallel tool task from a context
where AUTONOMY_LEVEL = SUPERVISED. Inside the task, assert WorkspaceContext.autonomy()
== SUPERVISED (not null, not a default). This proves ToolGuardInterceptor will see
the correct level.

--- PROMPT PHASE 80-A-EXT ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A12 ---

CLASS: HitlService (arcclaw-app or arcclaw-core, whichever owns REST HITL resolution)
BUG: HitlService.resolve(taskId, approved, reason) constructs a fake MessageContext
and calls approveReflex.execute(..., "/approve", context) directly. The REST UI layer
is coupled to the text-parsing semantics of a chat channel command handler. ApproveReflex
is a CLI text parser — it is not a domain API. If ApproveReflex is ever updated to
require a specific tool name syntax (e.g., /approve <toolName>), the UI's injected
plain "/approve" will silently fail or reject valid approvals with no compile-time
warning. Additionally this is the root cause of Component A13 (blind batch approval)
because a bare "/approve" with no tool name argument approves ALL pending tools.
FIX: Introduce a package-private (or internal) domain method
CheckpointApprovalService.approveTool(String sessionId, String toolName, String reason)
and CheckpointApprovalService.denyTool(String sessionId, String toolName, String reason).
HitlService.resolve() must call these domain methods directly — never through a
Reflex. The Reflex path (CLI /approve command) must be updated to call the same
CheckpointApprovalService methods so there is exactly ONE approval code path.
Do NOT delete ApproveReflex — it must remain functional for Telegram/CLI users.
It must simply delegate to CheckpointApprovalService instead of containing the
approval logic itself.
TEST 1 — Prove the fake MessageContext path is gone: verify HitlService.resolve()
no longer creates a MessageContext or calls any Reflex.execute(). Use static analysis
assertion in the test (import the class, reflect on method body references, or simply
verify the new delegation via mock: CheckpointApprovalService.approveTool is called
with the correct sessionId and toolName).
TEST 2 — Prove CLI path still works: call ApproveReflex.execute() with "/approve
github" and assert it delegates to CheckpointApprovalService.approveTool(sessionId,
"github", ...) — proving Reflex is now a thin CLI adapter not a logic owner.

--- PROMPT PHASE 80-A-EXT ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A13 ---

CLASS: HitlService + CheckpointApprovalService (introduced in A12)
BUG: The web UI displays individual tools as separate PendingApprovalDto rows (e.g.,
"github" and "sandboxexec" as two separate approval buttons). But the current backend
maps any approval action to a blanket "/approve" which — per Invariant §7.21 — approves
ALL pending tools in the batch atomically. A user clicking "Approve" on "github" in
the UI intends to approve ONLY github. The backend approves both github AND sandboxexec.
This is a security hole: a user who would deny sandboxexec never gets the chance —
the click on a different tool grants it anyway.
Depends on: A12 must be complete first (CheckpointApprovalService must exist).
FIX: HitlService.resolve(taskId, toolName, approved, reason) — add toolName as a
required parameter. When toolName is non-null and non-blank, call
CheckpointApprovalService.approveTool(sessionId, toolName, reason) or
CheckpointApprovalService.denyTool(sessionId, toolName, reason) for that specific
tool only. When toolName is null or blank (bulk approve/deny from UI), call
CheckpointApprovalService.approveAll(sessionId, reason) or denyAll. The REST endpoint
signature must be updated to accept toolName as an optional query or path parameter.
The web UI must pass the specific toolName when the user clicks an individual tool
row — not a bulk approve. Bulk Approve All / Deny All buttons pass null toolName.
Add Architecture Invariants §7.34: "Web UI tool approval MUST be per-tool granular.
The REST HITL endpoint MUST accept toolName as a parameter. Passing no toolName is
valid only for bulk Approve All / Deny All operations."
TEST 1 — Single tool approve: set up a checkpoint with tools [github, sandboxexec].
Call HitlService.resolve(taskId, "github", true, "approved"). Assert only github
transitions to APPROVED in the checkpoint. Assert sandboxexec remains PENDING.
TEST 2 — Bulk approve: call HitlService.resolve(taskId, null, true, "approved").
Assert both github and sandboxexec transition to APPROVED.
TEST 3 — Security scenario: call HitlService.resolve(taskId, "github", true, ...).
Assert sandboxexec is NOT executed (remains PENDING, not in completed results).

--- PROMPT PHASE 80-A-EXT ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A14 ---

CLASS: BatchHitlKeyboardBuilder (parseCallback and buildKeyboard methods)
BUG: parseCallback(String callbackData) uses callbackData.split(":") and reads
parts[2] as the tool name. Telegram callback data format is:
BATCH_APPROVE:sessionId:toolName. MCP tools and future channel tools use namespaced
names with colons (e.g., github:search_repos, browser:navigate, browser:click).
Splitting on ":" yields parts[2] = "github" (not "github:search_repos"), matching
nothing in the pending tool list. The HITL process deadlocks — the tool can never
be approved or denied. This will break every Playwright browser tool approval
(Phase 82) on day one since browser tool names will follow the namespace pattern.
FIX: Change the callback data separator from ":" to "|" (pipe) throughout
BatchHitlKeyboardBuilder. The new format is:
  BATCH_APPROVE|sessionId|toolName
  BATCH_DENY|sessionId|toolName
  BATCH_APPROVE_ALL|sessionId
  BATCH_DENY_ALL|sessionId
Update BOTH buildKeyboard (callback data construction) and parseCallback (split and
parts indexing) to use "|" as separator. Pipe cannot appear in tool names, session
IDs, or Telegram callback action prefixes — it is safe as a separator.
Recompute byte lengths in truncateCb after separator change (this component works
alongside the A2 fix — the byte-count fix in A2 still applies, now using "|" format).
Update Architecture Invariants §7.25 to document the "|" separator format and add:
"The separator character MUST NOT be a character that can appear in tool names.
Colon ':' is FORBIDDEN as separator because MCP and browser tool namespaces use it."
TEST 1 — Namespaced tool approval: build keyboard with tool name "github:search_repos".
Parse the resulting callback string. Assert parseCallback returns
BatchCallbackEvent.ApproveTool with toolName == "github:search_repos" (full name,
not truncated at colon).
TEST 2 — Existing tool names: verify all current non-namespaced tool names (github,
manageschedule, scheduletask, sandboxexec) still parse correctly with the new separator.
TEST 3 — Regression: verify split(":") is no longer present anywhere in
BatchHitlKeyboardBuilder (grep/static assertion in test).

--- PROMPT PHASE 80-A-EXT ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A15 ---

CLASS: WebSocketSessionStore (arcclaw-channel-web)
BUG: WebSocketSessionStore uses a static or application-scoped ConcurrentHashMap to
track active WebSocket sessions keyed by sessionId. Sessions are added on connect but
are NEVER removed on disconnect or error. WebSocket clients reconnect on every browser
refresh, network blip, or tab reopen — each reconnect creates a new entry without
cleaning the old one. In a long-running deployment with regular browser usage, the
map grows without bound. Each entry retains a reference to the WebSocket Session
object (which holds I/O buffers, headers, and handler state). This is a classic
heap memory leak that will trigger OOM in production.
FIX: In the WebSocket endpoint class (annotated @ServerEndpoint or using Quarkus
WebSocket extension), add @OnClose and @OnError handler methods that call
WebSocketSessionStore.remove(sessionId). The sessionId must be extractable from the
WebSocket Session object (either from path params, query params, or a session
attribute set at @OnOpen time). If sessionId is not available in the close/error
handler, store a mapping of WebSocket Session.getId() → sessionId at @OnOpen time
in a separate ConcurrentHashMap (this secondary map must also be cleaned in @OnClose).
Additionally add a Caffeine-backed TTL eviction as a safety net:
expireAfterAccess(30 minutes) so that zombie entries from crashed clients that never
sent a clean close frame are also evicted.
TEST 1 — Prove the leak: create 100 WebSocket session entries in the store. Simulate
disconnect (call the @OnClose handler for each). Assert the store is empty after all
disconnects.
TEST 2 — Prove TTL safety net: add an entry, do NOT call @OnClose. Wait past the TTL
(use a short test TTL via config override). Assert the entry is evicted automatically.
TEST 3 — Prove reconnect: add session for sessionId "s1", disconnect, reconnect with
new WebSocket Session object for same sessionId "s1". Assert store contains exactly
1 entry for "s1" (old entry replaced, not accumulated).

--- PROMPT PHASE 80-A-EXT ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A16 ---

CLASS: SessionRegistry
BUG: SessionRegistry.resolveOrCreate uses computeIfAbsent(key, k -> new ReentrantLock())
on a ConcurrentHashMap<String, ReentrantLock> called userLocks. A lock is created for
every unique channelType:channelId:userId triplet that ever connects. The lock is NEVER
removed from the map — there is no eviction, no cleanup, no TTL. Each ReentrantLock
retains approximately 48 bytes of heap. A public-facing bot with 100,000 unique users
accumulates 100,000 lock objects (~4.8 MB) that are never GC'd because the map holds
strong references. At 1,000,000 unique users the leak is ~48 MB of unreclaimable heap.
More critically, the lock map key is a composite string — if the key format ever
changes (e.g., channel type prefix added), old keys are never cleaned, accelerating
the leak.
FIX: Replace the raw ConcurrentHashMap<String, ReentrantLock> with a Caffeine cache:
  Cache<String, ReentrantLock> userLocks = Caffeine.newBuilder()
      .expireAfterAccess(30, TimeUnit.MINUTES)
      .build();
Usage: userLocks.get(key, k -> new ReentrantLock()). This is the same Caffeine pattern
already used correctly in TelegramAgentHandler for processingFlags. Locks for inactive
users are automatically evicted after 30 minutes of no access. The resolveOrCreate
method behavior is unchanged — the lock is still acquired before the REQUIRES_NEW
transaction, released in finally.
TEST 1 — Prove the leak is gone: create locks for 1000 unique user keys via
resolveOrCreate. Simulate 30+ minutes passing (use a short test TTL via config override). Assert the Caffeine cache size has dropped to 0 (all entries evicted).
TEST 2 — Prove lock behavior is unchanged: call resolveOrCreate for the same userId
twice concurrently from two threads. Assert only one session is created (the lock
still serializes the REQUIRES_NEW transaction correctly). Assert no
UniqueConstraintException is thrown.
TEST 3 — Prove active locks are not evicted mid-use: acquire a lock, hold it for
5 seconds (simulating a slow DB write), assert the lock is NOT evicted during the
hold (Caffeine expireAfterAccess resets TTL on each access — the get() call that
returned the lock counts as an access).

--- PROMPT PHASE 80-A-EXT ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A17 ---

CLASS: DelegateToAgentTool (arcclaw-core, tool implementation)
        + DelegationRoutingProvider (arcclaw-core, routing)
        + DelegateApprovalGuard (arcclaw-core, governance — if it exists)
BUG: DelegateToAgentTool.execute(agent, goal) routes a task to a named sub-agent
(e.g., coordinator delegates to coder). The sub-agent executes inside its own
AgentLoop iteration with its own RoleDefinition.capabilities enforcement. However
there is NO validation that the target role's capabilities are a subset of or
compatible with the delegating role's capabilities at the point of delegation.
This creates a capability escalation path:
  Scenario A — A researcher role (capabilities: GITHUB, MEMORYSEARCH) delegates
  to coordinator (capabilities: GITHUB, SCHEDULETASK, MANAGESCHEDULE, MEMORYSEARCH,
  INGESTDOCUMENT, DELEGATETOAGENT). The coordinator now runs in a sub-loop with
  SCHEDULETASK and MANAGESCHEDULE — capabilities the researcher was never allowed.
  Scenario B — Any role that has DELEGATETOAGENT can delegate to synthesizer which
  has SCHEDULETASK and MANAGESCHEDULE, granting scheduling capability to any
  delegating role transitively.
  Scenario C — A skill-routed agent (base role RESEARCHER) delegates to coder which
  has SANDBOXEXEC — the skill never had sandbox capability but gets it via delegation.
This bypasses the governance layer entirely for delegated sub-agent tool calls.
FIX: In DelegateToAgentTool.execute(), before constructing the sub-agent loop,
validate that the target role's RoleDefinition.capabilities does not contain any
capability that is absent from the delegating role's RoleDefinition.capabilities.
If the target has capabilities the delegating role does not have, either:
  Option A (strict) — reject the delegation and return a tool observation:
  "Delegation to <targetRole> rejected: target role has capabilities
  [SANDBOXEXEC, SCHEDULETASK] not permitted for delegating role [researcher].
  Use a role with sufficient capabilities or request coordinator to delegate."
  Option B (bounded) — allow delegation but pass the delegating role's capability
  set as an execution mask to the sub-agent loop, so the sub-agent is constrained
  to the intersection of its own capabilities and the delegating role's capabilities.
Implement Option A (strict) as it is the safer default and simpler to reason about.
Option B can be added as a follow-on in Phase 82 if use cases require it.
Add Architecture Invariants §4.10: "DelegateToAgentTool MUST validate that the target
role's capabilities are a subset of the delegating role's capabilities before
constructing the sub-agent loop. Delegation that would grant the sub-agent capabilities
the parent role does not have MUST be rejected with a descriptive tool observation.
Transitive capability escalation via delegation chains is FORBIDDEN."
TEST 1 — Escalation rejected: set up a delegation from researcher (no SANDBOXEXEC)
to coder (has SANDBOXEXEC). Assert DelegateToAgentTool.execute() returns a tool
observation containing "Delegation rejected" and does NOT construct a sub-agent loop.
TEST 2 — Valid delegation permitted: set up a delegation from coordinator (has GITHUB,
SCHEDULETASK, MEMORYSEARCH) to researcher (has GITHUB, MEMORYSEARCH — a strict subset).
Assert the delegation proceeds normally (no rejection, sub-agent loop constructed).
TEST 3 — Transitive escalation blocked: researcher delegates to coordinator which
would then have SCHEDULETASK. Assert the first delegation (researcher → coordinator)
is rejected at the DelegateToAgentTool level before any sub-loop runs.
TEST 4 — Skill route escalation blocked: a skill-routed agent (base RESEARCHER,
no SANDBOXEXEC) attempts to delegate to coder (has SANDBOXEXEC). Assert rejection.

--- PROMPT PHASE 80-A-EXT ......Tier A - IMPLEMENTATION REQUIREMENTS - Component A18 ---

CLASS: arcclaw-channel-web module — WebChatEndpoint, ChatStreamResource,
       WebChannelPlugin (or equivalent web channel entry point classes)
BUG: arcclaw-channel-web directly references arcclaw-core classes WorkspaceContext,
AgentLoop, and ReflexRegistry instead of routing exclusively through the ChannelPlugin
SPI contract. This is the same channel abstraction violation that was fought against
and corrected for Telegram in P67–P68. The web channel has re-introduced it.
Consequences:
  1. Adding a third channel (Discord, Slack, or the arcclaw-onboarding backend in
     Phase 81) will copy this broken pattern because the web channel is now the
     reference implementation for non-Telegram channels.
  2. Any change to WorkspaceContext, AgentLoop, or ReflexRegistry internals requires
     touching the web channel module — cross-module coupling that should not exist.
  3. Core unit tests cannot mock the web channel behavior without dragging in web
     channel dependencies — test isolation is broken.
Refer Architecture Invariants §1.2, §1.3, §1.4, §4.5.
FIX: Audit every import in arcclaw-channel-web. For each direct reference to:
  - WorkspaceContext → replace with WorkspaceScopeHolder (the correct layer for
    channel/reflex dispatch path per Invariant §2.4) or pass values through
    ChannelMessage / MessageContext which are SPI-safe types.
  - AgentLoop → remove entirely. Web channel must call MessagePipeline (the SPI
    entry point) not AgentLoop directly. MessagePipeline is already the correct
    channel-facing abstraction — Telegram uses it correctly.
  - ReflexRegistry → remove entirely. Web channel must route through
    CommandDispatchHandler (the SPI entry point for reflex dispatch) not call
    ReflexRegistry directly.
After the fix, arcclaw-channel-web pom.xml must NOT have a compile-scope dependency
on any arcclaw-core internal class outside the defined SPI packages
(com.arcclaw.core.pipeline, com.arcclaw.core.reflex public API,
com.arcclaw.channel.api). Add a Maven Enforcer rule or ArchUnit test to assert this
going forward.
TEST 1 — Compile-scope dependency check: write an ArchUnit test in
arcclaw-channel-web that asserts no class in com.arcclaw.channel.web imports from
com.arcclaw.core.agent.loop, com.arcclaw.core.agent.reasoning,
com.arcclaw.core.state, or com.arcclaw.core.scope directly. This test will FAIL
before the fix and PASS after — proving the violation existed and is corrected.
TEST 2 — MessagePipeline delegation: mock MessagePipeline in a web channel test.
Send a message through WebChatEndpoint or ChatStreamResource. Assert
MessagePipeline.process(MessageContext) is called with the correct chatId, userId,
and message content — proving the web channel now uses the correct SPI entry point.
TEST 3 — SPI contract regression: after removing direct AgentLoop references, run
an integration test that sends a full message through the web channel end-to-end
(WebChatEndpoint → MessagePipeline → AgentLoop → response). Assert the response
is delivered correctly — proving the SPI refactor did not break the functional path.

--- PROMPT PHASE 80-A-EXT ......Tier A - PRIORITIZATION MODEL ---

HIGH PRIORITY — P80-A-EXT (Production risk / Security / Memory safety / Phase 82 blockers):

  A11  ScopedValue drops user/autonomy/channel in virtual threads → governance bypass
       on every parallel tool execution today — SECURITY CRITICAL
  A13  HitlService blind batch approval → sandboxexec approved when user intended
       only github — SECURITY CRITICAL
  A14  parseCallback split(":") → MCP and browser:* tools deadlock HITL forever —
       PHASE 82 BLOCKER, fix now
  A17  DelegateToAgentTool capability escalation → any role can access any tool
       transitively via delegation — SECURITY CRITICAL
  A12  HitlService fake MessageContext → wrong abstraction, root cause of A13 —
       must precede A13
  A15  WebSocketSessionStore no disconnect cleanup → unbounded heap OOM in production
  A16  SessionRegistry.userLocks never removed → unbounded ReentrantLock heap growth
  A18  arcclaw-channel-web bypasses ChannelPlugin SPI → must fix before Phase 81
       onboarding backend copies the broken pattern — PHASE 81 BLOCKER

--- PROMPT PHASE 80-A-EXT ......Tier A - EXECUTION ORDER ---

Execute components in this exact order:

  A12 → A13 → A11 → A17 → A14 → A15 → A16 → A18

Rationale:
  A12 first — introduces CheckpointApprovalService which A13 depends on.
              Must land and be green before A13 begins.
  A13 second — depends on CheckpointApprovalService from A12.
               Adds toolName parameter to HitlService and REST endpoint.
  A11 third — touches WorkspaceScopeHolder and ContextAwareExecutorService in core.
              Isolated from A12/A13 (different module layer) but do after web-layer
              fixes so context propagation fix does not mask web-layer test failures.
  A17 fourth — touches DelegateToAgentTool in core. Isolated. No dependency on A11
               but shares core module — do after A11 so both core changes land in
               one review pass.
  A14 fifth — touches BatchHitlKeyboardBuilder. Isolated to channel-telegram module.
              Must be done before A18 because the web channel HITL path may reference
              the same callback parsing logic indirectly.
  A15 sixth — touches WebSocketSessionStore in channel-web module. Isolated.
  A16 seventh — touches SessionRegistry in core or app module. Isolated.
  A18 last — the SPI compliance refactor touches multiple classes in channel-web.
             Do last because it is the widest change in this set and benefits from
             all prior fixes being green first.

--- PROMPT PHASE 80-A-EXT ......Tier A - VERIFICATION GATE ---

After ALL 8 components are complete, run the full verification:

  ./mvnw clean verify -pl arcclaw-channel-api,arcclaw-channel-telegram,
      arcclaw-channel-web,arcclaw-core,arcclaw-app -am

Expected outcome:
  BUILD SUCCESS
  Minimum 985 tests green
  (967 original + 10 from Phase 80-A components A1–A10 + 8 new minimum from A11–A18
   — most components have 3–4 tests so realistic target is 993–997)
  Zero new compiler warnings
  ArchUnit test for A18 SPI compliance passes

Per-component isolated verification:

  A11: ./mvnw clean test -pl arcclaw-core
           -Dtest=ContextAwareExecutorScopedValuePropagationTest -am
  A12: ./mvnw clean test -pl arcclaw-core,arcclaw-app
           -Dtest=HitlServiceAbstractionTest -am
  A13: ./mvnw clean test -pl arcclaw-core,arcclaw-app
           -Dtest=HitlServicePerToolApprovalTest -am
  A14: ./mvnw clean test -pl arcclaw-channel-telegram
           -Dtest=BatchHitlKeyboardBuilderNamespacedToolTest -am
  A15: ./mvnw clean test -pl arcclaw-channel-web
           -Dtest=WebSocketSessionStoreDisconnectCleanupTest -am
  A16: ./mvnw clean test -pl arcclaw-core,arcclaw-app
           -Dtest=SessionRegistryLockEvictionTest -am
  A17: ./mvnw clean test -pl arcclaw-core
           -Dtest=DelegateToAgentToolCapabilityEscalationTest -am
  A18: ./mvnw clean test -pl arcclaw-channel-web
           -Dtest=WebChannelSpiComplianceArchUnitTest -am

--- PROMPT PHASE 80-A-EXT ......Tier A - ARCHITECTURE INVARIANT COMPLIANCE CHECKLIST ---

Before submitting each component for review, verify ALL boxes are checked:

  □ A11 — ALL 6 WorkspaceContext fields propagated (sessionId, userId, chatId,
           autonomyLevel, agentRole, channelType); finally block clears ALL 6;
           non-parallel main thread path is NOT touched; new Invariant §2.7 added
           to ArchitectureInvariants.md
  □ A12 — CheckpointApprovalService introduced with approveTool/denyTool/approveAll/
           denyAll methods; ApproveReflex still exists and still works for CLI path;
           HitlService no longer creates MessageContext or calls any Reflex.execute();
           single approval code path enforced
  □ A13 — HitlService.resolve() accepts toolName as optional parameter; null toolName
           = bulk approve/deny; non-null toolName = per-tool; REST endpoint updated;
           new Invariant §7.34 added to ArchitectureInvariants.md
  □ A14 — Separator changed from ":" to "|" in BOTH buildKeyboard and parseCallback;
           split(":") does NOT appear anywhere in BatchHitlKeyboardBuilder after fix;
           Invariant §7.25 updated with "|" format and colon-forbidden note;
           A2 byte-count fix (from Phase 80-A) still applies with new format
  □ A15 — @OnClose and @OnError handlers call WebSocketSessionStore.remove(sessionId);
           Caffeine TTL safety net added with expireAfterAccess(30 min);
           secondary sessionId lookup map (if needed) also cleaned in @OnClose
  □ A16 — Raw ConcurrentHashMap<String, ReentrantLock> replaced with Caffeine cache;
           expireAfterAccess(30 min); resolveOrCreate behavior is functionally
           identical — only the eviction mechanism changes; no change to
           REQUIRES_NEW transaction boundary
  □ A17 — Capability validation in DelegateToAgentTool.execute() fires BEFORE
           sub-agent loop construction; strict Option A (reject) implemented;
           rejection message includes delegating role name, target role name, and
           the specific excess capabilities; new Invariant §4.10 added to
           ArchitectureInvariants.md
  □ A18 — Zero direct imports of AgentLoop, ReflexRegistry, or WorkspaceContext in
           arcclaw-channel-web after fix; ArchUnit test asserts this and is part of
           the permanent test suite (not a one-off); MessagePipeline and
           CommandDispatchHandler used as the correct SPI entry points;
           Maven compile-scope dependency on core internals removed from pom.xml

