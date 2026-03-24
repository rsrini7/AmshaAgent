## Known Architecture Nuances — ALWAYS APPLY

**Nuance #1** — SystemMessages are NEVER stored in SQLite ChatMemoryStore.

**Nuance #2** — All CDI beans touching per-session context must use ScopedValue — never `@ApplicationScoped` mutable fields for per-session state.

**Nuance #3** — Pattern objects are always `static final` — never compiled inside methods, constructors, or per-message.

**Nuance #4** — `ToolRegistry.invokeTool()` uses String keys — any typed `ToolCapability` enum must bridge via `.toolName()` accessor.

**Nuance #5** — `arcclaw-deployment` module is Quarkus build-time ONLY — never add runtime logic there.

**Nuance #6** — SQLite WAL mode — keep all `@Transactional` boundaries short and tight.

**Nuance #7** — The existing `ReflexRegistry` two-pass chain must NOT be broken or reordered. New reflexes are additive: `CDI Instance<AgentReflex>` discovers them automatically.

**Nuance #8** — Quarkus profiles: `dev` / `test` / `staging` / `prod` — all config changes must be reflected in all relevant profile blocks in `application.yaml`.

**Nuance #9** — GraalVM native image: any new class using sealed interfaces or records must be registered in `native-image.properties` if not auto-detected.

**Nuance #10** — No `ALTER TABLE` or migration scripts of any kind. All regular table schema changes are `@Entity` only. Raw DDL reserved for virtual tables (`USING fts5/vec0`), triggers, and indexes ONLY.

**Nuance #11** — `SkillAwareIntentClassifier` checks `SkillRegistry` FIRST, then falls through to the LLM-based `IntentClassifierService`. Never invert this order.

**Nuance #12** — `ToolCapability` enum constants use underscores: `GITHUB`, `MEMORY_SEARCH`, `SCHEDULE_TASK`, `MANAGE_SCHEDULE`, `SANDBOX_EXEC`. `ToolCapability.toolName()` returns the lowercase no-underscore form: `"github"`, `"memorysearch"`, etc. `McpToolAllowlist @WithDefault` uses `toolName()` values (lowercase), NOT the enum constant names, because it is matched against external MCP server tool names which are always lowercase.

**Nuance #13** — `maxBudgetUsd` is EXPLICITLY EXCLUDED from skill frontmatter.

**Nuance #14** — In `application.yaml` test profile:
```yaml
%test:
  arcclaw:
    skills:
      hot-reload: false
```

**Nuance #15** — Dynamic skills use pre-compiled Qute wrapper `SkillSystemPrompt.html` only.

**Nuance #16** — ALL REST resource classes that touch JPA/Hibernate or trigger async audit writes MUST be annotated `@RunOnVirtualThread` at class level. Missing → `BlockingOperationNotAllowedException` immediately on any request. `McpAdminResource` MUST have `@RunOnVirtualThread`.

**Nuance #17** — Repository pattern in `arcclaw-core` ALWAYS uses raw `EntityManager` (`@Inject EntityManager em`). NEVER `PanacheRepositoryBase` or `PanacheEntity` — Panache bytecode enhancement is module-scoped and fails in cross-module `arcclaw-app` tests. `AgentSkillRepository` is the exact precedent.

**Nuance #18** — SQLite test isolation `@BeforeEach` rules: (a) All writes must be in a committed transaction BEFORE the first RestAssured call. (b) Read-only repository methods use `@Transactional(TxType.SUPPORTS)`. (c) Use `deleteAll()` + `GET /refresh` in `@BeforeEach` for clean baseline. Violating (a) → snapshot isolation anomalies.

**Nuance #19** — `@QuarkusTest` REST tests — every `given()` block for a protected endpoint MUST include: `.header(HttpConstants.X_ARCCLAW_API_KEY, "key-internal-changeme")`. Missing → 401 and test passes for the wrong reason.

**Nuance #20** — MCP stdio transport uses Newline-Delimited JSON-RPC 2.0. `PodmanMcpTransport` MUST: append `'\n'` to EVERY outbound message written to the process `OutputStream`, call `flush()` after every write, and read inbound messages line-by-line using `BufferedReader.readLine()`. Failure to frame with `'\n'` causes the remote MCP server to hang forever.

