---
name: amsha-maven-verify
description: "Reusable Maven verification workflow for the Amsha multi-module project (Quarkus/LangChain4j). Use this skill when asked to verify, review, build, or check Java changes before commit/merge, running the required AGENTS.md gates (tests, PMD, Spotless, clean install, doc verification, coverage). Captures the exact JDK 25 toolchain, offline-build, and Surefire flags needed in this environment, and enforces the non-negotiable rule that git staging and commits are reserved exclusively for the human."
---

# Amsha Maven Verify

## Overview

This skill encodes the canonical verification gate sequence required by `AGENTS.md` for the Amsha project, along with workspace-specific environment requirements and reliable Maven flags.

Use it whenever verifying, building, checking merge-readiness, or adding test coverage for changes in this codebase.

---

## Non-Negotiable Invariants

1. **Git Staging & Commit Hygiene**:
   - **NEVER** run git staging or commit commands (`git add`, `git commit`, `git reset`, `git restore --staged`).
   - Leave all git staging and commit decisions exclusively to the human user for review.
2. **Quality & Complexity Bounds**:
   - Methods limited to 80 NCSS statements.
   - Cognitive complexity limited to 50.
   - Source files limited to 500 lines.
   - Enforced by PMD (`app/src/main/resources/amsha-rules.xml`).

---

## Environment Setup

Always export the JDK 25 toolchain before executing `./mvnw`:

```bash
export JAVA_HOME=/Users/srinivasanragothaman/.sdkman/candidates/java/25.0.3-tem
export PATH="$JAVA_HOME/bin:$PATH"
```

