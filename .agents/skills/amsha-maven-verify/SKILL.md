---
name: amsha-maven-verify
description: "Reusable Maven verification workflow for the Amsha multi-module project (Quarkus/LangChain4j). Use this skill when asked to verify, review, build, or check Java changes before commit/merge, running the required AGENTS.md gates (tests, PMD, Spotless, clean install, doc verification). It captures the exact JDK, offline-build, and Surefire flags needed in this environment, and the hard rule that git staging and commits are reserved for the human."
agent_created: true
---

# Amsha Maven Verify

## Overview

This skill encodes the verification gate sequence required by `AGENTS.md` for the
Amsha project, plus the environment-specific incantations that make Maven run at
all in this workspace. It exists because the obvious commands fail here (no `java`
on PATH, `timeout` wrapper missing, Surefire aborts on `-am` downstream modules
with no matching tests) and because `AGENTS.md` forbids the agent from touching
git staging or commits.

Use it whenever the user says "check", "verify", "all fixed do check", "run the
gate", "is it merge-ready", or asks for a build/review of Java changes.

## Environment Setup (run FIRST, every time)

No Java is on the default PATH. Export the JDK 25 toolchain before any Maven call:

```bash
export JAVA_HOME=/Users/srinivasanragothaman/.sdkman/candidates/java/25.0.3-tem
export PATH="$JAVA_HOME/bin:$PATH"
```

Equivalent alternative (the toolchain the project's `mise.toml` pins, also verified
present) — either works:

