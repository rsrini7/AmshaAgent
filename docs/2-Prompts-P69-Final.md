=====
PROMPT — PHASE 69-A: Critical Correctness, Security & Regression Fixes
=====

Role: Senior Java Architect, Bug Fixer & Senior Java Developer
Task: Phase 69-A — Fix 10 confirmed production defects in P68 live code.
All bugs confirmed to exist in P68 source. No architectural changes.
Surgical fixes only.

Architecture Context:
  Refer @Architecture_Invariants.md (mandatory — added in P68).

Common Requirements:
  - Every fix accompanied by a test proving the bug existed and no longer does.
  - No new dependencies. No structural package changes.
  - All existing 877+ tests must remain green after each component.
  - Use static final Pattern for any new regex — NEVER compiled inline.
  - All fixes safe under JDK 25, no preview features.
  - Test commands: mvn clean verify -pl <module>

──────────────────────────────────────────────────────────────────────────
Component A1 — Fix looksLikeNaturalLanguage() Regression
  (SOURCE-VERIFIED in EngineStepHandler.java)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/EngineStepHandler.java

BUG (P68 code — confirmed):
  private boolean looksLikeNaturalLanguage(String text) {
      if (text == null || text.isBlank()) return false;
      char first = text.stripLeading().charAt(0);
      if (first == '{' || first == '[') return false;
      return text.contains(" ");  // ← ONLY CHECK
  }
The P67 version checked for both space AND period. P68 removed the
period check. Now "Rate limit exceeded for requests", "Invalid API key
detected", "Tool execution error: permission denied" all return true
and surface as the agent's final answer to the user.
looksLikeNaturalLanguage() is called on failed_generation content from
Anthropic tool_use_failed errors — raw LLM error payload, not a user answer.

FIX — Replace the method entirely:
  private static final Pattern NATURAL_LANGUAGE_GATE =
      Pattern.compile("^[A-Za-z].*\\s.*[.!?]\\s*$", Pattern.DOTALL);

  private boolean looksLikeNaturalLanguage(String text) {
      if (text == null || text.length() < 20) return false;
      String trimmed = text.strip();
      char first = trimmed.charAt(0);
      if (first == '{' || first == '[' || first == '<') return false;
      String lower = trimmed.toLowerCase(Locale.ROOT);
      if (lower.contains("error") || lower.contains("exception") ||
          lower.contains("invalid") || lower.contains("failed") ||
          lower.contains("limit") || lower.contains("permission") ||
          lower.contains("unauthorized") || lower.contains("code\":")) return false;
      String[] tokens = trimmed.split("\\s+");
      return tokens.length >= 5 && NATURAL_LANGUAGE_GATE.matcher(trimmed).matches();
  }

TEST: EngineStepHandlerNaturalLanguageTest
  assertFalse: "Rate limit exceeded for requests"
  assertFalse: "Invalid API key detected"
  assertFalse: "Tool execution error: permission denied"
  assertFalse: "{\"error\": \"Bad request\", \"code\": 400}"
  assertFalse: "Done."
  assertTrue:  "The current deployment uses Kubernetes with three replicas."
  assertTrue:  "I analyzed the repository and found no critical vulnerabilities."

──────────────────────────────────────────────────────────────────────────
Component A2 — Fix ChannelType Reference Equality Security Bypass
  (SOURCE-VERIFIED in AgentDispatchHandler.java — NOTE: Claude.ai prompt
   incorrectly attributed this to SessionResolutionHandler; actual location
   is AgentDispatchHandler)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../pipeline/handlers/AgentDispatchHandler.java

BUG: ChannelType is a record. Records implement value equality via .equals(),
not ==. Any ChannelType constructed via ChannelType.fromString("telegram")
is a distinct JVM reference. The Telegram→SUPERVISED autonomy cap silently
fails for any non-constant ChannelType instance.

