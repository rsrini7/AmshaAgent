# ArcClaw — Testing & Verification Guide

> This guide covers all testing and verification workflows for ArcClaw. Complete [SETUP.md](./SETUP.md) before running any section here.

---

> **Docs**: [README](./README.md) · [Setup](./SETUP.md) · [Telegram Testing](./TELEGRAM_TESTING.md) · [FAQ](./FAQ.md) · [REST Client Files](./rest-http/)

---

## Table of Contents

11. [Unit & Integration Test Suite](#11-unit--integration-test-suite)
12. [E2E REST API Workflow](#12-e2e-rest-api-workflow)
13. [Distributed Tracing (Jaeger)](#13-distributed-tracing-jaeger)
14. [Sandbox Isolation Tier Testing](#14-sandbox-isolation-tier-testing)
15. [Secure Secret Passing Verification](#15-secure-secret-passing-verification)
16. [Context Compaction Test](#16-context-compaction-test)
17. [SQLite FTS5 & Agent Memory RAG Testing](#17-sqlite-fts5--agent-memory-rag-testing)
18. [Telegram Bot Integration & Verification](#18-telegram-bot-integration--verification)
19. [API Key Generation Reference](#19-api-key-generation-reference)
20. [Final Verification Checklist](#20-final-verification-checklist)
21. [Troubleshooting](#21-troubleshooting)

> Section numbers start at 11 to continue from [SETUP.md](./SETUP.md) which covers Sections 1–10.

---

## 11. Unit & Integration Test Suite

### 11.1 Full Test Suite

Runs all 150+ unit and integration tests across all modules using Surefire and Failsafe.

```bash
# macOS / WSL2
./mvnw clean verify -pl arcclaw-app -am

# Windows
.\mvnw.cmd clean verify -pl arcclaw-app -am
```

Mockito inline mock attachment works automatically because the required `--add-opens` JVM flags are embedded in `arcclaw-app/pom.xml`.

### 11.2 Run Specific Tests

```bash
# macOS / WSL2
./mvnw test -pl arcclaw-app -am \
  "-Dtest=ApiKeyAuthFilterTest,StartupSecurityValidatorTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"

# Windows
.\mvnw.cmd test -pl arcclaw-app -am `
  "-Dtest=ApiKeyAuthFilterTest,StartupSecurityValidatorTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

Running only a specific module without rebuilding everything:

```bash
# Run only arcclaw-core tests
mvn test -pl arcclaw-core

# Run only arcclaw-app integration tests
mvn verify -pl arcclaw-app -am

# Run a single test class
mvn test -pl arcclaw-core -Dtest=SandboxCircuitBreakerTest

# Run with LLM debug logging enabled
mvn verify -pl arcclaw-app -am -Darcclaw.debug.llm=true
```

### 11.3 Key Test Classes Reference

Tests live in two modules:

- **`arcclaw-core/src/test`** — pure unit and integration tests with zero Quarkus startup. Covers agent loop state machines, reasoning engine, reflex system, sandbox circuit breaker, secret vault, context compaction, FTS5 search, audit HMAC, governance budget, workspace lifecycle, and tool implementations.
- **`arcclaw-app/src/test`** — `@QuarkusTest` integration tests that start the full CDI context. Covers REST API end-to-end, Telegram route startup and routing, API key auth, rate limiting, security validators, and scheduler concurrency.

Both test modules use an **in-memory SQLite** database (`:memory:`) so no file cleanup is required between tests.

| Test Class | Module | What It Covers |
|---|---|---|
| `ServeCommandTest` | `arcclaw-app` | Serve mode startup and port binding |
| `TelegramAgentHandlerTest` | `arcclaw-app` | Telegram message handler, ack behaviour |
| `TelegramChannelRouteStartupTest` | `arcclaw-app` | Apache Camel Telegram route registration on startup |
| `TelegramChannelRouteTest` | `arcclaw-app` | Apache Camel Telegram route message flow |
| `TelegramChannelSenderTest` | `arcclaw-app` | Telegram message sending, MarkdownV2 |
| `TelegramMarkdownV2RendererTest` | `arcclaw-app` | MarkdownV2 escaping for all special characters |
| `ChatCommandApprovalTest` | `arcclaw-app` | CLI chat REPL HITL approval flow |
| `DenyPipelineTest` | `arcclaw-app` | MessagePipeline deny path |
| `IntentClassifierServiceTest` | `arcclaw-app` | Role classification logic |
| `AgentCronSchedulerTest` | `arcclaw-app` | Scheduled cron job execution |
| `AgentCronSchedulerConcurrencyTest` | `arcclaw-app` | Concurrent cron scheduling under virtual threads |
| `CronResultHandlerTest` | `arcclaw-app` | Cron result delivery and observer events |
| `ApiKeyAuthFilterTest` | `arcclaw-app` | REST API key authentication filter |
| `ApiKeyRegistryTest` | `arcclaw-app` | Key registry lookup, timing safety |
| `ApiKeyRegistryVaryingLengthKeyTest` | `arcclaw-app` | Constant-time comparison with variable length keys |
| `DestructiveVocabularyFilterTest` | `arcclaw-app` | Shell destructive command vocabulary blocking |
| `StartupSecurityValidatorTest` | `arcclaw-app` | Production safety validation on boot |
| `StartupSecurityValidatorProdSafeTest` | `arcclaw-app` | Validator passes on valid prod secrets |
| `StartupSecurityValidatorProdUnsafeTest` | `arcclaw-app` | Validator aborts on weak prod secrets |
| `StartupSecurityValidatorDevExemptTest` | `arcclaw-app` | Validator warns but continues in dev mode |
| `StartupSecurityValidatorUnknownProfileTest` | `arcclaw-app` | Validator behaviour on unknown profiles |
| `StartupSecurityValidatorStringOnlyAllowlistTest` | `arcclaw-app` | Username-only allowlist validation |
| `TelegramAllowlistGuardOpenAccessTest` | `arcclaw-app` | Empty allowlist (open access) mode |
| `TelegramAllowlistGuardRestrictedTest` | `arcclaw-app` | Restricted allowlist enforces block |
| `AgentResourceTest` | `arcclaw-app` | REST API end-to-end (create session, run, status, delete) |
| `AuditServiceIntegrationTest` | `arcclaw-app` | Immutable HMAC audit chain integrity |
| `PodmanHealthCheckTest` | `arcclaw-app` | Podman socket liveness health check |
| `WorkspaceScopeTest` | `arcclaw-app` | Custom CDI `@WorkspaceScoped` lifecycle |
| `SecretVaultImplTest` | `arcclaw-core` | AES-256-GCM-SIV encryption/decryption |

### 11.4 Test Profile Notes

Tests use the `%test` profile from `arcclaw-app/src/test/resources/application.yaml`. Key differences from `%dev`/`%prod`:

- Sandbox strategy is always `NONE` — no container execution during tests.
- An in-process, in-memory SQLite database is used (`:memory:`).
- LLM calls are mocked via Mockito `@InjectMock` — no real API calls are made.

### 11.5 Why `--add-opens` JVM Flags Are Required

Mockito's inline mock agent (used for mocking final classes and static methods) requires reflective access to JVM internals that Java 17+ restricts by default. The `--add-opens` flags in `arcclaw-app/pom.xml` grant Mockito the access it needs to attach the byte-buddy agent. These flags are scoped to `<argLine>` in the Surefire plugin and do not affect production code.

---

## 12. E2E REST API Workflow

These steps verify the full request path: REST → AgentLoop → LLM → SQLite checkpoint.

### Prerequisites

Ensure the application is running in serve mode (dev or packaged jar — see [SETUP.md Section 7](./SETUP.md#7-common-run-modes)).

```bash
# Quickest way to start
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"
```

### Step 1 — Create a Session

```bash
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "X-ArcClaw-API-Key: key-internal-changeme"
```

Expected response:

```json
{"sessionId":"abcd-1234-efgh-5678"}
```

Set the session ID for subsequent commands:

```bash
# Linux / macOS
export SESSION_ID="abcd-1234-efgh-5678"

# Windows PowerShell
$SESSION_ID="abcd-1234-efgh-5678"
```

### Step 2 — Run an Agent (Asynchronous)

Delegate a task to the Vishnu coordinator agent. The endpoint is non-blocking and returns immediately with `202 Accepted`.

```bash
# Linux / macOS
curl -X POST "http://localhost:8080/api/v1/sessions/$SESSION_ID/run-async" \
  -H "Content-Type: application/json" \
  -H "X-ArcClaw-API-Key: key-internal-changeme" \
  -d '{
        "goal": "Analyze the arcclaw repository on GitHub and tell me what it does",
        "agentRole": "coordinator",
        "maxIterations": 3
      }'

# Windows PowerShell
curl -X POST "http://localhost:8080/api/v1/sessions/$SESSION_ID/run-async" `
  -H "Content-Type: application/json" `
  -H "X-ArcClaw-API-Key: key-internal-changeme" `
  -d '{ "goal": "Analyze the arcclaw repository on GitHub and tell me what it does", "agentRole": "coordinator" }'
```

Expected response: `HTTP 202 Accepted`

Valid `agentRole` values: `coordinator`, `researcher`, `coder`, `debugger`, `architect`, `synthesizer` (and their deity aliases: `vishnu`, `saraswati`, `brahma`, `shiva`, `dattatreya`).

### Step 3 — Poll Session Status

Poll repeatedly to watch the agent's progress. The `steps` array shows the live Thought → Action → Observation trace.

```bash
# Linux / macOS
curl "http://localhost:8080/api/v1/sessions/$SESSION_ID/status/coordinator" \
  -H "X-ArcClaw-API-Key: key-internal-changeme"

# Windows PowerShell
curl "http://localhost:8080/api/v1/sessions/$SESSION_ID/status/coordinator" `
  -H "X-ArcClaw-API-Key: key-internal-changeme"
```

Expected response: JSON payload with `status` field (`IN_PROGRESS`, `AWAITING_APPROVAL`, `COMPLETED`, or `FAILED`) and a `steps` array showing the agent's thoughts and actions.

### Step 4 — Read the Terminal Logs

A successful async run produces this exact log sequence, matching the architecture diagram:

1. `Spawning virtual thread for async agent run, session: <id>` — HTTP worker offloads to a JDK 25 Virtual Thread.
2. `AgentLoop starting for goal... (maxIterations=5)` — `@WorkspaceScoped` session activates.
3. `HTTP request: - url: https://api.groq.com/openai/v1/chat/completions` — LangChain4J calls Groq.
4. `Agent reached FINISH at iteration 1` — LLM signals completion.
5. `Created checkpoint for session=<id> role=coordinator` then `Checkpoint id=2 marked COMPLETED` — Hibernate ORM writes the reasoning trajectory to SQLite.

### Using the REST Client Files (IntelliJ / VSCode)

Instead of manual `curl` commands, use the provided files in [`rest-http/`](./rest-http/):

1. Open any `.http` file in IntelliJ IDEA or VSCode (with the REST Client extension).
2. Run the first `POST /api/v1/sessions` request — it automatically captures the `sessionId` as a variable using the `# @name <varName>` annotation.
3. Run subsequent requests without copying IDs manually — the client resolves `{{varName.response.body.sessionId}}` automatically.

See [`rest-http/README.md`](./rest-http/README.md) for a full index of all 10 numbered files.

---

## 13. Distributed Tracing (Jaeger)

ArcClaw emits OTLP spans for every REST request, agent loop iteration, and tool execution via the OTel Collector.

**Steps to view traces:**

1. Ensure the compose stack is running (`docker compose up -d`).
2. Trigger any agent run via the REST API (see [Section 12](#12-e2e-rest-api-workflow)).
3. Open the Jaeger UI: [http://localhost:16686](http://localhost:16686)
4. In the **Service** dropdown, select `arcclaw`.
5. Click **Find Traces**.
6. Click an individual trace to see the full span hierarchy: HTTP ingress → AgentLoop → LLM call → Sandbox tool execution → SQLite checkpoint.

> Note: In `%dev` mode, OTEL export is disabled (`quarkus.otel.sdk.disabled=true`). Observe tracing via log output (`AgentOtelContext`) instead.

---

## 14. Sandbox Isolation Tier Testing

These tests verify that each of the three sandbox tiers is working as designed.

### The Windows / WSL2 Boundary Trap

> **Critical**: If you are developing on Windows and want to verify Tier 1 (Podman rootless), you **cannot** run the `.jar` in PowerShell. `ProcessIsolationExecutor` checks `System.getProperty("os.name")`. If it detects `Windows`, it bypasses Linux namespaces and falls directly to host execution — regardless of your strategy configuration.
>
> Additionally, **Podman Desktop runs in its own isolated WSL VM** (`podman-machine-default`). Your Ubuntu WSL distribution cannot see Podman Desktop's containers. Do not try to bridge them.
>
> **The correct approach**: Install Podman natively inside your Ubuntu WSL distribution (see [SETUP.md Section 3](./SETUP.md#3-wsl2-ubuntu-setup)) and run the jar from inside Ubuntu.

### 14.1 Understanding Profile vs. Strategy

| Execution Mode | Active Profile | Expected Sandbox Strategy |
|---|---|---|
| `./mvnw quarkus:dev ...` | `%dev` | `NONE` — direct host execution, hardcoded for developer velocity |
| `java -jar quarkus-run.jar serve` | `%prod` | `PODMAN` — full rootless container isolation |
| `java -Dquarkus.profile=prod -jar ...` | `%prod` | `PODMAN` — forces prod profile from any terminal |

To force `%prod` mode for local sandbox testing without the compose stack:

```bash
java -Dquarkus.profile=prod \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve
```

### 14.2 The Circuit Breaker Behaviour

`ProcessIsolationExecutor` determines its capability **exactly once at startup**:
- It runs `podman --version` during the first 5 seconds of boot.
- If Podman is not running or not on the `PATH` at that moment, the circuit breaker **trips permanently** for that JVM session.
- Starting Podman after the application has booted will **not** fix it — you must restart ArcClaw.

### 14.3 Tier 1 — Full Podman Isolation Test (Linux / WSL2 Native)

**Prerequisites**: Podman installed natively in Linux/WSL2, `arcclaw-tool-runner:latest` image built.

**Step 1** — Build the tool runner image inside Ubuntu (if not done already):

```bash
podman build -t arcclaw-tool-runner:latest -f sandbox/Dockerfile.toolrunner sandbox/
```

**Step 2** — Build and run the jar in prod mode inside Ubuntu:

```bash
./mvnw clean package -pl arcclaw-app -am -DskipTests
java -Dquarkus.profile=prod \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve
```

**Step 3** — Verify via boot logs. In the **first 50 lines** of console output:

```
✅  INFO: Isolation strategy: PODMAN initialized.        → Full Security — Tier 1 active
⚠️  WARN: Podman not available — circuit open, falling back...  → Degraded — Tier 2 or 3
```

**Step 4** — Trigger a GitHub tool call via REST or Telegram. Watch the logs: ArcClaw should launch an ephemeral `arcclaw-tool-runner` container, execute `gh`, and tear it down — all within a single tool call.

### 14.4 Tier 2 — Linux Namespace Fallback Test

To force the circuit breaker open for testing purposes:

```bash
# Stop Podman before starting ArcClaw
systemctl --user stop podman.socket

java -Dquarkus.profile=prod -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve

# Boot log should show:
# WARN: Podman not available — circuit open, falling back to NAMESPACE strategy.
```

Trigger a tool call — it should execute using `unshare` namespace isolation instead of a container.

### 14.5 Tier 3 — Direct Execution Test (Dev Mode)

This is the default in `%dev`. No special setup needed:

```bash
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"
```

Ensure `gh` is on your `PATH` and authenticated. Trigger a GitHub tool call — it should execute directly on the host without any container wrapping.

### 14.6 Automated Sandbox Tests

Run the unit tests that cover circuit breaker and namespace logic without needing a live Podman daemon:

```bash
./mvnw test -pl arcclaw-core -am \
  "-Dtest=SandboxCircuitBreakerTest,ProcessIsolationExecutorTest,TmpfsSecretTransportTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

---

## 15. Secure Secret Passing Verification

ArcClaw uses a zero-string security model: secrets are injected into tool processes via a tmpfs mount and memory-wiped immediately after use. This section verifies that model is working.

### 15.1 Production Tmpfs Verification (Linux / WSL2)

```bash
# Verify the tmpfs mount is active
mount | grep arcclaw-secrets
# Expected: tmpfs on /run/arcclaw/secrets type tmpfs (rw,nosuid,nodev,noexec,mode=700,size=10240k,...)

# Before a tool call, the directory should be empty
ls -la /run/arcclaw/secrets
```

Trigger an agent tool call that requires secrets (e.g. the GitHub tool via a `researcher` role query). During execution, the `.env` file will momentarily appear in `/run/arcclaw/secrets` and be deleted as soon as the container process exits.

### 15.2 Development Fallback Verification

If running in `%dev` mode or without root access for the tmpfs mount, the application falls back to `/tmp`. Check the logs for:

```
INFO  tmpfs path /run/arcclaw/secrets is inaccessible. Falling back to /tmp/...
```

### 15.3 Automated Memory Security Tests

```bash
# macOS / WSL2
./mvnw test -pl arcclaw-core -am \
  "-Dtest=TmpfsSecretTransportTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"

# Windows
.\mvnw.cmd test -pl arcclaw-core -am `
  "-Dtest=TmpfsSecretTransportTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected test outcomes:
- `testLifecycle` — verifies the secret file is created before tool execution and automatically deleted after.
- `testZeroCopyWrite` — verifies that the `byte[]` holding the secret is zeroed out in memory after the write operation completes.

---

## 16. Context Compaction Test

Context compaction fires automatically when a session accumulates more than the configured threshold of messages (default: compaction triggers when the rolling window exceeds `max-messages - keep-recent`, i.e. 20 - 5 = 15). It summarises older history via LLM, writes the summary to SQLite and the FTS5 index, and trims the in-memory chat window.

### 16.1 Manual Trigger Test

```bash
# Start the application
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"
```

Send **17 messages** in a single REST session or Telegram session — any content. Then verify the compaction fired.

**In the application logs, look for:**

```
INFO  Context compacted for session <id>. Compacted N messages, kept M.
```

**Verify the SQLite records directly:**

```bash
# Check compaction_summary was written
sqlite3 data/arcclaw-meta.db "SELECT * FROM compaction_summary;"
# Expected: 1 row with sessionId, summary text, and compactedAt timestamp

# Verify the audit event was recorded
sqlite3 data/arcclaw-meta.db \
  "SELECT eventType, sessionId, timestamp FROM audit_event WHERE eventType = 'CONTEXT_COMPACTED';"
# Expected: 1 row

# Inspect the reasoning step trajectory
sqlite3 data/arcclaw-meta.db \
  "SELECT * FROM reasoning_step ORDER BY checkpoint_id, sequence_num;"
```

### 16.2 Automated Compaction Tests

```bash
./mvnw test -pl arcclaw-core -am \
  "-Dtest=ContextCompactionServiceTest,ContextCompactionServiceIntegrationTest,CompactionSummaryRepositoryTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

---

## 17. SQLite FTS5 & Agent Memory RAG Testing

The `MemorySearchTool` (used by `coordinator`, `researcher`, `architect`, and `synthesizer` roles) queries a dedicated `arcclaw-fts.db` FTS5 full-text search database built from compaction summaries. This section verifies the schema, tenant isolation, and LLM orchestration.

### 17.1 Step 1 — Verify the FTS5 Schema on Boot

Start the application and read the boot logs:

```bash
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"
```

Watch the console during startup. Look for:

```
INFO  AuditSchemaEnforcer: FTS5 memory index and triggers verified.
```

If you see a SQLite syntax error instead, the FTS5 virtual table was not created successfully. The `MemorySearchTool` will fail at runtime if this line is absent.

### 17.2 Step 2 — Run the Automated Tests

These tests verify the FTS5 Java bindings and tenant isolation logic without spending any LLM tokens.

**Test the Repository (FTS5 schema, tenant isolation, search queries):**

```bash
# macOS / WSL2
./mvnw test -pl arcclaw-core -am \
  "-Dtest=CompactionSummaryRepositoryTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"

# Windows
.\mvnw.cmd test -pl arcclaw-core `
  "-Dtest=CompactionSummaryRepositoryTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

**Test the Tool (ScopedValue propagation, output formatting):**

```bash
# macOS / WSL2
./mvnw test -pl arcclaw-app -am \
  "-Dtest=MemorySearchToolTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"

# Windows
.\mvnw.cmd test -pl arcclaw-app `
  "-Dtest=MemorySearchToolTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

### 17.3 Step 3 — Live End-to-End RAG Verification

After accumulating at least one compaction summary (see [Section 16](#16-context-compaction-test)):

1. Start a new Telegram session or REST session.
2. Ask: *"What do you remember about our past conversations?"*
3. The `coordinator` agent will invoke `MemorySearchTool`, query the FTS5 index for your `userId`, and return a ranked summary of prior context.
4. Verify in the logs: `MemorySearchTool: Found N results for userId=<id>`.

### 17.4 FTS5 Backfill (Database Migration from pre-Phase 45)

If upgrading an existing deployment where `compaction_summary` rows have null `user_id`:

```bash
sqlite3 data/arcclaw-meta.db
```

```sql
ATTACH DATABASE './data/arcclaw-fts.db' AS fts;
-- Run the backfill script
.read backfill_fts5.sql
.quit
```

---

## 18. Telegram Bot Integration & Verification

For comprehensive Telegram testing, see [TELEGRAM_TESTING.md](./TELEGRAM_TESTING.md). This section covers basic integration verification.

### Available Telegram Commands

Once connected, these commands are handled by the Reflex System (zero LLM tokens consumed):

| Command | Description |
|---|---|
| `/approve` | Approve a pending HITL tool execution and resume the agent from checkpoint |
| `/deny` | Deny a pending HITL tool execution and mark the checkpoint `FAILED` |
| `/halt` | Emergency stop — terminate the current session immediately |
| `/status` | Show current agent state, USD spent, iteration count, and compaction stats |
| `/history` | Show recent conversation turns and compaction summaries |
| `/crons` or `/list crons` | List all scheduled autonomous tasks for your user |
| `deletecron <id>` | Delete a specific scheduled task by ID |
| `/reset` | Soft-reset session state (wipes ReAct loop, preserves chat memory) |
| `store <key>=<value>` | Store a secret in the encrypted vault (e.g. `store MY_API_KEY=abc123`). Note: bypasses PII scanning to protect secret values. |

> `store <key>=<value>` stores the value in the AES-256-GCM-SIV vault. The key name must match a `@RequiresSecret(vaultKey=...)` annotation on the tool that needs it (e.g. `store GITHUB_TOKEN=ghp_...`).

### 18.1 Create a Bot via BotFather

1. Open Telegram and search for `@BotFather`.
2. Send `/newbot`.
3. Provide a display name (e.g. `ArcClaw Dev Bot`) and a username ending in `bot` (e.g. `arcclaw_dev_bot`).
4. Copy the HTTP API token (format: `123456789:ABCdefGHIjklmNOPQrsTUVwxyz`).

### 18.2 Configure `.env`

```env
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklmNOPQrsTUVwxyz

# Find your numeric Telegram user ID via @userinfobot
# Comma-separate multiple IDs: ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789,987654321
ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789
ARCCLAW_TELEGRAM_ALLOWED_USERS=yourusername
```

> The allowlist is enforced by `TelegramAllowlistGuard`. Messages from IDs or usernames not on the list are silently dropped (or blocked with a configurable message if `silent-block=false`).

### 18.3 Start in Attach (Daemon) Mode

```bash
# Dev mode (uses %dev profile — no Podman isolation)
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=attach"

# OR packaged jar (attach mode — recommended for Telegram testing)
java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  "-Dquarkus.profile=%cli" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar attach telegram
```

Confirm the route registered in startup logs:

```
INFO  [TelegramChannelRoute] Telegram route self-registered and started via StartupEvent.
INFO  [TelegramChannelRoute] Route count: 1, routeStatus=Started
```

If you see `Telegram bot token is a placeholder — skipping route registration`, the token is not set.

### 18.4 Verification Tests

**Test 1 — Cold Start**
Send your bot any message. In the logs you should immediately see the Apache Camel route pick it up, hand it off to a Virtual Thread, and the AgentLoop start logging its thoughts. The bot should reply within seconds.

**Test 2 — Session Continuity**
Follow up with: *"Can you summarize what you just told me in exactly 3 bullet points?"*
If the bot references the previous response, it proves `UUID.nameUUIDFromBytes(chatId + ":" + userId)` session mapping works and the `IN_PROGRESS` checkpoint persisted correctly.

**Test 3 — Human-in-the-Loop (HITL)**
Request a task that requires `NETWORK_OUTBOUND` autonomy (e.g. *"Search GitHub for..."*). The agent should pause and send an approval request. Reply `/approve` to resume or `/deny` to terminate. Verify the SQLite checkpoint changes from `AWAITING_APPROVAL` to `COMPLETED` or `FAILED`.

**Test 4 — Intent Classification**
Send the bot: *"Write me a Python function to sort a list."*
Watch the logs for `IntentClassifierService` routing. It should classify the intent and route to the `coder` (Brahma) role, not the coordinator.

### 18.5 Token Revocation (Nuclear Option)

If ghost connections from previous sessions are polling your bot:

1. Open `@BotFather` in Telegram.
2. Select your bot and send `/revoke`.
3. BotFather issues a new token immediately.
4. Replace `TELEGRAM_BOT_TOKEN` in `.env` with the new value.
5. Restart the daemon.

---

## 19. API Key Generation Reference

The `X-ArcClaw-API-Key` header must match `ARCCLAW_API_KEY_INTERNAL` in your `.env`. Use one of the following methods to generate a cryptographically secure random key.

**Linux / macOS (hex, 64 chars = 32 bytes):**

```bash
openssl rand -hex 32
```

**Linux / macOS (Base64, ~44 chars = 32 bytes):**

```bash
openssl rand -base64 32
```

**Windows PowerShell (Base64):**

```powershell
[System.Convert]::ToBase64String(
  [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
)
```

Paste the output as the value of `ARCCLAW_API_KEY_INTERNAL` (and `ARCCLAW_API_KEY_CI` if needed) in your `.env` file. The `key-internal-` prefix convention makes the key type obvious in logs.

---

## 20. Final Verification Checklist

Work through this checklist after completing setup and running the tests above.

### Environment
- [ ] `java -version` shows JDK 25
- [ ] `./mvnw --version` (or `.\mvnw.cmd`) shows Maven 3.9+
- [ ] `podman ps` returns an empty list with no errors
- [ ] `.env` file exists with all required keys populated
- [ ] `ARCCLAW_AUDIT_HMAC_KEY` is set to a stable 32-byte value
- [ ] `ARCCLAW_VAULT_PEPPER` is set to a stable 32-byte value

### Build
- [ ] `./mvnw clean package -pl arcclaw-app -am -DskipTests` completes without errors
- [ ] `arcclaw-app/target/quarkus-app/quarkus-run.jar` exists

### Runtime & Health
- [ ] Application starts and logs `ArcClaw daemon mode starting on port 8080`
- [ ] `curl http://localhost:8080/q/health` returns `{"status":"UP",...}`
- [ ] `curl -X POST http://localhost:8080/api/v1/sessions -H "X-ArcClaw-API-Key: key-internal-changeme"` returns a `sessionId`
- [ ] Boot logs confirm sandbox strategy: `INFO: Isolation strategy: PODMAN initialized.` (prod) or `NONE` (dev)
- [ ] Boot logs show: `INFO  [AuditSchemaEnforcer] WORM triggers installed on audit_event`
- [ ] Boot logs show: `INFO  [AuditSchemaEnforcer] FTS5 memory index and triggers verified.`

### Sandbox
- [ ] `podman images | grep arcclaw-tool-runner` shows the image (if using prod profile)
- [ ] Tier 1 test (prod mode) shows Podman container launch in logs for a tool call
- [ ] `TmpfsSecretTransportTest` passes: secrets are written and zeroed

### Testing
- [ ] `./mvnw clean verify -pl arcclaw-app -am` — all tests green
- [ ] `CompactionSummaryRepositoryTest` passes — FTS5 tenant isolation verified
- [ ] `AuditServiceIntegrationTest` passes — HMAC chain integrity verified
- [ ] E2E REST flow ([Section 12](#12-e2e-rest-api-workflow)) completes: `COMPLETED` status returned
- [ ] Jaeger trace visible at http://localhost:16686 after an agent run (if using compose stack)

### Observability (if using compose stack)
- [ ] Jaeger UI accessible at http://localhost:16686
- [ ] Prometheus UI accessible at http://localhost:9090
- [ ] Metrics visible at http://localhost:8080/q/metrics

### Telegram (if configured)
- [ ] Cold start test: bot responds within seconds
- [ ] Session continuity test: bot references prior turn after restart
- [ ] HITL test: agent pauses on `NETWORK_OUTBOUND`, resumes on `/approve`
- [ ] Audit chain verify returns `{"valid": true}` after full Telegram session

---

## 21. Troubleshooting

### `./mvnw: cannot execute: required file not found` (WSL2)

Windows CRLF line endings in the Maven wrapper. Fix:

```bash
sudo apt install dos2unix -y
dos2unix mvnw
```

### `NullPointerException` in Quarkus Dev Console

Disable the dev console and Dev UI:

```bash
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve" \
  "-Dquarkus.console.enabled=false" \
  "-Dquarkus.devui.enabled=false"
```

### Podman Socket Not Found / `docker ps` Fails

On WSL2, the socket requires systemd to be running. Verify:

```bash
systemctl --user status podman.socket
# If inactive:
systemctl --user enable --now podman.socket
```

Confirm `DOCKER_HOST` is set correctly:

```bash
echo $DOCKER_HOST
# Expected: unix:///run/user/1000/podman/podman.sock
```

If your UID is not `1000`:

```bash
id -u
export DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock"
# Then update ~/.bashrc with your actual UID
```

### Podman `permission denied` with Docker CLI

```bash
sudo usermod -aG $(getent group 10 | cut -d: -f1) $USER
# Log out and log back in
```

### Sandbox Strategy is `NONE` When You Expect `PODMAN`

Check the following in order:
1. Are you running `quarkus:dev`? It always uses `NONE`. Package and run the jar with `QUARKUS_PROFILE=prod`.
2. Was Podman running when ArcClaw started? The circuit breaker evaluates `podman --version` at boot — starting Podman afterwards won't help. Restart ArcClaw.
3. Are you running the jar in PowerShell on Windows? The OS check detects `Windows` and bypasses Linux namespaces. Run inside WSL2 Ubuntu instead.

### `InaccessibleObjectException` / Unsafe Memory Warnings

Expected on JDK 22+ with Netty. They are suppressed by the JVM flags in `pom.xml`. If you see them running the jar manually, ensure all `--add-opens` and `-Dsun.misc.unsafe.memory.access=allow` flags from [SETUP.md Section 7.6](./SETUP.md#76-jvm-flags-reference) are present.

### `open //./pipe/dockerDesktopLinuxEngine` (Windows Podman Desktop)

```powershell
$env:DOCKER_HOST="npipe:////./pipe/podman-machine-default"
```

Or enable "Docker API compatibility" in Podman Desktop → Settings → General.

### OOM During Native Build

The native profile sets `-J-Xmx8g` by default. If memory is limited:

```bash
./mvnw clean package -pl arcclaw-app -am -Pnative -DskipTests \
  "-Dquarkus.native.additional-build-args=-J-Xmx6g"
```

Or increase Podman machine memory before building:

```bash
podman machine stop
podman machine set --memory 8192
podman machine start
```

### `lstat /arcclaw-app/target: no such file or directory` (Docker Compose)

`Dockerfile.native` expects the pre-built native binary on the host. Run the native build step ([SETUP.md Section 9](./SETUP.md#9-common-native-binary-build)) before `docker compose up --build`.

### Ollama Port Collision (`bind: address already in use` on 11434)

Quit your local Ollama app or remap the port in `docker-compose.yaml`:

```yaml
ollama:
  ports:
    - "11435:11434"
```

### `@WorkspaceScoped` CDI Errors in Tests

Ensure the `arcclaw-deployment` module is on the classpath. The `WorkspaceScopeProcessor` `@BuildStep` registers the custom scope at Quarkus build time — without it, CDI scope resolution fails.

### Groq API Key Not Working

- Verify the key starts with `gsk_` with no extra whitespace.
- Confirm `ARCCLAW_AUTO_SEED_SECRETS=true` is in `.env`.
- Check boot logs for: `Seeding secret: GROQ_API_KEY`.

### FTS5 Boot Error — Virtual Table Not Created

If `AuditSchemaEnforcer: FTS5 memory index and triggers verified.` is absent from boot logs, SQLite may not support FTS5 on your system. Verify:

```bash
sqlite3 :memory: "CREATE VIRTUAL TABLE t USING fts5(x);"
# No error = FTS5 is available
```

If FTS5 is not available, install a newer SQLite version:

```bash
sudo apt install sqlite3   # Ubuntu — recent packages include FTS5
```

### FTS5 Backfill (Upgrading from pre-Phase 45)

```bash
sqlite3 data/arcclaw-meta.db
```

```sql
ATTACH DATABASE './data/arcclaw-fts.db' AS fts;
.read backfill_fts5.sql
.quit
```

### `Permission denied` on SQLite Data Volume (Docker)

Docker creates named volumes as `root:root`. ArcClaw runs as `arcclaw` (UID 1000). The fix is the **Init-Container pattern**: the `init-perms` service in `docker-compose.yaml` briefly boots as root, runs `chown -R 1000:1000 /app/data`, and exits before the main `arcclaw` container starts (`depends_on: init-perms: condition: service_completed_successfully`).

### Why Does `containers.conf` Force the `vfs` Storage Driver?

Inside a Docker container, standard storage drivers (`overlayfs`, `btrfs`) fail because the host kernel restricts unprivileged mounts inside containers. The `vfs` (Virtual File System) driver is a universal fallback: it creates deep copies of container layers instead of copy-on-write snapshots. It is slower and more disk-intensive than `overlayfs`, but it works in Docker-in-Docker and Kubernetes pods without `privileged` storage. Run `podman system prune -f` periodically to reclaim disk space from accumulated layer copies.

---

## 📚 Related Documentation

| Document | Purpose |
|---|---|
| [`README.md`](./README.md) | Architecture overview, agent personas, security model, extension guide |
| [`SETUP.md`](./SETUP.md) | Prerequisites, OS-specific setup, build, run modes, Docker Compose, native build |
| [`TELEGRAM_TESTING.md`](./TELEGRAM_TESTING.md) | Telegram bot end-to-end testing (19 sections) |
| [`FAQ.md`](./FAQ.md) | In-depth architecture Q&A, security rationale, and operational guidance |
| [`rest-http/`](./rest-http/) | IntelliJ / VSCode REST Client files — see `rest-http/README.md` for full index |

---

*Last updated: ArcClaw V1.0.0 · Quarkus 3.23.0 · JDK 25*