*Alternative toolchain path (pinned via `mise.toml`):*
```bash
export JAVA_HOME="$HOME/.local/share/mise/installs/java/temurin-25.0.3+9.0.LTS/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

Verify with `java -version` (must be JDK 25.x).

### Maven Execution Rules
- **Use the project wrapper**: Always invoke `./mvnw` at the repo root.
- **Redirect stdin**: Always append `< /dev/null` to prevent interactive CLI utilities or prompts from blocking execution indefinitely.
- **Prefer offline builds**: Add `-o` when the dependency tree is already resolved locally. Drop `-o` only if downloading newly added dependencies.
- **No `timeout` binary**: The macOS host does not supply GNU `timeout`. Rely on Maven/Surefire process timeouts or asynchronous background task execution.

---

## The Verification Gate Sequence

Execute gates in order. Each gate must succeed before declaring a change merge-ready.

### 1. Targeted Tests (Fast Feedback)
Run only affected test classes first. Use `-pl <module> -am` to compile upstream dependencies, and **always** supply `-Dsurefire.failIfNoSpecifiedTests=false`:

```bash
./mvnw -o -pl <module> -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=TargetClassTest,AnotherClassTest < /dev/null
```
Confirm output: `Failures: 0, Errors: 0`.

> **Trap — `-pl <module>` without `-am` can test against STALE installed jars.** A sibling module that is
> not in the reactor is resolved from `~/.m2`: whatever was last installed, which may predate your working
> tree. Observed 2026-10-01 — three `core` decoder tests failed "expected `Opinion` but was `NoOpinion`"
> purely because `-pl core test` linked a stale `amsha-classifier-api`; the identical tests were green
> under `-pl classifier-api,core`. **If a failure implicates a sibling module's class, get that module into
> `-pl` (or use `-am`) before believing it is a regression.**

### 2. Module PMD Gate
Run the module-scoped PMD complexity and size checks:

```bash
./mvnw -pl <affected-module> -am pmd:check -DskipTests < /dev/null
```
Confirm output: `BUILD SUCCESS` across all checked modules in the reactor.

### 3. FQN Fixes & Spotless Formatting
Apply fully-qualified name cleanups and run Spotless formatting:

```bash
python3 ./scripts/fix_fqns.py && ./mvnw -o spotless:apply -q < /dev/null
```

Verify formatting is clean:
```bash
./mvnw -o spotless:check < /dev/null
```

### 4. Markdown & Documentation Verification
After editing any Markdown files (docs, plans, ADRs):

```bash
python3 ./scripts/verify_docs.py            # auto-forwarding shim — SIGTERMs (exit 137)
python3 ./scripts/quality/verify_docs.py    # canonical script
```
*(Note: Run the canonical script under `scripts/quality/verify_docs.py` to avoid subprocess forwarding signals).*
Confirm output: `found 0 errors and 0 warnings`.

> **Correction (2026-10-01) — the canonical script now fails too, and *not* with 137.** It walks all ~149
> repo Markdown files, and the sandbox file-access broker raises
> `PermissionError: Sensitive content approval timed out` partway through. There is **no CLI argument to
> scope it to a path**, so the real doc gate cannot currently be run in this environment at all. Substitute
> a scoped check over just the files you edited — resolve each relative Markdown link against the repo root
> and assert the target exists — and **state explicitly that you substituted it** rather than implying the
> gate passed.

### 5. Full Clean Install / Reactor Build
Compiles all modules end-to-end and validates packaging:

```bash
./mvnw -o clean install -DskipTests < /dev/null
```
Confirms clean builds across all ~28 reactor modules.

### 6. Configuration Surface (yaml ↔ `.env.example`)
A change to a `@ConfigProperty` is not verified until the documented surfaces agree — the ADR convention is
*"every `@ConfigProperty` must appear in `application.yaml`, `.env.example`, and the module README."*

**The trap that silently eats the work.** `app/src/main/resources/env-example` is **GENERATED**.
`app/pom.xml` has an execution `sync-env-example` bound to `generate-resources` that copies the repo-root
`.env.example` over it with `overwrite=true`, and `.gitignore:68` excludes it. An edit made there is destroyed
by the next build **and leaves no trace in `git status`** — so it looks like nothing was ever written. The
only file to edit is the repo-root **`.env.example`**:

```bash
./mvnw -o -pl app generate-resources -q
diff -q .env.example app/src/main/resources/env-example   # no output = identical
```

**Verifying parity.** Extract every `AMSHA_<AREA>_*` name from the relevant `application.yaml` block and
require each to appear (optionally commented) in `.env.example`. Use the **Grep tool, not a script**: on
2026-10-01 a Python script reading `.env.example` was SIGTERM'd twice by the file-access broker (exit 137),
and a bash `grep` over the same files returned empty at exit 0 — while Grep read both fine.

**Renaming a config field** touches more places than it looks: the `@ConfigMapping` accessor, the
`application.yaml` key, the `AMSHA_*` name in *both* the yaml default and `.env.example`, every hand-written
config double in tests, and every `@TestProfile` `getConfigOverrides()` key. Then re-run `spotless:apply` and
the affected suites. When a previously-optional field becomes **mandatory**, every double that omitted it now
fails — update the doubles as part of the change rather than relaxing the rule, and add a case for each new
refusal plus one for the boundary that must *not* trigger it.

### 7. Static Analysis & Sonar Audit
Check for critical issues or blocker bugs before handoff:

```bash
/opt/homebrew/bin/mise run sonar:issues
```

---

## Measuring & Verifying Code Coverage

Use this workflow to measure test coverage or enforce the JaCoCo line coverage gate.

### 1. Full Reactor Verification & Measurement
```bash
# Run full reactor test suite, ITs, PMD, and coverage generation
./mvnw --batch-mode clean verify < /dev/null

# Summarize overall coverage
python3 ./scripts/analyze_jacoco_coverage.py --summary

# Per-module line + branch totals, weakest first, with a reactor TOTAL row
python3 ./scripts/analyze_jacoco_coverage.py --modules

# Enforce coverage floor (read from pom.xml <jacoco.minimum.line.coverage>)
python3 ./scripts/quality/verify_jacoco_coverage.py --minimum 0.82

