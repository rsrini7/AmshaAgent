# Amsha Agent Developer Guide

Read this before editing. Detailed rationale, endpoint lists, and historical
incidents live in [Architecture Invariants](docs/Architecture_Invariants.md).

## Required checks

- After Java edits: `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`.
- Before building or handing off Java changes, run the module-scoped PMD gate:
  `./mvnw -pl <affected-module> -am pmd:check -DskipTests`.
- PMD uses `app/src/main/resources/amsha-rules.xml`: methods are limited to 80
  NCSS statements, cognitive complexity is limited to 50, and classes are limited
  to 500 lines. A PMD pass is required even when the build uses `-DskipTests`;
  `package` alone does not reach the PMD `verify` execution.
- After Markdown edits: `python3 ./scripts/verify_docs.py`.
- Before committing: `mise run sonar:issues` (or the BLOCKER-only variant).
- Verify changes proportionally: compile affected modules, run focused tests, run
  the PMD gate, and run `./mvnw clean install -DskipTests` after changing a
  dependency module.

## Non-negotiable invariants

### Git staging and review hygiene

- Never stage or unstage files (`git add`, `git reset`, `git restore --staged`, `git commit`). Leave all git staging and commit decisions exclusively to the human user for review and learning.

### Transactions, persistence, and native image

- Never perform LLM calls, network I/O, or external tool execution inside
  `@Transactional`; keep writes short. Do not nest `REQUIRES_NEW` inside active
  writes or alter SQLite safety settings in a transaction.
- Every store port needs SQLite, PostgreSQL, and filesystem implementations.
  Flyway migrations must have matching SQLite/PostgreSQL versions and semantics.
  JDBI query records need `@RegisterConstructorMapper`; inject `JdbiProducer` and
  use `@BindMethods` for record writes.
