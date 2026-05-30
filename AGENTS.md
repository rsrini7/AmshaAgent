# ArcClaw Agent Developer Guide (AGENTS.md)

Welcome! This document outlines the critical invariants, conventions, and architectural structures of ArcClaw. **All AI coding agents (Gemini, Claude, etc.) must strictly adhere to these rules when editing this codebase.**

---

* **Import Alignment**: After making any modifications to Java source files, you MUST run:
  ```bash
  python3 ./fix_fqns.py          # FQN replacement only (short names + imports)
  ./mvnw spotless:apply -q       # Format + import sorting + remove unused imports
  ```
* **SonarQube Quality Gate**: Before committing, check for BLOCKER/CRITICAL issues:
  ```bash
  mise run sonar:issues                          # all BLOCKER + CRITICAL
  mise run sonar:issues -- -severity BLOCKER     # BLOCKER only
  mise run sonar:issues -component arcclaw-core  # one module
  ```

## 1. Critical Architectural Invariants

### 1.1 The Transaction-Free Zone
* **Rule**: Long-running operations—including LLM calls, external tool execution, and network API requests—**MUST NOT** be executed within an active database transaction.
* **Reason**: SQLite's WAL mode and connection pool configuration will starve or deadlock under concurrent access if connections are held open during high-latency remote tasks. Keep transactions minimal, transactional writes fast, and run all remote tasks in non-transactional contexts.
* **CDI Restriction**: Do not nest `@Transactional(value = TxType.REQUIRES_NEW)` inside active writes.

### 1.2 SQLite WAL & Safety Level
* **Rule**: Do not attempt to alter SQLite safety configurations (such as setting `synchronous=NORMAL`) inside an active transaction. Doing so throws a `SQLiteException` (Safety level may not be changed inside a transaction).

### 1.3 Flyway Migration Parity (SQLite ↔ PostgreSQL)
* **Rule**: Every Flyway migration added to either backend **MUST** have a matching migration in the other backend with the same version number and table semantics.
* **Locations**:
  - SQLite: `arcclaw-persistence-sqlite/src/main/resources/db/migration/sqlite/V<n>__<name>.sql`
  - PostgreSQL: `arcclaw-persistence-postgres/src/main/resources/db/migration/postgresql/V<n>__<name>.sql`
* **Table creation differences**:
  | Column Type | SQLite | PostgreSQL |
  |---|---|---|
  | Auto-increment PK | `INTEGER PRIMARY KEY AUTOINCREMENT` | `BIGSERIAL PRIMARY KEY` |
  | Timestamps | `TEXT NOT NULL` (ISO-8601) | `TIMESTAMP WITH TIME ZONE NOT NULL` |
  | Boolean | `BOOLEAN DEFAULT FALSE` | `BOOLEAN NOT NULL DEFAULT FALSE` |
  | Unique constraint | `UNIQUE(col1, col2)` | `UNIQUE(col1, col2)` (same) |
* **Verification**: After adding a new migration, confirm both files exist and have identical version numbers:
  ```bash
  ls arcclaw-persistence-sqlite/src/main/resources/db/migration/sqlite/V<N>*.sql
  ls arcclaw-persistence-postgres/src/main/resources/db/migration/postgresql/V<N>*.sql
  ```
* **Reason**: Phase 11 (Backend Parity) requires identical semantics across backends. Migration drift between SQLite and PostgreSQL causes silent schema mismatches that only surface when users switch backends.

### 1.3.1 Persistence Backend Selection (`@IfBuildProperty`)

* **How it works**: Every persistence adapter CDI bean uses `@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite"|"postgres"|"fs")` to conditionally activate. Only one backend's beans register at startup.
* **Default**: SQLite — the SQLite adapter uses `enableIfMissing = true`, meaning if the property is unset, SQLite activates. This ensures the binary works out of the box with zero configuration.
* **JVM mode** (`arcclaw serve`): `@IfBuildProperty` is evaluated at **app startup**. Set `ARCCLAW_PERSISTENCE_BACKEND=postgres` in `.env` — **no rebuild needed**.
* **Native image** (`arcclaw-native`): `@IfBuildProperty` is baked into the binary at **build time**. The user must **rebuild** the native image with the new property set during build.
* **Switching to PostgreSQL** (JVM mode):
  ```bash
  echo "ARCCLAW_PERSISTENCE_BACKEND=postgres" >> .env
  echo "QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/arcclaw" >> .env
  echo "QUARKUS_DATASOURCE_USERNAME=arcclaw" >> .env
  echo "QUARKUS_DATASOURCE_PASSWORD=<password>" >> .env
  arcclaw serve
  ```
* **Switching to PostgreSQL** (native image):
  ```bash
  ./mvnw package -Dnative -Darcclaw.persistence.backend=postgres
  ```
* **All three backend jars are in the build** — no Maven exclusion needed. `@IfBuildProperty` selects which CDI beans register.
* **Tiered memory backend** (`arcclaw.memory.tiered.backend`) is a **separate property** — it can differ from the main persistence backend (e.g., main DB on Postgres, tiered memory on SQLite). Both default to `sqlite`.
* **Flyway** runs automatically on startup for whichever backend is active — the user does not manually create tables.
* **Native image binary naming**: Since `@IfBuildProperty` is baked at build time, each backend needs its own native binary:
  | Binary | Backend | Build command |
  |--------|---------|--------------|
  | `arcclaw-sqlite` | SQLite (default) | `./mvnw package -Dnative` |
  | `arcclaw-pg` | PostgreSQL | `./mvnw package -Dnative -Darcclaw.persistence.backend=postgres` |
  | `arcclaw-fs` | Filesystem | `./mvnw package -Dnative -Darcclaw.persistence.backend=fs` |