# View test and coverage metrics summary
/opt/homebrew/bin/mise run test:summary
```

### 2. Coverage Inspection & Diagnostics
- **Check missed lines for a specific class**:
  ```bash
  python3 ./scripts/jacoco_lines.py --root . <fully.qualified.ClassName>
  ```
- **Per-module totals**: use `--modules` (line *and* branch, weakest first, plus a `TOTAL` row; add
  `--module <mod>` for one module, or `--json` for machine-readable). Do **not** confuse it with
  `--module <mod>` alone, which only *filters the class gap ranking* and yields no per-module totals.
  The `TOTAL` row must reproduce the reactor figure exactly — that equality is the honest-aggregation check.
- **Mise wrappers exist for all of the above** (they read existing reports and never build):
  `mise run quality:coverage` (summary + per-module totals), `mise run quality:coverage-gaps` (top gaps with
  missed line numbers), `mise run quality:coverage-gate` (the gate alone — seconds instead of an ~11 min
  reactor build), `mise run quality:py-tests` (Python contract suites, including two `ci.yml` omits).
- **Always re-measure after `clean`**: JaCoCo maps current source line numbers onto compiled bytecode in `target/classes`. Running against stale classes yields corrupted line data.
- **Historical Analysis & Gap Registry**: Detailed module-by-module analysis, class rosters, and gap tracking are maintained in [`docs/TEST_COVERAGE_ANALYSIS.md`](../../../docs/TEST_COVERAGE_ANALYSIS.md). Consult that document when planning coverage enhancements.

### 3. Coverage Traps & Badge Maintenance

- **Black-box / distribution validation contributes ZERO coverage.** `scripts/distribution/` and
  `rest-http/` contain no `jacoco`/`javaagent` reference, and the CI `jvm-validation` job
  (`package -DskipTests` plus HTTP checks) launches an uninstrumented JVM. `validate_decision_plane.sh`,
  the other `validate_*.sh` scripts, and that job move the gate by **0 lines** — never cite them as
  coverage evidence. A `@QuarkusTest` inventory is likewise a *static count of annotations*, not a
  measurement.
- **Unit and IT coverage are MERGED and cannot be separated.** Surefire and Failsafe both take
  `@{argLine}`, so both forks append to one `target/jacoco.exec` per module (no module sets `destFile`).
  The reactor percentage is a union; "what do the `*IT`s cover on their own?" is unanswerable from these
  reports. `--strict-instrumentation` catches an agent that never attached — **not** an IT gap.
- **README Java badges are hand-maintained.** Nothing writes `![Tests]` / `![Coverage]` for Java:
  `analyze_jacoco_coverage.py --badge` only *prints* a string, and `--sync-readme` exists only on the UI
  analyzer. Reconcile both Java badges by hand after a full reactor run. (UI badges *are* auto-synced via
  `analyze_ui_coverage.py --summary --sync-readme` — never hand-edit those.)
- **`analyze_jacoco_coverage.py` reads the floor from the POM** via `build_gate(root)`, with the badge
  colour band in the separately testable `badge_color(pct, gate)`. It once hardcoded a dead `0.74` and so
  painted `brightgreen` for `[0.74, 0.82)` — a range the build rejects. Guarded by
  `scripts/quality/test_analyze_jacoco_coverage.py` (23 tests), whose `BadgeOutputTest` drives `main()`
  end-to-end by patching `repo_root.get_repo_root`. **The pure-function tests still pass when the call
  site is broken**, so the end-to-end ones are the ones that matter.
- **Concurrent Maven runs corrupt each other.** A second `clean` deletes `target/classes` while the first
  is still compiling; the signature is `default-compile` failing in under a second with "package
  com.amsha.… does not exist". Never start a second reactor while one is running — to test another
  revision, isolate it with `git archive <sha> | tar -x -C /tmp/x`.
- **Mise task files have a required shape.** `.mise/tasks/<group>/<name>.sh` needs
  `#MISE description = "..."` on **line 2**, `set -euo pipefail`, a trailing `exec <root-shim> "$@"`, and
  mode **100755** — mise silently skips it otherwise. A task that can emit machine-readable stdout
  (`--json`, `--badge`) must send its chatter to **stderr**, or the payload is corrupted and `| jq` fails
  with `Expecting value: line 1 column 1`.

