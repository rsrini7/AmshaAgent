# 1. EXECUTIVE SUMMARY — POST P68 RE-EVALUATION

**Maturity Level: "Phase-65-progress captured, Phase-67 critical bugs unchanged."**

P68 delivered meaningful structural work: the `Architecture_Invariants.md` (an enforceable contract document), the new `StructuredRetryLoop`/`StructuredOutputEngine` subsystem, the `ObservationTransformer` chain for PII/injection sanitization, `RateLimitTracker` for compaction backpressure, and the `TelemetryAuditService`/`AuditObserver` refactor that correctly maps loop outcomes. These are all solid, non-trivial improvements.

**However, all six HIGH-priority bugs identified from the P67 analysis remain completely unfixed in P68 — verbatim identical code in every case.** Additionally, P68 introduces three new defects of its own. The six survivors from P67 are production-correctness failures, not cosmetic issues. They will cause data corruption, security bypass, and semantic failures in real production use.

**Top 5 Current Critical Concerns (updated for P68):**

1. **`ChannelType ==` privilege escalation** — `SessionResolutionHandler` line 18411-18412 still uses `==` instead of `.equals()` for the Telegram FULL→SUPERVISED autonomy cap. UNFIXED from P67.

2. **`isDenial` substring match destroys approved tool executions** — `CheckpointResumeHandler` line 9382 still uses `.contains("denial")`. UNFIXED from P67.

3. **`MemorySearchTool` sends misleading success signal on null query** — line 26498 still returns `ToolResponse.success("No query provided")`. UNFIXED from P67.

4. **`StructuredRetryLoop` budget-warning recovery bypasses the budget gate** — NEW in P68: when `BudgetWarningException` fires on attempt 1, the catch block calls `call.get()` directly, bypassing `executeWithBudgetGate()` (which calls `assertWithinBudget()`). If the session is simultaneously approaching the hard limit, the gate is skipped entirely on the recovery call.

5. **`AuditEvent.securityEvent()` records security events as `AuditOutcome.FINISH`** — line 13962 still uses `FINISH` for vault access, injection detection, etc. UNFIXED from P67.

---

# 2. DEEP FINDINGS — DELTA (P67 → P68)

## 2.1 What P68 Fixed

### GovernanceLayer: Budget warning ordering now correct

The `Architecture_Invariants.md` section 3.7 documents the invariant: "threshold check MUST precede `compareAndSet()`". Looking at the actual `GovernanceLayer.recordLlmCall()` code in P68:

```java
if (currentCost >= budget.warningLimit && !budget.warningSent) {
    budget.warningSent = true;  // ← check first, THEN set
    throw new BudgetWarningException(...)
}
```

The ordering is correct. The invariant was documented proactively (not because it was broken), or was fixed before P68 shipped.

### `SessionAuditSequencer` now has `releaseStaleSession()`

The P67 bug M3 (lock map grows unbounded) is partially addressed: a `releaseStaleSession(String sessionId)` method now exists. This is only useful if `SessionReaper` calls it. The method exists, which closes the architectural gap, but whether it's called correctly depends on `SessionReaper` integration (not re-verified here).

### `TelemetryAuditService.resolveOutcome()` correctly maps loop results

The `AuditObserver.onLoopComplete()` now produces correct `AuditOutcome` values for agent loop events: `FINISH`, `ERROR`, `BUDGET_EXCEEDED`, `MAX_ITERATIONS`, `AWAITING_APPROVAL`. The P67 finding H6 was specifically about the non-loop `securityEvent()` factory method — that path is STILL broken.

### `EngineStepHandler` handles Groq `not in request.tools` correctly

New branch terminates immediately with a user-friendly `AgentResult.finished()` message instead of retrying, preventing the previously-infinite schema correction loop for cross-role tool contamination.

### `ObservationTransformer` chain is a strong addition

`PiiRedactionTransformer`, `InjectionRedactionTransformer`, `TruncationTransformer` — all registered via CDI `Instance<ObservationTransformer>`, priority-ordered. This closes the gap noted in P67 Analysis §2.5: tool observations are now sanitized through a structured pipeline before history append, as required by Architecture Invariant 3.3.

---

## 2.2 New Bugs Introduced in P68

### NEW Bug N1 — `StructuredRetryLoop` budget-warning recovery bypasses `assertWithinBudget()`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/structured/StructuredRetryLoop.java`

