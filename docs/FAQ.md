# Amsha Agent V0.1.0 - Frequently Asked Questions (FAQ)

---

## 📑 Table of Contents

1. [Architecture & Design](#architecture--design)
2. [Podman vs Docker](#podman-vs-docker)
3. [Language & Framework Choices](#language--framework-choices)
4. [AI & Agent Logic](#ai--agent-logic)
5. [The Reflex System](#the-reflex-system)
6. [Skills System](#skills-system)
7. [MCP Integration](#mcp-integration)
8. [Memory, Compaction & Long-Term Context](#memory-compaction--long-term-context)
9. [RAG & Vector Search](#rag--vector-search)
10. [Structured Output Engine](#structured-output-engine)
11. [Cron Scheduler & Autonomous Tasks](#cron-scheduler--autonomous-tasks)
12. [Security & Secrets](#security--secrets)
13. [Extending Amsha Agent - Tools, Personas & Reflexes](#extending-amsha-agent---tools-personas--reflexes)
14. [Channels & Telegram Integration](#channels--telegram-integration)
15. [Runtime & Operations](#runtime--operations)
16. [Testing](#testing)
17. [Tiered Memory & Self-Evolution](#tiered-memory--self-evolution)

---

## 🏛️ Architecture & Design

### Q: Why is Amsha Agent structured as a multi-module Maven reactor instead of a single application?

The reactor enforces strict architectural separation that prevents accidental dependency creep. The 18 modules are:

- **`amsha-channel-api`** - Channel SPI contracts (6 interfaces: `ChannelPlugin`, `ChannelSender`, `ReplyRenderer`, `ChannelAllowlist`, `ChannelJobNotifier`, `ChannelDaemon`) plus structured output types. Zero implementation — allows `amsha-core` to reference channel concepts without coupling to any specific adapter.
- **`amsha-common`** - Shared utilities and cross-module constants.
- **`amsha-persistence-api`** - Hexagonal architecture port interfaces (`CheckpointStorePort`, `ChatMemoryStorePort`, `CronStorePort`, `SecretStorePort`, `SkillStorePort`, `AuditEventStorePort`, `AuditSchemaPort`, `CompactionStorePort`, `MemoryRetrievalPort`, `AdvisoryLockPort`, `PersistenceAdapterPort`). Core depends on ports, not implementations.
- **`amsha-persistence-common`** - Shared JDBI records/DTOs and `SessionKnowledgeBase` (in-memory embedding store) used by all persistence adapters.
- **`amsha-core`** - Agent runtime kernel. Domain logic, tools, reflexes, governance, sandbox, pipeline, and CDI interfaces. Has zero awareness of REST, Telegram, or CLI.
- **`amsha-persistence-sqlite`** - SQLite adapter implementing all persistence ports. Contains `GlobalKnowledgeBase`, `SessionKnowledgeBase`, WAL configuration, FTS5 setup, and `RagSchemaInitializer`.
- **`amsha-persistence-fs`** - Filesystem-based persistence adapter for environments without SQLite.
- **`amsha-persistence-postgres`** - PostgreSQL adapter (partial: checkpoint, cron, and audit operations only).
- **`amsha-mcp`** - MCP server/client logic. Exposes `DynamicToolLookupImpl` behind the `DynamicToolLookup` interface defined in `amsha-core`. Keeping it separate means the kernel has no compile-time dependency on MCP transport code.
- **`amsha-rag`** - Two-tier RAG memory (sqlite-vec). The `vec0.so` native extension has platform-specific native image metadata. Isolating it prevents this from polluting the core module.
- **`amsha-onboarding`** - First-run setup wizard. Isolated so it can be excluded from headless/CI deployments.
- **`amsha-channel-web`** - WebSocket-based web channel. Can be excluded for Telegram-only or API-only deployments.
- **`amsha-channel-telegram`** - Full Telegram channel implementation with its own test suite. Can be excluded for non-Telegram deployments.
- **`amsha-self-evolve`** - Optional trace capture, pattern extraction, and behavioral-directive synthesis.
- **`amsha-deployment`** - Quarkus build-time processors. Contains the `@BuildStep` logic that wires up `@WorkspaceScoped`, `AgentFunctionProcessor`, `RestResourceBuildStep`, MCP build steps, and RAG resource steps.
- **`ui`** - Frontend (Lit 3.x Web Components + TypeScript + Vite). No Java source; excluded from Maven build.
- **`amsha-app`** - the entry point. Owns all application-layer concerns: REST resources, Telegram routes, CLI commands, scheduler, security filters. It depends on all other modules but **no module depends on `amsha-app`**.

The rule is: **kernel logic never imports application-layer code**. This makes `amsha-core` independently testable with zero Quarkus startup overhead.

---

### Q: Why does Amsha Agent use SQLite instead of a scalable database like PostgreSQL?

Amsha Agent relies on `StatefulAgentStore` to checkpoint the LLM's `ReasoningStep` history at every iteration. There are three reasons SQLite is the right tool:

1. **Zero-latency:** Writing to a local SQLite file avoids network round-trips during the high-speed Agent Loop. Each checkpoint is a single write; adding a network hop would double iteration latency.
2. **Concurrency:** SQLite's WAL (Write-Ahead Logging) mode allows concurrent readers while a single writer commits. Amsha Agent's `AsyncPersistenceEngine` serializes all writes through a `LinkedBlockingQueue` backed by a single virtual thread, eliminating WAL contention entirely.
3. **Portability:** Amsha Agent deploys as a single, self-contained native binary with no external service dependencies. A PostgreSQL sidecar would eliminate that advantage.

The dual-database design (`amsha-meta.db` for structured state, `amsha-fts.db` for FTS5 full-text search) keeps the schema clean and prevents FTS index operations from locking the primary checkpoint tables.

---

### Q: What is the hexagonal persistence architecture?

Amsha Agent uses a port/adapter (hexagonal) architecture for persistence. The `amsha-persistence-api` module defines **port interfaces** (e.g., `CheckpointStorePort`, `ChatMemoryStorePort`, `CronStorePort`, `SecretStorePort`, `AuditEventStorePort`, `CompactionStorePort`, `MemoryRetrievalPort`, `SkillStorePort`, `AdvisoryLockPort`) that the core module depends on. Three adapter modules implement these ports:

- **`amsha-persistence-sqlite`** — Full implementation of all ports. Default backend.
- **`amsha-persistence-fs`** — Filesystem-based implementation. Alternative for environments without SQLite.
- **`amsha-persistence-postgres`** — Partial implementation (checkpoint, cron, audit only). For production deployments requiring a server database.

Swapping backends requires zero core changes — the CDI container discovers the active adapter at runtime. `PersistenceAdapterPort` is the composite interface that aggregates all individual ports.

---

### Q: Why use Virtual Threads instead of reactive Vert.x coroutines?

Agent loops are fundamentally I/O-bound. They spend ~95% of their time blocked on LLM API responses or waiting for Podman sandbox processes to finish. Both Virtual Threads and Vert.x coroutines solve blocking I/O - the difference is ergonomics and safety.

Vert.x reactive code is non-blocking by design but is **collaborative**: if any frame on the event loop blocks, every other request stalls. That makes it brittle for agentic workloads where any reasoning step might block for 10–30 seconds. Virtual Threads (JEP 444, stable in JDK 21) are **preemptive**: the JVM parks the thread at any blocking call and recycles the underlying carrier thread, with zero risk of event-loop starvation. The code also reads exactly like synchronous Java, which keeps the agent loop logic readable and debuggable.

---

### Q: What is `@WorkspaceScoped` and why is a custom CDI scope necessary?

Standard CDI scopes (`@RequestScoped`, `@SessionScoped`) are tied to HTTP lifecycle events. Agent runs on virtual threads - especially async runs and cron jobs - have no HTTP request context. Without a custom scope, any bean holding per-session state would either be shared globally (`@ApplicationScoped`) or lost across thread boundaries.

`@WorkspaceScoped` is a CDI scope keyed on `sessionId`. The `WorkspaceScopeHolder` uses Java 21 `ScopedValue` (not `ThreadLocal`) to bind a workspace context to the logical execution - it propagates correctly across virtual threads, CDI-managed executors, and Camel routes without leaking state between sessions. The `amsha-deployment` module wires this scope up at Quarkus build time via `@BuildStep`.

---

### Q: What is the difference between `ask`, `chat`, `serve`, and `attach` modes?

| Mode | Command | Behaviour |
|---|---|---|
| `ask` | `ask "goal" --role coordinator` | One-shot: run agent, print answer, exit. Ideal for CI/CD scripts. |
| `chat` | `chat [--role <role>] [--session-id <uuid>]` | Interactive REPL with full multi-turn memory, all reflexes, and HITL approval. `--session-id` resumes an existing persisted session. |
| `serve` | `serve` | Long-running REST daemon on port 8080. No channel adapters; for direct API access. |
| `attach` | `attach telegram` | Long-running daemon with channel adapters active. Cron scheduler runs in this mode. |

The `%cli` profile is forced by `amsha-cli.ps1` and silences all Quarkus banners and `INFO` logs so terminal output only shows agent reasoning.

---

### Q: Why does `docker-compose.yaml` need `privileged: true` for the amsha container?

Amsha Agent runs **Podman inside a Docker container** (nested containerization). For rootless Podman to create user namespaces and cgroup hierarchies inside a Docker container, it needs permission to call `unshare()` and `newuidmap`. Docker restricts these syscalls by default. The `privileged: true` flag grants the container the full Linux capability set so Podman can manage nested namespaces correctly. This is a known requirement for container-in-container architectures and is documented in the Podman community as the standard approach for CI/CD environments.

---

## 🐳 Podman vs Docker

### Q: Why does Amsha Agent use Podman instead of Docker for the tool sandbox?

Amsha Agent executes AI-generated shell commands inside isolated containers. The threat model is: *an LLM has crafted a tool invocation that may contain adversarial input*. The sandbox's job is to contain the blast radius. Podman is the right tool for three reasons:

**1. No privileged daemon socket.** Docker requires mounting `/var/run/docker.sock` into the Amsha Agent container to spawn child containers. Owning that socket grants root-equivalent access to the entire host. Podman is daemonless - containers are direct child processes of the `amsha` user process, with no socket exposure.

**2. Rootless by default.** Podman containers run with the calling user's UID (UID 1000 for `amsha`). Even if a sandboxed tool escapes the container, it runs as an unprivileged user on the host. Docker's rootless mode requires manual setup and has limitations around cgroup v2 and storage drivers.

**3. SELinux-native.** Amsha Agent's `Dockerfile.native` is built on UBI 9 (RHEL lineage), where Podman's SELinux label propagation works seamlessly. Docker on the same base often requires manual `--security-opt` overrides.

### Q: Can I swap Podman for Docker in development?

Yes. Set `amsha.sandbox.strategy=NONE` in your local `application.yaml` (the `%dev` default) to bypass container isolation entirely and run tools directly on the host PATH. For production-like testing on a machine without Podman, the three-tier degradation in `ProcessIsolationExecutor` will fall through to Linux `unshare` namespaces as a middle-ground option.

### Q: When does Amsha Agent's sandbox circuit breaker trip?

`SandboxCircuitBreaker` trips to `OPEN` state after **3 consecutive Podman failures**. Once open, `ProcessIsolationExecutor` falls through to the Linux namespace executor (`NamespaceExecutor`) rather than retrying Podman. After a configurable half-open window, it probes Podman again. This means a transient Podman daemon restart or image pull failure doesn't crash every concurrent agent session.

### Q: Key security differences between Podman and Docker at a glance?

| Concern | Podman | Docker |
|---|---|---|
| Daemon | None (daemonless) | Root `dockerd` process |
| Default user | Rootless (unprivileged) | Root (unless configured) |
| Socket risk | No central privileged socket | `/var/run/docker.sock` is a privilege-escalation vector |
| SELinux | Native, first-class | Requires manual configuration |
| Nested containers | Supported without root | Requires `--privileged` or `--cap-add` |
| Idle resource cost | Zero (no daemon) | Persistent `dockerd` memory/CPU |

---

## ☕ Language & Framework Choices

### Q: Why Java 25 / Quarkus instead of Python (LangChain / LangGraph) or Go?

Amsha Agent's design requirements - a custom CDI scope, native image compilation, virtual threads, SQLite WAL concurrency, and Podman process spawning - all land in a sweet spot where Java 25 + Quarkus excels. The specific choices:

**Java over Python:** Python's async model (asyncio/gevent) is cooperative and brittle under sustained I/O load from LLM calls. Java 25 Virtual Threads are preemptive, JVM-managed, and trivially composable with existing blocking libraries (JDBC, ProcessBuilder). The JVM's JIT also gives significantly lower latency on repeated agent-loop iterations after warm-up. Native image compilation via GraalVM further brings startup time to under 100 ms - impossible with a Python interpreter.

**Quarkus over Spring Boot:** Quarkus was designed for GraalVM native image from day one. Spring Native exists but is a retrofit. Quarkus's build-time CDI model (all bean wiring resolved at compile time) is what makes `@WorkspaceScoped` implementable without reflection hacks. Quarkus also ships Micrometer, OTLP, Health, and Scheduler as first-class extensions with native-image metadata pre-configured.

**Java over Go:** Go lacks generics-mature library support for LLM orchestration, CDI-style IoC, and JPA-level SQLite ORM. The LangChain4J ecosystem is also far richer than any Go equivalent, and Go's goroutine model - while excellent - doesn't offer the CDI context propagation mechanism needed for `@WorkspaceScoped` beans.

---

### Q: Why LangChain4J instead of Spring AI or a raw HTTP client to the LLM?

LangChain4J provides three things that would require significant custom code otherwise:

1. **Typed tool calling.** LangChain4J's `JsonObjectSchema` builder generates the tool specification JSON the LLM expects and parses the tool invocation JSON the LLM returns. Writing this correctly (including nullable/required field semantics) by hand is error-prone.
2. **`ChatMemoryStore` abstraction.** Amsha Agent plugs `PersistentChatMemoryStore` (backed by SQLite) directly into LangChain4J's memory contract. This gives windowed rolling history with zero custom serialization code.
3. **Provider portability.** Swapping from Groq → Ollama → OpenAI is a single `application.yaml` change. The reasoning engine code does not change.

Spring AI is a strong alternative but is more opinionated toward the Spring ecosystem. Since Amsha Agent is Quarkus-native, LangChain4J's CDI-friendly design avoids unnecessary Spring context overhead.

---

### Q: Why Groq as the default LLM provider instead of OpenAI or a local model?

Groq's LPU (Language Processing Unit) inference delivers the lowest token/second latency of any hosted API, which directly reduces agent loop iteration time. For a ReAct-style agent that may call the LLM 5–7 times per task, a 3× latency advantage over OpenAI's standard API compounds significantly. Amsha Agent V0.1.0 defaults to `meta-llama/llama-4-scout-17b-16e-instruct` for reasoning and `llama-3.1-8b-instant` for intent classification and compaction - both available on Groq.

Ollama is still provisioned in `docker-compose.yaml` (disabled by default) for fully air-gapped deployments. Switching is a two-line change in `application.yaml`.

### Q: What is OMLX and when should I use it?

OMLX is a provider for local MLX-based OpenAI-compatible inference servers (e.g., `mlx-lm` serving quantized models on Apple Silicon). It defaults to `http://127.0.0.1:8000/v1`; unauthenticated servers can use the `not-needed` default, while protected servers accept a real `AMSHA_OMLX_API_KEY`. Use OMLX when you want fully local, air-gapped inference with models like `mlx-community/Qwen3.6-35B-A3B-4bit`. It implements both `AgentChatModelProvider` (reasoning) and `RoutingChatModelProvider` (intent classification + compaction), so it can serve as a complete end-to-end provider. Set `AMSHA_LLM_PROVIDER=omlx` for the dedicated provider, or map the same endpoint into `AMSHA_GENERIC_OPENAI_*` when exercising it through `generic-openai`.

For a credential-safe connectivity check, keep the `AMSHA_OMLX_*` values in the
ignored root `.env` and run `mise run smoke:local-runtime -- omlx`. The runner validates
the authenticated catalog and a minimal completion without printing headers, prompts,
responses, or credentials.

---

### Q: Why Apache Camel for the Telegram adapter instead of the Bot4J or TelegramBots library?

Apache Camel's `camel-telegram` component handles the long-polling lifecycle, graceful shutdown, and retry logic for the Telegram Bot API automatically. More importantly, Camel's `ManagedExecutor` handoff lets Amsha Agent bridge the Vert.x event loop thread (where Camel routes run) to a Quarkus-managed virtual thread (where the CDI context and `@WorkspaceScoped` beans live) in a single line. Writing the same handoff with a raw Telegram library would require explicit thread pool management and careful CDI context propagation - all code that Camel handles transparently.

---

## 🤖 AI & Agent Logic

### Q: What is the difference between all twelve agent roles?

| Role | Deity | Key Constraint | Use For |
|---|---|---|---|
| `coordinator` | Vishnu | Orchestration tools, memory, ingest_document, web_fetch, scheduling, garuda | General tasks, scheduling, delegation |
| `researcher` | Saraswati | GitHub, web/search/fetch, memory, agent_task, garuda | External data retrieval and source-backed analysis |
| `coder` | Brahma | sandbox_exec, manage_checklist, register_mcp_server, memory, garuda | Code execution and engineering tasks |
| `debugger` | Shiva | GitHub, sandbox_exec, manage_checklist, register_mcp_server, recall_memory_search, garuda | Bug analysis, stack traces, security audits |
| `architect` | Brihaspati | GitHub, memory, garuda; never executes code | ADRs, design reviews, trade-off analysis |
| `synthesizer` | Dattatreya | GitHub, web/search/fetch, scheduling, memory, ingest_document, agent_task, garuda | Cross-domain output integration |
| `optimizer` | Ganesha | garuda; unsupported tools redelegate to their owner | Complex logic and obstacle removal |
| `sentinel` | Kartikeya | garuda; unsupported tools redelegate to their owner | Security scanning and input validation |
| `healer` | Lakshmi | garuda; unsupported tools redelegate to their owner | Error recovery and token optimization |
| `protector` | Durga | garuda; unsupported tools redelegate to their owner | Policy enforcement and guardrails |
| `purifier` | Kali | garuda; unsupported tools redelegate to their owner | Cleanup and lifecycle management |
| `nurturer` | Parvati | ingest_document, garuda | Empathy, tone analysis, sentiment |

Direct access is intentionally narrow. If any persona requests a known tool it does not own, the loop delegates through `garuda` to the tool's owner; approval-resume and parallel-batch paths use the same rule. This preserves least privilege without granting every persona every tool.

The `SkillAwareIntentDetector` checks `SkillRegistry` first (zero classification tokens), then falls through to `llama-3.1-8b-instant` to auto-route incoming messages to the best role. You can also specify a role explicitly via `--role` on the CLI or `agentRole` in the REST payload.

---

### Q: Why do `coder` and `debugger` have empty (or near-empty) tool schemas?

This is a deliberate architectural constraint called **schema filtering**. When the LLM receives a tool schema, it is tempted to use those tools even when the task doesn't require them (tool bias). By passing an empty tool schema to `coder` and a read-only schema to `debugger`, it is *structurally impossible* for those personas to trigger a network call or file mutation - regardless of what the user prompt says. The constraint is enforced in `AgentRoleEnum.definition().toolNames()` and applied in `LangChainReasoningEngine` before the prompt is sent.

---

### Q: My Agent loop randomly failed with: *"System Error: You provided invalid JSON formatting"*. What happened?

This is a **JSON Hallucination** - the LLM generated malformed JSON for a tool invocation (e.g., a trailing comma or unescaped quote). Instead of crashing, `LangChainReasoningEngine` intercepts the `JsonParseException` and returns the error message back to the LLM as a "System Error" observation. This allows the LLM to self-correct on the next iteration. It is most common with smaller local models (`llama3.2:1b`). Groq-hosted Llama 4 Scout is significantly more reliable on structured output.

---

### Q: What prevents the agent from looping forever?

Three layers of protection:

1. **`maxIterations`** cap: configurable per-request (default 5 for REST, 2 for cron jobs). If reached, the loop terminates with `FAILED`.
2. **`AgentSentinels`**: pattern-matching guards inside `AgentStateMachineRunner` that detect stuck states (e.g., repeated identical tool calls, circular reasoning).
3. **Budget cap**: `GovernanceLayer` tracks cumulative USD cost per session. At 80% of `amsha.governance.budget-usd`, it raises a soft `BudgetWarningException`. At 100%, it throws `BudgetExceededException` and transitions the session to `READONLY`, blocking further tool calls.

---

### Q: What is the `AWAITING_APPROVAL` checkpoint state and how does it work?

When the `ToolRegistry` encounters a tool call that requires `SUPERVISED` autonomy and the current autonomy level is `SUPERVISED`, it throws `RequiresApprovalException` instead of executing the tool. The `AgentLoop` catches this, saves the complete reasoning state to SQLite with status `AWAITING_APPROVAL`, and returns a message asking for `/approve` or `/deny`.

When the user sends `/approve`, the `ApproveReflex` loads the checkpoint from SQLite, elevates autonomy to `FULL` for that single invocation, and resumes the loop from the exact iteration where it paused. The approval has a configurable timeout (`amsha.governance.approval-timeout-minutes`, default 60 minutes); expired approvals are reaped by `SessionReaper`.

---

### Q: What is "Task Amnesia" and why is it desirable?

After a checkpoint reaches `COMPLETED` or `FAILED`, Amsha Agent performs a **Soft Reset**: the internal ReAct loop state (tool call history, reasoning step sequence) is wiped, but the LangChain4J `ChatMemoryStore` (the conversational history visible to the LLM) is preserved.

Without this reset, a "ghost" tool trigger from a previous task can contaminate the next request - the LLM sees a prior `ToolExecutionResultMessage` in context and incorrectly assumes it is still mid-task. The soft reset eliminates this poisoning while keeping the conversation flowing naturally.

---

### Q: How does parallel tool execution work?

When the LLM requests multiple tools in a single reasoning step, `ParallelToolStepExecutor` splits the batch into two categories:

1. **Pre-executable tools**: Tools that can run immediately (configured via `amsha.hitl.pre-executable-tools` or in `FULL` autonomy mode). These execute concurrently using `CompletableFuture.allOf()`.
2. **HITL-pending tools**: Tools requiring human approval. These are held until the user approves them.

The results from both categories are merged before the next iteration. In Telegram, `BatchHitlKeyboardBuilder` generates an inline keyboard with one row per pending tool — the user can approve/deny individually or use "Approve All" / "Deny All" buttons.

---

### Q: How does agent delegation work?

Any agent role can use the `garuda` tool to spawn a sub-agent loop for specialist tasks. The `DelegationRoutingProvider` handles routing — it determines which specialist role should handle the delegated goal based on content analysis.

Key constraints:
- **Non-coordinators** are restricted from delegating to roles they lack capabilities for (e.g., a `researcher` cannot delegate to `coder`).
- **Delegation depth** is capped by `amsha.delegation.max-depth` (default: 2) to prevent infinite delegation chains.
- Sub-agents run with their own isolated context and report results back to the parent agent as an observation.

### Q: What are Lokas (grouped agent ensembles)?

Personas are organized into **4 Lokas (realms)** based on mythological residence. Each realm has a group lead who triages incoming tasks:

| Realm | Lead | Members | Domain |
|---|---|---|---|
| **Kailasa** | Shiva (debugger) | Parvati, Ganesha, Kartikeya, Durga, Kali | Debugging, security, logic, policy, cleanup |
| **Brahmaloka** | Brahma (coder) | Saraswati, Brihaspati | Coding, research, architecture |
| **Vaikunta** | Vishnu (coordinator) | Lakshmi | Orchestration, scheduling, healing |
| **Loka-Bahir** | Dattatreya (synthesizer) | — | Cross-realm conflict resolution |

**Group Consultation:** When a task requires multiple perspectives (e.g., "fix SQL injection AND optimize recursion"), the coordinator can trigger `mode='group_consult'` via the `garuda` tool. This spawns parallel virtual threads for relevant family members, collects their analyses, and the group lead synthesizes a unified response.

**Dynamic Group Formation:** When a task spans domains that don't fit a single realm, `TaskClassifierService` classifies the task into domains and forms a dynamic group with the primary domain's role as lead.

**Group-Aware Escalation:** When a persona fails, `EscalationPatterns.tryIntraGroupEscalation()` prefers routing to a sibling within the same realm before escalating externally. This ensures intra-realm expertise is leveraged first.

Config: `amsha.group.consult.max-concurrent` (default `4`), `amsha.group.consult.timeout-seconds` (default `60`), `amsha.group.analytics.enabled` (default `true`).

---

## ⚡ The Reflex System

### Q: What is the Reflex system and why doesn't it use the LLM?

A `AgentReflex` is a deterministic fast-path that intercepts specific input patterns before they reach the reasoning engine. Reflexes are evaluated by `ReflexRegistry` on every message. If one matches, its response is returned immediately with **zero LLM tokens consumed**.

This matters for three reasons:

1. **Cost:** Commands like `/approve`, `/status`, and `/history` are intent-unambiguous. Routing them through an LLM classification step wastes tokens and adds latency.
2. **Safety:** The `PromptInjectionReflex` must fire *before* the LLM sees the input - if an adversarial prompt could reach the reasoning engine, it might influence the model to bypass governance.
3. **Reliability:** Reflexes are pure Java - they cannot hallucinate, and their behaviour is deterministic and fully unit-testable.

---

### Q: How does `PiiScanReflex` work and can I change its behaviour?

`PiiScanReflex` runs on every message (via the `sanitize()` hook in `ReflexRegistry`) using a set of compiled `Pattern` objects that detect emails, phone numbers, Aadhaar, PAN, credit card numbers, US SSNs, IP addresses, and JWT tokens. It has two modes, controlled by `amsha.pii.mode`:

- **`REDACT_AND_FORWARD`** (default): Replaces detected PII with placeholder tokens (e.g., `[EMAIL]`, `[PHONE]`) and passes the sanitized message to the LLM. The user sees no interruption.
- **`BLOCK_AND_NOTIFY`**: Rejects the message outright and returns an error. Use this in regulated environments where PII must never touch the LLM API.

Note: `store <key>=<value>` messages bypass PII scanning entirely in `ReflexRegistry` - this prevents secret values (like a GitHub PAT that might match the JWT pattern) from being incorrectly redacted.

---

### Q: How does `PromptInjectionReflex` protect against jailbreaks?

It maintains four categories of compiled patterns:

1. **Fake approval bypass** - patterns like "human approved", "auto-approve", "this has been pre-approved", "skip governance".
2. **System prompt extraction** - patterns like "reveal your instructions", "repeat everything", "show your system prompt".
3. **Role override attempts** - patterns that try to redefine the agent's persona mid-conversation.
4. **Security bypass vocabulary** - patterns from `DestructiveVocabularyFilter` (shell destructive commands).

If any pattern matches, the message is blocked before it reaches the intent classifier or agent loop. The block is logged as a `SecurityEventType.PROMPT_INJECTION_ATTEMPT` audit event.

The `ToolObservationSanitizer` also runs both `PromptInjectionReflex.sanitize()` and `PiiScanReflex.redactPii()` on **tool output** before appending it to agent history - so adversarial content in a tool's stdout cannot inject instructions into the reasoning context.

---

### Q: What does the `/mcp` reflex do?

`McpReflex` handles the `/mcp` command and returns the current MCP status with zero LLM tokens:

```
🔌 MCP Status
Exposed internal tools (3): github, recall_memory_search, archival_memory_search
Dynamic MCP tools: none connected
```

Discovery through `POST /api/v1/mcp/connect/discover` is a throwaway preview. A durable connect through `POST /api/v1/mcp/connect` persists the server and its discovered tools disabled; only an approved per-tool enable operation registers a dynamic tool for that user. The response and `/mcp` status show the server name, state, transport, and enabled/discovered counts.

---

## 🎯 Skills System

### Q: What is the Skills system and how does it differ from the Reflex system?

Both skip the LLM in different ways:

- **Reflexes** handle *operational commands* (`/approve`, `/halt`, `/status`, etc.) and *security filters* (PII, injection). They return a direct response and end the pipeline immediately.
- **Skills** handle *content routing* - they match a user's natural-language query against a trigger pattern and inject a custom system prompt, then run the full agent loop with that persona. The agent still reasons and may still call tools; only the intent classifier step is skipped.

In short: a reflex replaces the LLM entirely. A skill replaces *only the intent classification step* and adjusts the persona.

---

### Q: How do I create a skill?

Skills are two-block Markdown documents with YAML frontmatter:

```markdown
---
name: code-reviewer
description: Security-focused code review persona
persona: researcher
capability: code_review
trigger-pattern: "review.*code|code.*review|security.*audit"
---
You are a strict security-focused code reviewer. Identify vulnerabilities
and anti-patterns. Always reference OWASP categories.
```

To deploy dynamically (no rebuild):

```bash
curl -X POST http://localhost:8080/api/v1/skills \
  -H "X-Amsha-API-Key: $API_KEY" \
  -H "Content-Type: text/plain" \
  --data-binary @my-skill.md
```

To deploy as built-in (requires rebuild): place the `.md` file in `src/main/resources/skills/` and add the filename to `index.txt`.

---

### Q: What happens if two skills have overlapping trigger patterns?

`SkillRegistry` evaluates skills in load order (built-in classpath skills first, then database-tier skills). The first match wins. To avoid conflicts, make trigger patterns as specific as possible. If you need to replace a built-in skill, upsert a database-tier skill with the same `name` - it will shadow the built-in entry.

---

### Q: How do I update an existing skill without creating a duplicate?

`POST /api/v1/skills` is an **upsert** - if a skill with the same `name` already exists in the database tier, it is replaced in-place and the registry is reloaded. You will see `{"message":"Skill saved and registry reloaded: <name>"}` in the response for both new and updated skills.

---

### Q: How do I force a skill registry refresh after a manual database change?

```bash
curl -s http://localhost:8080/api/v1/skills/refresh \
  -H "X-Amsha-API-Key: $API_KEY"
```

This reloads both classpath built-in skills and database-tier skills without restarting the application.

---

## 🌐 MCP Integration

### Q: How do I manage remote MCP servers now?

Open `#/mcp` for the dedicated management panel. It supports multiple collapsible server cards, throwaway
discovery, approval-gated connect, per-tool enable/disable, pause/resume/reconnect, disconnect, endpoint or
credential editing, and upload-only `.mcp.json` import. Discovery never persists a connection; connecting
persists the server with every discovered tool disabled. A status such as `1/56 tools` means one tool is
explicitly enabled out of 56 discovered tools.

The same operations are available through `/mcp help` in chat. MCP approvals use `/mcp approve <task-id>`
and `/mcp deny <task-id>` or the MCP approval REST endpoints; this is separate from global agent `/approve`.

### Q: Why does MCP status differ between local UI and native CLI?

Connections are user-scoped. Authenticated requests use the authenticated principal. In local web mode,
the browser platform ID is only a continuity fallback when `AMSHA_WEB_REQUIRE_AUTH=false`. Native CLI chat
uses the claimed local bootstrap owner when available and falls back to `cli-user` before onboarding. Restart
the rebuilt native binary after changing it. Different users, browser tabs in unauthenticated mode, or data
directories intentionally do not share connections.

### Q: What is the Model Context Protocol (MCP) and how does Amsha Agent implement it?

MCP is an open protocol for LLM tool interoperability over JSON-RPC. Amsha Agent implements it in both directions:

- **Inbound MCP server**: External MCP clients (e.g. Claude Desktop, other agents) can connect to Amsha Agent's `/mcp` endpoint and call its `github`, `recall_memory_search`, and `archival_memory_search` tools via JSON-RPC over HTTP-SSE.
- **Outbound MCP client**: Amsha Agent can connect to any external MCP server (`POST /api/v1/mcp/connect`). The connected server's tools are registered as `ExternalMcpToolAdapter` instances, becoming natively available to agent roles in the tool schema.

---

### Q: Which internal tools does Amsha Agent expose as an MCP server?

By default, `github`, `recall_memory_search`, and `archival_memory_search` are exposed. The `McpToolAllowlist` configuration controls this allowlist. Disallowed tool names return a JSON-RPC error with a descriptive message rather than executing.

---

### Q: How does the MCP session lifecycle work?

```
1. POST /mcp  {"method":"initialize"}      → 200, Mcp-Session-Id in response header
2. POST /mcp  {"method":"initialized"}     → 202, handshake complete
3. POST /mcp  {"method":"tools/list"}      → tools array (github, recall_memory_search, archival_memory_search)
4. POST /mcp  {"method":"tools/call",...}  → content block + MCP_TOOL_INVOKED audit event
```

Always run `GET /api/v1/mcp/status` before starting the protocol sequence to confirm `serverEnabled:true`. If the server is disabled, step 1 returns 404 and you will have no session ID.

---

### Q: How do dynamic MCP tools appear to agents?

After connecting an external MCP server, its tool descriptions are persisted as disabled policies. An approved enable operation registers the selected tool as a `DynamicToolCapability` in the user-scoped `DynamicToolCapabilityRegistry`. `DynamicToolLookupImpl` (in `amsha-mcp`) provides the `DynamicToolLookup` implementation used by `ToolRegistry`. The enabled tool then appears in the LLM's tool schema for roles that permit dynamic tools, and `ExternalMcpToolAdapter` dispatches calls to the remote server via `McpClientService`.

---

### Q: What is the self-connect smoke test?

Connect Amsha Agent to its own `/mcp` endpoint:

```bash
curl -X POST http://localhost:8080/api/v1/mcp/connect \
  -H "X-Amsha-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"url":"http://localhost:8080/mcp","name":"self"}'
```

The first response is an approval task. Approve it through the MCP approval endpoint, then `GET /api/v1/mcp/connect` should show a durable `CONNECTED` row with `enabledToolCount:0` and a non-zero `discoveredToolCount`. `GET /api/v1/mcp/tools` should still show `registeredDynamicTools` with 0 entries because built-in `/mcp` tools are intentionally not re-registered as dynamic tools. This remains a full outbound MCP handshake smoke test without creating internal-name collisions.

---

## 🧠 Memory, Compaction & Long-Term Context

### Q: What happens when a conversation gets very long?

Amsha Agent uses a rolling window `ChatMemoryStore` capped at `amsha.chat-memory.max-messages` (default 20). When the window fills, the `CompactionHandler` in the message pipeline triggers `ContextCompactionService` if the message count exceeds `amsha.memory.compaction.threshold` (default 5 above the keep-recent count).

Compaction uses `llama-3.1-8b-instant` to summarize the oldest messages into a `CompactionSummaryDto`, discards the originals from the rolling window, and preserves the most recent `keep-recent` (default 5) messages verbatim. The summary is also written into the FTS5 database (`amsha-fts.db`) so it can be recalled later via `MemorySearchTool`.

---

### Q: What is the `MemorySearchTool` and how is long-term memory retrieved?

`MemorySearchTool` queries the two-tier memory service (`TwoTierMemoryService`) scoped to the current user's `userId` (from `WorkspaceScopeHolder.USER_ID`) - the LLM can control the search query but **cannot access another user's memories**. Tenant isolation is enforced in Java, not in the SQL query.

The search merges FTS5 keyword results and sqlite-vec vector results using Reciprocal Rank Fusion (RRF, k=60). If the vector extension is unavailable, it degrades silently to FTS5-only results.

Agents with `recall_memory_search` in their tool list (coordinator, researcher, architect, synthesizer) can use this to recall decisions made in previous sessions.

---

### Q: What is the `backfill_fts5.sql` script for?

It is a **one-time migration script** to be run after deploying the FTS5 feature on a database that already has `compaction_summary` rows. It backfills the `user_id` column (using `audit_event` as the source of truth) and populates the `compaction_summary_fts` FTS index for all existing rows. New rows written after the deployment are indexed automatically.

---

## 🔍 RAG & Vector Search

### Q: What is the two-tier RAG architecture?

Amsha Agent's `TwoTierMemoryService` merges results from two sources using **Reciprocal Rank Fusion (RRF)**:

- **Tier 1 - FTS5 (keyword)**: `CompactionSummaryRepository` queries `amsha-fts.db` using SQLite's FTS5 full-text search. Results are ranked by keyword relevance.
- **Tier 2 - Vector (semantic)**: `GlobalKnowledgeBase` (application-scoped, always active) and `SessionKnowledgeBase` (`@WorkspaceScoped`, active only when a workspace is live) store embedded chunks. Queries use cosine similarity via sqlite-vec.

**RRF merge formula**: `score(d) = Σ 1.0 / (60 + rank)` across both ranked lists. This outperforms either method alone on mixed-domain queries.

---

### Q: What is sqlite-vec and how does Amsha Agent use it?

`sqlite-vec` (the `vec0.so` extension) adds vector similarity search to SQLite as a virtual table extension. Amsha Agent uses it for the second tier of RAG. The extension is loaded at runtime by `FtsConnectionInterceptor` using `amsha.rag.vec-extension-path` (default: `./lib/vec0`, native Docker: `/app/lib/vec0`). The `FtsConnectionInterceptor` automatically performs a recursive parent-directory lookup (up to 4 levels) to resolve the extension's absolute path if executing inside a submodule directory during testing or local development.

**Native image requirement**: `vec0.so` links against `libm.so.6` for math functions (`sqrtf`). GraalVM native image does not statically link `libm`, so `LD_PRELOAD=/lib64/libm.so.6` must be set before the native binary starts. Without it, `dlopen(vec0.so)` fails with `undefined symbol: sqrtf` and vector search degrades silently to FTS5-only.

---

### Q: What happens if sqlite-vec is unavailable?

`FtsConnectionInterceptor.isVectorSearchEnabled()` checks at startup whether the extension loaded successfully. If not, `TwoTierMemoryService` takes the **fast path**: it skips the RRF merge entirely and returns FTS5 results directly. No error is thrown, no agent functionality is lost - only the semantic vector search dimension is unavailable. Check boot logs for: `vector search disabled - vec0 extension not loaded`.

---

### Q: What are `GlobalKnowledgeBase` and `SessionKnowledgeBase`?

- **`GlobalKnowledgeBase`** (`@ApplicationScoped`): An in-memory embedding store loaded at startup. It holds globally-ingested documents (e.g., from `IngestDocumentTool`) that persist for the application lifetime. All sessions share it.
- **`SessionKnowledgeBase`** (`@WorkspaceScoped`): An in-memory embedding store scoped to a single session. Documents ingested during a session are available for the lifetime of that session only. It is wiped by `@PreDestroy` when the workspace is torn down.

---

### Q: How do I ingest documents for RAG retrieval?

Use `IngestDocumentTool` (tool name: `ingest_document`). It accepts document content and a scope (`global` or `session`) and chunks + embeds the document into the appropriate knowledge base. For persistent global knowledge, use `global` scope - it survives application restarts if the embedding store is backed by a persistent store. For session-scoped ephemeral context, use `session` scope.

---

## 🧱 Structured Output Engine

### Q: What is the Structured Output Engine and when is it used?

`StructuredOutputEngine` provides typed extraction of structured data from LLM free-text responses. Instead of asking the LLM for JSON and manually parsing it, you define a Java record or class as the target type and let the engine drive the prompt-and-parse loop.

It is primarily used by the **synthesizer (Dattatreya)** agent when integrating multi-agent findings into a typed `SynthesisResult`.

---

### Q: What is `PermissiveJsonExtractor` and why is it needed?

LLMs frequently produce JSON with minor formatting errors: trailing commas, single-quoted strings, unquoted keys, or extra whitespace. Standard `ObjectMapper` parsing fails on all of these. `PermissiveJsonExtractor` uses a lenient parsing strategy that tolerates these common LLM outputs without throwing. If lenient parsing also fails, it throws `JsonExtractionException` so `StructuredRetryLoop` can retry with a corrected prompt.

---

### Q: What is the `@AgentFunction` annotation?

`@AgentFunction` marks a SAM interface as a BAML-style typed agent function. The build-time `AgentFunctionProcessor` (in `amsha-deployment`) generates a JSON schema from the interface's return type at compile time. This schema is embedded in the system prompt to constrain the LLM's output format, making extraction reliable without prompt engineering per-call.

Built-in examples: `SynthesisFunction` returns `SynthesisResult` (synthesizer's final answer), which may reference `AgentContribution` (per-agent findings) and `ConflictSummary` (structured conflict description between agents).

---

## ⏰ Cron Scheduler & Autonomous Tasks

### Q: How does the autonomous cron scheduler work end-to-end?

1. A user asks an agent to schedule a future task. The `coordinator` or `synthesizer` persona invokes `ScheduleTaskTool` with a 5-field UNIX cron expression, a self-contained goal, an optional timezone, and a `runOnce` flag.
2. The tool validates the cron expression (via `cron-utils`), computes `nextFireTime`, and writes an `AgentCronDto` with status `PENDING` to the `agent_cron` SQLite table.
3. `AgentCronScheduler` polls every 30 seconds (`amsha.cron.poll-interval`). It picks up `PENDING` jobs whose `nextFireTime ≤ now()` and hands them to `CronJobRunner`.
4. `CronJobRunner` executes an agent loop (capped at `amsha.cron.max-iterations`, default 2) for the cron goal. On completion, it fires a CDI `AgentJobCompleted` event.
5. `TelegramCronNotificationObserver` (or `CliCronNotificationObserver`) observes the event and delivers the result to the originating chat.

---

### Q: What safeguards prevent a cron job from spawning another cron job (fork bomb)?

Two layers:

1. **Goal prefix guard:** `ScheduleTaskTool` prefixes every cron goal with `CRON:`. The cron runner checks for this prefix before execution. A cron goal that itself contains `CRON:` (indicating a nested schedule attempt) is rejected structurally. The `CronJobRunner` also appends `[SYSTEM CONSTRAINT: Do NOT call scheduletask. Execute the goal directly.]` to every cron goal.
2. **Naked-pronoun guard:** `ScheduleTaskTool` runs a regex check for vague references ("it", "this", "that", "the result") before persisting the goal. If matched, the tool returns an error asking the agent to restate the goal with specific, self-contained instructions.

---

### Q: How do I list, inspect, or cancel a scheduled cron job?

Three ways:

- **Telegram / CLI:** Send `/crons` or `/list crons` to get a formatted list. Send `deletecron <id>` to cancel by ID. These are zero-token reflexes.
- **Agent tool:** Ask the coordinator agent "list my scheduled tasks" - it will invoke `ManageScheduleTool` with `action=list`. Ask it to "delete cron job abc-123" to invoke `action=delete`.
- **Direct SQL (admin):** `UPDATE agent_cron SET status = 'CANCELLED' WHERE id = 'abc-123';` on `amsha-meta.db`.

---

## 🔒 Security & Secrets

### Q: How does Amsha Agent store and protect secrets (e.g., `GITHUB_TOKEN`)?

Secrets are stored in the `encrypted_secret` SQLite table encrypted with AES-256-GCM-SIV. The key derivation chain is:

```
server pepper (env var) + user password
        ↓ Argon2id (64 MB, 3 iterations, 1 parallelism)
        ↓ 256-bit vault key
        ↓ AES-GCM-SIV encrypt (deterministic nonce from key+salt)
        → stored ciphertext + salt + version tag
```

When a sandboxed tool needs a secret, `SandboxSecretResolver` decrypts it from the vault, passes the raw bytes (never a String) to `TmpfsSecretTransport`, which writes them to a `mode=600` tmpfs-backed env-file under `/run/amsha/secrets/`. Podman receives `--env-file <path>`. After the container exits, `TmpfsSecretTransport.close()` overwrites the byte array with zeros and unlinks the tmpfs file.

Secrets **never** appear in process arguments, JVM heap Strings, or on-disk files.

---

### Q: Why use AES-GCM-SIV instead of standard AES-GCM?

Standard AES-GCM is catastrophically broken if a nonce is ever reused with the same key (nonce-misuse attack). In a multi-threaded server under key rotation, guaranteeing nonce uniqueness requires a synchronized counter or random 96-bit nonces with collision risk at high call volumes. AES-GCM-SIV (RFC 8452) is nonce-misuse-resistant: a nonce collision degrades to revealing that two plaintexts are identical, rather than leaking the key. This makes it safe for Amsha Agent's deterministic nonce derivation strategy (`key + salt → HKDF → nonce`) without a global counter.

---

### Q: What does `StartupSecurityValidator` check?

At application startup it validates:

- All production API keys are non-default (rejects `key-internal-changeme`, `key-ci-changeme`).
- The vault pepper is at least 32 bytes and is not the dev placeholder value.
- The audit HMAC key is at least 32 bytes and is not the dev placeholder.
- In `%prod` profile, any detected weak secret causes a hard `IllegalStateException` that aborts startup.
- In `%dev` profile, violations log a `WARN` but do not abort startup.

This fail-fast pattern ensures misconfigured production deployments are caught at boot, not at first user request.

---

### Q: How does the API rate limiter work?

Read and mutation requests use separate per-key buckets. Read requests use
`amsha.api.rate-limit.requests-per-minute` (default 20); mutating requests use
`amsha.api.rate-limit.mutation-per-minute` (default 10). Onboarding draft
creates, step updates, commits, and discards use the mutation bucket, so they
can return `HTTP 429` when that bucket is exhausted even if the general request
bucket still has capacity. The valid API key is still required; this only
controls throttling.

`ApiKeyAuthFilter` extracts the `Authorization: Bearer <key>` header and validates it against `ApiKeyRegistry` using constant-time comparison (preventing timing attacks). Either bucket returns `HTTP 429` with a `Retry-After` header when exhausted. The implementation uses `AtomicLong` counters and does not require a Redis sidecar.

---

## 🔧 Extending Amsha Agent - Tools, Personas & Reflexes

### Q: What is the minimal contract for adding a new tool?

Implement `AgentTool`, annotate the class `@ApplicationScoped`, and Amsha Agent discovers it automatically via CDI `Instance<AgentTool>`. The required methods:

```java
@ApplicationScoped
public class MyTool implements AgentTool {
    @Override public String name() { return "mytool"; }
    @Override public String description() { return "LLM-visible description..."; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.READ_FS; }
    @Override public boolean requiresSandbox() { return false; }
    @Override public JsonObjectSchema parametersSchema() { /* define params */ }
    @Override public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) { /* logic */ }
}
```

If your tool needs secrets, annotate `sandboxCommand()` (not `execute()`) with `@RequiresSecret`. The registry reads these annotations reflectively.

If your tool needs sandbox isolation, set `requiresSandbox()` to `true`, implement `sandboxCommand()` to return the OS command tokens, and the `ToolRegistry` will route execution through `ProcessIsolationExecutor` automatically.

---

### Q: How do I make a new tool available only to specific agent roles?

Add your tool's `ToolCapability` entry to the `Set<ToolCapability>` inside the relevant `AgentRoleEnum` entries. Tool schema filtering is applied in `LangChainReasoningEngine.getPermittedToolNames()` - it intersects the agent's declared `allowedTools()` with the full registered tool list. Any tool not in the intersection is **invisible to the LLM** for that role, regardless of what the user prompt says.

---

### Q: How do I define a new agent persona?

Per Architecture Invariant §4.4, agent personas are **data-driven via Qute templates**, not class-per-persona inheritance. The steps are:

1. Add the role to `AgentRoleEnum` with a `RoleDefinition` specifying `maxIterations`, `requiresApproval`, `codeCapable`, and capabilities. Tool capabilities are dynamically assembled at startup via `@ToolMetadata` annotations on tool classes — you don't need to list them in `RoleDefinition`.
2. Create a Qute system prompt template under `src/main/resources/templates/prompts/role-<name>.txt`. The template engine injects `sortedCapabilities`, `roleDef`, and safety/compaction includes automatically.
3. Tools grant themselves to roles via `@ToolMetadata(defaultRoles = {"your_role", ...})`. Add a case to every exhaustive switch over `AgentRoleEnum` — the compiler will flag missing cases.
4. Optionally register the role in `application.yaml` under `amsha.agents.roles` for REST API description and intent classifier hints.

The new persona is immediately available via `--role your_role` on the CLI and `agentRole: "your_role"` in the REST API.

> [!IMPORTANT]
> Class-per-persona inheritance (`BrahmaAgent`, `ShivaAgent`, etc.) is **forbidden** by Architecture Invariant §4.4. All twelve built-in personas use the single `DefaultAgent` implementation with Qute-rendered system prompts.

---

### Q: How do I add a new Reflex?

Implement `AgentReflex`, annotate `@ApplicationScoped`, return `true` from `matches(String input)` for your trigger, and implement `execute()`. The `ReflexRegistry` discovers it via CDI automatically. Order of reflex evaluation is unspecified - make sure your `matches()` pattern is specific enough not to shadow other reflexes.

If your reflex needs to sanitize input before the LLM sees it (like `PiiScanReflex`), override the default `sanitize()` method.

---

### Q: Can I add a new channel adapter (e.g., Discord, Slack)?

Yes. The `amsha-channel-api` module defines a **6-interface SPI contract**. To add a channel, implement all six interfaces and wire them in a `ChannelPlugin` composite bean:

1. **`ChannelPlugin`** — composite registration bean wiring all 5 components together.
2. **`ChannelSender`** — sends `List<ReplyNode>` to the channel (handling the split into `ContentNode` and `InteractionNode` with chunking, edit support, etc.).
3. **`ReplyRenderer`** — converts the `ReplyNode` AST (split into `ContentNode` and `InteractionNode`) to channel-native format (e.g., MarkdownV2 for Telegram).
4. **`ChannelAllowlist`** — determines if a `ChannelUser` is authorized (supports open-access and silent-block modes).
5. **`ChannelJobNotifier`** — sends cron completion and approval timeout notifications.
6. **`ChannelDaemon`** — daemon lifecycle: `isConfigured()`, `start()` (e.g., starts Camel route).

The entire reflex system, skill routing, intent classification, session routing, and agent execution are channel-agnostic — your new adapter gets all of these for free. See [`CHANNEL_SPI.md`](./CHANNEL_SPI.md) for a complete step-by-step guide.

---

## 📱 Channels & Telegram Integration

### Q: How does session continuity work across Telegram bot restarts?

The durable `session_catalog` is the active mapping, scoped by `(ownerKey, channelType, channelId)`. As long as the persistence volume is mounted, Telegram resumes that scope's active session across bot restarts. The older `session_registry` table is consulted only to lazily migrate pre-catalog mappings.

Use `/newsession` to preserve the current history and open a fresh session, `/sessions` to list this chat's resumable history, and `/resume <number-or-id>` to switch back. `--last-session` is a CLI-only startup option; Telegram and WebSocket select their active catalog session automatically on inbound messages.

---

### Q: What does `TelegramMarkdownV2Renderer` do?

Telegram's MarkdownV2 format requires escaping every special character (`_`, `*`, `[`, `]`, `(`, `)`, `~`, `` ` ``, `>`, `#`, `+`, `-`, `=`, `|`, `{`, `}`, `.`, `!`) that appears outside of formatting syntax. Raw LLM output routinely contains these characters. `TelegramMarkdownV2Renderer` traverses Amsha Agent's custom `ReplyNode` tree and correctly escapes all plain-text content while preserving bold, italic, code, and link formatting. Without this renderer, Telegram rejects messages with a `Bad Request: can't parse entities` error.

---

### Q: What happens when a Telegram user I have not allowlisted sends a message?

`TelegramAllowlistGuard` is evaluated in the `AllowlistHandler` at the start of the `MessagePipeline`. If the user's Telegram user ID or username is not in `AMSHA_TELEGRAM_ALLOWED_USER_IDS` / `AMSHA_TELEGRAM_ALLOWED_USERS`, the message is rejected before it reaches the intent classifier or agent loop.

If `amsha.channel.telegram.silent-block=false` (default), the user receives the configured `block-response` message (default: `⛔ Access denied.`). If `silent-block=true`, the message is dropped silently with no reply - useful when you do not want to reveal that the bot is running.

An empty allowlist (`AMSHA_TELEGRAM_ALLOWED_USERS=`) allows all users - this is the open-access mode used in development.

---

## ⚙️ Runtime & Operations

### Q: What happens if a Sandbox tool returns a massive payload (e.g., a 10,000-line file)?

Amsha Agent applies **defensive truncation** via `ToolObservationSanitizer`. Tool observations exceeding 16 KiB (16,384 bytes) are truncated before being appended to the LLM's reasoning history. Feeding a 10k-line file directly into the context would exceed the model's token limit and cause an unrecoverable API error. The truncation is logged at `WARN` level so you can identify over-verbose tools.

In addition, `ToolObservationSanitizer` also runs prompt injection redaction and PII scrubbing on tool output before it reaches the LLM.

---

### Q: I am getting `Permission denied` when SQLite tries to write to the `amsha_data` Docker volume. Why?

Docker creates named volumes as `root:root`. Amsha Agent runs as `amsha` (UID 1000) inside the container. The fix is the **Init-Container pattern**: the `init-perms` service in `docker-compose.yaml` briefly boots as root, runs `chown -R 1000:1000 /app/data`, and exits before the main `amsha` container starts (`depends_on: init-perms: condition: service_completed_successfully`).

---

### Q: Why does the `containers.conf` force the `vfs` storage driver for Podman?

Inside a Docker container, standard storage drivers (`overlayfs`, `btrfs`) fail because the host kernel restricts unprivileged mounts inside containers. The `vfs` (Virtual File System) driver is a universal fallback: it creates deep copies of container layers instead of copy-on-write snapshots. It is slower and more disk-intensive than `overlayfs`, but it works in any environment including Docker-in-Docker and Kubernetes pods without `privileged` storage. Run `podman system prune -f` periodically to reclaim disk space from accumulated layer copies.

---

### Q: What are `createdFiles.lst` and `inputFiles.lst` appearing in my workspace?

These are generated by the `maven-compiler-plugin` inside `target/maven-status/` to track file timestamps for incremental compilation (only recompiling Java files that actually changed). They should be in `.gitignore`. If they were accidentally committed, run `git rm -r --cached "*/target/*"` to remove them from the Git index without deleting the files locally.

---

### Q: Why does `amsha-cli.ps1` not prompt to rebuild before running?

The rebuild prompt (`Read-Host "Rebuild/Package application before running? (y/N)"`) is commented out in the shipping version. This is intentional - the wrapper is designed for day-to-day usage where you have already built the JAR. Uncomment the block if you want the interactive rebuild prompt during active development cycles.

---

### Q: What Quarkus profiles does Amsha Agent define and when are they active?

| Profile | When Active | Key Differences |
|---|---|---|
| `%dev` | `quarkus:dev` | Sandbox `strategy=NONE`, verbose LLM logging, hot reload, budget=$999 |
| `%test` | `mvn verify` | In-memory SQLite (`target/`), Telegram disabled, compaction disabled, vault with test pepper |
| `%cli` | `amsha-cli.ps1` wrapper | All banners and INFO logs silenced; OTEL SDK disabled |
| `%staging` | Manual `-Dquarkus.profile=staging` | Sandbox=PODMAN, budget=$50, supervised autonomy |
| `%prod` | Docker Compose (`QUARKUS_PROFILE=prod`) | Sandbox=PODMAN, startup security validation strict, console color off |

---

## 🧪 Testing

### Q: How is the test suite structured across modules?

Tests live in two modules:

- **`core/src/test`** - pure unit and integration tests with zero Quarkus startup. Covers agent loop state machines, reasoning engine, reflex system, sandbox circuit breaker, secret vault, context compaction, FTS5 search, audit HMAC, governance budget, workspace lifecycle, and tool implementations.
- **`app/src/test`** - `@QuarkusTest` integration tests that start the full CDI context. Covers REST API end-to-end (`AgentResourceTest`), Telegram route startup and routing, API key auth, rate limiting, security validators, and scheduler concurrency.

Both test modules use an **in-memory SQLite** database (`target/`) so no file cleanup is required between tests.

---

### Q: Why does the test suite require `--add-opens` JVM flags?

Mockito's inline mock agent (used for mocking final classes and static methods) requires reflective access to JVM internals that Java 17+ restricts by default. The `--add-opens` flags in `app/pom.xml` grant Mockito the access it needs to attach the byte-buddy agent. These flags are scoped to `<argLine>` in the Surefire plugin and do not affect production code.

---

### Q: How do I run only a specific test module without rebuilding everything?

```bash
# Run only amsha-core tests
mvn test -pl amsha-core

# Run only amsha-app integration tests
mvn verify -pl amsha-app -am

# Run a single test class
mvn test -pl amsha-core -Dtest=SandboxCircuitBreakerTest

# Run with LLM debug logging enabled
mvn verify -pl amsha-app -am -Damsha.debug.llm=true
```

---

## 🧠 Tiered Memory & Self-Evolution

### Q: What is Tiered Memory in Amsha and how does it prevent prompt bloat?

Amsha organizes agent memory into three distinct, governed tiers to separate immediate context from long-term reference material (see the [Tiered Memory & RAG Diagram](./assets/amsha_tiered_memory.svg) for a visual layout):
* **Core Memory:** Represents active user context (e.g. active instructions, credentials, keys). Capable of CAS write-replace checks, limited to 20 blocks of 2000 characters each.
* **Recall Memory:** Contains exact conversational history from past sessions. Retained for 30 days and searched via hybrid BM25 + FTS5 exact matching.
* **Archival Memory:** Contains long-term, semantic knowledge, and injected reference files. Retained indefinitely, vector-indexed, and searched using semantic/vector cosine similarity.

This organization prevents prompt bloat by keeping the main chat history compact, while allowing relevant past facts (Recall) and long-term knowledge (Archival) to be injected dynamically only when matching the current goal context.

---

### Q: How does the ArchivalContextCache avoid latency on the live agent path?

Vector database semantic search or disk lookups are high-latency operations. If executed synchronously during prompt building, they would delay the agent's reaction. 
To bypass this, `ArchivalContextCache` pre-fetches and cache-warms RAG results asynchronously when a goal is resolved. Prompt assembly then reads from the cache in O(1) synchronous time, keeping response loops fast and decoupling remote retrieval latency from reasoning loops.

---

### Q: What is the lifecycle of a synthesized behavioral directive in the Self-Evolution system?

Behavioral directives generated by offline trace analysis move through a strict lifecycle to ensure reliability:
1. **`CANDIDATE`**: Synthesized by `InteractionPolicySynthesizer` but pending manual administrator approval.
2. **`SHADOW`**: Approved for testing. Evaluated silently in the background of active agent sessions. The agent's outputs are compared to the directive, but the directive itself is not injected into the prompt.
3. **`ACTIVE`**: Fully promoted and injected into the matching agent prompts to direct future behaviors.
4. **`DISABLED`**: Explicitly disabled due to degradation in performance or manual override.

---

### Q: How are expired memories cleaned up?

A daily cron scheduler (`MemoryRetentionScheduler` executing at 2 AM and 3 AM) manages storage reclamation:
* **Recall Purge**: Deletes recall messages exceeding the retention days parameter (default 30 days) from the SQL database and prunes filesystem JSON session files.
* **Archival Eviction**: Removes low-confidence archival vector chunks (below eviction threshold) if they have not been accessed within the minimum age window, keeping vector tables lean.

---

## ⚡ In-Process Local ML & ONNX

### Q: How does Amsha run local ML models in-process without Python or containers?

Amsha uses **ONNX Runtime Java** (`1.27.0`) and **DJL Hugging Face Tokenizers** (`0.33.0`) in `amsha-local-ml-onnx`. The JVM path executes a verified local `model.onnx` and `tokenizer.json` without Python sidecars, containers, or inference-time model downloads. Native-image runtime execution is still a release gate, not a completed support claim.

---

### Q: How do I configure and activate local ONNX RAG embeddings?

Set the following environment variables in your `.env` or `dist/.env` file:

```env
AMSHA_ONNX_ENABLED=true
AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=onnx
AMSHA_EMBEDDING_ONNX_MODEL=all-mpnet-base-v2-e8c3b32
AMSHA_EMBEDDING_ONNX_TIMEOUT_SECONDS=5
AMSHA_EMBEDDING_ONNX_SPACE_ID=sentence-transformers/all-mpnet-base-v2
AMSHA_EMBEDDING_ONNX_SPACE_REVISION=e8c3b32edf5434bc2275fc9bab85f82640a19130
AMSHA_EMBED_DIMENSIONS=768
```

For complete architectural details, manifest format, and the native verification gate, refer to the [In-Process Local ML Inference Guide](./ONNX_LOCAL_ML_GUIDE.md).

---

### Q: How are `AMSHA_EMBEDDING_ONNX_MODEL`, `SPACE_ID`, and `SPACE_REVISION` determined?

All three values are determined from the verified model bundle manifest at `data/models/{bundleId}/manifest.json`:
* `AMSHA_EMBEDDING_ONNX_MODEL`: The folder name under `data/models/` (matching `"bundleId"` in `manifest.json`).
* `AMSHA_EMBEDDING_ONNX_SPACE_ID`: The `"spaceId"` field in `manifest.json` (e.g. `sentence-transformers/all-mpnet-base-v2`).
* `AMSHA_EMBEDDING_ONNX_SPACE_REVISION`: The `"spaceRevision"` field in `manifest.json` (the Hugging Face Git commit SHA).

---

### Q: Is native ONNX inference verified already?

The macOS arm64 native binary has compiled, started with ONNX Runtime ready, and registered the MPNet slot. After rebuilding with the diagnostic probe, `--onnx` executes a fixed-text embedding and requires the ONNX success metric to increase. The same native inference check is still required on every supported target before ONNX native deployment is supported. The 435 MB model always remains external in `data/models/`.

---

### Q: How do I run the REST validation script for ONNX?

Execute the ONNX REST validation suite against a running daemon:

```bash
bash rest-http/scripts/run_all_validations.sh --onnx
```

This verifies health check `/q/health`, executes the protected fixed-text ONNX probe,
requires the successful-inference metric to increment, verifies model bundle file
integrity, and requires `READY` vector-index compatibility. For one command that also
checks an authenticated OMLX completion and manages an isolated native lifecycle, run:

```bash
mise run smoke:local-runtime -- native --binary dist/amsha-sqlite
```

That runner forces `generic-openai` plus ONNX and never selects Ollama. It is a local
smoke check, not a replacement for the supported-platform records, numeric corpus,
repeated leak test, or signed SBOM/provenance gates described in the
[Local ML guide](./ONNX_LOCAL_ML_GUIDE.md).