CURRENT (wrong):
  if (level == AutonomyLevel.FULL
      && context.getUser().channel() == ChannelType.TELEGRAM) {

FIX:
  if (level == AutonomyLevel.FULL
      && ChannelType.TELEGRAM.equals(context.getUser().channel())) {

Audit: grep the entire codebase for == ChannelType. and ChannelType. ==
and fix every occurrence.

TEST: AgentDispatchHandlerAutonomyCapTest
  - Construct ChannelUser using ChannelType.fromString("telegram") (not constant).
    With AutonomyLevel.FULL → assert resolved level is SUPERVISED.
  - Constant-based path also results in SUPERVISED (regression guard).
  - Non-Telegram channel with FULL autonomy is NOT capped.

──────────────────────────────────────────────────────────────────────────
Component A3 — Fix isDenial Substring False-Positive
  (SOURCE-VERIFIED in CheckpointResumeHandler.java)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/CheckpointResumeHandler.java

BUG (confirmed):
  boolean isDenial = goal != null && goal.toLowerCase().contains("denial");

Any user goal containing the word "denial" (insurance denial analysis,
access denial, denial of service research) silently cancels the tool
execution and sets CheckpointStatus.FAILED immediately after the user
already approved. The DenyReflex was already refactored to use
resumeSignal as the trigger, so this substring check is stale/incorrect.

FIX — Add sentinel to AgentSentinels.java:
  public static final String GOAL_DENY_APPROVED = "DENY_APPROVED_SENTINEL_V1";

In CheckpointResumeHandler.java — replace substring check:
  // OLD (wrong):
  boolean isDenial = goal != null && goal.toLowerCase().contains("denial");

  // NEW (correct):
  boolean isDenial = AgentSentinels.GOAL_DENY_APPROVED.equals(goal);

Update DenyReflex.handleCronDenial() to set goal to
AgentSentinels.GOAL_DENY_APPROVED (not GOAL_RESUME_DENIED) when
constructing the denial AgentLoop call.

TEST: CheckpointResumeHandlerTest
  - Goal "analyze insurance denial letters" with AWAITING_APPROVAL → tool executes.
  - Goal "claim denial analysis summary" → no cancellation.
  - Goal exactly AgentSentinels.GOAL_DENY_APPROVED → denial fires.
  - Goal null → no NPE, no denial.

──────────────────────────────────────────────────────────────────────────
Component A4 — Fix extractFailedGeneration Unicode Escape Handling
  (SOURCE-VERIFIED in EngineStepHandler.java)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/EngineStepHandler.java

BUG (confirmed): The hand-rolled JSON parser handles \n and \t but the
escape branch has no case for 'u', so \uXXXX sequences produce literal
"u" + hex digits. Multilingual sessions (Hindi, Arabic, Chinese) return
garbled text when recovered from tool_use_failed errors.

FIX — Replace manual parser with Jackson (already a project dependency
via quarkus-rest-jackson):

  @Inject
  com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  private String extractFailedGeneration(String rawError) {
      if (rawError == null) return null;
      try {
          int start = rawError.indexOf('{');
          if (start < 0) return null;
          com.fasterxml.jackson.databind.JsonNode root =
              objectMapper.readTree(rawError.substring(start));
          com.fasterxml.jackson.databind.JsonNode node =
              root.path("failed_generation");
          if (node.isMissingNode() || !node.isTextual()) return null;
          String result = node.asText().strip();
          return result.isBlank() ? null : result;
      } catch (Exception e) {
          log.debugf("extractFailedGeneration parse failed: %s", e.getMessage());
          return null;
      }
  }

TEST: EngineStepHandlerExtractFailedGenerationTest
  - Input with "failed_generation": "Voil\u00e0 les r\u00e9sultats"
    → extracted equals "Voilà les résultats"
  - Input with \n embedded → newline decoded correctly.
  - Input with no failed_generation key → null.
  - Malformed JSON input → null gracefully.

──────────────────────────────────────────────────────────────────────────
Component A5 — Fix AuditHashUtil.getHmacKey() Internal Array Exposure
  (Security — confirmed pattern in AuditHashUtil.java)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../audit/AuditHashUtil.java

BUG: getHmacKey() returns the internal hmacKey byte[] reference directly.
Any caller using Arrays.fill(key, (byte) 0) — standard JCA practice —
corrupts the master HMAC key. All subsequent audit events silently
produce wrong HMACs. The audit chain fails verification without any
exception being thrown.

FIX — Return a defensive copy:
  private byte[] getHmacKey() {
      byte[] result = hmacKey;
      if (result == null) {
          synchronized (this) {
              result = hmacKey;
              if (result == null) {
                  // ... existing init logic ...
                  hmacKey = result;
              }
          }
      }
      return result.clone();  // ← DEFENSIVE COPY — never expose internal reference
  }

TEST: AuditHashUtilKeyIsolationTest
  - Call getHmacKey() twice → key1, key2.
  - Arrays.fill(key1, (byte) 0).
  - Assert key2 is not zeroed (reference isolation confirmed).
  - Assert subsequent hmacHex("test") produces a consistent non-zero result.

──────────────────────────────────────────────────────────────────────────
Component A6 — Fix AuditEvent.securityEvent() Records as FINISH Outcome
  (Confirmed by code pattern in AuditEvent.java)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../audit/AuditEvent.java
      arcclaw-core/.../audit/AuditOutcome.java

BUG: All security events (vault access, PII detection, injection detection,
checkpoint creation) are recorded with AuditOutcome.FINISH. Compliance
queries for security-specific outcomes miss all security events entirely.

FIX:
  Step 1 — Add to AuditOutcome enum:
    SECURITY_EVENT   // vault, PII, injection, checkpoint events

  Step 2 — In AuditEvent.securityEvent():
    e.outcome = AuditOutcome.SECURITY_EVENT;

  Step 3 — Update TelemetryAuditService.resolveLoopEventType():
    Add case for SECURITY_EVENT to prevent MatchException on exhaustive
    switch. Return a suitable event type (e.g., EventType.SECURITY).

  Step 4 — Update AuditVerifier chain checks to permit SECURITY_EVENT.

TEST:
  - AuditEvent.securityEvent(...) produces outcome == SECURITY_EVENT.
  - AuditEvent.of(...) loop events still produce FINISH/ERROR/etc.
  - TelemetryAuditService.resolveLoopEventType() does not throw for any
    AuditOutcome value.
  - HMAC chain verification passes for SECURITY_EVENT records.

──────────────────────────────────────────────────────────────────────────
Component A7 — Fix StructuredRetryLoop Budget Gate Bypass on Warning Recovery
  (Architecture Invariant 3.7 violation — confirmed in StructuredRetryLoop.java)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../structured/StructuredRetryLoop.java

BUG: When BudgetWarningException fires on attempt 1, both catch blocks call
call.get() directly, bypassing executeWithBudgetGate(). If session is at
82% and the recovery call costs 20% more, budget exceeds 100% silently.

CURRENT (wrong):
  } catch (BudgetWarningException e1) {
      log.warnf(...);
      return call.get();  // ← no budget gate
  }

FIX — Replace ALL bare call.get() in BudgetWarningException catch blocks:
  } catch (BudgetWarningException e1) {
      log.warnf("Budget warning for session %s during %s: %s",
                sessionId, operationName, e1.getMessage());
      return executeWithBudgetGate(sessionId, call);  // ← gate enforced
  }

