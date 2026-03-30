# JavaClaw → ArcClaw: Deep Gap Analysis & Integration Roadmap

> **Goal:** Identify features present in JavaClaw that ArcClaw is missing, ranked by impact and ease of integration — without reinventing from scratch.

---

## Executive Summary

| Dimension | JavaClaw | ArcClaw |
|---|---|---|
| **Stack** | Spring Boot 4 + Spring AI + JobRunr | Quarkus 3 + LangChain4j + CDI |
| **Build** | Gradle, multi-module | Maven, multi-module |
| **Agent model** | Single agent, one role | Multi-role agent system (6 personas) |
| **Persistence** | H2 (in-process) + Markdown files | SQLite (WAL) with FTS5 + vec0 |
| **Security** | None | API key auth, secret vault, PII redaction, audit |
| **RAG** | None | Two-tier vector store (global + session) |
| **HITL** | None | Per-tool approval with checkpoint/resume |
| **Scheduling** | JobRunr (full-featured dashboard) | Quartz/custom cron scheduler |
| **Chat UI** | Browser WebSocket UI (HTMX) | CLI only, no browser UI |
| **Onboarding** | 6-step guided web wizard | Config file + .env only |
| **Checklist tracking** | CheckListTool (real-time) | None |
| **Browser tool** | Playwright plugin | None |
| **Web search** | Brave plugin | None |
| **MCP self-registration** | Agent can add MCP servers at runtime | REST endpoint only, not agent-driven |
| **Anthropic Claude Code OAuth** | Supported | Standard API key only |

---

## Part 1 — Feature Deep Dive

### 1. Guided Onboarding Web UI ❌ MISSING IN ARCCLAW

**What JavaClaw has:**
A 6-step HTMX-powered onboarding wizard accessible at `/onboarding`:

| Step | Screen | What it does |
|---|---|---|
| S1 | Welcome | Intro + system check |
| S2 | Provider | Choose Ollama / OpenAI / Anthropic |
| S3 | Credentials | Enter API key + model name, live validation |
| S4 | Agent.md | Edit the agent's system prompt in-browser |
| S5 | MCP | Browse & add MCP server connections |
| S6 | Complete | Summary + links to Chat UI and JobRunr dashboard |

The `OnboardingProvider` SPI lets plugins (Brave, Playwright, Telegram) inject their own onboarding steps automatically.

**Why ArcClaw needs it:**
ArcClaw's entire setup is `.env` + `application.yaml`. There's no discoverable first-run experience. Users must know config keys, provider IDs, model names upfront. This is a massive barrier to adoption — especially given the complexity of ArcClaw's security/vault/MCP setup.

**Integration approach (minimal reinvention):**
- Add a `arcclaw-onboarding` module (or sub-package in `arcclaw-app`)
- Use Quarkus Qute templates + HTMX (already a viable stack with Quarkus)
- Map ArcClaw-specific config keys (`ARCCLAW_LLM_PROVIDER`, `ARCCLAW_LLM_API_KEY`, `ARCCLAW_TELEGRAM_TOKEN`, etc.)
- Reuse ArcClaw's existing `SecretVault` for storing credentials (instead of writing to `application.yaml`)
- Port the `OnboardingProvider` CDI SPI so channel plugins (Telegram) can add their own step
- Step 5 can wire into the existing `McpConnectResource`

**Files to port from JavaClaw:**
- `OnboardingController.java` → adapt to JAX-RS resource
- `S1_WelcomeStep` through `S6_CompleteStep` → Qute template equivalents
- `OnboardingProvider.java` → CDI SPI interface
- All HTML templates → Qute `.html` with HTMX

---

### 2. Browser-based Real-time Chat UI ❌ MISSING IN ARCCLAW

**What JavaClaw has:**
- `ChatWebSocketHandler` — Spring WebSocket handler that streams agent responses token-by-token
- `ChatController` — REST endpoint to initiate chat sessions
- `ChatHtml` + `Htmx` utilities — server-side HTMX fragment rendering
- Full `chat.html.peb` UI with message history, typing indicator, and a scrollable conversation view
- `ChatChannel` — implements the `Channel` SPI so the web chat participates in the same channel architecture as Telegram