**Nuance #21** — In native image, EVERY class, record, or enum passed as data to a Qute template via `.data("key", object)` MUST be annotated `@TemplateData` (`io.quarkus.qute.TemplateData`). Without it, ANY property access `{var.field}` throws `TemplateException` at runtime — NOT at build time. This is silent in JVM mode and only surfaces in native. `@TemplateData` is SEPARATE from `@RegisterForReflection` — both may be needed.

**Nuance #22** — `AgentRoleEnum` constants are ROLE NAMES not deity names: `COORDINATOR`, `RESEARCHER`, `CODER`, `DEBUGGER`, `ARCHITECT`, `SYNTHESIZER`. Display names carry deity: `"Brihaspati (System Designer)"`, `"Dattatreya (Synthesizer)"`. The old `DATTATREYA` constant no longer exists — it is `SYNTHESIZER`. `fromString()` aliases: `"dattatreya"`, `"datta"`, `"synthesizer"` all → `SYNTHESIZER`. `PromptTemplateEngine.templateFor()` switch MUST be exhaustive across all 6 roles.

**Nuance #23** — `sqlite-vec` `load_extension()` uses the OS dynamic linker (Windows `LoadLibrary` / Linux `dlopen`). It IGNORES `java.library.path` entirely. Relative paths are resolved against the JVM working directory — which differs between dev mode (`arcclaw-app/`) and JAR mode (project root/). ALWAYS resolve via `Path.of(vecExtensionPath).toAbsolutePath().normalize().toString()` in `FtsConnectionInterceptor` before passing to `load_extension()`. `%dev` profile config: `arcclaw.rag.vec-extension-path: "../lib/vec0"`.

**Nuance #24** — Apache Camel dev mode lifecycle — CRITICAL: `camelContext.disableJMX()` is guarded by `isNew()` check in `AbstractCamelContext`. In Quarkus dev mode, `StartupEvent` fires AFTER the `INITIALIZED` state. `disableJMX()` becomes a SILENT NO-OP — `JmxManagementLifecycleStrategy` remains registered. Calling `camelContext.start()` then NPEs in `onContextStarting():189`. CORRECT FIX before `camelContext.start()`:
```java
camelContext.getLifecycleStrategies()
    .removeIf(s -> s.getClass().getName().contains("JmxManagement"));
```
This bypasses the `isNew()` guard and removes the strategy directly. JAR/native unaffected.

**Nuance #25** — `StructuredOutputEngine` resource loading: ALWAYS use `Thread.currentThread().getContextClassLoader().getResourceAsStream()`. NEVER use `getClass().getResourceAsStream()` — fails for in-memory resources generated by Quarkus augmentation in multi-module test execution.

**Nuance #26** — `AgentFunctionProcessor` schema generation: Schema must be generated from the SAM RETURN TYPE using `SchemaFactoryWrapper.acceptJsonFormatVisitor(returnTypeClass, visitor)`. NEVER use `ToolSpecifications.toolSpecificationFrom()` — that generates INPUT parameter schema, not the OUTPUT schema. Generated resource path: `schemas/functions/{InterfaceSimpleName}.json`. Emit `GeneratedResourceBuildItem` ONLY — never write to filesystem.

**Nuance #27** — `PermissiveJsonExtractor` Jackson configuration:
- CORRECT: `JsonReadFeature.ALLOW_SINGLE_QUOTED_STRINGS`
- WRONG: `JsonReadFeature.ALLOW_SINGLE_QUOTES` (does not exist — compile error)
- CORRECT: `JsonReadFeature.ALLOW_TRAILING_COMMA` (token-level, preserves commas in strings)
- WRONG: regex comma stripping (corrupts `"He left, she stayed"` → `"He left"`)
- ABSENT (intentional): `FAIL_ON_MISSING_CREATOR_PROPERTIES` — allows `@Nullable` fields to be absent from JSON without throwing.

**Nuance #28** — `StructuredRetryLoop` budget handling: `BudgetWarningException` → CATCH internally, log, proceed with retry (soft signal). `BudgetExceededException` → DO NOT CATCH, propagate to `AgentStateMachineRunner`. `assertWithinBudget()` threshold check MUST precede `compareAndSet` CAS — if CAS fires at 79% budget, `warningSent` is permanently true and the real 80% boundary warning is forever suppressed.

**Nuance #29** — Hibernate ORM 7 + `QuarkusTransaction` lambda scope: `EntityManager` proxy does NOT correctly bind to a new transaction inside a `QuarkusTransaction.requiringNew().run(lambda)`. Extract lambda bodies into dedicated `@Transactional(TxType.REQUIRES_NEW)` methods for correct proxy binding. `em.merge()` on an already-managed entity is a NO-OP in ORM 7 — rely on dirty checking instead. `em.find()` outside `@Transactional` returns null.

