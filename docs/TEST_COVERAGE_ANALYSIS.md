# Java Test Coverage — Deep Analysis and Prioritized Action Plan

> **Scope**: multi-module Maven reactor (28 modules / 29 reactor projects including the root aggregator,
> JDK 25, Quarkus 3.39.3)
> **Method**: full-reactor test execution + JaCoCo 0.8.15 line/branch/method analysis, cross-referenced with a
> static test-reference scan
> **Measured**: 2026-09-14

---

## 1. Executive summary

| Metric | Measured |
|---|---|
| Reactor line coverage | **74.51%** (44,706 / 59,997 lines) |
| Reactor branch coverage | **58.31%** (20,235 / 34,702 branches) |
| Reactor method coverage | **78.85%** (8,857 / 11,233 methods) |
| `onboarding` line coverage | **82.31%** (2,210 / 2,685 lines) |
| Test suites / tests executed | 900 suites, **6,199 tests**, 0 failures, 0 errors, 7 skipped |

> **These figures replace the earlier 70.66% baseline, which was wrong.** That number was depressed by
> two independent defects, both now fixed (see §1.2): a module permanently recorded as 0% covered, and a
> coverage gate that had never actually executed. The honest baseline is **74.51%** — the project was
> always about four points better than its own gate claimed.

> **On the test count.** The figure above is read from a run of `./mvnw clean verify`, so no stale reports are in
> the denominator. An earlier draft quoted **6,207 tests in 904 suites** — that run was taken *without* `clean`, and
> the extra 8 tests / 4 suites were leftover report files from a previous build (including a `failsafe` IT report).
> `mise run test:summary` aggregates `target/surefire-reports/TEST-*.xml` and will over-report by the same mechanism
> if stale files are present; always `clean` before quoting it.

> **Run-to-run stability.** An independent re-run of `clean verify` measured **74.52%** (44,708 lines) against the
> **74.51%** (44,706) quoted above — a two-line difference. The metric is therefore stable to ~2 lines, and the
> `0.74` gate floor sits ~300 lines below it. Quote the lower figure as the baseline; the gap is noise, not drift.

**Why "70%" looked plausible and was nevertheless meaningless.** `70%` was the value hard-coded as the *gate
threshold* in `pom.xml`. The measured number happened to land just above it — but for two wrong reasons:
`onboarding` (2,685 lines, 4.5% of the reactor) was recorded as 0% covered, and the gate that consumed the number
was not running at all.

> **The floor has since been raised to `0.74`.** With both defects fixed, `0.70` was four points below reality and
> permitted a silent 4.5pp decline. `pom.xml` now carries `0.74` with a comment explaining the headroom; CI reads
> the value from the POM rather than duplicating it. See §10 item 2.

> **Phase 1 progress (same day).** Coverage has since risen to **76.10%** (45,657 / 59,997) — **+951 lines,
> +1.59pp** over the 74.51% baseline above — measured from 6,528 tests in 905 suites, 0 failures, 0 errors,
> 7 skipped, with the `0.74` gate green and unchanged. Branch coverage is **59.62%** and method **79.62%**. The
> work targeted the lowest-covered classes in `core.agent.loop` / `core.tools` / `core.lifecycle`, most of which
> turned out to be unit-testable: their collaborators arrive through **package-private fields or constructors**
> over port interfaces, so a plain JUnit test in the same package can inject mocks with no Quarkus container.
>
> | Class | Before | After | Lines gained |
> |---|---|---|---|
> | `DynamicToolSchemaParser` | 0.0% | **100.0%** | 54 |
> | `CronFailureCleanup` | 28.0% | **100.0%** | 72 |
> | `ToolStepEscalationHandler` | 2.6% | **100.0%** | 37 |
> | `ToolSchemaProvider` | 40.4% | **100.0%** | 34 |
> | `LoopResultHandler` | 56.5% | **99.4%** | 69 |
> | `ParallelBatchResumeHandler` | 67.1% | **100.0%** | 53 |
> | `ToolStepHandler` | 71.0% | **100.0%** | 61 |
> | `ThinkingState` | 46.4% | **100.0%** | 45 |
> | `CheckpointResumeHandler` | 50.0% | **76.8%** | 66 |
> | `ParallelToolStepExecutor` | 52.3% | **99.2%** | 114 |
> | `StepDecisionRouter` | 57.4% | **98.3%** | 47 |
> | `ExecutionFailureRecoveryService` | 59.2% | **100.0%** | 49 |
> | `PendingToolResumeExecutor` | 79.7% | **100.0%** | 47 |
> | `DelegatedCompletionHandoff` | 66.2% | **97.8%** | 42 |
> | `BrowserPreflightHandler` | 73.6% | **98.8%** | 41 |
>
> The seven classes closed out most recently — `ThinkingState` (45), `StepDecisionRouter` (47),
> `ExecutionFailureRecoveryService` (49), `PendingToolResumeExecutor` (47), `ParallelToolStepExecutor` (102),
> `DelegatedCompletionHandoff` (45) and `BrowserPreflightHandler` (43) — took the loop's routing, recovery,
> HITL-resume, delegation-bridge and first-turn-preflight layer from the least-exercised part of the engine to near-total coverage. Between them they cover the runner loop's four escape hatches (the write-intent directive
> injection, the no-progress breaker with its deterministic `ingest_document` fallback, the rate-limit retry
> budget, and the structured-output self-correction budget); every `FinishOutcome` variant the router can
> actually receive (`Completed` — including the SYNTHESIZER rebuild — `RetryLeak`, `Correction`, `SalvageFailed`,
> `ExecuteLeakedTool`, `InjectToolCall`, and the deliberate fall-through for `SynthesisCorrection`); the
> session-halt short circuit; the `AWAITING_APPROVAL` WAL-visibility retry, its corruption failure and its
> interrupt-restore branch; the whole Shiva auto-heal path — the overload chain, the retry/accumulate loop with
> its extracted-code carry-forward, code-block extraction, structural validation rejection, non-`Finished`
> result formatting, delegation-throw handling and the non-code error skip; the single-pending-tool resume path
> end to end: the checkpoint-history prepend that preserves HITL context, the delegation redirect for a tool the
> resuming persona may not use (including its unparseable-parameter and unserialisable-request failure modes),
> all four guard arms (`Allow`, `Blocked`, `PauseForApproval`, `Deduplicate`), the `retry-after` backoff-and-retry
> loop, the delegated-approval pause with its payload-validation and persistence-failure guards, the bounded
> web-retrieval fallback (`browser_navigate` → `web_fetch` → one search), and the halt/abort paths; and, in
> `ParallelToolStepExecutor`, the whole parallel-batch lifecycle — the batch MDC scope and its teardown, the
> observer fan-out, the pre-execution budget check, the rate-limit-backing-off warning, the per-category
> `ManagedExecutor` selection, the session-halt cancellation sweep, all three `future.get` failure arms (timeout
> with its `amsha.agent.tool.timeout` metric, `ExecutionException`, and the interrupt that restores the flag for
> the caller), the HITL split into pre-executable and pending halves with both of its outcomes, and the
> delegated-pause checkpoint that re-arms the call for replay; and, in `DelegatedCompletionHandoff`, the whole
> child-to-parent bridge — every input it must refuse (a non-terminal child, a missing child session, no active
> parent checkpoint, a sequential parent not waiting on a delegation), the batch-call match with its
> agent-name lookup and single-call fallback, the replayed-delegation guard, the persist-failure degradation, the
> sibling-still-active readiness flag, both child-result mappings (structured and failed), and the sequential
> capture that clears the pending delegation; and, in `BrowserPreflightHandler`, the whole first-turn preflight
> ladder — the empty-history gate, all four guard arms at the preflight boundary (`Allow`, `Blocked`,
> `PauseForApproval`, `Deduplicate`), the `RequiresApprovalException` catch that pauses on the exception's own
> frame rather than a synthesised one, the deliberate swallow that degrades an unexpected tool failure to the
> normal reasoning path, the failed-`ToolResponse` arm with its internal-directive stripping, the invalid-timezone
> fallback, the recurring-request cron arm, the `SUGGESTED_CRON` / `SUGGESTED_NEXT_FIRE_TIME` scope bindings, the
> researcher-only browser-navigation gate with its URL detection, and the gist resolver's possessive-username and
> stop-word-exhaustion paths.
> 761 missed lines across the fourteen classes taken to completion fell to **10** — one in `LoopResultHandler`,
> the two in `StepDecisionRouter`, the two in `ParallelToolStepExecutor`, the three in
> `DelegatedCompletionHandoff` and the two in `BrowserPreflightHandler` that cannot be reached at all (see §1.3).
> `CheckpointResumeHandler` (540 lines) gained from the cron-lease guards, failure-notification, resume-budget and
> dispatch paths, but still needs a CDI container to reach its async body — see §1.3. It is now the largest
> remaining gap in the package.
>
> The headline figure above is deliberately **left at 74.51%**: it is the measurement that anchored the ratchet,
> and overwriting it would erase the evidence that the floor was set correctly. `0.74` is still the gate, and the
> next step (`0.74` → `0.75`) is now well within reach — `0.75` sits ~1.10pp (~660 lines) below measured
> coverage, against run-to-run jitter of ~2 lines, so it can be raised deliberately rather than reflexively.

### 1.3 Dead code found while raising coverage

Nine guards cannot be exercised and should be deleted or simplified rather than "covered":

1. **The blank-resume-goal guards (lines 178–181 and 455–459 of `CheckpointResumeHandler`) are unreachable.**
   `ResumeStrategyDispatcher.resolveResumeGoal` never returns `null` or blank — it falls back to
   `AgentSentinels.GOAL_RESUME_APPROVED` — so `resumeGoal == null || resumeGoal.isBlank()` is always false.
2. **`CheckpointResumeHandler.fallbackResultDispatch`'s CLI branch (lines 409–417) is unreachable.** It
   recomputes the *identical* `isCli` condition already evaluated false at line 354 before the fallback can be
   reached, so the branch can never be taken.
3. **`StepDecisionRouter`'s parallel-invariant guard (lines 127–131) is unreachable.** This is the last
   remaining uncovered code in the class, and it is the reason `StepDecisionRouter` stops at 98.3% rather than
   100%:

   ```java
   private Optional<RoutingOutcome> handleFinishOutcome(
           AgentExecutionContext context,
           List<ReasoningStep> history,
           ReasoningStep.FinishStep currentStep,   // <- statically a FinishStep
           int llmSchemaErrorCount) {
       if (currentStep.isParallelBatch()) {        // always false
           log.errorf("PARALLEL INVARIANT VIOLATION: session %s parallel batch step reached FINISH handler.", ...);
       }
   ```

   `ReasoningStep.isParallelBatch()` is `default boolean isParallelBatch() { return this instanceof ParallelStep; }`,
   and `FinishStep` is not a `ParallelStep` — both are direct records implementing the sealed `ReasoningStep`
   interface. The parameter is statically typed `FinishStep`, so the condition is a compile-time constant. The
   only caller narrows the same way (`route` matches `currentStep instanceof ReasoningStep.FinishStep`), so no
   input can reach the body. Note that line 127 itself *is* reported covered — the `if` is evaluated on every
   FINISH turn, it just never branches true — which is exactly why "the line is green" is not evidence that a
   guard works. The check is a leftover from when the parameter was a plain `ReasoningStep` (which is what
   `FinishActionHandler.handle` still accepts), so the fix is to delete the guard rather than to contort a test.

Relatedly, the `if (result instanceof AgentResult.AwaitingApproval)` checks at lines 361–366 and 411–415 of
`CheckpointResumeHandler` have **identical bodies in both arms** — the condition changes nothing. Tests now pin
the behaviour, but the branches are redundant.

4. **`ParallelToolStepExecutor.handleHitlBatch`'s missing-response fallbacks (lines 434 and 439) are
   unreachable.** This is the only reason the class stops at 99.2% rather than 100%:

   ```java
   if (split.hitlPending().isEmpty()) {                 // line 429 — every call was pre-executed
       List<ParallelToolCall> completed = new ArrayList<>();
       for (ParallelToolCall call : calls) {            // line 431 — all of `calls`
           ToolResponse resp = preResults.get(resultKey(call));   // line 432
           if (resp == null) {                                    // line 433
               resp = preResults.get(call.toolName());            // line 434  <- dead
           }
           if (resp != null) {
               completed.add(call.withResult(resp.output(), resp.success()));
           } else {
               completed.add(call.withResult("Tool execution missing response", false));  // line 439  <- dead
           }
       }
   ```

   The pre-execution loop (lines 410–427) writes `preResults` under `resultKey(call)`, and this loop reads it
   under the *same* `resultKey(call)`, so every call in `calls` has an entry — including a call whose
   pre-execution *threw*, because the `catch` still stores a `ToolResponse.failure`. The `resp == null` case
   would require a call present in `calls` but absent from `split.preExecutable()`, which cannot happen on this
   path: reaching line 429 at all means `hitlPending()` is empty, and `PreExecutionFilter.split` partitions
   `calls` exhaustively. Note that line 433 *is* reported covered — the `if` is evaluated on every iteration, it
   just never branches true. As with item 3, the fix is to delete the two fallbacks rather than to contort a test.

5. **`DelegatedCompletionHandoff` lines 68, 229 and 251 are unreachable**, which is the whole of its residual
   gap (the class stops at 97.8%, not 100%). Three separate reasons, all of them "a guard that re-checks what its
   caller already guaranteed":

   ```java
   // line 59 — findChildCall() only ever returns a call that is ABSENT from the results map
   ParallelToolCall childCall = findChildCall(calls, childSessionId, parent.getPreExecutedResults());
   // line 65 — ...re-read from the same source, so it is the identical map
   Map<String, JsonNode> results = readResults(parent.getPreExecutedResults());
   String callKey = callKey(childCall);
   if (results.containsKey(callKey) || results.containsKey(childCall.toolName())) {
       return Optional.empty();   // line 68 — always false
   }
   ```

   Inside `findChildCall`, the returned call satisfies `!results.containsKey(callKey(call)) &&
   !results.containsKey(call.toolName())` by construction (lines 186–188), and the `calls.size() == 1` fallback
   applies the same test before returning `only` (line 194). Since line 65 re-reads the *same*
   `getPreExecutedResults()` string, line 67 can never be true.

   Line 229 is the `marker < 0` early return in `childAgent(childSessionId)`. Its only caller is `findChildCall`
   (line 183), whose only caller is `capture` — and `capture` has already returned at line 43 unless
   `childSessionId.indexOf(DELEGATE_SESSION_SENTINEL) > 0` (line 42). `childAgent` looks for the *same* sentinel
   in the *same* string, so `marker < 0` is impossible by the time it runs.

   Line 251 is the `default -> throw new IllegalArgumentException("Non-terminal delegated result")` arm of
   `toToolResponse`. Both of its call sites (lines 71 and 103) are downstream of `isTerminal(result)` at line 39,
   and `isTerminal` enumerates exactly the three variants the switch handles (`Finished`, `StructuredFinished`,
   `Failed`) — so the arm is a compile-time impossibility and the switch is already exhaustive over its real
   domain.

   Note the pattern common to all three: the guard is *correct* and cheap, but it duplicates a decision its
   caller already made. Each is worth deleting (or replacing with an assertion the compiler can check) rather
   than covering.

