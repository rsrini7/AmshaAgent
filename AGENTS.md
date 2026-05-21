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
