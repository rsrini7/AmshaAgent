Now I have a thorough understanding of the entire codebase. Here's a comprehensive breakdown:

***

## Architecture Overview

This is a **multi-module Quarkus Chat UI** project that streams LLM responses to a browser via SSE, with a POJO actor system at its core. It supports multiple provider backends (Claude, Codex, OpenAI-compat) and integrates MCP (Model Context Protocol) for inter-agent communication. 

### Module Structure

| Module | Role |
|---|---|
| `core` | Actor system, REST endpoints, MCP tools, event DTOs |
| `app` | CDI wiring, provider selection via config |
| `provider-claude` | Claude HTTP API provider |
| `provider-claude-code` | Claude CLI process provider |
| `provider-codex` | OpenAI Codex provider |
| `provider-openai-compat` | Generic OpenAI-compatible provider |

### The Actor System

The heart of the architecture uses a `pojo-actor` library (v3.0.1) - plain Java POJOs acting as thread-safe actors via `ActorRef<T>` wrappers. No CDI annotations inside actors, no `synchronized` blocks - thread safety comes from actor-sequential message delivery. 

Five actors cooperate:

- **`ChatActor`** - owns all chat session state: `busy` flag, conversation history (ring-evicted at 200), SSE emitter, API key, log ring buffer (500 entries). Spawns a **virtual thread** for blocking LLM I/O so the actor stays responsive for `cancel`, `publishLog`, etc. 
- **`QueueActor`** - manages prompts when ChatActor is busy. Supports 3 modes: `queue` (FIFO), `cancelandsend` (interrupt + push to front), `wait` (wait up to N seconds, then cancel). Ticks every 2 seconds AND is triggered immediately by `onPromptComplete`. 
- **`BtwActor`** - handles "by-the-way" side questions **independently**, on its own virtual thread, without touching ChatActor state. Uses `btwdelta`/`btwresult` SSE events. 
- **`WatchdogActor`** - monitors CLI-based LLM streams for stalls. If no activity heartbeat is received for >300s, emits a warning SSE event and can detect recovery. 
- **`McpClientActor`** - handles async outbound MCP calls to other agents. 

### SSE Streaming & Events

`ChatResource` registers a Vert.x SSE route (`/api/chat/stream`) and holds a `volatile HttpServerResponse`. All streaming uses a `ChatEvent` record with typed event names: `delta`, `result`, `error`, `info`, `status`, `thinking`, `prompt`, `heartbeat`, `log`, `mcpuser`, `btwdelta`, `btwresult`. Heartbeats fire every 15 seconds. 

### MCP Integration

`McpTools` exposes 6 MCP tool endpoints (`submitPrompt`, `cancelRequest`, `listModels`, `getStatus`, `getPromptStatus`, `getPromptResult`). Incoming MCP messages are **enriched** with self-awareness (the agent's own URL, the sender's identity, and how to reply via `callMcpServer`). `GatewayRegistrar` registers this agent with a gateway at startup via an HTTP POST. MCP-sourced queue items are tagged with `source=mcp` so they can be selectively cleared on cancel without touching human-queued messages. 

### Provider Abstraction

`LlmProvider` interface + `ProviderCapabilities` flags (`supportsWatchdog`, `supportsInteractivePrompts`, `supportsSlashCommands`, `supportsImages`, `supportsUrlFetch`). A CDI `@Produces` factory (`LlmProviderProducer`) selects the active provider via `chat-ui.provider` config. 

***

## Ideas for Amsha Agent

### 1. Adopt the POJO Actor Pattern for Agent Isolation
Amsha Agent's agents (planner, executor, reviewer, etc.) could each become a POJO actor using the same `pojo-actor` library. This gives you per-agent state isolation with zero synchronization overhead. The sequential mailbox guarantees eliminate the concurrent-modification bugs that are common in long-running agent workflows. Each agent can safely own its own history, busy flag, and task queue as plain fields.

### 2. Port the `QueueActor` 3-Mode Design to Amsha Agent's Scheduler
Your current Quarkus scheduler could grow `cancelandsend`, `queue`, and `wait` modes exactly as implemented here. The `wait` mode with timeout-based cancellation is particularly powerful - when Amsha Agent receives a priority task via Telegram, it can use `wait(30s)` to gracefully preempt the current run instead of hard-cancelling. The `clearMcpMessages()` pattern (clear machine-submitted tasks but preserve human-submitted ones) maps directly to your human-vs-agent task separation in Amsha Agent. 

### 3. BtwActor → Amsha Agent "Probe" Pattern
The `BtwActor` concept - a completely isolated side-channel LLM call that doesn't disturb the main agent's state - is extremely applicable to Amsha Agent. You could implement an `AgentProbeActor` that lets you query an agent's current reasoning or status mid-run via Telegram without interrupting execution. This is essentially "interrupt-safe introspection."

### 4. WatchdogActor for Agent Stall Detection
Amsha Agent's long-running tasks are prime candidates for a stall detector. Wrap each agent execution in a watchdog that fires a Telegram alert if no progress event is emitted within a configurable threshold (e.g., 5 minutes). The recovery detection pattern (`stallRecovered` tracking) is also valuable - notify when an agent resumes after a stall so you know the session wasn't lost. 

### 5. Typed `ChatEvent`-Style Event Bus
Replace Amsha Agent's ad-hoc logging/notification with a typed event record (`type`, `content`, `sessionId`, `durationMs`, `model`, `busy`, etc.). This gives you a consistent schema for SSE streaming, Telegram forwarding, and SQLite audit logging - all from one event shape. The `thinking` event type maps well to Amsha Agent's in-progress reasoning steps. 