**Why ArcClaw needs it:**
ArcClaw's only interactive modes are:
- CLI (`arcclaw chat` / `arcclaw ask`) — requires terminal
- REST API (`/agent/run`) — requires a REST client or curl

There is no browser UI. This locks out non-technical users entirely and makes demos/evaluation much harder.

**Integration approach:**
- ArcClaw already has the `Channel` SPI (`ChannelPlugin`, `ChannelSender`, `ChannelDaemon`, etc.)
- Add a `arcclaw-channel-web` module implementing the 6-interface Channel contract
- Use Quarkus WebSocket (`@ServerEndpoint` or Quarkus WebSockets Next)
- Implement `WebChannelDaemon` that manages WebSocket sessions
- Implement `WebChannelSender` that pushes reply nodes over the WebSocket
- The `ReplyRenderer` can emit plain text or simple HTML fragments
- The frontend can be a single `index.html` served as a static resource (vanilla JS + HTMX or Alpine.js)
- Reuse ArcClaw's existing `SessionRegistry`, `MessagePipeline`, and `AgentLoop` — no core changes

**Files to port/adapt:**
- `ChatWebSocketHandler.java` → Quarkus `@ServerEndpoint` or WebSockets Next
- `ChatChannel.java` → implement `ChannelPlugin` SPI fully
- `chat.html.peb` → static HTML in `src/main/resources/META-INF/resources/`

---

### 3. CheckListTool — Structured Agent Progress Tracking ❌ MISSING IN ARCCLAW

**What JavaClaw has:**
`CheckListTool` is one of JavaClaw's most UX-impactful features. It is a Spring AI `@Tool` that lets the agent:
- Create a structured checklist for any multi-step task
- Mark items `pending` → `in_progress` → `completed` in real-time
- Enforce only ONE item in progress at a time
- Require both `content` (imperative form) and `activeForm` (present-continuous form) for each item

The tool fires a `CheckListEventHandler` callback on every update — in the web UI this pushes a live checklist panel via WebSocket/SSE.

**Why ArcClaw needs it:**
ArcClaw's `AgentStateMachineRunner` executes reasoning steps invisibly. The user sees the final answer but has no window into what the agent is doing. For long-running tasks this creates anxiety and opacity. The CheckListTool would provide this window at the application layer without changing the core engine.

**Integration approach (minimal reinvention):**
ArcClaw already has the `AgentTool` SPI (`AgentTool` interface with `name()`, `description()`, `execute()`, `parametersSchema()`). The CheckListTool maps cleanly to this:

```java
@ApplicationScoped
public class CheckListTool implements AgentTool {
    
    @Inject SessionRegistry sessionRegistry; // to push updates
    
    @Override public String name() { return "check_list"; }
    
    @Override public ToolResponse execute(Map<String, Object> params) {
        // validate + emit event to WebSocket session
    }
    
    @Override public boolean requiresManualApproval(Map<String, Object> params) {
        return false; // checklist management never needs HITL
    }
}
```

- Register via `ToolRegistry` — it will be available to all agent roles
- The `CheckListEventHandler` callback pushes updates to the active WebSocket session (via `SessionRegistry`)
- The validation logic (single in-progress item, non-blank content/activeForm) is self-contained and ports exactly

**Files to port:**
- `CheckListTool.java` — almost verbatim, swap `@Tool` annotation for `AgentTool` interface
- `CheckListTool.CheckList` record stays identical

---

### 4. Anthropic Claude Code OAuth Backend ❌ MISSING IN ARCCLAW

**What JavaClaw has:**
Three classes that implement using **Claude Code's stored OAuth token** as an Anthropic backend:
- `AnthropicClaudeCodeBackend` — reads the OAuth token from `~/.claude` and calls the Anthropic API
- `AnthropicClaudeCodeOAuthTokenExtractor` — parses the Claude Code credential store
- `AnthropticClaudeCodeConfiguration` — Spring auto-configuration