**Problem:**
```java
} catch (BudgetWarningException e1) {
    log.warnf("Budget warning for session %s during %s...", ...);
    // Absorb warning and proceed
    return call.get();  // ← direct, no budget gate
}
```

When `BudgetWarningException` fires on attempt 1, the recovery path calls `call.get()` directly, skipping `executeWithBudgetGate()`. If the session is at 79% and climbing, the next call could push it over 100% without the gate firing again. This also violates Architecture Invariant 3.7's spirit — the budget gate must precede the call in all paths, not just the happy path.

**Why it matters:** Session budget can be exceeded silently on the recovery call. The user sees no error; the LLM call succeeds; the governance violation is undetected.

**Real-world failure:** Session at 82% budget triggers `BudgetWarningException` on a structured output call. The catch block calls `call.get()` again (no gate check). The second call costs 20% more. Budget is now at 102%. `recordLlmCall` eventually fires and throws `BudgetExceededException` asynchronously from the main loop, but by then the call has already completed and its output has been used.

---

### NEW Bug N2 — `AuditObserver.trackers` map leaks on abnormal session termination

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/audit/AuditObserver.java`

**Problem:**
```java
private final ConcurrentHashMap<String, TokenTracker> trackers = new ConcurrentHashMap<>();
```

`trackers.remove(sessionId)` is called inside `onLoopComplete()`. If a session terminates without completing a loop (server restart, crash, timeout, session reaper firing before loop completion), `onLoopComplete()` is never called and the `TokenTracker` leaks permanently.

**Why it matters:** Long-running deployments accumulate one unreachable `TokenTracker` per abnormally terminated session. Minor per item, but unbounded growth over time.

---

### NEW Bug N3 — `GovernanceLayer.budgets` map also leaks on abnormal session termination

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/governance/GovernanceLayer.java`

**Problem:** `clearSession()` must be called explicitly. If a session is reaped by `SessionReaper` before `clearSession()` is invoked, the `SessionBudget` object leaks. This existed in P67 too but wasn't called out explicitly since `clearSession()` is now declared on the `GovernanceStrategy` interface — the interface contract implies it must be called, but nothing enforces the call is made on abnormal termination.

---