* **Mise tasks** (recommended): Use `mise run build:native-local` (auto-switches JDK to GraalVM), `mise run build:native-local-pg`, `mise run build:native-local-fs`, or `mise run build:native-local-all` (builds all 3 variants).
* **Release archives** contain all 3 binaries per OS (e.g., `arcclaw-1.0.0-linux-x64.tar.gz` has `arcclaw-sqlite`, `arcclaw-pg`, `arcclaw-fs`).
* **JVM mode is backend-agnostic**: one JAR, switch via `.env` — no separate builds needed.
* **See also**: `docs/Architecture_Invariants.md` §5.24 for the full invariant and §5.24.1/§5.24.2 for binary naming and archive distribution.

### 1.4 Admin REST Resource Pattern
* **Rule**: All new `@Path("/admin/...")` JAX-RS resources in `arcclaw-app` **MUST** follow the existing auth and user-resolution patterns documented below. Do not hardcode userId literals or leave write endpoints unprotected.
* **UserId Resolution**: Copy the `resolveUserId()` pattern from [CronResource.java](./arcclaw-app/src/main/java/com/arcclaw/app/api/CronResource.java) — uses `SecurityContext.getUserPrincipal()` with `WorkspaceContext.getUserIdOrUnknown()` fallback. Never hardcode `"default"`.
  ```java
  @Context SecurityContext securityContext;

  private String resolveUserId() {
      if (securityContext != null && securityContext.getUserPrincipal() != null) {
          return securityContext.getUserPrincipal().getName();
      }
      return WorkspaceContext.getUserIdOrUnknown();
  }
  ```
* **Write Protection**: All mutation endpoints (PUT, POST, DELETE, PATCH) **MUST** be gated behind `isInternal()`:
  ```java
  private boolean isInternal() {
      return securityContext != null && securityContext.isUserInRole("internal");
  }
  ```
  Reference: [PromptAdminResource.java](./arcclaw-app/src/main/java/com/arcclaw/app/api/PromptAdminResource.java).
* **Pattern**: Returns `Response.status(FORBIDDEN).entity("...").build()` when `!isInternal()` for mutations. Reads (GET) remain unprotected.

### 1.5 Heterogeneous Nullability in Sealed Interfaces

* **Rule**: If a sealed interface method returns a nullable wrapper type (`Boolean`, `Integer`, `Optional`, etc.), the Javadoc **MUST** declare `@nullable` and document which permitted subtypes return null. Every consumer **MUST** null-guard the value — never auto-unbox.
* **Reason**: When permitted records implement the same method with different nullability guarantees (e.g., `FinishStep.success()` always returns `true`, `ParallelStep.success()` returns `null`), any consumer that auto-unboxes without a null check gets an NPE on specific execution paths. This is a known anti-pattern called **Heterogeneous Nullability**.
* **Pattern**:
  ```java
  // SAFE — handles null
  if (Boolean.TRUE.equals(step.success())) { ... }

  // SAFE — explicit null check
  if (step.success() != null && step.success()) { ... }

  // UNSAFE — NPE when success() returns null
  if (step.success()) { ... }  // DO NOT DO THIS
  ```
* **Enforcement**: ArchUnit test `ReasoningStepSuccessNullSafetyTest` scans all call sites and rejects raw auto-unboxing of `ReasoningStep.success()`.
* **Reference**: `ReasoningStep.java` sealed interface, `AgentTraceEnricher.onStepComplete()` NPE fix (2026-05-28).

### 1.6 JDK 25 Language Features (GA Only — No Preview)

* **Rule**: ArcClaw targets JDK 25 (GA). Use finalized language features below. **Do NOT use preview features** — they may change or be removed.
* **Enforcement**: Code using preview features will be rejected at review.

#### Use These (Finalized in JDK 21–25)

| Feature | JEP | Since | Use Instead Of |
|---|---|---|---|
| **Unnamed variables `_`** | JEP 456 | JDK 22 | `var unused = ...` → `var _ = ...` for intentionally discarded values |
| **Pattern matching `switch`** | JEP 441 | JDK 21 | `if-else` chains with `instanceof` casts → `switch (obj) { case Type t -> ... }` |
| **Record patterns** | JEP 440 | JDK 21 | Manual field extraction → `case Point(int x, int y) -> ...` |
| **Sealed interfaces** | JEP 409 | JDK 17 | Open hierarchies → `sealed interface X permits A, B, C` |
| **Pattern matching `instanceof`** | JEP 394 | JDK 16 | `if (x instanceof T) { T t = (T) x; }` → `if (x instanceof T t)` |
| **Text blocks** | JEP 378 | JDK 15 | String concatenation for SQL/JSON/HTML → `"""..."""` |
| **Virtual threads** | JEP 444 | JDK 21 | Platform thread pools for I/O-bound work → `Thread.ofVirtual()` |
| **Scoped values** | JEP 481 | JDK 25 | `ThreadLocal` for request-scoped data → `ScopedValue` |
| **Flexible constructors** | JEP 513 | JDK 25 | Workarounds for super() ordering → validate/transform before `super()` |

#### Do NOT Use (Preview in JDK 25 — May Change)