```bash
export JAVA_HOME="$HOME/.local/share/mise/installs/java/temurin-25.0.3+9.0.LTS/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

Verify with `java -version` (expect 25.x). If `JAVA_HOME` is wrong, `./mvnw`
prints "Unable to locate a Java Runtime" and exits.

Other environment notes:
- The `timeout` command is **not** installed. Do not prefix Maven with `timeout`.
  To bound a run, rely on Maven's own idle/surefire timeouts or `run_in_background`.
- Prefer offline builds with `-o` when the dependency tree is already resolved.
  Drop `-o` only if a command reports a missing artifact it should fetch.
- The wrapper is `./mvnw` at the repo root. Use it, not a system `mvn`.

## The Verification Gate Sequence

Run these in order. Each must pass before declaring the change merge-ready.

### 1. Targeted tests (fast feedback)

Run only the affected test classes first. Use `-pl <module> -am` so upstream
modules build, and ALWAYS add `-Dsurefire.failIfNoSpecifiedTests=false` — without
it, `-am` descends into downstream modules that have no matching test and Surefire
aborts the whole build with "No tests matching pattern ... were executed!".

```bash
./mvnw -o -pl llm-providers -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=GovernedLangChainHttpClientTest,ModelArenaResourceTest,ModelArenaHelperTest
```

- Pass `-Dtest=...` only the classes the change touches.
- Confirm the count line (`Tests run: N, Failures: 0, Errors: 0`).

### 2. PMD gate (per affected module)

`AGENTS.md` requires a PMD pass even with `-DskipTests`; `package` alone does not
reach the `verify` execution.

```bash
./mvnw -pl <affected-module> -am pmd:check -DskipTests
```

PMD config: `app/src/main/resources/amsha-rules.xml` — 80 NCSS/method, cognitive
complexity 50, class ≤ 500 lines. Expect output `BUILD SUCCESS` for all modules
in the reactor.

### 3. Spotless (formatting)

```bash
./mvnw -o spotless:check
```

If it fails, the user runs `./mvnw spotless:apply`; the agent may run
`spotless:apply` only after an explicit edit request — for a pure verify pass just
report the violation. All files clean prints nothing special; a failure lists the
exact files.

### 4. Full clean install

```bash
./mvnw -o clean install -DskipTests
```

This compiles every module and reaches the PMD `verify` execution end-to-end.
`BUILD SUCCESS` with all ~28 modules is the merge-ready signal. Keep `-DskipTests`
for speed unless the user wants the full suite.

### 5. Markdown / docs

After any Markdown edits (ADRs, docs):

```bash
python3 ./scripts/verify_docs.py            # AUTO-FORWARDING SHIM — SIGTERMs (exit 137) on this machine
python3 ./scripts/quality/verify_docs.py    # <- run the canonical script instead
```

Expect `Scan complete: found 0 errors and 0 warnings.` The shim uses `runpy.run_path`
and gets killed before it prints anything; the canonical file behind it works fine.
Same failure signature as `find` over the repo (exit 137) — when a repo-traversing
script dies silently, try the canonical path before assuming a real error.

## Measuring Test Coverage

Use this when asked about coverage levels, coverage gaps, or "what should we test
next".

**One command now produces everything** — tests, ITs, PMD, and the coverage gate:

```bash
./mvnw --batch-mode clean verify < /dev/null
python3 ./scripts/analyze_jacoco_coverage.py --summary
python3 ./scripts/jacoco_lines.py --root . <fully.qualified.ClassName>   # missed line numbers for one class
/opt/homebrew/bin/mise run test:summary   # surefire counts + jacoco + UI coverage
```

Use `clean`: `test:summary` and the gate both sum on-disk `TEST-*.xml`, so stale
reports from an earlier run inflate the count (8 tests / 4 suites, observed).

**Baseline (2026-09-14, after `clean verify`)**: line **76.10%** (45,657/59,997),
branch **59.62%**, method **79.62%**; **6,528 tests / 905 suites**, 0 failures,
0 errors, 7 skipped. UI: 172 tests, 58.67% line. (Earlier the same day, before the
Phase-1 test additions: 74.51% line / 58.31% branch / 78.85% method, 6,199 tests /
900 suites — so run-to-run jitter is ~2 lines and Phase 1 has gained +951 lines so
far. `mise run test:summary` reproduces all of these in one shot.)

As of **2026-09-15 (Phase 1 Tasks #8–#10)** the `core` module alone measures **83.8%** line coverage
(19,676 / 23,486) — well above the `0.74` floor — so the approval/HITL cluster (#11–#13:
`AgentSessionApprovalHandler`, `DelegateApprovalCascade`, `PendingToolFailureHandler`, `BatchResultAggregator`,
and the remaining loop-state vocabulary) is **optional polish, not gate-blocking**. The reactor-wide figure in CI
is what the gate enforces.

The gate is `app/pom.xml` → `scripts/verify_jacoco_coverage.py`, bound to `verify`.
Its floor is the `jacoco.minimum.line.coverage` property in the root `pom.xml`
(**0.74** as of 2026-09-14; it was 0.70, which sat four points below reality).
**CI runs it**: a `java-tests` job in `.github/workflows/ci.yml` runs
`./mvnw --batch-mode verify` plus a fatal `--strict-instrumentation` step, and reads
the floor out of the POM rather than hardcoding it.

### Traps that will hang or corrupt a coverage run

- **Always redirect stdin (`< /dev/null`).** Bootstrap code does a blocking
  `System.in.read()`. There *are* guards now (`junit.jupiter.execution.timeout.default=5 m`
  and `forkedProcessTimeoutInSeconds=900`, both properties in the root pom), but the
  redirect is still required.
- **`mvn test` alone does not write `jacoco.csv`** — follow with
  `./mvnw jacoco:report -q`, or just use `verify`.
- **Never trust stale reports.** A previously generated `jacoco.csv` can show
  `LINE_COVERED=0` for every class (the `antrun touch jacoco.exec` step masks
  missing instrumentation). Regenerate before quoting any number.
- **`mise run test:summary` counts Surefire XML only — not Failsafe.** A hand-rolled
  "sum every `TEST-*.xml`" script that also globs
  `*/target/failsafe-reports/TEST-*.xml` reports a *larger* test count than the task
  and than the README badge. Observed 2026-09-14: task `6,436 tests / 905 suites` vs
  a both-globs script `6,441 / 906` — the extra 5 tests / 1 suite were
  `SessionOwnershipIT`. The **Surefire-only figure is the house convention**; use it
  for badges and docs, and say which one you are quoting.
- **A wrong config prefix is silently ignored.** `onboarding` reported 0% for
  months because its test yaml said `quarkiverse.mcp.server.stdio` instead of
  `quarkus.mcp.server.stdio`. The unknown key produced no warning, the MCP stdio
  reader stayed enabled, and its **non-daemon** thread blocked in `readLine()` on
  `System.in` forever — so the JVM never exited, Surefire force-killed the fork, and
  the JaCoCo shutdown hook never ran. Fixed (module now 82.31%). **Symptom to
  recognise:** `Surefire is going to kill self fork JVM`. Check for a forced kill
  and `jstack` the fork *before* theorising about the framework.
- **Surefire and Failsafe do NOT share configuration.** A property set on one does
  not reach the other. This was a live bug: Failsafe had only `<argLine>`, so `*IT`
  booted Quarkus as `prod`, `StartupSecurityValidator` aborted on the missing audit
  HMAC key, the build died in `verify`, and the gate bound to that phase never ran.
  The two plugin blocks in the root `pom.xml` are now identical on purpose.
- `BootstrapOrchestratorTest` used to hang the `app` module (unbounded
  `npm install` via `PlaywrightCliInstaller`). **Fixed** — no exclusion needed.
- **The JaCoCo gate is REACTOR-WIDE, so `-pl app` cannot verify it.** `app/pom.xml` binds
  `scripts/verify_jacoco_coverage.py --minimum ${jacoco.minimum.line.coverage}` to `verify`, and that script parses the
  root `pom.xml`'s `<modules>` list and aggregates *every* module's `target/site/jacoco/jacoco.csv`. Running
  `clean verify -pl app` therefore aggregates whatever reports happen to be on disk — a mix of fresh and stale — and
  the resulting percentage is meaningless. Only a full-reactor `./mvnw -o clean verify` exercises the gate honestly.
  (It also means the gate never runs during a `-pl persistence-* clean verify` slice.)
- **`scripts/verify_jacoco_coverage.py` is the same 19-line `runpy` auto-forwarding shim as `verify_docs.py`.** Unlike
  `verify_docs.py`, **it runs fine under Maven** — a full-reactor `clean verify` on 2026-09-15 printed
  `Reactor JaCoCo line coverage: 77.83% (minimum: 74.00%, covered: 46696, total: 59997)`. So do not pre-emptively
  work around it; only fall back to the canonical `scripts/quality/verify_jacoco_coverage.py` if it does SIGTERM.
- **Reactor-wide coverage on 2026-09-15 was 77.83%** against the `0.74` floor (headroom 3.83pp), total 59,997 lines —
  which matches the CSV total the docs quote, so the gate and the manual CSV reading agree.

### Analysing gaps correctly

- Static "class name not referenced in any test" scans **overstate** gaps: roughly
  29% of flagged classes are covered indirectly. Example: `persistence-*.jdbi` has
  0 dedicated test files but 76.8%/71.8% measured coverage. Always cross-check the
  static list against the JaCoCo CSV before recommending new tests.
- Exclude `*ReflectionConfig`, `*Metrics`, `*Constants`, DTO records, and
  interfaces from "must test" lists — they have no branching logic.
- Report **branch** coverage alongside line coverage. Branch is typically ~16.5pp
  lower here (76.10% line vs 59.62% branch on 2026-09-14), and the uncovered work is
  concentrated in error paths and multi-backend parity, not untouched classes.
- **The runner-loop "state vocabulary" is the cheapest win in `core.agent.loop`.** The
  `RunnerState` implementations (`ThinkingState`, `ToolExecutionState`,
  `AwaitingApprovalState`, …) hold their collaborators as plain private fields and are
  constructed from a `RunnerServices`, so `new RunnerServices(engine, null, null,
  correction, null, intentDetector, maxRetries)` plus mocks for the two or three
  collaborators the state actually touches is enough — no CDI container. Get the state
  from `services.thinkingState()` rather than `new ThinkingState(services)` so the
  production wiring is what is under test. `EngineStepHandler.processEngineStep` is
  **package-private**, which is fine: a test in `com.amsha.core.agent.loop` can still
  stub it. This took `ThinkingState` 46.4% → 100% (45 → 0 missed) with 22 tests.
- **To assert a value a state keeps private, read the field reflectively.** E.g.
  `ToolExecutionState` stores the built step in a private `step` field with no getter;
  `getDeclaredField("step")` + `setAccessible(true)` is how the deterministic
  write-fallback payload (`action`, `actionInput`) gets asserted. The codebase already
  does this (`ParallelToolStepExecutorPreExecTest.setField`), so it is house style —
  but only reach for it when there is genuinely no observable seam.
- **Check reachability before writing a test for a guard.** Some uncovered branches
  are *dead code*, not gaps — and no test can reach them. Found so far: two in
  `CheckpointResumeHandler` (a blank-goal guard that can never fire because the
  collaborator falls back to a non-blank sentinel, and a CLI fallback branch that
  recomputes a condition already known false), and the parallel-invariant guard in
  `StepDecisionRouter.handleFinishOutcome` — `currentStep.isParallelBatch()` is
  `this instanceof ParallelStep`, the parameter is statically typed
  `ReasoningStep.FinishStep`, and the only caller narrows the same way, so the condition
  is a compile-time constant. Confirm by tracing the call path; if it is dead, **report
  it as a finding** and suggest deleting it rather than contorting a test (e.g. via
  reflection into a private field) to fake reachability.
- **A green line is not evidence that a guard works.** In that same `StepDecisionRouter`
  case JaCoCo reports line 127 (`if (currentStep.isParallelBatch()) {`) as *covered* —
  the condition is evaluated on every FINISH turn, it just never branches true — while
  the body is missed. So "the line is green" says nothing about whether the branch can
  fire. When a class plateaus below 100%, list the residual lines and check whether the
  *branch* is reachable before assuming the tests are incomplete.
- **Not every "hard" class needs a CDI container.** `PendingToolResumeExecutor` (435 LOC)
  and `ExecutionFailureRecoveryService` (449 LOC) both *looked* container-bound and both
  went to 100% in a plain unit test — `WorkspaceContext.run(...)` is written null-safe
  against a missing `Arc.container()`. Only budget the expensive fixture when a method's
  *first statement* is `Arc.container().requestContext().activate()` or it calls
  `CompletableFuture.supplyAsync(..., executorFor(...))`. Grep for those two patterns
  before assuming.
- **Final classes *are* mockable here — the inline mock maker is the default.** Mockito 5.x bundles
  `InlineByteBuddyMockMaker` into `mockito-core` and makes it the default, so `mock(FinalClass.class)` just
  works; there is no `mockito-extensions/org.mockito.plugins.MockMaker` file and none is needed (verified
  2026-09-14 by probing `Plugins.getMockMaker()`). Prefer a real object when the class ships a package-private
  no-arg test constructor (e.g. `new ToolDelegationResolver()` with `capabilityRegistry == null`), because that
  exercises production behaviour — but reach for a mock without hesitation when the real object would need
  collaborators you do not care about. **Do not assert "final classes are unmockable" without probing
  `Plugins.getMockMaker()` first** — that mistake was made once and cost a needless workaround.
- **Classes needing a CDI container or a real `ManagedExecutor` are expensive.** If a
  method's first statement is `Arc.container().requestContext().activate()`, the body
  needs `mockStatic(Arc.class)` plus a mocked `ArcContainer`/`RequestContext`, and the
  dispatch path needs a synchronous executor (`Runnable::run`). Same for
  `CompletableFuture.supplyAsync(..., executorFor(category))`, which needs a real
  `ManagedExecutor.builder().build()` and a doubly-nested `Instance<ManagedExecutor>`
  mock. Budget for that separately instead of assuming a plain unit test will do.

## Raising Coverage for a Target Class (the Phase-N workflow)

When asked to *add* tests rather than just measure, follow this loop. It is the
sequence that produced the 2026-09-14 gains (`DynamicToolSchemaParser` 0→100%,
`CronFailureCleanup` 28→100%, `ToolStepEscalationHandler` 2.6→100%,
`ToolSchemaProvider` 40→100%, `ToolStepHandler` 71→100%,
`ParallelBatchResumeHandler` 67→100%, `ThinkingState` 46.4→100%,
`ExecutionFailureRecoveryService` 59.2→100%, `StepDecisionRouter` 57.4→98.3%,
`PendingToolResumeExecutor` 79.7→100%, `LoopResultHandler` 56→99.4%,
`CheckpointResumeHandler` 50→76.8%, `ParallelToolStepExecutor` 52.3→99.2%,
`DelegatedCompletionHandoff` 66.2→97.8%, `BrowserPreflightHandler` 73.6→98.8%).
Reactor moved 74.51% → 76.10% (+951 lines) across eight passes.

Worked example — `ToolStepHandler` + `ParallelBatchResumeHandler`, both taken to
**100% line coverage** in one pass (62 → 0 and 53 → 0 missed lines): read the two
classes, extract the missed line numbers with the XML snippet above, group them into
named behaviours (blocked-guard branch, repeat-termination, delegate fast path,
auto-heal matrix, approval short circuit / parse fallbacks, per-tool decisions,
delegation redirect, guard decisions, invocation outcomes), then write one test per
behaviour. 114 of the reactor's 127 gained lines came from these two.

Worked example — `ThinkingState` (46.4% → 100%, 45 → 0 missed, 22 tests). A single
`RunnerState` with three collaborators, so one `@BeforeEach` sets up mocks for
`EngineStepHandler`, `IntentClassificationService` and `CorrectionStrategy`, and the
state comes from `services.thinkingState()`. Group the missed lines by *escape
hatch* rather than by line: the write-intent directive injection, the no-progress
breaker (its `>= 2` write fallback, its `>= 2` failure, and its `< 2` retry-arm), the
rate-limit budget, the `StructuredOutputException` budget, the three
`handleEngineDecision` arms, and the private `extractWritePayload` branches (blank
goal, prefix stripped, prefix stripped to nothing, >12,000-char truncation). Note
that `doThrow(...).when(engine).processEngineStep(...)` is required for the
exception paths — `when(...)` would invoke the method and throw during stubbing.

Worked example — `ExecutionFailureRecoveryService` (59.2% → 100%, 49 → 0 missed) plus
`StepDecisionRouter` (57.4% → 98.3%, 49 → 2 missed), both by *extending an existing
test file* rather than creating one. Two things made the "hard" one easy:

- **`WorkspaceContext.run(...)` works in a plain unit test.** The auto-heal path wraps
  its `AgentLoop.run` call in `WorkspaceContext.run(...)` → `WorkspaceScopeHolder.call(...)`,
  which starts with `Arc.container()`. It is written null-safe
  (`container != null ? container.requestContext() : null`), so with no Quarkus container
  it simply skips the request-context activation and still binds the `ScopedValue`s.
  Do **not** reach for `mockStatic(Arc.class)` here — try it plain first.
- **`Instance<AgentLoop>` is mockable as a `@Mock` field** (`when(agentLoopInstance.get()).thenReturn(agentLoop)`),
  so the delegation hop needs no CDI. Note `MockitoExtension` defaults to strict stubs:
  stub `get()` inside each test that reaches it, not in `@BeforeEach`.
- Group the missed lines by *lifecycle stage*: overload chain (8-arg → 9-arg → 10-arg),
  the retry/accumulate loop, one `singleHealAttempt`, then the four exits
  (no fenced code, validation rejection, non-`Finished` result, delegation throws).
- `RecoveryResult.extractedCode` is an **internal** hand-off between attempts — the public
  entry point always returns the accumulated-error failure with `extractedCode == null`.
  Asserting it is non-null on the outer result is a wrong test, not a bug. To verify the
  carry-forward, capture the *goal* passed to `agentLoop.run` on the second attempt.
- For the router, one test per `FinishOutcome` variant. Make the `Completed` outcome's
  `answer` differ from its embedded `result`'s answer, or you cannot tell the SYNTHESIZER
  arm from the plain `instanceof Finished` arm.
- The `AWAITING_APPROVAL` WAL retry can be driven **deterministically**: interrupt the
  thread from inside a `thenAnswer` on the *first* `loadCheckpointStatus`, so the
  `Thread.sleep(150)` aborts on entry and takes its `InterruptedException` branch. Clear
  the flag in a `finally` (`Thread.interrupted()`) or you will leak it into later tests.

Worked example — `PendingToolResumeExecutor` (79.7% → 100%, 47 → 0 missed, 8 → 23 tests),
again by extending the existing file. It is a 435-line `final class` with a
`record PendingToolRequest` holding the request state, so group the missed lines by
*lifecycle stage*: the checkpoint-history prepend, the delegation redirect, the
retry loop, the four guard arms, the delegated-approval pause, the web-retrieval
fallback, and the abort paths. Four things cost real time:

- **Use the real `ToolDelegationResolver`, not a mock.** It is `public final` — mockable (the inline mock
  maker is the default, see the traps above), but the real instance is a better test of the routing rule: the
  **package-private no-arg constructor** leaves `capabilityRegistry == null`, so ownership falls back to the
  static `EXPLICIT_GRANTS` / `EXPLICIT_OWNERS` maps. Picking the right pair matters: `resolveOwner` returns
  `Optional.empty()` the moment the current role *is* authorized, so `nurturer` + `sandbox_exec` redirects to
  `coder`, while `coordinator` + `web_fetch` (and anything + `garuda`, which short-circuits) does not.
- **`ReasoningStep.observeStep(action, observation, thought)` — the third argument is
  the `thought`, not the observation.** The fallback messages you want to assert on
  (`"Browser and web_fetch failed, falling back to one search."`) land in
  `step.thought()`. Asserting on `observation()` silently checks the wrong string.
- **An exception thrown from inside a `catch` block is not handled by that same
  `try`.** `executeWithRetries` calls `failIfHalted` at the top of the loop (outside the
  `try`) and `waitBeforeRetry` *inside* the `catch`, so both aborts escape `execute` as
  unchecked exceptions — through `ResumeStrategyDispatcher.handle`, which does not
  catch. Pin them with `assertThrows(RuntimeException.class, ...)`, not with an
  assertion on a `Failed` result. (Reported as a finding; the halt case reads as
  deliberate, the backoff-interrupt case is questionable.)
- **A tiny retry-after keeps the backoff test fast.** `parseRetryAfterMillis` matches
  `(?i)retry-after:\s*(\d+)` (seconds) and, failing that, `Please try again in
  ([\dhms.]+)`. `"Please try again in 0.001s"` yields **1 ms**, so
  `Thread.sleep(retryAfter + 100)` costs ~101 ms instead of ~1.1 s. Interrupting from
  inside the `thenAnswer` that throws makes the sleep abort on entry — deterministic,
  no race — and `Thread.interrupted()` in a `finally` cleans up.
- Re-stubbing the `@BeforeEach` `lenient()` guard stub inside a test is the clean way to
  drive `Blocked` / `PauseForApproval` / `Deduplicate`; the last stubbing wins.

Worked example — `ParallelToolStepExecutor` (52.3% → 99.2%, 102 → 2 missed, 8 → 21 tests),
by extending the existing file. This is the class that was **deferred twice** on the
assumption that its `Instance<ManagedExecutor>` dependency made it untestable. It does not:

- **A mocked `ManagedExecutor` is enough — you never need a real thread pool.** The class
  only ever uses its executor as a plain `Executor`:
  `CompletableFuture.supplyAsync(() -> sequentialToolExecutor.execute(context, call, otel), executor)`.
  So stub `execute(Runnable)` with `doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })`
  and the whole batch runs inline on the calling thread, which also keeps the batch MDC
  keys observable from inside the stubbed tool. Wire the lookup with
  `pool.select(any()) → Instance.stream() → Stream.of(inline)`.
- **A "stalled" executor makes the timeout and interrupt arms deterministic.** Mock the
  executor with no `doAnswer` at all, so the task is accepted and never run and the future
  never completes. Then set `toolTimeoutSeconds = 0` — `future.get(0, SECONDS)` throws
  `TimeoutException` immediately, no sleeping. For the `InterruptedException` arm, have a
  helper thread poll `Thread.getState()` until the *calling* thread is `WAITING`/
  `TIMED_WAITING` and only then interrupt. That removes the race entirely: an interrupt
  delivered any earlier is caught by the loop's `isInterrupted()` halt check at the top of
  each iteration, which takes the cancel-and-break branch instead. A fixed
  `Thread.sleep(150)` also works but is strictly worse.
- **`ExecutionException` needs no trick at all** — with an inline executor, a stubbed
  `sequential.execute` that throws surfaces through `future.get` as `ExecutionException`.
- **`Instance<AgentObserver>.forEach` on a bare mock is a silent no-op**, so
  `onBatchStart` / `onBatchComplete` / `onStepComplete` assertions against a plain
  `Mockito.mock(Instance.class)` are vacuous. Stub it explicitly to fan out to a real list:
  `doAnswer(inv -> { Consumer<? super AgentObserver> c = inv.getArgument(0); observerList.forEach(c); return null; }).when(instance).forEach(any());`
- **Use a real `SimpleMeterRegistry`, not a mock.** `MeterRegistry.counter(String, String...)`
  returns `null` from a mock (varargs stubbing is awkward), so the timeout arm NPEs on
  `.increment()`. `new SimpleMeterRegistry()` is on the test classpath and lets you assert
  the metric directly: `registry.counter("amsha.agent.tool.timeout", "tool", "web_search").count() == 1.0`.
- **Check the argument count of `ApprovalCheckpointManager.persistBatchWithPreExecuted` — it takes SIX, not five.**
  `(context, allCalls, results, history, channelType, channelPlatformId)`. `persistBatch` takes five. Getting this
  wrong produces a confusing `cannot be applied to given types … actual and formal argument lists differ in length`
  pointing at the `any()` varargs. Extract a `stubPersistWithPreExecuted` helper so the arity lives in one place.
- Two lines (434, 439) are left uncovered on purpose: they are dead. Reaching line 429 means
  `split.hitlPending().isEmpty()`, so every call was pre-executed and `preResults.get(resultKey(call))`
  can never be `null`. See §1.3 of `docs/TEST_COVERAGE_ANALYSIS.md`; do not contort a test to reach them.
- There is already a `ParallelToolStepExecutorPreExecTest` covering the pre-execution path, so
  extend `ParallelToolStepExecutorTest` with the *other* behaviours — batch lifecycle, the three
  `future.get` failure arms, and both HITL split outcomes.

Worked example — `DelegatedCompletionHandoff` (66.2% → 97.8%, 45 → 3 missed, 3 → 20 tests), extending the
existing file. A `final class` with two collaborators (`StatefulAgentStore` + `ObjectMapper`), package-private
constructor, so `new DelegatedCompletionHandoff(mock(StatefulAgentStore.class), new ObjectMapper())` is the whole
fixture. Group the missed lines by *refusal* first: a well-behaved bridge is mostly a list of inputs it declines.

- **`capture` has four refusal paths** and they are the cheapest lines in the class: non-terminal child result or
  null child session (line 40), no active parent checkpoint (line 50), no matching batch call (61–62), and a
  child result that cannot be persisted (77–81, driven with `doThrow(...).when(store).save(any(AgentCheckpoint.class))`).
  Then the *matching* rules: the agent-name lookup across `agent`/`persona`/`target`, the single-call fallback
  when the params name no agent, and the replayed-delegation guard.
- **The private parse helpers are worth calling reflectively rather than driving through `capture`.** `readCalls`,
  `readResults` and `parentResume` are pure functions of one string (or one checkpoint), and reaching their
  malformed-input branches through the public path costs a full checkpoint + store setup each. Reflection is
  already the house pattern in this package (`redirectUnauthorizedCall`, `rewriteDelegatedCalls`).
- **A bare `mock(StatefulAgentStore.class)` is safe for the "nothing found" paths** — Mockito's
  `ReturnsEmptyValues` returns `Optional.empty()` for an `Optional` return type and an empty `List` for a `List`
  return type, so `findLatestAwaitingApproval` / `findLatestCheckpoint` / `findByStatus` all behave as
  "not found" without any stubbing. Do not add `when(...).thenReturn(Optional.empty())` "for safety"; it is
  noise. (This is the *opposite* of the `Instance<T>.iterator()` trap above, where the return type is
  `Iterator` and therefore unhandled.)
- **`isParallelBatch()` on the checkpoint record is derived from `pendingToolName`**, not a stored flag:
  `pendingToolName != null && pendingToolName.startsWith("parallel|")`. So a parallel-parent fixture sets
  `"parallel|garuda"` and a sequential one sets the bare tool name — there is no setter to call.
- **Watch for guards that duplicate a decision their caller already made.** Three lines in this class are
  unreachable for exactly that reason (see below), and the honest move is to report them, not to reach them
  reflectively. A reflective test that *can* invoke a dead guard is not evidence that the guard is needed.

Worked example — `BrowserPreflightHandler` (73.6% → 98.8%, 43 → 2 missed, 5 → 23 tests), extending the existing
file. Four constructor-injected collaborators (`ToolRegistry`, `ToolInvoker`, `ApprovalCheckpointManager`,
`ToolInvocationGuard`) and a package-private `@ConfigProperty String defaultTimezone` field that a same-package
test assigns directly (`handler.defaultTimezone = "Not/AZone"`) — no CDI, no reflection. The public surface is a
single `Optional<AgentResult> run(context, history)`, so group the missed lines by the *stage* of the preflight
ladder rather than by method:

- **The empty-history gate (98)** is the cheapest test in the file: pass a non-empty history and assert both that
  the result is empty and that the guard and tool were never consulted. Preflight is a first-turn-only affordance.
- **All four `GuardDecision` arms must be exercised here, not just `Allow`.** The `Blocked` arm (130–131) returns
  `AgentResult.failed(observation, SYSTEM_ERROR, …)`; the `Deduplicate` arm (134–136) records the replayed step in
  history as a *successful* step and finishes with the cached observation. `PauseForApproval` was already covered.
- **Two catch blocks, two different contracts.** `RequiresApprovalException` (154–160) pauses on
  `e.getFrame()` — the frame the *tool* raised — and must be asserted with an `ArgumentCaptor` on the persisted
  history, because the live list must stay untouched (the pause snapshot is a copy carrying the not-yet-executed
  step). Any *other* exception (161–164) is deliberately swallowed into `Optional.empty()`, degrading to the
  normal reasoning path.
- **The `SUGGESTED_CRON` / `SUGGESTED_NEXT_FIRE_TIME` bindings (214, 217) can be asserted, not just covered.**
  Stub `toolInvoker.invoke` with a `thenAnswer` that reads `WorkspaceContext.suggestedCron()` /
  `suggestedNextFireTime()` and capture them into an `AtomicReference`: the answer runs *inside* the
  `ScopedValue.where(...).call(...)`, so this proves the values are visible to the tool at the moment it runs
  rather than merely passed around.
- **The browser arm needs the role gate to be passed explicitly.** `buildStep` returns early unless the role is
  `researcher`, so a coordinator test covers nothing beyond line 232; drive the researcher path with the tool
  unregistered, then registered-but-disabled, then enabled with a URL-less goal, then enabled with a URL.
- **The gist resolver's tail (292–294, 303–304) needs two different fixtures.** `extractGistTarget` returns early
  on the first *possessive* match (`"Show me rsrini7's gists"` → target `rsrini7`), so the stop-word guard and the
  loop-exhaustion `return null` are only reached by a *prefix* form whose candidate is a stop word
  (`"Get gists for user all"` — `all` matches the username grammar but is filtered). The second test must assert
  the *absence* of the `target` key, not just that a call was made.
- **A strict-stubs trap specific to a multi-name lookup** (see the traps list): stubbing `findTool` for one tool
  name makes every *other* name the handler looks up a `PotentialStubbingProblem`. Register a lenient
  `findTool(anyString()) → Optional.empty()` default in `@BeforeEach` — that is also production's fail-closed
  semantics for an unknown name — and let the per-test `registerTool(name, enabled)` stubs (declared later, so
  they win) override it.

### Guards that re-check what the caller guaranteed

Six separate instances of the same anti-pattern turned up in `DelegatedCompletionHandoff` (lines 68, 229, 251),
`BrowserPreflightHandler` (lines 240, 274) and `DelegateToAgentTool` (lines 415–418), all unreachable, all worth
deleting:

```java
// findChildCall() only returns calls ABSENT from the results map, so this re-read can never contain the key
ParallelToolCall childCall = findChildCall(calls, childSessionId, parent.getPreExecutedResults());
Map<String, JsonNode> results = readResults(parent.getPreExecutedResults());   // same source -> same map
if (results.containsKey(callKey(childCall)) || results.containsKey(childCall.toolName())) {
    return Optional.empty();   // line 68 — always false
}
```

```java
// capture() already returned unless indexOf(SENTINEL) > 0, so childAgent() cannot see marker < 0
private static String childAgent(String childSessionId) {
    int marker = childSessionId.indexOf(AgentSentinels.DELEGATE_SESSION_SENTINEL);
    if (marker < 0) return null;   // line 229 — impossible
```

```java
// both call sites are downstream of isTerminal(result), which enumerates exactly these three variants
return switch (result) {
    case AgentResult.Finished finished -> ...;
    case AgentResult.StructuredFinished finished -> ...;
    case AgentResult.Failed failed -> ...;
    default -> throw new IllegalArgumentException("Non-terminal delegated result");   // line 251 — impossible
};
```

```java
// AgentExecutionContext's constructor is `Objects.requireNonNull(goal, "goal must not be null")`
String goal = context.goal();
if (goal == null) {              // line 239 — evaluated on every call, always false
    return Optional.empty();     // line 240 — never taken
}
```

```java
// the gate above requires GET_GIST_INTENT_PATTERN, whose pattern ends in the literal \bgists?\b
if (!GET_GIST_INTENT_PATTERN.matcher(normalized).find()) {
    return Optional.empty();                                    // line 266 — reachable
}
String lower = normalized.toLowerCase(Locale.ROOT);
boolean hasGithubIntent =
        lower.contains("github") || lower.contains("gh") || lower.contains("gist") || target != null;
if (!hasGithubIntent) {                                         // line 273 — always false
    return Optional.empty();                                    // line 274 — never taken
}
```

```java
// execute() already returned early when depth >= maxDelegationDepth, so executeNormalDelegation()
// is entered with depth <= maxDelegationDepth - 1. The only other call site is the self-recursion,
// and that hardcodes the shiva/DEBUGGER role -- so the enclosing CODER block is never re-entered
// at a raised depth. The nested check re-asks a strictly weaker question than the caller's gate.
if (AgentRoleEnum.CODER.equals(targetRole)) {
    ...
    if (depth + 1 > maxDelegationDepth) {                    // line 414 — evaluated, always false
        log.warnf("[VerificationGate] Max delegation depth reached (%d). ...", maxDelegationDepth);
        return handleSubAgentResult(result, targetRole, outerSessionId, "shiva");   // line 418 — never taken
    }
```

The tell is a *condition whose operands are computed from the same source as a condition already evaluated
upstream*, a `default` arm over a closed set the caller has already narrowed, a guard on a **type invariant the
constructor already enforces**, or a disjunct **implied by the regex that gated entry**. Grep for the guard's
operands before assuming a test is missing — and note that in every one of these six cases the `if` line itself
is reported **covered** while its body is not, so a green line is not evidence that the guard works.

For the `DelegateToAgentTool` case the arithmetic was settled by **enumeration rather than assertion**: a
throwaway `java Probe.java` replicated the two gates and swept `maxDelegationDepth` −5…20 × `depth` 0…30,
finding **210** combinations that reach the inner guard and **0** where `depth + 1 > maxDelegationDepth` holds.
That probe needs no classpath at all (pure integer arithmetic) — reach for it before writing a test that
"must" be able to trigger the guard.

### Dead-code findings recorded during Phase 1 (2026-09-15)

These surfaced while closing out Tasks #8–#10 (`DelegateToAgentTool`, `LeakedToolCallParser`) and are documented
here so a future coverage run does not re-investigate them. All are reported as findings, **never faked**.

> **Correction (same day, re-measured).** Two of the three roadmap counts for this trio were already stale
> (`ToolGovernanceGuard` 33 → **0**, `LeakedToolCallParser` 32 → **0**), and the 164–165 note below was **wrong**:
> that guard is reachable, and is now covered. Re-measure before trusting any entry in this section.

- **`DelegateToAgentTool` lines 164–165** (shutdown guard) — **REACHABLE, now covered.** An earlier revision of
  this file recorded it as "unit-test-unreachable; leave it". That was wrong.
  `AgentGuards.isShuttingDown()` is `Arc.container() != null && !container.isRunning()`: a plain unit test has no
  container (so `container()` is `null` → `false`), but the guard needs the container to **exist and report not
  running** — which `mockStatic(Arc.class)` supplies exactly. Recipe below.
- **`DelegateToAgentTool` lines 415–418** (CODER verification-gate max-depth skip): **dead.** `execute()` returns
  early on `depth >= maxDelegationDepth`, so `executeNormalDelegation` is entered with
  `depth <= maxDelegationDepth - 1`, making `depth + 1 > maxDelegationDepth` unsatisfiable; the only other call
  site is the self-recursion, which hardcodes `DEBUGGER`, so the enclosing CODER block is never re-entered at a
  raised depth. Probed: **210** reachable `(depth, maxDelegationDepth)` combinations, **0** counterexamples.
- **`DelegateToAgentTool` lines 427–429** (`catch (IllegalArgumentException)` around
  `AgentRoleEnum.fromString("debugger")`): `"debugger"` is a constant role — `DEBUGGER` is in `BUILT_IN` and its
  definition's `canonicalName` is `AgentPersona.SHIVA.roleId()`, literally `"debugger"` — so the lookup never
  throws → the `catch` is **dead**. Independently confirmed by the pre-existing
  `execute_verificationGateFailure_routesToShiva`, which covers line 426 and continues to line 432.
- **`LeakedToolCallParser`**: **fully covered** as of this re-measurement (256/256 lines, plus 7 anonymous
  `TypeReference` classes). The earlier note about lines 362 & 366 being JaCoCo closing-brace artifacts no longer
  applies — neither line is in the missed set.

**Recipe — reaching the shutdown guard (the one that looked unreachable):**

```java
ArcContainer container = mock(ArcContainer.class);
when(container.isRunning()).thenReturn(false);
try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
    arc.when(Arc::container).thenReturn(container);
    ToolResponse res = tool.execute(coordinatorCtx(), Map.of("agent", "shiva", "goal", "debug"));
    assertEquals(503, res.exitCode());
    verifyNoInteractions(agentLoop);
}
```

The generalisable lesson: *"unreachable in a plain unit test"* and *"unreachable"* are **different claims**. Before
recording a guard as dead because the harness lacks a container, check whether the guard's own predicate is
static-mockable — `Arc.container()`, `CDI.current()` and `AgentGuards.isShuttingDown()` all are.

(Earlier set, from §1.3 of `docs/TEST_COVERAGE_ANALYSIS.md`: `CheckpointResumeHandler` 178–181 / 455–459 /
409–417, `StepDecisionRouter` 127–131, `ParallelToolStepExecutor` 434 / 439, `DelegatedCompletionHandoff` 68 /
229 / 251, `BrowserPreflightHandler` 240 / 274, plus the `ParallelBatchResumeHandler` line-97 NPE risk — a *real*
defect, not dead code, worth hardening.)

### Phase 2 notes (security / tools-builtin / sandbox modules)

Phase 2 targets live **outside `core`** (`security`, `tools-builtin`, `sandbox`). The `amsha-*` upstream artifacts
are installed in `~/.m2`, so `./mvnw -o -pl security test jacoco:report` works **without `-am`** and is the fast
loop. `-pl security clean verify` runs that module's tests + PMD + `spotless:check` (the `0.74` JaCoCo gate is
bound in `app`, so it does not run per-module).

- **A self-signed cert is the fixture for custom-CA/TLS trust success paths.** `CertificateFactory.generateCertificate`
  needs a parseable X.509 DER blob; generate a compact one once (`openssl ecparam -name prime256v1 -genkey` +
  `openssl req -x509 … | openssl x509 -outform DER | base64`) — an EC P-256 cert is only ~294 bytes / ~390 base64
  chars — embed it as a `String` constant and `Base64.getDecoder().decode(...)` it in the test. A plain-HTTP target
  exercises the SSLContext build without a real TLS handshake.
- **`NetworkProfilePolicy.fromJson` fails closed to `tlsTrustMode=SYSTEM`** whenever `CUSTOM_CA` lacks a
  `customCaVaultRef` (lines 137–141). Consequence: in `GovernedHttpClient.buildClient`, the
  `caRef == null || caRef.isBlank()` guard (287–288) is **dead** — `tlsTrustMode() == CUSTOM_CA` always implies a
  non-blank ref. Report, don't fake.
- **`URI.getPath()` decodes percent-escapes.** `URI.create("telegram://file/%01")` yields a decoded path containing
  `\u0001`, which is how to drive an `Character::isISOControl` guard when the raw URI parser would reject a literal
  control char.
- **`Future.get()` only throws `InterruptedException` immediately when the task is not already complete.** To make
  an interrupted-read test deterministic, pair a pre-set `Thread.currentThread().interrupt()` with a stream that
  blocks on a `CountDownLatch` (so the future is still pending when `get()` checks the flag); release the latch in
  `finally` and clear the flag with `Thread.interrupted()`.
- **A class that builds its own `HttpClient` (e.g. `GovernedMediaDownloadService.downloadHttpWithRedirects`) has no
  injection seam** — its HTTP paths need a real local-server fixture, not a mock. Check for an injectable transport
  hook (like `GovernedTelegramMediaResolver.customMetadataTransport`) before budgeting the effort.

#### CRITICAL: take the "before" reading after a `clean` build

`target/classes` can be left stale by an earlier revision. JaCoCo then maps the *current* source line numbers onto
*old* bytecode and the per-line figures are fiction. Real example: `tools-builtin.GithubTool` reported **46** missed
pre-`clean` and **73** missed once `clean` was used — the 46 was wrong, and it made a genuine 26-line improvement
look like a 1-line regression.

Always `clean` (or at least confirm the class files are newer than the sources) before recording a before/after
pair. If a delta looks implausible, re-measure before writing it down.

To recover a trustworthy baseline **without touching git**: back the modified test files up to `/tmp`, restore the
committed versions with `git show HEAD:<path> > <path>` (a read-only git command plus an ordinary file write —
never `git stash` / `checkout` / `add`), run the clean measurement, then copy the backups back and re-verify.

#### `tools-builtin` notes (2026-09-15)

- **`0.0.0.0` is the destination trick for `LocalSocksProxy`.** Its SSRF guard rejects loopback / link-local /
  site-local / multicast, so a unit test cannot reach a server it bound itself. But `InetAddress` classifies the
  wildcard address as *none* of those four, while `connect()` still routes `0.0.0.0:<port>` to the local host
  (verified on macOS; specified behaviour on Linux). That makes the whole CONNECT tunnel — both pipe threads and
  `closeQuietly` included — testable with no external network.
- **`DirectExecutor.executeDirectly` declares `throws Exception`**, so `when(...)` on it forces a `throws` clause
  onto every test method. Hoist it into `setUp()` as
  `lenient().when(directExecutor.executeDirectly(any(), any(), any())).thenReturn("ran")` — keeps methods clean and
  sidesteps `UnnecessaryStubbingException` for tests whose path throws before reaching the executor.
- **`HostExecTool.autoOpenGeneratedHtml` spawns the platform browser opener** (`open` / `xdg-open`) once it finds a
  *new or modified* `.html` artifact — a real side effect. To cover the snapshot/compare logic safely, pre-create
  the `.html` under `<skillHome>/output` so `isNewOrModified` returns false and the method returns `null` early.
- **`gh` is not installed here** (`command -v gh` fails), so `GithubTool.execute` always dies at
  `ProcessBuilder.start()`; its child-process lifecycle is integration-only. Check `command -v gh` before assuming
  those lines are reachable.
- **`playwright-cli` IS installed** (Chromium with it), so `execute()` past validation spawns real node processes.
  Avoid `action=navigate|screenshot|extract_text` **with** a `url`: those call `execWithSession(session, "open",
  url)` and launch Chromium. `action=click|fill|wait|eval` need no URL and fail fast with "browser … is not open"
  (~1s, no browser). Each `execWithSession` failure costs **3** spawns (attempt → `close-session` → retry), and
  `doWait` costs 6 (unsupported `wait` plus its JS fallback) — budget test time and use one invocation per helper.
- **`PlaywrightCliTool.mcpHelper` is not null-checked** (line 214, outside the `try`). Any `execute()` that gets past
  parameter validation must have `tool.mcpHelper` set to a mock first, or it NPEs out of the method.
- **JaCoCo mis-attributes `execWithSession(...)` call sites** in `PlaywrightCliTool` (278, 292, 304, 317, 325, 336):
  reported `mi>0, ci=0` even though the surrounding lines in the same method are covered and the tests pin the exact
  `playwright-cli` failure thrown from those calls. Same artifact class as `LocalSocksProxy` 189. Confirm with a
  behavioural assertion before treating such a line as a real gap.
- **Pin the failure *cause* when asserting on a reflective call.** A bare
  `assertThrows(Exception.class, () -> privateMethod.invoke(...))` also passes on an argument-type mismatch, so the
  test can go green without ever reaching the code under test. Assert `InvocationTargetException` **and** that the
  cause's message identifies the expected collaborator.

#### `persistence-*` notes (2026-09-15, Phase 3)

- **Docker is not available here, but `persistence-postgres` still runs.** `PostgresTestResource` tries Testcontainers
  and falls back to the **zonky `embedded-postgres`** server (`EmbeddedPostgres.builder().start()`), so all 120
  module tests execute on a plain workstation. Do not assume Postgres tests are skipped — check the surefire count.
- **`PersistencePortContractTest` (persistence-api, 2.3k lines) is NOT a contract test for the real backends.** It
  validates the port *interfaces* against hand-written `InMemory*Store` mocks. Coverage of the actual adapters comes
  only from their own module's tests, so it is no evidence that a backend behaves correctly.
- **There is no test-jar infrastructure in this reactor** — zero `test-jar` declarations in any `pom.xml` and zero
  `abstract class *Contract|*Base` in test sources. A genuinely shared suite therefore needs a new artifact, which
  would break the module-scoped loop (`-pl <module> test` would stop resolving without an `install` first), and a new
  *module* would trip the `--strict-instrumentation` empty-report guard. Mirror the spec across per-backend classes
  by convention instead, and say so in each class's javadoc.
- **The SQL backends' tables are shared across a whole module test run** (SQLite writes a file DB under
  `./target`; Postgres starts one embedded server for the *whole module run*, not per class — `@QuarkusTestResource`
  declared on a test class is global unless `restrictToAnnotatedClass = true`). Assert global queries (`findAll`,
  `countAll`) as **deltas** (`countAll(null)` before vs after seeding) and keep absolute assertions to
  user/session-scoped queries. Generate a fresh `user`/`session` per test from a `UUID` and clean up in `@AfterEach`
  with `deleteAllByUserId` + `deleteAllBySessionId`.
- **`agent_cron` is `NOT NULL` on `nextFireTime`, `userId`, `chatId`, `goal`, `sessionId`, `cronExpression`,
  `timezone`, `status`, `createdAt`** in *both* migrations (`db/migration/{sqlite,postgresql}/V2__…sql`). The FS
  adapter imposes none of that. Always build test DTOs fully populated, or the save dies with
  `SQLITE_CONSTRAINT_NOTNULL` / a Postgres not-null violation.
- **The FS store is a real, cheap fixture.** Build `new FilesystemFileStore(props)` where `props` is an anonymous
  `FsStoreProperties` returning a `Files.createTempDirectory(...)` path, then `fileStore.ensureDataDir()` and inject
  it with `setAccessible(true)` on the adapter's `store` field. Same recipe as `FsStoreSmokeTest`, and it exercises
  the JSON round-trip rather than a mock.
- **A mirrored contract suite finds real bugs, so budget for it.** Writing the SQLite suite turned up three
  behaviours that differ between backends (null-column handling, `createdAt` on insert, `INSERT OR REPLACE` vs
  `ON CONFLICT DO UPDATE`). When an assertion fails, first ask whether the *expectation* was wrong — one
  `expected: <…T11:23:09.322Z> but was: <…T11:33:09.322918Z>` failure was the Postgres adapter stamping
  `Instant::now()` on insert rather than a test-pollution artifact (confirmed by re-running the single method with
  `-Dtest='Class#method'`). Pin the observed behaviour with a comment naming the divergence; do not "fix" a backend
  as a side effect of a coverage task.
- **A 267-line mocked adapter test is not evidence the adapter is covered.** `PostgresCheckpointStoreAdapterTest`
  mocks `Jdbi`, so it only proves *delegation* — it cannot tell whether the SQL is valid, whether the column aliases
  line up, or what a query returns. JaCoCo showed 56 lines still missed on that adapter (the entire
  `saveCheckpointWithSteps`, the batch-decision reject arms, every sweep, `purgeOldChatHistory`). Always read the
  per-class numbers before assuming an existing test class closes a target, and write the new suite as
  **complementary** (real DB) rather than a duplicate, saying so in its javadoc.
- **Age-sensitive queries cannot be driven through the port.** `CheckpointDtoHelper.withStatus(...)` stamps
  `updatedAt = Instant.now()`, so a checkpoint can never be *saved* in the past; `findInProgressZombies`,
  `findStaleApprovals`, `isCheckpointExpired` and the purge all need back-dated fixtures written with raw SQL
  (`UPDATE agent_checkpoint SET updated_at = :ts`). **The bind type differs per backend** — `sqlite` binds
  `TemporalUtils.formatIsoLex(instant)` (a `String`), `postgres` binds the `Instant` itself. Same divergence exists
  across the two `CheckpointJdbiDao`s generally.
- **The FS backend's equivalent of that fixture is `FilesystemFileStore.write` — and `List.of` will reject your
  fixture.** `write` builds the path with `List.of(segments)` internally, so a fixture that deliberately exercises a
  **null session segment** must construct the *outer* list with `Arrays.asList(...)`, or it dies with an NPE inside
  the helper before it ever reaches the adapter. Cost: one confusing `NullPointerException` at the fixture line.
- **`purgeOldChatHistory` is global, unguarded, and deletes rows *and* their steps.** Date the fixture far in the
  past (2001) and sweep with an ancient cutoff (2005) so the DELETE cannot reach another suite's rows, then assert
  the old row and its `reasoning_step` children are gone while a fresh row survives.
- **When a helper sorts the actual result, write the expected literal in that same order.** One contract test
  failed on `[root/r1, root:delegate:other/r1, root:delegate:sub/r1]` vs an expected `…sub…, …other…` — the SQL's
  `ORDER BY checkpoint_id ASC` was irrelevant because the helper called `.sorted()` on `session + "/" + role` and
  `"other" < "sub"`. Same three elements, pure ordering, entirely a test bug.
- **Log-before-guard NPEs are a real defect class — report, don't pin.** `PostgresCheckpointStoreAdapter.saveSteps`
  logs `steps.size()` on line 356 *before* its own `steps != null` guard on line 360, so the null-tolerant guard is
  unreachable and a null list throws inside the log call. The guard proves null was meant to be supported, so the
  log line is the bug: record it as a finding and simply don't call `saveSteps(id, null)` from the suite.
- **Migration parity is now enforced** by `app/src/test/java/com/amsha/app/db/FlywayMigrationParityTest.java` (7
  tests). It compares the 12 `V<n>__<desc>.sql` pairs for: identical file/version sets; identical static table sets;
  identical column-name + `NOT NULL` maps per table; identical static index names; identical `ALTER TABLE ... ADD
  COLUMN` targets. **Column types are deliberately not compared** (`TEXT` vs `TIMESTAMP WITH TIME ZONE`, `INTEGER`
  vs `BIGINT` are the expected dialect mappings). As of 2026-09-15 the sets are in **exact parity** — 42 static
  `CREATE TABLE` statements in each dialect with zero column-level drift.
  **If you add a migration:** add it to *both* directories with the same version and description. If it needs a
  genuinely dialect-specific construct, add the name to the relevant allow-list constant in that test — the lists
  are asserted for **exact equality**, so a new dialect-only index/column fails the build until it is named, and
  naming something that is no longer used also fails.
- **Three dialect-specific constructs are excluded from that comparison**, each pinned by name: `DO $$ ... $$;`
  blocks (PostgreSQL's pgvector DDL, guarded on `pg_available_extensions`); `CREATE VIRTUAL TABLE ... USING fts5`
  (SQLite's full-text search, where PostgreSQL uses `content_tsv tsvector` + `GIN` indexes); and the
  PostgreSQL-only `content_tsv` columns those indexes back.
- **Read migrations from the working tree, not the classpath.** `app` depends on both backends so the migration
  resources *are* on its test classpath — but under `-pl app` they resolve from the installed `~/.m2` jars, which can
  lag the sources being reviewed. Resolve the sibling module directories instead (walk up from the working directory;
  surefire's cwd is the module basedir, the same assumption `ApiKeyAuthFilterTest` makes).
- **Do NOT copy `ApiKeyAuthFilterTest`'s `if (Files.exists(sourceFile)) { ... }` idiom.** It silently skips when the
  file is missing, so the test passes without asserting anything — a vacuous green. Fail loudly instead.
- **Prove a new guard/parity test is non-vacuous by mutation.** A parity test that compares two empty sets passes
  silently. Perturb the input (append a stray index to one dialect's migration), confirm the test fails with a
  readable message, then restore and confirm `git status --short <file>` is empty. Both directions are worth
  checking: drift *towards* the other dialect, and a *new* dialect-only construct. Build anti-vacuity guards in:
  assert the parser's match count equals the raw keyword count, and assert the parsed set is non-empty.
- **An `@Nullable`-injected collaborator hides its own call sites.** `FsLlmProfileStoreAdapter` (and both SQL
  siblings) hold `@Inject @Nullable ModelCacheInvalidationPort cacheInvalidator` and guard every call with
  `if (cacheInvalidator != null && id != null)`. Every existing test left the field `null`, so the guard always took
  the false branch and the two call sites sat uncovered while looking "obviously fine". The fix is a package-private
  recording stub assigned straight to the field (no CDI needed) — and the *negative* assertions are the valuable
  half: a rejected optimistic-lock update, or a delete of an absent row, must **not** fire, because nothing was
  persisted. Generalise: when a class has a nullable optional collaborator, check whether any test ever binds it.
- **`idx_session_catalog_active` is a PARTIAL UNIQUE INDEX, and only `activateNew()` maintains it.** It is
  `ON session_catalog(owner_key, channel_type, channel_id) WHERE active = 1` (SQLite) / `WHERE active = TRUE`
  (Postgres), i.e. **at most one active row per scope**. `activateNew()` first issues `UPDATE ... SET
  active=FALSE,status='INACTIVE' WHERE <scope> AND active=TRUE`, then inserts. `record()` does neither — the SQL
  adapters call `insert(..., e.active(), e.status(), false)` (trailing `false` = "leave stored `active`/`status`
  alone on conflict"). So seeding **two active rows in one scope** fails the Postgres suite with
  `PSQLException: duplicate key value violates unique constraint "idx_session_catalog_active"`. Give your `entry(...)`
  fixture helper an explicit `boolean active` parameter and make the second row in each scope inactive.
- **The FS backend enforces no uniqueness at all, so it is the permissive outlier on invariants.** The same
  `record(active = true)` that throws on both SQL backends is silently accepted by `FsSessionCatalogAdapter` (which
  just writes the entry), leaving two active rows and an ambiguous `resolveActive`. Expect this shape generally:
  where a migration adds a constraint, `fs` accepts what `sqlite`/`postgres` reject. Pin the difference; don't
  "fix" it. (Here it is latent: `record()` has **no production caller** — `SessionCatalogService` uses
  `activateNew`/`setActive`, and `SessionReaper` / `SessionRegistry` / `DbConsoleResource` only read. Check callers
  before rating a divergence's severity.)
- **Exception-*message* divergences are real divergences.** `replaceHeaderSecretRefs` has an explicit
  `if (isBlank(connectionId) || isBlank(userId)) throw new IllegalArgumentException("connectionId/userId required")`
  guard in both SQL adapters; `FsMcpConnectionStoreAdapter` has no blank guard and falls through to
  `"MCP connection not found"`. Same type, different text — same shape as the cron `failReason` and checkpoint
  reason mismatches. Assert the message, not just the type, when the port's contract is caller-visible text.
- **A non-injected stock `ObjectMapper` makes its `catch` block dead code.** Both SQL LLM-profile adapters end with
  2 permanently-missed lines: the `catch (Exception _) { return "[]"; }` in `toJson(List<String>)`. `mapper` is a
  private `new ObjectMapper()` with no injection seam, and the only argument is a record component that cannot be
  null, so `writeValueAsString` cannot throw. Report as **dead code**, not a coverage gap, and do not fake-reach it
  (no reflection into `mapper`, no `assertThrows`). Removing the catch would take both classes to 100% — that is a
  source change, so it belongs to the human.
- **Re-measure per module *after* every slice; roadmap target lists go stale.** `FsLlmProfileStoreAdapter` (17
  missed) was never on the Phase 3 list and only appeared from a fresh `jacoco:report` taken after the other seven
  adapters were closed — it had no coverage of `findModelProfile` / `updateModelProfile` / `deleteModelProfile` /
  `updateRouteAssignment` / `deleteRouteAssignment` / either invalidator branch. Take a per-class reading at the end
  of each slice and sweep for same-shaped gaps in sibling classes the plan forgot.

#### `persistence-fs` notes (2026-09-15, Phase 3b first slice — evolution/directive/lock stores)

The first slice after Phase 3's original list closed. Targets came straight from the re-measurement table in
`docs/TEST_COVERAGE_ANALYSIS.md` §Phase 3:

| Class | Before | After | New test file |
|---|---:|---:|---|
| `FsEvolutionEventStore` | 39 missed, **0 covered** | **0** | `FsEvolutionEventStoreTest` (7 tests) |
| `FsLockManager` | 44 | **5** | `FsLockManagerTest` (16 tests) — no test file existed |
| `FsDirectiveStore` | 44 | **0** | `FsDirectiveStoreDeepTest` (14 tests) |

`persistence-fs`: **523 → 400 missed, 81.25% → 85.66%**; module tests **236 → 266**.

**A mocked store test is not coverage — again.** `FsDirectiveStoreTest` already existed but `@Mock`s
`FilesystemFileStore`, so it proves only delegation. Because the mock returns canned values, the dedup predicate
(`existing.stream().anyMatch(...)` over an *empty* list never invokes its lambda), both limit arms, the whole
`update` loop and the whole `purgeExpired` loop never execute. Adding a real-temp-dir companion class took the
class 44 → 0 with no change to the existing mock test. **Check whether an existing test file is mock-based before
assuming a class is "already tested"** — the file existing and the behaviour running are different things.

**The reusable trick: put a DIRECTORY where the code expects a FILE.** This reaches `IOException` arms
deterministically, with no mocking and no filesystem permissions games:

```java
// writeMeta()'s Files.move(tmp, metaFile, ATOMIC_MOVE, REPLACE_EXISTING) cannot replace a directory
Files.createDirectories(locksDir.resolve("metablocked.meta"));
assertFalse(manager.tryLock("metablocked", 5));   // -> catch (IOException _) -> return false
```

The same shape reaches three different arms in `FsLockManager`: a directory at `locks/<key>.lock` makes
`new RandomAccessFile(...)` throw (→ `return false`), a *file* where the `locks/` directory belongs makes
`Files.createDirectories(...)` throw (→ `return false`, and `ensureLocksDir()` throw `UncheckedIOException`), and a
directory at `locks/<key>.meta` makes `renew()`'s `writeMeta` throw. `isLocked()` then reads a directory and gives
up through its `catch (Exception _)`.

**Reach `OverlappingFileLockException` with a second instance, not a second thread.** `FileChannel.tryLock()`
throws it when *this JVM* already holds the lock, so a second `FsLockManager` over the same data dir (whose
`heldLocks` map is empty, so it skips the `containsKey` early-return) lands in that catch:

```java
assertTrue(manager.tryLock("contended", 30));
FsLockManager second = new FsLockManager(props);
second.ensureLocksDir();
assertFalse(second.tryLock("contended", 30));   // OverlappingFileLockException
```

**Drive `isLocked()`'s on-disk arms by writing the `.meta` file yourself** — a future instant (true), a past
instant (prunes both files and returns false), whitespace only (false), `not-an-instant` (the `catch (Exception _)`),
and a `.lock` file with no `.meta` (the `: lockFile` ternary arm).

**`FsLockManager` residual 5 lines — reported, not faked.** `82`–`83` (`channel.tryLock()` returning `null`) needs a
**second OS process** holding the fcntl lock; an in-JVM competitor raises `OverlappingFileLockException` instead, so
this is genuinely cross-process. `101` (`closeQuietly`'s `catch`) needs `RandomAccessFile.close()` to throw, and
`116`–`117` (`unlock`'s `catch`) needs a filesystem that refuses deletion. The latter two are *only* reachable via a
read-only-directory `chmod` trick, which silently stops working when the suite runs as root — that is exactly the
machine-dependent test the "a test whose coverage depends on the machine can skip the SUBJECT" rule forbids. Leave
them.

#### `persistence-fs` notes (2026-09-15, Phase 3b second slice — memory, checkpoint and vector stores)

Second slice, driven **entirely by re-measurement**: the first slice's six-class table was already stale by the
time it landed — `FsApiCredentialStoreAdapter` (19 missed) was never on it, and four of the six named entries were
mock-based store tests that proved only delegation. Final: **400 → 16 missed, 85.66% → 99.43%**, tests
**266 → 404**, **49 of 54 classes at 0 missed**.

| Class | Before | After | Test file |
|---|---:|---:|---|
| `FsApiCredentialStoreAdapter` | 19 | **0** | `FsIdentityStoreAdapterTest` 19 → 21 tests |
| `FsBootstrapStateStoreAdapter` | 3 | **0** | `FsBootstrapStateStoreAdapterTest` 7 → 14 tests |
| `FsRecallMemoryStore` | 39 | **0** | `FsRecallMemoryStoreDeepTest` (10) |
| `FsExtractedMemoryStore` | 32 | **0** | `FsExtractedMemoryStoreDeepTest` (8) |
| `FsCoreMemoryStore` | 8 | **0** | `FsCoreMemoryStoreDeepTest` (6) |
| `FsArchivalMemoryStore` | 28 | **1** dead | `FsArchivalMemoryStoreDeepTest` (11) |
| `FilesystemFileStore` | 28 | **2** dead | `FilesystemFileStoreErrorPathTest` (13) |
| `FsCheckpointHelper` | 12 | **0** | `FsCheckpointHelperDeepTest` (15) |
| `FsVectorIndexMetadataAdapter` | 29 | **6** unreachable | `FsVectorIndexMetadataAdapterDeepTest` (8) |
| `FsMemoryRetrievalAdapter` | 21 | **0** | `FsMemoryRetrievalAdapterDeepTest` (10) |
| `FsExecutionTraceStoreAdapter` | 32 | **2** dead | `FsExecutionTraceStoreAdapterDeepTest` (18) |
| `LuceneIndexManager` | 17 | **0** | `lucene/LuceneIndexManagerDeepTest` (12) |
| seven small gaps (≈16 lines) | 16 | **0** | four existing test files extended |

Trajectory, re-measured at every step: **400 → 267 → 161 → 100 → 49 → 16**.

**The residual 16 lines, every one filed dead or environment-locked** (verified line-by-line against `jacoco.xml`
`<sourcefile>` entries — missed iff `mi>0 && ci==0`):

| Class | Lines | Why out of reach |
|---|---|---|
| `FsVectorIndexMetadataAdapter` | 125–126 | `catch (AtomicMoveNotSupportedException)` — `ATOMIC_MOVE` works on every filesystem the suite can use. |
| `FsVectorIndexMetadataAdapter` | 134–135 | deleting the temp manifest in `finally` — it was created in the same directory microseconds earlier. |
| `FsVectorIndexMetadataAdapter` | 168–169 | `NoSuchAlgorithmException` for SHA-256 — mandated by the JLS. |
| `FsExecutionTraceStoreAdapter` | 113 | `if (batch.isEmpty()) continue` — a `groupingBy` map never holds an empty list. |
| `FsExecutionTraceStoreAdapter` | 151 | `if (events.isEmpty()) return` — only called from `processRunBatch`, i.e. for a non-empty group. |
| `FilesystemFileStore` | 86–87 | `catch (NoSuchFileException)` — Jackson's `readValue(File)` throws `FileNotFoundException`, a **sibling** not a subtype, so the `catch (IOException)` below always wins. |
| `FsArchivalMemoryStore` | 137 | inner-loop back-jump — the `\|\| chunks.stream().anyMatch(...)` disjunct is true for every element once `found` is set, so the loop always breaks on iteration 1. |
| `FsLockManager` | 82–83, 101, 116–117 | same environment-locked set as slice 1. |

**New traps this slice:**

- **Check the *declared* exception of the call you are guarding — a sibling is not a subtype.**
  `FilesystemFileStore.read` catches `NoSuchFileException`, but Jackson's `readValue(File)` throws
  `FileNotFoundException`; both extend `IOException`, so the more general catch below always wins and the specific
  one is dead. Before writing a `catch`, confirm the callee's declared exception (and what it actually throws at
  runtime) rather than assuming the name matches.
- **A failed Lucene `commit()` closes the writer.** The `index`-failure arm and the `delete`-failure arm therefore
  cannot share a `LuceneIndexManager`: the second call throws `AlreadyClosedException`, not `UncheckedIOException`.
  Give each write-failure arm its own manager instance. (`close()` declares `IOException`, so try-with-resources
  needs `throws IOException` on the test method.)
- **`MultiFieldQueryParser` is lenient in ways that break the obvious fixtures.** `"(alpha"` parses fine, and
  `"alpha\\"` is stripped by the analyzer before parsing, so neither reaches the escaped-retry path. Use `"alpha^"`,
  whose escaped retry still matches the document, so `size() == 1` proves the retry ran. For the "both attempts
  fail" arm, use `"alpha AND"`.
- **`Files.writeString` does not create parent directories.** `FsCheckpointHelperDeepTest`'s corrupt-file case threw
  `NoSuchFileException` until it called `Files.createDirectories(fileStore.resolve(...))` first.
- **An inline field initialiser survives a missing JSON property.** `SessionIndex.latestActivity` is initialised at
  declaration, so Jackson leaves the initialiser in place when the property is absent and the backfill branch never
  runs. The test must write `"latestActivity": null` explicitly.
- **`FilesystemFileStore.readOptional` throws on a malformed file** rather than returning empty — one corrupt file
  takes down the whole read. Pin it as observed behaviour; do not soften it into an imagined degradation.

#### `persistence-sqlite` notes (2026-09-15, Phase 3b third slice — stores, knowledge base, ingestion)

First SQLite slice. **375 → 140 missed, 80.00% → 92.53%**; module tests **192 → 280**. Ten classes closed:
`SqliteArchivalMemoryStore` 49, `GlobalKnowledgeBase` 42, `SqliteMemoryRetrievalAdapter` 24,
`VectorIngestionService` 23, `SqliteCheckpointHelper` 22, `SqliteAdvisoryLockAdapter` 20,
`SqliteIdentityStoreAdapter` 15, `SqliteChatMemoryStoreAdapter` 11 (no test file existed at all),
`SqliteSecretStoreAdapter` 10, `JdbiProducer` factories/mappers 22.

**THE ONE THAT CHANGED EVERY NUMBER: `target/jacoco.exec` is APPENDED to, not replaced.** A stale exec file from a
previous session made the first reading of this module a *union* of old and new data, understating the baseline by 7
lines. The tell was a re-measure in which two untouched classes went **up** (`FtsConnectionInterceptor` 16 → 19,
`SqliteSecretStoreAdapter` 6 → 10) — impossible for an unchanged tree. **Always `rm -f <module>/target/jacoco.exec`
before a measurement run**, and never compare a figure taken without that deletion against a clean one. (A full
`clean` would also work but throws away the compiled classes.)

**A bare `Jdbi.create(...)` needs `SqlObjectPlugin`.** The production producer installs it, so this only bites tests
that build their own `Jdbi`. Without it every `withExtension`/`useExtension` fails with
`NoSuchExtensionException: Extension not found: <Dao> [Hint: maybe you need to register a plugin, like SqlObjectPlugin]`.
Note the failure mode is sneaky in two ways: an adapter that catches `Exception` around the call will silently
"pass" for the wrong reason (the `SqliteSecretStoreAdapter.find` missing-table test did exactly this), and the retry
wrapper will retry three times with backoff first, so the test takes ~0.4 s before failing.

**Real SQLite + a registered SQL-function shim beats mocking the JDBI chain.** `GlobalKnowledgeBase` and
`VectorIngestionService` call `vec_distance_cosine`, which exists only once `vec0` loads. `vec0` ships in the repo
(`app/src/main/resources/native/vec0/`) but is **not** wired into this module's tests, so mocked-`Jdbi` tests can
never reach the query bodies. Registering the function makes the real SQL run:

```java
ConnectionFactory cf = () -> {
    Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
    Function.create(c, "vec_distance_cosine", new Function() {      // org.sqlite.Function (sqlite-jdbc)
        @Override protected void xFunc() throws SQLException { result(0.25); }
    });
    return c;
};
Jdbi jdbi = Jdbi.create(cf).installPlugin(new SqlObjectPlugin());
```

Three details decide whether this works:

- **File-backed, not `:memory:`.** Each JDBI operation opens its own connection; an in-memory database is empty for
  every call after the first. Use `@TempDir` + `jdbc:sqlite:<path>`.
- **A schema-qualified table (`fts.vec_global`) needs `ATTACH DATABASE '<path>' AS fts` on every connection**, so do
  it inside the same `ConnectionFactory`.
- **Derive the shim's value from the stored data** rather than returning a constant — then `ORDER BY distance ASC`
  and a `1.0 - distance` score are both verifiable instead of incidental. Here the shim read the stored vector's
  first component, which the mocked `EmbeddingModel` controlled.

**A mocked collaborator that *wraps* the work hides the work.** `SqliteAdvisoryLockAdapterTest` mocks
`JdbiRetryWrapper`, so the lambdas it is handed never execute and none of the adapter's SQL runs (20 uncovered lines
beside a green class). The wrapper is a plain class with an implicit no-arg constructor, so the real one can be used
against a real database — and then every branch of the lock protocol is observable through its own effects. Same
shape as the `@Nullable`-injected-invalidator trap: **check whether the existing test stubs a collaborator that the
subject merely delegates to.**

**Cover a package-private nested factory through a test-scope bridge.** `JdbiProducer`'s argument factories and
column mappers are package-private and registered only by the CDI producer, so nothing calls `build()`/`map()` on
them. Testing them directly means being in the `jdbi` package — but a test in another package that needs the
production `float[]` factory cannot name it. Add a two-line public accessor in the *test* tree
(`src/test/java/.../jdbi/TestJdbiArguments.java`) rather than reimplementing the encoding, which would test the copy.
`build()` and the mappers' constructors are package-private but accessible from a same-package test, and every
factory ignores its `StatementContext` (pass a mock — `new StatementContext()` needs three args).

**Pre-set the interrupt flag to reach `awaitTermination`'s `catch (InterruptedException)` — but only while the
executor is still busy.** `AbstractQueuedSynchronizer.awaitNanos` throws immediately when the calling thread is
already interrupted, so `Thread.currentThread().interrupt(); service.shutdown();` lands in that catch with no
waiting — clear the flag in a `finally`. **An idle executor makes this vacuous**: `awaitTermination` returns `true`
without consulting the interrupt status, the catch is skipped, and `assertTrue(isInterrupted())` is satisfied by the
test's own `interrupt()` call. Park a writer in `Thread.sleep` first, and assert `shutdown()` returned in **under**
5 s — that is what proves the interrupt arm ran rather than the timeout arm. The sibling branch (`awaitTermination`
returning `false`) has no shortcut because the 5-second window is hardcoded: cover it with one deliberately slow
test that blocks the single writer thread, or leave it and say so.

**Two smaller traps.** `assertEquals(1, jdbi.withHandle(h -> h.createQuery(...).mapTo(Long.class).one()))` does not
compile — `assertEquals` is ambiguous across `(long, Long)` / `(int, Integer)` / `(float, Float)`; assign to an
explicitly-typed local first. And a thin adapter over a DAO is worth a real-DB test even when a smoke test exists:
`SqliteChatMemoryStoreAdapter` had **no test file at all** (0 of 11 executable lines) while being the live
`ChatMemoryStorePort` for the default backend.

#### `persistence-sqlite` notes (2026-09-15, Phase 3b fourth slice — the residual)

Closing the module's remaining 140 lines. **140 → 7 missed, 92.53% → 99.63%**; tests **280 → 339**; 56 of 61 classes
clean. Twelve classes closed plus 15 lines of scattered 1–2 line residues. The residual 7 are five findings, not gaps.

**A test that pins the wrong thing is worse than no test.** Two tests in this module passed while never reaching the
code they were named for. Isolating a suspect against a **clean `jacoco.exec`** is the decisive experiment — ~30 s
that turns "the suite is green" into "this test executes these lines":

```bash
rm -f <module>/target/jacoco.exec
./mvnw -o -pl <module> test -Dtest=TheSuspectTest -Dsurefire.failIfNoSpecifiedTests=false jacoco:report
```

Then read individual lines, not the class total:

```python
for l in sourcefile.findall("line"):          # the sourcefile for FtsConnectionInterceptor.java
    if 66 <= int(l.get("nr")) <= 76: print(l.get("nr"), l.get("mi"), l.get("ci"))
```

- **`FtsConnectionInterceptorVecFallbackTest`**: `Arc.container()` is **null** outside a running Quarkus app, so
  `onConnectionAcquire` returned on its first statement. The isolated run showed the `Arc == null` guard **covered**
  and the two setup calls plus `resolveExtensionPath`'s absolute branch **missed**; its
  `assertFalse(isVectorSearchEnabled())` was trivially true from the flag's initial value. **Any plain JUnit test of
  code guarded by `Arc.container()` / `CDI.current()` must stub the static** (`mockStatic(Arc.class)`), or it is a
  no-op. Fixing that one line made the test real — and because it drives a *real* SQLite connection, it now covers
  ATTACH, the `pragma_module_list` probe, `unwrap`, `enable_load_extension` and a failing `load_extension` end to end.
- **`VectorIngestionServiceDeepTest.shutdownRestoresTheInterruptFlagWhenInterruptedWhileDraining`**: an idle executor
  makes `awaitTermination` return `true` without checking the interrupt status, so the catch never ran and
  `assertTrue(isInterrupted())` was satisfied by the test's own `interrupt()`. Fix: park a writer in `Thread.sleep`
  **and** pre-set the flag, then assert `shutdown()` returned in under 5 s.

**A positional `createStatement()` helper silently mis-assigns mocks when a branch is skipped.**
`FtsConnectionInterceptor` calls `connection.createStatement()` once per setup step, so a helper that queues
`pragma, attach, module, vec` hands the *attach* mock to the *module* probe the moment the ATTACH arm is skipped
(auto-commit off). The probe's `executeQuery` then returns `null`, the NPE is swallowed by the interceptor's own
`catch (Exception)`, and the test passes having never reached `load_extension`. Pass the statements as a **vararg in
the order the code will ask for them**, and **always stub `conn.unwrap(SQLiteConnection.class)`** — a null return
there is the same invisible trap.

**Malformed JSON: writing is provably total, reading is not.** `SqliteOnboardingDraftStoreAdapter.toJson` and
`SqliteLlmProfileStoreAdapter.toJson` have dead `catch (Exception _) { return "[]"; }` arms — a private, non-injected
`new ObjectMapper()` and a never-null argument mean `writeValueAsString` cannot throw. Their **read-side twins** look
identical but are live: `OnboardingDraftJdbiDao.DraftRowMapper.parseList` and `LlmProfileJdbiDao.RouteRowMapper.parseList`
parse a **database column**, so a corrupt value really does throw. Cover them by writing `'{not json'` / `'[not json'`
into `completed_step_ids` / `ordered_profile_ids` with raw SQL and reading the row back — and assert an **intact
sibling column still parses**, which proves the mapper did not abandon the whole row. Do not file these as dead code
just because the `toJson` arms are.

**Closed-set and environment guards — report, never fake-reach.** The module's residual 7 lines:
`SqliteVectorIndexCompatibilityInitializer:84` (the `throw` for a null whitelist entry — `NAMESPACE_TABLES` is a
`Map.of` over both `VectorIndexNamespace` constants and `verify` is private, so no value reaches it);
`FtsConnectionInterceptor:190` (the `break` when the upward extension-path walk runs out of parents — needs a cwd
shallow enough that four `getParent()` hops hit `/`); `SqlitePersistenceReflectionConfig:177` (the empty class body:
it exists only to carry `@RegisterForReflection`, so its implicit constructor never runs); and the two `toJson`
catches above.

**Reusable shape — where the last lines of a module hide.** Once the big classes are closed, sweep for **uncalled
public delegate methods**: `deleteAll`, `deleteByRoleAndKey`, `deleteByUserId`, an unfiltered `findAll`, a null/empty
batch guard, a protected no-arg constructor. Each is a one-line test, and no roadmap ever lists them because a
per-class `LINE_MISSED` ranking only surfaces them after the large classes are gone.

#### `persistence-postgres` notes (2026-09-15, Phase 3b fifth slice — closing the phase)

The last Phase 3b module, and the only one that had never been touched: 32 of 45 classes carried missed lines and three
were fully dark. **244 → 7 missed, 82.19% → 99.49%**; tests **159 → 267**; **237 lines closed, +17.30 pp**, 41 of 46
classes clean. Slice order that worked: (A) the roadmap's two named targets + the initializer, 81 lines; (B1) guards,
delegates, JSON bridges, 67; (B2) the fully-uncovered adapters + `mergeBatchDecisions`, 56; (C) the residual, 33.

**The `@QuarkusTest` suite REALLY RUNS even though Docker is unavailable.** The log line
`ERROR [org.testcontainers.dockerclient.DockerClientProviderStrategy] Could not find a valid Docker environment` is
Testcontainers **giving up**, not the suite skipping — `PostgresTestResource` falls back to a zonky
**`embedded-postgres`** server. Baseline was 159 tests, not 0. Never infer "skipped" from that error; check the
surefire count. This is what made a real-DB (`@QuarkusTest`) strategy viable for the whole module.

**Four JDBI stub shapes cover essentially every adapter in the module.** Match the return type — a `void` method
(`useExtension` / `useTransaction` / `useHandle`) **must** use `doAnswer`, or Mockito silently no-ops:

```java
// withExtension (returns a value) — also withHandle / inTransaction
when(jdbi.withExtension(eq(BootstrapStateJdbiDao.class), any())).thenAnswer(inv -> {
    ExtensionCallback cb = inv.getArgument(1);
    return cb.withExtension(dao);
});
// useExtension (void) — also useTransaction(HandleConsumer) / useHandle
doAnswer(inv -> { ExtensionConsumer c = inv.getArgument(1); c.useExtension(dao); return null; })
        .when(jdbi).useExtension(eq(BootstrapStateJdbiDao.class), any());
```

Two signature facts worth memorising: `mapTo(Class<T>)` is on `ResultBearing` and returns **`ResultIterable<T>`**, so
`one()` is on the *iterable*, not the `Query`; and an **undeclared** checked exception needs an explicit type witness
for `thenThrow`, e.g.
`when(jdbi.<Optional<EmbeddingIndexDescriptor>, VectorIndexMetadataJdbiDao, RuntimeException>withExtension(eq(...), any())).thenThrow(...)`.

**`aryEq` is in `org.mockito.AdditionalMatchers`, NOT `org.mockito.ArgumentMatchers`.** Mockito compares `byte[]` by
identity, so a plain `eq` never matches a freshly built array — `PostgresSecretStoreAdapter`'s five-column round trip
needs `verify(dao).save(eq("sess-1"), eq("api-key"), eq(3), aryEq(ciphertext()), aryEq(nonce()));`.

**A guard test is only meaningful when it installs NO stubs.** Asserting the empty return of a blank-identifier
short-circuit passes even if the guard were deleted. The proof is that no connection is opened at all:

```java
assertTrue(adapter.findExternalIdentityByUserId(null, "user-1").isEmpty());
// ... twelve more ...
verifyNoInteractions(jdbi);          // against an UNstubbed mock — this is the assertion that matters
```

**Batch writes: `Handle.prepareBatch(CharSequence)` → `PreparedBatch`,** whose `bindMethods(Object)`, `add()` and
`execute()` all chain. `add()` and `bindMethods` are called once **per DTO** — so for a 2-element batch,
`verify(batch, times(2)).add()`; and the null/empty-list guard is proved the same way as above
(`verifyNoInteractions(jdbi)`), not by the return value.

**The `toInstant` fallbacks are schema-locked, not gaps.** `OnboardingDraftJdbiDao.DraftRowMapper.toInstant` and
`LlmProfilePostgresDao.toInstant` both end `return Instant.parse(o.toString());` after testing
`null` / `Instant` / `Timestamp`. Every temporal column in every migration is `TIMESTAMP WITH TIME ZONE` (or the
identical `TIMESTAMPTZ`), which pgjdbc maps to `java.sql.Timestamp` — so the `Timestamp` branch always wins. Per-line
counters prove it: line 129 is `mi=0 ci=9` (fully covered) while line 130 is `mi=4 ci=0`. Defensive fallback for a
driver/column type that never occurs. **Report, do not reach via a synthetic `ResultSet`.**

**The dead-`toJson` / live-`parseList` asymmetry is now a rule, not an anecdote** — it reproduces byte-for-byte on
Postgres after being found on SQLite. `PostgresOnboardingDraftStoreAdapter:40–41` and
`PostgresLlmProfileStoreAdapter:63–64` are dead `catch (Exception _) { return "[]"; }` arms (private, non-injected
`new ObjectMapper()`, never-null argument → `writeValueAsString` cannot throw). Their read-side twins parse a
**database column** and really do throw — cover them by writing `'{not json'` / `'[not json'` into
`completed_step_ids` / `ordered_profile_ids` with raw SQL, reading the row back, and **asserting an intact sibling
column still parses** (proves the mapper did not abandon the whole row). Two backends is enough to state the rule.

**The residual 7 lines are five independently verified findings, zero reachable gaps:** the two dead `toJson` pairs
above, the two schema-locked `Instant.parse` fallbacks, and `PostgresPersistenceReflectionConfig:172` (the empty
`@RegisterForReflection` holder — it exists only to carry the annotation, so its implicit constructor never runs).
Nothing was faked via reflection.

Two smaller traps this module taught:

- **Port and DAO method names differ.** The port is `CronRunStorePort.countAll(String status)`; `countByStatus` is the
  **DAO** method. A status-filtered test must call `store.countAll("SUCCESS")`, and that branch issues a *different*
  DAO query from the unfiltered one, so it needs its own assertion.
- **Assigning an `@Inject` field in a test trips PMD `UnnecessaryAssignment`.** Add a class-level
  `@SuppressWarnings("UnnecessaryAssignment")` (the sibling-test convention), rather than restructuring the test.

**`PostgresPersistenceAdapter.execute(sessionId, operationName, action)` accepts and ignores both identifiers** — it
just runs the action inside `jdbi.useTransaction(...)` and lets a failure escape so JDBI rolls back. Worth an explicit
`identifiersAreNotValidated` test so the missing validation is documented rather than assumed.

#### `llm-providers` notes (2026-09-15, Phase 4)

All seven Phase 4 targets live in this one module, so use the fast `-pl llm-providers` loop (its `clean verify` is
~20s of tests plus PMD/spotless).

- **A test whose coverage depends on the machine is not a test.** `AgyTokenResolverTest` used to wrap every assertion
  in `if (AgyCliExecutor.isAvailable()) { ... } else { ... }`. `~/.local/bin/agy` **is** installed here, so
  `resolveToken` short-circuited at `return "agy-cli-session"` and the vault / extractor / env fallbacks were never
  reached — 11% line and **7.1% branch** coverage from a file that looked thorough. Pin such probes with
  `Mockito.mockStatic(AgyCliExecutor.class)` (inline mock maker is the default, and it works on a `final` class with a
  private ctor) and open/close it in `@BeforeEach`/`@AfterEach`. Then both branches are reachable everywhere.
- **`System.getenv` is not settable from a test — use `Assumptions`, never a silent `if`.** The env-var tail of a
  fallback chain (`AMSHA_AGY_API_KEY` / `GEMINI_API_KEY` / `GOOGLE_API_KEY`) and the `ANTIGRAVITY_AGENTAPI_EXE` /
  `PATH` probes in `AgyCliExecutor.resolveCliPath` cannot be driven. Guard those assertions with
  `Assumptions.assumeTrue(precondition, "reason")` so they report as **skipped** (visible in the report) rather than
  branching invisibly. Write a small helper for the precondition (e.g. `noAgyOnPath()` walking `PATH`) so the skip is
  honest about what it needs.
- **`System.setProperty("user.home", ...)` IS settable — use it to reach later branches.** `resolveCliPath` returns
  early from `~/.local/bin/agy` on a machine that has the CLI installed, hiding the whole `PATH`-scan loop. Pointing
  `user.home` at an empty temp dir during the test reaches lines that are otherwise dead — and it is the *only* way
  to get there without `System.getenv`. **Restore it in a `finally`**, because surefire reuses one JVM for the module.
- **Drive a subprocess with a fake executable, not a mocked `ProcessBuilder`.** `AgyCliExecutor.execute` cannot be
  mocked without replacing the JDK. Write a small POSIX shell script into a temp dir, `setExecutable(true)`, and pass
  its `Path` as the `agyExe` argument — the code only calls `agyExe.toString()`, so a script is indistinguishable from
  the real binary while giving you canned stdout, a chosen exit code, chosen stderr, or a deliberate hang. Use a
  **quoted heredoc** (`cat <<'DELIM' … DELIM`) to emit a JSON payload verbatim.
- **A timeout arm needs no sleep.** `Duration.ZERO.toSeconds()` is `0`, so `process.waitFor(0, TimeUnit.SECONDS)`
  returns `false` immediately and the executor force-destroys the process — assert `"timed out after 0 seconds"` with
  no wall-clock cost. Pair it with a `sleep 30` script so the process genuinely outlives the wait.
- **An unstartable process is a distinct path from a non-zero exit.** `ProcessBuilder.start()` on a non-existent path
  throws `IOException`, which the executor wraps in `RuntimeException("Failed to execute Antigravity CLI request: …")`.
  Feed it a `Path` inside the temp dir that was never created, and assert on the cause being non-null.
- **Report env-locked lines; do not fake-reach them.** After both helpers were done the residue was
  `AgyCliExecutor` 43–45 (`ANTIGRAVITY_AGENTAPI_EXE`) and 62 (`PATH` match), plus three `.filter(...)` *branches* in
  `AgyTokenResolver`. Two more branches (try-with-resources abnormal close) need `VaultKey.close()` to throw, and
  `VaultKey` is final with a zeroing `close()`. All are recorded as findings in the coverage doc, not pinned.
- **`FoundryCliExecutor` is the same recipe with a different response shape.** `execute` reads `root.path("text")` and
  falls back to `root.asText()` when that is blank *and* the whole document is textual — so the fake CLI must emit
  both `{"text":"…"}` and a bare `"plain string"` payload. Its `buildCliPrompt` `AiMessage` branch is *nested*
  (text append and tool-call loop are independent), unlike the agy version's single `&&`-chained condition, so a
  blank-text assistant turn carrying tool requests must still render `Assistant Tool Call: …`.
- **A homebrew install shadows the `~/.local` probe — blank `user.home` is the way past it.**
  `/opt/homebrew/bin/foundry` is installed here (a symlink into `Cellar/foundrylocal/…`) and `resolveCliPath` checks
  that absolute path *before* `~/.local/bin/foundry`, so the latter is unreachable and its test is skipped by
  assumption. The `PATH` scan is reachable only by setting `user.home` to `""`, which skips the entire
  `if (!userHome.isBlank())` block. Note this differs from `AgyCliExecutor`, whose homebrew probe does not exist —
  check the machine before assuming which probe wins.
- **Mockito rejects nested stubbing.** `when(container.instance(X.class)).thenReturn(handle(true, service))` fails with
  `UnfinishedStubbingException` when `handle(...)` itself calls `Mockito.mock`/`when` — build the mock into a **local
  variable first**, then stub. Mockito's hint #3 names this case explicitly; the stack trace points at the helper,
  not the real problem.
- **`AnthropicChatModel.builder().build()` and `ChatModelFactory.createOpenAi(...)` need a live ArC context.**
  Both construct a Quarkus *Reactive REST Client*, which throws `"The Reactive REST Client needs to be built within
  the context of a Quarkus application with a valid ArC (CDI) context running"` in a plain unit test.
  `GoogleAiGeminiChatModel` and `OllamaChatModel` take a different HTTP path and *do* build. That asymmetry is why
  `SpecializedChatModelFactory`'s Gemini/Ollama lines were already covered while its Anthropic lines were not. Assert
  the **routing** (that the call reaches the Anthropic/OpenAI builder and cannot complete without CDI) without
  matching the third-party message, and leave the genuinely happy path to a future `@QuarkusTest`. Pin
  `Arc.container()` with `mockStatic(Arc.class)` to cover `resolveGovernedTransport`'s catch arms — throw a
  `NoClassDefFoundError` for the standalone fallback and an `Exception` for the rethrow.
- **`SpecializedChatModelFactory` is package-private**, so its test must live in `com.amsha.llm.providers`. It had no
  direct test at all before this pass — it was reached only indirectly through `ChatModelFactory`, which is why 59
  lines were missed despite the module looking well covered. Sweep for package-private helpers that only the public
  entry point exercises.

#### `llm-providers` notes (2026-09-15, Phase 4 third pass — the SSE wrappers)

The three `*ResponsesChatModel` classes (`Foundry`, `Kimchi`, `Agy`) plus `NvidiaMessageSanitizer` closed
**299 → 1** missed, taking the module **72.38% → 79.87%**. All three wrappers build their own `HttpRequest` and hand
it to a `GovernedTransportService`, so **the fixture is a loopback `HttpServer` plus a mocked transport whose `send`
delegates to a real `HttpClient`** — the same recipe as `CodexResponsesChatModelTest` /
`ClaudeCodeResponsesChatModelTest`. Copy it rather than inventing a new one.

- **One root context plus a captured path beats one context per URL variant.** `server.createContext("/", this::handleChat)`
  matches every resolved path, so a test can assert `exchange.getRequestURI().getPath()` and cover six
  `resolveEndpoint` branches in a single method (`baseUrl`, `baseUrl + "/"`, `+ "///"`, `+ "/chat/completions"`,
  `+ "/v1"`, `+ "/v1beta"`, `+ "/openai"`). Registering a context per variant is needless work.
- **Assert a predicate by behaviour, not by outcome.** `FoundryResponsesChatModel.isTransportFailure` gates the CLI
  fallback. Asserting that a failure is *rethrown* proves nothing — the identical exception is rethrown whether the
  predicate matched or not. Make the CLI **available** and assert the CLI response comes back; that is the only
  observable that distinguishes the two. Eight arms are covered this way (six checked exception types travelling as
  the *cause* of an unchecked wrapper, the `HTTP 404` / `HTTP 503` message arms, and a nested two-level chain).
- **`mockStatic` the CLI executor to drive the fallback with no real binary.** `mockStatic(FoundryCliExecutor.class)` /
  `mockStatic(AgyCliExecutor.class)` + `isAvailable()` → `true`, `resolveCliPath()` → a temp `Path`, and
  `execute(...)` → a canned `ChatResponse`. No `Assumptions` skip, no `agy`/`foundry` on the machine, deterministic.
- **`mockStatic(Arc.class)` covers the CDI-resolution arms in both directions.** Stub `Arc::container` to a mocked
  `ArcContainer` whose `instance(GovernedTransportService.class)` returns a mocked `InstanceHandle`: `isAvailable()`
  true → the model completes a real round trip through the Arc-resolved transport (proves the fallback wires up);
  false → resolution yields null and `chat()` throws; `thenThrow` on `Arc::container` → the swallow-and-return-null
  catch. This is the only way to reach those lines without a container.
- **`doThrow(...).when(mock).send(...)` — never a second `when(...).thenThrow(...)`.** The `@BeforeEach` stub already
  answers that call, so a second `when(...)` *invokes the existing answer* with null matcher arguments and NPEs
  inside the `HttpClient` (`Objects.requireNonNull` → `HttpClientImpl.sendAsync`). Symptom: a stack trace pointing at
  your own `thenAnswer` lambda, not at the test.
- **`GovernedTransportService.send` declares NO checked exception.** Mockito rejects `doThrow(new IOException(...))`
  with `Checked exception is invalid for this method!` — an `IOException` cannot be injected there at all. Use
  `OutboundHttpException` (a `RuntimeException` with a `Category` enum, `Category.IO` for a transport failure) or a
  plain `RuntimeException`. Note `IllegalStateException` is *caught and rethrown* by both wrappers, so it cannot be
  used to reach a generic `catch (Exception)` wrap.
- **`ChatRequest.parameters()` is never null for a built request.** `ChatRequest.builder().messages(...).build()`
  yields non-null parameters, so `(request.parameters() != null) ? request.parameters().toolSpecifications() : null`
  always takes the true branch, and an absent tool set arrives as an **empty list, not `null`** — verified by
  `ArgumentCaptor` (`FoundryResponsesChatModel` 132 and `AgyResponsesChatModel` 148 are dead ternary arms). A test
  asserting `isNull()` on that argument is wrong, not a bug in production.
- **A `@QuarkusTest` sibling makes the degraded branches unreachable.** `AgyChatModelLoopbackTest` already existed but
  is a `@QuarkusTest`: it runs against a live ArC container and a real `agy` CLI probe, so the "no transport", "no
  CLI" and "unresolvable handle" rungs of `chat()`'s dispatch ladder cannot be reached there — and its live container
  is why `buildMessages` was entirely missed (it only ever sent user messages). Add a **plain** unit test alongside,
  which is also the existing house pattern (`CodexResponsesChatModelTest` / `ClaudeCodeResponsesChatModelTest` next to
  their `*LoopbackTest` siblings). Do not try to make the `@QuarkusTest` do both jobs.
- **A multi-rung dispatch ladder should be tested rung by rung.** `AgyResponsesChatModel.chat` is
  `hasHttpToken → HTTP`, `else CLI available → CLI`, `else transport == null → throw A`, `else throw B`. Four
  separate tests, with `mockStatic(AgyCliExecutor.class)` controlling rung 2 so the real CLI is never invoked
  (`~/.local/bin/agy` **is** installed here, so the real probe would return true and try to run it).
- **Report divergences between sibling accumulators; do not "fix" them.** A `delta.tool_calls` entry with an `id` but
  no `function` node yields an **empty-named** `ToolExecutionRequest` in `FoundryResponsesChatModel` (its `build()`
  does not filter), and because tool requests exist the response then carries **no text**;
  `KimchiResponsesChatModel.build()` and `AgyResponsesChatModel.buildToolRequests()` both skip blank-named builders.
  `AgyResponsesChatModel` also **assigns** `builder.name = …` (keeping only the last fragment) while *appending*
  `arguments` — the other two append both. Pin the observed behaviour with a comment; a coverage pass must not change
  production behaviour.
- **`NvidiaMessageSanitizer` filters the response by schema but not by bipartite pairing.** `chat()` passes
  `ai.toolExecutionRequests()` (raw) and a **fresh** id map to `internalSanitizeAiMessage`, unlike the history path
  which passes the bipartite-filtered subset. That asymmetry is the only way its out-of-schema `continue` is
  reachable: a response naming an out-of-schema tool loses its tool calls and degrades to text, and with null text it
  becomes an empty `AiMessage`. Its `[TOOL_CALLS]` Mistral regex needs both payload shapes
  (`[{...}]` array form, and `name {...}` single form), plus a malformed array to reach the `readTree` catch, plus a
  marker with no payload at all to reach the "nothing extracted, return the response unchanged" tail.
- **`applyAuthHeaders`'s empty-token guard is dead in `AgyResponsesChatModel`** (line 426): its only caller is
  `chatViaHttp`, reached only when `hasHttpToken`, which already requires `token.length > 0`. A guard that re-checks
  what the caller guaranteed — report it, do not reflect into it.

#### `llm-providers` notes (2026-09-15, Phase 4 fourth pass — resolvers, OAuth, and the provider-factory package)

This pass closed the token resolvers, the OAuth exchange service, `ChatModelFactory`, and the whole
`reasoning` provider-factory package: **478 → 6** missed, module **79.87% → 91.57%** (missed 817 → 342).

**The roadmap's package-level gaps are placeholders, not counts.** The Phase 4 plan listed
"`llm-providers.reasoning` package (21 prod classes, 1 test)" with no line figure. A fresh per-class sweep found
**303 missed lines across those 21 classes** — the single largest block left in the module. Whenever the plan
gives a class *count* instead of a *line* count, sweep before you scope.

**Ask which side of the CDI boundary the code under test sits on** — see §3 above for the full
`Unable to locate CDIProvider` trace. `resolveGovernedTransport()` returning `null` without a container tells you
nothing about whether the *builder* needs one.

**Supplying credentials generically across a family of near-identical classes.** The 21 factories declare their
credential four different ways: `Optional<String> apiKey` (14), `String apiKey` (Omlx), a token resolver
(Agy/ClaudeCode/Codex/Kimchi), or nothing (Foundry/LlamaCpp/Ollama). Walk the declared fields and set each by
**type**, not by name:

```java
for (Field field : target.getClass().getDeclaredFields()) {
    if (Modifier.isStatic(field.getModifiers())) continue;
    if (field.getType() == AgentLlmConfig.class) continue;      // keep the injected bean
    String name = field.getName().toLowerCase(Locale.ROOT);
    Class<?> fieldType = field.getType();

    if (fieldType == CodexTokenResolver.class) { /* mock returning "test-token" */ }
    else if (fieldType == Instance.class)       { /* resolvable mock -> injected transport */ }
    else if (fieldType == Optional.class)       { setField(target, field.getName(), Optional.of(valueFor(name))); }
    else if (fieldType == String.class)         { setField(target, field.getName(), valueFor(name)); }
}
```

with `valueFor` keying off the lower-cased name (`model` → `"test-model"`, `baseurl` → a loopback URL,
`account` → `"test-account"`, `extrabody` → `"{}"`, else → `"test-api-key"`). One helper, no per-provider table.

Three details that make the parameterisation actually cover things:

- **The credential-free local providers resolve their transport from a CDI `Instance`, not a plain field.** The
  `Instance` arm must report itself resolvable (`isResolvable()` → true, `get()` → the injected transport), or the
  `: null` rung is taken and the CDI rung stays uncovered (`OllamaProviderFactory` 91, `LlamaCppProviderFactory` 93).
- **A comma-separated model list is what selects the `MultiModelChatModel` arm.** `create("model-a,model-b")` makes
  `ChatModelFactory.buildMultiModelEntries` return more than one entry, so the `if (entries.size() == 1) return
  entries.getFirst().model();` unwrap is skipped. The concrete return type differs per provider, so the
  parameterised assertion is "it builds" — the four `buildMultiModelEntries` arms belong in their own test.
- **Pure string logic stays in the plain unit test.** `CloudflareProviderFactory.resolveEffectiveBaseUrl` /
  `sanitizeAccountId` / `normalizeBaseUrl` (44 lines) touch no CDI: account-ID substitution, an account ID pasted
  whole from the dashboard URL (`accounts/acc-123/ai/v1` → `acc-123`), the `System.getProperty` fallback, a trailing
  `}` from MicroProfile Config's early-`}` corruption, the `/ai/v1/ai/v1` and `/v1/v1` collapses, and both
  post-substitution throws. `baseUrl()` is the defensive wrapper, so one test asserts it degrades to the configured
  value rather than propagating the failure out of the metadata surface.

**Dead / unreachable lines reported in this pass** (all re-checks of a caller invariant — the §"Guards that
re-check what the caller guaranteed" shape):

- `ProviderOAuthExchangeService` 277 (`accessToken.isBlank()`) and 301 (`refreshToken.isBlank()`) — both values come
  from `extractJsonField`, which only returns when `!val.isBlank()`.
- `ProviderOAuthExchangeService` 452/453 — the `"system"` fallbacks in the private `recordAuditEvent`; all six call
  sites run only after the public entry points rejected null/blank session and actor values.
- `ProviderOAuthPkceStore` 220/221 — the `NoSuchAlgorithmException` catch in `computeS256Challenge`; SHA-256 is a
  required JDK algorithm.
- `CloudflareProviderFactory` 101 — `sanitizeAccountId`'s `return ""` for a null/blank argument; its one caller is
  fed by an `or(...)` ladder that already filters `!id.isBlank()`.

**Order of work that worked:** the two token resolvers first (a direct port of the `AgyTokenResolver` recipe the
first pass took 64 → 0), then `oauth` (44 + 9), then the `reasoning` package sweep, then the `ChatModelFactory`
delegates last — the delegates are only reachable once the container-backed test exists for the factories.

#### `llm-providers` notes (2026-09-15, Phase 4 fifth pass — the `audio` package)

`audio` was the module's last untouched cluster. Measured **118** missed lines across four classes (the roadmap's
`OggOpusToWavConverter` 46 / `OmlxAudioTranscriptionProvider` 40 / `OmlxSpeechSynthesisProvider` 27 omitted
`AudioConversionException` 2 and understated the last two). Closed **109**, leaving 9 unreachable. Module
**91.57% → 94.26%** (missed 342 → 233, covered 3,717 → 3,826 — exactly the package delta, no indirect movement).

**`assumeTrue(ffmpegPresent(), ...)` was hiding the whole `transcode` body.** `ffmpeg` is NOT installed on this
machine, so both live transcoding tests in `OggOpusToWavConverterTest` reported as *skipped* and **40 of the class's
46 missed lines sat inside the region the guard made unreachable**. Same anti-pattern as `AgyTokenResolverTest`'s
`if (AgyCliExecutor.isAvailable())` wrapper (first pass), in its most expensive form: the guard skipped the *subject*,
not just an assertion. If a test's coverage depends on a binary being on the PATH, that test is not a test — replace
it, don't keep it alongside.

**The fix: a fake executable.** `OggOpusToWavConverter` only ever calls `toString()` on its configured `ffmpegPath`
(never `toFile()` or an existence check), so a POSIX script in a `@TempDir` with `setExecutable(true)` is a drop-in
replacement that still drives the real `Process`, the real pipes and the real drainer thread:

```java
private Path fakeFfmpeg(String name, String body) throws IOException {
    Path script = tempDir.resolve(name + ".sh");
    Files.writeString(script, "#!/bin/sh\n" + body);
    assertTrue(script.toFile().setExecutable(true), "could not mark " + script + " executable");
    return script;
}
```

Scripts used (all deterministic, no sleeping):

| Script body | Reaches |
|---|---|
| `for last in "$@"; do :; done` + `printf 'RIFF0000WAVE' > "$last"` + `exit 0` | success path, both suffixes, `drainAsync` |
| `exit 3` | non-zero-exit arm |
| `exec sleep 5` | timeout arm + interrupted-wait arm |
| `sleep 5 &` + `exec sleep 5` | interrupted *join* arm |
| `dd if=/dev/zero of="$last" bs=1024 count=8` | payload bound |
| `rm -f "$last"; mkdir "$last"; echo kept > "$last/keep"` | read-back `IOException` **and** the cleanup catch |

**`Duration.ofNanos(1)` is a deterministic "return immediately" timeout.** `toMillis() == 0`, and
`ProcessImpl.waitFor` (JDK 25) is:

```java
long remainingNanos = unit.toNanos(timeout);
while (remainingNanos > 0 && !hasExited) { remainingNanos = condition.awaitNanos(remainingNanos); }
return hasExited;
```

So `waitFor(0, …)` returns `false` from the loop guard **without** touching the interruptible park. That separates the
two interrupt arms cleanly:

- **Interrupted wait** — flag set + a real timeout (30 s) ⇒ `condition.awaitNanos` throws `InterruptedException` and
  *clears the flag* ⇒ the catch that destroys the child, joins the drainer and re-interrupts.
- **Interrupted join** — flag set + `Duration.ofNanos(1)` ⇒ the flag survives into the timeout branch, so
  `joinQuietly(...)`'s `drainer.join(5000)` is what throws. Needs the drainer still alive: `destroyForcibly()` reaps
  only the direct child, so use `sleep 5 & exec sleep 5` to keep a grandchild holding stderr.

Always clear the flag in a `finally` (`Thread.interrupted()`) — surefire reuses one JVM per module.

**Verified macOS facts** (probed with a throwaway `java Probe.java` before writing the tests — cheaper than a Maven
cycle):

| Expression | Result |
|---|---|
| `Files.size(dir)` | `96` |
| `Files.readAllBytes(dir)` | throws `IOException: Is a directory` |
| `Files.deleteIfExists(nonEmptyDir)` | throws `DirectoryNotEmptyException` |

**Two Mockito traps this slice:**

- **A checked exception the mocked method does not declare must come from an `Answer`, not `doThrow(...)`.**
  `ResponseTooLargeException extends IOException` while `GovernedTransportService.send` declares nothing, so `doThrow`
  is rejected at stub time. `doAnswer(invocation -> { throw failure; })` propagates it.
- **A shared helper must use `verify(mock, atLeastOnce())` + `captor.getValue()`.** A plain `verify(mock)` counts
  *every* matching invocation and fails on the helper's second use with `TooManyActualInvocations`. Related: two base
  URLs that normalise to the same endpoint (`…/v1///` and `…/`) cannot be told apart by a `verify` on the URL string —
  use a second host/port.

**Unreachable lines reported, not faked** (9 total): `OggOpusToWavConverter` 154 (`joinQuietly(null)` — `drainAsync`
never returns null), 165 (`deleteQuietly(null)` — only if `Files.createTempFile` throws, and `TempFileHelper` caches
`java.io.tmpdir` in a `static final`, so a test *cannot* make it throw), 208 (`startsWith`'s short-input guard — all
three callers pre-check the length); `OmlxAudioTranscriptionProvider` 124 and 129 (size re-checks already implied by
`declaredBytes`/`fileSize` and by `convertIfNeeded`'s own bound), 168 (`tempFilePath == null` — record-enforced
invariant), 226 and 234 (the `multipartFileName` null-guard and its `default` arm — the switch domain is *exactly* the
MIME set `isAudioMime` admits, and the only other value that can arrive is `audio/wav`);
`OmlxSpeechSynthesisProvider` 286 (a no-arg `isSupportedFormat()` overload with no caller).

#### `llm-providers` notes (2026-09-15, Phase 4 sixth pass — catalog, transport, remaining wrappers)

**Phase 4 is closed.** The last three clusters (153 lines) went to 10:

| Class | Before | After |
|---|---|---|
| `LiveProviderCatalogService` | 28 | 2 (both unreachable) |
| `StaticProviderCatalogAdapter` | 14 | 7 (classpath-gated) |
| `GovernedLangChainHttpClient` + `WatchdogInputStream` | 33 | 1 (unreachable) |
| `CodexResponsesChatModel` + `SseAccumulator` | 33 | 0 |
| `ClaudeCodeResponsesChatModel` + `ClaudeSseAccumulator` | 31 | 0 |
| `CodexTokenResolver` | 14 | 0 |

Module **91.57% → 97.88%** (missed 233 → 86). Reactor **80.55% → 80.80%** (48,330 → 48,479 / 59,997).
Module `clean verify` = 722 tests; the test files grew to `LiveProviderCatalogServiceTest` 20,
`GovernedLangChainHttpClientTest` 22, `ClaudeCodeResponsesChatModelTest` 19, `CodexResponsesChatModelTest` 11,
`CodexTokenResolverTest` 19, `ProviderOnboardingAdapterTest` 14.

**`HttpMethod` is a closed three-value enum — check the RESOLVED version.** `buildHttpRequest`'s
`else { throw new IllegalArgumentException("Unsupported HTTP method: …") }` cannot fire: in langchain4j **1.19.0**
`HttpMethod` is only GET/POST/DELETE and `HttpRequest.Builder.build()` rejects a null method
(`IllegalArgumentException: method cannot be null`). `~/.m2` held **five** `langchain4j-http-client` versions
(1.5.0, 1.5.3, 1.11.10, 1.17.2, 1.19.0); only the resolved one decides reachability. Settle it in seconds:

```bash
./mvnw -o -pl <module> dependency:tree -Dincludes='dev.langchain4j:langchain4j-http-client'
# -> dev.langchain4j:langchain4j-http-client:jar:1.19.0:compile
```

**Prove an unreachable guard by enumerating, don't assert it.** `LiveProviderCatalogService` 188/189 is a
`catch (Exception _)` around `URI.create(probeUrl).getHost()`. A throwaway `java Probe.java` replicated
`buildProbeUrl`'s exact switch and swept 145 curated base-URL × provider cases plus 52,948 exhaustive
tail × provider cases (tails ≤ 3 chars over `[]%#?/:@&=+$,;!*'()`, seven seeds) — **zero** counterexamples. Two
traps in that sweep:

- **Model the real transform.** My first probe reported counterexamples from suffixes `"models"` / `"api/tags"`
  that no arm of `buildProbeUrl` produces. Only the four real suffixes count.
- **The "strip one trailing slash first" step is not a separate transform.** For `X` ending in `/`,
  `strip(X) + "/models"` == `X + "models"`, so it collapses into the plain append case — except for
  `http:///` → `http://` and `http:/` → `http:`, which only matter if `cleanUrl` is used *alone* (it is not; it is
  always appended to).

**A class re-defined via `defineClass` is INVISIBLE to the JaCoCo agent.** This cost real time. `StaticProviderCatalogAdapter.load()`'s
failure arms (68/69 absent resource, 76/77 invalid entry, 81/82/83 unparseable JSON) were exercised by a 46-line
child-first classloader that served a caller-supplied `provider-catalog.json` and re-defined the adapter (plus its
`$RawProvider` and anonymous `TypeReference` nested classes) from the parent's own bytes. The `log.errorf`/`log.warnf`
output proved the arms ran — and the report still showed all 7 lines missed. Diagnose it cheaply:

```bash
rm -f <module>/target/jacoco.exec
./mvnw -o -pl <module> test -Dtest=TheOneClass -Dsurefire.failIfNoSpecifiedTests=false jacoco:report
# happy-path lines covered, the re-defined ones not  => the agent never saw the child class
```

Two sub-traps if you ever do this: the nested classes must be child-defined too (a nested class left to the parent
sits in a **different runtime package** and fails package-private access with `IllegalAccessError`), and the
instance must be returned as the *interface* type — the concrete class is a distinct `Class` object and cannot be
cast to the parent's copy (`ClassCastException`). The scaffolding was removed: 46 lines that cannot move the metric
are not worth keeping.

**The alternative — shadowing `target/test-classes/provider-catalog.json` — is a flakiness hazard.** It precedes
`target/classes` so it *does* shadow, but `process-resources` never deletes it, and its newer mtime means a
hard-killed run leaves a malformed catalog shadowing the whole module on every later `mvn test` until a `clean`.
Prefer reporting the gap.

**`mockStatic(Arc.class)` covers the `Arc.container()` fallback ladder** in both provider wrappers. Two tests, one
per arm:

```java
InstanceHandle<GovernedTransportService> handle = Mockito.mock(InstanceHandle.class);
Mockito.when(handle.isAvailable()).thenReturn(true);
Mockito.when(handle.get()).thenReturn(governedTransport);
ArcContainer container = Mockito.mock(ArcContainer.class);
Mockito.when(container.instance(GovernedTransportService.class)).thenReturn(handle);

try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
    arc.when(Arc::container).thenReturn(container);      // happy arm (lines 94-96 / 91-93)
    // arc.when(Arc::container).thenThrow(new NoClassDefFoundError("io/quarkus/arc/Arc"));  // catch arm
}
```

The *convenience* constructor (the one with no transport parameter) is what routes through CDI — use it, not the
transport-taking one, or the ladder is never entered.

**Count the `catch` clauses before planning the arms — the three classes differ.** Both `*ResponsesChatModel`
wrappers have a **single** `catch (Exception _)` in `resolveGovernedTransport`, so one test stubbing
`thenThrow(new IllegalStateException("no arc container"))` covers that arm (and a `NoClassDefFoundError` would *not*
be caught there — it is an `Error`, not an `Exception`). `GovernedLangChainHttpClient.resolveTransport` is the one
with **two** distinct arms: `catch (NoClassDefFoundError _)` (falls through to the "transport is mandatory" throw)
and `catch (Exception e)` (wraps in `IllegalStateException("Failed to resolve GovernedTransportService from CDI
container: …")`). Assert on the message to tell them apart.

**A literal-IP base URL never reaches the probe.** `LiveProviderCatalogService.discoverModels` rejects blocked
literal addresses before probing:

```
WARN  Custom base URL host is blocked: 127.0.0.1
```

So a test that wants the Ollama no-auth arm must pass a host **name** (`http://localhost:11434`). Symptom if you
forget: `expected: <1> but was: <0>` — the fallback is returned instead of the parsed models.

**Small traps that each cost a cycle:**

- **A blank header name is a live filtering path.** langchain4j accepts `headers(Map.of("", List.of("v")))` and
  preserves the blank key (`headers={=[v]}`), while `java.net.http.HttpRequest.Builder.header` would reject it — so
  `isRestrictedHeader`'s `name.isBlank()` arm is reachable. A *null* key is rejected by langchain4j itself
  (`IllegalArgumentException: Header name cannot be null`), so only the blank half of that disjunct is live.
- **`doAnswer(inv -> { throw checked; })` for `GovernedLangChainHttpClient.execute`'s wrapped arm (line 86).**
  The method distinguishes a checked transport failure (wrapped in `RuntimeException`) from an unchecked one
  (rethrown as-is); `send` declares nothing, so the checked failure must come from an `Answer`.
- **`ToolExecutionResultMessage` produces a *list* content**, so two consecutive tool results merge into one
  user-role entry — and a preceding `UserMessage` merges with them too. Assert the merged count, not the input count.
- **`Instance<T>` ladders want two three-line helpers**: `resolvable(v)` stubbing **both** `isResolvable()` and
  `get()`, and `unresolvable()` stubbing only `isResolvable()`.
- **A `@BeforeEach` that clears process-wide static state** (`setDefaultTransport(null)`) makes a class
  order-independent when one test writes it. Cheap insurance.
- **When `mvn test` fails, `jacoco:report` in the same invocation never runs** — the XML/CSV you then read is from
  the *previous* build and shows nearly every line missed. Fix the test first; do not chase the phantom.

#### `security` / `sandbox` notes (2026-09-15, Phase 2 third pass)

Both remaining Phase 2 targets are **boundary** classes, and both had resisted earlier passes for the same reason:
they cannot be reached with a mock. Each needed a *real* boundary plus one surgical seam. If a class builds its own
collaborator (`new HttpClient(...)`) or wraps `ProcessBuilder`, stop looking for a mock and ask what real object you
can stand up instead.

- **A boundary that constructs its own client has no injection seam — stand up a real server.**
  `GovernedMediaDownloadService.downloadHttpWithRedirects` does `HttpClient.newBuilder()...build()` internally, so no
  mock can intercept the HTTP hop. Use a real `com.sun.net.httpserver.HttpServer` on `127.0.0.1` and give it one
  context per response shape (`/image.png`, `/missing.png`, `/wrong-magic.png`, `/redirect-same-origin`,
  `/redirect-no-location`, `/redirect-denied`, `/redirect-hitl`, `/redirect-cross-origin`, `/hang`). Helpers
  `respond(exchange, status, contentType, bytes)` / `redirect(exchange, location)` keep it to a few lines per context.
- **Give the test server a DAEMON-thread executor.** `testServer.setExecutor(Executors.newCachedThreadPool(r -> {Thread t = new Thread(r, "…"); t.setDaemon(true); return t;}))`.
  A deliberately hanging handler (`sleepQuietly(1_500L)`) otherwise parks a non-daemon pool thread and can stall the
  dispatcher — or the JVM — for every test that follows.
- **`MediaSecurityValidator.validateTarget` rejects loopback BY DESIGN**, so a real local server cannot pass the media
  SSRF gate (unlike `GovernedHttpClient`, which permits localhost for dev — that is why `GovernedHttpClientTest` and
  `GovernedTransportServiceTest` can use a loopback fixture directly and the media test cannot). Do **not** weaken the
  validator. Open a *selective* static mock that keeps every real method and overrides exactly one:
  ```java
  MockedStatic<MediaSecurityValidator> gate =
          Mockito.mockStatic(MediaSecurityValidator.class, Answers.CALLS_REAL_METHODS);
  gate.when(() -> MediaSecurityValidator.validateTarget(any(URI.class), any(Duration.class)))
          .thenAnswer(invocation -> null);   // this call must return null, not "true"
  ```
  `CALLS_REAL_METHODS` is what makes this honest: `sameOrigin`, `validateRedirectReference`, `validateMimeType` and
  `validateMagicBytes` all stay live, so redirect/HITL/magic-byte assertions still mean something. Hold the handle in a
  field and close it in `@AfterEach`; `MockedStatic` is not thread-safe across tests.
- **Mockito static mocks are THREAD-LOCAL, and the symptom is misleading.** A gate opened on the test thread is
  invisible to a worker thread. The interrupt test drove `service.download(...)` from a new `Thread`, so the *real*
  `validateTarget` ran and rejected `127.0.0.1` — the assertion failed with `expected: <IO> but was: <DNS_REBINDING>`,
  which points at DNS, not at threading. Extract the gate as a static factory and have the worker open its own inside
  try-with-resources:
  ```java
  Thread worker = new Thread(() -> {
      try (MockedStatic<MediaSecurityValidator> gate = openLoopbackGate()) {   // per-thread!
          service.download(attachment, "user-1", true);
      } catch (Throwable t) { failure.set(t); }
  }, "media-download-interrupt-test");
  ```
  Any test that drives the SUT off the test thread needs its own static mock for **every** statically-mocked class.
- **`ProcessBuilder`/`Process` cannot be faithfully mocked — write a fake executable.** Put a POSIX script in a
  `@TempDir`, `assertTrue(exe.toFile().setExecutable(true))`, and pass its `Path` where the code expects the binary
  (production only calls `.toString()`). Real `start` / drain / `waitFor` / exit code / forced destroy then run end to
  end. A `case "$1" in` dispatcher covers `info` / `image` / `run` / `stop` in one fixture:
  ```java
  private Path fakePodman(String body) throws IOException {
      Path exe = tempDir.resolve("podman-" + UUID.randomUUID() + ".sh");
      Files.writeString(exe, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
      assertTrue(exe.toFile().setExecutable(true), "fake podman must be executable");
      return exe;
  }
  ```
  Use a **quoted heredoc** (`cat <<'DELIM' … DELIM`) when emitting a payload verbatim. `@TempDir` is the clean
  injection point — never `System.getProperty("java.io.tmpdir")`.
- **`Duration.ZERO` / `timeoutSeconds = 0` reaches a timeout arm with no sleep.** `Duration.ZERO.toSeconds()` is `0`,
  so `waitFor(0, TimeUnit.SECONDS)` returns `false` immediately and the executor force-destroys. Pair it with a
  `sleep 30` script so the process genuinely outlives the wait, and assert the timeout message. No wall-clock cost.
- **A backgrounded grandchild is the deterministic way to keep a drain thread alive.**
  `sh -c "sleep 8 & exit 0"` — the shell exits immediately but the grandchild inherits the stdout pipe, so the reader
  thread is still alive when the executor checks `reader.isAlive()` and takes the reap arm. Without this the arm is a
  race you cannot win reliably.
- **A stream that returns one byte forever spins a deadline loop on purpose.** `oneByteForever()` never returns `-1`,
  so `readWithDeadline` loops until the wall clock passes the deadline and takes the `remainingNanos <= 0` pre-check
  that looks unreachable by race reasoning. Sibling fixture `failingStream(Throwable)` (throws on first read) covers
  the `IOException` / `RuntimeException` / `Error` arms — for `Error` there is no wrapping, so `assertSame(boom, ex)`.
- **`System.setProperty("os.name", "Windows 11")` with a `finally` restore reaches Windows-only fixups on macOS.**
  Surefire reuses ONE JVM per module, so a leaked override silently corrupts every later test in the class. Wrap it:
  ```java
  private static void withOsName(String osName, ThrowingRunnable body) throws Exception {
      String original = System.getProperty("os.name");
      try { System.setProperty("os.name", osName); body.run(); }
      finally { if (original == null) System.clearProperty("os.name");
                else System.setProperty("os.name", original); }
  }
  ```
  Same discipline as `user.home` in the `llm-providers` notes. Note `System.getenv` is **not** settable — guard those
  assertions with `Assumptions.assumeTrue(precondition, "reason")` so they report as **skipped**, never a silent `if`.
- **`jacoco_lines.py` bails out when a source file is shared by two classes.** It prints
  `# NOTE: source file shared by 2 classes: [...]` and omits the numbers — triggered by a nested record such as
  `DirectExecutor$ProcessOutput` or `NamespaceExecutor$ProcessOutput`. Fall back to parsing
  `<module>/target/site/jacoco/jacoco.xml`: iterate `<sourcefile>` elements and take lines with `mi>0 && ci==0`. That
  gave the exact residues (32 and 24) the helper refused to print.
- **`mvn test` does NOT refresh `jacoco.csv`.** After editing tests, `jacoco:report` must be re-run or the CSV stays
  stale and the reading looks unchanged — this produced a false "no progress" reading (still 89 missed) on the very
  first media win. Always chain `test jacoco:report`, or re-run `jacoco:report` afterwards, before quoting a number.
- **Dead / unreachable lines found in this pass (reported, not faked).**
  `GovernedMediaDownloadService` 111 (guard unreachable — both callers guarantee a positive budget), 214–215
  (`total > limit` overflow guard unreachable because `Math.min(limitBytes, maximumBytes)` caps every item), 436 and
  506 (defensive `catch (IOException)` around a close that cannot throw). `PodmanHealthCheck` 49 / 55–59 / 63 / 81 /
  99 and `PodmanImageHealthCheck` 61 / 67–71 / 75 / 93 / 111 — all behind an unconditional early return when
  `sandboxEnabled` is false. `DirectExecutor` 128–130 (Windows `python3` shim; needs a host without `python3`),
  `DirectExecutor` 107–108 and `NamespaceExecutor` 120–121 (`catch (InterruptedException)` around
  `reader.join(1_000)`), `ProcessIsolationExecutor` 203 / 213 (`recordFailure` behind an already-returned fallback
  flag). Never reflect into a field or mock a type just to make a dead line green.
- **A health check that returns early on a config flag makes every later arm dead.** `PodmanHealthCheck` /
  `PodmanImageHealthCheck` both short-circuit when `sandboxEnabled` is false, so their timeout, truncation and
  error-message arms are only reachable by enabling the flag *and* driving a fake binary — the fake-executable recipe
  is what makes those reachable at all. Check for this shape before declaring a class untestable.
- **Sweep sibling classes after the nominal targets close.** The two headline targets closed 162 lines, but a fresh
  per-class reading found five more `sandbox` classes sharing the just-built recipe and closed **131 more**
  (`ProcessIsolationExecutor` 45→2, `DirectExecutor` 32→5, `PodmanHealthCheck` 29→9, `PodmanImageHealthCheck` 28→9,
  `NamespaceExecutor` 24→2). Module movement: `sandbox` **65.04% → 91.39%** (worst module in the reactor, now one of
  the best), `security` **84.03% → 89.37%**.

### 1. Get the per-class numbers, not the package numbers

Parse `**/target/site/jacoco/jacoco.csv` (one CSV per module, written by
`jacoco:report`). **Use the CSV, not the XML** — the gate
(`verify_jacoco_coverage.py`) reads `LINE_MISSED`/`LINE_COVERED` from the CSV, and
the XML report disagrees with it (59,923 vs 59,997 total lines on the 2026-09-14
run, because the XML nests classes differently). Only the CSV reproduces the
gate's own number, so quoting an XML-derived figure will not match CI.

```python
import csv, glob
lm = lc = bm = bc = 0
for f in glob.glob('**/target/site/jacoco/jacoco.csv', recursive=True):
    for row in csv.DictReader(open(f, newline='', encoding='utf-8')):
        lm += int(row['LINE_MISSED']);  lc += int(row['LINE_COVERED'])
        bm += int(row['BRANCH_MISSED']); bc += int(row['BRANCH_COVERED'])
print(f"LINE {100*lc/(lm+lc):.2f}% ({lc:,}/{lm+lc:,})   BRANCH {100*bc/(bm+bc):.2f}%")
```

To rank *targets*, sort per-class rows by `LINE_MISSED` descending — that is the
metric the gate counts. Inner classes appear as their own rows (`Foo$1`); ignore
the one-line lambdas.

To get **line numbers** rather than counts (which is what you actually need before
writing tests), read the module's `jacoco.xml`. Watch the schema: `<class>` carries
`sourcefilename` as an **attribute** and holds only `<method>`/`<counter>` children —
the `<line>` elements live under `<sourcefile>`, and one source file is shared by all
of its nested classes. So resolve class → `sourcefilename` → the sibling `<sourcefile>`:

```python
for pkg in root.iter("package"):
    for cls in pkg.iter("class"):
        if cls.get("name", "").replace("/", ".") != fqcn:
            continue
        src = cls.get("sourcefilename")          # attribute, not a child element
        for sf in pkg.iter("sourcefile"):
            if sf.get("name") == src:
                missed = [int(l.get("nr")) for l in sf.iter("line")
                          if int(l.get("mi")) > 0 and int(l.get("ci")) == 0]
```

A line counts as *missed* only when `mi > 0 and ci == 0`; a line with any covered
instruction is not reported, so "missed" undercounts partially-executed lines. That
is fine for targeting — it tells you which paths never ran at all.

The snippet above is the manual form. The **maintained tool** `scripts/jacoco_lines.py`
(canonical logic in `scripts/quality/jacoco_lines.py`, with a `scripts/jacoco_lines.py`
shim that forwards to it) does exactly this and is importable (`find_class_lines` /
`find_class_lines_in_root`). Use it instead of pasting the loop:

```bash
# explicit module XML
python3 scripts/jacoco_lines.py core/target/site/jacoco/jacoco.xml com.amsha.core.agent.loop.BrowserPreflightHandler
# or auto-discover the owning module across the whole reactor
python3 scripts/jacoco_lines.py --root . com.amsha.core.agent.loop.BrowserPreflightHandler
```

And `analyze_jacoco_coverage.py` grew a `--lines` flag (also implied by `--json`) that
attaches those missed line numbers to every gap it prints, so the gap report is directly
actionable — you see *which* lines to target, not just how many:

```bash
python3 ./scripts/analyze_jacoco_coverage.py --lines --top 10
python3 ./scripts/analyze_jacoco_coverage.py --json --top 10   # each gap carries missed_line_numbers + source_file
```

### 2. ALWAYS check for an existing test file first

This bites every time. `ToolRegistryTest`, `ToolRegistryUnitTest`,
`ToolRegistryDeadCatchTest`, `CronFailureCleanupTest`, `LoopResultHandlerTest`,
`ParallelToolStepExecutorTest`, `DelegatedCompletionHandoffTest` and
`BrowserPreflightHandlerTest` all already existed. Find them before writing:

```bash
find . -name "<ClassName>*Test*.java" -not -path "*/target/*"
```

If one exists, **extend it** with only the genuinely-uncovered paths. Creating a
second file for the same class either collides on the name or duplicates coverage.
A `Write` to an existing path is rejected until you `Read` it first — treat that
rejection as the signal to go read what is already there.

### 3. Prefer plain JUnit + mocks over `@QuarkusTest`

Most `core.agent.loop` / `core.tools` / `core.lifecycle` classes are unit-testable
because their collaborators are injected through **package-private fields** or a
constructor, and the dependencies are ports (interfaces):

```java
registry.agentStore = agentStore;   // package-private @Inject field
```

A test in the *same package* can set those fields directly. `@QuarkusTest` is only
needed when CDI wiring or a real database is genuinely under test.

**But "the class is unit-testable" is a per-dependency question, not a per-class one.**
A class can have one dependency that degrades gracefully without a container and
another that does not. `llm-providers` is the worked example: the factories resolve
their `GovernedTransportService` through `SpecializedChatModelFactory.resolveGovernedTransport()`,
which swallows a missing container and returns `null` — so an unmanaged `new` factory
runs fine. But the *builder* those factories call,
`ChatModelFactory.openAiBuilder(...).build()`, constructs a `QuarkusOpenAiClient`, which
resolves `io.quarkiverse.langchain4j.auth.ModelAuthProvider`, which calls
`CDI.current()` and throws:

```
java.lang.IllegalStateException: Unable to locate CDIProvider
  at jakarta.enterprise.inject.spi.CDI.findAllProviders(CDI.java:137)
  at io.quarkiverse.langchain4j.auth.ModelAuthProvider.resolve(ModelAuthProvider.java:71)
  at io.quarkiverse.langchain4j.openai.common.QuarkusOpenAiClient.<init>(QuarkusOpenAiClient.java:98)
  at dev.langchain4j.model.openai.OpenAiChatModel.<init>(OpenAiChatModel.java:83)
```

So supplying credentials in a plain unit test does **not** reach the construction body —
it just moves the failure one frame later. Two parameterised "supply credentials and
assert `create()` is non-null" tests produced **30 identical errors** this way. The fix
is to split by reachability (plain test for the no-container surface, `@QuarkusTest` for
the build path), **not** to downgrade the assertion to `assertThrows` or a
swallow-the-failure try/catch — that would pass while covering nothing.

When you do need a container for a whole family of near-identical classes, note that a
21-way parameterised `@QuarkusTest` costs **one** container boot, not 21: construct each
class with `new`, set its fields reflectively, and only rely on the container being
*running* for the `CDI.current()` call inside the third-party builder. Inject the real
config bean (`@Inject AgentLlmConfig agentConfig`) rather than mocking it, so the
production defaults are exercised.

### 4. Traps that cost real time

- **Mockito 5.7.0 has NO `Iterator` case in `ReturnsEmptyValues`.** It maps the
  `Iterable` return *type* to an empty collection, but `iterator()`'s declared type
  is `Iterator`, which is unhandled — so an unstubbed `iterator()` on a mock returns
  **null**, not an empty iterator. A for-each loop over a mocked CDI `Instance<T>`
  therefore dies with `NPE: Cannot invoke "java.util.Iterator.hasNext()" because
  "<local6>" is null`. `Instance<T>` consumers use **two different access styles**
  and both must be stubbed:

  ```java
  doAnswer(i -> { ((Consumer<X>) i.getArgument(0)).accept(x); return null; })
          .when(instance).forEach(any());          // listTools()/listToolDescriptors()
  doAnswer(i -> List.of(x).iterator())
          .when(instance).iterator();              // enhanced for-loop in invokeTool()
  ```

  Confirm library behaviour with `javap -c` on the class from the jar rather than
  reasoning about it:

  ```bash
  unzip -p ~/.m2/repository/org/mockito/mockito-core/5.7.0/mockito-core-5.7.0.jar \
    org/mockito/internal/stubbing/defaultanswers/ReturnsEmptyValues.class > /tmp/R.class
  javap -p -c /tmp/R.class | grep -i iterator
  ```

- **`thenReturn(Stream.of(x))` is a one-shot trap when the stub is hit more than once.**
  `Instance.stream()` is called *once per tool call* by `ParallelToolStepExecutor.executorFor`, and
  `thenReturn` hands back the **same** `Stream` instance every time. The second lookup gets an
  already-consumed stream and dies with
  `IllegalStateException: stream has already been operated upon or closed`. Use
  `thenAnswer(invocation -> Stream.of(x))` so each call gets a fresh stream. The same shape applies
  to any stub returning a single-use object (`Stream`, `Iterator`, a `Reader`). Symptom to recognise:
  a single-call test passes and a two-call test fails on the same helper.

- **Do not verify an exact call count without reading the production path.**
  `LoopResultHandler.createExecutionContext()` resolves `skillHome` *and*
  `browserPreview`, and each does its own `findByName(...)` — so the mock sees
  **two** calls, not one.

- **Stub everything the assertion depends on, including `.name()`.** `Map.of()`
  (immutable) throws `NPE` on a **null key** via `Objects.requireNonNull`, whereas a
  `HashMap` would return the default. A mock passed as a lookup key must have
  `name()` stubbed.

- **Methods returning `Object`** (e.g. `ToolSchemaProvider.elementToMap`) need a
  local cast helper in the test; a direct `Map<String,Object> m = ...` will not
  compile. Prefer a single whole-map `assertEquals` instead.

- **Never issue two `Edit` calls against the same file in one message.** Both are
  applied against the same starting snapshot, so one silently clobbers the other —
  each reports success and the file keeps only one of the two changes. This cost a
  full compile-and-test cycle. Batch edits to *different* files in one message;
  sequence edits to the *same* file one per message.

- **`ObjectMapper.readValue(x, TypeReference)` is ambiguous under inference.** If the
  first argument's type is not pinned, javac cannot choose between
  `readValue(InputStream, TypeReference)` and `readValue(byte[], TypeReference)` and
  reports `reference to readValue is ambiguous`. Assign to a local first:

  ```java
  when(mapper.readValue(anyString(), any(TypeReference.class))).thenAnswer(inv -> {
      String json = inv.getArgument(0);                      // pins the overload
      return real.readValue(json, (TypeReference<?>) inv.getArgument(1));
  });
  ```

  That pattern (a mocked `ObjectMapper` delegating to a real one, throwing only for a
  chosen argument type) is the clean way to force a serialization failure without
  inventing an unserializable value.

- **Read the collaborator's declared parameter types before typing test locals.**
  `ToolStepEscalationHandler.attemptEscalationAfterHealFailure` and
  `attemptGenericEscalation` take `ToolResponse.ToolFailure`, **not** `ToolResponse`.
  A `ToolResponse failure = ToolResponse.failure(1, "x")` local makes `eq(failure)`
  fail to compile with "inference variable T has incompatible bounds", and
  `ArgumentCaptor.forClass(ToolResponse.class)` cannot capture into a `ToolFailure`
  parameter. Declare both the local and the captor as `ToolFailure`.

- **`Map.of().containsKey(null)` throws NPE.** `Map.of()` is immutable and its
  `containsKey` calls `Objects.requireNonNull` on the key, unlike a `HashMap`. So a
  production `preExecuted.containsKey(call.callId())` crashes whenever `callId` is
  null — while the surrounding code (`BatchDecisionUtil.decisionKey`,
  `ParallelToolStepExecutor.resultKey`) deliberately tolerates a null call ID. When a
  new test NPEs inside `ImmutableCollections$MapN.containsKey`, that is a real defect:
  **report it**, do not "fix" it by weakening the test, and do not pin the buggy
  behaviour with `assertThrows` (that makes the eventual fix fail the build).

- **`MockitoExtension` defaults to strict stubs** — an unused `when(...)` fails the
  test. Plain `mock()` calls in a `@BeforeEach` avoid this entirely, which is why
  most of the existing test files here use that style. Two consequences bite
  specifically in a `@BeforeEach`:

  - **Unnecessary stubbings are reported per test *method*, not per class.** A
    `@BeforeEach` stub is fine only if *every* test method uses it. Adding one test
    that returns before touching a collaborator (e.g. an early empty-result gate)
    makes a long-standing `@BeforeEach` stub fail that one test with
    `UnnecessaryStubbing`. Prefer a named helper (`registerEnabledTool(name)`) called
    from the tests that need it over a shared `@BeforeEach` stub.
  - **A stubbed lookup method invoked with a *different* key raises
    `PotentialStubbingProblem`.** With `when(registry.findTool("browser_navigate"))` in
    place, any other `findTool("github")` the class under test performs is an argument
    mismatch — and Mockito reports it *early*, before the stubbing has been used. For a
    registry/lookup collaborator, register a lenient catch-all default in `@BeforeEach`
    and let the specific stubs (declared later, and therefore matched first) override it:

    ```java
    lenient().when(toolRegistry.findTool(anyString())).thenReturn(Optional.empty());
    ```

    That is also production's fail-closed semantics for an unknown name, so it documents
    the contract rather than papering over it.

- **`javap` produces no output until `JAVA_HOME`/`PATH` are exported** (same
  root cause as `./mvnw` failing).

### 5. Verify in this order

```bash
# scoped, fast
./mvnw -o -pl core -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='FooTest,BarTest' < /dev/null
# then measure + prove nothing else regressed
./mvnw -o -pl core -am test jacoco:report < /dev/null
# finally the real gate
./mvnw --batch-mode clean verify < /dev/null
```

Filter the output on the **result** lines, not the warning lines — a grep for
`Tests run|BUILD` is required, because `ERROR].*java:\[` matches PMD/compiler
warnings from unrelated files and looks like a failure.

**Run `spotless:apply` LAST — immediately before `verify`.** `spotless:check` is
bound into `verify`, so any code written *after* the apply step fails the build on
formatting alone. This bit twice in one session. The symptom is easy to misread:

```
[ERROR] Failed to execute goal ...spotless-maven-plugin...:check ... BUILD FAILURE
[ERROR]     src/test/java/.../FooTest.java
[ERROR]         @@ -213,8 +213,7 @@   (a whitespace-only diff)
```

All tests pass; only the diff is wrong. Re-run `spotless:apply` and re-verify — do
not go hunting for a test bug.



- **Never run `git add`, `git reset`, `git restore --staged`, or `git commit`.**
  Staging and commit decisions belong to the human (AGENTS.md, Git staging and
  review hygiene). Report findings and let the user commit.
- **Sonar is environment-blocked here.** SonarQube tasks are `.ps1` scripts needing a
  container + token. Do NOT claim the Sonar gate passed. Report it explicitly as
  "environment-blocked — run `mise run sonar:issues` on a host with the
  container/token" and exclude it from any "all green" verdict. (Note: `mise`
  *is* installed, at `/opt/homebrew/bin/mise` — it is simply not on the
  non-interactive PATH, so call it by absolute path.)