### NEW Issue N4 — `AgentObserver.onLoopComplete()` has non-idiomatic `default public` signature

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/observability/AgentObserver.java`

```java
default public void onLoopComplete(...) {}
```

Java interface `default` methods are already implicitly `public`. This is non-idiomatic and indicates the method was added hastily. Any code-quality tool (Checkstyle, PMD, SonarQube) will flag this. Minor but signals rushed implementation of the new observer extension point.

---

## 2.3 Carry-Forward From P67 (All Still Unfixed)

Every HIGH and MEDIUM priority item from the P67 report that was NOT addressed by the fixes above remains open verbatim. The six HIGH bugs are confirmed present in P68 with identical code. For completeness:

| P67 Finding | P68 Status | Evidence |
|---|---|---|
| H1: `ChannelType ==` bypass | **UNFIXED** | Line 18411-18412 P68 |
| H2: `isDenial` substring | **UNFIXED** | Line 9382 P68 |
| H3: `extractFailedGeneration` Unicode | **UNFIXED** | No Jackson usage added |
| H4: `MemorySearchTool` success on null | **UNFIXED** | Line 26497-26498 P68 |
| H5: `DotenvConfigSourceProvider` export | **UNFIXED** | Lines 2449-2458 P68 |
| H6: `securityEvent()` FINISH outcome | **UNFIXED** | Line 13962 P68 |
| M1: `ToolInvocationGuard` public fields | **UNFIXED** | (not re-verified but structure unchanged) |
| M2: Null sentinel in CheckpointResumeHandler | **UNFIXED** | Line 9427 P68 |
| M6: `rotateSecrets()` UnsupportedOp | Not visible in P68 | (not re-verified) |

---

# 3. IMPROVEMENT COMPARISON — P67 → P68

## What actually improved

- Structural: `Architecture_Invariants.md` is a serious engineering artifact that will prevent regressions if maintained.
- `StructuredRetryLoop` / `StructuredOutputEngine` are solid new capabilities with correct budget-gate integration on the happy path.
- `ObservationTransformer` pipeline closes the tool observation sanitization gap from Invariant 3.3.
- `RateLimitTracker` solves the real-world cascading compaction problem.
- `TelemetryAuditService` loop completion audit is now semantically correct.
- `SessionAuditSequencer.releaseStaleSession()` provides the mechanism to close the lock map leak.
- Groq `not in request.tools` is now handled terminally (no wasted retries).

## What is still weak

All six P67 HIGH-priority bugs are carried forward unchanged. The two most dangerous — the autonomy bypass and the denial false-positive — remain production risks.

## What is missing vs elite systems

No changes on the missing-from-elite list since P67: no distributed trace propagation through `ReasoningStep`, no idempotency key on agent invocations, no schema migration strategy, no exhaustive `ReplyNode` switch coverage test, and `rotateSecrets()` remains unimplemented.

---

# 4. PRIORITIZED ROADMAP — UPDATED FOR P69

## HIGH (P69-A) — same 6 bugs from P67 + 1 new P68 bug

**All six P67 HIGH findings carry forward unchanged. Added:**

### H7 (NEW) — `StructuredRetryLoop` budget-warning recovery bypasses gate
- **Impact:** Budget enforcement silently bypassed; session can exceed hard limit on recovery call.
- **Effort:** Trivial — replace `call.get()` with `executeWithBudgetGate(sessionId, call)` in both catch blocks.
- **Risk if ignored:** Real cost overruns in production; `BudgetExceededException` fires asynchronously after the fact.

## MEDIUM (P69-B)

All P67 MEDIUM findings carry forward. Added:

### M9 (NEW) — `AuditObserver.trackers` map leaks on abnormal termination
- **Impact:** Memory leak proportional to abnormally-terminated session count.
- **Effort:** Low — call `trackers.remove(sessionId)` from a `SessionReaper` hook or `@Observes SessionTerminated` event.

### M10 (NEW) — `GovernanceLayer.budgets` lifecycle must be tied to session reaper
- **Impact:** Memory leak. `clearSession()` exists but must be wired to the session reaper.
- **Effort:** Low — verify `SessionReaper` calls `governanceLayer.clearSession(sessionId)`.

## LOW (P69-C)

All P67 LOW findings carry forward. Added:

### L6 (NEW) — Fix `default public` on `AgentObserver.onLoopComplete()`
- **Impact:** Non-idiomatic code, will flag in static analysis tools.
- **Effort:** Trivial — remove `public` keyword.

---

# 5. PHASE PROMPTS

---

# PROMPT — PHASE 69-A: Fix 7 Confirmed Production Defects (Tier A)

**Role:** Senior Java Architect, Bug Fixer & Senior Java Developer
**Task:** Phase 69-A — Fix 7 confirmed production defects existing today in P68 live code. No architectural changes. Surgical fixes only. All bugs have been confirmed to exist in the P68 codebase.

---

## Architecture Context and Other References

Refer `@Architecture_Invariants.md`

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. Every fix must be accompanied by a test proving the bug existed and now does not.
2. No new dependencies. No structural package changes.
3. All existing 887+ tests must remain green after every component.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. All fixes must be safe under JDK 25 (no preview features).
6. Test commands always use `clean` to prevent stale class false-negatives.

---

### Component A1 — `ChannelType` reference equality security bypass in `SessionResolutionHandler`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/pipeline/handlers/SessionResolutionHandler.java`

**Bug:** Line uses `==` for `ChannelType` record comparison:
```java
if (level == AutonomyLevel.FULL
    && context.getUser().channel() == ChannelType.TELEGRAM) {
```

`ChannelType` is a `record`. Records implement value equality via `.equals()`, not `==`. Any `ChannelType("telegram")` constructed via `ChannelType.fromString("telegram")` or `new ChannelType("telegram")` is a distinct JVM reference — the security cap silently fails.

**Fix:** Replace `==` with `.equals()`:
```java
if (level == AutonomyLevel.FULL
    && ChannelType.TELEGRAM.equals(context.getUser().channel())) {
```

**Required tests (`SessionResolutionHandlerTest`):**
- Construct a `ChannelUser` using `ChannelType.fromString("telegram")` (not the constant). Set up `agentStore` to return `AutonomyLevel.FULL`. Assert resolved level is `SUPERVISED`.
- Confirm the existing constant-based path also results in `SUPERVISED` (regression guard).
- Confirm a non-Telegram channel with `FULL` autonomy is NOT capped.

---

