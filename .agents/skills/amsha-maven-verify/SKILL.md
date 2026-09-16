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
python3 ./scripts/quality/verify_docs.py
```
*(Note: Run the canonical script under `scripts/quality/verify_docs.py` to avoid subprocess forwarding signals).*
Confirm output: `found 0 errors and 0 warnings`.

### 5. Full Clean Install / Reactor Build
Compiles all modules end-to-end and validates packaging:

```bash
./mvnw -o clean install -DskipTests < /dev/null
```
Confirms clean builds across all ~28 reactor modules.

### 6. Static Analysis & Sonar Audit
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
- **Always re-measure after `clean`**: JaCoCo maps current source line numbers onto compiled bytecode in `target/classes`. Running against stale classes yields corrupted line data.
- **Historical Analysis & Gap Registry**: Detailed module-by-module analysis, class rosters, and gap tracking are maintained in [`docs/TEST_COVERAGE_ANALYSIS.md`](../../../docs/TEST_COVERAGE_ANALYSIS.md). Consult that document when planning coverage enhancements.

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

---

## Reporting the Verdict

After running the verification workflow, summarize findings using a clean Markdown table:

| Gate | Command | Result |
| :--- | :--- | :--- |
| **Targeted Tests** | `./mvnw -pl <module> -am test -Dtest=...` | Passed (N/N tests) |
| **PMD Complexity** | `./mvnw -pl <module> -am pmd:check -DskipTests` | BUILD SUCCESS |
| **Spotless & FQNs** | `python3 fix_fqns.py && ./mvnw spotless:check` | Clean |
| **Clean Install** | `./mvnw -o clean install -DskipTests` | BUILD SUCCESS |
| **Docs Verifier** | `python3 ./scripts/quality/verify_docs.py` | 0 errors, 0 warnings |
| **Coverage Gate** | `python3 ./scripts/quality/verify_jacoco_coverage.py --minimum 0.82` | X.XX% (≥ 82.00%) |
| **Sonar Check** | `mise run sonar:issues` | 0 Blockers / 0 Critical |

Provide an explicit verdict indicating whether the branch is verified and ready for the user to review and stage.