- Do not use JDK preview features (no structured concurrency, stream gatherers,
  module imports, compact source files, value classes) — relevant if writing new
  code during a fix pass.

#### `app` notes (2026-09-15, Phase 5 first slice — config validators, DB-console helpers, identity governance)

The last big mass in the plan: **2,306 missed lines across 102 of 188 classes** — about nine times the whole
`persistence-postgres` module — so `app` has to be worked in slices. This slice closed 153 lines (**69.17% → 71.21%**,
+2.04 pp) with six new plain-JUnit test files and 106 tests; the module suite is 933 tests / 3m40s.

**`app` is mostly testable without a CDI container — triage before you assume otherwise.** The module looks
`@QuarkusTest`-heavy, but a large share of its remaining gaps are `final` static utilities and package-private
helpers that take their collaborators as *parameters* or as `Instance<T>` fields. Those need no container at all.
Read the class before deciding it is unreachable:

| Shape | Example | How to drive it |
|---|---|---|
| `final` class, private ctor, all-static | `LocalModelValidator`, `DbResultMapper`, `DbQueryAstScanner` | call the static directly |
| `@ApplicationScoped` but with a directly-invocable `@Observes` method | `BackendValidator` | `new StartupEvent()` |
| package-private final class, static methods taking `Instance<T>` | `DbConsoleTenantResolver` | mock the `Instance` |
| JAX-RS `@Provider` filter | `ActiveWorkspaceFilter` | mock `ContainerRequestContext` |