### Component A2 — `isDenial` substring false-positive cancels approved tool executions

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/CheckpointResumeHandler.java`

**Bug:**
```java
boolean isDenial = goal != null && goal.toLowerCase().contains("denial");
```

This fires for any user goal containing the word "denial" (insurance, legal, psych, access control) — silently cancelling the tool execution and setting `CheckpointStatus.FAILED` immediately after the user approved.

**Fix:** Replace with exact sentinel comparison against `AgentSentinels.GOAL_DENY_APPROVED`. Add a new sentinel constant to `AgentSentinels`:
```java
// In AgentSentinels.java:
public static final String GOAL_DENY_APPROVED = "DENY_APPROVED_SENTINEL_V1";
```

In `CheckpointResumeHandler`:
```java
boolean isDenial = AgentSentinels.GOAL_DENY_APPROVED.equals(goal);
```

Update `DenyReflex.execute()` to set the goal to `AgentSentinels.GOAL_DENY_APPROVED` when constructing the `AgentLoop.run()` call for a denial. The sentinel must be an exact equality match, never a substring match.

**Required tests (`CheckpointResumeHandlerTest`):**
- Goal `"analyze insurance denial letters"` with status `AWAITING_APPROVAL` → tool executes normally (no cancellation).
- Goal `"claim denial analysis summary"` → no cancellation.
- Goal exactly `AgentSentinels.GOAL_DENY_APPROVED` → denial fires, status set to `FAILED`.
- Goal `null` → no NPE, no denial.

---

### Component A3 — `extractFailedGeneration` garbles Unicode escape sequences

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/EngineStepHandler.java`

**Bug:** The hand-rolled JSON string escape parser handles `\n`, `\t` but does NOT handle `\uXXXX` Unicode escapes. LLM prose answers from multilingual sessions (Hindi, Arabic, Chinese, etc.) are returned garbled when recovered from `tool_use_failed` Anthropic/Groq errors.

**Fix:** Replace the manual parser with Jackson:
```java
@Inject
com.fasterxml.jackson.databind.ObjectMapper objectMapper;

private String extractFailedGeneration(String rawError) {
    if (rawError == null) return null;
    try {
        int start = rawError.indexOf('{');
        if (start < 0) return null;
        com.fasterxml.jackson.databind.JsonNode root =
            objectMapper.readTree(rawError.substring(start));
        com.fasterxml.jackson.databind.JsonNode node = root.path("failed_generation");
        if (node.isMissingNode() || !node.isTextual()) return null;
        String result = node.asText().strip();
        return result.isBlank() ? null : result;
    } catch (Exception e) {
        log.debugf("extractFailedGeneration parse failed: %s", e.getMessage());
        return null;
    }
}
```

Add `@Inject ObjectMapper objectMapper` to `EngineStepHandler` (Jackson is already a project dependency via `quarkus-rest-jackson`).

**Required tests (`EngineStepHandlerTest` or equivalent):**
- Input containing `"failed_generation": "Voil\u00e0 les r\u00e9sultats"` → extracted text equals `"Voilà les résultats"`.
- Input with `"failed_generation": "Line1\nLine2"` → newline decoded correctly.
- Input with no `failed_generation` key → returns `null`.
- Input that is not JSON → returns `null` gracefully.

---

### Component A4 — `MemorySearchTool` returns misleading `success` on null/blank query

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/tools/MemorySearchTool.java`

**Bug:**
```java
if (query == null || query.isBlank()) {
    return ToolResponse.success("No query provided");
}
```

The LLM receives status `success` with content `"No query provided"` — indistinguishable from a legitimate empty-result search. The LLM continues reasoning as if memory was searched successfully, potentially hallucinating memory state.

**Fix:**
```java
if (query == null || query.isBlank()) {
    return ToolResponse.failure(400, "query parameter is required for memory_search");
}
```

**Required tests:**
- `params = {}` (no query key) → `ToolResponse` status code 400.
- `params = {"query": "   "}` (blank) → status code 400.
- `params = {"query": "kubernetes"}` with mocked repository → succeeds normally (regression guard).

---

### Component A5 — `DotenvConfigSourceProvider` silently drops `export KEY=VALUE` syntax

**File:** `arcclaw-app/src/main/java/com/arcclaw/app/config/DotenvConfigSourceProvider.java`

**Bug:** Lines formatted as `export TELEGRAM_BOT_TOKEN=abc123` are stored with key `"export TELEGRAM_BOT_TOKEN"` instead of `"TELEGRAM_BOT_TOKEN"`. This silently loses all secrets in `.env` files generated by shell scripts or documentation templates that use the `export` keyword.

**Fix:** After the `parts[0].trim()` key extraction, strip the `export ` prefix:
```java
String key = parts[0].trim();
// Strip leading "export " keyword (common in shell-generated .env files)
if (key.toLowerCase(java.util.Locale.ROOT).startsWith("export ")) {
    key = key.substring(7).trim();
}
if (key.isEmpty()) return; // skip malformed lines
```

**Required tests (`DotenvConfigSourceProviderTest`):**
- Line `export KEY=value` → `properties.get("KEY") == "value"`.
- Line `EXPORT KEY=value` (uppercase) → `properties.get("KEY") == "value"`.
- Line `export KEY = value` (spaces around `=`) → `properties.get("KEY") == "value"`.
- Line `# export KEY=value` (commented) → key absent.
- Line `KEY=value` (no export) → unaffected.