| Feature | JEP | Status | Why Not |
|---|---|---|---|
| Structured concurrency | JEP 505 | Preview | API may change in JDK 26 |
| Stream gatherers | JEP 485 | Preview | API may change |
| Module import declarations | JEP 511 | Preview | Syntax may change |
| Compact source files | JEP 512 | Preview | Main method simplification — not for library code |
| Value classes | JEP 502 | Preview | Memory layout optimization — experimental |

#### Examples

```java
// ✅ Unnamed variable (JDK 22+)
var _ = conn.close();                    // intentionally discarded
var _ = executor.submit(() -> { ... });  // Future intentionally ignored

// ✅ Pattern matching switch (JDK 21+)
return switch (step) {
    case ToolCallStep tc  -> tc.success() != null && tc.success();
    case FinishStep f     -> true;
    case ParallelStep p   -> Boolean.TRUE.equals(p.success());
    case ObservationStep o -> Boolean.TRUE.equals(o.success());
};

// ✅ Pattern matching instanceof (JDK 16+)
if (response instanceof ToolResponse.ToolSuccess s) {
    return s.output();
}

// ❌ DO NOT: unnamed variable with old style
@SuppressWarnings("unused") String unused = conn.close();

// ❌ DO NOT: preview features
import module java.base;  // JEP 511 — preview, do not use
```

---

### 1.7 Continuity Intent vs Follow-Up Heuristics

* **Rule**: Intent classification **MUST** distinguish between explicit continuity commands (which restore the **old** goal) and follow-up heuristics (which preserve the **role** but use the **new** goal input).
* **Continuity commands** (restore old goal): `try again`, `retry`, `do it again`, `once more`, `redo`, `go ahead`, `proceed`, `continue`, `now execute`, `run that`, `execute it`, `run this`
* **Execution follow-ups** (NOT continuity — use NEW goal): `execute`, `run it`, `do it`, `Execute the code`, single-word execution commands like `run java`, `run python`, `run node`, `run go`, `run bash`
* **Enforcement**: `AgentDispatchHandler.resolveGoal()` uses `isExplicitContinuity` (renamed from `isContinuity`). `IntentClassificationService.CONTINUITY_PATTERN` excludes bare execution verbs. `TextPatternUtils.isFollowUpHeuristic()` handles language-specific run commands.
* **Reason**: Execution follow-ups ("run it", "execute") should use the current user message as the new goal, not the old restored goal. Mixing them causes stale context to be restored after compaction, producing confusing results.
* **Reference**: `IntentClassificationService.java` CONTINUITY_PATTERN, `AgentDispatchHandler.java` resolveGoal(), `IntentClassificationServiceTest.java` assertions.

## 2. Key Framework Components

### 2.1 Exception Classification (Chain of Responsibility)
* **Structure**: Exception classification for LLM errors is delegated to [LlmExceptionClassifier.java](./arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/error/LlmExceptionClassifier.java).
* **Pattern**: Follows the Chain of Responsibility pattern via [LlmExceptionClassifierChain.java](./arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/error/LlmExceptionClassifierChain.java).
* **Extending**: To add a new LLM provider or handle specific API errors:
  1. Create a new subclass extending `LlmExceptionClassifierChain`.
  2. Implement `classify(Throwable t)` and delegate unhandled causes using `delegate(t)`.
  3. Prepend or append the new classifier instance in the static block of `LlmExceptionClassifier.java`.
* **Shared Utility**: Use the inherited `isRateLimitError(String)` helper method on the base chain rather than duplicating rate-limit matches.

### 2.2 Channel Inbound Pipelines (Template Method Pattern)
* **Structure**: All external message ingestion handlers (Telegram, Slack, Discord, etc.) must extend [AbstractInboundMessagePipeline.java](./arcclaw-channel-api/src/main/java/com/arcclaw/channel/api/AbstractInboundMessagePipeline.java).
* **Pattern**: Implements standard concurrency serialization (chat-level processing flags/locks), reflex/slash command bypasses, and async delegation boundaries.
* **Implementation Details**:
  * Concrete handlers should parse raw payloads, resolve the `chatId` and `text`, and build standard `ChannelUser` and `ChannelMessage` representations.
  * Delegate execution by calling `processInboundMessage(chatId, text, user, channelMessage)`.

### 2.3 Prompt Template Engine (Hardcoded Switch Pattern)

* **Structure**: Role-specific system prompt rendering is handled by [PromptTemplateEngine.java](./arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/PromptTemplateEngine.java).
* **Pattern**: Uses a hardcoded `switch` statement in `templateFor()` to map `role.id()` → Qute template.
* **Extending**: When adding a new persona:
  1. Create the template file at `templates/prompts/role-<name>.txt`
  2. Add `@Inject @Location("prompts/role-<name>") Template <name>Template` field
  3. Add the corresponding `case "<name>"` entry in `templateFor()`
  * **No compile-time safety** — the switch is a runtime `IllegalStateException` if missing. Always verify.

---

### 2.4 Prompt Registry (Multi-Backend Hexagonal Port)