This means users who already have Claude Code installed can use it as a free/subscription-backed LLM without entering an API key.

**Why ArcClaw needs it:**
ArcClaw targets developers. Many of them will already have Claude Code installed. This provider eliminates the credential setup barrier entirely for that audience — directly complementing the onboarding improvement.

**Integration approach:**
- Add a `RoutingChatModelProvider` implementation (`ClaudeCodeProviderFactory`) in `arcclaw-app/routing/`
- Read the credential file from `~/.claude/` (same logic as JavaClaw's `OAuthTokenExtractor`)
- Store the extracted token in ArcClaw's `SecretVault` (better than JavaClaw's in-memory approach)
- Register with provider ID `"claude-code"` in `LlmModelFactory`
- Add to `.env.example` as an option

**Files to port:**
- `AnthropicClaudeCodeOAuthTokenExtractor.java` — port directly, remove Spring annotations
- `AnthropicClaudeCodeBackend.java` — adapt to LangChain4j `ChatModel` interface
- The auto-configuration → `@PostConstruct` in `ModelProducer` with config guard

---

### 5. Agent-Driven Runtime MCP Server Registration ⚠️ PARTIAL IN ARCCLAW

**What JavaClaw has:**
`McpTool` — a `@Tool` that the AI agent itself can call to add new MCP servers to `application.yaml` at runtime:
- `addStreamableHttpMcpServer(name, url, headers)` — HTTP MCP
- `addStdioMcpServer(name, command, args, env)` — stdio MCP

The tool persists the new server config via `ConfigurationManager.updateProperties()`, which writes YAML. Effect takes place on next restart.

**What ArcClaw has:**
`McpConnectResource` and `McpAdminResource` expose REST endpoints for MCP management, but only a human with REST access can call them — the agent cannot self-configure.

**Why ArcClaw needs it:**
The ability for the agent to be told "connect to this MCP server" in natural language and execute it is powerful for automation workflows. ArcClaw has the plumbing; it just doesn't expose it as an `AgentTool`.

**Integration approach:**
- Create `McpSelfRegisterTool` implementing `AgentTool`
- Delegate to the existing `McpClientService` / `McpServerConfig` storage
- Gate with `requiresManualApproval = true` (always require HITL — this is a high-trust operation)
- ArcClaw's `DynamicToolCapabilityRegistry` can pick up the new connection immediately (no restart needed, unlike JavaClaw)

**Key improvement over JavaClaw:** ArcClaw's MCP layer (`McpClientService`) already supports live hot-connection — no restart needed. The JavaClaw approach (write to YAML, restart) is weaker.

---

### 6. Playwright Browser Tool ❌ MISSING IN ARCCLAW

**What JavaClaw has:**
`PlaywrightBrowserTool` — a plugin that uses Microsoft Playwright Java to:
- Navigate to URLs
- Extract page content (text, DOM)
- Take screenshots
- Interact with web pages (click, fill, submit)

It's packaged as an opt-in auto-configuration plugin in `plugins/playwright/`.

**Why ArcClaw needs it:**
ArcClaw has a `SandboxExecTool` (Podman-isolated shell execution) but no dedicated web browsing capability. Researchers and automation tasks frequently need to read web pages beyond what a search API returns.

**Integration approach:**
- Add `arcclaw-tool-playwright` module (mirrors `plugins/playwright/` structure)
- Implement `AgentTool` for the browsing operation
- Gate with `requiresSandbox = true` (run Playwright inside Podman container for isolation)
- OR: Use `SandboxExecTool` + a headless Playwright/Puppeteer script as a simpler bridge
- Register with `ToolCapability` enum extension or as a dynamic tool capability

**Files to port:**
- `PlaywrightBrowserTool.java` — adapt to `AgentTool` interface
- `PlaywrightAutoConfiguration.java` → CDI `@ApplicationScoped` bean with config guard (`isConfigured()`)

---

### 7. Brave Web Search Tool ❌ MISSING IN ARCCLAW

**What JavaClaw has:**
`BraveWebSearchAutoConfiguration` — auto-configures a Brave Search API tool when `BRAVE_API_KEY` is set. Integrates with Spring AI's `WebSearchTool`.

