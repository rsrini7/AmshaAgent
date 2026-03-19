<div align="center">
  <h1>🛡️ ArcClaw V1.0.0</h1>
  <p><strong>A secure, stateful, and financially governed AI agent kernel designed for enterprise deployment.</strong></p>

  ![JDK 25](https://img.shields.io/badge/JDK-25-red.svg?style=flat&logo=java)
  ![Quarkus](https://img.shields.io/badge/Quarkus-3.27.2-blue.svg?style=flat&logo=quarkus)
  ![Native Image Ready](https://img.shields.io/badge/Native_Image-Ready-orange.svg?style=flat&logo=graalvm)
  ![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-Tracing-blueviolet.svg?style=flat&logo=opentelemetry)
  ![Podman](https://img.shields.io/badge/Isolation-Rootless_Podman-purple.svg?style=flat&logo=podman)
</div>

---

> **A self-hosted, security-first multi-agent AI framework built on Quarkus + LangChain4J**
>
> **Stack**: JDK 25 · Quarkus 3.27.2 · LangChain4J 1.x · SQLite (WAL + FTS5 + sqlite-vec) · Podman (rootless) · Apache Camel · Groq Cloud

---

> **Docs**: [Setup](./SETUP.md) · [Testing Guide](./TESTING_GUIDE.md) · [Telegram Testing](./TELEGRAM_TESTING.md) · [FAQ](./FAQ.md) · [REST Client Files](./rest-http/)

---

## What is ArcClaw?

ArcClaw is a **self-hosted, production-grade agentic AI platform** designed to run complex, multi-step reasoning tasks with full auditability, human-in-the-loop governance, and sandboxed tool execution. It runs as a single ~118 MB binary with zero external service dependencies (SQLite replaces a database server; Podman replaces a container daemon).

### Key Capabilities

- **ReAct agent loop** (Reason → Act → Observe) over Groq-hosted LLMs or local Ollama models
- **Six specialist agent personas** with per-role tool schemas, system prompts, and iteration caps
- **Reflex system** — deterministic zero-token fast-path for commands, safety guards, and PII redaction before the LLM sees any input
- **Podman rootless sandbox** for executing AI-generated tool calls in full container isolation
- **AES-256-GCM-SIV secret vault** with Argon2id key derivation — secrets are never stored in plaintext
- **Tamper-evident HMAC audit chain** — every agent action is logged as an append-only, verifiable record
- **Autonomous cron scheduler** — agents can schedule future tasks using a 5-field UNIX cron expression
- **Context compaction + FTS5 + vector long-term memory** — rolling chat window with LLM-summarised history searchable via two-tier RAG (FTS5 keyword + sqlite-vec embeddings merged via Reciprocal Rank Fusion)
- **MCP integration** — serves as both an inbound MCP server (JSON-RPC over HTTP-SSE) and an outbound MCP client that connects to external MCP servers and exposes their tools natively
- **Custom Skills system** — zero-LLM-token skill routing; skills defined as YAML-frontmatter Markdown documents, stored in SQLite, matched by trigger patterns before intent classification
- **Structured Output engine** — typed LLM extraction with retry loops and lenient JSON parsing
- **Multi-channel** — REST API, interactive CLI REPL, Telegram bot (via Apache Camel), one-shot `ask` mode for CI/CD

---

## 🏗️ Core Architectural Pillars

ArcClaw is not a conceptual prototype; it is an enterprise-grade kernel built with a paranoid security posture and rigorous financial guardrails.

| Pillar | Implementation |
|---|---|
| **Isolation** | OS-level sandboxing via Podman rootless or Linux Namespaces; Session-constrained workspaces |
| **State** | JDBC-backed `ChatMemoryStore` + SQLite (WAL) for 100% session persistence and graceful continuity |
| **Intelligence** | LLM Intent Routing (`llama-3.1-8b-instant`) for dynamic specialist dispatch |
| **Governance** | Human-in-the-Loop (HITL) manual approval for sensitive tool operations |
| **Observability** | Prometheus Metrics (Micrometer) + OTLP Tracing + Jaeger |
| **Security** | Argon2id key derivation + AES-256-GCM-SIV authenticated encryption |
| **Memory** | Context compaction via `ContextCompactionService` + SQLite FTS5 + sqlite-vec two-tier RAG |
| **Scheduling** | Autonomous cron-based task scheduling with Telegram notifications |
| **MCP** | Inbound MCP server (JSON-RPC/HTTP-SSE) + Outbound MCP client (`ExternalMcpToolAdapter`) |
| **Skills** | Zero-token skill routing via `SkillReflex`; CRUD via REST; built-in classpath + dynamic DB-tier |

- **🛡️ Hardened Isolation:** Rootless Podman execution with ephemeral secret injection. Tools run in strictly capped containers, and secrets are passed via memory-wiped `.env` files — never as process arguments.
- **🔐 Zero-String Security:** Argon2id key derivation and AES-GCM-SIV cryptographic vault. Keys are held in `AutoCloseable` wrappers that aggressively overwrite memory with zeros when no longer needed.
- **🧵 Virtual Thread Mastery:** Fully non-blocking. Leverages JDK 25 optimized concurrent loops utilizing standard `synchronized` blocks alongside a bespoke CDI `@WorkspaceScoped` isolation mechanism to safely manage state maps across thousands of lightweight threads.
- **💰 Financial Governance:** Hard USD circuit breakers and **Human-in-the-Loop** approvals. Sensitive operations (e.g., `NETWORK_OUTBOUND`) pause execution until a user clicks `/approve` via Telegram.
- **🔍 Observability:** Real-time Prometheus metrics at `/q/metrics` track token consumption and agent invocations, while OTLP distributed tracing provides basic HTTP spans via Jaeger. Note: internal reasoning spans are stubbed as no-ops in V1.0.0.

---

## 🌊 High-Level Architecture Flow

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryBorderColor': '#4fc3f7', 'lineColor': '#4fc3f7', 'activationBorderColor': '#4fc3f7', 'actorBorder': '#4fc3f7', 'actorLineColor': '#4fc3f7','labelBoxBorderColor': '#0b0c0cff', 'noteBorderColor': '#81c784', 'fontSize': '13px'}}}%%
flowchart TD

    IN(["📨 REST · Telegram · CLI · MCP"]):::entry

    subgraph PIPELINE["🔀 MESSAGE PIPELINE"]
        direction LR
        P1["① Allowlist"]:::handler
        P2["② Session"]:::handler
        P3{"③ Reflex?"}:::decision
        P4{"④ Skill?"}:::decision
        P5["⑤ Intent Classifier"]:::handler
        P6["⑥ Compaction"]:::handler
        P7["⑦ Dispatch"]:::handler
    end

    subgraph REFLEXES["⚡ REFLEXES — 0 LLM tokens"]
        direction LR
        R1["/approve · /deny · /halt"]:::reflexOut
        R2["/status · /history · /crons"]:::reflexOut
        R3["PII Scan → Redact/Block"]:::reflexOut
        R4["Prompt Injection → Block"]:::reflexOut
        R5["/mcp → MCP status"]:::reflexOut
    end

    subgraph SKILLS["🎯 SKILLS — 0 LLM tokens"]
        direction LR
        S1["SkillReflex pattern match"]:::skillOut
        S2["Skill system prompt injected"]:::skillOut
        S3["Agent runs with skill persona"]:::skillOut
    end

    subgraph REACT["🔁 ReAct AGENT LOOP — LangChain4J"]
        direction LR
        L1["🧠 LLM<br/>Reason"]:::loop
        L2{"Tool<br/>call?"}:::decision
        L3{"HITL<br/>Gate?"}:::decision
        L4["⏸ AWAITING<br/>APPROVAL"]:::approval
        L5["🏖 Sandbox<br/>Podman→NS→Direct"]:::sandbox
        L6["📝 Observe<br/>+ SQLite tick"]:::loop
        L7{"FINISH?"}:::decision
        L8(["✅ Reply sent"]):::result
    end

    IN --> PIPELINE
    P1 --> P2 --> P3
    P3 -->|"matched"| REFLEXES
    P3 -->|"no match"| P4
    P4 -->|"skill matched"| SKILLS
    P4 -->|"no skill"| P5 --> P6 --> P7

    SKILLS --> REACT
    P7 --> REACT
    L1 --> L2
    L2 -->|"no"| L7
    L2 -->|"yes"| L3
    L3 -->|"approved"| L5
    L3 -->|"needs approval"| L4
    L4 -.->|"/approve"| L5
    L5 --> L6 --> L7
    L7 -->|"next iter"| L1
    L7 -->|"done"| L8
```

When a REST call hits the kernel, the execution sequence is rigorously defined:

1. **REST API:** The edge layer (`AgentResource`) intercepts the request on a Virtual Thread and provisions a session.
2. **Message Pipeline:** Incoming channel messages pass through an ordered `MessagePipeline` of handlers: `AllowlistHandler` → `SessionResolutionHandler` → `CommandDispatchHandler` (reflexes) → `IntentClassificationHandler` → `CompactionHandler` → `AgentDispatchHandler`.
3. **Reflex System:** Before the LLM is ever invoked, the `ReflexRegistry` scans the input for deterministic fast-path commands. If a reflex matches, it executes without consuming any LLM tokens and short-circuits the pipeline.
4. **Skill System:** After reflexes pass, `SkillReflex` checks whether the input matches any compiled trigger pattern in `SkillRegistry`. If matched, the skill's system prompt is injected and the agent runs directly — bypassing the intent classifier entirely (zero additional LLM classification tokens).
5. **AgentLoop (LangChain4J):** The core reasoning engine runs a bounded `maxIterations` loop (Thought → Plan → Act → Observe), preventing infinite hallucination spirals.
6. **ToolRegistry:** The CDI-powered registry discovers available tools and enforces Governance policy (e.g., blocking `NETWORK_OUTBOUND` actions if the agent is untrusted or bankrupt).
7. **ProcessIsolationExecutor:** The Sandbox layer enforces three-tier degradation: Podman rootless containers in production (strict network/CPU/memory caps), Linux namespace isolation as fallback, and direct host execution in dev mode (`strategy: NONE`). Tool schemas are also filtered per persona — the `coder` role receives an empty tool list, making tool bias architecturally impossible regardless of LLM instruction.

> [!NOTE]
> **Sandbox Degradation Details**: The `ProcessIsolationExecutor` operates in three tiers:
> - **All profiles except `%test`** (`%dev`, `%prod`, `%staging`): `strategy: PODMAN` — attempts full Podman rootless container isolation with a circuit breaker (3-failure trip to fallback). This applies to **both dev and prod**.
> - **Namespace fallback**: If the Podman circuit opens (Podman unavailable or 3 consecutive failures), Linux `unshare` namespaces for pid, net, and mount isolation are used automatically.
> - **Direct execution** (`%test` profile, or Windows OS detected): `strategy: NONE` — direct host `ProcessBuilder` execution. Requires `gh` CLI on the host `PATH`. No container isolation.
>
> In `%dev`, if Podman is running and `arcclaw-tool-runner:latest` is present, **full Tier 1 container isolation is active** — identical to production. Direct execution (`NONE`) is only the default for the `%test` profile and Windows hosts.

8. **SQLite Checkpointing:** After each tick, the entire Thought trajectory is serialized into a SQLite WAL-enabled checkpoint, making the agent deeply resilient to JVM crashes.
9. **Async Persistence Engine:** A single-writer `AsyncPersistenceEngine` serializes all SQLite writes through a `LinkedBlockingQueue`, preventing WAL contention across virtual threads.


<details>
<summary>Detailed Architecture Flow Diagram</summary>

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryBorderColor': '#4fc3f7', 'lineColor': '#4fc3f7', 'activationBorderColor': '#4fc3f7', 'actorBorder': '#4fc3f7', 'actorLineColor': '#4fc3f7','labelBoxBorderColor': '#0b0c0cff', 'noteBorderColor': '#81c784', 'fontSize': '13px'}}}%%
graph TD
    RestClient([REST Client / UI]) -->|HTTP| REST[AgentResource REST API]
    CLIUser([CLI User]) -->|chat / ask / serve| CLI[PicoCLI Commands]
    TelegramUser([Telegram User]) -->|Apache Camel Route| TH[TelegramAgentHandler]
    McpClient([External MCP Client]) -->|JSON-RPC HTTP-SSE /mcp| MCP[McpConnectResource]
    FutureChannel([Future Channel<br/>Discord / Slack / SMS]) -.->|ChannelSender impl| Pipeline

    CLI --> Pipeline[MessagePipeline]
    TH --> Pipeline
    REST --> Pipeline
    MCP --> Pipeline

    subgraph Message Pipeline
        Pipeline --> ALH[AllowlistHandler<br/>user allowlist check]
        ALH --> CDH[CommandDispatchHandler<br/>Reflex fast-path]
        CDH --> SRH[SessionResolutionHandler<br/>session binding]
        SRH --> SKH[SkillReflex<br/>zero-token skill routing]
        SKH --> ICH[IntentClassificationHandler<br/>llama-3.1-8b-instant]
        ICH --> CH[CompactionHandler<br/>FTS5 + vec compaction trigger]
        CH --> ADH[AgentDispatchHandler<br/>Agent run]
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
        RR --> McpR[McpReflex /mcp]
        RR --> PII[PiiScanReflex redact before LLM]
        RR --> PIR[PromptInjectionReflex block jailbreaks]
    end

    subgraph Skill System - No Intent Classification Tokens
        SKH -->|trigger matches| SR[SkillRegistry]
        SR --> SB[Built-in Skills<br/>classpath resources/skills/]
        SR --> SD[Dynamic Skills<br/>SQLite AgentSkillEntity]
        SR --> SP[Skill system prompt injected<br/>agent runs with skill persona]
    end

    ADH --> Loop[AgentLoop Orchestrator]

    subgraph Core Kernel
        Loop <-->|Load/Save State| SQLite[(SQLite Checkpoint DB<br/>WAL + Async Writer)]
        Loop <-->|JDBC ChatMemory<br/>Rolling Window| JDBC[(Persistent ChatMemory<br/>+ FTS5 Long-Term Memory)]
        Loop <-->|Prompt + Role-Filtered<br/>Tool Schema| AI[LangChainReasoningEngine]
        Loop -->|Execute Tool Request| ToolReg[ToolRegistry<br/>Governance + HITL]
        Loop -->|RAG context enrichment| RAG[TwoTierMemoryService<br/>FTS5 + sqlite-vec RRF]
    end

    subgraph Strict Tool Binding per Role
        AI -->|agentRole filter| Schema{Schema Filter<br/>getPermittedToolNames}
        Schema -->|coordinator / synthesizer| ST[scheduletask + manageschedule]
        Schema -->|coordinator / researcher / architect / synthesizer| MS[memory_search]
        Schema -->|coordinator / researcher / architect / debugger / synthesizer| GT[github]
        Schema -->|coder| NoTool[empty schema<br/>tool bias impossible]
        Schema -->|dynamic MCP tools| DynT[ExternalMcpToolAdapter<br/>MCP server tools]
    end

    AI <-->|Cloud Reasoning| Groq((Groq: llama-4-scout<br/>17b-16e-instruct))
    Loop -->|Micrometer| Prom[Prometheus /q/metrics]

    subgraph Sandbox Layer - Three-Tier Degradation
        ToolReg -->|requiresSandbox=true| AgentToolImpl[AgentTool Implementation]
        AgentToolImpl -->|sandboxCommand + @RequiresSecret| PIE[ProcessIsolationExecutor]

        PIE -->|strategy=PODMAN<br/>Podman available| Podman[(Podman Rootless Container<br/>--network=none --read-only<br/>memory=512m + cpu=0.5)]
        PIE -->|Podman circuit open<br/>3 failures| NS[Linux unshare Namespaces<br/>pid + net + mount isolated]
        PIE -->|strategy=NONE<br/>%test profile or Windows| Direct[Direct ProcessBuilder<br/>Host PATH execution<br/>no isolation]
    end

    subgraph HITL Governance
        ToolReg -->|NETWORK_OUTBOUND<br/>SUPERVISED level| Pause[Pause + Save AWAITING_APPROVAL]
        Pause -->|/approve via any channel| Resume[Resume from SQLite Checkpoint]
        Pause -->|/deny via any channel| Terminate[Mark FAILED + Wipe State]
    end

    subgraph MCP Layer
        McpAdmin[McpAdminResource<br/>/api/v1/mcp/status · /api/v1/mcp/tools] --> McpTB[McpToolBridge<br/>DynamicToolCapabilityRegistry]
        McpConn[McpConnectResource<br/>/api/v1/mcp/connect] --> McpCS[McpClientService<br/>HTTP_SSE transport]
        McpCS --> ExtMcp[ExternalMcpToolAdapter<br/>wraps remote tools as AgentTool]
    end

    subgraph Cron Scheduler
        AgentCronScheduler[AgentCronScheduler<br/>poll every 30s] -->|pending jobs| CronJobRunner
        CronJobRunner -->|runs agent loop| Loop
        CronJobRunner -->|notifies| CronObs[CronNotificationObserver<br/>Telegram / CLI / custom]
    end

    subgraph RAG - Two-Tier Memory
        RAG --> FTS5T[FTS5 keyword search<br/>CompactionSummaryRepository]
        RAG --> VecT[sqlite-vec vector search<br/>GlobalKnowledgeBase + SessionKnowledgeBase]
        FTS5T & VecT --> RRF[Reciprocal Rank Fusion<br/>k=60]
    end
```
</details>

<details>
<summary>Detailed Architecture Sequence Diagram</summary>

### Sequence Flow

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryBorderColor': '#4fc3f7', 'lineColor': '#4fc3f7', 'activationBorderColor': '#4fc3f7', 'actorBorder': '#4fc3f7', 'actorLineColor': '#4fc3f7','labelBoxBorderColor': '#0b0c0cff', 'noteBorderColor': '#81c784', 'fontSize': '13px'}}}%%
sequenceDiagram
    participant U as User / Client<br/>(REST · CLI · Telegram · MCP)
    participant CH as Channel Adapter<br/>(AgentResource · ChatCommand · TelegramAgentHandler)
    participant PP as MessagePipeline
    participant RR as ReflexRegistry
    participant SK as SkillRegistry
    participant IC as IntentClassifier
    participant AL as AgentLoop
    participant LLM as ReasoningEngine (Llama-4-Scout)
    participant TR as ToolRegistry
    participant PIE as ProcessIsolationExecutor
    participant DB as SQLite WAL + AsyncWriter

    U->>CH: Send message / goal
    CH->>PP: process(MessageContext)

    PP->>RR: processIfReflex(input)
    alt Reflex matches (e.g. /approve /halt /history /status /mcp)
        RR-->>U: Deterministic response (0 LLM tokens)
    else No reflex match
        PP->>SK: checkSkill(input)
        alt Skill trigger pattern matches
            SK-->>AL: inject skill system prompt + run agent (0 intent classification tokens)
        else No skill match
            PP->>IC: Classify intent → role
            IC-->>PP: coordinator / coder / debugger / researcher / architect / synthesizer
        end
        PP->>AL: run(sessionId, role, goal)

        loop maxIterations — Thought → Plan → Act → Observe
            AL->>LLM: nextStep(agentRole, goal, history)
            Note over LLM: Role-filtered tool schema injected<br/>coder → empty schema<br/>researcher / architect → github + memory_search<br/>coordinator / synthesizer → all tools + dynamic MCP tools
            LLM-->>AL: Action (ToolName + Args) OR FINISH

            alt Tool call requested
                AL->>TR: invokeTool(toolName, params)
                TR->>TR: Governance check<br/>AutonomyPolicy.isAllowed()

                alt SUPERVISED + NETWORK_OUTBOUND
                    TR-->>U: ⚠️ Approval required — /approve or /deny
                    AL->>DB: 💾 Save AWAITING_APPROVAL checkpoint
                    U->>CH: /approve
                    CH->>AL: Resume from checkpoint
                    AL->>TR: invokeTool (elevated to FULL)
                end

                TR->>PIE: executeSandboxed(sessionId, workspaceRoot, command, secrets)

                alt strategy=PODMAN — Podman available
                    PIE->>PIE: executeInContainer()<br/>--network=none --read-only<br/>memory + CPU capped
                else Podman circuit open — Linux host
                    PIE->>PIE: executeInNamespace()<br/>unshare pid + net + mount
                else strategy=NONE — %test profile or Windows
                    PIE->>PIE: executeDirectly()<br/>host PATH ProcessBuilder
                end

                PIE-->>AL: 🔍 Tool output observation
            else FINISH
                AL->>DB: 💾 Checkpoint COMPLETED
                AL-->>U: 🏁 Final answer
            end

            AL->>DB: 💾 Async checkpoint each iteration
        end
    end
```
</details>

---

## 🕉️ The Cosmic Pantheon — Full Agent Roster

ArcClaw ships a **six-agent roster** inspired by the Hindu Trimurti & Tridevi, with two additional specialist roles derived from extended Vedic tradition. The default multi-agent architecture is informed by four-agent collaborative reasoning frameworks designed for high reasoning capability. Roles are selected automatically by the intent classifier or passed explicitly via `agentRole` in the API/CLI.

| Role | Deity | Canonical Name | Tools | Description |
|---|---|---|---|---|
| `coordinator` | **Vishnu** | coordinator | `github`, `scheduletask`, `manageschedule`, `memory_search` | The Preserver. Primary router: decomposes goals, delegates to specialists, synthesizes final output |
| `researcher` | **Saraswati** | researcher | `github`, `memory_search` | Goddess of Knowledge. Strict fact retrieval; never fabricates or writes code |
| `coder` | **Brahma** | coder | *(none — empty schema)* | The Creator. Pure logic and engineering brain; refuses conversational tasks |
| `debugger` | **Shiva** | debugger | `github` | The Transformer. Contrarian balance expert; finds blind spots and challenges assumptions |
| `architect` | **Brihaspati** | architect | `github`, `memory_search` | System Designer & Standards Author. Authors ADRs, evaluates trade-offs; never writes production code |
| `synthesizer` | **Dattatreya** | synthesizer | `github`, `scheduletask`, `manageschedule`, `memory_search` | The Avadhuta (Trinity Incarnate). Cross-domain synthesis when a task spans multiple specialist domains simultaneously |

> [!NOTE]
> The `coder` role receives an **empty tool schema** — tool bias is architecturally impossible for it regardless of LLM instruction. The `debugger` has access to `github` for code inspection but no scheduling or memory tools. The `architect` and `synthesizer` roles are additional personas beyond the default four; they can be invoked via the REST API using their canonical role names.

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryBorderColor': '#4fc3f7', 'lineColor': '#4fc3f7', 'activationBorderColor': '#4fc3f7', 'actorBorder': '#4fc3f7', 'actorLineColor': '#4fc3f7','labelBoxBorderColor': '#0b0c0cff', 'noteBorderColor': '#81c784', 'fontSize': '13px'}}}%%
graph LR

    IC(["🎯 Intent Classifier<br/>llama-3.1-8b-instant"]):::classifier

    subgraph AGENTS["🕉️ THE COSMIC PANTHEON — SIX AGENTS"]
        direction TB
        A1["🔵 coordinator — Vishnu<br/>Routes · Delegates · Synthesizes"]:::coordinator
        A2["🟣 researcher — Saraswati<br/>Fact retrieval · No code"]:::researcher
        A3["🟠 coder — Brahma<br/>Pure logic · EMPTY tool schema"]:::coder
        A4["🔴 debugger — Shiva<br/>Contrarian · Finds blind spots"]:::debugger
        A5["🟡 architect — Brihaspati<br/>ADRs · Trade-offs · No prod code"]:::architect
        A6["🟢 synthesizer — Dattatreya<br/>Cross-domain · Multi-specialist"]:::synthesizer
    end

    subgraph TOOLS["🛠️ TOOLS"]
        direction TB
        T1["🐙 github<br/>GH CLI via Podman sandbox<br/>GH_TOKEN injected via tmpfs"]:::tool
        T2["⏰ scheduletask<br/>5-field UNIX cron expression<br/>Fork-bomb + naked-pronoun guard"]:::tool
        T3["📋 manageschedule<br/>List / Delete cron jobs<br/>Tenant-isolated per userId"]:::tool
        T4["🔍 memory_search<br/>SQLite FTS5 + sqlite-vec<br/>Past compaction summaries"]:::tool
        T5["🌐 ExternalMcpTool<br/>Dynamic tools from MCP servers<br/>HTTP_SSE transport"]:::tool
    end

    subgraph AUTONOMY["🔰 AUTONOMY POLICY"]
        direction TB
        AU1["FULL — All ops permitted"]:::policy
        AU2["SUPERVISED ← default<br/>NETWORK_OUTBOUND → HITL pause"]:::policy
        AU3["BACKGROUND — READ_FS only<br/>No scheduling"]:::policy
        AU4["READONLY — READ_FS only<br/>Most restricted"]:::policy
    end

    IC --> A1 & A2 & A3 & A4 & A5 & A6

    A1 -->|"all tools"| T1 & T2 & T3 & T4 & T5
    A2 -->|"github · memory_search"| T1 & T4
    A3 -.->|"∅ empty schema"| T1
    A4 -->|"github only"| T1
    A5 -->|"github · memory_search"| T1 & T4
    A6 -->|"all tools"| T1 & T2 & T3 & T4 & T5

    TOOLS --> AUTONOMY
```

### Tool Schema Enforcement

The tool schema for each role is enforced **structurally** in `LangChainReasoningEngine.getPermittedToolNames()` — it intersects the agent's declared `allowedTools()` with the full registered tool list. Any tool not in the intersection is **invisible to the LLM** for that role, regardless of what the user prompt says. This is a **structural guarantee**, not a system-prompt instruction.

Dynamic MCP tools registered via `DynamicToolCapabilityRegistry` are also subject to this intersection — they appear in the schema only for roles that permit them.

### Intent Classification

The `IntentClassifierService` uses `llama-3.1-8b-instant` to automatically route free-text messages to the best-fit role. It defaults to `coordinator` on any error or rate limit. You can override by passing `agentRole` explicitly.

---

## 📁 Project Structure (The Reactor)

ArcClaw enforces strict module boundaries to guarantee clean architectural separation:

```
arcclaw-parent/               ← BOM dependency definitions and version management
arcclaw-channel-api/          ← Pure interfaces only: ChannelSender, ChannelAllowlist,
│                               IntentClassifier, ReplyRenderer, ChannelUser, etc.
│                               Also: structured output types (AgentFunction, SynthesisResult,
│                               AgentContribution, ConflictSummary, SynthesisFunction).
│                               No implementation. No Quarkus. Zero dependencies.
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
│   ├── skill/                  SkillRegistry, SkillLoader, SkillDefinitionParser,
│   │                           SkillReflex, AgentSkillEntity, AgentSkillRepository
│   ├── state/                  SQLite entities, PersistentChatMemoryStore,
│   │                           StatefulAgentStore, ContextCompactionService, AgentCronRepository
│   ├── structured/             StructuredOutputEngine, StructuredRetryLoop,
│   │                           PermissiveJsonExtractor, JsonExtractionException
│   └── tools/                  AgentTool interface, GithubTool, ScheduleTaskTool,
│                               ManageScheduleTool, MemorySearchTool, ToolRegistry
arcclaw-mcp/                  ← MCP integration layer (inbound server + outbound client)
│   ├── bridge/                 ExternalMcpToolAdapter, McpToolBridge,
│   │                           McpToolDescriptor, McpToolAllowlist
│   ├── config/                 McpToolAllowlist (governs which internal tools are exposed)
│   ├── reflex/                 McpReflex (/mcp status command)
│   ├── server/                 McpToolsBean (tools exposed to external MCP clients)
│   ├── tool/                   DynamicToolCapability, DynamicToolCapabilityRegistry,
│   │                           DynamicToolLookupImpl, McpTransportType
│   └── transport/              McpClientService, McpServerConfig, NdjsonWriter,
│                               PodmanMcpTransport, McpConnectionException
arcclaw-rag/                  ← Two-tier RAG memory (FTS5 + sqlite-vec vector search)
│   ├── EmbeddingModelProducer  Produces LangChain4J embedding model
│   ├── GlobalKnowledgeBase     Application-scoped in-memory embedding store
│   ├── SessionKnowledgeBase    @WorkspaceScoped per-session embedding store
│   ├── TwoTierMemoryService    RRF merge (k=60) of FTS5 + vector results
│   ├── RagContextEnricher      Enriches agent context with retrieved RAG chunks
│   ├── IngestDocumentTool      Tool for ingesting documents into the knowledge base
│   ├── RagSchemaInitializer    Schema setup for vector tables
│   └── ScoredChunk             Value object for ranked search results
arcclaw-deployment/           ← The brain. Quarkus build-time @BuildStep processors
│                               that wire up custom @WorkspaceScoped CDI logic,
│                               MCP build steps (McpBuildStep),
│                               RAG resource steps (RagResourcesBuildStep).
│                               Required for native image compilation.
arcclaw-app/                  ← The entrypoint. REST endpoints, application.yaml,
    ├── channel/telegram/       TelegramChannelRoute (Apache Camel), TelegramAgentHandler,
    │                           TelegramMarkdownV2Renderer, TelegramCronNotificationObserver
    ├── cli/                    ChatCommand (REPL), AttachCommand, CliCronNotificationObserver
    ├── compaction/             CompactionModelProducer (llama-3.1-8b-instant)
    ├── mcp/                    McpAdminResource, McpConnectResource
    ├── observability/          MetricsAgentObserver (Micrometer)
    ├── routing/                IntentClassifierService, ModelProducer, SkillAwareIntentClassifier
    ├── scheduler/              AgentCronScheduler, CronJobRunner, CronResultHandler
    ├── security/               ApiKeyAuthFilter, ApiKeyRegistry, RateLimiter,
    │                           DestructiveVocabularyFilter, StartupSecurityValidator,
    │                           TelegramAllowlistGuard
    └── skill/                  SkillResource (REST CRUD /api/v1/skills)
```

### Why This Split?

| Layer | Reason |
|---|---|
| `arcclaw-channel-api` | Allows `arcclaw-core` to reference channel concepts (sender, allowlist) without coupling to Telegram or any concrete adapter. Swapping channels requires zero core changes. |
| `arcclaw-core` | Independently testable with zero Quarkus startup overhead. All 100+ unit tests run without any HTTP container. |
| `arcclaw-mcp` | MCP server/client logic is isolated from the agent kernel. The `arcclaw-core` `DynamicToolLookup` interface is the only seam — `arcclaw-mcp` provides the implementation via `DynamicToolLookupImpl`. |
| `arcclaw-rag` | Vector search (sqlite-vec) has native library dependencies (`vec0.so`). Isolating it in its own module prevents native image metadata from polluting the core module. |
| `arcclaw-deployment` | The `@WorkspaceScoped` CDI scope, MCP build steps, and RAG resource steps are registered at Quarkus build time via `@BuildStep`. Separating this ensures the scope works correctly in native image compilation. |
| `arcclaw-app` | The only module that knows about external protocols (HTTP, Telegram, CLI). Keeps protocol concerns out of the kernel. |

### `@WorkspaceScoped` — Custom CDI Scope

Standard CDI scopes (`@RequestScoped`, `@SessionScoped`) are tied to HTTP lifecycle events. Agent runs on virtual threads — especially async runs and cron jobs — have no HTTP request context. `@WorkspaceScoped` is a CDI scope keyed on `sessionId`. The `WorkspaceScopeHolder` uses Java 21 `ScopedValue` (not `ThreadLocal`) to bind a workspace context to the logical execution — it propagates correctly across virtual threads, CDI-managed executors, and Camel routes without leaking state between sessions.

---

## 🖥️ Run Modes

ArcClaw is a dual-mode Quarkus + PicoCLI binary. The subcommand after the jar controls the mode.

| Mode | Command | Behaviour |
|---|---|---|
| `ask` | `ask "goal" --role coordinator` | One-shot: run agent, print answer, exit. Ideal for CI/CD scripts. |
| `chat` | `chat` | Interactive REPL with full multi-turn memory, all reflexes, and HITL approval. |
| `serve` | `serve` | Long-running REST daemon on port 8080. No channel adapters; for direct API access. |
| `attach` | `attach telegram` | Long-running daemon with channel adapters active (Apache Camel). Cron scheduler runs. |

The `%cli` profile is forced by `arcclaw-cli.ps1` and silences all Quarkus banners and `INFO` logs so terminal output only shows agent reasoning.

---

## 📨 Message Pipeline

Every incoming message (from REST, Telegram, or CLI) travels through an ordered pipeline of handlers. Priority numbers determine execution order.

```
Incoming Message (any channel)
    ↓
MessagePipeline:
    10 — AllowlistHandler            → blocks non-allowlisted users before any processing
    15 — SessionResolutionHandler    → derives or creates deterministic session UUID
    30 — CommandDispatchHandler      → routes /commands and zero-token reflexes (no LLM call)
    50 — IntentClassificationHandler → SkillReflex check first, then llama-3.1-8b-instant selects best agent role
    60 — CompactionHandler           → compacts chat history if threshold exceeded
    70 — AgentDispatchHandler        → runs the ReAct agent loop, sends reply
```

For Telegram specifically, `TelegramAgentHandler` sends a `⏳` acknowledgment immediately before queuing the message to a per-chat Caffeine executor (serialises messages per user), preventing out-of-order processing.

### ReAct Agent Loop

Inside `AgentDispatchHandler`, the `AgentLoop` runs the standard Reason–Act–Observe cycle:

```
Goal → SystemPromptBuilder → LangChainReasoningEngine (LLM call)
    → ReasoningStepParser → ToolInvocationGuard (HITL gate)
    → ToolInvoker → ProcessIsolationExecutor (sandbox)
    → observation appended → next iteration
    → FINISH detected → AgentResult → reply sent
```

Checkpoints are persisted to SQLite at every iteration via `AsyncPersistenceEngine`. If the JVM is killed mid-loop, the checkpoint survives and `WorkspaceManager` will recover it on the next startup.

---

## 🧠 The Reflex System (Zero-Token Fast Path)

Before any message reaches the LLM, the `ReflexRegistry` evaluates it against all registered `AgentReflex` implementations. Reflexes that match short-circuit the pipeline entirely — **no LLM tokens are ever consumed**.

This matters for three reasons:

1. **Cost**: Commands like `/approve`, `/status`, and `/history` are intent-unambiguous. Routing them through an LLM wastes tokens and adds latency.
2. **Safety**: The `PromptInjectionReflex` must fire *before* the LLM sees the input — if an adversarial prompt could reach the reasoning engine, it might influence the model to bypass governance.
3. **Reliability**: Reflexes are pure Java — they cannot hallucinate, and their behaviour is deterministic and fully unit-testable.

| Reflex | Trigger | Behaviour |
|---|---|---|
| `ApproveReflex` | `/approve` | Resumes a paused `AWAITING_APPROVAL` checkpoint; fires cron job completion if applicable |
| `DenyReflex` | `/deny` | Marks checkpoint `FAILED`, wipes state, fires cron job completion |
| `HaltReflex` | `/halt` | Emergency stop — terminates the workspace and all in-flight runs |
| `HistoryReflex` | `/history` | Dumps recent chat history + compaction summaries |
| `StatusReflex` | `/status` | Shows current checkpoint state, USD spent, and compaction stats |
| `ListCronsReflex` | `/crons`, `/cron`, `/list crons` | Lists all scheduled autonomous tasks for the current user (tenant-isolated) |
| `DeleteCronReflex` | `deletecron <id>` | Deletes a specific cron job by ID (tenant-isolated) |
| `ResetReflex` | `/reset` | Soft-resets session state (clears ReAct loop + compactions, preserves chat memory) |
| `McpReflex` | `/mcp` | Shows MCP server status: exposed internal tools and dynamically connected MCP server tools |
| `PiiScanReflex` | every message | Detects emails, phone numbers, Aadhaar, PAN, credit cards, SSNs, IPs, JWTs; redacts or blocks per `arcclaw.pii.mode` |
| `PromptInjectionReflex` | every message | Blocks jailbreak patterns (fake approvals, "reveal system prompt", auto-approve bypass attempts); writes mandatory `PROMPT_INJECTION_BLOCKED` audit event |

> [!NOTE]
> The `PiiScanReflex` and `PromptInjectionReflex` run on **every message** via their `sanitize()` hook in `ReflexRegistry.processIfReflex()`, even when no other reflex matches. PII mode is configurable: `REDACT_AND_FORWARD` (default) silently redacts and passes to the LLM; `BLOCK_AND_NOTIFY` rejects the message outright. Override at runtime via `ARCCLAW_PII_MODE` in your `.env`.

---

## 🛠️ Built-in Tools

All tools are discovered via CDI `Instance<AgentTool>` and registered automatically. The `ToolRegistry` enforces autonomy governance before invoking any tool.

| Tool | Name | Operation | Sandbox | Description |
|---|---|---|---|---|
| `GithubTool` | `github` | `NETWORK_OUTBOUND` | ✅ Podman | GitHub CLI (`gh repo view/list`, `gh gist view/list`) with `GH_TOKEN` secret injection via tmpfs |
| `ScheduleTaskTool` | `scheduletask` | `SCHEDULE_AUTONOMOUS` | ❌ In-memory | Schedule a future autonomous task with a 5-field UNIX cron expression; naked-pronoun guard + fork-bomb exclusion |
| `ManageScheduleTool` | `manageschedule` | `READ_FS` | ❌ In-memory | List or delete the current user's scheduled cron jobs; tenant-isolated via `WorkspaceScopeHolder.USER_ID` |
| `MemorySearchTool` | `memory_search` | `READ_FS` | ❌ In-memory | Two-tier RAG: FTS5 full-text + sqlite-vec vector search of past session compaction summaries |
| `IngestDocumentTool` | `ingest_document` | `READ_FS` | ❌ In-memory | Ingest a document into the global or session knowledge base for future vector retrieval |
| `ExternalMcpToolAdapter` | *(dynamic name)* | Varies | ❌ In-process | Wraps tools from connected external MCP servers; registered dynamically via `DynamicToolCapabilityRegistry` |

### Tool Governance — Autonomy Levels

| Operation | FULL | SUPERVISED | BACKGROUND | READONLY |
|---|---|---|---|---|
| `NETWORK_OUTBOUND` | ✅ | ⚠️ HITL pause | ❌ | ❌ |
| `SCHEDULE_AUTONOMOUS` | ✅ | ✅ | ❌ | ❌ |
| `READ_FS` | ✅ | ✅ | ✅ | ✅ |

> [!NOTE]
> **Secret Injection**: Tools annotate their `sandboxCommand()` method with `@RequiresSecret(envVar="GH_TOKEN", vaultKey="GITHUB_TOKEN")`. The `ToolRegistry` reads these annotations reflectively, resolves secrets from `SecretVault`, and injects them via `TmpfsSecretTransport` — an ephemeral env-file written to RAM-backed tmpfs, zeroed from JVM memory on close. Secrets **never** appear in process arguments.

---

## 🎯 Custom Skills System

ArcClaw's Skills system is a zero-LLM-token routing mechanism that intercepts messages **before** the intent classifier. When a message matches a skill's trigger pattern, the skill's system prompt is injected directly and the agent runs — no classification tokens spent.

### Skill Document Format

Skills are YAML-frontmatter Markdown documents separated by `---`:

```markdown
---
name: code-reviewer
description: Security-focused code review persona
persona: researcher
capability: code_review
trigger-pattern: "review.*code|code.*review|security.*audit"
---
You are a strict security-focused code reviewer. Your job is to identify
vulnerabilities, anti-patterns, and security risks in the code presented.
Always reference OWASP categories where applicable.
```

| Frontmatter Field | Required | Description |
|---|---|---|
| `name` | ✅ | Unique skill identifier (used for upsert/delete) |
| `description` | ✅ | Human-readable purpose |
| `persona` | ✅ | Agent role to run under (e.g. `researcher`, `coordinator`) |
| `capability` | ✅ | Capability category for tool schema filtering |
| `trigger-pattern` | ✅ | Java regex matched against incoming message (case-insensitive) |

### Skill Sources

| Source | How | When |
|---|---|---|
| **Built-in (classpath)** | `.md` file in `src/main/resources/skills/` + entry in `index.txt` | Compiled into the binary; requires rebuild to add |
| **Dynamic (database)** | `POST /api/v1/skills` with `X-ArcClaw-API-Key` | No rebuild needed; stored in `AgentSkillEntity` SQLite table |

### Skill REST API

| Endpoint | Method | Description |
|---|---|---|
| `GET /api/v1/skills` | GET | List all loaded skills with source (`classpath` or `database`) |
| `POST /api/v1/skills` | POST | Upsert a skill (create or replace by `name`). Returns `{"message":"Skill saved and registry reloaded: ..."}` |
| `DELETE /api/v1/skills/{name}` | DELETE | Remove a skill by name |
| `GET /api/v1/skills/refresh` | GET | Force a registry reload from both classpath and database |

> [!NOTE]
> All Skill API endpoints require the `X-ArcClaw-API-Key` header. The `/mcp` reflex in Telegram also shows currently loaded skills via the `McpReflex`. Trigger-pattern matching runs **before** intent classification — a matched skill adds zero classification tokens and zero latency from the LLM round-trip.

---

## 🌐 MCP Integration

ArcClaw implements the **Model Context Protocol (MCP)** in both directions: it can serve as an inbound MCP server that external clients connect to, and as an outbound MCP client that connects to external MCP servers.

### MCP Server (Inbound)

ArcClaw exposes two of its internal tools to external MCP clients over JSON-RPC via HTTP Server-Sent Events (HTTP-SSE):

- **Exposed tools by default**: `github`, `memory_search`
- **Protocol endpoint**: `/mcp` (HTTP-SSE streaming)
- **Session management**: `Mcp-Session-Id` header for stateful sessions (MCP-1 initialize → MCP-2 handshake → MCP-3 list tools → MCP-4/5 invoke tools)
- **Allowlist**: `McpToolAllowlist` governs which internal tools are exposed to external MCP clients

```
MCP-1  POST /mcp  initialize        → 200 + Mcp-Session-Id header
MCP-2  POST /mcp  initialized       → 202 handshake complete
MCP-3  POST /mcp  tools/list        → tools array (github, memory_search)
MCP-4  POST /mcp  tools/call (memory_search) → content block + MCP_TOOL_INVOKED audit
MCP-5  POST /mcp  tools/call (github)        → content (or error if GH_TOKEN missing)
```

### MCP Admin API

| Endpoint | Description |
|---|---|
| `GET /api/v1/mcp/status` | Server status: `serverEnabled`, version, exposed tool count |
| `GET /api/v1/mcp/tools` | `exposedInternalTools` (static) + `registeredDynamicTools` (connected outbound) |

### MCP Client (Outbound)

ArcClaw can connect to any external MCP server over HTTP-SSE. Connected tools are registered as `ExternalMcpToolAdapter` instances and become available to agent roles (subject to `McpToolAllowlist`).

| Endpoint | Description |
|---|---|
| `POST /api/v1/mcp/connect` | Connect to an external MCP server. Body: `{"url":"...", "name":"..."}`. Returns `{"status":"connected","toolsRegistered":N}` |
| `POST /api/v1/mcp/disconnect` | Disconnect a server by name. Returns `{"toolsRemoved":N}` |
| `GET /api/v1/mcp/servers` | List all connected external MCP servers |

**Self-connect smoke test**: Connect ArcClaw to its own `/mcp` endpoint to verify the full outbound stack without a separate server. After connecting, `registeredDynamicTools` in `GET /api/v1/mcp/tools` will show the newly registered tools with `mcpServerName:"self"` and `transport:"HTTP_SSE"`.

### `/mcp` Reflex (Telegram / CLI)

Send `/mcp` in any channel to see the current MCP state (zero LLM tokens):

```
🔌 MCP Status
Exposed internal tools (2): github, memory_search
Dynamic MCP tools (2): github [self, HTTP_SSE], memory_search [self, HTTP_SSE]
```

When no external servers are connected:
```
Dynamic MCP tools: none connected
```

---

## 🗃️ Memory & Persistence Architecture

ArcClaw uses a **dual-database SQLite architecture**:

- `arcclaw-meta.db` — primary metadata database (WAL mode): checkpoints, chat memory, cron entities, audit events, encrypted secrets, compaction summaries, skills.
- `arcclaw-fts.db` — standalone FTS5 full-text index: `compaction_summary_fts` table for semantic memory search.

The two databases are connected at runtime via `ATTACH DATABASE`. All writes to SQLite are serialized through a single-threaded `AsyncPersistenceEngine` backed by a `LinkedBlockingQueue`, eliminating WAL contention across concurrent virtual threads.

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryBorderColor': '#4fc3f7', 'lineColor': '#4fc3f7', 'activationBorderColor': '#4fc3f7', 'actorBorder': '#4fc3f7', 'actorLineColor': '#4fc3f7','labelBoxBorderColor': '#0b0c0cff', 'noteBorderColor': '#81c784', 'fontSize': '13px'}}}%%
graph LR

    APE["⚙️ AsyncPersistenceEngine<br/>LinkedBlockingQueue<br/>single writer thread"]:::writer
    CC["🧠 ContextCompactionService<br/>llama-3.1-8b-instant<br/>Summarises rolling window"]:::compactor
    TMS["🔍 TwoTierMemoryService<br/>FTS5 + vec · RRF k=60"]:::searcher
    SR["🗑 SessionReaper<br/>Scheduled GC<br/>Purges stale sessions"]:::reaper

    subgraph DUAL["  🗃  SQLite WAL DUAL-DATABASE  "]
        direction TB
        Meta[("📦 arcclaw-meta.db<br/>─────────────────<br/>checkpoints<br/>chat_memory<br/>agent_cron<br/>audit_event<br/>encrypted_secret<br/>compaction_summary<br/>agent_skill")]:::metadb
        FTS[("🔎 arcclaw-fts.db<br/>─────────────────<br/>compaction_summary_fts<br/>FTS5 full-text index<br/>Queryable via MATCH")]:::ftsdb
        Meta -->|"ATTACH DATABASE<br/>runtime link"| FTS
    end

    APE -->|"serialized writes<br/>eliminates WAL contention"| Meta
    CC -->|"compaction summaries<br/>CONTEXT_COMPACTED audit event"| Meta
    CC -->|"FTS index population<br/>on every compaction"| FTS
    TMS -->|"FTS5 query by userId<br/>returns past session summaries"| FTS
    SR -->|"purge stale sessions<br/>+ orphaned checkpoints"| Meta
```

### Context Compaction (`ContextCompactionService`)

When the rolling chat window exceeds `arcclaw.memory.compaction.threshold` (default: 5 messages), the compaction service fires:

1. Summarizes the oldest messages using `llama-3.1-8b-instant` into a `CompactionSummaryEntity`
2. Discards the original messages from the rolling window
3. Preserves the most recent `keep-recent` (default: 5) messages verbatim
4. Writes the summary into both `arcclaw-meta.db` and `arcclaw-fts.db`
5. Writes a `CONTEXT_COMPACTED` audit event

This enables theoretically unbounded conversation while controlling context window token costs.

### Two-Tier RAG Memory (`TwoTierMemoryService`)

Long-term memory search merges results from **two** retrieval tiers using **Reciprocal Rank Fusion (RRF)**:

| Tier | Backend | Data Source |
|---|---|---|
| **FTS5 (keyword)** | `CompactionSummaryRepository` → `arcclaw-fts.db` | Compaction summaries searched by keyword |
| **Vector (semantic)** | `GlobalKnowledgeBase` + `SessionKnowledgeBase` | Embedded chunks from ingested documents or past context |

**RRF formula**: `score(d) = Σ 1.0 / (k + rank)` where `k = 60` and rank is the 1-based position in each ranked list.

**Graceful degradation**: If `FtsConnectionInterceptor.isVectorSearchEnabled()` returns `false` (sqlite-vec extension unavailable), `TwoTierMemoryService` skips the RRF merge entirely and returns FTS5 results directly — no error, no downtime.

The `vec0.so` native extension (sqlite-vec) must be present for vector search. In the native Docker image it is copied to `/app/lib/vec0.so` and requires `LD_PRELOAD=/lib64/libm.so.6` (for `sqrtf` symbol resolution). In dev mode, set `-Darcclaw.rag.vec-extension-path=../lib/vec0`.

### Long-Term Memory (`MemorySearchTool`)

`MemorySearchTool` queries the two-tier memory service scoped to the current user's `userId` (from `WorkspaceScopeHolder.USER_ID`). The LLM can control the search query but **cannot access another user's memories** — tenant isolation is enforced in Java, not in the SQL query. Roles with `memory_search` in their tool list (coordinator, researcher, architect, synthesizer) can use this to recall decisions made in previous sessions.

### Soft Reset

After a checkpoint reaches `COMPLETED` or `FAILED`, ArcClaw performs a **Soft Reset**: the internal ReAct loop state (tool call history, reasoning step sequence) is wiped, but the LangChain4J `ChatMemoryStore` (the conversational history visible to the LLM) is preserved. This prevents "ghost tool trigger" contamination while keeping the conversation flowing naturally.

---

## 🧩 Structured Output Engine

`arcclaw-core/structured/` provides a typed extraction subsystem for getting structured data out of LLM responses:

| Class | Role |
|---|---|
| `StructuredOutputEngine` | Drives LLM calls and extracts typed output (via `PermissiveJsonExtractor`) from the response |
| `StructuredRetryLoop` | Retries extraction on `JsonExtractionException` up to a configurable limit before failing |
| `PermissiveJsonExtractor` | Lenient JSON parser that tolerates trailing commas, unquoted keys, and other common LLM formatting errors |
| `JsonExtractionException` | Thrown when extraction fails even after lenient parsing |
| `StructuredOutputException` | Higher-level wrapper for structured output failures |

**`@AgentFunction` annotation**: Marks a SAM (Single Abstract Method) interface as a BAML-style agent function. The build-time `AgentFunctionProcessor` (in `arcclaw-deployment`) generates a JSON schema from the return type at compile time, which is embedded in the LLM prompt to constrain its output.

**Built-in structured functions** (defined in `arcclaw-channel-api/structured/`):

| Interface | Return type | Used by |
|---|---|---|
| `SynthesisFunction` | `SynthesisResult` | Dattatreya (synthesizer) — multi-agent result integration |
| — | `AgentContribution` | Per-agent findings passed to synthesizer |
| — | `ConflictSummary` | Structured conflict description between agent outputs |

The synthesizer (Dattatreya) uses `StructuredOutputEngine` + `SynthesisFunction` to produce a typed `SynthesisResult` from combined multi-agent context, rather than free-form text.

---

## ⏰ Autonomous Cron Scheduler

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryBorderColor': '#4fc3f7', 'lineColor': '#4fc3f7', 'activationBorderColor': '#4fc3f7', 'actorBorder': '#4fc3f7', 'actorLineColor': '#4fc3f7','labelBoxBorderColor': '#0b0c0cff', 'noteBorderColor': '#81c784', 'fontSize': '13px'}}}%%
sequenceDiagram
    actor U as 👤 User — Telegram / CLI
    participant AL as 🧠 AgentLoop<br/>ReAct Engine
    participant ST as ⏰ ScheduleTaskTool<br/>Fork-bomb + Pronoun Guard
    participant DB as 🗃 SQLite<br/>agent_cron table
    participant CS as 🔄 AgentCronScheduler<br/>polls every 30s
    participant CJR as ⚙️ CronJobRunner<br/>maxIterations=2
    participant TN as 📬 CronNotificationObserver<br/>Telegram / CLI

    U->>AL: "remind me every day at 9am to check alerts"
    AL->>ST: scheduletask(cronExpression="0 9 * * *",<br/>goal="Check alerts...", runOnce=false)
    ST->>DB: INSERT agent_cron<br/>status=PENDING · nextFireTime calculated
    ST-->>AL: ✅ Scheduled — cronId=abc-123
    AL-->>U: Cron job created successfully

    Note over CS,DB: Polling loop — every 30 seconds

    loop Every 30 seconds
        CS->>DB: SELECT WHERE status=PENDING<br/>AND nextFireTime ≤ now()
        DB-->>CS: job abc-123 ready
        CS->>CJR: run(cronEntity)
        Note over CJR,AL: maxConcurrent=10 · maxIterations=2<br/>Goal prefixed with CRON: for fork-bomb exclusion
        CJR->>AL: agentLoop(goal, role=coordinator,<br/>maxIterations=2)
        AL-->>CJR: AgentResult
        CJR->>DB: UPDATE status=COMPLETED or FAILED<br/>nextFireTime recalculated for recurring jobs
        CJR->>TN: fire CDI AgentJobCompleted event
        TN-->>U: 🕐 Scheduled task result delivered
    end
```

ArcClaw supports fully autonomous scheduled tasks via a built-in cron engine backed by SQLite persistence.

- **`ScheduleTaskTool`**: An agent can create a cron job mid-conversation using a standard 5-field UNIX expression. The goal must be self-contained (naked-pronoun guard rejects vague references). Tasks are prefixed with `CRON:` to enable structural fork-bomb exclusion.
- **`AgentCronScheduler`**: Polls the `agent_cron` table every 30 seconds (configurable via `arcclaw.cron.poll-interval`). Picks up `PENDING` jobs whose next fire time has elapsed.
- **`CronJobRunner`**: Executes the agent loop for the scheduled goal. Supports up to `arcclaw.cron.max-concurrent` (default: 10) concurrent jobs and `arcclaw.cron.max-iterations` (default: 2) reasoning iterations per job to prevent runaway costs.
- **`CronResultHandler`**: On job completion or failure, fires a CDI `AgentJobCompleted` event observed by `TelegramCronNotificationObserver` and `CliCronNotificationObserver` to notify the originating channel.

**Fork-bomb protection**: `ScheduleTaskTool` prefixes every cron goal with `CRON:`. A cron goal that itself contains `CRON:` (nested schedule) is rejected structurally.

**Naked-pronoun guard**: Vague references ("it", "this", "that", "the result") in a cron goal are rejected — the agent is prompted to restate the goal with specific, self-contained instructions.

---

## 🔒 Security Architecture

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryBorderColor': '#4fc3f7', 'lineColor': '#4fc3f7', 'activationBorderColor': '#4fc3f7', 'actorBorder': '#4fc3f7', 'actorLineColor': '#4fc3f7','labelBoxBorderColor': '#0b0c0cff', 'noteBorderColor': '#81c784', 'fontSize': '13px'}}}%%
sequenceDiagram
    actor User
    participant API as 🌐 REST / Telegram / CLI
    participant Vault as 🔐 SecretVault<br/>AES-256-GCM-SIV
    participant Guard as 🛡 ToolInvocationGuard<br/>HITL Gate
    participant Sandbox as 🏖 ProcessIsolationExecutor<br/>Podman → Namespace → Direct
    participant SQLite as 🗃 SQLite WAL<br/>arcclaw-meta.db
    participant FTS as 🔍 FTS5<br/>arcclaw-fts.db
    participant Audit as 📜 Audit Chain<br/>HMAC-SHA256

    User->>API: Send message / goal
    API->>API: ① PiiScanReflex<br/>Redact emails · PAN · SSN · JWTs
    API->>API: ② PromptInjectionReflex<br/>Block jailbreak patterns
    API->>SQLite: Resolve or create session checkpoint

    Note over API,SQLite: ReAct loop begins — every tick checkpointed to SQLite WAL

    API->>Guard: Tool call requested (e.g. github)
    Guard->>Guard: Check ToolOperation vs Autonomy level

    alt NETWORK_OUTBOUND + SUPERVISED
        Guard-->>API: RequiresApprovalException thrown
        API-->>SQLite: Save AWAITING_APPROVAL checkpoint
        API-->>User: 202 — awaiting /approve
        User->>API: /approve or /deny
        API->>SQLite: Resume or fail checkpoint
    end

    Guard->>Vault: Resolve secret<br/>vaultKey → GH_TOKEN
    Vault->>Vault: Argon2id KDF — 64MB · 3 iter<br/>AES-GCM-SIV decrypt
    Vault-->>Sandbox: Raw secret bytes via AutoCloseable

    Sandbox->>Sandbox: Write tmpfs env-file — mode 600
    Sandbox->>Sandbox: Podman rootless<br/>--network=none · --read-only<br/>memory + CPU capped
    Sandbox-->>Sandbox: close() → zero bytes + unlink tmpfs

    Sandbox-->>API: Tool output / observation
    API->>SQLite: AsyncPersistenceEngine<br/>LinkedBlockingQueue — single writer thread
    API->>Audit: Append HMAC row<br/>HMAC-SHA256(prevHash ‖ payload)

    Note over SQLite,FTS: When chat window exceeds threshold — default 5 messages

    API->>SQLite: ContextCompactionService<br/>Summarise oldest msgs via llama-3.1-8b-instant
    SQLite->>FTS: Write compaction_summary_fts<br/>via ATTACH DATABASE
    FTS-->>API: memory_search queries FTS5 + vec<br/>scoped to userId — cross-tenant proof

    API-->>User: Final reply<br/>Audit verifiable at GET /api/v1/audit/{sessionId}/verify
```

### API Authentication & Rate Limiting

All `/api/v1/*` endpoints (except `/api/v1/info`, `/q/health`, `/q/metrics`) require the `X-ArcClaw-API-Key` header. `ApiKeyAuthFilter` validates it against `ApiKeyRegistry` using **constant-time comparison** (preventing timing attacks). Supports multiple named API keys: `ARCCLAW_API_KEY_INTERNAL` and `ARCCLAW_API_KEY_CI`. Each key is tracked in a per-key sliding window — exceeding `requests-per-minute` (default: 20) returns `HTTP 429` with a `Retry-After` header.

- **`DestructiveVocabularyFilter`**: Pre-execution filter that blocks messages containing dangerous shell keywords (e.g., `rm -rf`, `DROP TABLE`, `:(){ :|:& };:`) before they reach the LLM.
- **`StartupSecurityValidator`**: Validates the security posture at startup. In `%prod`, fails fast if weak default secrets are detected. In `%dev`, exempts validation with a warning.
- **`TelegramAllowlistGuard`**: Enforces an allowlist of permitted Telegram user IDs and usernames. Supports open-access mode (empty allowlist = everyone allowed) and silent-block mode.

### Cryptographic Vault

ArcClaw's `SecretVaultImpl` uses a multi-layer cryptographic scheme:

- **Key Derivation**: `Argon2idKeyDerivation` with 64 MB memory, 3 iterations, 1 parallelism thread.
- **Encryption**: `AesGcmSivCipher` — AES-256-GCM-SIV authenticated encryption. Nonce is derived from the vault key + salt to make it deterministic (immune to nonce reuse under key rotation).
- **Pepper**: A server-side secret (`arcclaw.vault.pepper`) mixed into the key derivation, preventing offline dictionary attacks even if the database is exfiltrated.
- **Key Rotation**: Manual rotation via `rotateSecrets` API (Implementation deferred to Phase 64).
- **Memory Safety**: `VaultKey` implements `AutoCloseable`. The `close()` method explicitly overwrites the key byte array with zeros before GC.

**Why AES-GCM-SIV over standard AES-GCM?** Standard AES-GCM is catastrophically broken if a nonce is ever reused with the same key. AES-GCM-SIV (RFC 8452) is nonce-misuse-resistant: a nonce collision degrades to revealing that two plaintexts are identical, rather than leaking the key. This makes it safe for ArcClaw's deterministic nonce derivation strategy (`key + salt → HKDF → nonce`) without a global counter.

Secrets derivation chain:

```
server pepper (env var) + user password
        ↓ Argon2id (64 MB, 3 iterations, 1 parallelism)
        ↓ 256-bit vault key
        ↓ AES-GCM-SIV encrypt (deterministic nonce from key+salt)
        → stored ciphertext + salt + version tag
```

### Sandbox Secret Transport

Secrets for sandboxed tools are transported via `TmpfsSecretTransport`:

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryBorderColor': '#4fc3f7', 'lineColor': '#4fc3f7', 'activationBorderColor': '#4fc3f7', 'actorBorder': '#4fc3f7', 'actorLineColor': '#4fc3f7','labelBoxBorderColor': '#0b0c0cff', 'noteBorderColor': '#81c784', 'fontSize': '13px'}}}%%
graph LR
    SV[SecretVault<br/>AES-GCM-SIV Decrypt] -->|raw bytes| SR[SandboxSecretResolver]
    SR --> TT[TmpfsSecretTransport<br/>tmpfs-backed env-file<br/>mode=600]
    TT -->|--env-file /run/arcclaw/secrets/...| Podman[Podman Container]
    TT -->|AutoCloseable.close| Zero[Zero bytes in JVM + unlink tmpfs file]
```

### Immutable Audit Log (WORM + HMAC Chain)

Every agent action is appended to the `audit_event` table with:

- A **HMAC-SHA256 chain**: Row N's `hmacHash = HMAC-SHA256(prevHash | payload)`. Row 1's `prevHash = SHA256("GENESIS:" + sessionId)`. Tampering any row breaks the chain from that point forward.
- **SQLite WORM triggers** installed by `AuditSchemaEnforcer` on every startup: `UPDATE` and `DELETE` on `audit_event` are rejected at the database level.
- **Mandatory events** (agent halts if write fails — fail-closed): `VAULT_ACCESS`, `TOOL_INVOKED`, `BUDGET_EXCEEDED`, `PROMPT_INJECTION_BLOCKED`, `APPROVAL_GRANTED`, `APPROVAL_DENIED`, `CRONFIRED`, `CRONFAILED`
- Chain integrity is verifiable via `GET /api/v1/audit/{sessionId}/verify`

### Human-in-the-Loop (HITL) Governance

When an agent invokes a tool with `ToolOperation.NETWORK_OUTBOUND` and the session autonomy level is `SUPERVISED` (default), `ToolInvocationGuard` throws `RequiresApprovalException`. The `AgentLoop` saves the checkpoint as `AWAITING_APPROVAL` and returns a `202` response. The operator approves or denies via:

- `POST /api/v1/sessions/{id}/approve?agentRole=coordinator`
- `POST /api/v1/sessions/{id}/deny?agentRole=coordinator`
- In-band via goal strings: `goal: "/approve"` or `goal: "/deny"`
- Via Telegram: send `/approve` or `/deny` to the bot

---

## 🏖️ Sandbox Isolation

ArcClaw executes AI-generated shell commands inside a three-tier isolation system.

| Tier | Condition | Isolation | Notes |
|---|---|---|---|
| **1 — Podman Rootless** | `%prod` + Podman socket up + image present | Full container isolation: `--network=none --read-only`, memory/CPU capped | Secrets injected via tmpfs, never as CLI args |
| **2 — Linux Namespaces** | Podman circuit breaker opened after 3 consecutive failures | `unshare` pid + net + mount namespaces | Fallback, Linux only |
| **3 — Direct Execution** | `%dev` with `strategy: NONE`, or Windows OS detected | None — host `PATH` `ProcessBuilder` | `gh` CLI must be on `PATH`. No isolation. |

**Why Podman over Docker?**

- **No privileged daemon socket**: Docker requires mounting `/var/run/docker.sock` — owning that socket grants root-equivalent access to the host. Podman is daemonless.
- **Rootless by default**: Podman containers run as UID 1000. Even if a sandboxed tool escapes, it's unprivileged on the host.
- **SELinux-native**: Built on UBI 9 (RHEL lineage) where Podman's SELinux label propagation works seamlessly.

**Sandbox Circuit Breaker**: `SandboxCircuitBreaker` trips to `OPEN` state after 3 consecutive Podman failures. Once open, `ProcessIsolationExecutor` falls through to the Linux namespace executor rather than retrying Podman. After a configurable half-open window, it probes Podman again.

---

## 🏥 Health Checks & Observability

### Health Endpoints (`/q/health`)

| Health Check | Type | Validates |
|---|---|---|
| `PodmanHealthCheck` | Liveness | `podman --version` exits successfully |
| `PodmanImageHealthCheck` | Readiness | `arcclaw-tool-runner:latest` image is present and pullable |
| `SystemMetadataHealthCheck` | Readiness | `SystemMetadataRegistry` has version and environment populated |

### Observability Stack

| Service | Port | Purpose |
|---|---|---|
| `otel-collector` | `4317` (gRPC), `4318` (HTTP) | Receives OTLP spans from ArcClaw, forwards to Jaeger |
| `jaeger` | `16686` (UI) | Distributed trace viewer — every agent loop iteration is a child span |
| `prometheus` | `9090` (UI) | Metrics scraper — polls `http://localhost:8080/q/metrics` |

- **Prometheus** (`/q/metrics`): Micrometer metrics exported for token consumption (`arcclaw.llm.tokens`), agent invocations (`arcclaw.agent.invocations`), and Quarkus scheduler metrics.
- **Structured Logging**: MDC-enriched log lines include `sessionId` in every log entry. Log format: `HH:mm:ss LEVEL [class] [sid=<sessionId>] (thread) message`.
- **`MetricsAgentObserver`**: CDI observer wired into `AgentLoop.recordLlmCall()`. Increments Micrometer counters per LLM call. Also emits per-session token counters, session lifecycle counters, and reflex invocation counters.
- **`AgentOtelContext`**: OpenTelemetry span holder for tool execution. Internal reasoning spans are stubbed as no-ops in V1.0.0; HTTP spans are active.

In `%dev` mode, OTEL export is disabled (`quarkus.otel.sdk.disabled=true`). Observe tracing via log output instead.

---

## 🧩 Extending ArcClaw

ArcClaw is designed as a modular kernel. Every major subsystem exposes a clean CDI interface that you can implement once and have it discovered automatically. No wiring code, no registry modifications — just implement, annotate, and deploy.

### 1. Create a New Tool

Implement the `AgentTool` interface and mark it `@ApplicationScoped`. ArcClaw handles LangChain4J spec mapping, governance enforcement, and sandbox routing automatically.

**Simple in-process tool (no sandbox, no secrets):**

```java
@ApplicationScoped
public class MyTool implements AgentTool {
    @Override public String name()        { return "mytool"; }
    @Override public String description() { return "Does something useful."; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.READ_FS; }
    @Override public boolean requiresSandbox()         { return false; }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        String input = (String) params.get("input");
        return ToolResponse.success("Result: " + input);
    }
}
```

**Sandboxed tool with secret injection (Podman + tmpfs):**

For tools that must make network calls or run untrusted binaries, enable sandbox isolation and declare the secrets your command needs. The `ToolRegistry` resolves them from `SecretVault` and injects them via a RAM-backed tmpfs env-file — they never appear in process arguments.

```java
@ApplicationScoped
public class MyNetworkTool implements AgentTool {
    @Override public String name()        { return "mynetworktool"; }
    @Override public String description() { return "Fetches data from an external API."; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.NETWORK_OUTBOUND; }
    @Override public boolean requiresSandbox()         { return true; }

    @Override
    @RequiresSecret(envVar = "MY_API_KEY", vaultKey = "MY_SERVICE_TOKEN")
    public List<String> sandboxCommand(Map<String, Object> params) {
        return List.of("my-cli", "fetch", "--target", (String) params.get("url"));
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        // Only called in %dev / direct-exec mode; Podman path goes through sandboxCommand()
        throw new UnsupportedOperationException("Sandbox required in production.");
    }
}
```

Once deployed, add your tool's name to the permitted tool list for whichever roles should have access in `ModelProducer.getPermittedToolNames()`.

> [!NOTE]
> **Storing secrets at runtime**: Users can store secrets interactively via the Telegram `store <key>=<value>` command (e.g. `store MY_SERVICE_TOKEN=abc123`). They are encrypted with AES-256-GCM-SIV and persisted in `arcclaw-meta.db`. Your `@RequiresSecret(vaultKey="MY_SERVICE_TOKEN")` declaration resolves against this vault automatically.

### 2. Define a New Persona

Create a class implementing `Agent`, tag it with `@AgentRole("your_role")` and `@ApplicationScoped`, and it is instantly available to the REST API and the intent classifier.

```java
@AgentRole("analyst")
@ApplicationScoped
public class AnalystAgent implements Agent {

    @Override
    public String role() { return "analyst"; }

    @Override
    public String systemPrompt() {
        return """
            You are a data analyst. Your only job is to interpret structured data
            and produce clear summaries. You never write code or make network calls.
            """;
    }

    @Override
    public List<String> allowedTools() {
        return List.of("memory_search");
    }
}
```

Then wire its tool permissions into `ModelProducer.getPermittedToolNames()`:

```java
// In ModelProducer (arcclaw-app)
case "analyst" -> Set.of("memory_search");
```

Without this step the persona boots correctly but the LLM receives an empty tool schema. Also add the role to `AgentRoleEnum` with a `RoleDefinition` specifying `maxIterations`, `canUseTools`, and `Set<ToolCapability>`, and add a system prompt template under `src/main/resources/templates/prompts/role-<n>.txt`.

### 3. Register a New Reflex

Implement `AgentReflex`, mark it `@ApplicationScoped`, and return `true` from `matches()` for your trigger pattern. The `ReflexRegistry` discovers it automatically at startup via CDI — no manual registration required.

```java
@ApplicationScoped
public class PingReflex implements AgentReflex {

    @Override
    public boolean matches(String input) {
        return "/ping".equalsIgnoreCase(input.strip());
    }

    @Override
    public ReflexResult handle(MessageContext ctx) {
        return ReflexResult.reply("🏓 Pong! ArcClaw is alive.");
    }
}
```

Keep `matches()` cheap — it is called on the hot path for every inbound message. If your reflex needs to sanitize input before the LLM sees it (like `PiiScanReflex`), override the default `sanitize()` method.

### 4. Add a New Channel Adapter

All channel-specific code lives in `arcclaw-channel-api`. To add a new channel (e.g. Discord, Slack, SMS):

1. Implement `ChannelSender` and register it in `ChannelSenderRegistry` with a matching `ChannelType`.
2. Create a Camel route (or equivalent) that reads messages from the channel and feeds them into `MessagePipeline.process(MessageContext)`.
3. Register the channel's allowlist by implementing `ChannelAllowlist` and contributing it to the pipeline's `AllowlistHandler`.

The entire reflex system, skill routing, intent classification, session routing, and agent execution are channel-agnostic — your new adapter gets all of these for free.

### 5. Add a Custom Skill

Skills require no code. Create a Markdown document with YAML frontmatter and post it via the REST API:

```bash
curl -X POST http://localhost:8080/api/v1/skills \
  -H "X-ArcClaw-API-Key: $API_KEY" \
  -H "Content-Type: text/plain" \
  --data-binary @my-skill.md
```

Where `my-skill.md` follows the two-block format (YAML frontmatter + system prompt separated by `---`). The skill is immediately active — no restart required.

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

*Last updated: ArcClaw V1.0.0 · Quarkus 3.27.2 · JDK 25*