6. **`BrowserPreflightHandler.buildStep`'s null-goal guard (line 240) is unreachable.** `AgentExecutionContext`'s
   constructor is `this.goal = Objects.requireNonNull(goal, "goal must not be null")`, so `goal == null` at line
   239 can never hold:

   ```java
   String goal = context.goal();
   if (goal == null) {          // line 239 — evaluated on every browser preflight
       return Optional.empty(); // line 240 — never taken
   }
   ```

   As with items 3 and 4, line 239 itself *is* reported covered — the condition is evaluated on every call that
   reaches it, it just never branches true. The guard is not merely redundant but actively misleading: it implies
   the goal is optional at this boundary when the type invariant already forbids it. (`buildGithubGistStep`'s
   `goal == null || goal.isBlank()` at line 259 has the same dead half, but its `isBlank()` operand *is*
   reachable — a whitespace-only goal — so only the `null` half is redundant there.)

7. **`BrowserPreflightHandler.buildGithubGistStep`'s `hasGithubIntent` guard (line 274) is unreachable.** The gate
   immediately before it requires `GET_GIST_INTENT_PATTERN` to match, and that pattern is
   `(?i)\b(?:get|fetch|list|show|retrieve|pull|find|read|check|display)\b.*\bgists?\b` — it ends in the literal
   word `gist`/`gists`, so any text that passes it necessarily contains the substring `gist`:

   ```java
   if (!GET_GIST_INTENT_PATTERN.matcher(normalized).find()) {
       return Optional.empty();                                    // line 266 — reachable
   }
   String target = extractGistTarget(normalized);
   String lower = normalized.toLowerCase(Locale.ROOT);
   boolean hasGithubIntent =
           lower.contains("github") || lower.contains("gh") || lower.contains("gist") || target != null;
   if (!hasGithubIntent) {                                         // line 273 — always false
       return Optional.empty();                                    // line 274 — never taken
   }
   ```

   The `"gist"` disjunct is implied by the gate that has just passed, so `hasGithubIntent` is a constant `true` at
   line 273 — and, once again, line 273 is reported covered while its body is not. The guard's stated intent
   ("only route to the github tool when the text actually mentions GitHub") is already enforced one gate earlier;
   the fix is to delete it, or to fold the `github`/`gh` test into `GET_GIST_INTENT_PATTERN` where it would
   genuinely narrow the match set.

### 1.4 A crash found while raising coverage

Writing the resume-path tests surfaced a genuine defect, not a coverage gap.

**`ParallelBatchResumeHandler` line 97 throws `NullPointerException` when a batch call has no `callId`.**

```java
Map<String, JsonNode> preExecuted = Map.of();          // line 84 — immutable, empty
...
String preExecutedKey = preExecuted.containsKey(call.callId()) ? call.callId() : call.toolName();
```

`Map.of()` is immutable and its `containsKey` calls `Objects.requireNonNull` on the key, so a `null` `callId`
raises a message-less `NullPointerException` **before** the `call.toolName()` fallback that the expression was
clearly written to provide. The observed frames are:

```
java.lang.NullPointerException
    at java.base/java.util.Objects.requireNonNull(Objects.java:220)
    at java.base/java.util.ImmutableCollections$MapN.containsKey(ImmutableCollections.java:1451)
    at com.amsha.core.agent.loop.ParallelBatchResumeHandler.resumeParallelBatch(ParallelBatchResumeHandler.java:97)
```

The exception is raised outside the per-call `try` block (which starts at line 130), so it escapes
`resumeParallelBatch` entirely and fails the whole HITL resume rather than degrading one call.

This is inconsistent with the rest of the codebase, which treats a null call ID as expected input:

- `BatchDecisionUtil.decisionKey(call)` — *"Matches checkpoint keying when a provider omitted call IDs."*
- `ParallelToolStepExecutor.resultKey(call)` — `call.callId() == null || call.callId().isBlank() ? call.toolName() : call.callId()`

The minimal fix is to mirror `resultKey`:

```java
String preExecutedKey = call.callId() != null && preExecuted.containsKey(call.callId())
        ? call.callId()
        : call.toolName();
```

**Reachability is unproven.** `ParallelToolCallCollapser` (line 249) synthesises an ID when the provider omits
one, so the main LLM path always produces a non-null `callId`. The exposure is therefore a checkpoint whose
`pendingToolParametersJson` was written by another producer — an older build, or a hand-edited/imported
checkpoint. Given that the two neighbouring helpers defend against exactly this case, the guard is worth adding
regardless; but the fix has **not** been applied here, and the test suite deliberately does not pin the NPE with
`assertThrows` (doing so would make the fix fail the build). Decide whether to harden or leave it.

**Two abort paths in `PendingToolResumeExecutor` escape as unchecked exceptions rather than as results.** Both
`failIfHalted` (line 341) and the interrupt-restore branch of `waitBeforeRetry` (line 350) throw a
`RuntimeException`, and both sit where no handler remains: `failIfHalted` is called at the top of the retry loop,
*outside* the `try` that wraps `executeAttempt`, and the backoff throw happens *inside* the `catch` block that
would otherwise route to `handleResumeExecutionError`. The exception therefore propagates out of `execute`,
through `ResumeStrategyDispatcher.handle` (which does not catch) and out of `CheckpointResumeHandler.handle`.
This reads as deliberate for the halt case — aborting a killed session with a control-flow exception is a
defensible choice — but the backoff-interrupt case is less obviously intentional: a resume interrupted mid-backoff
is arguably a `Failed` result rather than an uncaught throw. The tests pin the **current** behaviour
(`execute_haltedSessionAbortsBeforeTheToolIsInvoked`,
`execute_interruptDuringBackoffAbortsWithAnUncheckedException`) instead of asserting a preferred one, and no fix
is proposed.

**Branch coverage (58.31%) is now the weakest axis and the highest-value target.** Line coverage is respectable;
the untested work is concentrated in *decision logic* — error paths, retries, fallbacks, and multi-backend
parity — not in untouched classes.

### The findings that matter most

| # | Finding | Impact |
|---|---|---|
| 1 | **The coverage gate had never run, because `./mvnw verify` was broken.** `SessionOwnershipIT` could not boot Quarkus: the parent `maven-failsafe-plugin` configuration carried only `<argLine>`, so the integration tests started the app as `prod` instead of `test`, and `StartupSecurityValidator` aborted on the missing audit HMAC key. The build died in the `verify` phase — the same phase that hosts `jacoco-coverage-check`. | The 70% gate protected nothing, in CI *or* locally. Every "coverage is 70%" figure in the repo was read from a report nobody regenerated. Fixed in §1.2. |
| 2 | **`onboarding` was permanently recorded as 0% covered.** A non-daemon MCP *stdio* thread (`StdioMcpMessageHandler`) blocks on `System.in.readLine()` and only returns on EOF. The JVM therefore never exited, Surefire force-killed the fork once `forkedProcessExitTimeoutInSeconds` elapsed, and the JaCoCo shutdown hook never wrote `jacoco.exec` — leaving a 0-byte file and 0% coverage. | Understated the reactor by **3.7pp** and made a genuinely *well*-covered module (82.31%) look untested. The real trigger was a one-word config typo: `quarkiverse.mcp.server.stdio` instead of `quarkus.mcp.server.stdio`. Fixed in §1.2. |
| 3 | **CI never ran the Java test suite at all.** `.github/workflows/ci.yml` ran `./mvnw clean package -DskipTests`; there were **zero** references to `jacoco` or `coverage` anywhere in CI. | Even once the gate worked, nothing invoked it on a push. A `java-tests` job was added in §1.2. |
| 4 | **A test hung the build indefinitely locally.** `BootstrapOrchestratorTest.testBootstrapExecution()` invoked the full production bootstrap, which reached `npm install -g @playwright/cli` via `ProcessBuilder` with an **unbounded `waitFor()`**, and there was no JUnit or Surefire timeout guard. | The app module never finished for a developer. CI was *not* affected — `PlaywrightCliInstaller.ensureCliInstalled()` returns early when `CI`/`GITHUB_ACTIONS` is set. Fixed in §1.1. |

---

## 1.1 Phase 0 implementation status

Phase 0 (restore measurement integrity) was implemented alongside this analysis.

| Item | Status | Change |
|---|---|---|
| 0.1 CI enforcement | **Fixed** | A `java-tests` job in `.github/workflows/ci.yml` now runs `./mvnw --batch-mode verify` (JDK 25, Python 3.12) and then a fatal `verify_jacoco_coverage.py --strict-instrumentation`. The exact command was validated locally: `clean verify` → BUILD SUCCESS, gate reports 74.51%. |
| 0.2 Onboarding instrumentation | **Fixed** | Root cause was **not** the JaCoCo/Quarkus interaction the earlier bisection suggested. It was a one-word MCP config typo (`quarkiverse.mcp.server.stdio` vs the real `quarkus.mcp.server.stdio`) that left the stdio transport enabled; its non-daemon reader blocked on `System.in` forever, so the forked JVM never exited and Surefire force-killed it before the JaCoCo shutdown hook ran. See §1.2. Module went **0% → 82.31%** and `jacoco.exec` from **0 → 723,459 bytes**. |
| 0.3 Hanging test | **Fixed** | `BootstrapOrchestrator` now skips external installs and interactive prompts for `--help`/`-h`/`--version`/`-v`, matching the short-circuit already used by `CliUtils` and `LocalModelValidator`. `BootstrapOrchestratorTest` went from hanging indefinitely to **9.2s**. |
| 0.3b Unbounded subprocess | **Fixed** | `PlaywrightCliInstaller` now runs external commands through a bounded `runBounded()` helper (hard timeout + virtual-thread stdout drain to prevent pipe-buffer deadlock). |
| 0.3c Blocking prompt | **Fixed** | `InteractiveSetupWizard.readLineWithFallback` no longer issues a blocking `System.in.read()` when no console is attached and no input is buffered. This also removes a real production hang for `serve` under systemd/Docker without a TTY. Regression test added. |
| 0.4 Global timeout guard | **Fixed** | `pom.xml` now sets `junit.jupiter.execution.timeout.default` (`5 m`) and `forkedProcessTimeoutInSeconds` (`900`) via the `tests.timeout.default` / `tests.forked.process.timeout.seconds` properties. Sized against measured data — the slowest test is 10.0 s and the slowest module (`app`) is 2.6 min — so the guard converts an *infinite* hang into a failure without policing test speed. **First attempt used `PT5M`; the JUnit Platform rejects ISO-8601 and silently ignores the value**, so the guard was inert until it was changed to `5 m`. Verified: zero invalid-timeout warnings across the reactor. |
| 0.5 Empty-report detection | **Fixed** | `verify_jacoco_coverage.py` now detects modules that report zero coverage despite passing tests. Default is a prominent **warning** (non-breaking); `--strict-instrumentation` makes it fail, for CI. |
| 0.6 Stale docs | **Fixed** | `docs/TESTING_GUIDE.md` corrected in four places. Rather than swap one literal for another, §1.6 now states the acceptance rule as "0 failures, 0 errors, no drop in count" and points at `mise run test:summary` to derive the current number — the hard-coded "1664" had drifted by ~4,500 tests. |
| 0.7 `mise run test:summary` reported **0 tests** | **Fixed** | Found while validating 0.6. The task aggregated surefire XML with `rg`, which is **not declared in `[tools]`** and therefore absent on a clean checkout; because stderr was discarded, the task printed a confident `Total: 0` instead of failing. Replaced with POSIX `find`/`grep`/`awk` and added an explicit empty-state message. Now reports `Total: 6199 … Suites: 900`. |
| 0.8 `./mvnw verify` was broken — the gate had **never run** | **Fixed** | Discovered while validating 0.1. `maven-failsafe-plugin` inherited only `<argLine>` from the parent, so integration tests booted Quarkus as `prod`; `StartupSecurityValidator` then aborted on the missing audit HMAC key, the build died in `verify`, and `jacoco-coverage-check` — bound to that same phase — was never reached. Fixed by giving Failsafe the same `systemPropertyVariables` as Surefire (notably `quarkus.profile=test`). `SessionOwnershipIT` went 1 error → **5/5 pass**. See §1.2. |

Verified after the changes: full reactor `./mvnw --batch-mode clean verify` → **BUILD SUCCESS, exit 0, all 29 projects
green, 6,199 tests, 0 failures, 0 errors, 7 skipped** (confirming the 0.4 timeout guard breaks nothing); the gate
reports **74.51%** and now *runs* for the first time; `onboarding/target/jacoco.exec` is **723,459 bytes** (was 0);
zero Surefire fork kills and zero invalid-timeout warnings across the reactor; `mise run test:summary` prints the same
totals; `BootstrapOrchestratorTest` 1/1 pass (9.2s); `InteractiveSetupWizardTest` 12/12 pass; `fix_fqns.py` and
`spotless:apply` clean; PMD gate green.

---

## 1.2 The two defects that made the old baseline wrong

The earlier baseline of **70.66%** was not a conservative estimate — it was two independent defects cancelling into
a plausible-looking number. Both are now fixed, and this section is the record of what they were, because the
symptom in each case pointed somewhere other than the cause.

### 1.2.1 The coverage gate had never executed

**Symptom.** `./mvnw --batch-mode verify` — the exact command added to CI — failed with:

```
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.2 s
  <<< ERROR! SessionOwnershipIT
Caused by: java.lang.IllegalStateException: FATAL: amsha-audit-hmac-key is not present in SecretVault.
    at com.amsha.app.security.StartupSecurityValidator.validateHmacKey(StartupSecurityValidator.java)
```

**Why it mattered more than it looks.** The JaCoCo gate in `app/pom.xml` is bound to the **`verify`** phase:

```xml
<!-- app/pom.xml, ~line 513 -->
<execution>
  <id>jacoco-coverage-check</id>
  <phase>verify</phase>
  <goals><goal>exec</goal></goals>
  <configuration>
    <arguments><argument>--minimum</argument><argument>${jacoco.minimum.line.coverage}</argument></arguments>
  </configuration>
</execution>
```

The build died *in* `verify`, before reaching that execution. So the 70% threshold had **never been enforced** — not
in CI, not on a developer machine. Every "coverage is 70%" figure in the repository was read from a report that
nobody had regenerated since the failure was introduced. The gate was decorative.

**Root cause.** The parent POM's `maven-failsafe-plugin` configuration carried only `<argLine>`. Surefire and
Failsafe keep **separate** configurations — a property set on Surefire does not reach Failsafe — so the integration
tests started the application without `quarkus.profile=test`. Under the default profile `StartupSecurityValidator`
does *not* short-circuit:

```java
void onStart(@Observes StartupEvent ev) {
    enforceDnsTtl();
    if ("dev".equals(profile) || "test".equals(profile)) return;   // ← never taken under `prod`
    validatePepper();
    validateHmacKey();        // ← throws
    validateAllowlist();
}
```

Every `*IT` therefore failed to boot. The fix was to give Failsafe the same `systemPropertyVariables` Surefire
already had, `quarkus.profile=test` above all. `SessionOwnershipIT` went from 1 error to **5/5 passing**, and the
gate ran for the first time.

> **Note for future readers:** this is a general trap. Any property that test code depends on must be declared on
> **both** Surefire and Failsafe, because they do not share configuration. The two blocks in the root `pom.xml` are
> now deliberately identical and should be kept that way.

### 1.2.2 `onboarding` was permanently recorded as 0% covered

**Symptom.** All 194 tests in the module passed, yet `onboarding/target/jacoco.exec` was **0 bytes**, so JaCoCo
reported **0.00%** across 2,685 lines — 4.5% of the reactor, and 12% of all "missed" lines.

**The earlier diagnosis was wrong, and this matters.** The ADR (`ADR-extract-core-subsystems.md` §8) called it a
*flake*: "observed 4×, always resolved by re-running `./mvnw verify -pl onboarding`". Bisecting by test selection
appeared to confirm a deterministic mechanism — plain tests fine, `@QuarkusTest` classes fine, the two *combined in
one fork* producing 0 bytes — which pointed at the Quarkus test extension resetting the JaCoCo runtime between
application restarts.

That was a coincidence of the *setup*, not the cause. The real evidence was in the Surefire log, one line that the
bisection had been filtering past:

```
[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).
```

The fork was being **force-killed**. That is why the `forkedProcessExitTimeoutInSeconds` experiment (30 → 120)
"had no effect" — it only changed *how long* the kill waited. A `jstack` of the doomed fork gave the answer:

```
"pool-8-thread-1" ... java.lang.Thread.State: BLOCKED (on object monitor)
    at io.quarkiverse.mcp.server.stdio.runtime.StdioMcpMessageHandler$1.run(StdioMcpMessageHandler.java:117)
    at ... BufferedReader.readLine()
"main" ... ForkedBooter.acknowledgedExit
```

**Root cause.** The MCP **stdio** transport was still enabled during tests. Its reader loop runs on a **non-daemon**
thread and only returns on EOF:

```java
String line = reader.readLine();
if (line == null) { LOG.debug("EOF received, exiting"); Quarkus.asyncExit(0); return; }
```

Nothing ever closes `System.in`, so the thread blocks in `readLine()` forever. `main` had already completed and
called `System.exit(0)`, but a live non-daemon thread keeps the JVM alive — so the process never terminated, the
JaCoCo agent's **shutdown hook never ran**, and no execution data was ever written.

The module *intended* to disable it. `onboarding/src/test/resources/application.yaml` contained:

```yaml
quarkiverse:            # ← wrong prefix
  mcp:
    server:
      stdio:
        enabled: false
```

`quarkiverse.*` is not a known configuration root. The real prefix, read from the extension's own
`McpStdioRuntimeConfig`, is **`quarkus.mcp.server.stdio`**. Because the key belonged to no known root, Quarkus
**ignored it silently** — no "Unrecognized configuration key" warning, nothing in the log. A one-word typo
manufactured a permanent 0% module.

**Fix.** Removed the bogus block and set the correct key. Result: 194/194 tests pass, **0 fork kills**,
`jacoco.exec` = **723,459 bytes**, module coverage **82.31%** line / **65.76%** branch.

### 1.2.3 CI never ran the suite

`.github/workflows/ci.yml` ran `./mvnw clean package -DskipTests` and contained **zero** references to `jacoco`,
`coverage`, or `verify`. Even after 1.2.1 made the gate work, nothing would have invoked it on a push.

A `java-tests` job was added. Two details are load-bearing:

- It runs **`verify`, not `package`** — `package` reaches neither the PMD `complexity-gate` execution nor the JaCoCo
  gate, so a coverage regression would pass unnoticed.
- It runs `verify_jacoco_coverage.py --strict-instrumentation` as a **separate, fatal** step. The gate inside
  `verify` only *warns* about a module that ran tests yet recorded no coverage; that is exactly the failure mode in
  1.2.2, and it must not be able to reopen silently.

Both commands were validated locally before being committed to the workflow.

---

## 2. How coverage is measured and enforced today

```
pom.xml                          jacoco-maven-plugin 0.8.15
  prepare-agent  (initialize)    → sets ${argLine}
  report         (verify)        → target/site/jacoco/jacoco.csv
  antrun         (initialize)    → touch target/jacoco.exec   ← creates a 0-byte file
app/pom.xml                      antrun (verify)
  → scripts/verify_jacoco_coverage.py --minimum 0.74
scripts/quality/verify_jacoco_coverage.py   sums LINE_MISSED/LINE_COVERED across modules; fails < 74%
scripts/quality/analyze_jacoco_coverage.py  ranks gaps; --target default 0.80
mise run test:summary                       surefire counts + jacoco summary + UI coverage
```

> The `0.74` floor is defined once, in `pom.xml` as `jacoco.minimum.line.coverage`. `app/pom.xml` passes the
> property through, and the CI step reads it out of the POM with `sed` rather than hardcoding a copy — the old
> duplicated `0.70` is how the floor silently drifted four points below reality.
>
> `analyze_jacoco_coverage.py` was realigned in the same pass: its `--target` default moved `0.72` → `0.80` (the
> goal recommended in §8, and now above the gate) and its badge colour bands moved `0.70` → `0.74`, so the badge
> can no longer read "green" at a coverage level the build would reject.

**Integrity problems in this pipeline — with current status:**

- ~~**The gate is not wired into CI.**~~ **Fixed (§1.2.3).** A `java-tests` job now runs `./mvnw verify`, which is
  the only phase that reaches the gate. The pre-existing `jvm-validation` job still builds with `-DskipTests` and
  exercises the running JAR over HTTP — that job is a smoke test, not a coverage check, and both are needed.
- ~~**`verify` never completed.**~~ **Fixed (§1.2.1).** The gate was bound to a phase the build never reached.
- ~~**`--minimum 0.70` was four points stale.**~~ **Fixed.** The floor is now `0.74` against a measured 74.51%
  (§10 item 2).
- ~~**`analyze_jacoco_coverage.py --target` defaulted to `0.72`.**~~ **Fixed.** Now `0.80`, above the gate; the
  badge colour bands moved to `0.74` with it.
- **The `touch jacoco.exec` step still masks missing instrumentation.** Its stated purpose is to stop JaCoCo from
  skipping reports for modules with no tests. The side effect is that a module whose agent silently fails
  produces a *valid-looking but empty* report showing 0% — indistinguishable from "no tests exist". This is exactly
  what hid the `onboarding` defect for months. `--strict-instrumentation` (0.5) now catches it, but the underlying
  `touch` remains a footgun worth revisiting.
- **`--minimum` is a single reactor-wide number.** A 90%-covered module can still subsidise a 30%-covered one, so
  per-module regressions are invisible. See §10 item 3.

---

## 3. Measured baseline by module

Sorted ascending by line coverage. `missed` = uncovered lines (the absolute size of each gap).

> **Four rows differ from the first draft of this table.** `onboarding` was listed at 0.00% — a measurement defect,
> not a coverage level (see §1.2.2); it is in fact one of the better-covered modules at **82.31%**. `app` rose from
> 67.81% to **69.18%** once the previously-excluded `BootstrapOrchestratorTest` was restored to the suite (§1.1, 0.3).
> `local-ml-onnx` moved 72.67 → 72.63% (rounding, from a re-run). `persistence-common`'s branch column read 100.00%
> and now correctly reads `—`: its single class has no branch instructions, so the percentage is undefined. All other
> rows are unchanged.

| Module | Line % | Branch % | Lines | Missed | Classes |
|---|---:|---:|---:|---:|---:|
| tool-api | 35.83 | **4.17** | 120 | 77 | 12 |
| common | 35.90 | 14.47 | 117 | 75 | 8 |
| sandbox | 65.04 | 53.25 | 801 | 280 | 19 |
| llm-providers | 65.80 | 44.97 | 4,059 | **1,388** | 87 |
| persistence-postgres | 66.06 | 37.07 | 1,370 | 465 | 46 |
| skills | 66.28 | 50.35 | 608 | 205 | 13 |
| tools-builtin | 67.41 | 58.41 | 2,145 | 699 | 26 |
| persistence-api | 67.67 | 35.98 | 600 | 194 | 57 |
| channel-telegram | 68.82 | 58.68 | 959 | 299 | 19 |
| app | 69.18 | 57.28 | 7,479 | **2,305** | 187 |
| deployment | 69.66 | 55.41 | 178 | 54 | 7 |
| persistence-sqlite | 70.40 | 44.40 | 1,875 | 555 | 61 |
| self-evolve | 71.67 | 61.31 | 893 | 253 | 30 |
| local-ml-onnx | 72.63 | 58.92 | 2,554 | 699 | 67 |
| memory | 72.69 | 68.94 | 271 | 74 | 20 |
| security | 75.29 | 66.27 | 1,647 | 407 | 30 |
| persistence-fs | 76.85 | 50.35 | 2,790 | 646 | 54 |
| mcp | 78.05 | 59.06 | 2,410 | 529 | 66 |
| channel-web | 78.07 | 52.53 | 579 | 127 | 14 |
| core | 78.19 | 61.47 | 23,486 | **5,122** | 610 |
| persistence-common | 81.82 | — † | 33 | 6 | 1 |
| onboarding | 82.31 | 65.76 | 2,685 | 475 | 56 |
| rag | 83.29 | 67.86 | 425 | 71 | 18 |
| llm-provider-api | 83.35 | 67.94 | 1,279 | 213 | 46 |
| channel-api | 84.51 | 77.98 | 439 | 68 | 56 |
| security-api | 95.90 | 86.76 | 122 | 5 | 12 |
| classifier-api | 100.00 | 94.44 | 73 | 0 | 12 |

† `persistence-common` contains a single class (`SessionKnowledgeBase`) with no branch instructions at all, so a
branch percentage is undefined rather than 0% or 100%. It is 1.5% of nothing; ignore it.

### Where the uncovered line mass actually is

| Module | Missed lines | Share of all gaps |
|---|---:|---:|
| core | 5,122 | **33.5%** |
| app | 2,305 | 15.1% |
| llm-providers | 1,388 | 9.1% |
| local-ml-onnx | 699 | 4.6% |
| tools-builtin | 699 | 4.6% |
| persistence-fs | 646 | 4.2% |
| persistence-sqlite | 555 | 3.6% |
| mcp | 529 | 3.5% |
| onboarding | 475 | 3.1% |
| persistence-postgres | 465 | 3.0% |
| security | 407 | 2.7% |

Denominator: **15,291 uncovered lines** (59,997 total − 44,706 covered).

**`core` + `app` + `llm-providers` = 57.6% of all uncovered lines.** One third of the entire remaining gap is
`core` alone. The `onboarding` measurement fix has already been banked — it moved the reactor from 70.83% to
**74.51%** without a single test being written, and `onboarding` no longer appears as a gap at all.

### A note on what "untested class" does and does not mean

A static scan found **399 of 1,376 production classes (29%)** whose simple name appears nowhere in their
module's test sources. Cross-referencing against measured coverage materially narrows this:

- **115 of those classes are covered indirectly** (they are executed through other tests). Example: the
  `persistence-sqlite.jdbi` package has **26 production classes and 0 dedicated test files**, yet measures
  **76.8% line coverage** (Postgres equivalent: 71.8%). Do not add tests here.
- **245 are interfaces, records, enums, DTOs, `*ReflectionConfig`, or `*Metrics` classes** — API surface with
  no branching logic. Testing them inflates counts without adding safety.
- **~179 are genuinely at 0%**, of which a further ~65 are in `app`/`onboarding` and are artifacts of the
  measurement failures described above.

**The actionable population is roughly 154 real logic classes**, concentrated in the tiers in §7.

---

## 4. Worst branch coverage — the real weak spot

Branch coverage is **58.31%** versus **78.85%** method and **74.51%** line. The classes below are *partially*
covered — the happy path works, the decision lattice does not.

| Missed branches | Branch % | Class |
|---:|---:|---|
| 116 | 43.7 | `persistence-fs.FsCronStoreAdapter` |
| 110 | **23.6** | `persistence-sqlite.SqliteCronStoreAdapter` |
| 101 | 46.0 | `llm-providers.wrapper.AgyResponsesChatModel` |
| 99 | 58.4 | `llm-provider-api.MultimodalRequests` |
| 99 | **23.8** | `local-ml-onnx.LocalAudioTranscoder` |
| 98 | 42.4 | `persistence-fs.FsCheckpointStoreAdapter` |
| 81 | 32.5 | `llm-providers.wrapper.FoundryResponsesChatModel` |
| 80 | 34.4 | `llm-providers.helper.AgyCliExecutor` |
| 79 | 57.1 | `core.AgentResultFormatter` |
| 77 | 50.0 | `llm-providers.ProviderOAuthExchangeService` |
| 76 | **5.0** | `persistence-postgres.PostgresCronStoreAdapter` |
| 75 | 47.9 | `core.TextPatternUtils` |
| 72 | 41.0 | `skills.SkillsJarSkillProvider` |
| 61 | **21.8** | `core.lifecycle.CronFailureCleanup` |
| 65 | **7.1** | `llm-providers.helper.AgyTokenResolver` |
| 61 | 26.5 | `llm-providers.SpecializedChatModelFactory` |

**Pattern**: cron/checkpoint store adapters, provider wrappers, and retry/token-resolution helpers are where
error handling is least exercised. `PostgresCronStoreAdapter` at **5.0% branch coverage** is the single most
decision-blind class in the reactor.

---

## 5. Top uncovered classes by absolute line gap

| Missed | Covered | Line % | Missed branches | Class |
|---:|---:|---:|---:|---|
| 123 | 123 | 50.0 | 65 | `core.agent.loop.CheckpointResumeHandler` |
| 117 | 139 | 54.3 | 72 | `skills.SkillsJarSkillProvider` |
| 116 | 127 | 52.3 | 25 | `core.agent.loop.ParallelToolStepExecutor` |
| 89 | 168 | 65.4 | 42 | `security.media.GovernedMediaDownloadService` |
| 83 | 112 | 57.4 | 50 | `tools-builtin.playwright.PlaywrightCliTool` |
| 83 | 106 | 56.1 | 60 | `core.tools.ToolRegistry` |
| 81 | 59 | 42.1 | 80 | `llm-providers.helper.AgyCliExecutor` |
| 78 | 77 | 49.7 | 42 | `sandbox.PodmanContainerExecutor` |
| 77 | 120 | 60.9 | 51 | `local-ml-onnx.LocalOnnxAudioTranscriptionAdapter` |
| 72 | 84 | 53.8 | 52 | `tools-builtin.GithubTool` |
| 72 | 28 | **28.0** | 61 | `core.lifecycle.CronFailureCleanup` |
| 70 | 91 | 56.5 | 67 | `core.agent.loop.LoopResultHandler` |
| 67 | 115 | 63.2 | 41 | `mcp.transport.StdioMcpTransportClient` |
| 67 | 77 | 53.5 | 81 | `llm-providers.wrapper.FoundryResponsesChatModel` |
| 67 | 72 | 51.8 | 65 | `llm-providers.wrapper.KimchiResponsesChatModel` |
| 66 | 65 | 49.6 | 76 | `persistence-postgres.PostgresCronStoreAdapter` |
| 65 | 49 | 43.0 | 99 | `local-ml-onnx.LocalAudioTranscoder` |
| 64 | 89 | 58.2 | 110 | `persistence-sqlite.SqliteCronStoreAdapter` |
| 64 | 38 | 37.3 | 57 | `core.agent.reasoning.ChatModelProducer` |
| 62 | 141 | 69.5 | 28 | `mcp.transport.McpConnectionLifecycleOrchestrator` |
| 61 | 149 | 71.0 | 39 | `core.agent.loop.ToolStepHandler` |
| 54 | **0** | **0.0** | 44 | `core.tools.DynamicToolSchemaParser` |