---

## Testing Patterns & Best Practices in Amsha

When writing or extending tests in this codebase:

1. **Inline MockMaker by Default**:
   - Mockito 5.x uses the inline mock maker; `final` classes are mockable out of the box without special configuration.
   - Prefer real instances when lightweight package-private constructors exist.
2. **Plain Unit Tests vs `@QuarkusTest`**:
   - Most core loop and business logic classes do not require a heavy CDI container.
   - `WorkspaceContext.run(...)` and `WorkspaceScopeHolder.call(...)` safely no-op when `Arc.container()` is null, allowing fast plain JUnit testing.
   - Reach for `@QuarkusTest` only when testing full CDI bean discovery, JAX-RS REST endpoints, or persistence integrations.
3. **Async Tasks & Executors in Tests**:
   - When classes use `ManagedExecutor` or virtual thread pools, stub the executor using an inline runner: `doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(executor).execute(any());`. This ensures deterministic execution on the test thread without sleeping.
   - For `submit` overloads on `ManagedExecutor`, match `submit(Runnable)` vs `submit(Callable)` explicitly to avoid stub misses.
4. **Concrete Utility Types**:
   - Use real instances of lightweight utilities (`ObjectMapper`, `SimpleMeterRegistry`) rather than mocks to avoid silent false positives on complex method chains.
5. **`@Alternative @Priority(1)` in `src/test` is enabled GLOBALLY for the module's whole test deployment** — not merely for the profiles that list it in `getEnabledAlternatives()`. Proven in `core`: it declares only `amsha-persistence-api`, `MockAuditEventStorePort` appears in no `getEnabledAlternatives()`, yet it is the only `AuditEventStorePort` implementation and `AuditVerifierTest` is green. When a globally resolvable test bean would silently change another test's semantics (e.g. `Instance<DecisionEngine>.isResolvable()`, read by `DecisionPlaneReadiness`), gate it with `QuarkusTestProfile.getEnabledAlternatives()`.
6. **A `@Dependent` CDI producer hands back a FRESH object on every `Instance.get()`**, so `verify(mock, never())` against a separately injected instance is **vacuously true** — it passes while proving nothing. Make the producer `@Singleton` first, then confirm non-vacuity with a `times(1)` positive control before trusting the negative assertion.
7. **Assert structure, not substrings, when the point is wire/contract conformance.** A `body.contains("...")` check cannot see a field in the wrong slot or an extra field — which is exactly the defect class these tests exist to catch. Parse the emitted payload and assert the exact key sets instead.

---

## Reporting the Verdict

After running the verification workflow, summarize findings using a clean Markdown table:

| Gate | Command | Result |
| :--- | :--- | :--- |
| **Targeted Tests** | `./mvnw -pl <module> -am test -Dtest=...` | Passed (N/N tests) |
| **PMD Complexity** | `./mvnw -pl <module> -am pmd:check -DskipTests` | BUILD SUCCESS |
| **Spotless & FQNs** | `python3 fix_fqns.py && ./mvnw spotless:check` | Clean |
| **Clean Install** | `./mvnw -o clean install -DskipTests` | BUILD SUCCESS |
| **Docs Verifier** | `python3 ./scripts/quality/verify_docs.py` | 0 errors, 0 warnings — **currently unrunnable in this sandbox; substitute a scoped link check and say so** |
| **Coverage Gate** | `python3 ./scripts/quality/verify_jacoco_coverage.py --minimum 0.82` | X.XX% (≥ 82.00%) |
| **Sonar Check** | `mise run sonar:issues` | 0 Blockers / 0 Critical |

Provide an explicit verdict indicating whether the branch is verified and ready for the user to review and stage.
