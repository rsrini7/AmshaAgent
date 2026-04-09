# ArcClaw V1.0.0 — Frequently Asked Questions (FAQ)

---

## 📑 Table of Contents

1. [Architecture & Design](#-architecture--design)
2. [Podman vs Docker](#-podman-vs-docker)
3. [Language & Framework Choices](#-language--framework-choices)
4. [AI & Agent Logic](#-ai--agent-logic)
5. [The Reflex System](#-the-reflex-system)
6. [Skills System](#-skills-system)
7. [MCP Integration](#-mcp-integration)
8. [Memory, Compaction & Long-Term Context](#-memory-compaction--long-term-context)
9. [RAG & Vector Search](#-rag--vector-search)
10. [Structured Output Engine](#-structured-output-engine)
11. [Cron Scheduler & Autonomous Tasks](#-cron-scheduler--autonomous-tasks)
12. [Security & Secrets](#-security--secrets)
13. [Extending ArcClaw — Tools, Personas & Reflexes](#-extending-arcclaw--tools-personas--reflexes)
14. [Channels & Telegram Integration](#-channels--telegram-integration)
15. [Runtime & Operations](#-runtime--operations)
16. [Testing](#-testing)

---

## 🏛️ Architecture & Design

### Q: Why is ArcClaw structured as a multi-module Maven reactor instead of a single application?

The reactor enforces strict architectural separation that prevents accidental dependency creep. The six modules are:

- **`arcclaw-channel-api`** — pure interfaces (`ChannelSender`, `IntentClassifier`, `ChannelAllowlist`, structured output types) with no implementation. This allows `arcclaw-core` to reference channel concepts without coupling to Telegram or any specific adapter.
- **`arcclaw-core`** — domain logic and CDI interfaces. Has zero awareness of REST, Telegram, or CLI. This is the "kernel" that could theoretically be embedded in any JVM runtime.
- **`arcclaw-mcp`** — MCP server/client logic. Exposes `DynamicToolLookupImpl` behind the `DynamicToolLookup` interface defined in `arcclaw-core`. Keeping it separate means the kernel has no compile-time dependency on MCP transport code.
- **`arcclaw-rag`** — Two-tier RAG memory (sqlite-vec). The `vec0.so` native extension has platform-specific native image metadata. Isolating it prevents this from polluting the core module.
- **`arcclaw-deployment`** — Quarkus build-time processors. Contains the `@BuildStep` logic that wires up the custom `@WorkspaceScoped` CDI scope, MCP build steps (`McpBuildStep`), and RAG resource steps (`RagResourcesBuildStep`). Keeping this separate ensures the scope works correctly in native image compilation.
- **`arcclaw-app`** — the entry point. Owns all application-layer concerns: REST resources, Telegram routes, CLI commands, scheduler, security filters. It depends on all other modules but **no module depends on `arcclaw-app`**.

The rule is: **kernel logic never imports application-layer code**. This makes `arcclaw-core` independently testable with zero Quarkus startup overhead.

---

### Q: Why does ArcClaw use SQLite instead of a scalable database like PostgreSQL?

ArcClaw relies on `StatefulAgentStore` to checkpoint the LLM's `ReasoningStep` history at every iteration. There are three reasons SQLite is the right tool:

1. **Zero-latency:** Writing to a local SQLite file avoids network round-trips during the high-speed Agent Loop. Each checkpoint is a single write; adding a network hop would double iteration latency.
2. **Concurrency:** SQLite's WAL (Write-Ahead Logging) mode allows concurrent readers while a single writer commits. ArcClaw's `AsyncPersistenceEngine` serializes all writes through a `LinkedBlockingQueue` backed by a single virtual thread, eliminating WAL contention entirely.
3. **Portability:** ArcClaw deploys as a single, self-contained binary (118 MB) with no external service dependencies. A PostgreSQL sidecar would eliminate that advantage.

The dual-database design (`arcclaw-meta.db` for structured state, `arcclaw-fts.db` for FTS5 full-text search) keeps the schema clean and prevents FTS index operations from locking the primary checkpoint tables.

---

### Q: Why use Virtual Threads instead of reactive Vert.x coroutines?

Agent loops are fundamentally I/O-bound. They spend ~95% of their time blocked on LLM API responses or waiting for Podman sandbox processes to finish. Both Virtual Threads and Vert.x coroutines solve blocking I/O — the difference is ergonomics and safety.

Vert.x reactive code is non-blocking by design but is **collaborative**: if any frame on the event loop blocks, every other request stalls. That makes it brittle for agentic workloads where any reasoning step might block for 10–30 seconds. Virtual Threads (JEP 444, stable in JDK 21) are **preemptive**: the JVM parks the thread at any blocking call and recycles the underlying carrier thread, with zero risk of event-loop starvation. The code also reads exactly like synchronous Java, which keeps the agent loop logic readable and debuggable.

---

### Q: What is `@WorkspaceScoped` and why is a custom CDI scope necessary?

Standard CDI scopes (`@RequestScoped`, `@SessionScoped`) are tied to HTTP lifecycle events. Agent runs on virtual threads — especially async runs and cron jobs — have no HTTP request context. Without a custom scope, any bean holding per-session state would either be shared globally (`@ApplicationScoped`) or lost across thread boundaries.

`@WorkspaceScoped` is a CDI scope keyed on `sessionId`. The `WorkspaceScopeHolder` uses Java 21 `ScopedValue` (not `ThreadLocal`) to bind a workspace context to the logical execution — it propagates correctly across virtual threads, CDI-managed executors, and Camel routes without leaking state between sessions. The `arcclaw-deployment` module wires this scope up at Quarkus build time via `@BuildStep`.

---

### Q: What is the difference between `ask`, `chat`, `serve`, and `attach` modes?

| Mode | Command | Behaviour |
|---|---|---|
| `ask` | `ask "goal" --role coordinator` | One-shot: run agent, print answer, exit. Ideal for CI/CD scripts. |
| `chat` | `chat` | Interactive REPL with full multi-turn memory, all reflexes, and HITL approval. |
| `serve` | `serve` | Long-running REST daemon on port 8080. No channel adapters; for direct API access. |
| `attach` | `attach telegram` | Long-running daemon with channel adapters active. Cron scheduler runs in this mode. |

The `%cli` profile is forced by `arcclaw-cli.ps1` and silences all Quarkus banners and `INFO` logs so terminal output only shows agent reasoning.

---

### Q: Why does `docker-compose.yaml` need `privileged: true` for the arcclaw container?

ArcClaw runs **Podman inside a Docker container** (nested containerization). For rootless Podman to create user namespaces and cgroup hierarchies inside a Docker container, it needs permission to call `unshare()` and `newuidmap`. Docker restricts these syscalls by default. The `privileged: true` flag grants the container the full Linux capability set so Podman can manage nested namespaces correctly. This is a known requirement for container-in-container architectures and is documented in the Podman community as the standard approach for CI/CD environments.

---

## 🐳 Podman vs Docker

### Q: Why does ArcClaw use Podman instead of Docker for the tool sandbox?

ArcClaw executes AI-generated shell commands inside isolated containers. The threat model is: *an LLM has crafted a tool invocation that may contain adversarial input*. The sandbox's job is to contain the blast radius. Podman is the right tool for three reasons:

**1. No privileged daemon socket.** Docker requires mounting `/var/run/docker.sock` into the ArcClaw container to spawn child containers. Owning that socket grants root-equivalent access to the entire host. Podman is daemonless — containers are direct child processes of the `arcclaw` user process, with no socket exposure.

**2. Rootless by default.** Podman containers run with the calling user's UID (UID 1000 for `arcclaw`). Even if a sandboxed tool escapes the container, it runs as an unprivileged user on the host. Docker's rootless mode requires manual setup and has limitations around cgroup v2 and storage drivers.

**3. SELinux-native.** ArcClaw's `Dockerfile.native` is built on UBI 9 (RHEL lineage), where Podman's SELinux label propagation works seamlessly. Docker on the same base often requires manual `--security-opt` overrides.

### Q: Can I swap Podman for Docker in development?

Yes. Set `arcclaw.sandbox.strategy=NONE` in your local `application.yaml` (the `%dev` default) to bypass container isolation entirely and run tools directly on the host PATH. For production-like testing on a machine without Podman, the three-tier degradation in `ProcessIsolationExecutor` will fall through to Linux `unshare` namespaces as a middle-ground option.

### Q: When does ArcClaw's sandbox circuit breaker trip?

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

ArcClaw's design requirements — a custom CDI scope, native image compilation, virtual threads, SQLite WAL concurrency, and Podman process spawning — all land in a sweet spot where Java 25 + Quarkus excels. The specific choices:

**Java over Python:** Python's async model (asyncio/gevent) is cooperative and brittle under sustained I/O load from LLM calls. Java 25 Virtual Threads are preemptive, JVM-managed, and trivially composable with existing blocking libraries (JDBC, ProcessBuilder). The JVM's JIT also gives significantly lower latency on repeated agent-loop iterations after warm-up. Native image compilation via GraalVM further brings startup time to under 100 ms — impossible with a Python interpreter.

**Quarkus over Spring Boot:** Quarkus was designed for GraalVM native image from day one. Spring Native exists but is a retrofit. Quarkus's build-time CDI model (all bean wiring resolved at compile time) is what makes `@WorkspaceScoped` implementable without reflection hacks. Quarkus also ships Micrometer, OTLP, Health, and Scheduler as first-class extensions with native-image metadata pre-configured.

**Java over Go:** Go lacks generics-mature library support for LLM orchestration, CDI-style IoC, and JPA-level SQLite ORM. The LangChain4J ecosystem is also far richer than any Go equivalent, and Go's goroutine model — while excellent — doesn't offer the CDI context propagation mechanism needed for `@WorkspaceScoped` beans.

---

### Q: Why LangChain4J instead of Spring AI or a raw HTTP client to the LLM?

LangChain4J provides three things that would require significant custom code otherwise:

1. **Typed tool calling.** LangChain4J's `JsonObjectSchema` builder generates the tool specification JSON the LLM expects and parses the tool invocation JSON the LLM returns. Writing this correctly (including nullable/required field semantics) by hand is error-prone.
2. **`ChatMemoryStore` abstraction.** ArcClaw plugs `PersistentChatMemoryStore` (backed by SQLite) directly into LangChain4J's memory contract. This gives windowed rolling history with zero custom serialization code.
3. **Provider portability.** Swapping from Groq → Ollama → OpenAI is a single `application.yaml` change. The reasoning engine code does not change.

Spring AI is a strong alternative but is more opinionated toward the Spring ecosystem. Since ArcClaw is Quarkus-native, LangChain4J's CDI-friendly design avoids unnecessary Spring context overhead.

---

### Q: Why Groq as the default LLM provider instead of OpenAI or a local model?

Groq's LPU (Language Processing Unit) inference delivers the lowest token/second latency of any hosted API, which directly reduces agent loop iteration time. For a ReAct-style agent that may call the LLM 5–7 times per task, a 3× latency advantage over OpenAI's standard API compounds significantly. ArcClaw V1.0.0 defaults to `meta-llama/llama-4-scout-17b-16e-instruct` for reasoning and `llama-3.1-8b-instant` for intent classification and compaction — both available on Groq.

Ollama is still provisioned in `docker-compose.yaml` (disabled by default) for fully air-gapped deployments. Switching is a two-line change in `application.yaml`.

---

### Q: Why Apache Camel for the Telegram adapter instead of the Bot4J or TelegramBots library?

Apache Camel's `camel-telegram` component handles the long-polling lifecycle, graceful shutdown, and retry logic for the Telegram Bot API automatically. More importantly, Camel's `ManagedExecutor` handoff lets ArcClaw bridge the Vert.x event loop thread (where Camel routes run) to a Quarkus-managed virtual thread (where the CDI context and `@WorkspaceScoped` beans live) in a single line. Writing the same handoff with a raw Telegram library would require explicit thread pool management and careful CDI context propagation — all code that Camel handles transparently.

---

## 🤖 AI & Agent Logic

### Q: What is the difference between all six agent roles?

| Role | Deity | Key Constraint | Use For |
|---|---|---|---|
| `coordinator` | Vishnu | Can use all tools | General tasks, delegation, scheduling |
| `researcher` | Saraswati | Read-only tools only; never fabricates | External data retrieval, GitHub lookups |
| `coder` | Brahma | Empty tool schema — no tools | Pure code generation, math, logic |
| `debugger` | Shiva | GitHub only; contrarian by design | Bug analysis, stack traces, security audits |
| `architect` | Brihaspati | GitHub + memory search; never writes code | ADRs, design reviews, trade-off analysis |
| `synthesizer` | Dattatreya | All tools; only for cross-domain tasks | Multi-agent output integration |

The `IntentClassifierService` uses `llama-3.1-8b-instant` to auto-route incoming messages to the best role. You can also specify a role explicitly via `--role` on the CLI or `agentRole` in the REST payload.

---

### Q: Why do `coder` and `debugger` have empty (or near-empty) tool schemas?

This is a deliberate architectural constraint called **schema filtering**. When the LLM receives a tool schema, it is tempted to use those tools even when the task doesn't require them (tool bias). By passing an empty tool schema to `coder` and a read-only schema to `debugger`, it is *structurally impossible* for those personas to trigger a network call or file mutation — regardless of what the user prompt says. The constraint is enforced in `AgentRoleEnum.definition().toolNames()` and applied in `LangChainReasoningEngine` before the prompt is sent.

---

### Q: My Agent loop randomly failed with: *"System Error: You provided invalid JSON formatting"*. What happened?

This is a **JSON Hallucination** — the LLM generated malformed JSON for a tool invocation (e.g., a trailing comma or unescaped quote). Instead of crashing, `LangChainReasoningEngine` intercepts the `JsonParseException` and returns the error message back to the LLM as a "System Error" observation. This allows the LLM to self-correct on the next iteration. It is most common with smaller local models (`llama3.2:1b`). Groq-hosted Llama 4 Scout is significantly more reliable on structured output.

---

### Q: What prevents the agent from looping forever?

Three layers of protection:

1. **`maxIterations`** cap: configurable per-request (default 5 for REST, 2 for cron jobs). If reached, the loop terminates with `FAILED`.
2. **`AgentSentinels`**: pattern-matching guards inside `AgentStateMachineRunner` that detect stuck states (e.g., repeated identical tool calls, circular reasoning).
3. **Budget cap**: `GovernanceLayer` tracks cumulative USD cost per session. At 80% of `arcclaw.governance.budget-usd`, it raises a soft `BudgetWarningException`. At 100%, it throws `BudgetExceededException` and transitions the session to `READONLY`, blocking further tool calls.

---

### Q: What is the `AWAITING_APPROVAL` checkpoint state and how does it work?

When the `ToolRegistry` encounters a tool call that requires `SUPERVISED` autonomy and the current autonomy level is `SUPERVISED`, it throws `RequiresApprovalException` instead of executing the tool. The `AgentLoop` catches this, saves the complete reasoning state to SQLite with status `AWAITING_APPROVAL`, and returns a message asking for `/approve` or `/deny`.

When the user sends `/approve`, the `ApproveReflex` loads the checkpoint from SQLite, elevates autonomy to `FULL` for that single invocation, and resumes the loop from the exact iteration where it paused. The approval has a configurable timeout (`arcclaw.governance.approval-timeout-minutes`, default 60 minutes); expired approvals are reaped by `SessionReaper`.

---

### Q: What is "Task Amnesia" and why is it desirable?

After a checkpoint reaches `COMPLETED` or `FAILED`, ArcClaw performs a **Soft Reset**: the internal ReAct loop state (tool call history, reasoning step sequence) is wiped, but the LangChain4J `ChatMemoryStore` (the conversational history visible to the LLM) is preserved.

Without this reset, a "ghost" tool trigger from a previous task can contaminate the next request — the LLM sees a prior `ToolExecutionResultMessage` in context and incorrectly assumes it is still mid-task. The soft reset eliminates this poisoning while keeping the conversation flowing naturally.

---

## ⚡ The Reflex System

### Q: What is the Reflex system and why doesn't it use the LLM?

A `AgentReflex` is a deterministic fast-path that intercepts specific input patterns before they reach the reasoning engine. Reflexes are evaluated by `ReflexRegistry` on every message. If one matches, its response is returned immediately with **zero LLM tokens consumed**.

This matters for three reasons:

1. **Cost:** Commands like `/approve`, `/status`, and `/history` are intent-unambiguous. Routing them through an LLM classification step wastes tokens and adds latency.
2. **Safety:** The `PromptInjectionReflex` must fire *before* the LLM sees the input — if an adversarial prompt could reach the reasoning engine, it might influence the model to bypass governance.
3. **Reliability:** Reflexes are pure Java — they cannot hallucinate, and their behaviour is deterministic and fully unit-testable.

---

### Q: How does `PiiScanReflex` work and can I change its behaviour?

`PiiScanReflex` runs on every message (via the `sanitize()` hook in `ReflexRegistry`) using a set of compiled `Pattern` objects that detect emails, phone numbers, Aadhaar, PAN, credit card numbers, US SSNs, IP addresses, and JWT tokens. It has two modes, controlled by `arcclaw.pii.mode`:

- **`REDACT_AND_FORWARD`** (default): Replaces detected PII with placeholder tokens (e.g., `[EMAIL]`, `[PHONE]`) and passes the sanitized message to the LLM. The user sees no interruption.
- **`BLOCK_AND_NOTIFY`**: Rejects the message outright and returns an error. Use this in regulated environments where PII must never touch the LLM API.

Note: `store <key>=<value>` messages bypass PII scanning entirely in `ReflexRegistry` — this prevents secret values (like a GitHub PAT that might match the JWT pattern) from being incorrectly redacted.

---

### Q: How does `PromptInjectionReflex` protect against jailbreaks?

It maintains four categories of compiled patterns:

1. **Fake approval bypass** — patterns like "human approved", "auto-approve", "this has been pre-approved", "skip governance".
2. **System prompt extraction** — patterns like "reveal your instructions", "repeat everything", "show your system prompt".
3. **Role override attempts** — patterns that try to redefine the agent's persona mid-conversation.
4. **Security bypass vocabulary** — patterns from `DestructiveVocabularyFilter` (shell destructive commands).

If any pattern matches, the message is blocked before it reaches the intent classifier or agent loop. The block is logged as a `SecurityEventType.PROMPT_INJECTION_ATTEMPT` audit event.

The `ToolObservationSanitizer` also runs both `PromptInjectionReflex.sanitize()` and `PiiScanReflex.redactPii()` on **tool output** before appending it to agent history — so adversarial content in a tool's stdout cannot inject instructions into the reasoning context.

---

### Q: What does the `/mcp` reflex do?

`McpReflex` handles the `/mcp` command and returns the current MCP status with zero LLM tokens:

```
🔌 MCP Status
Exposed internal tools (2): github, memory_search
Dynamic MCP tools: none connected
```

After connecting an external MCP server via `POST /api/v1/mcp/connect`, the response changes to show the registered dynamic tools with their server name and transport type.

---

## 🎯 Skills System

### Q: What is the Skills system and how does it differ from the Reflex system?

Both skip the LLM in different ways:

- **Reflexes** handle *operational commands* (`/approve`, `/halt`, `/status`, etc.) and *security filters* (PII, injection). They return a direct response and end the pipeline immediately.
- **Skills** handle *content routing* — they match a user's natural-language query against a trigger pattern and inject a custom system prompt, then run the full agent loop with that persona. The agent still reasons and may still call tools; only the intent classifier step is skipped.

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
  -H "X-ArcClaw-API-Key: $API_KEY" \
  -H "Content-Type: text/plain" \
  --data-binary @my-skill.md
```

To deploy as built-in (requires rebuild): place the `.md` file in `src/main/resources/skills/` and add the filename to `index.txt`.

---

### Q: What happens if two skills have overlapping trigger patterns?

`SkillRegistry` evaluates skills in load order (built-in classpath skills first, then database-tier skills). The first match wins. To avoid conflicts, make trigger patterns as specific as possible. If you need to replace a built-in skill, upsert a database-tier skill with the same `name` — it will shadow the built-in entry.

---

### Q: How do I update an existing skill without creating a duplicate?

`POST /api/v1/skills` is an **upsert** — if a skill with the same `name` already exists in the database tier, it is replaced in-place and the registry is reloaded. You will see `{"message":"Skill saved and registry reloaded: <name>"}` in the response for both new and updated skills.

---

### Q: How do I force a skill registry refresh after a manual database change?

```bash
curl -s http://localhost:8080/api/v1/skills/refresh \
  -H "X-ArcClaw-API-Key: $API_KEY"
```

This reloads both classpath built-in skills and database-tier skills without restarting the application.

---

## 🌐 MCP Integration

### Q: What is the Model Context Protocol (MCP) and how does ArcClaw implement it?

MCP is an open protocol for LLM tool interoperability over JSON-RPC. ArcClaw implements it in both directions:

- **Inbound MCP server**: External MCP clients (e.g. Claude Desktop, other agents) can connect to ArcClaw's `/mcp` endpoint and call its `github` and `memory_search` tools via JSON-RPC over HTTP-SSE.
- **Outbound MCP client**: ArcClaw can connect to any external MCP server (`POST /api/v1/mcp/connect`). The connected server's tools are registered as `ExternalMcpToolAdapter` instances, becoming natively available to agent roles in the tool schema.

---

### Q: Which internal tools does ArcClaw expose as an MCP server?

By default, `github` and `memory_search` are exposed. The `McpToolAllowlist` configuration controls this allowlist. Disallowed tool names return a JSON-RPC error with a descriptive message rather than executing.

---

### Q: How does the MCP session lifecycle work?

```
1. POST /mcp  {"method":"initialize"}      → 200, Mcp-Session-Id in response header
2. POST /mcp  {"method":"initialized"}     → 202, handshake complete
3. POST /mcp  {"method":"tools/list"}      → tools array (github, memory_search)
4. POST /mcp  {"method":"tools/call",...}  → content block + MCP_TOOL_INVOKED audit event
```

Always run `GET /api/v1/mcp/status` before starting the protocol sequence to confirm `serverEnabled:true`. If the server is disabled, step 1 returns 404 and you will have no session ID.

---

### Q: How do dynamic MCP tools appear to agents?

After connecting an external MCP server, `DynamicToolCapabilityRegistry` registers each of its tools as a `DynamicToolCapability`. `DynamicToolLookupImpl` (in `arcclaw-mcp`) provides the `DynamicToolLookup` implementation used by `ToolRegistry`. The tool then appears in the LLM's tool schema for roles that permit dynamic tools — the LLM invokes them exactly like built-in tools, and `ExternalMcpToolAdapter` dispatches the call to the remote server via `McpClientService`.

---

### Q: What is the self-connect smoke test?

Connect ArcClaw to its own `/mcp` endpoint:

```bash
curl -X POST http://localhost:8080/api/v1/mcp/connect \
  -H "X-ArcClaw-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"url":"http://localhost:8080/mcp","name":"self"}'
```

After this, `GET /api/v1/mcp/tools` should show `registeredDynamicTools` with 2 entries, both with `mcpServerName:"self"` and `transport:"HTTP_SSE"`. This verifies the full outbound MCP stack without needing an external server.

---

## 🧠 Memory, Compaction & Long-Term Context

### Q: What happens when a conversation gets very long?

ArcClaw uses a rolling window `ChatMemoryStore` capped at `arcclaw.chat-memory.max-messages` (default 20). When the window fills, the `CompactionHandler` in the message pipeline triggers `ContextCompactionService` if the message count exceeds `arcclaw.memory.compaction.threshold` (default 5 above the keep-recent count).

Compaction uses `llama-3.1-8b-instant` to summarize the oldest messages into a `CompactionSummaryEntity`, discards the originals from the rolling window, and preserves the most recent `keep-recent` (default 5) messages verbatim. The summary is also written into the FTS5 database (`arcclaw-fts.db`) so it can be recalled later via `MemorySearchTool`.

---

### Q: What is the `MemorySearchTool` and how is long-term memory retrieved?

`MemorySearchTool` queries the two-tier memory service (`TwoTierMemoryService`) scoped to the current user's `userId` (from `WorkspaceScopeHolder.USER_ID`) — the LLM can control the search query but **cannot access another user's memories**. Tenant isolation is enforced in Java, not in the SQL query.

The search merges FTS5 keyword results and sqlite-vec vector results using Reciprocal Rank Fusion (RRF, k=60). If the vector extension is unavailable, it degrades silently to FTS5-only results.

Agents with `memory_search` in their tool list (coordinator, researcher, architect, synthesizer) can use this to recall decisions made in previous sessions.

---

### Q: What is the `backfill_fts5.sql` script for?

It is a **one-time migration script** to be run after deploying the Phase 45 FTS5 feature on a database that already has `compaction_summary` rows. It backfills the `user_id` column (using `audit_event` as the source of truth) and populates the `compaction_summary_fts` FTS index for all existing rows. New rows written after the deployment are indexed automatically.

---

## 🔍 RAG & Vector Search

### Q: What is the two-tier RAG architecture?

ArcClaw's `TwoTierMemoryService` merges results from two sources using **Reciprocal Rank Fusion (RRF)**:

- **Tier 1 — FTS5 (keyword)**: `CompactionSummaryRepository` queries `arcclaw-fts.db` using SQLite's FTS5 full-text search. Results are ranked by keyword relevance.
- **Tier 2 — Vector (semantic)**: `GlobalKnowledgeBase` (application-scoped, always active) and `SessionKnowledgeBase` (`@WorkspaceScoped`, active only when a workspace is live) store embedded chunks. Queries use cosine similarity via sqlite-vec.

**RRF merge formula**: `score(d) = Σ 1.0 / (60 + rank)` across both ranked lists. This outperforms either method alone on mixed-domain queries.

---

### Q: What is sqlite-vec and how does ArcClaw use it?

`sqlite-vec` (the `vec0.so` extension) adds vector similarity search to SQLite as a virtual table extension. ArcClaw uses it for the second tier of RAG. The extension is loaded at runtime by `FtsConnectionInterceptor` using `arcclaw.rag.vec-extension-path` (dev: `../lib/vec0`, native Docker: `/app/lib/vec0`).

**Native image requirement**: `vec0.so` links against `libm.so.6` for math functions (`sqrtf`). GraalVM native image does not statically link `libm`, so `LD_PRELOAD=/lib64/libm.so.6` must be set before the native binary starts. Without it, `dlopen(vec0.so)` fails with `undefined symbol: sqrtf` and vector search degrades silently to FTS5-only.

---

### Q: What happens if sqlite-vec is unavailable?

`FtsConnectionInterceptor.isVectorSearchEnabled()` checks at startup whether the extension loaded successfully. If not, `TwoTierMemoryService` takes the **fast path**: it skips the RRF merge entirely and returns FTS5 results directly. No error is thrown, no agent functionality is lost — only the semantic vector search dimension is unavailable. Check boot logs for: `vector search disabled — vec0 extension not loaded`.

---

### Q: What are `GlobalKnowledgeBase` and `SessionKnowledgeBase`?

- **`GlobalKnowledgeBase`** (`@ApplicationScoped`): An in-memory embedding store loaded at startup. It holds globally-ingested documents (e.g., from `IngestDocumentTool`) that persist for the application lifetime. All sessions share it.
- **`SessionKnowledgeBase`** (`@WorkspaceScoped`): An in-memory embedding store scoped to a single session. Documents ingested during a session are available for the lifetime of that session only. It is wiped by `@PreDestroy` when the workspace is torn down.

---

### Q: How do I ingest documents for RAG retrieval?

Use `IngestDocumentTool` (tool name: `ingest_document`). It accepts document content and a scope (`global` or `session`) and chunks + embeds the document into the appropriate knowledge base. For persistent global knowledge, use `global` scope — it survives application restarts if the embedding store is backed by a persistent store. For session-scoped ephemeral context, use `session` scope.

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

`@AgentFunction` marks a SAM interface as a BAML-style typed agent function. The build-time `AgentFunctionProcessor` (in `arcclaw-deployment`) generates a JSON schema from the interface's return type at compile time. This schema is embedded in the system prompt to constrain the LLM's output format, making extraction reliable without prompt engineering per-call.

Built-in examples: `SynthesisFunction` returns `SynthesisResult` (synthesizer's final answer), which may reference `AgentContribution` (per-agent findings) and `ConflictSummary` (structured conflict description between agents).

---

## ⏰ Cron Scheduler & Autonomous Tasks

### Q: How does the autonomous cron scheduler work end-to-end?

1. A user asks an agent to schedule a future task. The `coordinator` or `synthesizer` persona invokes `ScheduleTaskTool` with a 5-field UNIX cron expression, a self-contained goal, an optional timezone, and a `runOnce` flag.
2. The tool validates the cron expression (via `cron-utils`), computes `nextFireTime`, and writes an `AgentCronEntity` with status `PENDING` to the `agent_cron` SQLite table.
3. `AgentCronScheduler` polls every 30 seconds (`arcclaw.cron.poll-interval`). It picks up `PENDING` jobs whose `nextFireTime ≤ now()` and hands them to `CronJobRunner`.
4. `CronJobRunner` executes an agent loop (capped at `arcclaw.cron.max-iterations`, default 2) for the cron goal. On completion, it fires a CDI `AgentJobCompleted` event.
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
- **Agent tool:** Ask the coordinator agent "list my scheduled tasks" — it will invoke `ManageScheduleTool` with `action=list`. Ask it to "delete cron job abc-123" to invoke `action=delete`.
- **Direct SQL (admin):** `UPDATE agent_cron SET status = 'CANCELLED' WHERE id = 'abc-123';` on `arcclaw-meta.db`.

---

## 🔒 Security & Secrets

### Q: How does ArcClaw store and protect secrets (e.g., `GITHUB_TOKEN`)?

Secrets are stored in the `encrypted_secret` SQLite table encrypted with AES-256-GCM-SIV. The key derivation chain is:

```
server pepper (env var) + user password
        ↓ Argon2id (64 MB, 3 iterations, 1 parallelism)
        ↓ 256-bit vault key
        ↓ AES-GCM-SIV encrypt (deterministic nonce from key+salt)
        → stored ciphertext + salt + version tag
```

When a sandboxed tool needs a secret, `SandboxSecretResolver` decrypts it from the vault, passes the raw bytes (never a String) to `TmpfsSecretTransport`, which writes them to a `mode=600` tmpfs-backed env-file under `/run/arcclaw/secrets/`. Podman receives `--env-file <path>`. After the container exits, `TmpfsSecretTransport.close()` overwrites the byte array with zeros and unlinks the tmpfs file.

Secrets **never** appear in process arguments, JVM heap Strings, or on-disk files.

---

### Q: Why use AES-GCM-SIV instead of standard AES-GCM?

Standard AES-GCM is catastrophically broken if a nonce is ever reused with the same key (nonce-misuse attack). In a multi-threaded server under key rotation, guaranteeing nonce uniqueness requires a synchronized counter or random 96-bit nonces with collision risk at high call volumes. AES-GCM-SIV (RFC 8452) is nonce-misuse-resistant: a nonce collision degrades to revealing that two plaintexts are identical, rather than leaking the key. This makes it safe for ArcClaw's deterministic nonce derivation strategy (`key + salt → HKDF → nonce`) without a global counter.

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

`ApiKeyAuthFilter` extracts the `Authorization: Bearer <key>` header and validates it against `ApiKeyRegistry` using constant-time comparison (preventing timing attacks). Each valid key is tracked in a per-key sliding window. If a key exceeds `arcclaw.api.rate-limit.requests-per-minute` (default 20), the filter returns `HTTP 429` with a `Retry-After` header. The implementation uses `AtomicLong` counters and does not require a Redis sidecar.

---

## 🔧 Extending ArcClaw — Tools, Personas & Reflexes

### Q: What is the minimal contract for adding a new tool?

Implement `AgentTool`, annotate the class `@ApplicationScoped`, and ArcClaw discovers it automatically via CDI `Instance<AgentTool>`. The required methods:

```java
@ApplicationScoped
public class MyTool implements AgentTool {
    @Override public String name() { return "mytool"; }
    @Override public String description() { return "LLM-visible description..."; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.READ_FS; }
    @Override public boolean requiresSandbox() { return false; }
    @Override public JsonObjectSchema parametersSchema() { /* define params */ }
    @Override public ToolResponse execute(Map<String, Object> params) { /* logic */ }
}
```

If your tool needs secrets, annotate `sandboxCommand()` (not `execute()`) with `@RequiresSecret`. The registry reads these annotations reflectively.

If your tool needs sandbox isolation, set `requiresSandbox()` to `true`, implement `sandboxCommand()` to return the OS command tokens, and the `ToolRegistry` will route execution through `ProcessIsolationExecutor` automatically.

---

### Q: How do I make a new tool available only to specific agent roles?

Add your tool's `ToolCapability` entry to the `Set<ToolCapability>` inside the relevant `AgentRoleEnum` entries. Tool schema filtering is applied in `LangChainReasoningEngine.getPermittedToolNames()` — it intersects the agent's declared `allowedTools()` with the full registered tool list. Any tool not in the intersection is **invisible to the LLM** for that role, regardless of what the user prompt says.

---

### Q: How do I define a new agent persona?

1. Create a class implementing `Agent` annotated with `@ApplicationScoped` and `@AgentRole("your_role")`.
2. Add the role to `AgentRoleEnum` with a `RoleDefinition` specifying `maxIterations`, `canUseTools`, and `Set<ToolCapability>`.
3. Optionally register it in `application.yaml` under `arcclaw.agents.roles` for the REST API description and intent classifier hints.
4. Add a system prompt template under `src/main/resources/templates/prompts/role-<n>.txt`.

The new persona is immediately available via `--role your_role` on the CLI and `agentRole: "your_role"` in the REST API.

---

### Q: How do I add a new Reflex?

Implement `AgentReflex`, annotate `@ApplicationScoped`, return `true` from `matches(String input)` for your trigger, and implement `execute()`. The `ReflexRegistry` discovers it via CDI automatically. Order of reflex evaluation is unspecified — make sure your `matches()` pattern is specific enough not to shadow other reflexes.

If your reflex needs to sanitize input before the LLM sees it (like `PiiScanReflex`), override the default `sanitize()` method.

---

### Q: Can I add a new channel adapter (e.g., Discord, Slack)?

Yes. The `arcclaw-channel-api` module defines the `ChannelSender` and `ChannelAllowlist` interfaces. To add a channel:

1. Implement `ChannelSender` and register it in `ChannelSenderRegistry` with a matching `ChannelType`.
2. Create a Camel route (or equivalent) that reads messages from the channel and feeds them into `MessagePipeline.process(MessageContext)`.
3. Register the channel's allowlist by implementing `ChannelAllowlist` and contributing it to the pipeline's `AllowlistHandler`.

The entire reflex system, skill routing, intent classification, session routing, and agent execution are channel-agnostic — your new adapter gets all of these for free.

---

## 📱 Channels & Telegram Integration

### Q: How does session continuity work across Telegram bot restarts?

Sessions are identified using `UUID.nameUUIDFromBytes(chatId + ":" + userId)`. This is a **deterministic** UUID derived from the Telegram chat and user identity — it produces the same session ID every time for the same user, whether the bot has been restarted, redeployed, or migrated. As long as `arcclaw-meta.db` is mounted from a persistent volume, the user's conversation history and pending checkpoints survive any JVM restart.

---

### Q: What does `TelegramMarkdownV2Renderer` do?

Telegram's MarkdownV2 format requires escaping every special character (`_`, `*`, `[`, `]`, `(`, `)`, `~`, `` ` ``, `>`, `#`, `+`, `-`, `=`, `|`, `{`, `}`, `.`, `!`) that appears outside of formatting syntax. Raw LLM output routinely contains these characters. `TelegramMarkdownV2Renderer` traverses LangChain4J's `ReplyNode` tree and correctly escapes all plain-text content while preserving bold, italic, code, and link formatting. Without this renderer, Telegram rejects messages with a `Bad Request: can't parse entities` error.

---

### Q: What happens when a Telegram user I have not allowlisted sends a message?

`TelegramAllowlistGuard` is evaluated in the `AllowlistHandler` at the start of the `MessagePipeline`. If the user's Telegram user ID or username is not in `ARCCLAW_TELEGRAM_ALLOWED_USER_IDS` / `ARCCLAW_TELEGRAM_ALLOWED_USERS`, the message is rejected before it reaches the intent classifier or agent loop.

If `arcclaw.channel.telegram.silent-block=false` (default), the user receives the configured `block-response` message (default: `⛔ Access denied.`). If `silent-block=true`, the message is dropped silently with no reply — useful when you do not want to reveal that the bot is running.

An empty allowlist (`ARCCLAW_TELEGRAM_ALLOWED_USERS=`) allows all users — this is the open-access mode used in development.

---

## ⚙️ Runtime & Operations

### Q: What happens if a Sandbox tool returns a massive payload (e.g., a 10,000-line file)?

ArcClaw applies **defensive truncation** via `ToolObservationSanitizer`. Tool observations exceeding 16 KiB (16,384 bytes) are truncated before being appended to the LLM's reasoning history. Feeding a 10k-line file directly into the context would exceed the model's token limit and cause an unrecoverable API error. The truncation is logged at `WARN` level so you can identify over-verbose tools.

In addition, `ToolObservationSanitizer` also runs prompt injection redaction and PII scrubbing on tool output before it reaches the LLM.

---

### Q: I am getting `Permission denied` when SQLite tries to write to the `arcclaw_data` Docker volume. Why?

Docker creates named volumes as `root:root`. ArcClaw runs as `arcclaw` (UID 1000) inside the container. The fix is the **Init-Container pattern**: the `init-perms` service in `docker-compose.yaml` briefly boots as root, runs `chown -R 1000:1000 /app/data`, and exits before the main `arcclaw` container starts (`depends_on: init-perms: condition: service_completed_successfully`).

---

### Q: Why does the `containers.conf` force the `vfs` storage driver for Podman?

Inside a Docker container, standard storage drivers (`overlayfs`, `btrfs`) fail because the host kernel restricts unprivileged mounts inside containers. The `vfs` (Virtual File System) driver is a universal fallback: it creates deep copies of container layers instead of copy-on-write snapshots. It is slower and more disk-intensive than `overlayfs`, but it works in any environment including Docker-in-Docker and Kubernetes pods without `privileged` storage. Run `podman system prune -f` periodically to reclaim disk space from accumulated layer copies.

---

### Q: What are `createdFiles.lst` and `inputFiles.lst` appearing in my workspace?

These are generated by the `maven-compiler-plugin` inside `target/maven-status/` to track file timestamps for incremental compilation (only recompiling Java files that actually changed). They should be in `.gitignore`. If they were accidentally committed, run `git rm -r --cached "*/target/*"` to remove them from the Git index without deleting the files locally.

---

### Q: Why does `arcclaw-cli.ps1` not prompt to rebuild before running?

The rebuild prompt (`Read-Host "Rebuild/Package application before running? (y/N)"`) is commented out in the shipping version. This is intentional — the wrapper is designed for day-to-day usage where you have already built the JAR. Uncomment the block if you want the interactive rebuild prompt during active development cycles.

---

### Q: What Quarkus profiles does ArcClaw define and when are they active?

| Profile | When Active | Key Differences |
|---|---|---|
| `%dev` | `quarkus:dev` | Sandbox `strategy=NONE`, verbose LLM logging, hot reload, budget=$999 |
| `%test` | `mvn verify` | In-memory SQLite (`target/`), Telegram disabled, compaction disabled, vault with test pepper |
| `%cli` | `arcclaw-cli.ps1` wrapper | All banners and INFO logs silenced; OTEL SDK disabled |
| `%staging` | Manual `-Dquarkus.profile=staging` | Sandbox=PODMAN, budget=$50, supervised autonomy |
| `%prod` | Docker Compose (`QUARKUS_PROFILE=prod`) | Sandbox=PODMAN, startup security validation strict, console color off |

---

## 🧪 Testing

### Q: How are the 150+ tests structured across modules?

Tests live in two modules:

- **`arcclaw-core/src/test`** — pure unit and integration tests with zero Quarkus startup. Covers agent loop state machines, reasoning engine, reflex system, sandbox circuit breaker, secret vault, context compaction, FTS5 search, audit HMAC, governance budget, workspace lifecycle, and tool implementations.
- **`arcclaw-app/src/test`** — `@QuarkusTest` integration tests that start the full CDI context. Covers REST API end-to-end (`AgentResourceTest`), Telegram route startup and routing, API key auth, rate limiting, security validators, and scheduler concurrency.

Both test modules use an **in-memory SQLite** database (`target/`) so no file cleanup is required between tests.

---

### Q: Why does the test suite require `--add-opens` JVM flags?

Mockito's inline mock agent (used for mocking final classes and static methods) requires reflective access to JVM internals that Java 17+ restricts by default. The `--add-opens` flags in `arcclaw-app/pom.xml` grant Mockito the access it needs to attach the byte-buddy agent. These flags are scoped to `<argLine>` in the Surefire plugin and do not affect production code.

---

### Q: How do I run only a specific test module without rebuilding everything?

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