* **Structure**: All system prompt content blocks (persona identities, role templates, security rules, shared snippets) are stored in a backend-agnostic `PromptRegistry` rather than `static final String` constants or classpath Qute `@Location` templates.
* **Backends**: SQLite, PostgreSQL, or Filesystem — selected via `arcclaw.persistence.backend` config property. Each implements `PromptStorePort` from `arcclaw-persistence-api`.
* **Seed on startup**: `PromptRegistry.onStart(@Observes StartupEvent)` runs after Flyway migration, reads classpath `templates/personas/*.txt` and `templates/prompts/*.txt`, and writes them to the backend if missing. SHA-256 drift detection auto-updates rows on classpath changes but respects admin edits (version>1 or status≠ACTIVE).
* **Graceful degradation**: If no `PromptStorePort` implementation is available (e.g. in unit tests), `get()` falls back to the classpath cache. All DB operations become no-ops.
* **Extending**: When adding a new persona:
  1. Create template file at `templates/prompts/role-<name>.txt`
  2. Create persona identity at `templates/personas/<name>.txt`
  3. Add role ID to `ALL_ROLES` array in `PromptRegistry.java` static block
  4. The registry automatically seeds/detects drift on next startup
  5. NO changes needed to `PromptTemplateEngine.java` hardcoded switch — the registry is tried first, then falls back to `@Location` templates
* **Admin API**: `PUT /admin/prompts/{id}` and `POST /admin/prompts` for runtime edits. Changes with version>1 or non-ACTIVE status are preserved across deployments.

### 2.5 Configuration Property Sync Rule

* **Rule**: When adding or updating any `@ConfigProperty` in a Java source file, you MUST update ALL of the following:
  1. **`application.yaml`** (`arcclaw-app/src/main/resources/application.yaml`) — add the property under the appropriate `arcclaw.*` section with `${ENV_VAR:default}` format
  2. **`.env.example`** (project root) — add the environment variable with a comment describing its purpose
  3. **Module README** (if one exists) — update the configuration table in the module's own `README.md` (e.g. `arcclaw-tool-playwright/README.md`)
* **Reason**: Config properties are documented in three places. Missing any one causes confusion for operators, failing CI builds, or runtime misconfiguration.
* **Verification**: After adding a new config property, run:
  ```bash
  grep "MY_NEW_PROP" .env.example arcclaw-app/src/main/resources/application.yaml
  ```
  to confirm it appears in both files.

### 2.6 Optional Bean Resolution via BeanManager (Reflection Pattern)

* **Rule**: When a bean from an optional Maven dependency (`<optional>true</optional>`) must be accessed reflectively at runtime, use `BeanManager` — NOT `Instance<Object>` or `@Any Instance<Object>`.
* **Reason**: `Instance<Object>` in Quarkus/ArC does not resolve all beans as expected. `@Any Instance<Object>` returns only beans explicitly annotated with `@Any`, not all beans. `BeanManager.getBeans(Object.class)` returns every CDI bean regardless of qualifiers.
* **Pattern** (see `PlaywrightMcpHelper.java` for a working example):
  1. `@Inject BeanManager beanManager`
  2. `beanManager.getBeans(Object.class)` — returns `Set<Bean<?>>` of all beans
  3. Iterate set, check `bean.getBeanClass().getName()` for the target class name
  4. Resolve via `beanManager.getReference(typed, typed.getBeanClass(), creationalContext)`
  5. Verify the resolved object has the expected methods via `getMethod()` before using
* **Verification**: Run the app and grep the log for `[MCP] BeanManager returned N beans total`. If `N` is < 10, bean resolution is broken.

### 2.7 Filesystem Backend + Lucene Memory Retrieval

* **Structure**: The filesystem persistence backend (`arcclaw-persistence-fs`) replaces `MemoryRetrievalPort`'s naive `HashMap` + `String.contains()` with a disk-based Lucene index (`LuceneIndexManager`) for BM25 full-text and optional vector kNN search.
* **Architecture**:
  * JSON files remain the **source of truth** under `{dataDir}/memory-index/*.json`
  * Lucene index under `{dataDir}/lucene-index/` is a **derived index**, rebuilt from JSON on startup via `rebuildFromJson()`
  * Vector search is optional — governed by `Instance<EmbeddingModel>` availability. If the `EmbeddingModel` CDI bean is unsatisfied or dimension mismatches, FS degrades to BM25-only.
  * Hybrid search merges BM25 + vector results via Reciprocal Rank Fusion (RRF, k=60).
* **Lucene Document Schema**:
  * `id` (`StringField`, stored) — unique document ID
  * `content` (`TextField`, stored + indexed) — BM25 search body
  * `metadata_json` (`StoredField`) — JSON-serialized metadata (retrievable, not indexed)
  * `timestamp` (`LongPoint` + `StoredField`) — time-based filtering
  * `embedding` (`KnnFloatVectorField`, COSINE) — optional vector for kNN search
* **Configuration** (`arcclaw.fs.*`):
  * `data-dir` — root data directory (default: `./arcclaw-data`)
  * `lucene-index-dir` — Lucene index subdirectory (default: `lucene-index`; absolute paths supported)
  * `vector-dimension` — expected embedding dimension (default: `768`; validated at runtime)
* **Lifecycle**: `@PostConstruct init()` creates `LuceneIndexManager` + rebuilds from JSON; `@PreDestroy destroy()` closes the Lucene `IndexWriter` and `MMapDirectory`.
* **Extending**: To change indexing behavior:
  1. Modify `LuceneIndexManager.index()` for document schema changes
  2. Modify `LuceneIndexManager.searchBM25()` / `searchVector()` for ranking changes
  3. All retrieval operations go through `MemoryRetrievalPort` — the adapter pattern is unchanged