Apply to BOTH catch blocks (outer and inner retry).

TEST: StructuredRetryLoopBudgetGateTest
  - Session at 85% → BudgetWarningException on attempt 1 →
    recovery call hits assertWithinBudget() → if session at 101%
    throws BudgetExceededException (not silent success).
  - Session at 82% with room → warning absorbed, call succeeds normally.

──────────────────────────────────────────────────────────────────────────
Component A8 — Fix Double-Acknowledgment Race in TelegramAgentHandler
  (SOURCE-VERIFIED via concurrency test in TelegramAgentHandlerTest.java)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-channel-telegram/.../TelegramAgentHandler.java

BUG: ⏳ is sent BEFORE the lock check. When second message arrives while
first is processing, user receives ⏳ immediately, then ⏳ "Still processing"
— two consecutive bot messages before any work starts.

FIX — Move ⏳ to AFTER successful lock acquisition:
  AtomicBoolean flag = processingFlags.get(chatId, id -> new AtomicBoolean(false));
  if (!flag.compareAndSet(false, true)) {
      sender.sendRaw(user, "⏳ Still processing your previous message. Please wait.");
      return;
  }
  // Lock acquired — NOW send acknowledgment
  if (text != null && !text.trim().startsWith("/")) {
      sender.sendRaw(user, "⏳");
  }

TEST: TelegramAgentHandlerConcurrencyTest
  - Send message 1 for chatId (pipeline artificially delayed).
  - Send message 2 immediately after.
  - Assert: exactly ONE ⏳ total for the rejected second message.
  - Assert: no double ⏳ before the rejection message.

──────────────────────────────────────────────────────────────────────────
Component A9 — Fix WorkspaceContext.channelType() TELEGRAM Default
  (Violates Architecture_Invariants.md §1.4 added in THIS same P68)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../scope/WorkspaceContext.java

BUG: Architecture_Invariants.md §1.4 states "Any fallback to Telegram is
LEGACY and must be removed, not copied." Yet channelType() returns
"TELEGRAM" when unbound. New channels silently resolve to Telegram plugin.

FIX:
  Step 1 — Optional-returning form:
    public static Optional<String> channelType() {
        if (CHANNEL_TYPE.isBound() && CHANNEL_TYPE.get() != null) {
            return Optional.of(CHANNEL_TYPE.get());
        }
        return Optional.empty();
    }

  Step 2 — Strict variant:
    public static String requireChannelType() {
        return channelType().orElseThrow(() ->
            new IllegalStateException(
                "CHANNEL_TYPE not bound — violates Architecture_Invariants.md §1.4. " +
                "Ensure all channel entry points bind ChannelType via " +
                "WorkspaceContext.run(..., channelType, ...)."));
    }

  Step 3 — Migrate call sites:
    - ChannelPluginRegistry lookups → requireChannelType()
    - Notification dispatchers → requireChannelType()
    - Logging-only usage → channelType().orElse("UNBOUND")

TEST: WorkspaceContextChannelTypeTest
  - channelType() returns Optional.empty() when not bound.
  - requireChannelType() throws with "Architecture_Invariants" in message.
  - Both return correct value when bound.

──────────────────────────────────────────────────────────────────────────
Component A10 — Fix msg.getFrom() NPE for Forwarded Telegram Messages
──────────────────────────────────────────────────────────────────────────

File: arcclaw-channel-telegram/.../TelegramAgentHandler.java

BUG: String userId = String.valueOf(msg.getFrom().getId()) — getFrom() is
not null-checked. Telegram forwarded messages, channel posts, bot-relay
scenarios produce updates where from == null → NPE → Camel may re-deliver
→ loop starts.

FIX: Guard after chatId is resolved:
  if (msg.getFrom() == null) {
      log.warnf("Dropped message with null 'from' in chat %s " +
                "(forwarded/channel post — no user identity)", chatId);
      return;
  }
  String userId = String.valueOf(msg.getFrom().getId());

TEST: TelegramAgentHandlerNullFromTest
  - getChat() non-null, getFrom() null → no NPE.
  - sender.sendRaw() was not called.
  - WARN log emitted with chatId.


=====
PROMPT — PHASE 69-B: Reliability, Threading, Observability & Memory Safety
=====

Role: Senior Java Architect & Senior Java Developer
Task: Phase 69-B — Fix reliability gaps, threading issues, observability
gaps, and memory leaks. No agent behavior changes. All tests remain green.

Architecture Context: Refer @Architecture_Invariants.md

Common Requirements: same as Phase 69-A.

──────────────────────────────────────────────────────────────────────────
Component B1 — Add WARN Log for Dropped Parallel Tool Calls
  (SOURCE-VERIFIED: .getFirst() in ReasoningStepParser.java)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../reasoning/ReasoningStepParser.java

BUG: aiMessage.toolExecutionRequests().getFirst() silently drops all tool
calls beyond the first. Claude 3.5, GPT-4o, Gemini 2.0 all support
parallel tool use. Agent reasons from corrupted partial-observation state.
NOTE: Full parallel execution is Phase 70+. This fix makes data loss VISIBLE.