### 6. MCP Gateway Registration for Amsha Agent Orchestration
The `GatewayRegistrar` pattern (POST to a gateway with `name`, `url`, `description`, `port` at startup) is a low-ceremony way to make Amsha Agent instances discoverable. If Amsha Agent ever runs multiple sub-agents as separate processes, they can self-register with a coordinator. The `buildEnrichedPrompt()` (inject self-URL, caller identity, and reply instructions into the prompt) is a great copy-paste pattern for Amsha Agent's inter-agent messaging. 

### 7. Log Ring Buffer → Live Log Streaming to Telegram
The `publishLog()` + circular ring buffer (500 entries) + SSE forwarding pattern in `ChatActor` is a clean design to adopt for Amsha Agent. You could stream live logs to your Telegram bot in real time (bounded to avoid flood), with configurable log levels, and replay recent entries on reconnect without re-running anything. 

### 8. `ProviderCapabilities` Feature Flags for Amsha Agent Tools
Model the capabilities of each agent or LLM in Amsha Agent as a `ProviderCapabilities`-style record with boolean flags (`supportsCodeExecution`, `supportsFileWrite`, `supportsWebSearch`, etc.). This lets your orchestrator query what an agent can do at runtime and conditionally wire watchdogs, approval prompts, or tool restrictions - exactly what this project does with `supportsWatchdog` and `supportsInteractivePrompts`. 

### 9. Source-Tagged Task Queue (`source = "human" | "agent"`)
The `QueueItem.source` field (human vs. mcp) is a simple but powerful pattern. In Amsha Agent, tag every queued task with its origin: `human` (Telegram/UI), `scheduled` (cron), or `agent` (automated sub-task). This makes it trivial to implement priority rules - e.g., human tasks always preempt agent-originated ones, and you can drain only agent tasks on cancel. 

### 10. Playwright E2E Tests for Amsha Agent UI
The E2E test suite (queue persistence, theme switching, keyboard shortcuts, SSE connection) uses Playwright + Quarkus failsafe plugin with a real running server. You could adopt the same `E2eTestBase` + `exec-maven-plugin` pattern to start Amsha Agent in a test profile and validate your approval workflows, queue UI, and Telegram webhook stubs end-to-end. 

---

[https://scivicslab.com/blog/2026-04-05-pojo-actor-llm-conversation/](https://scivicslab.com/blog/2026-04-05-pojo-actor-llm-conversation/) further reading

This post is the official design writeup for the codebase you already analyzed - here's a distilled summary of the key insights and their direct implications for Amsha Agent.

## Core Design Principle: State Guardian + I/O Worker Split

The central idea is a clean two-role split: the **actor is the state guardian**, and a **virtual thread is the I/O worker** . This is why `ChatActor.startPrompt()` immediately spawns a `Thread.startVirtualThread(...)` and returns - the actor's message loop stays free to process `cancel`, `publishLog`, queue ticks, or anything else the entire time the LLM is running. The boundary is visually explicit in the code: the moment you see `Thread.startVirtualThread(...)` inside a message handler, you know you've crossed from actor domain into I/O worker domain.

## The `volatile Thread activeThread` Trick

`activeThread` is `volatile` for one specific reason: `cancel()` is called via `tellNow()`, which runs on a **different virtual thread** than the actor's own queue thread . Without `volatile`, the write from `startPrompt()` might not be visible to the `cancel()` read across that thread boundary. This is a small but non-obvious detail that's directly applicable to Amsha Agent - any actor field that is written by the actor thread but read from a `tellNow()` path needs `volatile`.

## `tell()` vs `tellNow()` - Semantics Matter

The blog makes a nuanced point: using `tell(cancel)` would actually work in practice here because `startPrompt()` returns immediately and leaves the queue empty . But `tellNow()` is used anyway because it communicates **intent** - "cancel NOW" rather than "cancel when it's your turn." For Amsha Agent's Telegram-triggered cancellations or priority interruptions, this semantic distinction is worth adopting explicitly.

## BtwActor: The "Don't Touch Shared State" Rule

The most important thread-safety insight in the post is about `BtwActor` sharing a `LlmProvider` instance with `ChatActor` . `BtwActor` deliberately does **not** call `provider.setModel()` before `sendPrompt()`, because `setModel()` mutates shared state that `ChatActor` is also relying on mid-stream. Instead, the model is passed directly as a parameter. For Amsha Agent, this rule generalizes: when two actors share a service or client, any **stateful mutation** (setting model, setting session, setting auth) must be owned by exactly one actor - parallel actors pass parameters instead of mutating shared fields.

## The Actor Structure as a Scalable Pattern

The four-actor hierarchy (`ChatActor → QueueActor`, `BtwActor`, `WatchdogActor`) is explicitly described as a pattern that scales by **adding actors without changing existing ones** . `BtwActor` is 30 lines. `WatchdogActor` plugs in without modifying `ChatActor`. For Amsha Agent, this means each new capability (a `TelegramNotifierActor`, a `GitActor`, an `ApprovalActor`) should be a new POJO wired into `LlmConsoleActorSystem`-equivalent, not a feature bolted onto an existing actor.

## What's Coming Next

The blog mentions a **Part 3** on "Scaling Multi-Agent Communication with MCP Gateway" is coming soon  - that will likely cover the `GatewayRegistrar` + `McpTools` + multi-agent discovery architecture in depth, which is highly relevant to Amsha Agent's orchestration layer. Worth watching for.