### 2.8 ToolSearchIndex (Dynamic Tool Discovery)

* **Structure**: `ToolSearchIndex` (`arcclaw-core/.../tools/search/`) is an in-memory Lucene index (`ByteBuffersDirectory`) over all registered agent tools. It indexes tool `name` (StringField) and `description` (TextField) for BM25 relevance matching.
* **Integration**: Wired into `ReasoningMessageAssembler.resolveTools()` to **reorder** (not filter) `ToolSpecification` objects by relevance to the user's goal. All permitted tools remain available — relevant ones are listed first.
* **Query Safety**: On `ParseException` (e.g., `[ref:...]` brackets in goal text), falls back to `QueryParser.escape()` before retrying. Returns empty on total parse failure.
* **Lifecycle**: Rebuilt on startup via `@PostConstruct`. Can be hot-reloaded via public `rebuild()` method.

### 2.9 Role-Based Tool Governance Bypass

* **Structure**: `ToolGovernanceGuard` (`arcclaw-core/.../tools/ToolGovernanceGuard.java`) now has a **role trust pre-flight check** before operation-level policy enforcement.
* **Pattern**:
  ```java
  AgentRoleEnum roleEnum = AgentRoleEnum.fromString(ctx.agentRole());
  boolean isTrustedRole = !roleRegistry.requiresApprovalForTools(roleEnum);
  boolean isSensitiveOp =
          requiredOp == ToolOperation.NETWORK_OUTBOUND || requiredOp == ToolOperation.EXECUTE_SHELL;
  if (isTrustedRole && !isSensitiveOp) {
      return; // trusted role — allow non-sensitive operations
  }
  ```
* **Trusted roles** (those with `requiresApproval=false` in their `RoleDefinition`) bypass operation policy checks for **non-sensitive** operations.
* **Sensitive operations always require HITL**: `NETWORK_OUTBOUND` (external search) and `EXECUTE_SHELL` (code execution) always trigger HITL regardless of role trust.
* **When adding a new `ToolOperation` that should always require HITL**, add it to the `isSensitiveOp` check in `ToolGovernanceGuard.checkPolicy()`.
* **Garuda removed from HITL-exempt tools**: `garuda` is no longer in the `hitl-exempt-tools` list across all config files (`.env.example`, `application.yaml`, `PolicyConfig.java`). The role-based bypass now handles trusted role access instead.
* **Reference**: `ToolGovernanceGuard.java`, `AgentRoleEnum.java` `RoleDefinition.requiresApproval`, `PolicyConfig.hitlExemptTools()`.

### 2.10 EXECUTE_SHELL as Sensitive Tool Operation

* **Rule**: `EXECUTE_SHELL` is classified as a **sensitive tool operation** — alongside `NETWORK_OUTBOUND`. Both always require HITL for trusted roles.
* **Enforcement**: `ToolGovernanceGuard.checkPolicy()` checks `requiredOp == ToolOperation.NETWORK_OUTBOUND || requiredOp == ToolOperation.EXECUTE_SHELL` for the sensitive op gate.
* **Verification**: Run the app and confirm that `sandbox_exec` triggers AWAITING_APPROVAL at SUPERVISED autonomy level even for trusted roles.
* **Reference**: `ToolGovernanceGuard.java` isSensitiveOp check, `ToolOperation.java` enum.

---

### 3.1 No Hardcoded Tool Name Strings
* **Rule**: Never use hardcoded strings like `"sandbox_exec"`, `"github"`, `"schedule_task"`, etc. in Java code logic. Always reference the canonical name via `ToolCapability.ENUM.toolName()`.

### 3.2 Telegram Garbled Line Stripping
* **Rule**: All outbound Telegram messages **MUST** be sanitized via `stripGarbledLines()` before sending. This filters lines where >80% of non-space characters are garbled/control characters (`?`, `…`, em-dashes, non-breaking hyphens, OTHER_PUNCTUATION).
* **Pattern**:
  ```java
  text = stripGarbledLines(text);
  if (text.isBlank()) {
      log.warnf("Attempted to send garbled-only message to %s - skipping", chatId);
      return;
  }
  ```
* **Location**: `TelegramChannelSender.java` `sendMessageInternal()` calls `stripGarbledLines()` at the top of the method, before length-based chunking.
* **Reason**: Free/quantized LLM models sometimes produce spurious control character sequences before real content. Stripping them prevents user-facing garbled output.
* **Reference**: `TelegramChannelSender.stripGarbledLines()`, `TelegramChannelSender.sendMessageInternal()`.

### 3.3 SessionReaper Zombie Recovery — All Channels
* **Rule**: On service restart, `SessionReaper` **MUST** auto-deny ALL stale `AWAITING_APPROVAL` checkpoints for **all** channel types (CLI, Telegram, WebSocket UI), not just CLI.
* **Pattern**:
  ```java
  // After restart, conversation context is stale — auto-deny all pending approvals
  checkpointStore.transitionStatus(dto.id(), CheckpointStatus.DENIED.name());

  // Fire decision event for Telegram/UI to update inline keyboards/SSE streams
  decisionEvents.fireAsync(new ApprovalDecisionEvent(...));
  agentSseService.complete(dto.sessionId());
  ```
* **Behavior**:
  - **CLI**: `cliResultBridge.cancel()` is called to unblock CLI sessions.
  - **Telegram**: An `ApprovalDecisionEvent` is fired so inline keyboard buttons update to show "expired".
  - **UI (WebSocket)**: `agentSseService.complete()` completes the SSE stream so subscribers are notified.