**Why ArcClaw needs it:**
ArcClaw's Researcher role has no built-in web search. It relies on the user providing context or the GitHub tool. Adding Brave search gives the Researcher role real internet access.

**Integration approach:**
- Add `arcclaw-tool-brave` module or add to `arcclaw-app`
- Implement `AgentTool` that calls Brave Search API (simple HTTP call via MicroProfile REST Client)
- Register via `ToolRegistry` with `ToolCapability.WEB_SEARCH`
- Gate behind `ARCCLAW_BRAVE_API_KEY` config; tool reports `isConfigured() = false` if absent
- Does **not** require approval (read-only, no side effects)

---

### 8. AgentEnvironment Tool ⚠️ WEAKER IN ARCCLAW

**What JavaClaw has:**
`AgentEnvironment` — a singleton that exposes system context to the agent:
- Working directory
- Whether it's a Git repo
- OS/platform
- Java version
- Available tools summary

This is injected into the system prompt so the agent understands its execution context.

**What ArcClaw has:**
`SystemInfoResource` (REST endpoint) and `ArcClawInfo` expose version/build info, but this doesn't flow into the agent's system prompt automatically.

**Integration approach:**
- Add an `EnvironmentContextEnricher` implementing `ContextEnricher`
- ArcClaw already has the `ContextEnricher` SPI — this is a one-class addition
- Emit: working dir, OS, Java version, Git status, ArcClaw version, enabled tool names
- Inject via `SystemPromptBuilder` which already calls all `ContextEnricher` beans

---

### 9. Human-readable Workspace File Persistence ⚠️ DIFFERENT IN ARCCLAW

**What JavaClaw has:**
- Tasks stored as Markdown files in `workspace/tasks/`
- Chat memory stored as YAML files in `workspace/memory/`
- Skills loaded from `workspace/skills/` at runtime (hot-reload)

This "files as source of truth" approach means users can open, edit, and version-control their agent's state with any text editor.

**What ArcClaw has:**
- SQLite for everything (tasks, memory, sessions, audit, RAG)
- Skills loaded from classpath + database — no hot-reload from filesystem by default
- The `arcclaw.skills.hot-reload` config exists but filesystem watching is separate from the Markdown-file approach

**Why it matters:**
The filesystem approach has a power-user appeal: you can git commit your agent's task history and memory. ArcClaw's SQLite approach is more robust for concurrency/queries but opaque.

**Integration approach (low effort):**
- Add an optional `MarkdownTaskExporter` that writes a human-readable `.md` copy of each task alongside the SQLite record (write-through, read from SQLite)
- Enable via `arcclaw.tasks.markdown-export: true`
- No schema changes needed — purely additive

---

### 10. OnboardingProvider SPI for Channel Plugins ❌ MISSING IN ARCCLAW

**What JavaClaw has:**
`OnboardingProvider` is a Spring SPI interface implemented by each plugin:

```java
public interface OnboardingProvider {
    String id();
    String getHtmlContent(Model model);
    boolean isConfigured();
}
```

Telegram, Brave, Playwright each implement this. The onboarding wizard discovers them via Spring's bean registry and injects their steps automatically.