FIX:
  List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
  if (requests.size() > 1) {
      log.warnf("[PARALLEL-TOOLS] session=%s role=%s: LLM issued %d parallel " +
                "tool calls. Only first ('%s') will execute. Calls 2..%d dropped. " +
                "Parallel tool execution not yet supported — tracked for Phase 70.",
                sessionId, agentRole, requests.size(),
                requests.getFirst().name(), requests.size());
  }
  ToolExecutionRequest req = requests.getFirst();

TEST: ReasoningStepParserParallelToolTest
  - Mock ChatResponse with 3 ToolExecutionRequests.
  - Assert returned step uses first tool name.
  - Assert WARN log contains "parallel tool calls" and count.

──────────────────────────────────────────────────────────────────────────
Component B2 — Add Telegram Message Chunking at 4096 Characters
──────────────────────────────────────────────────────────────────────────

File: arcclaw-channel-telegram/.../TelegramChannelSender.java

BUG: Telegram rejects messages > 4096 chars with HTTP 400. Long agent
responses (code reviews, research, summaries) silently fail — user
receives nothing.

FIX:
  private static final int TELEGRAM_MAX_LENGTH = 4096;

  private void sendRaw(String chatId, String text) {
      if (text == null || text.isBlank()) {
          log.warnf("Attempted to send empty message to %s — skipping", chatId);
          return;
      }
      if (text.length() <= TELEGRAM_MAX_LENGTH) {
          producerTemplate.sendBodyAndHeader(
              "telegram:bots", text, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
      } else {
          List<String> chunks = splitAtWordBoundary(text, TELEGRAM_MAX_LENGTH);
          log.infof("TELEGRAM-SEND splitting %d-char response into %d chunks for %s",
              text.length(), chunks.size(), chatId);
          for (String chunk : chunks) {
              producerTemplate.sendBodyAndHeader(
                  "telegram:bots", chunk, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
          }
      }
  }

  private List<String> splitAtWordBoundary(String text, int maxLen) {
      List<String> parts = new ArrayList<>();
      while (text.length() > maxLen) {
          int split = text.lastIndexOf(' ', maxLen);
          if (split <= 0) split = maxLen;
          parts.add(text.substring(0, split));
          text = text.substring(split).stripLeading();
      }
      if (!text.isBlank()) parts.add(text);
      return parts;
  }

TEST: TelegramChannelSenderChunkingTest
  - 9000-char string → producerTemplate called exactly 3 times.
  - No chunk exceeds 4096 characters.

──────────────────────────────────────────────────────────────────────────
Component B3 — Clear WorkspaceScopeHolder Bridge in processPipeline finally
──────────────────────────────────────────────────────────────────────────

File: arcclaw-channel-telegram/.../TelegramAgentHandler.java

BUG: processPipeline() finally block calls requestContext.terminate() but
does NOT call WorkspaceScopeHolder.clear(). ManagedExecutor thread pool
threads carry stale session IDs in ThreadLocal bridge indefinitely.
Under high concurrent load, stale bridge interferes with active
ScopedValue resolution causing subtle @WorkspaceScoped resolution failures.

FIX (in processPipeline finally block):
  } finally {
      WorkspaceScopeHolder.clear();  // ← ADD before thread returns to pool
      requestContext.terminate();
  }

TEST: TelegramAgentHandlerBridgeCleanupTest
  - Submit task via ManagedExecutor with bridge pre-set to "session-X".
  - After processPipeline() completes, assert
    WorkspaceScopeHolder.getIfActive() == null.

──────────────────────────────────────────────────────────────────────────
Component B4 — Fix MemorySearchTool Returns success on Null/Blank Query
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../tools/MemorySearchTool.java

BUG:
  if (query == null || query.isBlank()) {
      return ToolResponse.success("No query provided");  // ← misleading
  }
LLM receives status SUCCESS with content "No query provided" —
indistinguishable from a legitimate empty-result search. LLM continues
reasoning as if memory was searched successfully → hallucinated memory state.

FIX:
  if (query == null || query.isBlank()) {
      return ToolResponse.failure(400, "query parameter is required for memory_search");
  }

TEST:
  - params = {} (no query) → ToolResponse status 400.
  - params = {"query": "   "} → status 400.
  - params = {"query": "kubernetes"} with mocked repo → succeeds (regression).

──────────────────────────────────────────────────────────────────────────
Component B5 — Fix DotenvConfigSourceProvider export KEY=VALUE Handling
──────────────────────────────────────────────────────────────────────────

File: arcclaw-app/.../config/DotenvConfigSourceProvider.java

BUG: Lines formatted as "export TELEGRAM_BOT_TOKEN=abc123" store key as
"export TELEGRAM_BOT_TOKEN" — silently losing all secrets in .env files
generated by shell scripts or documentation templates.

FIX: After key extraction:
  String key = parts[0].trim();
  if (key.toLowerCase(java.util.Locale.ROOT).startsWith("export ")) {
      key = key.substring(7).trim();
  }
  if (key.isEmpty()) return;

TEST: DotenvConfigSourceProviderTest
  - "export KEY=value" → properties.get("KEY") == "value".
  - "EXPORT KEY=value" (uppercase) → properties.get("KEY") == "value".
  - "export KEY = value" (spaces) → properties.get("KEY") == "value".
  - "# export KEY=value" (commented) → key absent.
  - "KEY=value" (no export) → unaffected.

──────────────────────────────────────────────────────────────────────────
Component B6 — Make RateLimitTracker Per-Provider With Configurable Backoff
  (SOURCE-VERIFIED: isBackingOff() takes no provider arg in CompactionHandler)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../governance/RateLimitTracker.java

BUG: Single global AtomicReference<Instant> tracks rate limits for ALL
providers. A Groq rate limit hit suppresses OpenAI compaction for 30s.
BACKOFF_SECONDS=30 is hardcoded.

FIX:
  @ApplicationScoped
  public class RateLimitTracker {

      @ConfigProperty(name = "arcclaw.governance.rate-limit-backoff-seconds",
                      defaultValue = "30")
      long backoffSeconds;

      private final ConcurrentHashMap<String, AtomicReference<Instant>> hitsByProvider =
          new ConcurrentHashMap<>();

      public void recordHit(String provider) {
          hitsByProvider
              .computeIfAbsent(provider, p -> new AtomicReference<>(Instant.EPOCH))
              .set(Instant.now());
      }

      public boolean isBackingOff(String provider) {
          AtomicReference<Instant> ref = hitsByProvider.get(provider);
          if (ref == null) return false;
          return Instant.now().isBefore(ref.get().plusSeconds(backoffSeconds));
      }

      /** true if ANY provider is backing off (for global compaction guard). */
      public boolean isAnyBackingOff() {
          return hitsByProvider.values().stream()
              .anyMatch(ref ->
                  Instant.now().isBefore(ref.get().plusSeconds(backoffSeconds)));
      }
  }

Migrate all callers of recordHit() / isBackingOff() to pass provider name.
Update CompactionHandler to call rateLimitTracker.isAnyBackingOff().

TEST: RateLimitTrackerPerProviderTest
  - recordHit("groq") → isBackingOff("groq") == true.
  - isBackingOff("openai") == false (not affected).
  - isAnyBackingOff() == true.
  - After backoffSeconds + 1 → isBackingOff("groq") == false.

──────────────────────────────────────────────────────────────────────────
Component B7 — Wire AuditObserver.trackers + GovernanceLayer.budgets
              Cleanup to SessionReaper (Memory Leak Prevention)
──────────────────────────────────────────────────────────────────────────

Files: arcclaw-core/.../audit/AuditObserver.java
       arcclaw-core/.../governance/GovernanceLayer.java
       arcclaw-core/.../lifecycle/SessionReaper.java

BUG: trackers.remove(sessionId) is called in onLoopComplete(). If a
session terminates abnormally (crash, timeout, reaper), onLoopComplete()
is never called → TokenTracker leaks. Same for GovernanceLayer.budgets —
clearSession() is not called on abnormal termination.

FIX in SessionReaper (or WorkspaceManager.terminateSession):
  // Inject both:
  @Inject AuditObserver auditObserver;
  @Inject GovernanceLayer governanceLayer;

  // In session termination path:
  auditObserver.cleanupSession(sessionId);  // add this method
  governanceLayer.clearSession(sessionId);  // already exists

Add cleanupSession(String sessionId) to AuditObserver:
  public void cleanupSession(String sessionId) {
      trackers.remove(sessionId);
  }

TEST: SessionReaperCleanupTest
  - Create session, partially run (no loop completion).
  - Invoke reaper termination path.
  - Assert trackers map no longer contains sessionId.
  - Assert governanceLayer has no budget state for sessionId.


=====
PROMPT — PHASE 69-C: Architecture Compliance & Low-Priority Hygiene
=====

Role: Senior Java Architect & Senior Java Developer
Task: Phase 69-C — Targeted hygiene, locale safety, delegation shell
removal, and architecture compliance fixes. No behavior changes.
All tests must remain green. mvn clean verify for all modules.

──────────────────────────────────────────────────────────────────────────
Component C1 — Fix REACT_KEYWORDS Locale Safety
  (SOURCE-VERIFIED in ToolStepHandler.java — NOTE: Perplexity attributed
   to EngineStepHandler; actual location is ToolStepHandler)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/ToolStepHandler.java

FIX:
  REACT_KEYWORDS.contains(toolName.toUpperCase())
→ REACT_KEYWORDS.contains(toolName.toUpperCase(Locale.ROOT))

Import: import java.util.Locale;

TEST: Assert "think".toUpperCase(Locale.ROOT) matches REACT_KEYWORDS in
a Turkish-locale test context (Locale.setDefault(new Locale("tr","TR"))).

──────────────────────────────────────────────────────────────────────────
Component C2 — Fix extractUnavailableToolName Inline Pattern Compilation
  (SOURCE-VERIFIED in EngineStepHandler.java)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/EngineStepHandler.java

BUG (confirmed exact code):
  private String extractUnavailableToolName(String rawError) {
      Matcher m = Pattern
          .compile("attempted to call tool '([^']+)'")  // ← compiled fresh per call
          .matcher(rawError);
      return m.find() ? m.group(1) : null;
  }
Violates Architecture_Invariants.md rule: "Use static final Pattern —
never compiled inline."

FIX:
  private static final Pattern UNAVAILABLE_TOOL_PATTERN =
      Pattern.compile("attempted to call tool '([^']+)'");

  private String extractUnavailableToolName(String rawError) {
      Matcher m = UNAVAILABLE_TOOL_PATTERN.matcher(rawError);
      return m.find() ? m.group(1) : null;
  }

TEST: Assert UNAVAILABLE_TOOL_PATTERN is static final via reflection.
Assert extraction of "manage_schedule" from sample error string.

──────────────────────────────────────────────────────────────────────────
Component C3 — Delete AgentStepProcessor Delegation Shell
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/AgentStepProcessor.java (DELETE)
      arcclaw-core/.../loop/AgentStateMachineRunner.java (MODIFY)

Every change to a handler currently requires touching 3 files.
AgentStepProcessor is a pure pass-through facade.

ACTION:
  1. Delete AgentStepProcessor.java.
  2. In AgentStateMachineRunner, inject EngineStepHandler, ToolStepHandler,
     ApprovalCheckpointManager directly.
  3. Replace all stepProcessor.* calls with direct handler calls.

TEST: All existing AgentStateMachineRunner tests pass unchanged.

──────────────────────────────────────────────────────────────────────────
Component C4 — Emit Audit Event + Micrometer Counter on Unknown Role Fallback
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../agent/loop/AgentLoop.java

BUG: Unknown agentRole silently runs as COORDINATOR — only a log.warnf().
No audit event. No metric. Invisible in production.

FIX:
  auditService.record(sessionId, WorkspaceContext.userId(), agentRole,
      SecurityEventType.INVALID_ROLE_FALLBACK,
      "Unknown agentRole '" + agentRole + "' — defaulted to COORDINATOR");

  Counter.builder("arcclaw.agent.role.unknown")
      .tag("role", agentRole)
      .register(meterRegistry)
      .increment();

TEST: AgentLoopUnknownRoleFallbackAuditTest
  - Call run() with role "completely-unknown-role".
  - Assert result is from COORDINATOR (no exception).
  - Assert AuditService.record() called with INVALID_ROLE_FALLBACK.
  - Assert counter incremented.

──────────────────────────────────────────────────────────────────────────
Component C5 — Add Null Validation to AgentExecutionContext Compact Constructor
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/AgentExecutionContext.java

FIX:
  public AgentExecutionContext {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(agentRole,  "agentRole must not be null");
      Objects.requireNonNull(goal,       "goal must not be null");
      if (maxIterations <= 0)
          throw new IllegalArgumentException(
              "maxIterations must be > 0, got: " + maxIterations);
  }

TEST: null sessionId → NullPointerException with "sessionId" in message.
      maxIterations = 0 → IllegalArgumentException.

──────────────────────────────────────────────────────────────────────────
Component C6 — Fix default public on AgentObserver.onLoopComplete()
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../observability/AgentObserver.java

FIX:
  // Before:
  default public void onLoopComplete(...) {}
  // After:
  default void onLoopComplete(...) {}

The public modifier on interface default methods is redundant. Will
flag in all static analysis tools (Checkstyle, PMD, SonarQube).


=====
PROMPT — PHASE 69-D: Architecture Improvements & Durability
  (From Claude.ai Fresh Review — all SOURCE-VERIFIED from directory structure)
=====

Role: Senior Java Architect & Senior Java Developer
Task: Phase 69-D — Four architecture-level improvements. No bug fixes here
(all bugs addressed in 69-A/B/C). These are structural completions.
All existing 877+ tests must remain green.

──────────────────────────────────────────────────────────────────────────
Component D1 — Rename core LlmProviderFactory to ReasoningModelProviderFactory
  (SOURCE-VERIFIED: both interfaces exist in separate packages)
──────────────────────────────────────────────────────────────────────────

Files:
  arcclaw-core/.../reasoning/LlmProviderFactory.java         ← RENAME
  arcclaw-app/.../routing/LlmProviderFactory.java            ← keep as-is
  arcclaw-core/.../reasoning/ChatModelProducer.java          ← update ref
  arcclaw-core/.../reasoning/GroqProviderFactory.java        ← update ref
  arcclaw-core/.../reasoning/OllamaProviderFactory.java      ← update ref
  arcclaw-core/.../reasoning/OpenAiProviderFactory.java      ← update ref

PROBLEM: Two interfaces named LlmProviderFactory exist with DIFFERENT
contracts in different packages:

  arcclaw-core/...reasoning/LlmProviderFactory:
    String providerName();    ← no-arg create
    ChatModel create();

  arcclaw-app/...routing/LlmProviderFactory:
    String providerId();      ← config-bundle create
    ChatModel create(LlmModelConfig config);
    default boolean requiresApiKey();

This creates a silent classpath hazard. Any developer importing the wrong
interface compiles successfully but wires the wrong factory — no compile
error, no CDI startup error, wrong model silently loaded at runtime.

ACTION:
  1. Rename arcclaw-core/.../reasoning/LlmProviderFactory
     → ReasoningModelProviderFactory.java

  2. Update its contract to match the rename:
       public interface ReasoningModelProviderFactory {
           String providerName();
           ChatModel create();
       }

  3. Update all implementors in arcclaw-core to implement
     ReasoningModelProviderFactory (not the old name):
       - GroqProviderFactory
       - OllamaProviderFactory
       - OpenAiProviderFactory

  4. Update ChatModelProducer.java:
       @Inject Instance<ReasoningModelProviderFactory> factories;
       ...
       .filter(factory -> factory.providerName().equalsIgnoreCase(provider))

  5. The arcclaw-app/.../routing/LlmProviderFactory STAYS named as-is
     — it is consumed only by LlmModelFactory in arcclaw-app, has a
     completely different contract, and must not be touched.

  6. Update Architecture_Invariants.md with a new §6 entry:
       MUST NOT name an interface in arcclaw-core the same as one in
       arcclaw-app. Use ReasoningModelProviderFactory (core) vs
       LlmProviderFactory (app routing) as the canonical example.

VERIFY: grep -r "LlmProviderFactory" --include="*.java" shows zero
results in arcclaw-core after the rename.

TEST: All existing ChatModelProducerTest and GroqProviderFactoryTest
pass unchanged after the rename. Add a new test:
  ReasoningModelProviderFactoryContractTest
  - Assert GroqProviderFactory.providerName() == "groq"
  - Assert OllamaProviderFactory.providerName() == "ollama"
  - Assert OpenAiProviderFactory.providerName() == "openai"

──────────────────────────────────────────────────────────────────────────
Component D2 — Fix StatusReflex Injecting Concrete GovernanceLayer
  (SOURCE-VERIFIED: GovernanceStrategy interface exists in governance package)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../reflex/StatusReflex.java

PROBLEM: StatusReflex injects GovernanceLayer (concrete @ApplicationScoped
bean) directly. GovernanceStrategy is the declared interface. This
breaks the SPI contract — any alternative governance implementation
(e.g. EnterpriseGovernanceLayer, TestGovernanceLayer) is bypassed.

CURRENT (wrong):
  @Inject
  GovernanceLayer governanceLayer;  // ← concrete class, not the interface

FIX:
  @Inject
  GovernanceStrategy governanceStrategy;  // ← interface injection

  // Update all call sites in StatusReflex:
  governanceStrategy.getBudgetState(sessionId)
  governanceStrategy.isSessionActive(sessionId)
  // etc. — match GovernanceStrategy interface methods exactly

VERIFY: grep -r "GovernanceLayer" arcclaw-core/src/main/java/
  Must return zero results after the fix. Only
  arcclaw-app/ and GovernanceLayer.java itself may reference the
  concrete class name.

TEST: StatusReflexGovernanceInterfaceTest
  - Inject a mock GovernanceStrategy (not GovernanceLayer).
  - Assert StatusReflex correctly calls mock methods.
  - Assert no direct reference to GovernanceLayer in StatusReflex.java
    (use reflection or a simple grep assertion in build).

──────────────────────────────────────────────────────────────────────────
Component D3 — Persist SessionKnowledgeBase to SQLite (Durability Gap)
  (SOURCE-VERIFIED: SessionKnowledgeBase.java exists in arcclaw-rag,
   no vec_session DDL found in any schema file)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-rag/.../SessionKnowledgeBase.java
      arcclaw-rag/.../RagSchemaInitializer.java (or equivalent DDL file)

PROBLEM: SessionKnowledgeBase stores per-session RAG chunks in memory only.
On application restart (deploy, crash, OOM kill) all session-scoped
knowledge is lost silently. Users who ingest documents, share GitHub
context, or build up session memory across a long task lose everything
on the next Quarkus restart.

GlobalKnowledgeBase persists to SQLite (vec_global table). The same
pattern must be applied to session-scoped chunks.

IMPLEMENTATION PLAN:

Step 1 — Add DDL for vec_session table in RagSchemaInitializer:
  CREATE TABLE IF NOT EXISTS vec_session (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      session_id TEXT    NOT NULL,
      content    TEXT    NOT NULL,
      embedding  BLOB    NOT NULL,
      created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
  );
  CREATE INDEX IF NOT EXISTS idx_vec_session_session
      ON vec_session(session_id);

Step 2 — Add RagChunkSessionEntity.java (mirrors RagChunkEntity.java):
  @Entity
  @Table(name = "vec_session")
  public class RagChunkSessionEntity {
      @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
      public Long id;
      @Column(name = "session_id", nullable = false)
      public String sessionId;
      @Column(nullable = false, length = 65535)
      public String content;
      @Column(nullable = false)
      public byte[] embedding;
      // InstantToTextConverter as used by RagChunkEntity
      @Convert(converter = InstantToTextConverter.class)
      @Column(name = "created_at", nullable = false)
      public Instant createdAt;
  }

Step 3 — Refactor SessionKnowledgeBase.java:
  - Replace in-memory List<RagChunk> with JPA EntityManager persistence.
  - ingest(sessionId, content, embedding) → persist RagChunkSessionEntity.
  - search(sessionId, queryEmbedding, topK) → load all for sessionId,
    compute cosine similarity in-process (same as GlobalKnowledgeBase).
  - clear(sessionId) → DELETE FROM vec_session WHERE session_id = :sid.

Step 4 — Wire cleanup to SessionReaper and WorkspaceManager:
  // In WorkspaceManager.terminateSession(sessionId):
  sessionKnowledgeBase.clear(sessionId);  // purge on session end

Step 5 — Ensure RagSchemaInitializer runs the new DDL at startup.

TEST: SessionKnowledgeBasePersistenceTest
  - Ingest a chunk for session "s1".
  - Stop and restart the application context (QuarkusTest restart or
    @BeforeEach schema re-init).
  - Assert search("s1", queryEmbedding, 3) still returns the ingested chunk.
  - Assert clear("s1") removes all rows for "s1" only (other sessions intact).
  - Assert terminateSession("s1") results in zero rows for "s1".

──────────────────────────────────────────────────────────────────────────
Component D4 — Fix SkillProvider CDI Load Order (Undefined Iteration)
  (SOURCE-VERIFIED: Instance<SkillProvider> in SkillLoader.java,
   ClasspathSkillProvider + DatabaseSkillProvider both present)
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../skill/SkillLoader.java
      arcclaw-core/.../skill/SkillProvider.java
      arcclaw-core/.../skill/ClasspathSkillProvider.java
      arcclaw-core/.../skill/DatabaseSkillProvider.java

PROBLEM: CDI Instance<SkillProvider> iteration order is undefined by spec.
When DatabaseSkillProvider and ClasspathSkillProvider both declare a skill
with the same name, the "winner" (which overrides which) is JVM/container
dependent. On GraalVM AOT native builds the order can differ from JVM mode.
A skill override that works in dev breaks silently in native production.

IMPLEMENTATION:

Step 1 — Add priority() to SkillProvider interface:
  public interface SkillProvider {
      /**
       * Lower value = higher priority. Classpath = 100, Database = 10.
       * Database overrides classpath by default.
       * Custom providers should use values between 10 and 100.
       */
      int priority();

      List<SkillDefinition> loadSkills();
  }

Step 2 — Implement in each provider:
  // ClasspathSkillProvider:
  @Override public int priority() { return 100; }

  // DatabaseSkillProvider:
  @Override public int priority() { return 10; }
  // Lower = higher priority → Database overrides Classpath

Step 3 — Update SkillLoader.reloadAll() to sort before merge:
  List<SkillProvider> ordered = providers.stream()
      .sorted(Comparator.comparingInt(SkillProvider::priority))
      .toList();

  Map<String, SkillDefinition> merged = new LinkedHashMap<>();
  for (SkillProvider provider : ordered) {
      for (SkillDefinition def : provider.loadSkills()) {
          merged.put(def.name(), def);  // later = lower priority (higher int)
                                         // so process high-priority first
      }
  }
  // RESULT: Database (priority 10, processed first) wins over
  //         Classpath (priority 100, processed after → does NOT overwrite)
  //         Because we iterate from lowest-int (highest-priority) first.

  NOTE: Verify the merge semantics with your team — decide definitively
  whether Database or Classpath should be the authoritative source. The
  above makes Database win (Database overrides Classpath). If you want
  Classpath to win (for immutable baseline skills), reverse priorities:
    ClasspathSkillProvider: priority = 10
    DatabaseSkillProvider:  priority = 100

Step 4 — Update Architecture_Invariants.md §5 (Skill System):
  MUST: SkillProvider implementations must declare a priority() value.
  MUST NOT: Rely on CDI Instance iteration order for SkillProvider resolution.
  SHOULD: Database-sourced skills (user-created) override classpath-bundled
          skills when names collide, using priority() to enforce this.

TEST: SkillLoaderProviderOrderTest
  - Register a mock ClasspathSkillProvider (priority=100) with skill "ping".
  - Register a mock DatabaseSkillProvider (priority=10) with skill "ping"
    (different description).
  - Call reloadAll().
  - Assert: registered "ping" uses DatabaseSkillProvider's description.
  - Assert: order is deterministic across 100 repeated loads.

──────────────────────────────────────────────────────────────────────────
Component D5 — Fix CronResultHandler TELEGRAM Fallback Violating §1.4
  (SOURCE-VERIFIED: ChannelType.fromString(...).orElse(ChannelType.TELEGRAM)
   in CronResultHandler.fireCompletionEvent())
──────────────────────────────────────────────────────────────────────────

File: arcclaw-app/.../scheduler/CronResultHandler.java

PROBLEM (confirmed exact code):
  ChannelType chType = ChannelType.fromString(job.channelType)
      .orElse(ChannelType.TELEGRAM);  // ← §1.4 violation

The code comment says "Fallback to TELEGRAM for legacy rows missing
channelType." Architecture_Invariants.md §1.4 says this is LEGACY and
must be removed. This is the LAST surviving Telegram default outside
WorkspaceContext — it must be eliminated before multi-channel expansion.

DECISION POINT: What should happen for legacy cron rows with null channelType?
  (a) Log + skip the notification (safe, no silent wrong channel)
  (b) Emit an audit event for LEGACY_CHANNEL_FALLBACK and skip
  Choose (b) for full audit trail.

FIX:
  ChannelType chType;
  Optional<ChannelType> resolvedType = ChannelType.fromString(job.channelType);
  if (resolvedType.isEmpty()) {
      log.warnf("CRON-NOTIFY cronId=%d has null/unknown channelType='%s'. " +
                "Cannot notify — see Architecture_Invariants.md §1.4. " +
                "Run migration: UPDATE agentcron SET channel_type='TELEGRAM' " +
                "WHERE channel_type IS NULL;",
                job.id, job.channelType);
      auditService.record(cronSessionId, job.userId,
          AgentRoleEnum.COORDINATOR.toScopedName(),
          SecurityEventType.LEGACY_CHANNEL_FALLBACK,
          "cronId=" + job.id + " channelType=" + job.channelType);
      return;  // skip notification, do NOT default to Telegram
  }
  chType = resolvedType.get();

MIGRATION: Add a Flyway/Liquibase script or startup corrector:
  UPDATE agentcron SET channel_type = 'TELEGRAM'
  WHERE channel_type IS NULL OR channel_type = '';

This makes the data explicit rather than relying on code fallback.

TEST: CronResultHandlerLegacyChannelTypeTest
  - Create cron job with channelType = null.
  - Call fireCompletionEvent().
  - Assert: no NullPointerException, no Telegram send.
  - Assert: WARN log contains "Architecture_Invariants.md §1.4".
  - Assert: AuditService.record() called with LEGACY_CHANNEL_FALLBACK.