---

### Component A6 — `AuditEvent.securityEvent()` records security events as `AuditOutcome.FINISH`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/audit/AuditEvent.java` and `AuditOutcome.java`

**Bug:**
```java
e.outcome = AuditOutcome.FINISH; // Default for non-loop events
```

All security events (vault access, PII detection, injection detection, checkpoint creation) are recorded with `AuditOutcome.FINISH`. Compliance queries for non-successful or non-loop outcomes miss all security events entirely.

**Fix:**
1. Add `SECURITY_EVENT` to `AuditOutcome`:
```java
public enum AuditOutcome {
    FINISH, ERROR, BUDGET_EXCEEDED, APPROVAL_TIMEOUT, MAX_ITERATIONS,
    AWAITING_APPROVAL,
    SECURITY_EVENT   // Non-agent-loop events: vault, PII, injection, checkpoint
}
```

2. In `AuditEvent.securityEvent()`:
```java
e.outcome = AuditOutcome.SECURITY_EVENT;
```

3. Update `TelemetryAuditService.resolveLoopEventType()` to handle the new enum value defensively (it uses `switch` — add a case or ensure exhaustive coverage).

4. Update any `AuditVerifier` chain checks that validate `outcome` to permit `SECURITY_EVENT`.

**Required tests:**
- `AuditEvent.securityEvent(...)` produces `outcome == SECURITY_EVENT`.
- `AuditEvent.of(...)` (loop events) still produces `FINISH`/`ERROR`/etc.
- `TelemetryAuditService.resolveLoopEventType()` does not throw `MatchException` for any `AuditOutcome` value.
- HMAC chain verification passes for events with `SECURITY_EVENT` outcome.

---

### Component A7 — `StructuredRetryLoop` budget-warning recovery bypasses `assertWithinBudget()`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/structured/StructuredRetryLoop.java`

**Bug:**
```java
} catch (BudgetWarningException e1) {
    log.warnf(...);
    return call.get();  // ← direct, no budget gate
}
```

Both `BudgetWarningException` catch blocks call `call.get()` directly, bypassing `executeWithBudgetGate()`. This violates Architecture Invariant 3.7's spirit and allows a session to exceed its hard budget on the recovery call.

**Fix:** Replace all bare `call.get()` in catch blocks with `executeWithBudgetGate(sessionId, call)`:

```java
} catch (BudgetWarningException e1) {
    log.warnf("Budget warning for session %s during %s: %s", sessionId, operationName, e1.getMessage());
    // Absorb warning and proceed — but still check hard limit
    return executeWithBudgetGate(sessionId, call);
}
```

Apply the same fix to the nested retry's `BudgetWarningException` catch (line ~25749).

**Required tests:**
- Session at 85% budget → `BudgetWarningException` fires on attempt 1 → recovery attempt calls `assertWithinBudget()` → if session is then at 101%, `BudgetExceededException` is thrown from the recovery call (not silently succeeded).
- Session at 82% budget with room → warning absorbed, call succeeds normally.

---

---

# PROMPT — PHASE 69-B: Refactoring and Design Improvements (Tier B)

**Role:** Senior Java Architect & Refactoring Expert
**Task:** Phase 69-B — Address 7 design and maintainability issues identified in P68. No behavioral changes. All existing tests must remain green.

---

## Architecture Context and Other References

Refer `@Architecture_Invariants.md`

---