**`Instance<T>` parameters are the cheapest seam in this module.** Stub both halves explicitly — `isResolvable()`
*and* `get()` — and build an `unresolvable()` twin, because every one of these helpers guards on `isResolvable()` and
you need both arms:

```java
private static <T> Instance<T> resolvable(T value) {
    @SuppressWarnings("unchecked") Instance<T> i = mock(Instance.class);
    when(i.isResolvable()).thenReturn(true);
    when(i.get()).thenReturn(value);
    return i;
}
```

**`System.getProperty` is settable, `System.getenv` is not — and that decides testability.** Every input
`LocalModelValidator` reads is `System.getProperty(key, System.getenv(key))`, and all four relevant env vars are unset
in this environment, so the properties are the single source of truth. The same trick unlocks the OS branches:
**`os.name` is itself a property**, so the macOS / Windows / Linux arms of a platform-specific message are all
reachable from one host — *save and restore* the original value in `@AfterEach` (never `clearProperty`, which would
corrupt the JVM for later tests). And when a class prints to `System.err`, swap it for a `ByteArrayOutputStream`
`PrintStream` in `@BeforeEach` and flush before reading; there is no parallel test execution configured in this repo,
so that is safe.

**The headline finding is an authorisation hole, not a coverage number.** In
`IdentityResourceHelper.validateInvitationRole` the third guard needs `!"OWNER".equals(callerRole)` **and** a role
outside `{ADMIN, MEMBER}`; `isValidRole` already narrowed the role to `{OWNER, ADMIN, MEMBER}`, so that means `OWNER`
— which the second guard has already handled for every non-owner caller. **The guard can never fire and a `MEMBER`
can invite an `ADMIN`.** This is the "guard over a closed set that an earlier guard already exhausted" shape. The
right move is to pin the *actual* behaviour with a test that names the finding, and to escalate it for a human
decision — not to quietly "fix" production code inside a coverage task.