* **Reference**: `SessionReaper.java` zombie recovery step 2, `ApprovalDecisionEvent.java`, `AgentSseService.complete()`.

### 3.4 Saraswati (Researcher) Role Disabled from Delegation
* **Rule**: The `saraswati` researcher role now has `requiresApproval=false` in its `RoleDefinition`, meaning it is **disabled from delegation** — other agents cannot delegate tasks to Saraswati.
* **Location**: `AgentRoleEnum.java` `DEFINITIONS` map for `AgentPersona.SARASWATI`.
* **Reason**: Saraswati's role definition was adjusted to reduce delegation surface area. The researcher role is a read-only information retrieval role; direct delegation to it is unnecessary when all roles have `garuda` for escalation.
* **Reference**: `AgentRoleEnum.java` `DEFINITIONS.put(SARASWATI_ID, ...)`.
* **Reason**: Hardcoded strings silently break when tool names change. The enum is the single source of truth.
* **Exceptions** (not refactorable):
  * `@ToolMetadata(capabilityId = "...")` / `@Tool(name = "...")` annotation attributes (require compile-time constants)
  * `@WithDefault("...")` annotation defaults
  * Javadoc examples referencing tool names for documentation
  * User-input pattern matching (e.g. checking if a user typed "github")
  * LLM prompt instructions (text the model reads, not code identifiers)
  * Cross-module constraint: `ToolCapability` lives in `arcclaw-core`. Only modules with a Maven dependency on `arcclaw-core` can import it. Modules like `arcclaw-channel-api` that don't depend on `arcclaw-core` must keep hardcoded strings.

---

## 4. Maven Build & Dependency Resolution
* **Rule**: Because modules are heavily linked, running single-module tests (e.g. via `-pl`) can fail if dependent jars are out of date in your local repository.
* **Best Practice**: Whenever you modify a class in a dependency module (like `arcclaw-channel-api` or `arcclaw-core`), run a local repository installation:
  ```bash
  ./mvnw clean install -DskipTests
  ```
  This guarantees that all local classpath references are synchronized before executing module tests.

### 4.1 PMD Code Quality Gates
* **File Length**: Maximum **500 lines** per Java source file.
* **Method Length**: Maximum **80 lines** per method.
* **Cognitive Complexity**: Maximum **15** per method (matches SonarQube S3776 threshold).
* **Enforcement**: PMD runs during `verify` phase. Violations are reported as warnings (PMD loads extra rules from categories). SonarQube (`mise run sonar:issues`) is the primary quality gate.
* **Java 25 support**: PMD 7.17+ handles pattern matching `switch`, `when` guards, sealed interfaces, and other modern syntax.
* **Refactoring**: If a method exceeds 80 lines, extract logical blocks into private helper methods. If a file exceeds 500 lines, split into separate classes (e.g., extract inner classes, constants, or utility methods into companion files).

### 4.2 Dependency Version Management
* **Rule**: All third-party dependency versions MUST be declared in the parent `pom.xml`'s `<dependencyManagement>` section using a `<property>` reference. Module `pom.xml` files MUST NOT contain hardcoded `<version>` tags on `<dependency>` entries.
* **Reason**: Centralized version management prevents version drift across modules, ensures consistent transitive dependency resolution, and makes upgrades a single-point change.
* **Pattern**:
  1. Add a `<property>` in parent `pom.xml` `<properties>`: `<my-lib.version>1.2.3</my-lib.version>`
  2. Add the dependency to parent `<dependencyManagement>`: `<version>${my-lib.version}</version>`
  3. Reference in module `pom.xml` WITHOUT `<version>` — it inherits from the parent
* **Exceptions**:
  * Plugin versions (`<plugin>` blocks) are managed separately via `<pluginManagement>`
  * Internal module dependencies use `${project.version}` (already in `<dependencyManagement>`)
  * BOM imports (`<type>pom</type><scope>import</scope>`) are versioned via their own properties
* **Verification**: After adding a new dependency, run:
  ```bash
  # No hardcoded versions in dependency blocks (only plugin versions are OK)
  python3 -c "
  import xml.etree.ElementTree as ET, glob
  for f in glob.glob('arcclaw-*/pom.xml'):
      tree = ET.parse(f); root = tree.getroot()
      ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
      for dep in root.findall('.//m:dependencies/m:dependency', ns):
          v = dep.find('m:version', ns)
          if v is not None and v.text and not v.text.startswith('\${'):
              print(f'{f}: {dep.find(\"m:artifactId\",ns).text}:{v.text}')
  "
  ```

### 4.3 PostgreSQL Local Service Conflict

* **Problem**: A native PostgreSQL installed via `brew`, `apt`, `dnf`, `choco`, or the official installer binds to `127.0.0.1:5432`, intercepting connections intended for the Podman container's PostgreSQL. This causes `FATAL: role "arcclaw" does not exist` errors because the native instance doesn't have the ArcClaw user.
* **Detection**: Run `lsof -i :5432`. If you see both `gvproxy` (Podman gateway) and `postgres` (native), you have a conflict.
* **Fix (macOS/Homebrew)**:
  ```bash
  brew services stop postgresql@16
  brew uninstall postgresql@16
  rm -rf /opt/homebrew/var/postgresql@16
  ```
* **Fix (Linux/apt)**:
  ```bash
  sudo systemctl stop postgresql
  sudo apt remove postgresql
  ```