**Why ArcClaw needs it:**
When ArcClaw adds the Onboarding UI (gap #1), channel plugins need a way to contribute steps without modifying the onboarding controller. ArcClaw's CDI `Instance<T>` injection makes this trivially implementable — it's literally the same pattern ArcClaw already uses for `RoutingChatModelProvider`, `EmbeddingProviderFactory`, etc.

**Integration approach:**
```java
public interface OnboardingStepProvider {
    String stepId();
    int order();
    boolean isConfigured();
    String renderStep(); // Qute template name
}
```
CDI discovers all `@ApplicationScoped` implementations automatically — zero registration boilerplate.

---

## Part 2 — Prioritization Matrix

| Feature | User Impact | Effort | Breaks Nothing | **Priority** |
|---|---|---|---|---|
| 2. Browser Chat UI | 🔴 Critical | Medium | Yes — Channel SPI | **P1** |
| 1. Onboarding Web UI | 🔴 Critical | Medium-High | Yes — new module | **P1** |
| 3. CheckListTool | 🟠 High | Low | Yes — new AgentTool | **P2** |
| 4. Claude Code OAuth | 🟠 High | Low | Yes — new RoutingProvider | **P2** |
| 5. Agent MCP registration | 🟠 High | Low | Yes — wraps existing API | **P2** |
| 8. AgentEnvironment | 🟡 Medium | Very Low | Yes — ContextEnricher | **P3** |
| 6. Playwright Browser | 🟡 Medium | Medium | Yes — new module | **P3** |
| 7. Brave Web Search | 🟡 Medium | Low | Yes — new AgentTool | **P3** |
| 9. Markdown task export | 🟢 Low | Very Low | Yes — additive | **P4** |
| 10. OnboardingProvider SPI | 🟢 Low | Very Low | Yes — CDI interface | **P4** (part of P1) |

---

## Part 3 — Implementation Playbook

### P1-A: Browser Chat UI (arcclaw-channel-web)

**New module:** `arcclaw-channel-web`

Required files:
```
arcclaw-channel-web/
  pom.xml                         (depends on arcclaw-channel-api)
  src/main/java/.../channel/web/
    WebChannelPlugin.java          implements ChannelPlugin
    WebChannelDaemon.java          manages WebSocket lifecycle
    WebChannelSender.java          pushes ReplyNode list to WS session
    WebChannelRoute.java           JAX-RS + WebSocket endpoint
    WebReplyRenderer.java          ReplyNode → plain text / markdown
    WebAllowlist.java              SessionRegistry-based allowlist
    WebJobNotifier.java            sends cron results to open WS sessions
  src/main/resources/
    META-INF/resources/index.html  static chat SPA (HTMX or vanilla JS)
    META-INF/beans.xml
```

**No changes to arcclaw-core required.** The Channel SPI already handles everything.

Key integration points:
- `WebChannelDaemon.start()` starts a `@ServerEndpoint` WebSocket server
- `WebChannelSender.send(List<ReplyNode>)` pushes to the active session via `SessionRegistry`
- `WebReplyRenderer.render(ReplyNode)` converts to plain text or simple HTML
- `ChannelType` needs `WEB` added to the enum

---

### P1-B: Onboarding UI (arcclaw-onboarding)

**New module:** `arcclaw-onboarding`

Steps:
1. Create `OnboardingResource` (JAX-RS) at `/onboarding`
2. Use Qute templates for each step (S1–S6)
3. Map config writes to `SecretVault` (not application.yaml like JavaClaw)
4. Step S2 (Provider): list `arcclaw.llm.provider` options (openai, groq, ollama, anthropic, claude-code)
5. Step S3 (Credentials): write to vault + validate with a test LLM call
6. Step S5 (MCP): call `McpConnectResource` to register servers
7. Step S6 (Complete): link to Chat UI (`/`) and Quarkus Dev UI
8. Add `OnboardingStepProvider` CDI SPI (gap #10)

---

### P2-A: CheckListTool

**Add to `arcclaw-core/tools/`:**

```java
@ApplicationScoped
public class CheckListTool implements AgentTool {
    
    @Inject Instance<CheckListEventObserver> observers;
    
    @Override public String name() { return "check_list"; }
    @Override public ToolCapability capability() { return ToolCapability.MANAGE_CHECKLIST; }
    @Override public boolean requiresManualApproval(Map<String, Object> p) { return false; }
    @Override public boolean requiresSandbox() { return false; }
    
    @Override
    public ToolResponse execute(Map<String, Object> params) {
        CheckList checklist = parseAndValidate(params);
        observers.forEach(o -> o.onCheckListUpdate(WorkspaceContext.sessionId(), checklist));
        return ToolResponse.success("Checklist updated.");
    }
}
```

- Add `MANAGE_CHECKLIST` to `ToolCapability` enum
- Add `CheckListEventObserver` CDI event interface (fired to WebChannel, CLI observer, etc.)
- Port validation logic verbatim from JavaClaw

---

### P2-B: Claude Code OAuth Provider

**Add to `arcclaw-app/routing/`:**

```java
@ApplicationScoped
public class ClaudeCodeProviderFactory implements RoutingChatModelProvider {
    
    @Override public String providerId() { return "claude-code"; }
    
    @Override
    public ChatModel create(LlmModelConfig config) {
        String token = readClaudeCodeOAuthToken(); // port from JavaClaw
        return AnthropicChatModel.builder()
            .apiKey(token)
            .modelName(config.modelName())
            .build();
    }
    
    private String readClaudeCodeOAuthToken() {
        // Read ~/.claude/credentials.json — port from AnthropicClaudeCodeOAuthTokenExtractor
    }
}
```

Optionally: store the extracted token in `SecretVault` to avoid repeated file reads.

---

### P2-C: Agent MCP Self-Registration Tool

**Add to `arcclaw-mcp/` or `arcclaw-core/tools/`:**

```java
@ApplicationScoped
public class McpSelfRegisterTool implements AgentTool {
    
    @Inject McpClientService mcpClientService;
    
    @Override public String name() { return "add_mcp_server"; }
    @Override public boolean requiresManualApproval(Map<String, Object> p) { return true; } // always HITL
    
    @Override
    public ToolResponse execute(Map<String, Object> params) {
        McpServerConfig cfg = buildConfig(params);
        mcpClientService.connect(cfg);  // hot-connect, no restart needed
        return ToolResponse.success("MCP server connected: " + cfg.name());
    }
}
```

This is **strictly better than JavaClaw's approach** because ArcClaw's MCP layer supports live connections.

---

### P3: AgentEnvironment ContextEnricher

**Add to `arcclaw-core/agent/reasoning/`:**

```java
@ApplicationScoped
public class SystemEnvironmentEnricher implements ContextEnricher {
    
    @Inject ToolRegistry toolRegistry;
    
    @Override
    public String enrich() {
        return """
            ## System Environment
            - Working Directory: %s
            - Git Repository: %s
            - OS: %s / %s
            - Java: %s
            - ArcClaw Version: %s
            - Active Tools: %s
            """.formatted(
                System.getProperty("user.dir"),
                isGitRepo() ? "yes" : "no",
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                System.getProperty("java.version"),
                ArcClawInfo.VERSION,
                String.join(", ", toolRegistry.registeredToolNames())
            );
    }
}
```

Zero changes to core. `SystemPromptBuilder` already discovers `ContextEnricher` beans via CDI.

---

## Part 4 — What ArcClaw Does Better (Don't Regress)

When porting JavaClaw features, preserve these ArcClaw strengths:

| ArcClaw Strength | Risk when porting |
|---|---|
| **HITL per-tool approval** | CheckListTool must bypass approval (it's metadata, not action) |
| **Audit trail** | All new tools must call `AuditService.record()` |
| **Secret vault** | Credentials from Onboarding must go through `SecretVault`, not plain YAML |
| **Tool sanitization** | New tools must pass through `ToolObservationSanitizer` |
| **Channel neutrality** | Chat UI must implement full Channel SPI, not shortcut core |
| **Native image** | New classes used reflectively need `reflect-config.json` registration |
| **SkillRegistry hot-reload** | Don't disable it when adding filesystem exports |
| **Architecture invariants** | Read `Architecture_Invariants.md` before any core change |

---

## Part 5 — Files to NOT Port

These JavaClaw patterns are superseded by ArcClaw's more robust equivalents:

| JavaClaw | Skip because | ArcClaw equivalent |
|---|---|---|
| `FileSystemTaskRepository` (Markdown) | ArcClaw SQLite is superior | `StatefulAgentStore` + optional Markdown export |
| `FileSystemChatMemoryRepository` (YAML) | ArcClaw's `PersistentChatMemoryStore` with compaction is far better | `PersistentChatMemoryStore` |
| `JobRunr` scheduler | ArcClaw's cron scheduler integrates with approval/HITL | `AgentCronScheduler` + `ScheduleTaskTool` |
| `TaskTool` / `TaskManager` | ArcClaw's task+cron model is more sophisticated | `ScheduleTaskTool` + `ManageScheduleTool` |
| `ConfigurationManager` (write to YAML) | ArcClaw's `SecretVault` is cryptographically sound | `SecretVault` + `EncryptedSecretRepository` |
| `ChannelRegistry.getLatestChannel()` | Architecturally fragile; ArcClaw routes by channelType | `ChannelPluginRegistry.find(channelType)` |
| `DefaultAgent.respondTo()` | Too simple for ArcClaw's multi-role system | `AgentLoop.run()` |

---

## Quick Wins (< 1 day each)

1. **AgentEnvironment ContextEnricher** — ~50 lines, zero risk, immediate system-prompt improvement
2. **CheckListTool skeleton** — ~100 lines, port validation logic verbatim, wire to CDI event
3. **Brave Web Search AgentTool** — ~80 lines, REST call + `ToolCapability.WEB_SEARCH`
4. **Claude Code OAuth ProviderFactory** — ~120 lines, port OAuth extraction + register as `RoutingChatModelProvider`
5. **McpSelfRegisterTool** — ~80 lines, wraps existing `McpClientService`
6. **Markdown task export** — ~40 lines, write-through observer on task save events

---

## TL;DR — What ArcClaw Is Missing From JavaClaw

ArcClaw is the **far more sophisticated engine** (multi-role agents, HITL, audit, vault, RAG, compaction, parallel tools) but JavaClaw has two critical **UX/DX layers** that ArcClaw completely lacks.

### 🔴 P1 — Critical Gaps

**1. Browser-based Chat UI** — ArcClaw has no browser interface. JavaClaw has a full WebSocket chat UI with streaming. The good news: ArcClaw already has the **Channel SPI** (`ChannelPlugin`, `ChannelSender`, `ChannelDaemon`, etc.). You just need to create an `arcclaw-channel-web` module implementing those 6 interfaces. Zero changes to core.

**2. Guided Onboarding Wizard** — JavaClaw's 6-step HTMX wizard (Provider → Credentials → Agent.md → MCP → Done) is a massive DX win. ArcClaw's setup is all `.env` + YAML which is opaque. Port this as a `arcclaw-onboarding` module using Qute templates, but route credentials through ArcClaw's `SecretVault` (not plain YAML like JavaClaw does) — that's a strict improvement.

### 🟠 P2 — High-Value, Low Effort

**3. CheckListTool** — The most impactful single tool. Lets the agent create a live checklist for complex tasks. ~100 lines, ports almost verbatim, just implement `AgentTool` interface instead of Spring's `@Tool`. Wire the event to the WebSocket session.

**4. Claude Code OAuth provider** — If your users have Claude Code installed, they can skip API key setup entirely. Just add a `ClaudeCodeProviderFactory` implementing `RoutingChatModelProvider`. Store the OAuth token in `SecretVault`.

**5. Agent MCP self-registration** — JavaClaw lets the AI agent register new MCP servers by natural language instruction. ArcClaw has the REST plumbing (`McpConnectResource`) but the *agent* can't call it. Wrap it in an `AgentTool` with HITL=true. Actually **better** than JavaClaw because ArcClaw supports hot-connect (no restart needed).

### 🟡 P3 — Medium Priority

**6 & 7. Playwright browser tool + Brave web search** — ArcClaw's Researcher role has no web access. Both are ~80-120 line additions.

**8. AgentEnvironment ContextEnricher** — Inject OS/git/tool info into the system prompt. ~50 lines, zero risk, ArcClaw's `ContextEnricher` SPI was literally built for this.

### ❌ What to NOT Port

The report also flags things to skip: JavaClaw's `FileSystemTaskRepository`, `JobRunr` scheduler, `ConfigurationManager` (write to YAML), and `ChannelRegistry.getLatestChannel()` — all superseded by ArcClaw's more robust equivalents.

---

*Last updated: March 2026 | Based on full source analysis of `jobrunr-javaclaw` and `rsrini7-arcclawinternal` codebases*