**How to prove an override is unreachable, rather than merely untested.** `DbQueryAstScanner.visit(ParenthesedSelect)`
is exercised by every nested-subquery test yet never runs. The proof is two `javap` calls:

```bash
JAR=~/.m2/repository/com/github/jsqlparser/jsqlparser/4.9/jsqlparser-4.9.jar
javap -cp "$JAR" net.sf.jsqlparser.statement.select.ParenthesedSelect | grep accept
javap -c -cp "$JAR" net.sf.jsqlparser.statement.select.Select \
  | grep -A 6 "accept(net.sf.jsqlparser.expression.ExpressionVisitor)"
# -> invokeinterface ExpressionVisitor.visit:(…select/Select;)V
```

`Select` implements `Expression` and supplies a concrete `accept(ExpressionVisitor)` whose bytecode hardcodes
`visit(Select)`; `ParenthesedSelect extends Select` without overriding it, so static overload resolution — compiled
inside `Select`, where `this` is `Select` — always picks `visit(Select)`. Behaviour is still correct via
`visitSelect`'s own `ParenthesedSelect` branch, so this is redundancy, not a bug. **Report it; do not reflect into it.**

**Two more dead-arm shapes worth recognising on sight:**

- **A JDK-guaranteed algorithm makes its catch dead.** `DbResultMapper.hashQuery` catches `NoSuchAlgorithmException`
  around `MessageDigest.getInstance("SHA-256")`, which the JCA spec guarantees on every conformant JDK — the same
  "non-injected stock dependency" shape as the `toJson` catches in Phase 3b.