**Nuance #30** — Hibernate ORM 7 + SQLite DDL identity columns: Without `ArcClawSQLiteDialect.hasDataTypeInIdentityColumn()=true`, Hibernate ORM 7 emits DDL like `"checkpoint_id,"` (no type), breaking SQLite auto-increment rowid. `ArcClawSQLiteDialect` is mandatory for all entity tables with `@GeneratedValue`.

**Nuance #31** — `ToolObservationSanitizer.sanitize()` must wrap every tool response
before `history` append. Call site: `ToolInvoker.invoke()`. Never bypass for any tool
type including GitHub, sandbox, or MCP tools.

**Nuance #32** — `AsyncPersistenceEngine.submitCritical()` is bounded at 500. On
overflow → synchronous persist on the calling thread. A mandatory event
(`isMandatory=true`) is NEVER dropped. `submitTelemetry()` may drop on overflow —
increment Micrometer counter.

**Nuance #33** — `System.nanoTime()` for all elapsed-time measurements.
`System.currentTimeMillis()` is wall-clock, goes backward on NTP correction or
leap-second, and is FORBIDDEN for duration calculation.

**Nuance #34** — `EncryptedSecret.ormVersion` is Hibernate-managed.
`EncryptedSecret.encryptionKeyVersion` is application-managed. Application code sets
only `encryptionKeyVersion`. NEVER set or read `ormVersion` from application code.

**Nuance #35** — Vault pepper is `char[]`, zeroed immediately after use via
`Arrays.fill(pepperChars, '\0')` inside `deriveSessionKey()`. Never store as `String`.
`CharArrayConverter` registered in
`META-INF/services/org.eclipse.microprofile.config.spi.Converter` handles
MicroProfile injection of `char[]` config values.

**Nuance #36** — System-level SecretVault operations that must execute before any
WorkspaceScoped session is active use `VaultConstants.SYSTEM_SESSION_ID` as the
session identifier. This bypasses `VaultKeyCache`. The constant must never be a raw
inline string literal `"SYSTEM"`.

**Nuance #37** — `DevSecretSeeder` observes `StartupEvent` at `@Priority(100)`.
All beans that depend on secrets seeded by `DevSecretSeeder` must observe
`StartupEvent` at a higher number (e.g., no `@Priority` = default APPLICATION 2000,
or explicit value > 100). Lower number fires first.

**Nuance #38** — `WorkspaceContext` vs `WorkspaceScopeHolder` ScopedValue binding — TWO SEPARATE INSTANCES, never interchangeable:

| Bound by | Read via | Used in |
|---|---|---|
| `WorkspaceContext.run()` | `WorkspaceContext.userId()`, `.chatId()`, `.requireSessionId()`, `.autonomy()`, `.agentRole()` | All tools, agent loop, business logic |
| `WorkspaceScopeHolder.call()` | `WorkspaceScopeHolder.USER_ID.get()`, `.CHAT_ID.get()` | Reflexes only (`CommandDispatchHandler` path) |

**Rule:** Any class reachable via `AgentDispatchHandler → AgentLoop → ToolRegistry → tool.execute()` MUST use `WorkspaceContext.*()` accessors — NEVER `WorkspaceScopeHolder.USER_ID/CHAT_ID.get()`. Those ScopedValues are NOT bound on the agent execution path.

**Only two legitimate exceptions:**
1. `ToolRegistry.resolveUserId()` — audit drain virtual thread fallback, intentional
2. Reflex classes (`ListCronsReflex`, `DeleteCronReflex`, etc.) — `CommandDispatchHandler` binds `WorkspaceScopeHolder` directly

**Diagnostic:** If `tenant context not bound` or `SESSION_ID is unbound` appears in a tool:
```bash
rg "WorkspaceScopeHolder\.(USER_ID|CHAT_ID|SESSION_ID)\.get" --type java -n
```
Any hit inside a `Tool` class is the bug — replace with `WorkspaceContext.*()` accessor.

**Test rule:** Tests for tools must use `WorkspaceContext.run(...)` as wrapper. Tests for reflexes must use `WorkspaceScopeHolder.call(...)`. To test the unbound/403 path, pass blank userId: `WorkspaceContext.run("session", "", "chat", ...)` — never leave the context entirely unbound in tool tests.