* **Fix (Windows)**:
  ```powershell
  net stop postgresql-x64-16
  # Or uninstall via "Add or Remove Programs"
  ```
* **Verification**: Run `mise run jar:pg -- serve` and check `psql -h 127.0.0.1 -U arcclaw -d arcclaw -c "SELECT 1;"` succeeds.

## 5. Adding a New LLM Provider

When adding a new LLM provider (e.g., llama.cpp, OMLX, etc.), update ALL of the following:

### 5.1 Backend (Java)
1. **`RoutingChatModelProvider`** — `arcclaw-core/.../routing/<Name>LlmProviderFactory.java` (for intent/compaction)
2. **`AgentChatModelProvider`** — `arcclaw-core/.../agent/reasoning/<Name>ProviderFactory.java` (for infinite-loop agent)
3. **`LlmModelFactory.java`** — Add URL pattern to `URL_PROVIDER_MAP` (e.g., `"localhost:9000", "llamacpp"`)
4. **`OnboardingResource.java`** — Add default model to `DEFAULT_MODELS` map

### 5.2 Configuration (.env)
5. **`.env`** — Add `ARCCLAW_<NAME>_BASE_URL` and `ARCCLAW_<NAME>_MODEL`
6. **`.env`** — Add provider to `ARCCLAW_INFINITE_LOOP_MODELS_LIST`
7. **`.env.example`** — Same changes for documentation

### 5.3 Frontend (UI)
8. **`arc-settings.ts`** — Add entry to `PROVIDERS` array
9. **`arc-onboarding-step2.ts`** — Add entry to `providers` array

### 5.4 Verification
10. Compile: `./mvnw compile -DskipTests -q`
11. Check UI builds: `cd arcclaw-ui && npm run build`

## 6. Adding a New Persona (Agent Role)

When adding a new persona (e.g., Garuda, Indra, etc.), update ALL of the following:

### 6.1 Core Definitions
1. **`AgentPersona.java`** — Add enum entry with `roleId`, `displayName`, and `aliases`
2. **`AgentRoleEnum.java`** — Add `static final` ID constant, instance, and `RoleDefinition` in `DEFINITIONS` map (use `Map.ofEntries()` if >10 entries)

### 6.2 Templates (Qute)
3. **`templates/personas/<name>.txt`** — Create persona identity template (who they are, responsibilities, rules)
4. **`templates/prompts/role-<name>.txt`** — Create role-specific prompt section (PERMITTED TOOLS, rules, includes)

### 6.3 CDI & Wiring
5. **`AgentProducer.java`** — Add `@Produces @ApplicationScoped @AgentRole(ID)` method
6. **`CoordinatorPromptSection.java`** — Add to Trimurti/Tridevi architecture block
7. **`PromptTemplateEngine.java`** — Add `@Inject @Location("prompts/role-<name>") Template <name>Template` field and corresponding `case "<name>"` entry in the `templateFor()` switch

### 6.4 Delegation (CRITICAL - Static Lists, No Compile Safety)
8. **`DelegateToAgentTool.java`** — Add role ID to `defaultRoles` in `@ToolMetadata` annotation
9. **`templates/prompts/delegate-to-agent-rules.txt`** — Add to "Available Specialists" list

### 6.5 Escalation & Routing
10. **`PersonaEscalationService.java`** — Add escalation paths in `determineNewPersonaEscalation()`
11. **`PromptInstructionLibrary.java`** — Add to system capabilities list

### 6.6 Configuration
12. **`application.yaml`** — Add role entry under `arcclaw.agents.roles`

### 6.7 Verification
13. Compile: `./mvnw compile -DskipTests -q`
14. Run tests: `./mvnw test -pl arcclaw-core -Dtest="AgentRoleTest,PersonaEscalationServiceTest"`
15. Update `telegram-test-arcclaw.txt` with test cases for new persona

**WARNING**: Items 8 and 9 are static lists in annotations/templates. Item 7 (`PromptTemplateEngine.java`) is a hardcoded switch — none of these have compile-time safety. Always verify manually.

---

## 7. GraalVM Native-Image Reflection Registration

When adding new Java files, you MUST evaluate whether they need `@RegisterForReflection`. JVM-mode success does NOT prove native-image safety.

### 7.1 What Needs `@RegisterForReflection`

| Class Type | Needs Registration? | Why |
|---|---|---|
| **`record` (DTOs)** | **YES** | JDBI `@RegisterConstructorMapper` and Jackson deserialize via reflection |
| **JDBI DAO interfaces** | **YES** | JDBI creates proxy instances via reflection |
| **JDBI inner `*DtoRow` classes** | **YES** | Row mapping uses reflection |
| **CDI beans (`@ApplicationScoped`)** | **Only in persistence modules** | Quarkus ArC auto-handles CDI beans in most modules; persistence adapters need explicit registration because JDBI reflection bypasses ArC |
| **Enums** | **NO** | Quarkus automatically registers enums used in CDI/config |
| **`@ConfigMapping` interfaces** | **NO** | Quarkus config extension handles these |
| **Classes only used via CDI injection** | **NO** (except persistence) | ArC generates the necessary metadata |
| **`@Qualifier` annotations** | **NO** | Processed at build time |
| **`AnnotationLiteral` classes** | **NO** | Used programmatically, not reflectively |
| **Static utility classes** | **NO** | No reflection involved |

### 7.2 Where to Add Registration