Classes at a literal **0%** with meaningful size: `core.tools.DynamicToolSchemaParser` (54),
`app.api.IdentityResourceHelper` (82), `app.db.DbQueryAstScanner` (74), `app.api.DbConsoleTenantResolver` (60),
`persistence-fs.FsEvolutionEventStore` (40), `common.HtmlArtifactUtil` (37), `skills.ClasspathSkillProvider` (28),
`common.BackoffRetryExecutor` (28), `app.config.BackendValidator` (23), `app.config.SandboxValidator` (17),
`app.security.ActiveWorkspaceFilter` (18), `self-evolve.PostgresDirectiveStore` (21),
`llm-provider-api.LlmExceptionClassifierChain` (21), `memory.TieredMemoryProducer` (17).

---

## 6. Critical-path assessment against `AGENTS.md` invariants

The good news first — the **mandatory safety machinery is genuinely well tested**:

| Invariant | Test presence |
|---|---|
| `ToolInvocationGuard` executes before every tool; never bypassed | **Strong** — `ToolInvocationGuardTest` (49 refs), `ToolInvocationGuardDynamicTest` (20), `ToolInvocationGuardApprovalParityTest`, `ToolInvocationGuardMemoryReadTest`, `ToolGuardOrderingTest`, `ToolStepHandlerGuardTest`, `ToolInvocationGuardTraceTest` |
| `PreflightState` mandatory | Yes — `PreflightStateTest` |
| `LoopStuckDetector` mandatory | Yes — `LoopStuckDetectorTest`, `LoopStuckTraceTest` |
| `SessionReaper` / stale-approval auto-deny | Yes — `SessionReaperTest`, `StaleApprovalDeniedTest` |
| `ContextAwareExecutorService` / `WorkspaceScopeHolder` propagation | Yes — `ContextAwareExecutorScopedValuePropagationTest`, `WorkspaceScopeContextTest`, `WorkspaceScopeTest` |
| `@Transactional` placement rules | Yes — `TxReflectionTest` asserts TxType per method |

Gaps on the critical path:

| Gap | Evidence | Risk |
|---|---|---|
| **Native reflection registration is untested** | 23 `*ReflectionConfig` classes across 23 modules; **zero** test sources reference any of them | A missing `@RegisterForClass`/`classNames` entry fails only at native runtime. The `native-validation` CI job proves the binary *builds*, not that every JDBI record/DAO is reachable. |
| **No migration parity test** | `persistence-sqlite` and `persistence-postgres` each have 12 Flyway migrations; nothing asserts their semantics match | `AGENTS.md` requires "matching SQLite/PostgreSQL versions **and semantics**". Divergence is silent until production. |
| **No store-port parity test** | 3 backends (SQLite, PostgreSQL, filesystem) implement shared ports | The same port behaves differently per backend; only per-backend tests exist. |
| **Cron store adapters barely branch-covered** | Postgres **5.0%**, SQLite 23.6%, FS 43.7% branch | Cron scheduling is safety-relevant (fork-bomb guard, lookback window, run-once). |
| **The coverage gate itself was unenforced** | The gate is bound to `verify`; `verify` died at `SessionOwnershipIT`; CI ran `package -DskipTests` with zero `jacoco` references anywhere | The guard rail protecting *every other invariant in this table* was silently inert, in CI and locally. See §1.2.1, §1.2.3. Now runs in CI, with `--strict-instrumentation` as a fatal separate step. |
| ~~`onboarding` is invisible to coverage~~ | ~~0-byte `jacoco.exec` despite 194 green tests~~ | **Resolved — §1.2.2.** Root cause was a wrong MCP config prefix (`quarkiverse.*`), not a JaCoCo/Quarkus race. The module now measures **82.31%**, and its `SettingsResource` (216 lines) / `BootstrapResource` (145 lines) are back in the denominator as real code. |

### Known-issue note — onboarding instrumentation: **resolved, and the original diagnosis was wrong**

The `onboarding` 0-byte-exec defect was documented in
`docs/adr/architecture/ADR-extract-core-subsystems.md` §8 as a *flake*:

> *"the onboarding quarkus-jacoco agent flake (0-byte exec with green tests; observed 4×, always resolved by
> re-running `./mvnw verify -pl onboarding`) deserves a dedicated investigation issue."*

**It was neither flaky nor a JaCoCo problem, and the workaround never worked.** The behaviour was fully
deterministic. Bisecting the suite by test selection produced a very convincing table:

| Test selection | Tests | `jacoco.exec` |
|---|---:|---:|
| One plain JUnit class (`OnboardingStepValidatorTest`) | 8 | **63 KB** |
| One `@QuarkusTest` class (`OnboardingResourceTest`) | 9 | **669 KB** |
| All four `@QuarkusTest` classes together | 29 | **677 KB** |
| All plain tests, Quarkus classes excluded | 165 | **185 KB** |
| **Full suite (plain + Quarkus in one fork)** | **194** | **0 bytes** |

That pattern — *only the mixed fork fails* — pointed squarely at a Quarkus/JaCoCo interaction, and the working
hypothesis became "the Quarkus test extension resets the JaCoCo runtime between application restarts, and in a mixed
fork the final reset lands after the last recorded data". Plausible, and wrong.

**What was actually happening.** The bisection was correlating with the wrong variable. What the failing
configuration uniquely had was *the longest runtime* — and therefore the opportunity for a non-daemon thread to
outlive the test run. The decisive line was in the Surefire log, filtered out by every narrowing of the test set:

```
[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).
```

`jstack` on the lingering fork showed a non-daemon `pool-8-thread-1` blocked in
`StdioMcpMessageHandler$1.run()` → `BufferedReader.readLine()`, while `main` sat in `ForkedBooter.acknowledgedExit`.
The MCP stdio transport was enabled in tests; its reader blocks on `System.in` and only returns on EOF, which never
arrives. The JVM could not exit, so the JaCoCo shutdown hook never fired.

The trigger was a **wrong configuration prefix** in `onboarding/src/test/resources/application.yaml`:
`quarkiverse.mcp.server.stdio` instead of the real `quarkus.mcp.server.stdio`. `quarkiverse.*` is not a known
config root, so Quarkus discarded the key **silently** — no "Unrecognized configuration key" warning.

Full write-up: **§1.2.2**. Result: 194/194 pass, 0 fork kills, `jacoco.exec` 723,459 bytes, module coverage 82.31%.

> **Lesson worth keeping.** Three experiments appeared to rule out the real cause — raising
> `forkedProcessExitTimeoutInSeconds` (30 → 120) "had no effect" (it changed only how long the kill waited, never
> whether it happened); the forked command line showed a correctly-attached `-javaagent` (attachment was never the
> problem, *dumping* was); and `-Dquarkus.jacoco.data-file` produced no extra file (the merge path is dead code).
> Each was a true observation read as a false conclusion. When a process-based symptom is involved, **check for a
> forced kill and dump the thread stack before theorising about the framework.**
>
> Also note: `verify_jacoco_coverage.py` still contains a `merge_quarkus_jacoco_data()` path expecting a
> `jacoco-quarkus.exec` that this build never produces. It is dead code, and it cost time here.

---

## 7. Coverage-type gaps

### Unit coverage
Solid in `core` (78%) and the API modules. Weakest in `llm-providers` (65.8%), `skills` (66.3%),
`tools-builtin` (67.4%), and `common`/`tool-api` (~36%, though small).

### Integration coverage
`@QuarkusTest` usage is reasonable but uneven. `tools-builtin`, `mcp`, `persistence-fs`, `security`, and
`channel-web` have **zero** `@QuarkusTest` classes — everything is plain-JUnit or Mockito-based, so CDI wiring,
bean resolution, and startup ordering for those modules are never exercised end-to-end.

| Module | Test files | `@QuarkusTest` | Mockito |
|---|---:|---:|---:|
| core | 361 | 41 | 201 |
| app | 126 | 47 | 62 |
| persistence-sqlite | 43 | 28 | 8 |
| tools-builtin | 38 | **0** | 31 |
| llm-providers | 36 | 12 | 18 |
| persistence-postgres | 26 | 11 | 9 |
| persistence-fs | 25 | **0** | 14 |
| onboarding | 23 | 4 | 13 |
| mcp | 21 | **0** | 15 |
| security | 20 | **0** | 10 |

### Edge-case coverage — the largest single deficit
Branch coverage of **58.31%** means roughly **14,500 uncovered decision outcomes** (34,702 branches − 20,235
covered). Concentrated in:
- **Error/retry/fallback paths**: `BackoffRetryExecutor` (0%), `AgyTokenResolver` (7.1% branch),
  `CronFailureCleanup` (21.8% branch), `SelfCorrectionRetryManager` (untested, 214 LOC).
- **Null / empty / malformed input**: `DynamicToolSchemaParser` (0%), `HtmlArtifactUtil` (0%),
  `LlmExceptionClassifierChain` (0%).
- **Provider degradation**: `SpecializedChatModelFactory` (26.5% branch), `ProviderOAuthExchangeService` (50%).
- **Multi-backend divergence**: cron and checkpoint adapters (§4).

### Missing test *categories* entirely
1. ~~**No global timeout guard.**~~ **Fixed (0.4).** The root `pom.xml` now sets
   `junit.jupiter.execution.timeout.default=5 m` and `forkedProcessTimeoutInSeconds=900` as overridable properties.
   Note the first attempt used `PT5M`, which the JUnit Platform **silently ignores** — see §1.1.
2. **No architecture/invariant guard for reflection configs** (see §6).
3. **No migration or cross-backend parity assertions.**
4. **Test isolation defects.** `InteractiveSetupWizardTest` and `BootstrapOrchestratorTest` drive real
   production bootstrap code that reads `System.in`, writes `app/.env`, and shells out to `npm install -g`.
   `app/.env` is gitignored so the repo is not polluted, but a unit test performing a global npm install is
   not a unit test.

---

## 8. Recommended target: **80% line / 62% branch**

**Why not 90%.** The 90% threshold is not reachable at reasonable cost in this codebase, and chasing it would
degrade the suite:

- **~18% of the line denominator is non-logic**: 245 interfaces/records/enums/DTOs, 23 `*ReflectionConfig`
  classes, and assorted `*Metrics`/`*Constants`. These have near-zero branch density; testing them inflates the
  number without reducing risk.
- **`app` and `onboarding` are configuration/bootstrap heavy.** `app` has 187 classes but 18 DTOs in
  `app.ui.dto` alone and 20 config classes. Reaching 90% there means asserting on wiring, which is brittle.
- **Diminishing returns**: the classes with the largest remaining gaps are already 50–70% covered. Closing
  them is cheap; closing the last 10% is expensive.

**Why 80/62.** It is achievable by **~3,300 additional covered lines** concentrated in genuinely risky logic (§9),
and 62% branch forces real error-path testing rather than assertion padding. The measurement defects that made this
ask look expensive are already fixed (§1.2) — **3.7 percentage points of the distance to 75% were recovered without
writing a test**, so the remaining climb is materially shorter than the original plan assumed.

**Guard against gaming**: pair the reactor number with a **per-module floor of 60%** and a **no-regression
ratchet** (§10), so gains cannot be bought by over-testing an already-green module.

### Effort to target

Recomputed against the corrected baseline of **44,706 / 59,997** covered lines:

| Target | Additional covered lines needed | Notes |
|---|---:|---|
| 75% line | **+292** | Within reach of a single focused PR — Phase 1 alone clears it |
| **80% line** | **+3,292** | **Recommended** |
| 85% line | +6,292 | Requires covering app/config glue |
| 90% line | +9,292 | Not recommended |

*(The original draft of this table asked for +2,601 / +5,600 / +8,598 / +16,444. Those figures were computed from
the broken 70.66% baseline and double-counted the measurement repairs that §1.2 has since completed.)*

---

## 9. Prioritized action plan

### Phase 0 — Restore measurement integrity *(no tests written; recovered 3.7pp)*

**Status: complete.** Every item below is implemented and verified. The section is retained as a record of what
was wrong, not as outstanding work.

| # | Action | Status / detail |
|---|---|---|
| 0.8 | **Fix the broken `./mvnw verify`** | **Done — found while validating 0.1.** Failsafe inherited only `<argLine>`, so ITs booted as `prod` and `StartupSecurityValidator` aborted on the missing HMAC key; the build died in `verify`, and the JaCoCo gate bound to that phase never ran. Failsafe now gets the same `systemPropertyVariables` as Surefire. See §1.2.1. |
| 0.2 | **Fix the `onboarding` 0-byte `jacoco.exec`** | **Done.** Not a JaCoCo/Quarkus interaction: a wrong MCP config prefix (`quarkiverse.mcp.server.stdio`) left the stdio reader blocking on `System.in` on a non-daemon thread, so the fork was force-killed before the JaCoCo shutdown hook ran. Corrected to `quarkus.mcp.server.stdio`. Module 0% → **82.31%**. See §1.2.2. |
| 0.1 | **Wire the Java suite + coverage gate into CI** | **Done.** A `java-tests` job runs `./mvnw --batch-mode verify` (JDK 25, Python 3.12) plus a fatal `--strict-instrumentation` check. Validated locally before commit. See §1.2.3. |
| 0.3 | **Fix `BootstrapOrchestratorTest`** | **Done.** `BootstrapOrchestrator` short-circuits for `--help`/`-h`/`--version`/`-v`; `PlaywrightCliInstaller` runs commands through a bounded `runBounded()`; `InteractiveSetupWizard` no longer issues a blocking `System.in.read()` with no console. Test went from hanging indefinitely to **9.2 s**. |
| 0.4 | **Add a global JUnit timeout** | **Done.** `junit.jupiter.execution.timeout.default=5 m` plus `forkedProcessTimeoutInSeconds=900`, defined as overridable properties in the root `pom.xml`. *(First written as `PT5M`, which the JUnit Platform silently rejects — the guard was inert until corrected.)* |
| 0.5 | **Fail the gate on empty reports** | **Done.** `verify_jacoco_coverage.py` detects modules with passing tests but zero covered lines; warns by default, fails under `--strict-instrumentation` (now used in CI). |
| 0.6 | **Correct stale docs** | **Done.** `docs/TESTING_GUIDE.md` no longer pins a literal test count; §1.6 defines the acceptance rule as 0 failures / 0 errors / no drop, and defers the exact figure to `mise run test:summary`. |
| 0.7 | **Fix `mise run test:summary` reporting 0 tests** | **Done — found while validating 0.6.** The task aggregated surefire XML with `rg`, which is not declared in `[tools]`; with stderr discarded it printed a confident `Total: 0`. Rewritten in POSIX `find`/`grep`/`awk` with an explicit empty-state message. Now reports `Total: 6207 … Suites: 904`. |

