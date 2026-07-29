# Amsha Agent Developer Guide

Read this before editing. Detailed rationale, endpoint lists, and historical
incidents live in [Architecture Invariants](docs/Architecture_Invariants.md).

## Required checks

- After Java edits: `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`.
- After Markdown edits: `python3 ./scripts/verify_docs.py`.
- Before committing: `mise run sonar:issues` (or the BLOCKER-only variant).
- Verify changes proportionally: compile affected modules, run focused tests, and
  run `./mvnw clean install -DskipTests` after changing a dependency module.

## Non-negotiable invariants

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
  compact source files, or value classes.
- Nullable sealed-interface wrappers require `@nullable` documentation and
  null-safe consumers (`Boolean.TRUE.equals(value)`).
- Admin resources use `resolveUserId()`; mutating `/admin/**` endpoints require
  `isInternal()`. Reads remain public.
- Use `ToolCapability.toolName()` instead of tool-name literals where `amsha-core`
  is available. Put tool mode values in `static final` constants.
- Static data is immutable (`Map.copyOf()`); records validate cross-field rules in
  compact constructors. Qualifier literals extend `AnnotationLiteral<T>` and
  implement `T` (suppress `java:S2176`).

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
- Optional-dependency beans are discovered through `BeanManager.getBeans(Object.class)`,
  then class-name checked and resolved via `getReference()`.
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
4. Compile and build the UI. See [JVM LLM options](Ideas-Pending/JVM-LLMs.md).

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

| Change | Minimum check |
| --- | --- |
| Prompt/escalation | `./mvnw test -pl core -Dtest="SystemPromptBuilderTest,PersonaEscalationServiceTest,EscalationPatternsTest"` |
| Java | `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q` |
| Markdown | `python3 ./scripts/verify_docs.py` |
| Native-sensitive code | `./mvnw compile -DskipTests -q`; use `mise build:native-local` when applicable |
| SkillsJar dependencies | `python3 ./scripts/verify_skillsjar_index.py` |
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
  smoke pass does not close the Phase 0 platform-matrix, golden-corpus/leak, or signed
  SBOM/provenance gates; use the release-evidence scripts for those claims.

### REST validation

- Shell validators default to `http://localhost:8080`; set `TEST_BASE` when
  targeting another runtime. The shared value must reach both
  `rest-http/scripts/run_all_validations.sh` and child scripts through
  `rest-http/scripts/common_env.sh`.
- The master validator must return a nonzero exit code when any suite fails.
