<div align="center">
  <h1>🛡️ ArcClaw V1.0.0</h1>
  <p><strong>A secure, stateful, and financially governed AI agent kernel designed for enterprise deployment.</strong></p>

  ![JDK 25](https://img.shields.io/badge/JDK-25-red.svg?style=flat&logo=java)
  ![Quarkus](https://img.shields.io/badge/Quarkus-3.23.0-blue.svg?style=flat&logo=quarkus)
  ![Native Image Ready](https://img.shields.io/badge/Native_Image-Ready-orange.svg?style=flat&logo=graalvm)
  ![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-Tracing-blueviolet.svg?style=flat&logo=opentelemetry)
  ![Podman](https://img.shields.io/badge/Isolation-Rootless_Podman-purple.svg?style=flat&logo=podman)
</div>

---

## 🏗️ Core Architectural Pillars (The 'Why')

ArcClaw is not a conceptual prototype; it is an enterprise-grade kernel built with a paranoid security posture and rigorous financial guardrails.

### Core Technical Pillars

| Pillar | Implementation |
|---|---|
| **Isolation** | OS-level sandboxing via Podman rootless or Linux Namespaces; Session-constrained workspaces |
| **State** | JDBC-backed `ChatMemoryStore` + SQLite (WAL) for 100% session persistence and graceful continuity |
| **Intelligence** | LLM Intent Routing (`llama-3.1-8b-instant`) for dynamic specialist dispatch |
| **Governance** | Human-in-the-Loop (HITL) manual approval for sensitive tool operations |
| **Observability** | Prometheus Metrics (Micrometer) + OTLP Tracing + Jaeger |
| **Security** | Argon2id key derivation + AES-256-GCM-SIV authenticated encryption |
| **Memory** | Context compaction via `ContextCompactionService` + SQLite FTS5 full-text long-term memory |
| **Scheduling** | Autonomous cron-based task scheduling with Telegram notifications |

### 🕉️ The Cosmic Pantheon — Full Agent Roster

ArcClaw ships a **six-agent roster** inspired by the Hindu Trimurti & Tridevi, with two additional specialist roles derived from extended Vedic tradition. The default multi-agent architecture is informed by four-agent collaborative reasoning frameworks designed for high reasoning capability.

| Role | Deity | Canonical Name | Tools | Description |
|---|---|---|---|---|
| `coordinator` | **Vishnu** | coordinator | github, scheduletask, manageschedule, memory_search | The Preserver. Primary router: decomposes goals, delegates to specialists, synthesizes final output |
| `researcher` | **Saraswati** | researcher | github, memory_search | Goddess of Knowledge. Strict fact retrieval; never fabricates or writes code |
| `coder` | **Brahma** | coder | *(none — empty schema)* | The Creator. Pure logic and engineering brain; refuses conversational tasks |
| `debugger` | **Shiva** | debugger | github | The Transformer. Contrarian balance expert; finds blind spots and challenges assumptions |
| `architect` | **Brihaspati** | architect | github, memory_search | System Designer & Standards Author. Authors ADRs, evaluates trade-offs; never writes production code |
| `synthesizer` | **Dattatreya** | synthesizer | github, memory_search, scheduletask, manageschedule | The Avadhuta (Trinity Incarnate). Cross-domain synthesis when a task spans multiple specialist domains simultaneously |

> [!NOTE]
> The `coder` and `debugger` roles receive an **empty tool schema** — tool bias is architecturally impossible regardless of LLM instruction. The `architect` and `synthesizer` roles are additional personas beyond the default four registered in `application.yaml`. They can be invoked via the REST API using their canonical role names.

*   **🛡️ Hardened Isolation:** Rootless Podman execution with ephemeral secret injection. Tools run in strictly capped containers, and secrets are passed via memory-wiped `.env` files—never as process arguments.
*   **🔐 Zero-String Security:** Argon2id key derivation and AES-GCM-SIV cryptographic vault. Keys are held in `AutoCloseable` wrappers that aggressively overwrite memory with zeros when no longer needed.
*   **🧵 Virtual Thread Mastery:** Fully non-blocking. Leverages JDK 25 optimized concurrent loops utilizing standard `synchronized` blocks alongside a bespoke CDI `@WorkspaceScoped` isolation mechanism to safely manage state maps across thousands of lightweight threads.
*   **💰 Financial Governance:** Hard USD circuit breakers and **Human-in-the-Loop** approvals. Sensitive operations (e.g., `NETWORK_OUTBOUND`) pause execution until a user clicks `/approve` via Telegram.
*   **🔍 Observability:** Real-time Prometheus metrics at `/q/metrics` track token consumption and agent invocations, while OTLP distributed tracing provides basic HTTP spans via Jaeger (Note: internal reasoning spans are stubbed as no-ops in V1.0.0).

---

## 🌊 High-Level Architecture Flow

When a REST call hits the kernel, the execution sequence is rigorously defined:

1.  **REST API:** The edge layer (`AgentResource`) intercepts the request on a Virtual Thread and provisions a session.
2.  **Message Pipeline:** Incoming channel messages pass through an ordered `MessagePipeline` of handlers: `AllowlistHandler` → `CommandDispatchHandler` (reflexes) → `SessionResolutionHandler` → `IntentClassificationHandler` → `CompactionHandler` → `AgentDispatchHandler`.
3.  **Reflex System:** Before the LLM is ever invoked, the `ReflexRegistry` scans the input for deterministic fast-path commands (e.g., `/approve`, `/deny`, `/halt`, `/history`, `/status`, `/crons`). If a reflex matches, it executes without consuming any LLM tokens and short-circuits the pipeline.
4.  **AgentLoop (LangChain4J):** The core reasoning engine runs a bounded `maxIterations` loop (Thought → Plan → Act → Observe), preventing infinite hallucination spirals.
5.  **ToolRegistry:** The CDI-powered registry discovers available tools and enforces Governance policy (e.g., blocking `NETWORK_OUTBOUND` actions if the agent is untrusted or bankrupt).
6.  **ProcessIsolationExecutor:** The Sandbox layer enforces three-tier degradation: Podman rootless containers in production (strict network/CPU/memory caps), Linux namespace isolation as fallback, and direct host execution in dev mode (`strategy: NONE`). Tool schemas are also filtered per persona — coder and debugger roles receive an empty tool list, making tool bias architecturally impossible regardless of LLM instruction.

> [!NOTE]
> **Sandbox Degradation Details**: The ProcessIsolationExecutor operates in three tiers:
> - **Production** (`%prod`): Full Podman rootless container isolation with circuit breaker (3-failure trip to fallback).
> - **Namespace fallback**: If Podman circuit opens, Linux `unshare` namespaces for pid, net, and mount isolation.
> - **Development** (`%dev`, strategy: NONE): Direct host execution — requires `gh` CLI installed on the host PATH. No container isolation.

7.  **SQLite Checkpointing:** After each tick, the entire Thought trajectory is serialized into a SQLite WAL-enabled checkpoint, making the agent deeply resilient to JVM crashes.
8.  **Async Persistence Engine:** A single-writer `AsyncPersistenceEngine` serializes all SQLite writes through a `LinkedBlockingQueue`, preventing WAL contention across virtual threads.

```mermaid
graph TD
    Client([Client / UI]) -->|REST| REST[AgentResource REST API]
    Telegram([Telegram User]) -->|Apache Camel Route| TH[TelegramAgentHandler]
    TH --> Pipeline[MessagePipeline]

    subgraph Message Pipeline
        Pipeline --> ALH[AllowlistHandler\nuser allowlist check]
        ALH --> CDH[CommandDispatchHandler\nReflex fast-path]
        CDH --> SRH[SessionResolutionHandler\nsession binding]
        SRH --> ICH[IntentClassificationHandler\nllama-3.1-8b-instant]
        ICH --> CH[CompactionHandler\nFTS5 compaction trigger]
        CH --> ADH[AgentDispatchHandler\nAgent run]
    end

    subgraph Reflex System - No LLM Tokens Consumed
        CDH -->|matches| RR[ReflexRegistry]
        RR --> ApR[ApproveReflex /approve]
        RR --> DeR[DenyReflex /deny]
        RR --> HaR[HaltReflex /halt]
        RR --> HiR[HistoryReflex /history]
        RR --> StR[StatusReflex /status]
        RR --> LC[ListCronsReflex /crons]
        RR --> DC[DeleteCronReflex deletecron id]
        RR --> ReR[ResetReflex /reset]
        RR --> PII[PiiScanReflex redact before LLM]
        RR --> PIR[PromptInjectionReflex block jailbreaks]
    end

    ADH -->|Routed Role| REST
    REST --> Loop[AgentLoop Orchestrator]

    subgraph Core Kernel
        Loop <-->|Load/Save State| SQLite[(SQLite Checkpoint DB\nWAL + Async Writer)]
        Loop <-->|JDBC ChatMemory\nRolling Window| JDBC[(Persistent ChatMemory\n+ FTS5 Long-Term Memory)]
        Loop <-->|Prompt + Role-Filtered\nTool Schema| AI[LangChainReasoningEngine]
        Loop -->|Execute Tool Request| ToolReg[ToolRegistry\nGovernance + HITL]
    end

    subgraph Strict Tool Binding per Role
        AI -->|agentRole filter| Schema{Schema Filter\ngetPermittedToolNames}
        Schema -->|coordinator / synthesizer| ST[scheduletask + manageschedule]
        Schema -->|coordinator / researcher / architect / synthesizer| MS[memory_search]
        Schema -->|coordinator / researcher / architect / debugger / synthesizer| GT[github]
        Schema -->|coder / debugger-no-github| NoTool[empty or read-only schema\ntool bias impossible]
    end

    AI <-->|Cloud Reasoning| Groq((Groq: llama-4-scout\n17b-16e-instruct))
    Loop -->|Micrometer| Prom[Prometheus /q/metrics]

    subgraph Sandbox Layer - Three-Tier Degradation
        ToolReg -->|requiresSandbox=true| AgentToolImpl[AgentTool Implementation]
        AgentToolImpl -->|sandboxCommand + @RequiresSecret| PIE[ProcessIsolationExecutor]

        PIE -->|%prod + Podman running| Podman[(Podman Rootless Container\n--network=none --read-only\nmemory=512m + cpu=0.5)]
        PIE -->|Podman circuit open 3 failures| NS[Linux unshare Namespaces\npid + net + mount isolated]
        PIE -->|%dev strategy=NONE\nor Windows fallback| Direct[Direct ProcessBuilder\nHost PATH execution\nno isolation]
    end

    subgraph HITL Governance
        ToolReg -->|NETWORK_OUTBOUND\nSUPERVISED level| Pause[Pause + Save AWAITING_APPROVAL]
        Pause -->|/approve via Telegram or CLI| Resume[Resume from SQLite Checkpoint]
        Pause -->|/deny via Telegram or CLI| Terminate[Mark FAILED + Wipe State]
    end

    subgraph Cron Scheduler
        AgentCronScheduler[AgentCronScheduler\npoll every 30s] -->|pending jobs| CronJobRunner
        CronJobRunner -->|runs agent loop| Loop
        CronJobRunner -->|notifies| TelegramCron[TelegramCronNotificationObserver]
    end
```

### Sequence Flow

```mermaid
sequenceDiagram
    participant T as Telegram User
    participant TH as TelegramAgentHandler
    participant PP as MessagePipeline
    participant RR as ReflexRegistry
    participant IC as IntentClassifier
    participant AL as AgentLoop
    participant LLM as ReasoningEngine (Llama-4-Scout)
    participant TR as ToolRegistry
    participant PIE as ProcessIsolationExecutor
    participant DB as SQLite WAL + AsyncWriter

    T->>TH: Send message
    TH->>PP: process(MessageContext)

    PP->>RR: processIfReflex(input)
    alt Reflex matches (e.g. /approve /halt /history /status)
        RR-->>T: Deterministic response (0 LLM tokens)
    else No reflex match
        PP->>IC: Classify intent → role
        IC-->>PP: coordinator / coder / debugger / researcher / architect / synthesizer
        PP->>AL: run(sessionId, role, goal)

        loop maxIterations — Thought → Plan → Act → Observe
            AL->>LLM: nextStep(agentRole, goal, history)
            Note over LLM: Role-filtered tool schema injected\ncoder → empty schema\nresearcher / architect → github + memory_search\ncoordinator / synthesizer → all tools
            LLM-->>AL: Action (ToolName + Args) OR FINISH

            alt Tool call requested
                AL->>TR: invokeTool(toolName, params)
                TR->>TR: Governance check\nAutonomyPolicy.isAllowed()

                alt SUPERVISED + NETWORK_OUTBOUND
                    TR-->>T: ⚠️ Approval required — /approve or /deny
                    AL->>DB: 💾 Save AWAITING_APPROVAL checkpoint
                    T->>TH: /approve
                    TH->>AL: Resume from checkpoint
                    AL->>TR: invokeTool (elevated to FULL)
                end

                TR->>PIE: executeSandboxed(sessionId, workspaceRoot, command, secrets)

                alt %prod — Podman available
                    PIE->>PIE: executeInContainer()\n--network=none --read-only\nmemory + CPU capped
                else Podman circuit open — Linux host
                    PIE->>PIE: executeInNamespace()\nunshare pid + net + mount
                else %dev strategy=NONE or Windows
                    PIE->>PIE: executeDirectly()\nhost PATH ProcessBuilder
                end

                PIE-->>AL: 🔍 Tool output observation
            else FINISH
                AL->>DB: 💾 Checkpoint COMPLETED
                AL-->>T: 🏁 Final answer
            end

            AL->>DB: 💾 Async checkpoint each iteration
        end
    end
```

---

## 🧠 Reflex System (Zero-Token Fast Path)

Before any message reaches the LLM, the `ReflexRegistry` evaluates it against all registered `AgentReflex` implementations. Reflexes that match short-circuit the pipeline entirely — **no LLM tokens are ever consumed**.

| Reflex | Trigger | Behaviour |
|---|---|---|
| `ApproveReflex` | `/approve` | Resumes a paused `AWAITING_APPROVAL` checkpoint; fires cron job completion if applicable |
| `DenyReflex` | `/deny` | Marks checkpoint `FAILED`, wipes state, fires cron job completion |
| `HaltReflex` | `/halt` | Emergency stop — terminates the workspace and all in-flight runs |
| `HistoryReflex` | `/history` | Dumps recent chat history + compaction summaries |
| `StatusReflex` | `/status` | Shows current checkpoint state, USD spent, and compaction stats |
| `ListCronsReflex` | `/crons`, `/cron`, `/list crons` | Lists all scheduled autonomous tasks for the current user |
| `DeleteCronReflex` | `deletecron <id>` | Deletes a specific cron job by ID |
| `ResetReflex` | `/reset` | Soft-resets session state (clears ReAct loop + compactions, preserves chat memory) |
| `PiiScanReflex` | every message | Detects emails, phone numbers, credit cards, SSNs; redacts or blocks per `arcclaw.pii.mode` |
| `PromptInjectionReflex` | every message | Blocks jailbreak patterns (fake approvals, "reveal system prompt", auto-approve bypass attempts) |

> [!NOTE]
> The `PiiScanReflex` and `PromptInjectionReflex` run on **every message** via their `sanitize()` hook in `ReflexRegistry.processIfReflex()`, even when no other reflex matches. PII mode is configurable: `REDACT_AND_FORWARD` (default) silently redacts and passes to the LLM; `BLOCK_AND_NOTIFY` rejects the message outright.

---

## 🛠️ Built-in Tools

All tools are discovered via CDI `Instance<AgentTool>` and registered automatically. The `ToolRegistry` enforces autonomy governance before invoking any tool.

| Tool | Name | Operation | Sandbox | Description |
|---|---|---|---|---|
| `GithubTool` | `github` | `NETWORK_OUTBOUND` | ✅ Podman | GitHub CLI (`gh repo view/list`, `gh gist view/list`) with `GH_TOKEN` secret injection via tmpfs |
| `ScheduleTaskTool` | `scheduletask` | `SCHEDULE_AUTONOMOUS` | ❌ In-memory | Schedule a future autonomous task with a 5-field UNIX cron expression; naked-pronoun guard + fork-bomb exclusion |
| `ManageScheduleTool` | `manageschedule` | `READ_FS` | ❌ In-memory | List or delete the current user's scheduled cron jobs; tenant-isolated via `WorkspaceScopeHolder.USER_ID` |
| `MemorySearchTool` | `memory_search` | `READ_FS` | ❌ In-memory | FTS5 full-text search of past session compaction summaries for long-term memory recall |

**Tool Governance — Autonomy Levels:**

| Operation | FULL | SUPERVISED | BACKGROUND | READONLY |
|---|---|---|---|---|
| `NETWORK_OUTBOUND` | ✅ | ⚠️ HITL pause | ❌ | ❌ |
| `SCHEDULE_AUTONOMOUS` | ✅ | ✅ | ❌ | ❌ |
| `READ_FS` | ✅ | ✅ | ✅ | ✅ |

> [!NOTE]
> **Secret Injection**: Tools annotate their `sandboxCommand()` method with `@RequiresSecret(envVar="GH_TOKEN", vaultKey="GITHUB_TOKEN")`. The `ToolRegistry` reads these annotations reflectively, resolves secrets from `SecretVault`, and injects them via `TmpfsSecretTransport` — an ephemeral env-file written to RAM-backed tmpfs, zeroed from JVM memory on close. Secrets **never** appear in process arguments.

---

## 🗃️ Memory & Persistence Architecture

ArcClaw uses a **dual-database SQLite architecture**:

*   `arcclaw-meta.db` — primary metadata database (WAL mode): checkpoints, chat memory, cron entities, audit events, encrypted secrets.
*   `arcclaw-fts.db` — standalone FTS5 full-text index: `compaction_summary_fts` table for semantic memory search.

The two databases are connected at runtime via `ATTACH DATABASE`. All writes to SQLite are serialized through a single-threaded `AsyncPersistenceEngine` backed by a `LinkedBlockingQueue`, eliminating WAL contention across concurrent virtual threads.

```mermaid
graph LR
    subgraph SQLite WAL Dual-DB
        Meta[(arcclaw-meta.db\ncheckpoints\nchat_memory\nagent_cron\naudit_event\nencrypted_secret\ncompaction_summary)]
        FTS[(arcclaw-fts.db\ncompaction_summary_fts\nFTS5 full-text index)]
        Meta -- ATTACH DATABASE --> FTS
    end

    APE[AsyncPersistenceEngine\nLinkedBlockingQueue\nsingle writer thread] -->|serialized writes| Meta
    CC[ContextCompactionService\nllama-3.1-8b-instant] -->|compaction summaries| Meta
    CC -->|FTS index population| FTS
    MST[MemorySearchTool] -->|FTS5 query by userId| FTS
    SR[SessionReaper\nScheduled GC] -->|purge stale sessions| Meta
```

**Context Compaction** (`ContextCompactionService`):

When the rolling chat window exceeds `arcclaw.memory.compaction.threshold` (default: 5 messages), the compaction service summarizes the oldest messages using `llama-3.1-8b-instant` and writes a `CompactionSummaryEntity`. The `keep-recent` count of messages is preserved verbatim. This enables theoretically unbounded conversation while controlling context window token costs. Compaction summaries are indexed into the FTS5 database so they can be recalled by `MemorySearchTool`.

---

## ⏰ Autonomous Cron Scheduler

ArcClaw supports fully autonomous scheduled tasks via a built-in cron engine backed by SQLite persistence.

*   **`ScheduleTaskTool`**: An agent can create a cron job mid-conversation using a standard 5-field UNIX expression. The goal must be self-contained (naked-pronoun guard rejects vague references). Tasks are prefixed with `CRON:` to enable structural fork-bomb exclusion.
*   **`AgentCronScheduler`**: Polls the `agent_cron` table every 30 seconds (configurable via `arcclaw.cron.poll-interval`). Picks up `PENDING` jobs whose next fire time has elapsed.
*   **`CronJobRunner`**: Executes the agent loop for the scheduled goal. Supports up to `arcclaw.cron.max-concurrent` (default: 10) concurrent jobs and `arcclaw.cron.max-iterations` (default: 2) reasoning iterations per job to prevent runaway costs.
*   **`CronResultHandler`**: On job completion or failure, fires a CDI `AgentJobCompleted` event observed by `TelegramCronNotificationObserver` and `CliCronNotificationObserver` to notify the originating channel.

```mermaid
sequenceDiagram
    participant U as User (Telegram / CLI)
    participant AL as AgentLoop
    participant ST as ScheduleTaskTool
    participant DB as SQLite agent_cron
    participant CS as AgentCronScheduler (every 30s)
    participant CJR as CronJobRunner
    participant TN as TelegramCronNotificationObserver

    U->>AL: "remind me every day at 9am to check alerts"
    AL->>ST: scheduletask(cronExpression="0 9 * * *", goal="Check alerts...", runOnce=false)
    ST->>DB: INSERT agent_cron (PENDING, nextFireTime)
    ST-->>AL: ✅ Scheduled. cronId=abc-123

    loop Every 30 seconds
        CS->>DB: SELECT WHERE status=PENDING AND nextFireTime <= now()
        DB-->>CS: [job abc-123]
        CS->>CJR: run(cronEntity)
        CJR->>AL: agentLoop(goal, role=coordinator, maxIterations=2)
        AL-->>CJR: AgentResult
        CJR->>DB: UPDATE status=COMPLETED (or FAILED)
        CJR->>TN: fire AgentJobCompleted event
        TN-->>U: 🕐 Scheduled task result: ...
    end
```

---

## 🔒 Security Architecture

### Cryptographic Vault

ArcClaw's `SecretVaultImpl` uses a multi-layer cryptographic scheme:

*   **Key Derivation**: `Argon2idKeyDerivation` with 64 MB memory, 3 iterations, 1 parallelism thread.
*   **Encryption**: `AesGcmSivCipher` — AES-256-GCM-SIV authenticated encryption. Nonce is derived from the vault key + salt to make it deterministic (immune to nonce reuse under key rotation).
*   **Pepper**: A server-side secret (`arcclaw.vault.pepper`) mixed into the key derivation, preventing offline dictionary attacks even if the database is exfiltrated.
*   **Key Rotation**: Automatic 24-hour key rotation (`arcclaw.vault.rotation.interval-hours`). A `VaultKeyCache` maps key versions to derived key material.
*   **Memory Safety**: `VaultKey` implements `AutoCloseable`. The `close()` method explicitly overwrites the key byte array with zeros before GC.

### API Authentication & Rate Limiting

*   **`ApiKeyAuthFilter`**: Bearer token authentication on all `/api/v1/*` endpoints. Supports multiple named API keys configured via environment: `ARCCLAW_API_KEY_INTERNAL` and `ARCCLAW_API_KEY_CI`. Key comparison uses constant-time equality to prevent timing attacks.
*   **`ApiKeyRegistry`**: Registry that stores keys with their capabilities. Supports variable-length keys.
*   **Rate Limiting**: 20 requests/minute per API key (configurable: `arcclaw.api.rate-limit.requests-per-minute`).
*   **`DestructiveVocabularyFilter`**: Pre-execution filter that blocks messages containing dangerous shell keywords (e.g., `rm -rf`, `DROP TABLE`, `:(){ :|:& };:`) before they reach the LLM.
*   **`StartupSecurityValidator`**: Validates the security posture at startup. In `%prod`, fails fast if weak default secrets are detected. In `%dev`, exempts validation with a warning.
*   **`TelegramAllowlistGuard`**: Enforces an allowlist of permitted Telegram user IDs and usernames (`ARCCLAW_TELEGRAM_ALLOWED_USER_IDS` / `ARCCLAW_TELEGRAM_ALLOWED_USERS`). Supports open-access mode (empty allowlist = everyone allowed) and silent-block mode.

### Sandbox Secret Transport

Secrets for sandboxed tools are transported via `TmpfsSecretTransport`:

```mermaid
graph LR
    SV[SecretVault\nAES-GCM-SIV Decrypt] -->|raw bytes| SR[SandboxSecretResolver]
    SR --> TT[TmpfsSecretTransport\ntmpfs-backed env-file\nmode=600]
    TT -->|--env-file /run/arcclaw/secrets/...| Podman[Podman Container]
    TT -->|AutoCloseable.close| Zero[Zero bytes in JVM + unlink tmpfs file]
```

---

## 🏥 Health Checks & Observability

### Health Endpoints (`/q/health`)

| Health Check | Type | Validates |
|---|---|---|
| `PodmanHealthCheck` | Liveness | `podman --version` exits successfully |
| `PodmanImageHealthCheck` | Readiness | `arcclaw-tool-runner:latest` image is present and pullable |
| `SystemMetadataHealthCheck` | Readiness | `SystemMetadataRegistry` has version and environment populated |

### Observability Stack

*   **Prometheus** (`/q/metrics`): Micrometer metrics exported for token consumption (`arcclaw.llm.tokens`), agent invocations (`arcclaw.agent.invocations`), and Quarkus scheduler metrics.
*   **Jaeger** (`localhost:16686`): OTLP traces via `otel-collector`. SDK is disabled by default (`OTEL_SDK_DISABLED=true`); set to `false` when the collector is running.
*   **Structured Logging**: MDC-enriched log lines include `sessionId` in every log entry. Log format: `HH:mm:ss LEVEL [class] [sid=<sessionId>] (thread) message`.
*   **`MetricsAgentObserver`**: CDI observer wired into `AgentLoop.recordLlmCall()`. Increments Micrometer counters per LLM call.
*   **`AgentOtelContext`**: OpenTelemetry span holder for tool execution. Internal reasoning spans are stubbed as no-ops in V1.0.0; HTTP spans are active.

---

## ⚡ Quick Start (Zero to Agent in 60 seconds)

### 📋 Prerequisites
Before compiling the kernel, ensure your environment meets these strict requirements:
* **JDK 25** (or JDK 21+ with `--add-opens` flags for Mockito/Virtual Thread compatibility).
* **Maven 3.9+**
* **Docker or Podman** (for the observability stack and embedded tool isolation).
* **GraalVM / Mandrel** (Only required if building the native binary yourself).

### 🪟 Windows Setup Note (WSL2)
Because ArcClaw leverages advanced rootless container isolation for its tools, **WSL2 (Windows Subsystem for Linux) is highly recommended** for Windows developers.
* Install **Podman Desktop** (which automatically configures the WSL2 backend and injects the `podman` CLI into your Windows PATH), or do your development entirely inside a WSL2 Ubuntu distro using VS Code Remote.
* `.env` files work identically across OS boundaries. If you need to set temporary variables directly in the terminal, use standard PowerShell syntax (e.g., `$env:SESSION_ID="<uuid>"`).

---

### Step 1: Clone & Configure
```bash
git clone https://github.com/rsrini7/ArcClaw.git
cd ArcClaw

# Copy the environment template
cp .env.example .env

# Configure your secrets
# Note: ArcClaw V1.0.0 defaults to Groq (meta-llama/llama-4-scout-17b-16e-instruct) for reasoning
# and (llama-3.1-8b-instant) for intent classification and compaction.
# The local docker-compose stack still provisions an offline Ollama instance for experimentation,
# but it is disabled by default in application.yaml.
```

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `GROQ_API_KEY` | `(empty)` | **Required.** Authenticates with the Groq Cloud for the main reasoning engine and intent classifier. |
| `TELEGRAM_BOT_TOKEN` | `(empty)` | **Required for attach mode.** Connects the daemon to your Telegram bot. |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Points to the local or remote Ollama LLM provider (disabled by default in V1.0.0). |
| `GITHUB_TOKEN` | `(empty)` | Required if using the `GithubTool` (researcher / coordinator / architect / synthesizer / debugger agents). |
| `ARCCLAW_DATA_DIR` | `./data` | The host path where SQLite DBs and Workspaces are saved. |
| `ARCCLAW_API_KEY_INTERNAL` | `key-internal-changeme` | Bearer token for internal API access. **Change in production.** |
| `ARCCLAW_API_KEY_CI` | `key-ci-changeme` | Bearer token for CI/CD pipeline access. **Change in production.** |
| `ARCCLAW_VAULT_PEPPER` | `dev-pepper-...` | Server-side secret for Argon2id key derivation. **Must be 32+ bytes in production.** |
| `ARCCLAW_AUDIT_HMAC_KEY` | `dev-audit-hmac-key-...` | HMAC key for audit event integrity. **Must be 32+ bytes in production.** |
| `ARCCLAW_SESSION_BUDGET_USD` | `10.00` | Per-session USD budget cap. Downgrade to READONLY on exceed. |
| `ARCCLAW_TELEGRAM_ALLOWED_USER_IDS` | `(empty = open)` | Comma-separated Telegram user ID allowlist. Empty allows all. |

### Step 2: Compile the Native Binary (CRITICAL)
Your `Dockerfile.native` expects the GraalVM native binary to already exist on your host machine. Docker will **not** compile your Java code; if you deploy without this step, the container will instantly fail with `failed to solve: lstat /arcclaw-app/target: no such file or directory` or cannot find `*-runner`.

> [!NOTE]
> The final native executable will be approximately **118MB**, containing the entire statically-linked Quarkus runtime and ArcClaw kernel logic.

*(Note: You must have GraalVM/Mandrel installed locally, or use Quarkus's built-in container builder if on Windows/Mac)*
```bash
# Build the native Linux binary (uses Podman container-build automatically):
# The -am flag is CRITICAL — it rebuilds arcclaw-core before packaging arcclaw-app.
.\mvnw.cmd clean package -pl arcclaw-app -am -Pnative -DskipTests
```

### Step 3: Boot the Stack
Once the `.env` is populated, the native binary is sitting in `target/`, and the compose file is updated with the `init-perms` block, you are ready for liftoff:
```bash
docker compose up -d --build
```
> [!TIP]
> **Zero-Touch Initialization**: The Docker compose stack includes an `ollama-init` sidecar that will automatically download the LLM weights (e.g. `llama3.2:1b`) for you prior to the main ArcClaw server booting. Just wait a few seconds for the download! *(This only applies if you re-enable the Ollama chat model in `application.yaml`)*.

(or)

```bash
docker compose build --no-cache
docker compose up -d
```

> [!CAUTION]
> **Windows Podman Desktop Users**: Standalone `docker compose` binary defaults to Docker Desktop's named pipe. If you receive an error `open //./pipe/dockerDesktopLinuxEngine`, you must either enable "Docker API compatibility" in Podman Settings, or set the following environment variable before running the compose command:
> `$env:DOCKER_HOST="npipe:////./pipe/podman-machine-default"`

> [!CAUTION]
> **Local Ollama Port Collisions**: If you already have Ollama installed naturally on your host machine (Mac/Windows), Docker Compose will likely crash with `bind: address already in use` for TCP port `11434`. You must either quit your local Ollama application entirely before booting `docker compose`, or remap the Ollama port inside `docker-compose.yaml` (e.g., `"11435:11434"`).

**What to expect next:**
* Docker will download the images (Ollama, Jaeger, UBI 9).
* `init-perms` will boot instantly, change the permissions of the data volume to UID 1000 (`arcclaw`), and exit.
* `arcclaw` will boot, safely initialize its SQLite DB, and bind to port 8080.

You can then run the `curl` commands from your `TESTING_GUIDE.md`!

### 🧪 Running the Test Suite
ArcClaw includes **150+ unit and integration tests** covering CDI scopes, LLM reasoning logic, SQLite checkpoints, sandbox circuit breakers, reflex system, audit integrity, cron scheduling, and secret vault operations. To run the full suite across all modules, use:
```bash
mvn clean verify -pl arcclaw-app -am
```
*(Note: Mockito requires JDK 21+ inline mock attachments. This is handled automatically by the `--add-opens` flags in the `arcclaw-app` pom.xml).*

### Step 4: Run the Kernel (Multi-Mode)
ArcClaw V1.0.0 functions as a multi-mode binary. You can run it as a long-lived REST daemon, attach it to external channels, interact via a REPL, or execute a single task directly from the CLI.

**Option A: Daemon Mode (`serve`)**
Run as a background REST API server.
```bash
# Ensure you are using JDK 21+ (Designed for JDK 25)
.\mvnw.cmd quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"
```
*(Note: Or using the built JAR: `java -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve`)*

> [!NOTE]
> **Dev Sandbox Limitations**: In the `%dev` profile (when running `quarkus:dev`), the container isolation strategy defaults to `NONE`. To test full Podman rootless isolation, you must run the packaged runner (or native binary) using the Prod profile.
> If Quarkus dev mode fails with a console `NullPointerException`, disable the dev console by setting `quarkus.console.enabled=false`. See `TROUBLESHOOTING.md` for details.

**Option B: Channel Daemon (`attach`)**
Keep ArcClaw running as a background daemon to service external channel adapters (e.g., Telegram bots). The cron scheduler is active in this mode.
```bash
java -jar arcclaw-app/target/quarkus-app/quarkus-run.jar attach telegram
```

**Option C: Interactive REPL (`chat`)**
Start a multi-turn, stateful conversation stream directly in your terminal. All reflexes and HITL approval commands work in this mode.
```bash
java -jar arcclaw-app/target/quarkus-app/quarkus-run.jar chat
```

**Option D: One-Shot Task (`ask`)**
Run a single agentic task and exit immediately. Perfect for CI/CD automation.
```bash
java -jar arcclaw-app/target/quarkus-app/quarkus-run.jar ask "Analyze the codebase..." --role coordinator
```

**Option E: Silent PowerShell Wrapper (Recommended for Windows)**
For a cleaner experience, use the provided silent wrapper. It forces the `%cli` profile (disabling banners) and sets JVM `UTF-8` encoding to ensure Unicode terminal symbols (`✦`, `⚡`) render perfectly.
```powershell
.\arcclaw-cli.ps1 chat
# or
.\arcclaw-cli.ps1 ask "Your goal here" --role coordinator
```

---

## 🤖 Interacting with the Kernel

Use our REST API to spawn and observe agents.

**1. Create a Workspace Session:**
```bash
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Authorization: Bearer key-internal-changeme"
# Response: {"sessionId": "abcd-1234-..."}
```

**2. Trigger an Agent (Asynchronous):**
Delegate a task to the `coordinator` (Vishnu) agent fully asynchronously:
```bash
curl -v -X POST http://localhost:8080/api/v1/sessions/abcd-1234-.../run-async \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer key-internal-changeme" \
  -d '{
        "goal": "Analyze the arcclaw repository statistics",
        "agentRole": "coordinator",
        "maxIterations": 3
      }'
```

> [!TIP]
> **Pro Tip: Use `api.http` instead!**
> If you are using **IntelliJ IDEA** or **VSCode** (with the REST Client extension), you can skip manual `curl` commands and just use the provided [`api.http`](./api.http) file.
> 1. Run the first request to create the session.
> 2. The script will automatically capture the `SESSION_ID`.
> 3. Run the subsequent "Async" or "Sync" requests immediately without copying IDs!

**3. Observe the Trace:**
Open the [Jaeger UI](http://localhost:16686) in your browser to watch the agent think, execute tools in Podman, and persist its thoughts.

**4. Audit Trail:**
```bash
curl http://localhost:8080/api/v1/audit/sessions/abcd-1234-... \
  -H "Authorization: Bearer key-internal-changeme"
```

**5. End-to-End Verification (Reading the Logs):**
When the asynchronous agent executes successfully, you will see a precise sequence of events in your Quarkus terminal that perfectly matches our architecture diagram:

1. **Virtual Thread Initialization:** `Spawning virtual thread for async agent run, session: e8d8...`
   *ArcClaw receives the REST payload and instantly offloads the workload to a lightweight JDK 25 Virtual Thread so the HTTP worker isn't blocked.*
2. **Context Hydration:** `AgentLoop starting for goal... (maxIterations=5)`
   *The `@ActivateRequestContext` binds a fresh CDI lifecycle to the unmanaged thread, and the `WorkspaceScopeHolder` activates the isolated workspace session.*
3. **LLM Delegation:** `HTTP request: - url: https://api.groq.com/openai/v1/chat/completions`
   *LangChain4j serializes the tools and your prompt, then executes an asynchronous REST payload to the Groq cloud infrastructure.*
4. **Tool/Reasoning Execution:** `Agent reached FINISH at iteration 1`
   *The LLM decides it has satisfied your goal and commands the parser to halt the Thought-Action-Observation loop.*
5. **State Checkpointing:** `Created checkpoint for session=e8d8... role=coordinator` followed immediately by `Checkpoint id=2 marked COMPLETED`
   *The `AsyncPersistenceEngine` commits the final reasoning step history as a JSON CLOB into the SQLite database, marking the virtual thread's lifecycle complete.*

---

## 📱 Telegram Bot Integration (Live Test)

ArcClaw features an enterprise-grade, Virtual-Thread-backed Telegram network adapter built on **Apache Camel** (`TelegramChannelRoute`). By explicitly handling the Vert.x to ManagedExecutor handoff, ArcClaw can handle thousands of concurrent Telegram users without dropping connections.

It features several advanced conversational mechanisms:
*   **Deterministic Session Routing**: Uses `UUID.nameUUIDFromBytes(chatId + ":" + userId)` to ensure a user's session persists exactly across bot or server restarts.
*   **Semantic Intent Classification**: Every incoming message passes through the `IntentClassifierService` (`llama-3.1-8b-instant`) to auto-route your task to the correct specialist.
*   **Task Amnesia (Goldfish Memory)**: To prevent context-poisoning, sessions undergo a **Soft Reset** upon `COMPLETED` or `FAILED` checkpoints. This wipes the internal ReAct loop state to prevent "ghost" tool triggers, while retaining the LangChain conversational history for follow-up turns.
*   **Human-in-the-Loop (HITL)**: Tool calls requiring `SUPERVISED` autonomy pause the agent. You can review the intention and reply with `/approve` or `/deny`.
*   **Markdown Rendering**: `TelegramMarkdownV2Renderer` converts LangChain response nodes into Telegram's MarkdownV2 format, escaping all special characters correctly.
*   **Cron Notifications**: When a scheduled task completes, `TelegramCronNotificationObserver` sends the result to the originating chat.

### Available Telegram Commands

| Command | Description |
|---|---|
| `/approve` | Approve a pending HITL tool execution |
| `/deny` | Deny a pending HITL tool execution |
| `/halt` | Emergency stop — terminate session immediately |
| `/status` | Show current agent state, USD spent, compaction count |
| `/history` | Show recent conversation + compaction summaries |
| `/crons` or `/list crons` | List all scheduled autonomous tasks |
| `deletecron <id>` | Delete a specific scheduled task |
| `/reset` | Soft-reset session state |
| `store <key>=<value>` | Store a secret in the encrypted vault |

### Step 1: Create a Telegram Bot
1. Open Telegram and search for **@BotFather**.
2. Send the command `/newbot`.
3. Give it a name (e.g., `ArcClaw Dev Bot`) and a username (e.g., `arcclaw_dev_bot`).
4. BotFather will give you an HTTP API Token. Copy this.

### Step 2: Configure Your Environment
Open your `.env` file and add the token:
```env
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklmNOPQrsTUVwxyz
```
Make sure your `GROQ_API_KEY` is set in your environment so the agent has a brain.

### Step 3: Boot ArcClaw in Daemon Mode
```bash
.\mvnw.cmd quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"
```
*(You should see Quarkus boot up, connect to SQLite, and log: `ArcClaw daemon mode starting on port 8080...`)*

### Step 4: The Live Verification Test

**Test 1: The Cold Start**
1. Open Telegram and search for your bot's username.
2. Click **Start** (or send a message like: "Hello, I need you to research the ArcClaw repository.")
3. **Watch your terminal**: You should immediately see the Camel route pick it up, hand it off to the Virtual Thread, and the AgentLoop start logging its thoughts.
4. **Watch Telegram**: A few seconds later, the bot should reply with Vishnu's final synthesized answer!

**Test 2: Conversation Memory (Session Continuity)**
1. Reply to the bot in Telegram with a follow-up: *"Can you summarize what you just told me in exactly 3 bullet points?"*
2. **The Verification**: If the bot successfully references the previous message, it proves that your `UUID.nameUUIDFromBytes` mapping worked perfectly and the session stayed `IN_PROGRESS` as designed.

**Test 3: HITL Approval Flow**
1. Ask the bot to perform a GitHub operation (e.g., "List the repositories of user rsrini7").
2. The bot should pause and send an approval request: `⚠️ Approval required — /approve or /deny`.
3. Reply `/approve` and observe the agent resume from its saved checkpoint.

### 🚨 Troubleshooting: The "Nuclear Option" (Token Revocation)
If you have ghost container connections polling your bot, you can sever them by revoking the token in BotFather.

1. In Telegram, click your bot's username in the BotFather chat.
2. Send the `/revoke` (or `/token`) command.
3. BotFather will immediately revoke the old token and give you a new one.
4. Replace the old token in your `.env` file with the new one.
5. Restart the Quarkus daemon (`Ctrl+C`, then `.\mvnw.cmd quarkus:dev ...`) to pick up the new token.

---

## 🧩 Extending ArcClaw

ArcClaw is designed as a modular kernel. Adding new capabilities requires minimal boilerplate:

**1. Create a New Tool:**
Implement the `AgentTool` interface, register it as an `@ApplicationScoped` bean, and ArcClaw handles the LangChain4J specification mapping and Sandbox isolation automatically.

```java
@ApplicationScoped
public class MyTool implements AgentTool {
    @Override public String name() { return "mytool"; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.READ_FS; }
    @Override public boolean requiresSandbox() { return false; }
    @Override public ToolResponse execute(Map<String, Object> params) { ... }
}
```

**2. Define a New Persona:**
Create a new class implementing `Agent`. Define its `role()`, supply your custom `systemPrompt()`, and return a list of `allowedTools()`. Tag it with `@AgentRole("your_role")` to make it instantly available to the REST API and the intent classifier.

**3. Register a New Reflex:**
Implement `AgentReflex`, mark it `@ApplicationScoped`, and return `true` from `matches()` for your trigger pattern. ArcClaw's `ReflexRegistry` discovers it automatically via CDI.

---

## 📁 Project Structure (The Reactor)

ArcClaw enforces strict module boundaries to guarantee clean architectural separation:

```
arcclaw-parent/               ← BOM dependency definitions and version management
arcclaw-channel-api/          ← Pure interfaces: ChannelSender, ChannelAllowlist,
│                               IntentClassifier, ReplyRenderer, ChannelUser, etc.
arcclaw-core/                 ← The heart. Core logic, CDI API interfaces, domain models,
│   ├── agent/                  AgentLoop, personas, reasoning engine, role registry
│   ├── api/                    REST resources (AgentResource, AuditResource, SystemInfoResource)
│   ├── audit/                  HMAC-signed audit events, compliance service, telemetry
│   ├── governance/             GovernanceLayer: USD budget, circuit breakers
│   ├── health/                 Podman + image + system metadata health checks
│   ├── lifecycle/              WorkspaceManager, SessionReaper, SessionHeartbeatTracker
│   ├── observability/          AgentObserver interface, AgentOtelContext
│   ├── persistence/            AsyncPersistenceEngine, FtsConnectionInterceptor
│   ├── pipeline/               MessagePipeline + ordered MessageHandlers
│   ├── reflex/                 ReflexRegistry + all AgentReflex implementations
│   ├── sandbox/                ProcessIsolationExecutor, SandboxCircuitBreaker,
│   │                           TmpfsSecretTransport, PodmanContainerExecutor
│   ├── scope/                  @WorkspaceScoped CDI scope + WorkspaceScopeHolder (ScopedValue)
│   ├── security/               AES-GCM-SIV vault, Argon2id KDF, AutonomyPolicy
│   ├── state/                  SQLite entities, PersistentChatMemoryStore,
│   │                           StatefulAgentStore, ContextCompactionService, AgentCronRepository
│   └── tools/                  AgentTool interface, GithubTool, ScheduleTaskTool,
│                               ManageScheduleTool, MemorySearchTool, ToolRegistry
arcclaw-deployment/           ← The brain. Quarkus build-time @BuildStep processors
│                               that wire up custom @WorkspaceScoped CDI logic
arcclaw-app/                  ← The entrypoint. REST endpoints, application.yaml,
    ├── channel/telegram/       TelegramChannelRoute (Apache Camel), TelegramAgentHandler,
    │                           TelegramMarkdownV2Renderer, TelegramCronNotificationObserver
    ├── cli/                    ChatCommand (REPL), AttachCommand, CliCronNotificationObserver
    ├── compaction/             CompactionModelProducer (llama-3.1-8b-instant)
    ├── observability/          MetricsAgentObserver (Micrometer)
    ├── routing/                IntentClassifierService, ModelProducer
    ├── scheduler/              AgentCronScheduler, CronJobRunner, CronResultHandler
    └── security/               ApiKeyAuthFilter, ApiKeyRegistry, RateLimiter,
                                DestructiveVocabularyFilter, StartupSecurityValidator,
                                TelegramAllowlistGuard
```

---

## 🌐 Infrastructure Stack

The `docker-compose.yaml` provisions the full observability and inference stack:

| Service | Port | Purpose |
|---|---|---|
| `arcclaw` | `8080` | Main ArcClaw kernel (native UBI 9 binary) |
| `ollama` | `11434` | Local LLM inference (disabled by default; Groq is primary) |
| `ollama-init` | — | One-shot sidecar to pull Ollama model weights before arcclaw starts |
| `otel-collector` | `4317` (gRPC), `4318` (HTTP) | OpenTelemetry collector; forwards traces to Jaeger |
| `jaeger` | `16686` (UI) | Distributed tracing UI |
| `prometheus` | `9090` | Metrics scraping from `/q/metrics` |
| `tool-runner` | — | Build-only profile: builds `arcclaw-tool-runner:latest` sandbox image |
| `init-perms` | — | One-shot sidecar to `chown -R 1000:1000` the data volume before arcclaw starts |

---

## 📚 Deep-Dive Documentation

Our codebase was engineered for Future AI and Human contributors. Don't guess—read the docs:

*   **[`TESTING_GUIDE.md`](./TESTING_GUIDE.md)** — Comprehensive E2E `curl` commands, REST flows, and distributed tracing setups.
*   **[`WSL2_SETUP_GUIDE.md`](./WSL2_SETUP_GUIDE.md)** — Step-by-step guide for setting up JDK 25 and native Podman inside WSL2.
*   **[`FAQ.md`](./FAQ.md)** — Frequently Asked Questions — Answered.