### Phase 1 — P0: highest risk × highest line mass

**`core.agent.loop` + `core.tools`** — the agent execution engine. ~1,200 uncovered lines.

| Class | Missed | Coverage | Test type to add |
|---|---:|---:|---|
| `CheckpointResumeHandler` | 123 | 50% | Unit + state-machine: resume after approval, after denial, after timeout, after partial batch |
| `ParallelToolStepExecutor` | 116 | 52% | Unit: partial batch failure, mixed allow/deny, cancellation mid-batch |
| `ToolRegistry` | 83 | 56% | Unit: duplicate registration, missing tool, dynamic registration/deregistration |
| `LoopResultHandler` | 70 | 57% | Unit: FINISH-after-tool-result invariant, max-iteration exit, error exit |
| `ToolStepHandler` | 61 | 71% | Unit: guard ordering, blocked-tool path |
| `DynamicToolSchemaParser` | 54 | **0%** | Unit: malformed JSON, missing fields, unknown types |
| `ParallelBatchResumeHandler` | 53 | 67% | Unit: resume ordering, partial completion |
| `AgentSessionApprovalHandler` | 223 LOC untested | — | Integration: REST approve/deny/timeout |
| `DelegateApprovalCascade` | 158 LOC untested | — | Unit: cascade ordering, delegated approval |
| `PendingToolFailureHandler` (214 LOC), `ThinkingState` (154), `BatchResultAggregator` (100), `SelfCorrectionRetryManager`, `AwaitingApprovalState`, `ToolExecutionState`, `ObservationTransformer`, `ResumeToolInfoFormatter`, `AgentLoopTraceHelper` | untested | — | Unit — these are the loop's state vocabulary and are entirely unexercised |
| `core.lifecycle.CronFailureCleanup` | 72 | **28%** | Unit: cleanup on failure, retry exhaustion |
| `core.tools.ToolSchemaProvider` | 113 LOC untested | — | Unit |
| `core.routing.ModelRouter`, `NetworkProfilePolicyValidator` (135 LOC untested) | 57 | 67% | Unit: routing fallback, invalid profile |

**Status (2026-09-14).** Done, all verified by a green `clean verify`:

| Class | Missed before | Line coverage now | Test file |
|---|---:|---:|---|
| `DynamicToolSchemaParser` | 54 | **100%** | `DynamicToolSchemaParserTest` (new) |
| `CronFailureCleanup` | 72 | **100%** | `CronFailureCleanupTest` (2 → 30 tests) |
| `ToolStepEscalationHandler` | 37 | **100%** | `ToolStepEscalationHandlerTest` (new) |
| `ToolSchemaProvider` | 34 | **100%** | `ToolSchemaProviderTest` (new) |
| `ParallelBatchResumeHandler` | 53 | **100%** | `ParallelBatchResumeHandlerTest` (2 → 19 tests) |
| `ToolStepHandler` | 61 | **100%** | `ToolStepHandlerInvocationTest` (new) + existing `ToolStepHandlerGuardTest` |
| `ThinkingState` | 45 | **100%** | `ThinkingStateTest` (new, 22 tests) |
| `ExecutionFailureRecoveryService` | 49 | **100%** | `ExecutionFailureRecoveryServiceTest` (38 → 53 tests) |
| `PendingToolResumeExecutor` | 47 | **100%** | `PendingToolResumeExecutorTest` (8 → 23 tests) |
| `DelegatedCompletionHandoff` | 45 | **97.8%** | `DelegatedCompletionHandoffTest` (3 → 20 tests); the residual 3 lines are unreachable — §1.3 |
| `StepDecisionRouter` | 49 | **98.3%** | `StepDecisionRouterTest` (9 → 22 tests); the residual 2 lines are unreachable — §1.3 |
| `LoopResultHandler` | 70 | **99.4%** | `LoopResultHandlerTest` (3 → 39 tests) |
| `CheckpointResumeHandler` | 123 | **76.8%** | `CheckpointResumeHandlerUnitTest` (17 → 35 tests) |
| `ParallelToolStepExecutor` | 116 | **99.2%** | `ParallelToolStepExecutorTest` (3 → 21 tests) + existing `ParallelToolStepExecutorPreExecTest`; the residual 2 lines are unreachable — §1.3 |
| `BrowserPreflightHandler` | 43 | **98.8%** | `BrowserPreflightHandlerTest` (5 → 23 tests); the residual 2 lines are unreachable — §1.3 |
| `ToolRegistry` | 83 | **87.8%** | `ToolRegistryUnitTest` (extended) |

**Status (2026-09-15) — Tasks #8–#10 (additional `core.agent.loop` / `core.tools` targets).**

All verified by a green `./mvnw -o -pl core clean verify` (3,234 tests, 0 failures; aggregate `core` line
coverage **83.8%** — 19,676 / 23,486 — comfortably above the `0.74` floor).

| Class | Missed before | Line coverage now | Test file |
|---|---:|---:|---|
| `PersonaEscalationService` | 1 | **100%** (0 missed, 181 covered) | `PersonaEscalationServiceTest` (extended) |
| `GroupConsultationDelegator` | 36 | **100%** (0 missed, 66 covered) | `GroupConsultationDelegatorTest` (extended, 11 tests) |
| `RateLimitRetryScheduler` | 35 | 6 missed (all dead) / 86 covered | `RateLimitRetrySchedulerTest` (extended, 13 tests) |
| `CronSessionStatusSync` | 33 | 1 missed (dead) / 87 covered | `CronSessionStatusSyncTest` (extended, 20 tests) |
| `ToolGovernanceGuard` | 33 | **100%** (0 missed, 53 covered) | `ToolGovernanceGuardTest` (new, 12 tests) |
| `DelegateToAgentTool` | 33 | 8 missed (6 dead + 2 unit-unreachable) / 172 covered | `DelegateToAgentToolTest` (extended, 16 tests) |
| `LeakedToolCallParser` | 32 | 2 missed [362, 366] (JaCoCo closing-brace artifacts) / 254 covered | `LeakedToolCallParserTest` (9) + `LeakedToolCallParserUnitTest` (25) |

Dead / unreachable lines reported (not faked — per the dead-guard discipline):

- `RateLimitRetryScheduler` 165–167 & 209–211: `catch (Exception)` immediately after `catch (RuntimeException)` where
  the `try` body throws no checked exceptions → unreachable defensive guards.
- `CronSessionStatusSync.applyTerminal` 152: `default` branch of a `switch` over all six `AgentResult` subtypes
  (the explicit `case`s cover every sealed subtype) → unreachable.
- `DelegateToAgentTool` 415–418: CODER verification-gate max-depth skip — `depth` is re-derived from the session id
  (line 206) *before* the gate, and a CODER delegation only reaches the gate at `depth == 0` (the auto-debug
  recursion always targets `DEBUGGER`, never `CODER`), so `depth + 1 > maxDelegationDepth` is never true → dead.
- `DelegateToAgentTool` 427–429: `catch (IllegalArgumentException)` around `AgentRoleEnum.fromString("debugger")` —
  `"debugger"` is a constant role, so the lookup never throws → dead.
- `DelegateToAgentTool` 164–165: shutdown guard — only reachable when `Arc.container()` is non-null *and* not running;
  in a plain unit test `Arc.container()` is `null`, so the guard body is unit-test-unreachable (live in production).
- `LeakedToolCallParser` 362 & 366: JaCoCo closing-brace artifacts, not real dead code. Line 362 is the closing `}`
  of the `for` loop in `parseToolcallTagCall` (357–362) and line 366 is the `return` inside its `catch`; the
  adjacent lines 364–365 (catch body) and 367 (terminal `return`) are both covered, so the code path provably
  executes — only the braces carry no executable instruction of their own, which is why JaCoCo flags them.

Still open in this phase: `AgentSessionApprovalHandler` (223 LOC), `DelegateApprovalCascade` (158 LOC),
`PendingToolFailureHandler`, `BatchResultAggregator`, and the remaining loop state vocabulary;
plus the async body of `CheckpointResumeHandler` noted in §1.3. The `ToolRegistry`
and `LoopResultHandler` figures in the table above are the **pre-work** numbers — both have since been addressed.