- **A guard on a constructor-enforced DTO invariant is unreachable.** `DbConsoleTenantResolver` tests
  `m.workspaceId() != null && !m.workspaceId().isBlank()`, but `WorkspaceMembershipDto`'s compact constructor rejects
  both. The *line* still reports green because the first operand is evaluated — the usual "green line ≠ working guard"
  tell. Pin the DTO invariant with a test that asserts the constructor throws.

**Two mechanical traps that cost real cycles here:**

- **`assertTrue(cond, someList)` does not compile** — JUnit's second argument is a `String` message. Passing the
  collected names directly fails with a wall of "no suitable method found" overload listings. Add a
  `private static String show(List<String> n) { return n.toString(); }` helper rather than string-concatenating inline.
- **A fixed `Instant` in the past silently disables a rotation test.** `rotateActiveCredentials` compares against
  `Instant.now()`, so an expiry built from a hardcoded `2026-01-01` instant reads as *expired* and the credential is
  skipped — the test then asserts `0` revoked and looks like a production bug. Build expiries from a local
  `Instant realNow = Instant.now()`.

**Residual 9 lines, all four dead regions above — zero reachable gaps.** `DbQueryAstScanner` 68/69/71 (unreachable
override) and 113 (`scanBody`'s null guard, whose only caller is inside an `instanceof PlainSelect` branch);
`DbResultMapper` 85/86 (dead catch); `IdentityResourceHelper` 66/67/69 (the dead escalation-guard body).

**Where the remaining `app` mass is** (measured, after this slice): `ChatCommand` 195, `HitlService` 127,
`OidcIdentityProvider` 119, `ModelInstaller` 109, `CronJobRunner` 88, `DbConsoleResource` 75,
`McpConnectResource` 75, `CronResultHandler` 74, `AgentCronScheduler` 70, `PlaywrightCliInstaller` 70,
`Vec0NativeLibraryInstaller` 59, `AuthResource` 56, `GithubIdentityProvider` 50, `BootstrapOrchestrator` 46,
`AmshaCli` 45, `EnvConfigInitializer` 43, `McpImportResource` 42 (fully dark), `IdentityResource` 40.
**`PlaywrightCliInstaller` has no seam** — the command names are hardcoded and its install path would run a real
`npm install -g`; leave it unless you first add a seam (which is a production change, out of scope here).

#### `app` notes (2026-09-15, Phase 5 second slice — the model installer, and a hermeticity trap)

`ModelInstaller` **109 → 14 missed / 162 covered (92.05%)**; module **2,153 → 2,057 missed, 71.21% → 72.50%
(+1.29 pp), 96 lines closed**; `ModelInstallerTest` 33 → **35 tests**; module suite **968 / 0 / 0 / 3 skipped**.

**⚠️ THE BIG ONE — `System.getenv` before `System.getProperty` makes property-setting tests silently inert.**
`ModelInstaller.resolveModelRoot()` reads the ENVIRONMENT first and the property second, while its very next
lookup (`AMSHA_DATA_DIR`) is property-first — opposite precedence inside one method. So an exported
`AMSHA_ONNX_MODEL_ROOT` makes the property unreachable, resolution falls through to the repo's real
`data/models`, and every banner assertion inverts because the bundles genuinely live there. **A 9-failure "red
build" reported from a full-module run was reproduced byte-for-byte this way:**

```bash
AMSHA_ONNX_MODEL_ROOT="$PWD/data/models" AMSHA_STT_FFMPEG_PATH=/opt/homebrew/bin/ffmpeg \
  ./mvnw -o -pl app test -Dtest='ModelInstallerTest,LocalModelValidatorTest'
# → Tests run: 52, Failures: 9 — identical names, lines and expected/actual to the report
```

**Before believing any "the full suite fails but the class passes alone" report, check for an env-var override
of a setting the production code reads with `System.getenv`.** Grep the poms for `<environmentVariables>`
(absent here) and the dev scripts for `export AMSHA_` (they use per-command `VAR=x cmd`, which does NOT leak —
the leak comes from the developer's own shell). Do NOT "fix" the precedence: that is production behaviour, and
`LocalModelValidator` reads its own inputs property-first, so the inconsistency is a finding to report.

**Recipe — make such a test hermetic without skipping the whole class.** Guard the property-driven cases and
add a twin that runs only when the variable is present, so the precedence is pinned either way:

```java
private static final String MODEL_ROOT_ENV = "AMSHA_ONNX_MODEL_ROOT";

@Test
@DisabledIfEnvironmentVariable(named = MODEL_ROOT_ENV, matches = ".+")   // property wins on a clean machine
void modelRootUsesTheModelRootProperty() { … }

@Test
@EnabledIfEnvironmentVariable(named = MODEL_ROOT_ENV, matches = ".+")    // env wins where it is exported
void environmentVariableOutranksTheModelRootProperty() {
    Path fromProperty = modelRoot.resolve("ignored");
    System.setProperty(MODEL_ROOT_PROPERTY, fromProperty.toString());
    assertNotEquals(fromProperty.toAbsolutePath().normalize(), ModelInstaller.resolveModelRoot());
}
```

Use `matches = ".+"` (not `".*"`), or a variable set to the empty string counts as present. Result: clean env
→ 0 failures / 1 skip; env exported → **0 failures / 13 skips instead of 9 failures**.

**Snapshot and restore properties — never `clearProperty` them in `@AfterEach`.** A bare clear erases a value
another class deliberately installed. Capture in `@BeforeEach`, then `value == null ? clearProperty : setProperty`.
This is the same class of cross-class clobber as the env trap and costs three lines.

**Recipe — test a download's integrity path against a real server, not a mocked client.** A local
`com.sun.net.httpserver.HttpServer` on port 0 exercises exactly what a mock skips: the 64 KB streaming loop, the
running digest, the `.partial` file and the atomic publish.

```java
server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
server.createContext("/", exchange -> { /* switch on path: ok / wrong-size / wrong-hash / 404 / slow */ });
server.start();
baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
// then a synthetic entry: new ModelFileSpec(FILE_NAME, baseUrl + remotePath, bytes.length, sha256Hex(bytes))
```

Two traps: the `entryFor` helper must prefix `baseUrl` or every request dies with
`IllegalArgumentException: URI with undefined scheme`; and the real catalog points at hundreds of megabytes of
Hugging Face artifacts, so build a tiny synthetic entry whose bytes you control.

**Recipe — reach an `InterruptedException` arm without a second thread.** Pre-setting the calling thread's
interrupt flag makes `HttpClient.send` raise `InterruptedException` instead of blocking, and a `/slow.bin`
endpoint (3 s delay) guarantees the request is genuinely in flight so the race cannot go the other way. Clear the
flag in a `finally` (`Thread.interrupted()`) or it leaks into the next test. A mocked client never throws this, and
the streaming read loop surfaces interrupts as `IOException` — which is why lines 457–460 sat dark.

**Residual 14 lines in `ModelInstaller`, all unreachable or dead, none faked:** 268 and 278–284 are the
`Path.of("..").resolve(<absolute>)` parent-candidate fallbacks — for an absolute setting `resolve` returns that
same path, so the parent test is false whenever the direct test was; 289 and 295 are the `data/models` defaults
(`app/data/models` does not exist from the module CWD, and `<repo-root>/data/models` does, so 292–294 always wins);
316–317 is the dead `catch (IOException _)` around `Files.size` (**`Files.size` on a directory does not throw** —
use `PosixFilePermissions.fromString("---------")` to make an unreadable *file* instead, whose size still reads);
482–483 is the JCA-guaranteed `NoSuchAlgorithmException` catch. **268 and 278–284 are not dead** — they serve a
distribution layout where the process runs from a parent of the data directory with a relative setting; a unit test
cannot reach them without relocating the JVM's working directory.

**Where the remaining `app` mass is** (measured, after this slice): `ChatCommand` 195, `HitlService` 127,
`OidcIdentityProvider` 119, `CronJobRunner` 88, `DbConsoleResource` 75, `McpConnectResource` 75,
`CronResultHandler` 74, `PlaywrightCliInstaller` 70, `AgentCronScheduler` 70, `Vec0NativeLibraryInstaller` 59,
`AuthResource` 56, `GithubIdentityProvider` 50, `BootstrapOrchestrator` 46, `AmshaCli` 45, `EnvConfigInitializer` 43,
`McpImportResource` 42, `IdentityResource` 40, `CliUtils` 37, `HitlResource` 36, `ModelArenaResource` 36.

## Reporting the Verdict

Summarize as a table:

| Gate | Command | Result |
|------|---------|--------|
| Tests | `… -Dtest=…` | N/N pass |
| PMD | `pmd:check` | SUCCESS (28 modules) |
| Spotless | `spotless:check` | clean / N files |
| Clean install | `clean install -DskipTests` | SUCCESS |
| Docs | `verify_docs.py` | 0 errors |
| Sonar | `mise run sonar:issues` | BLOCKED (no container) |

Then give a one-line verdict: ready to commit/merge if every required gate is
green, with Sonar called out as a human-only remaining step.
