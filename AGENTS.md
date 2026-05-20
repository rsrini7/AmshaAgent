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

## 3. Maven Build & Dependency Resolution
* **Rule**: Because modules are heavily linked, running single-module tests (e.g. via `-pl`) can fail if dependent jars are out of date in your local repository.
* **Best Practice**: Whenever you modify a class in a dependency module (like `arcclaw-channel-api` or `arcclaw-core`), run a local repository installation:
  ```bash
  ./mvnw clean install -DskipTests
  ```
  This guarantees that all local classpath references are synchronized before executing module tests.
