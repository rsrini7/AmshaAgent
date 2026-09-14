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
python3 ./scripts/verify_docs.py
```

Expect `0 errors`.

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

Five separate instances of the same anti-pattern turned up in `DelegatedCompletionHandoff` (lines 68, 229, 251)
and `BrowserPreflightHandler` (lines 240, 274), all unreachable, all worth deleting:

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

The tell is a *condition whose operands are computed from the same source as a condition already evaluated
upstream*, a `default` arm over a closed set the caller has already narrowed, a guard on a **type invariant the
constructor already enforces**, or a disjunct **implied by the regex that gated entry**. Grep for the guard's
operands before assuming a test is missing — and note that in every one of these five cases the `if` line itself
is reported **covered** while its body is not, so a green line is not evidence that the guard works.

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
