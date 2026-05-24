# ArcClaw Agent Developer Guide (AGENTS.md)

Welcome! This document outlines the critical invariants, conventions, and architectural structures of ArcClaw. **All AI coding agents (Gemini, Claude, etc.) must strictly adhere to these rules when editing this codebase.**

---

* **Import Alignment**: After making any modifications to Java source files, you MUST run the import organization and FQN sorting script to align imports:
  ```bash
  python3 ./fix_fqns.py
  ```

## 1. Critical Architectural Invariants

### 1.1 The Transaction-Free Zone
* **Rule**: Long-running operations—including LLM calls, external tool execution, and network API requests—**MUST NOT** be executed within an active database transaction.
* **Reason**: SQLite's WAL mode and connection pool configuration will starve or deadlock under concurrent access if connections are held open during high-latency remote tasks. Keep transactions minimal, transactional writes fast, and run all remote tasks in non-transactional contexts.
* **CDI Restriction**: Do not nest `@Transactional(value = TxType.REQUIRES_NEW)` inside active writes.

### 1.2 SQLite WAL & Safety Level
* **Rule**: Do not attempt to alter SQLite safety configurations (such as setting `synchronous=NORMAL`) inside an active transaction. Doing so throws a `SQLiteException` (Safety level may not be changed inside a transaction).

---

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

---

## 3. Generic Coding Principles

### 3.1 No Hardcoded Tool Name Strings
* **Rule**: Never use hardcoded strings like `"sandbox_exec"`, `"github"`, `"schedule_task"`, etc. in Java code logic. Always reference the canonical name via `ToolCapability.ENUM.toolName()`.
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

### 4.1 Checkstyle Restrictions
* **File Length**: Maximum **500 lines** per Java source file.
* **Method Length**: Maximum **80 lines** per method.
* **Enforcement**: Checkstyle runs during `compile` phase. Violations fail the build.
* **Refactoring**: If a method exceeds 80 lines, extract logical blocks into private helper methods. If a file exceeds 500 lines, split into separate classes (e.g., extract inner classes, constants, or utility methods into companion files).

### 4.2 PostgreSQL Local Service Conflict

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