## IMPLEMENTATION REQUIREMENTS

1. No behavioral changes — tests must pass before and after.
2. No new dependencies. No package restructuring.
3. All existing tests must remain green.
4. Each component must include a regression or enhanced coverage test.

---

### Component B1 — Encapsulate `ToolInvocationGuard` `public` injection fields

Change all `@Inject public` fields to package-private. CDI injection functions on package-private fields. This eliminates cross-module mutation risk. Provide constructor injection path for testability.

---

### Component B2 — Replace null sentinel in `CheckpointResumeHandler.handle()` with `ResumeDecision`

Introduce:
```java
sealed interface ResumeDecision permits ResumeDecision.Continue, ResumeDecision.Terminal {
    record Continue() implements ResumeDecision {}
    record Terminal(AgentResult result) implements ResumeDecision {}
}
```

Change `handle()` return type to `ResumeDecision`. Update all callers in `AgentStateMachineRunner`/`AgentLoop` to use pattern matching. This eliminates the null-as-sentinel anti-pattern documented in P67.

---

### Component B3 — Ensure `AuditObserver.trackers` cleanup is wired to session lifecycle

Add a `@Observes` handler or a dedicated `cleanup(String sessionId)` method that removes the `TokenTracker` entry for sessions that terminate without completing a loop (abnormal path). Verify `SessionReaper` calls it, or register via CDI event.

---

### Component B4 — Verify and enforce `GovernanceLayer.clearSession()` is called on session reaper

Audit `SessionReaper` and `WorkspaceManager.destroySession()` to confirm `governanceLayer.clearSession(sessionId)` is called on session teardown. Add it if missing. Add a test that verifies no budget state remains after session destruction.

---

### Component B5 — Fix `default public` on `AgentObserver.onLoopComplete()`

```java
// Before:
default public void onLoopComplete(...) {}
// After:
default void onLoopComplete(...) {}
```

The `public` modifier on interface `default` methods is redundant and non-idiomatic. This will suppress static analysis warnings without behavior change.

---

### Component B6 — Extract duplicated `CommandDispatchHandler` dispatch logic

As identified in P67 (M7): extract the repeated `WorkspaceContext.run() + reflexRegistry.processIfReflex() + error handler + sender lookup` block into a single private `dispatchAndReply(MessageContext context, String input)` method. Both `dispatch()` and the switch fallback branch must call this method. Remove duplicated code entirely.

---

### Component B7 — Document `toScoredChunks` positional scoring rationale

Add Javadoc to `TwoTierMemoryService.toScoredChunks()` explicitly stating scores are `1/(rank)` positional values for RRF compatibility, not BM25 similarity scores. Add `@apiNote` to `ScoredChunk.score()` with the same caveat. No code changes — documentation only.

---

---

# PROMPT — PHASE 69-C: Low Priority Optimizations (Tier C)

**Role:** Senior Java Developer
**Task:** Phase 69-C — 4 low-priority polish and documentation items. No behavioral changes. All tests remain green.

---

### Component C1 — Add sealed `ReplyNode` exhaustive switch coverage test

Add a parameterized JUnit test that uses reflection to enumerate all permitted subclasses of `ReplyNode` and asserts that `TelegramMarkdownV2Renderer` (and any registered `ReplyRenderer`) handles each one without throwing `MatchException`. This is a compile-guard regression test for future `ReplyNode` extensions.

---

### Component C2 — `looksLikeNaturalLanguage` — add structural JSON guard

Add a secondary check: if the text ends with `}` or `]` and the content has structured-JSON characteristics (colon-separated key-value pairs, bracket nesting), return `false` even when the first character is not `{` or `[`. Document the limitation.

---

### Component C3 — `Vec0NativeLibraryInstaller` path bounds validation

Before `Files.copy()`, assert `target.toAbsolutePath().normalize()` starts within the expected parent directory. If it escapes bounds (potential path traversal via malformed config), log `WARN` and abort installation rather than throwing.

---

### Component C4 — Enforce `Architecture_Invariants.md` section 3.7 with a compile-time test

Add a unit test in `arcclaw-core` that reflectively inspects `GovernanceLayer.recordLlmCall()` bytecode or uses an assertion to verify the `assertWithinBudget()` call in `StructuredRetryLoop.executeWithBudgetGate()` is always invoked before `call.get()`. This prevents future regressions where the budget gate is accidentally removed during refactoring.