Each module has a `*ReflectionConfig.java` in its main package:

| Module | Config File |
|---|---|
| `arcclaw-persistence-api` | `TieredMemoryReflectionConfig.java` |
| `arcclaw-persistence-sqlite` | `SqlitePersistenceReflectionConfig.java` |
| `arcclaw-persistence-postgres` | `PostgresPersistenceReflectionConfig.java` |
| `arcclaw-persistence-fs` | `FsReflectionConfig.java` |
| `arcclaw-core` | `CoreReflectionConfig.java` |
| `arcclaw-app` | `AppReflectionConfig.java`, `ChannelReflectionConfig.java` |
| `arcclaw-channel-telegram` | `TelegramReflectionConfig.java` |
| `arcclaw-onboarding` | `OnboardingReflectionConfig.java` |
| `arcclaw-mcp` | `McpReflectionConfig.java` |
| `arcclaw-rag` | `RagReflectionConfig.java` |

### 7.3 Checklist for New Files

When adding a new Java file, ask:

1. **Is it a `record` used in JSON serialization or JDBI mapping?** → Add to the module's `*ReflectionConfig.java`
2. **Is it a JDBI DAO interface (annotated with `@SqlQuery`, `@SqlUpdate`)?** → Add to the module's `*ReflectionConfig.java`
3. **Is it a persistence adapter (`@ApplicationScoped` in `arcclaw-persistence-*`)?** → Add to the module's `*ReflectionConfig.java`
4. **Is it a CDI bean in a non-persistence module?** → No registration needed (ArC handles it)
5. **Is it an enum, config mapping, or qualifier?** → No registration needed

### 7.4 Verification

After adding a new class to a reflection config, run:
```bash
python3 ./fix_fqns.py                    # Align imports
./mvnw compile -DskipTests -q             # Compilation passes
```

For native-image verification:
```bash
mise build:native-local                   # Full native build
```

### 7.5 Native-Image Runtime Initialization (`native-image.properties`)

Some libraries use Foreign Function & Memory (FFM) API, `MethodHandle`, or native access that GraalVM can't analyze at build time. These must be deferred to runtime via `--initialize-at-run-time`.

**Config file:** `arcclaw-app/src/main/resources/META-INF/native-image/com.arcclaw/arcclaw-app/native-image.properties`

**When to add a class:**
- GraalVM error: `should not reach here: unexpected input could not be handled: linkToNative` → class uses FFM API
- GraalVM error: `An object of type X was found in the image heap. This type is marked for initialization at run time` → class is instantiated at build time but needs to be runtime-only
- GraalVM error: `Fatal error: com.oracle.graal.pointsto.util.AnalysisError$ParsingError` on native methods → class uses native/FFM intrinsics

**Critical rule — mark the ENTIRE package, not individual classes:**
GraalVM embeds static fields from build-time classes into the image heap. If you mark `ClassA` for run-time init but `ClassB` (build-time) has a static `ClassA` field, GraalVM errors. You'd need to also mark `ClassB`, then its dependents, endlessly. **Always mark the root package** to break the chain:

```properties
# ✅ CORRECT — marks entire package + sub-packages
--initialize-at-run-time=org.apache.lucene

# ❌ WRONG — triggers whack-a-mole (each class's static deps cause new errors)
--initialize-at-run-time=org.apache.lucene.store.PosixNativeAccess
--initialize-at-run-time=org.apache.lucene.store.MemorySegmentIndexInputProvider
```

**Also mark your own classes that trigger the library at build time:**
CDI `@PostConstruct` methods run at build time in native-image. If your bean initializes a library in `@PostConstruct`, mark the bean for run-time init too:

```properties
--initialize-at-run-time=com.arcclaw.persistence.fs.FsMemoryRetrievalAdapter,com.arcclaw.persistence.fs.lucene.LuceneIndexManager
```

**Do NOT put `--initialize-at-run-time` in `pom.xml` `additional-build-args`:**
Quarkus splits that property on commas. If the arg value contains commas (e.g., `--initialize-at-run-time=A,B`), it gets split into two broken args. Use `native-image.properties` instead — GraalVM reads it directly with no comma splitting.

### 7.6 Foreign Function Registration (`reachability-metadata.json`)

Libraries that use the FFM API (Foreign Function & Memory) for native downcalls require explicit registration in `reachability-metadata.json`. GraalVM cannot analyze FFM calls at build time and throws `MissingForeignRegistrationError` at runtime.

**Config file:** `arcclaw-app/src/main/resources/META-INF/native-image/com.arcclaw/arcclaw-app/reachability-metadata.json`

**When to add a function:**
- GraalVM error: `MissingForeignRegistrationError: Cannot perform downcall with leaf type ...` → register the function's native signature

**Current registrations:**
- `posix_madvise` — Lucene `PosixNativeAccess` calls this via FFM for memory-mapped file advice
- `getpagesize` — Lucene `PosixNativeAccess` calls this for page size detection

**Format:**
```json
{
  "foreign": [
    {
      "downcalls": [
        {
          "name": "posix_madvise",
          "parameterTypes": ["long", "long", "long", "int"],
          "returnType": "int"
        }
      ]
    }
  ]
}
```

**Note:** The leaf type in the error message may not directly match the C function signature — GraalVM decomposes `MemorySegment` parameters into `(address, size)` pairs. Always use the exact leaf type from the error message.

**Verification:**
```bash
mise build:native-local    # Must complete without heap/init errors
```