> **Gate status (2026-09-15).** With `core` at **83.8%** line coverage (19,676 / 23,486) the `0.74` floor is cleared
> with ~10pp headroom, so `AgentSessionApprovalHandler` / `DelegateApprovalCascade` / `PendingToolFailureHandler` /
> `BatchResultAggregator` and the remaining loop-state vocabulary (tasks #11–#13) are **optional polish**, not
> gate-blocking. They remain valuable for hardening the approval/HITL paths but can be scheduled independently.
`ThinkingState`, `ExecutionFailureRecoveryService`, `StepDecisionRouter`, `PendingToolResumeExecutor`,
`ParallelToolStepExecutor`, `DelegatedCompletionHandoff` and `BrowserPreflightHandler` are the first entries from
the "loop state
vocabulary" and "highest line mass" rows to
be closed out. `ParallelToolStepExecutor` was the largest single gap in the package (102 missed, 58.0%) and was
long assumed to need a real thread pool; it does not — its collaborators are constructor-injected, and because
the class only ever uses its `ManagedExecutor` as a plain `Executor`, a mocked pool that runs each task inline
(or never runs it at all, which is what makes the timeout and interrupt arms deterministic) is sufficient.
`DelegatedCompletionHandoff` (45 missed, 66.2%) is a `final class` over two collaborators, so a plain
`mock(StatefulAgentStore.class)` plus a real `ObjectMapper` reaches every path except its three dead guards.
`BrowserPreflightHandler` (43 missed, 73.6%) is the same shape — four constructor-injected collaborators, one
package-private `@ConfigProperty` timezone field a same-package test can assign directly, and the deterministic
`CronTimeResolver` behind the schedule arm — so its entire three-step preflight ladder (schedule → gist → browser)
is reachable without a container; only its two dead guards remain.
The next largest remaining gaps in `core.agent.loop` are `CheckpointResumeHandler` (57 missed, 76.8%) — which
still needs a CDI container for its async body — followed by `AgentStateMachineRunner` (45 missed, 76.4%) and
`EngineStepHandler` (40, 79.8%), neither of which needs a container.


### Phase 2 — Security-critical and execution-boundary code

| Class | Missed | Coverage | Test type |
|---|---:|---:|---|
| `security.media.GovernedMediaDownloadService` | 89 | 65% | Integration: size cap, MIME rejection, redirect handling, timeout |
| `tools-builtin.playwright.PlaywrightCliTool` | 83 | 57% | Unit + integration: CLI absent, non-zero exit, output truncation |
| `tools-builtin.GithubTool` | 72 | 54% | Unit: 401/403/404/429, malformed payload |
| `tools-builtin.HostExecTool` | 60 | 73% | Unit: skill-grant enforcement, denied path |
| `sandbox.PodmanContainerExecutor` | 78 | 50% | Integration: container start failure, image absent, timeout, cleanup |
| `security.GovernedHttpClient` / `SecretVaultImpl` / `GovernedTelegramMediaResolver` | 53 each | 62–76% | Unit: policy denial, missing secret, cross-tenant isolation |
| `security.BootstrapCredentialIssuer` | untested | — | Unit: issue, rotate, replay rejection |
| `tools-builtin.ScheduleTaskTool` / `LocalSocksProxy` | 58 / 53 | 69% / 62% | Unit: fork-bomb guard, `CRON:` prefix rejection, naked-pronoun guard |

**Phase 2 status (2026-09-15) — security-module targets (first pass).** Three of the Phase 2 targets are closed
out in the `security` module, verified by a green `./mvnw -o -pl security clean verify` (222 tests, 0 failures;
PMD 7.17.0 and `spotless:check` clean):

| Class | Missed before | Missed now | Test file |
|---|---:|---:|---|
| `SecretVaultImpl` | 54 | **0** | `SecretVaultImplUnitTest` (extended, 5 → 21 tests) |
| `http.GovernedHttpClient` | 58 | 11 | `GovernedHttpClientTest` (extended, 14 → 27 tests) |
| `media.GovernedTelegramMediaResolver` | 53 | 10 | `GovernedTelegramMediaResolverTest` (extended, 17 → 37 tests) |

Dead / unreachable lines reported (not faked):

- `GovernedHttpClient` 287–288: the `CUSTOM_CA` trust mode without a `customCaVaultRef` is unreachable —
  `NetworkProfilePolicy.fromJson` (lines 137–141) **fails closed to `SYSTEM`** whenever `CUSTOM_CA` lacks a CA
  ref, so `tlsTrustMode() == CUSTOM_CA` always implies a non-blank ref.
- `GovernedHttpClient` 154 (cleanup-loop `catch (Exception)`): the only cleanup registered is `Arrays.fill(...)`,
  which cannot throw → dead defensive guard.
- `GovernedHttpClient` 245–246 (`revalidateDns` generic `catch`): a literal IP resolved via `InetAddress.getByName`
  cannot throw a non-`OutboundHttpException` → dead defensive guard.
- `GovernedHttpClient` 377–378 (`buildCustomCaContext` `catch (OutboundHttpException)`): the try body performs only
  cert/keystore/SSLContext operations, none of which throw `OutboundHttpException` → dead defensive guard.

Remaining (integration-only) for the three closed classes: `GovernedHttpClient` 146–149 (`InterruptedException`
from `HttpClient.send` — needs an interrupt-during-send) and 347 (the `Authenticator` proxy-auth callback, invoked
only by the JDK against a real authenticated proxy); `GovernedTelegramMediaResolver` 103 (Vert.x event-loop guard)
and 246 + 274–281 (the real `GovernedHttpClient` build+send path — needs a non-loopback endpoint, since
`MediaSecurityValidator` rejects loopback).

**Still open in Phase 2:** `media.GovernedMediaDownloadService` (89 missed — dominated by the HTTP/streaming path
in `downloadHttpWithRedirects`, which builds its own `HttpClient` with no injection seam, so it needs a
local-server integration fixture) and `sandbox.PodmanContainerExecutor` (78 missed — container/integration
fixture).

**Phase 2 status (2026-09-15) — `tools-builtin` targets (second pass).** All five `tools-builtin` targets are
closed out as far as unit tests can reach, verified by a green `./mvnw -o -pl tools-builtin clean verify`
(296 tests, 0 failures; PMD 7.17.0 and `spotless:check` clean):

| Class | Missed before | Missed now | Test file |
|---|---:|---:|---|
| `ScheduleTaskTool` | 58 | **7** | `ScheduleTaskToolTest` (extended, 11 → 26 tests) |
| `GithubTool` | 73 | 47 | `GithubToolCoverageTest` (extended, 3 → 12 tests) |
| `HostExecTool` | 60 | 27 | `HostExecToolUnitTest` (extended, 4 → 16 tests) |
| `LocalSocksProxy` | 53 | **9** | `LocalSocksProxyCoverageTest` (extended, 6 → 13 tests) |
| `PlaywrightCliTool` | 83 | 49 | `PlaywrightCliToolCoverageTest` (extended, 8 → 17 tests) |
| **Total** | **327** | **139** | 188 lines closed |

> **Measurement caveat — always re-measure after `clean`.** The "before" column was re-taken with `clean` after
> the first pass produced implausible deltas. `tools-builtin/target/classes` had been left stale by an earlier
> revision, so JaCoCo was mapping current line numbers onto old bytecode: `GithubTool` first reported 46 missed
> instead of its true 73. A coverage reading taken without a `clean` build is not trustworthy at line level.

Dead / unreachable lines reported (not faked):

- `ScheduleTaskTool` 412–419: `createAndPersistCron`'s "synthesise a cron from `nextFireTime`" fallback is
  unreachable — line 284 already rewrites a blank cron from `nextFireTime`, and `nextFireTime` is guaranteed
  non-null by the guard at 264, so the false arm of the ternary at 410 can never be taken.
- `ScheduleTaskTool` 395: the `: null` arm of `resolveNextFireTime`'s final ternary is unreachable — a
  non-`runOnce` call always arrives with a non-blank cron (guarded at 224), and both `runOnce` arms return earlier.
- `HostExecTool` 385: `HTML_INSPECTION_PATTERN`'s capture group is `[^\s;|&]+\.(html?|htm)`, so it can never be
  null or blank → the guard's body is dead.
- `HostExecTool` 348, 355–362: `UnsupportedOperationException` from `setPosixFilePermissions` and the non-POSIX
  `chmod` fallback are both unreachable on a POSIX filesystem.
- `PlaywrightCliTool` 418–419: `sessionName`'s fallback needs `MessageDigest.getInstance("SHA-256")` to fail,
  which cannot happen on any supported JRE.

Remaining (integration-only / environment-dependent):

- `GithubTool` 290–310, 323–347, 353–375: the whole child-process lifecycle (env injection, exit-code mapping,
  the virtual-thread output reader, `terminateProcess`). `gh` is not installed in this environment, so
  `ProcessBuilder.start()` throws and it is the `IOException` branch (313–316, already covered) that runs.
- `HostExecTool` 233–234, 263–277: `autoOpenGeneratedHtml` returning a real artifact — it then spawns the
  platform browser opener (`open` on macOS), a side effect a unit test must not have.
- `HostExecTool` 302–303: `Files.walk` failing inside `snapshotHtmlArtifacts` (e.g. permission denied) — defensive.
- `LocalSocksProxy` 179–180, 201–202, 205–207, 218: failure paths only (dest→client pipe error, interrupt during
  the pipe joins, destination-connect failure → `{5,5,…}` reply, `closeQuietly`'s `catch`). The happy-path tunnel
  is fully covered — see the `0.0.0.0` destination technique below.
- `LocalSocksProxy` 189 is a **JaCoCo false negative**, not a real gap: `connectTunnelsBidirectionally` asserts the
  destination receives the client payload, which is only possible if that line ran, yet JaCoCo reports `ci=0`.
  Do not chase it.
- `PlaywrightCliTool` 120–121, 246–257, 267–270, 278–283: the CLI **success** path (needs a live playwright-cli
  daemon with an open page). 228–229 / 231 / 237–238 additionally require `open`, which launches Chromium.
- `PlaywrightCliTool` 377, 385–389, 468–469: stale-session detection, the `IOException`/`InterruptedException`
  arms and `cleanup`'s `catch` — they need `pb.start()` to fail (CLI absent) or a running process to interrupt.

> **JaCoCo attribution quirk (`PlaywrightCliTool`).** Lines whose statement is a call to the private varargs
> helper `execWithSession(...)` — 278, 292, 304, 317, 325, 336 — are reported as fully missed (`mi>0, ci=0`)
> even though they demonstrably execute: the surrounding lines in the same method are covered and the tests pin
> the exact `playwright-cli` failure thrown *from* those calls. Treat these as covered-but-mis-attributed rather
> than as genuine gaps.

Techniques worth reusing (see `.agents/skills/amsha-maven-verify/SKILL.md` for detail):

- **`0.0.0.0` as a loopback-reachable, SSRF-guard-passing destination.** `InetAddress` classifies the wildcard
  address as none of loopback / link-local / site-local / multicast, so it clears `LocalSocksProxy`'s guard, while
  `connect()` still routes it to the local host. This is what makes the full CONNECT tunnel unit-testable.
- **A lenient shared stub for checked-exception collaborators.** `DirectExecutor.executeDirectly` declares
  `throws Exception`, so `when(...)` needs a `throws` clause; hoisting it into `setUp()` as
  `lenient().when(...)` keeps each test method clean and avoids `UnnecessaryStubbingException`.


### Phase 3 — Persistence parity and cron correctness

**Highest branch-coverage ROI in the reactor.**

| Target | Action |
|---|---|
| `PostgresCronStoreAdapter` (**5.0%** branch), `SqliteCronStoreAdapter` (23.6%), `FsCronStoreAdapter` (43.7%) | Add a **shared parameterized contract test** run against all three backends: due/not-due, run-once, lookback window, pause/resume/trigger, concurrent claim. |
| `PostgresCheckpointStoreAdapter` (69.6%), `FsCheckpointStoreAdapter` (42.4% branch) | Shared contract test: claim/release resume, state transitions, stale-row recovery. |
| `SqliteLlmProfileStoreAdapter` (285 LOC), `PostgresLlmProfileStoreAdapter` (283 LOC) | Untested — add CRUD + default-resolution tests per backend. |
| `SqliteMcpConnectionStoreAdapter` / `PostgresMcpConnectionStoreAdapter` (211 LOC each), `SqliteSessionCatalogAdapter` / `PostgresSessionCatalogAdapter` (~205 LOC each) | Untested — add per-backend tests. |
| **New: Flyway migration parity test** | Assert SQLite and PostgreSQL migration sets match in count, version, and semantics for all 12 pairs. |

#### Phase 3 status (2026-09-15) — cron store parity (first slice)

Three contract suites added, one per backend, each driving the **real** adapter rather than a mock (real SQLite file
DB, real embedded PostgreSQL, real `FilesystemFileStore` on a temp dir):

| Module | New test class | Tests | `CronStorePort` adapter | Module line coverage |
|---|---|---:|---|---|
| `persistence-fs` | `FsCronStoreContractTest` | 31 | `FsCronStoreAdapter` 46 → **0** missed | 76.8% → **78.5%** |
| `persistence-sqlite` | `SqliteCronStoreContractTest` | 32 | `SqliteCronStoreAdapter` 64 → **0** missed | 70.4% → **73.8%** |
| `persistence-postgres` | `PostgresCronStoreContractTest` | 32 | `PostgresCronStoreAdapter` 66 → **0** missed | 66.1% → **70.9%** |

**176 lines closed**; all three cron adapters are now at **100% line coverage**. `clean verify` is green on all
three modules (212 / 164 / 120 tests, PMD 7.17.0 clean, `spotless:check` clean).

**On "shared parameterized contract test".** The plan above asks for one suite run against all three backends. The
three adapters live in separate Maven modules and the reactor has **no test-jar infrastructure** and no abstract
contract base (verified: zero `test-jar` declarations, zero `abstract class *Contract|*Base` in test sources).
Introducing one would mean a new test-jar artifact, which breaks the documented module-scoped loop
(`./mvnw -o -pl <module> test` would stop resolving without an `install` of the producing module first) — and a new
*module* would trip the `--strict-instrumentation` empty-report guard in CI. So the spec is **duplicated by
convention**: the three classes mirror each other in structure and keep the same section ordering, with a header
comment on each saying so. Consolidating them is recorded as a follow-up, not done here.

**Fixtures worth reusing.** The `persistence-fs` suite builds a real `FilesystemFileStore` from an anonymous
`FsStoreProperties` returning a temp dir and injects it with `setAccessible(true)` — the same recipe as
`FsStoreSmokeTest`, but scoped to one adapter. The `persistence-sqlite` / `-postgres` suites generate a fresh
`user`/`session` per test and assert on **deltas** (`countAll` before vs after) because the table is shared across
the whole module's test run; user-scoped queries are asserted absolutely. `PostgresTestResource` transparently
falls back to the zonky embedded server when Docker is absent, so these run on a plain workstation.

#### Phase 3 status (2026-09-15) — checkpoint parity (second slice)

The same treatment applied to `CheckpointStorePort`, again with three mirrored suites driving the real adapters:

| Module | New test class | Tests | `CheckpointStorePort` adapter | Module line coverage |
|---|---|---|---|---:|
| `persistence-fs` | `FsCheckpointStoreContractTest` | 13 | `FsCheckpointStoreAdapter` 52 → **0** missed | 78.5% → **80.4%** |
| `persistence-sqlite` | `SqliteCheckpointStoreContractTest` | 20 | `SqliteCheckpointStoreAdapter` 47 → **0** missed | 73.8% → **76.3%** |
| `persistence-postgres` | `PostgresCheckpointStoreContractTest` | 29 | `PostgresCheckpointStoreAdapter` 56 → **0** missed | 70.9% → **76.2%** |

**155 lines closed**; all three checkpoint adapters are now at **100% line coverage**. `clean verify` is green on
all three modules (225 / 184 / 149 tests, PMD 7.17.0 clean, `spotless:check` clean).

**Why the Postgres suite is longer than the other two.** `PostgresCheckpointStoreAdapter` carries the widest port
surface of the three (upsert-with-steps, upsert-and-finalize, `stampChannelInfo`, `purgeOldChatHistory`,
`findLatestBySessionAndStatus`, `findLatestNonArchivedBySession`, …). Its existing unit test
(`PostgresCheckpointStoreAdapterTest`, 267 lines) mocks `Jdbi` and so only proves *delegation* — it cannot tell
whether the SQL is valid, whether the column aliases still line up, or what a query actually returns. The contract
suite covers exactly those questions, so the two are complementary rather than redundant. The same is true of
`SqliteCheckpointStoreAdapterTest`.

**Fixtures worth reusing.**

- Age-sensitive queries cannot be driven through `CheckpointStorePort.save`: `CheckpointDtoHelper.withStatus(...)`
  stamps `updatedAt = Instant.now()`, so a checkpoint can never be saved in the past. Both SQL suites therefore
  back-date fixtures with a raw `UPDATE agent_checkpoint SET updated_at = :ts`. Note the bind type differs per
  backend — `sqlite` binds `TemporalUtils.formatIsoLex(instant)`, `postgres` binds the `Instant` itself.
- The FS suite writes fixtures **straight through `FilesystemFileStore`** for the same reason. `FilesystemFileStore.write`
  builds its path with `List.of(segments)` internally, and `List.of` rejects nulls — so a fixture that deliberately
  exercises a null session segment must build the *outer* list with `Arrays.asList(...)` or it dies in the helper
  before reaching the adapter.
- The global sweeps (`findInProgressZombies`, `findStaleApprovals`, `recoverInProgressZombies`) and
  `purgeOldChatHistory` are unguarded and table-wide. Fixtures are back-dated and asserted by **membership**; the
  purge fixture is dated 2001 and swept with a 2005 cutoff so it cannot reach another suite's rows.

#### Phase 3 findings — cross-backend divergences the contract suite surfaced

These are **not test gaps**; they are places where `CronStorePort` implementations are not interchangeable. Each is
pinned by an assertion in all three suites and annotated with a divergence comment. None were "fixed" — that is a
product decision for the human, and the port has no stated contract for these cases.

| # | Behaviour | `fs` | `sqlite` | `postgres` |
|---|---|---|---|---|
| 1 | `save` with a null `nextFireTime` | accepted | **rejected** (`NOT NULL`) | **rejected** (`NOT NULL`) |
| 2 | `save` with a null `userId` / `chatId` / `goal` / `sessionId` / `cronExpression` | accepted | **rejected** | **rejected** |
| 3 | `save` — `createdAt` on **insert** | caller's value | caller's value | **`Instant::now()`**, caller's value discarded |
| 4 | `save` — `createdAt` on **re-save** | caller's value | caller's value | preserved stored value |
| 5 | `markFired(id, last, null)` | writes `nextFireTime = null` | **rejected** | **rejected** |
| 6 | `markSkipped(id, null, now)` | writes `nextFireTime = null` | **rejected** | **rejected** |
| 7 | `markCompleted` | **clears** `nextFireTime` | leaves it | leaves it |
| 8 | `markFailedIfStatus(id, null, reason)` | treats null as "no expectation", **fails the row** | no-op (`status = NULL` never matches) | no-op |
| 9 | `reclaimStaleCrons(null)` | **NPE** (`ref.isBefore(null)`) | early-return no-op | no-op via SQL NULL semantics |

Notes on impact:

- **#1–#2 are the sharpest.** `FsCronStoreAdapter.findDueCrons` explicitly guards
  `dto.nextFireTime() != null`, i.e. the FS backend is *written* to expect a state its SQL siblings cannot store.
  Any caller that saves a cron before a fire time is resolved works on `fs` and fails on `sqlite`/`postgres`.
  Both SQL migrations agree with each other (`db/migration/{sqlite,postgresql}/V2__scheduling_and_skills.sql`), so
  the outlier is `fs`, not either SQL backend.
- **#8 has no production caller today** — `markFailedIfStatus` appears only in the port, its three adapters and
  tests. Latent, but it will bite the first caller that passes a null expectation.
- **#9** is the reverse: a null threshold is not a realistic call, but `fs` is the only backend that throws on it.
- **#7 is benign in practice**: `markCompleted` sets status `COMPLETED`, and `findDueCrons` filters on `ACTIVE`, so
  a stale `nextFireTime` is never acted on. It is still a value-visible difference for any reader of the row.

#### Phase 3 findings — `CheckpointStorePort` divergences

The checkpoint slice surfaced two more, plus one latent defect that is *not* a divergence:

| # | Behaviour | `fs` | `sqlite` | `postgres` |
|---|---|---|---|---|
| 10 | `markCheckpointsFailed` — `failReason` text | `"Session terminated"` | `"Session cancelled"` | `"Session cancelled"` |
| 11 | `deleteCheckpoint` — reasoning steps | **deleted with the checkpoint** | left orphaned | left orphaned |

Notes on impact:

- **#10 is a caller-visible string mismatch.** A caller that matches on the reason (or surfaces it to a user) gets
  different text depending on the configured backend. Both SQL backends agree, so `fs` is the outlier — the same
  shape as cron divergence #1.
- **#11 leaks rows on both SQL backends.** `FsCheckpointHelper.deleteStepsForSession` cascades; the SQL
  `deleteCheckpoint` only removes the `agent_checkpoint` row, leaving `reasoning_step` rows with no parent. Nothing
  currently reads them (every read path is keyed by `checkpoint_id`), so this is a storage-growth issue rather than
  a correctness one — but `purgeOldChatHistory` is the only thing that reclaims them, and it only fires on rows
  older than its cutoff.
- **Latent defect (not a divergence):** `PostgresCheckpointStoreAdapter.saveSteps` logs `steps.size()` on line 356
  *before* the `steps != null` guard on line 360, so the null-tolerant guard is unreachable — a null list throws
  `NullPointerException` in the log statement. Deliberately **not** pinned by a test: the guard shows null was
  meant to be supported, so the log line is the bug. Reported here for the human to fix; the contract suite
  therefore does not call `saveSteps(id, null)`.

#### Phase 3 status (2026-09-15) — Flyway migration parity (third slice)

`app/src/test/java/com/amsha/app/db/FlywayMigrationParityTest.java` (new, 7 tests) asserts that the two migration
sets describe the same schema, which `AGENTS.md` §"Persistence or memory" requires but nothing enforced.

**What is compared** for each of the 12 `V<n>__<description>.sql` pairs: the static table set; every shared table's
column names and `NOT NULL` flags; the statically created index names; and the columns added by
`ALTER TABLE ... ADD COLUMN`. Column *types* are deliberately not compared — `TEXT` vs `TIMESTAMP WITH TIME ZONE`
and `INTEGER` vs `BIGINT` are the expected dialect mappings, not drift.

**Result: the two sets are in exact parity.** 42 static `CREATE TABLE` statements in each dialect, identical table
names, and identical column-name + nullability maps for all 42 — zero column-level drift. That is a genuinely good
result and worth recording: the migration sets have been kept in step by hand and the discipline has held.

**Three dialect-specific exceptions, each pinned by name:**

| Construct | SQLite | PostgreSQL |
|---|---|---|
| Full-text search | `CREATE VIRTUAL TABLE ... USING fts5` (`recall_messages_fts`, `archival_chunks_fts`) | `content_tsv tsvector` columns + `GIN` indexes (`idx_recall_content_fts`, `idx_archival_content_fts`) |
| Vector storage | `sqlite-vec` (`vec0`), created at runtime rather than in a migration | `rag_chunk_embedding` + `idx_rag_chunk_embedding_vec`, created inside a `DO $$ ... $$;` block guarded on `pg_available_extensions` |

So the comparison strips `DO $$ ... $$;` blocks and `CREATE VIRTUAL TABLE` statements before diffing, and the
PostgreSQL-only index/column sets are allow-listed.

**The allow-lists are asserted for exact equality, not as a superset** — this is the design point. A *new*
dialect-only index fails the build, and so does *removing* a construct that is still listed, so the exemptions
cannot rot into a blanket "PostgreSQL may differ here" exemption. The excluded constructs are separately pinned by
name, so a change in *how* a dialect implements full-text or vector search is visible rather than absorbed.

**Verified non-vacuous by mutation.** Both directions were confirmed to fail: appending a SQLite-only index to
`V1__execution.sql` produced `PostgreSQL is missing indexes SQLite creates ==> expected: <[]> but was:
<[idx_parity_probe]>`, and appending a PostgreSQL-only index produced `PostgreSQL-only indexes changed ==> expected:
<[idx_archival_content_fts, idx_recall_content_fts]> but was: <[idx_archival_content_fts, idx_pg_probe,
idx_recall_content_fts]>`. Both mutations were reverted.

Two anti-vacuity guards are built in, because a parity test that compares two empty sets passes silently: the
`CREATE TABLE` parser's match count must equal the number of `CREATE TABLE` keywords it saw, and the migrations are
resolved from the working tree (failing loudly if not found) rather than skipped — unlike
`ApiKeyAuthFilterTest`, which wraps its file read in `if (Files.exists(...))` and so can pass without checking
anything.

**Read from the working tree, not the classpath.** The `app` module depends on both backends, so the migration
resources *are* on its test classpath — but with `-pl app` they come from the installed `~/.m2` jars, which may lag
the sources under review. Reading the sibling module directories instead means the comparison is always against
what is actually being changed.

**Verified by a full-reactor `clean verify`** (all 28 modules SUCCESS, 9m38s), because the JaCoCo gate is
reactor-wide — it aggregates every module's CSV, so a `-pl app` run cannot exercise it meaningfully. The gate
reported:

```
Reactor JaCoCo line coverage: 77.83% (minimum: 74.00%, covered: 46696, total: 59997)
```

i.e. **3.83pp of headroom** over the `0.74` floor. `FlywayMigrationParityTest` ran green inside that build.

#### Phase 3 status (2026-09-15) — LLM-profile, session-catalog and MCP-connection adapters (fourth slice)

The remaining Phase 3 targets, again per backend and again against the real adapters. Unlike the cron and checkpoint
slices, these three ports already had *some* tests per backend — the work here was to extend the existing classes
rather than add mirrored suites, so the section records deltas instead of new files.

| Module | Test class (extended) | Tests | Adapter | Missed |
|---|---|---:|---|---|
| `persistence-sqlite` | `SqliteLlmProfileStoreAdapterTest` | 4 → 7 | `SqliteLlmProfileStoreAdapter` | 27 → **2** |
| `persistence-postgres` | `PostgresLlmProfileStoreAdapterTest` | 3 → 7 | `PostgresLlmProfileStoreAdapter` | 53 → **2** |
| `persistence-sqlite` | `SqliteSessionCatalogAdapterTest` | 6 → 9 | `SqliteSessionCatalogAdapter` | 19 → **0** |
| `persistence-postgres` | `PostgresSessionCatalogAdapterTest` | 3 → 8 | `PostgresSessionCatalogAdapter` | 24 → **0** |
| `persistence-sqlite` | `SqliteMcpConnectionStoreAdapterTest` | 1 → 3 | `SqliteMcpConnectionStoreAdapter` | 32 → **0** |
| `persistence-postgres` | `PostgresMcpConnectionStoreAdapterTest` | 2 → 3 | `PostgresMcpConnectionStoreAdapter` | 7 → **0** |
| `persistence-fs` | `FsMcpConnectionStoreAdapterTest` | 2 → 3 | `FsMcpConnectionStoreAdapter` | 8 → **0** |
| `persistence-fs` | `FsLlmProfileStoreAdapterTest` | 6 → 9 | `FsLlmProfileStoreAdapter` | 17 → **0** |

**183 lines closed**; six of the eight adapters are at 100% line coverage and the remaining two are at 2 missed lines
each, which are dead code (see the finding below). `clean verify` is green on all three modules (192 / 229 / 159
tests, PMD 7.17.0 clean, `spotless:check` clean), and the **full-reactor `clean verify` is green** (all 28 modules
SUCCESS, 9m43s) with the gate reporting:

```
Reactor JaCoCo line coverage: 78.14% (minimum: 74.00%, covered: 46880, total: 59997)
```

i.e. **4.14pp of headroom** over the `0.74` floor — up from 77.83% (46,696 covered) at the previous full-reactor run,
a gain of **184 lines** across the fourth slice.

**`FsLlmProfileStoreAdapter` was not on the plan.** It surfaced from a per-module re-measurement after the first
seven adapters were closed. It had no coverage of `findModelProfile`, `updateModelProfile`, `deleteModelProfile`,
`updateRouteAssignment`, `deleteRouteAssignment`, or either cache-invalidator branch — i.e. the same optimistic-lock
and callback surface as its SQL siblings, just never exercised. Worth noting as a general lesson: the roadmap's
target lists came from an older measurement, so a fresh `jacoco:report` per module should be taken *after* each slice
rather than trusted at slice start.

**Fixture note — the cache-invalidation port.** `fireConnectionChanged` / `fireProfileChanged` only call the port
when it is bound (`cacheInvalidator` is `@Nullable`), so every existing test silently took the `null` branch and the
two call sites stayed uncovered. The FS suite now injects a package-private recording stub
(`RecordingInvalidator`, a plain `ArrayList` of `"connection:<id>"` / `"profile:<id>"` strings) and asserts the exact
recorded sequence — which also pins the *negative* behaviour that matters: a rejected optimistic-lock update, or a
delete of an absent row, must **not** fire, because nothing was persisted and flushing the cache would be wasted
work. Note the SQLite and Postgres adapters have the identical two helpers; their call sites were already covered
incidentally by the `activateConnection` path, so only the FS suite needed the stub.

#### Phase 3 findings — `SessionCatalogStore` and MCP-connection divergences

| # | Behaviour | `fs` | `sqlite` | `postgres` |
|---|---|---|---|---|
| 12 | `record(active = true)` into a scope that already has an active row | **accepted** — a second active row is written | **rejected** (`UNIQUE constraint failed: idx_session_catalog_active`) | **rejected** (`duplicate key value violates unique constraint`) |
| 13 | `replaceHeaderSecretRefs` with a blank `connectionId` / `userId` | no blank guard — falls through to `"MCP connection not found"` | explicit guard: `"connectionId/userId required"` | explicit guard: `"connectionId/userId required"` |

Notes on impact:

- **#12 is a genuine API asymmetry, but currently latent.** The "one active row per scope" invariant is enforced by
  a **partial unique index** in both SQL migrations (`idx_session_catalog_active`, `ON session_catalog(owner_key,
  channel_type, channel_id) WHERE active = 1` / `WHERE active = TRUE`), and it is *maintained* only by
  `activateNew()`, which first issues `UPDATE ... SET active=FALSE,status='INACTIVE' WHERE <scope> AND active=TRUE`
  and then inserts with `activate = true`. `record()` does neither: the SQL adapters call
  `insert(..., e.active(), e.status(), false)` (the trailing `false` means "leave the stored `active`/`status`
  alone on conflict"), and the FS adapter simply writes the entry. So on `fs` a second active row is created and
  `resolveActive` becomes ambiguous, whereas on either SQL backend the same call throws. **No production caller
  passes `active = true` to `record`** — `SessionCatalogService` uses `activateNew` (lines 93, 162) and `setActive`
  (line 132), and `SessionReaper` / `SessionRegistry` / `DbConsoleResource` only read (`listInactiveBefore`,
  `bySessionId`). The port method itself therefore has no production caller today; the suite pins the behaviour so
  the first caller cannot be surprised. This is also the footgun that made the Postgres suite fail during
  development with `PSQLException: duplicate key value violates unique constraint "idx_session_catalog_active"`
  — fixtures that seed two *active* rows in one scope are invalid on both SQL backends.
- **#13 is a message-text divergence only** — same exception type, different text, same shape as cron divergence #1
  and checkpoint divergence #10: the two SQL backends agree and `fs` is the outlier. A caller that surfaces the
  message to a user, or matches on it, gets different text per backend.

#### Phase 3 finding — dead code in both SQL LLM-profile adapters

`SqliteLlmProfileStoreAdapter` and `PostgresLlmProfileStoreAdapter` each still miss exactly **2 lines**, and in both
cases they are the same construct — the `catch` block of `toJson`:

```java
private String toJson(List<String> list) {
    try {
        return mapper.writeValueAsString(list == null ? List.of() : list);
    } catch (Exception _) {
        return "[]";                                   // <- unreachable
    }
}
```

`mapper` is a private, non-injected, stock `new ObjectMapper()`, and the only argument ever passed is a
`List<String>` taken from `ModelRouteAssignmentDto.orderedProfileIds()`. Serializing a list of strings cannot throw
`JsonProcessingException` (the only checked type the call can produce), and there is no injection seam to make it
throw. The catch is therefore **unreachable dead code**, not a coverage gap — so it is reported here rather than
pinned with a test. The defensive `list == null` arm on the line above is likewise never taken, because both call
sites pass a record component that the DTO constructor requires to be non-null; that arm is at least *reachable* in
principle if the DTO ever allowed null, so it is left alone.

Removing the dead `catch` (or narrowing it to `JsonProcessingException` and letting the compiler prove it) would take
both classes to 100%. That is a source change, so it is left to the human.

#### Phase 3 — remaining work

- ~~`PostgresCheckpointStoreAdapter` (56 missed) / `FsCheckpointStoreAdapter` (52) / `SqliteCheckpointStoreAdapter`
  (47) — the same treatment: claim/release resume, state transitions, stale-row recovery.~~ **Done** (second slice
  above); all three at 0 missed.
- ~~`PostgresLlmProfileStoreAdapter` (53), `PostgresSessionCatalogAdapter` (24), `SqliteLlmProfileStoreAdapter` (27),
  `SqliteSessionCatalogAdapter` (19) — CRUD + default resolution per backend.~~ **Done** (fourth slice above);
  session-catalog at 0 missed, LLM-profile at 2 missed each (dead code).
- ~~`SqliteMcpConnectionStoreAdapter` (32) / `PostgresMcpConnectionStoreAdapter` (7) /
  `FsMcpConnectionStoreAdapter` (8) — per-backend tests.~~ **Done** (fourth slice above); all three at 0 missed.
- ~~**Flyway migration parity test**~~ **Done** (third slice above).
- **Not on the original list, found by re-measurement:** `FsLlmProfileStoreAdapter` (17) — closed in the fourth
  slice. The three persistence modules still hold a large amount of uncovered surface that the roadmap never
  enumerated (top gaps per module as of this measurement):

  | Module | Largest remaining gaps |
  |---|---|
  | `persistence-fs` | `FsIdentityStoreAdapter` 47, `FsLockManager` 44, `FsDirectiveStore` 44, `FsRecallMemoryStore` 39, `FsEvolutionEventStore` 39 (0 covered), `FsWebSessionStoreAdapter` 36 |
  | `persistence-sqlite` | `SqliteArchivalMemoryStore` 49, `GlobalKnowledgeBase` 42, `SqliteVectorIndexCompatibilityInitializer` 27, `SqliteMemoryRetrievalAdapter` 24, `SqliteCheckpointHelper` 22 |
  | `persistence-postgres` | `PostgresAdvisoryLockAdapter` 24, `PostgresMemoryRetrievalAdapter` 23, `PostgresVectorIndexCompatibilityInitializer` 21, `PostgresExecutionTraceStoreAdapter` 15, `PostgresIdentityStoreAdapter` 15 |

  These are the natural candidates for a **Phase 3b**; the roadmap's original Phase 3 list is now fully closed.


### Phase 4 — LLM provider robustness

| Target | Missed | Coverage |
|---|---:|---:|
| `AgyCliExecutor` | 81 | 42% (34% branch) |
| `AgyTokenResolver` | 64 | 11% (**7.1%** branch) |
| `FoundryResponsesChatModel` / `KimchiResponsesChatModel` / `AgyResponsesChatModel` | 67 / 67 / 65 | 52–72% |
| `FoundryCliExecutor` | 65 | 48% |
| `SpecializedChatModelFactory` | 59 | 45% (26.5% branch) |
| `NvidiaMessageSanitizer` | 54 | 65% |
| `ProviderOAuthExchangeService` | — | 50% branch |
| `llm-providers.reasoning` package (21 prod classes, **1** test), `llm-providers.routing` (21 prod, **4** tests) | — | Package-level gap |

Test type: contract tests per provider (timeout, 429/5xx, malformed SSE, token refresh failure, capability
fallback), not more happy-path completions.

#### Phase 4 status (2026-09-15) — Antigravity/Foundry CLI helpers + the specialized factory (first pass)

All seven Phase 4 targets live in the single module `llm-providers`, so this phase runs on the fast
`-pl llm-providers` loop. This pass took the two CLI helpers and the specialized-model factory:

| Target | Before | After | Closed |
|---|---|---|---|
| `AgyTokenResolver` | 64 missed / 8 covered (11% line, **7.1%** branch) | **0 missed** / 72 covered (**100%** line, 88.6% branch) | 64 |
| `AgyCliExecutor` | 81 missed / 59 covered (42% line, 34% branch) | **4 missed** / 136 covered (97.1% line, 86.9% branch) | 77 |
| `FoundryCliExecutor` | 65 missed / 61 covered (48% line) | **7 missed** / 119 covered (94.4% line) | 58 |
| `SpecializedChatModelFactory` | 59 missed / 48 covered (45% line, 26.5% branch) | **2 missed** / 105 covered (98.1% line, 97.6% branch) | 57 |

**256 lines closed.** Module `clean verify` is green (360 tests, PMD 7.17.0 clean, `spotless:check` clean across 110
files). The 3 skipped tests are 2 pre-existing (`OggOpusToWavConverterTest`) plus 1 deliberate skip documented below.

**`AgyTokenResolver`: the root cause was an environment-conditional test.** The previous revision of
`AgyTokenResolverTest` wrapped its assertions in `if (AgyCliExecutor.isAvailable()) { ... } else { ... }`. Because
`~/.local/bin/agy` **is** installed on this machine, `isAvailable()` is true, `resolveToken` short-circuits at
`return "agy-cli-session"` and the vault / extractor / environment fallbacks are never reached — the class sat at
7.1% branch coverage while the test file looked comprehensive. The fix is to pin the probe with
`Mockito.mockStatic(AgyCliExecutor.class)`, which makes every branch deterministic and reachable on any machine.
This is the same class of defect as the cron/checkpoint suites' back-dated fixtures: **a test whose coverage depends
on the machine is not a test.**

**`AgyCliExecutor`: the subprocess path is driven with a fake executable.** `execute` spawns the real `agy` binary
via `ProcessBuilder`, so it cannot be mocked without replacing the JDK. Each test instead writes a small POSIX shell
script into a temp dir and passes it as the `agyExe` argument — the executor only ever calls `agyExe.toString()`, so
a script is indistinguishable from the real binary. That makes canned stdout, a chosen exit code, chosen stderr and
a deliberate hang all deterministic. A quoted heredoc (`cat <<'DELIM' … DELIM`) keeps the JSON payload verbatim.
The timeout arm needs no sleep: `Duration.ZERO.toSeconds()` is `0`, so `waitFor(0, SECONDS)` returns false
immediately and the executor force-destroys the process.

**`FoundryCliExecutor`: the same fake-executable recipe, one extra wrinkle.** `FoundryCliExecutorTest` mirrors the agy
suite (31 tests) but the response shape differs — `execute` reads `root.path("text")` and, if that is blank *and* the
whole document is textual, falls back to `root.asText()`. So the fake CLI emits both `{"text":"…"}` and a bare
`"plain string response"` payload. `buildCliPrompt` also has a nested `AiMessage` branch that renders text **and**
tool calls independently, unlike the agy version's single `&&`-chained condition — a blank-text assistant turn with
tool requests must still emit `Assistant Tool Call: …`.

The machine wrinkle: `/opt/homebrew/bin/foundry` **is** installed (a symlink into `Cellar/foundrylocal/0.10.3`), and
`resolveCliPath` probes that absolute path before `~/.local/bin/foundry`. So the `~/.local` probe is shadowed and its
test is skipped by assumption. The `PATH` scan *is* reachable, but only by setting `user.home` to `""` — a blank
`user.home` skips the whole `if (!userHome.isBlank())` block, which is the only way in from a test.

**`SpecializedChatModelFactory`: the CDI boundary is the real constraint.** This package-private factory had no direct
test at all — it was only reached through `ChatModelFactory`. `SpecializedChatModelFactoryTest` (22 tests) covers
adapter dispatch, every per-adapter builder's default-resolution, and `resolveGovernedTransport()`'s three paths
(no container, container with an available handle, container with an unavailable handle) plus both catch arms — the
latter by pinning `Arc.container()` with `Mockito.mockStatic(Arc.class)` and throwing a `NoClassDefFoundError` and an
`IllegalStateException` respectively.

Two things surfaced that are worth recording:

- **Mockito rejects nested stubbing.** `when(container.instance(X.class)).thenReturn(handle(true, service))` fails
  with `UnfinishedStubbingException`, because the `handle(...)` helper calls `Mockito.mock`/`when` *inside* the outer
  `when(...)` expression. Build the mock into a local first, then stub. (Mockito's own hint #3 names this case.)
- **`AnthropicChatModel.builder().build()` and `ChatModelFactory.createOpenAi(...)` require a live ArC context.**
  Both construct a Quarkus *Reactive REST Client*, which throws "The Reactive REST Client needs to be built within the
  context of a Quarkus application with a valid ArC (CDI) context running" outside one. `GoogleAiGeminiChatModel` and
  `OllamaChatModel` take a different HTTP path and *do* build in a plain unit test — which is exactly why the
  factory's Gemini/Ollama lines were already covered while its Anthropic lines were not. The tests therefore assert
  the **routing** (that the call reaches the Anthropic/OpenAI builder and cannot complete without CDI) without
  matching on the third-party message, and the two remaining lines — the `case "anthropic", "claude"` switch arm and
  `return builder.build()` — are left to a future `@QuarkusTest`.

**Environment-locked lines (reported, not faked).** `System.getenv` cannot be set from a test, so these stay
uncovered and are *not* pinned with reflection:

| Class | Lines | Why |
|---|---|---|
| `AgyCliExecutor` | 43–45 | the `ANTIGRAVITY_AGENTAPI_EXE` custom-executable override |
| `AgyCliExecutor` | 62 | the `PATH`-scan match — no `agy` is on `PATH` here, and `PATH` cannot be set |
| `AgyTokenResolver` | 105–107 (branches only) | the `AMSHA_AGY_API_KEY` / `GEMINI_API_KEY` / `GOOGLE_API_KEY` `.filter(...)` predicates — never invoked because all three are unset |
| `FoundryCliExecutor` | 42–44 | the `AMSHA_FOUNDRY_EXE` custom-executable override |
| `FoundryCliExecutor` | 54–56 | the `~/.local/bin/foundry` probe — shadowed by the installed `/opt/homebrew/bin/foundry` |
| `FoundryCliExecutor` | 65 | the `PATH`-scan match — no `foundry` is on `PATH` here |
| `SpecializedChatModelFactory` | 52, 199 | the Anthropic switch arm and `builder.build()` — need a live ArC context |

The assertions that depend on those variables are guarded with `Assumptions.assumeTrue(...)` so they report as
**skipped**, not silently passed. Two further branches in `AgyTokenResolver` (lines 82, 154) are the
try-with-resources *abnormal-close* branches, reachable only if `VaultKey.close()` throws — `VaultKey` is final with
a zeroing `close()`, so they are unreachable without a deliberate fault injection.

**Remaining Phase 4 targets:** `FoundryResponsesChatModel` (67), `KimchiResponsesChatModel` (67),
`AgyResponsesChatModel` (65), `NvidiaMessageSanitizer` (54), `ProviderOAuthExchangeService` (50% branch), plus the
package-level gaps (`llm-providers.reasoning`: 21 prod classes / 1 test; `llm-providers.routing`: 21 prod / 4 tests).
The roadmap asks for per-provider **contract** tests — timeout, 429/5xx, malformed SSE, token refresh failure,
capability fallback — not more happy-path completions. The three `*ResponsesChatModel` classes are SSE-streaming
wrappers and share an `SseAccumulator` shape, so they are the natural next slice.

### Phase 5 — MCP, ONNX, skills, and app glue

- `mcp.transport`: `HttpMcpTransportClient` (358 LOC untested), `StdioMcpTransportClient` (67),
  `McpConnectionLifecycleOrchestrator` (62), `McpManagementConnectionSetup` (176 LOC untested) —
  add transport-level integration tests (connect failure, handshake timeout, reconnect, disconnect cleanup).
- `local-ml-onnx`: `LocalOnnxAudioTranscriptionAdapter` (77), `LocalAudioTranscoder` (65, **23.8%** branch),
  `OnnxAudioTranscriptionBundleSpec` (60) — add unit tests for codec edge cases and bundle validation.
- `skills`: `SkillsJarSkillProvider` (117, 41% branch), `ClasspathSkillProvider` (0%), `DatabaseSkillProvider` (0%).
- `app`: `IdentityResourceHelper` (82, 0%), `DbQueryAstScanner` (74, 0%), `DbConsoleTenantResolver` (60, 0%),
  `McpImportResource` (44, 0%), `McpSetupResource` (39, 0%), `DbResultMapper` (39, 0%),
  `BackendValidator` (23, 0%), `SandboxValidator` (17, 0%), `ActiveWorkspaceFilter` (18, 0%).
- `onboarding` (once measurement is fixed): `SettingsResource` (216, 0%), `BootstrapResource` (145, 0%).

### Phase 6 — Cross-cutting invariant tests *(cheap, high value)*

| Test | Rationale | Est. effort |
|---|---|---|
| **Reflection registration completeness** — scan JDBI records/DAOs/DTO rows per module and assert each is registered in that module's `*ReflectionConfig` | 23 configs, 0 tests. Protects native builds from a class of runtime-only failure. | Low |
| **Migration parity** (SQLite vs PostgreSQL) | Required by `AGENTS.md`; currently unverified. | Low |
| **Cross-backend store-port parity** | Shared contract suite across all 3 backends. | Medium |
| **Global timeout guard** | Prevents the observed indefinite hang. | Very low |
| **`@QuarkusTest` coverage for modules at zero** (`tools-builtin`, `mcp`, `persistence-fs`, `security`, `channel-web`) | These modules never boot a CDI container in tests. | Medium |

---

## 10. Guardrails to prevent regression

1. **CI enforcement (non-negotiable).** ✅ **In place** — the `java-tests` job runs `./mvnw verify` on every push
   and fails on the coverage gate. It must stay on `verify`: `package` reaches neither PMD nor JaCoCo.
2. **Ratchet, don't just threshold.** ✅ **In place** — `jacoco.minimum.line.coverage` was `0.70` while the real
   number was **74.51%**, four points of slack a regression could quietly consume. It is now **`0.74`**, leaving
   ~0.5pp (~300 lines) of deliberate headroom so incidental churn does not trip the gate. Continue raising it as
   §9 lands: 0.74 → 0.75 → 0.78 → 0.80. Do not set it flush against the measured value — the gate compares the raw
   ratio and would fire on rounding.
3. **Per-module floor.** Add `--module`-level minimums (start at 60%) so `classifier-api` at 100% cannot
   subsidise `persistence-postgres` at 66%.
4. **Add a branch gate.** Line coverage is **74.51%** while branch is **58.31%** — a 16-point gap that a
   line-only gate cannot see. Gate branch separately (start at 58%, target 62%). This is the largest remaining
   hole in the enforcement story.
5. **Fail on empty module reports.** ✅ **In place** — `--strict-instrumentation` runs as a fatal CI step. This is
   the specific guard for the §1.2.2 failure class; keep it fatal.
6. **Report the badge from a real run.** `analyze_jacoco_coverage.py --badge` exists; publish it only from CI.
   The README badge was hand-corrected to 74.51% on 2026-09-14 — it should be generated, not typed.
7. **Align `analyze_jacoco_coverage.py` with the gate.** ✅ **In place** — `--target` moved `0.72` → `0.80` (the §8
   goal, and above the gate) and the badge colour bands moved `0.70` → `0.74`. Keep both above
   `jacoco.minimum.line.coverage`: the analyzer must not report "target met" for a level the build rejects.
8. **Exclude non-logic from the target, explicitly.** Rather than a blanket 80%, define the denominator to
   exclude `*ReflectionConfig`, `*Metrics`, `*Constants`, and DTO records — then hold *logic* classes to a
   higher bar (85%).

---

## 11. Appendix — methodology and caveats

**Measurement procedure**

```bash
export JAVA_HOME="$HOME/.local/share/mise/installs/java/temurin-25.0.3+9.0.LTS/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
# One command now produces everything: tests, ITs, PMD, and the coverage gate.
./mvnw --batch-mode clean verify < /dev/null
python3 ./scripts/analyze_jacoco_coverage.py --summary
mise run test:summary
```

> Earlier drafts of this document needed three separate invocations plus two test exclusions, because
> `verify` was broken and `BootstrapOrchestratorTest` hung. Both are fixed (§1.2); the single command above is
> sufficient and is what CI runs.

**Caveats — read before quoting these numbers**

- **Coverage numbers are per-module.** A module's report measures only code exercised by that module's own
  tests. Cross-module coverage (e.g. `core` code exercised from `app`'s `@QuarkusTest`) is attributed to the
  module where the class lives, but only when that module's own tests run it. This understates coverage for
  modules that are primarily consumed by `app`.
- ~~**`app` and `onboarding` required separate runs.**~~ **No longer true.** Both were consequences of the defects
  fixed in §1.2 — the full-reactor run hung on `BootstrapOrchestratorTest` (0.3) and `verify` died before the gate
  (0.8). A single `clean verify` now runs the whole reactor green.
- ~~**`onboarding` coverage is not captured at all.**~~ **No longer true.** Its 0.00% was a measurement defect, not
  a coverage level; it measures **82.31%** (§1.2.2). Reactor-wide figures no longer include any artificial "missed"
  mass from this source. *(The 15,291 uncovered lines quoted in §3 are all real.)*
- **Static scan limits.** The "no test reference" scan matches class simple names against test-source text.
  It cannot see reflective use, string-keyed DI, or indirect execution — which is why 115 of 399 flagged
  classes turned out to be covered. Treat the static list as a *candidate* set, and the JaCoCo report as truth.
- **7 skipped tests** across the suite were not investigated.
- **UI coverage is out of scope** (separate Vitest/V8 pipeline via `analyze_ui_coverage.py`).

**Reproduction artifacts** (this run, not committed):
`/tmp/amsha_full_test.log`, `/tmp/amsha_app_test.log`, `/tmp/amsha_app2.log`, `/tmp/amsha_onb.log`,
`/tmp/amsha_onb_verify.log`