- SQLite is the default persistence backend. JVM builds select it with
  `AMSHA_PERSISTENCE_BACKEND`; native binaries select it at build time. See
  [§5.24](docs/Architecture_Invariants.md#524-persistence-backend-selection-524-invariant).
- Review every new Java type for native reflection. JSON/JDBI records, JDBI DAOs,
  JDBI DTO rows, and persistence CDI beans require registration in the owning
  module's dedicated `*ReflectionConfig`. Do not place `@RegisterForReflection`
  directly on individual application types. Add accessible types to `targets`;
  add private, nested, or dependency types to `classNames` using their binary name
  (for example, `Outer$Inner`). If a module has no reflection config, create one
  central config for that module rather than adding type-level annotations. Mark
  native-access libraries for run-time initialization by package, not individual
  classes.

### Context, execution, and safety

- Tool-path code uses `WorkspaceContext`; reflex/command-dispatch code may use
  `WorkspaceScopeHolder`. Workspace-aware async tasks must use
  `ContextAwareExecutorService` or `WorkspaceScopeHolder.wrap()`.
- `PreflightState`, `LoopStuckDetector`, and `ToolInvocationGuard` are mandatory.
  The guard executes before every tool; never bypass it.
- Pre-policy tool-guard `Allow` decisions still pass through every mandatory check.
  Post-policy classifier hooks may only increase risk, and answer-quality classifiers
  may consume one retry only before a tool boundary. Optional absence or failure must
  preserve the deterministic decision without logging payloads.
- Classifier controls distinguish requested from effective state. Shadow beans
  never register as active implementations. Enable without a ready implementation fails
  closed; disable rejects new leases and drains accepted work. Mutations require internal
  authorization and mandatory audit-before-mutation.
- The Jev decision plane is **Deferred**: no production `DecisionEngine` bean may be
  registered. Enabled shadow classifiers without a resolvable engine return
  `NoOpinion(MISSING_MODEL)`; disabled classifiers return `DISABLED`. With no engine,
  `IntentDecisionRouter.isEnabled()` is false and `evaluateIntent()` returns a `DISABLED`
  fallback. Enabling a decision switch without a resolvable engine must stay inert and log
  exactly one `DecisionPlaneReadiness` WARN.
  Moving the ADR off `Deferred` is a deliberate architectural change, never a side effect
  of an unrelated edit (`docs/Architecture_Invariants.md` §27).
- An **explicit evaluation path** exists to make a real provider call without registering a production
  bean: see `docs/adr/architecture/ADR-jev-decision-plane-evaluation-path.md` (status: **Accepted**,
  2026-10-07) and the `docs/research/Jev-Decision-Plane-Activation-Plan-2026-10-07.md` brief. It is
  opt-in (`amsha.decision.evaluation.enabled`, default `false`), internal-only, audited-before-egress, and
  non-routing, and it is the only caller of the `DecisionEngineFactory` construction seam — constructing an
  engine for one evaluation is not registering one, so `Instance<DecisionEngine>` stays unsatisfiable and no
  activation switch opens. W4 and W5 have landed: the versioned corpus-backed `/evidence/run` endpoint
  reports M1–M5 through the deterministic policy layer. The price schedule is intentionally empty until
  rates are approved, so this work does not close the parent ADR's Quality or Operations gates.
  The path must stay off by default, internal-only, allow-listed, non-routing, and must NOT make
  `Instance<DecisionEngine>` resolvable (§27.6, §27.7).
- After a successful tool result, the coordinator must emit `FINISH`; no subsequent
  tool call may follow in that turn. Keep full and compact prompts aligned.
- `NETWORK_OUTBOUND` and `EXECUTE_SHELL` always require HITL. Trusted roles only
  bypass non-sensitive operation checks. Skill-role `host_exec` is granted only by
  `HostExecSkillGrant` through `amsha.tools.host-exec.skills`.
- Telegram approval callbacks that bypass `AbstractDecisionReflex` must call
  `flushPendingNotifications()`. On restart, stale approvals are denied for every
  channel. Fallback session IDs derive from `(channelType, channelId, userId)`;
  cron sessions never route to chat approval.
- Create `SecureRandom` locally and zero secret bytes in `finally`; do not retain
  it in static fields. Do not echo generated HTML into chat: use the HTML artifact
  transformer and browser preview path.
- Non-blocking thread guard checks must test the actual executing thread using
  `Context.isOnEventLoopThread()`. Never infer the thread from
  `Vertx.currentContext().isEventLoopContext()`, as `@RunOnVirtualThread` REST
  handlers retain an event-loop-derived Vert.x context while running safely on
  virtual threads.

### Java and API conventions

- JDK 25 finalized features are allowed; preview features are forbidden. In
  particular, do not use structured concurrency, stream gatherers, module imports,
  compact source files, or value classes. Use the unnamed variable `_` for unused
  variables or exceptions rather than naming them `ignored`.
- Nullable sealed-interface wrappers require `@nullable` documentation and
  null-safe consumers (`Boolean.TRUE.equals(value)`).
- Admin resources use `resolveUserId()`; mutating `/admin/**` endpoints require
  `isInternal()`. Reads remain public.
- Use `ToolCapability.toolName()` instead of tool-name literals where `amsha-core`
  is available. Put tool mode values in `static final` constants.
- Static data is immutable (`Map.copyOf()`); records validate cross-field rules in
  compact constructors. Qualifier literals extend `AnnotationLiteral<T>` and
  implement `T` (suppress `java:S2176`).
- Do not use raw `volatile` for compound state mutations, check-then-act lifecycle
  transitions, or multi-variable coordination. Use `AtomicReference`, `AtomicBoolean`,
  or immutable records swapped via `AtomicReference`. Virtual threads do not pin
  carrier threads on `synchronized` in JDK 25; prefer `synchronized` blocks or
  `computeIfAbsent` over fragile lock-free volatile schemes. Use `VarHandle` when
  zero-allocation field access modes (`getAcquire`/`setRelease`) are required. See
  [§24.6](docs/Architecture_Invariants.md#246-volatile-field-invariants--modern-jdk-25-concurrency-alternatives-246-invariant).


### Routing, channels, and prompts

- Only explicit continuity (`retry`, `redo`, `continue`, and similar) restores an
  old goal. Execution follow-ups use the new goal.
- Preserve escalation order: role rules → new-persona rules → group fallback →
  sandbox-code fallback. Use specific keywords; canonicalize known role IDs with
  `AgentRoleEnum.fromString(id).toScopedName()`, catching unknown dynamic IDs.
- Inbound channel handlers extend `AbstractInboundMessagePipeline`; channel modules
  communicate with core through channel APIs, not direct core imports.
- Prompt templates require the Qute template, injection, and `templateFor()` switch
  case. Prompt registry edits must preserve seeded/admin-edit behavior. When a
  prompt has a compact counterpart, update both and run `SystemPromptBuilderTest`.
- Telegram output must pass `stripGarbledLines()`. Researchers use recall or
  archival memory search, not `headroom_retrieve`.

## Implementation rules

- Add every `@ConfigProperty` to `application.yaml`, `.env.example`, and the
  relevant module README; verify the property appears in both config files.
- In Quarkus, `@ApplicationScoped` beans are lazily initialized. If a bean needs to eagerly
  register itself with a registry or control plane on startup (e.g., using `@PostConstruct`),
  it must also be annotated with `@io.quarkus.runtime.Startup`. Otherwise, its initialization
  will be deferred until its first method invocation, resulting in missing-implementation bugs.
- Optional-dependency beans are discovered through `BeanManager.getBeans(Object.class)`,
  then class-name checked and resolved via `getReference()`. These dynamically discovered beans
  must be annotated with `@io.quarkus.arc.Unremovable` to prevent Quarkus from pruning them
  during GraalVM native builds.
- Keep duplicate behavior in one layer: do not repeat work already performed by a
  called method. Log exception messages safely with `LogUtil.rootMessage(e)`.
- New CDI library modules require Jandex, `META-INF/beans.xml`, application indexing,
  test configuration, and a startup-log verification. New modules must not depend on
  `amsha-app`.
- Dynamic SkillsJars are prompt/tool wrappers, not executable engines. Vet their
  CLI/cloud dependencies; provision required binaries; keep
  `META-INF/skills/skills-index.txt` in sync with `app/pom.xml` using
  `python3 ./scripts/verify_skillsjar_index.py --fix` and the verifier.

## Change recipes

### LLM provider

1. Add routing and agent-reasoning factories in `amsha-core`.
2. Register URL detection and onboarding defaults.
3. Update `.env`, `.env.example`, `application.yaml`, and UI provider lists.
4. Compile and build the UI. See [JVM LLM options](docs/research/providers/JVM-LLMs.md).

## 7. Adding a New Persona (Agent Role)

Add the persona and role definitions, persona and role prompt templates, producer,
coordinator prompt entry, template-engine mapping, delegation metadata/rules,
escalation rules, system capabilities, configuration, and group membership. Update
the Telegram fixture; compile and run role/escalation tests. Static prompt mappings
and metadata lists have no compile-time safety.

## 8. GraalVM Native-Image Reflection Registration

Review every new type using the reflection rules above. Use the module's existing
`*ReflectionConfig`; never use a type-level `@RegisterForReflection` as a shortcut.
Register accessible application classes through `targets` and inaccessible,
private nested, or third-party classes through `classNames` with exact binary
names. Keep native-access libraries initialized at run time.

### Persistence or memory

Keep all backends and migration parity in one change. Preserve the admin-resource
authorization pattern, retention jobs, and memory metrics. Consult the detailed
[persistence rules](docs/Architecture_Invariants.md#5-persistence-and-transaction-rules)
and the memory section in `docs/Architecture_Invariants.md`.
- Every durable vector namespace must validate compatibility against `EmbeddingIndexDescriptor`.
  Descriptor fingerprints compare model, tokenizer, pooling, and dimension contracts,
  excluding `providerId` to allow provider failover. On descriptor mismatch, non-empty
  vector namespaces must never be auto-purged; they must fall back non-destructively
  to BM25/FTS5 keyword search.
- Local ONNX model bundle manifests must include validated upstream source metadata
  (`repository`, `revision`, `modelPath`, `tokenizerPath`, `license`). Tokenizers must
  enforce truncation bounded to the model's declared max sequence length (e.g. 384 for MPNet).

## Focused verification

### Change-review protocol

For a staged or work-in-progress change, review the exact diff before relying on tests or a generated report. Capture `git diff --cached --stat`, `git diff --cached --check`, and the changed-file list without changing the index. Read the originating ADR, specification, and relevant architecture invariants, then classify each finding as introduced, pre-existing, or uncertain by comparing it with `HEAD`.

Review the complete production path, including wiring, persistence, native reflection, and runtime configuration. A unit test that mocks the collaborator under review does not prove production wiring; add or run one test through the real boundary and assert the security-relevant side effect. Exercise malformed JSON, wrong value types, missing identifiers, duplicate identifiers, unknown references, and partial or legacy payloads whenever a change accepts external data. These cases must fail closed rather than being skipped, coerced, or reported as success.

Keep one canonical validator for policy semantics. Do not create a second parser with subtly different timeout, URI, TLS, proxy, or allow-list rules. Defaults belong only on an explicitly identified compatibility path; complete records from the current API must be rejected when required fields are absent. Generated persistent identifiers must be collision-resistant and references must be validated across the full submitted payload, not only the first section encountered.

Review output, exception messages, logs, test fixtures, and local notes must not contain credentials, authorization headers, raw provider payloads, or secret-bearing malformed input. Redact before recording evidence. Treat ignored working-memory files and previous review reports as hypotheses: verify their claims against the current source, index, tests, and runtime before acting on them.

Use `scripts/review_staged.sh --fast` for a read-only structural review and
`scripts/review_staged.sh --full` when affected-module test suites are needed.
Use `--native` for changes that affect native-image-sensitive code. The wrapper
records the commit and staged-diff hash, discards command output, and fails if
the Git index or tracked worktree changes during verification.

Validate changes to the wrapper itself with `bash -n scripts/review_staged.sh`
and `python3 scripts/test_review_staged.py`.

| Change | Minimum check |
| --- | --- |
| Prompt/escalation | `./mvnw test -pl core -Dtest="SystemPromptBuilderTest,PersonaEscalationServiceTest,EscalationPatternsTest"` |
| Java | `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q` |
| PMD complexity/size | `./mvnw -pl <affected-module> -am pmd:check -DskipTests` |
| Markdown | `python3 ./scripts/verify_docs.py` |
| Native-sensitive code | `./mvnw compile -DskipTests -q`; use `mise run build:native-local` when applicable (it rebuilds the UI, runs PMD via `verify`, and packages the native image) |
| SkillsJar dependencies | `python3 ./scripts/verify_skillsjar_index.py` |
| Classifier SPIs | `./mvnw -pl core -Dtest="PostPolicyToolRiskDispatcherTest,AnswerQualityDispatcherTest,ToolInvocationGuardTest,FinishActionHandlerTest" test` |
| Classifier controls | `./mvnw -pl local-ml-onnx,app -am -Dtest="OnnxClassifierRuntimeControlTest,OnnxAdminResourceTest,SecurityEventTypeTest" -Dsurefire.failIfNoSpecifiedTests=false test` |
| Local OMLX / ONNX smoke | `mise run smoke:local-runtime -- native --binary dist/amsha-sqlite` |
| Local ML / ONNX Release | `bash scripts/verify_native_matrix.sh && bash scripts/verify_onnx_release_provenance.sh` |

### Reusable local runtime smoke

- Enter through `mise run smoke:local-runtime -- <command>`. The mise task delegates
  to the root `scripts/local_runtime_smoke.sh` wrapper, which invokes
  `scripts/local_runtime_smoke.py`. Keep this chain intact so developers and coding
  agents use one reproducible entry point. Override Python only with
  `AMSHA_SMOKE_PYTHON`.
- Use `omlx` for authenticated provider catalog plus minimal completion checks. Use
  `running --amsha-base-url http://127.0.0.1:8080` for OMLX plus an existing Amsha
  process. Use `native --binary dist/amsha-sqlite` for isolated native startup,
  bundle/health/index/inference/metric validation, and graceful shutdown.
- The runner reads only required values from the ignored root `.env`, with process
  environment overrides: `AMSHA_OMLX_API_KEY`, `AMSHA_OMLX_BASE_URL`, and
  `AMSHA_OMLX_MODEL`; `running` also needs `AMSHA_API_KEYS_INTERNAL`. Never hardcode,
  stage, echo, log, or pass credentials as command-line arguments. Never print raw
  provider/application responses, prompts, completions, or authorization headers.
- For this smoke workflow, never use Ollama. The native command maps the OMLX values
  to `AMSHA_GENERIC_OPENAI_*`, forces `AMSHA_LLM_PROVIDER=generic-openai` and the
  embedding provider to `onnx`, and disables Telegram. Do not weaken those overrides.
- Native smoke artifacts live below gitignored `data/smoke/`. Successful runs clean
  them; failed runs retain a diagnostic path without automatically dumping logs.
- Run `python3 scripts/test_local_runtime_smoke.py` after changing the runner. A local
  smoke pass does not close the platform-matrix, golden-corpus/leak, or signed
  SBOM/provenance gates; use the release-evidence scripts for those claims.

### REST validation

- Shell validators default to `http://localhost:8080`; set `TEST_BASE` when
  targeting another runtime. The shared value must reach both
  `rest-http/scripts/run_all_validations.sh` and child scripts through
  `rest-http/scripts/common_env.sh`.
- The master validator must return a nonzero exit code when any suite fails.
- Every `validate_*.sh` must be registered in `run_all_validations.sh`; the guard in
  `scripts/distribution/test_dist_wrappers.py` fails when a validator is orphaned or a
  `*_SCRIPT` path is stale. Zero-token validators belong in the `--fast` set, because a
  check that only an opt-in flag can reach is invisible coverage.

## Agent skills

### Issue tracker

Issues are tracked as local Markdown files under
`.scratch/<feature-slug>/issues/`. Use `python3 ./scripts/scaffold_feature_tracker.py`
to scaffold or update ADRs, `spec.md`, and ordered iteration issues. See
`docs/agents/issue-tracker.md`.

### Quarkus update skill

The `quarkus-update` agent skill (from
[quarkusio/skills](https://github.com/quarkusio/skills)) is installed at
`~/.agents/skills/quarkus-update` (symlinked into `~/.pi/agent/skills/`).
Install or refresh it with:

```bash
npx skills add quarkusio/skills --skill quarkus-update
```

It compares the project's build files against reference generated projects from
[code-with-quarkus-compare](https://github.com/quarkusio/code-with-quarkus-compare)
and drives the Quarkus updater dry-run. Use it when checking whether build files
are up to date or when executing a Quarkus platform checkpoint; pair its output
with the verification matrix in the [Quarkus upgrade ADR](docs/adr/architecture/ADR-Quarkus-Upgrade-Strategy.md).
Evidence from its runs lives under `.scratch/quarkus-upgrade/evidence/`.

### Triage labels

The default five-role triage vocabulary is used. See
`docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository using root `CONTEXT.md` when present and
topic-scoped `docs/adr/**/ADR-*.md` files for ADRs. See
`docs/agents/domain.md`.
