Now I have everything I need. The gap analysis, existing prompts, Lit spec, and ArcClaw code are sufficient — **you do not need to share the JavaClaw 300KB source code**. The `JavaClaw_vs_ArcClaw_Gap_Analysis-2.md` has already done the heavy lifting of identifying every feature, the integration approach, and the specific files to port. Adding raw JavaClaw source would only add noise without changing the implementation contracts. 

Here is the complete updated prompts document, with Phase 77 preserved verbatim, Phase 78 updated (HTMX static frontend removed, SSE REST endpoints added), Phase 79 completely replaced with the Lit SPA module, Phase 80 as a new Lit-compatible onboarding phase, and Phase 81 as a new Playwright tool phase. 

***

## What Changed vs. Original

| Phase | Original | Updated |
|---|---|---|
| **77** | 5 Java backend capabilities (A1–A5) | **Unchanged** |
| **78** | arcclaw-channel-web with HTMX static `index.html` | **Updated**: remove HTMX frontend; add `AgentStatusResource`, `HitlResource`, `ChatStreamResource` SSE endpoints; frontend deferred to Phase 79 |
| **79** | arcclaw-onboarding with Qute + HTMX | **Replaced**: now `arcclaw-ui` — the full Vite 6 + Lit 3 + TypeScript SPA |
| **80** | *(did not exist)* | **New**: arcclaw-onboarding, JSON REST API backend (no Qute), `arc-onboarding` Lit component lives in `arcclaw-ui` |
| **81** | *(did not exist)* | **New**: arcclaw-tool-playwright — Playwright browser tool |

***

````markdown
# ArcClaw Implementation Prompts
# Version: 2.0 — March 2026
# Supersedes: JavaClaw-Prompts-3.md
# Integrates: Lit-4.md (arcclaw-ui component spec)
#
# Execution order: 77 → 78 → 79 → 80 → 81
# Phase 77 can run in parallel with Phase 78 (different modules).
# Phase 79 requires Phase 78 backend endpoints to be deployed.
# Phase 80 requires Phase 79 arcclaw-ui scaffold.
# Phase 81 is standalone — can run any time after Phase 77.

---

# PROMPT — PHASE 77: Five High-Value Agent Capabilities (Tier A)

Role: Senior Java Architect & Senior Java Developer
Task: Phase 77 — Implement 5 new agent capabilities extracted from the JavaClaw reference
implementation, adapted to ArcClaw's existing SPIs. No architectural changes. Additive only.
All 5 components are self-contained; each builds on existing ArcClaw interfaces without
modifying core engine classes.

---

## Architecture Context and Other References
Refer @Architecture_Invariants.md
Refer @arcclaw-core/src/main/java/com/arcclaw/core/tools/AgentTool.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/ContextEnricher.java
Refer @arcclaw-rag/src/main/java/com/arcclaw/rag/IngestDocumentTool.java (AgentTool reference impl)
Refer @arcclaw-app/src/main/java/com/arcclaw/app/routing/GroqLlmProviderFactory.java (RoutingChatModelProvider reference impl)

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Every component must be accompanied by at least one unit test proving correct behaviour.
2. No new Maven module. All code goes into existing modules as specified per component.
3. All existing tests must remain green after every component.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. All code must be safe under JDK 21+ (no preview features unless already used in codebase).
6. Every new `AgentTool` implementation MUST:
   a. Call `AuditService.record(...)` with the appropriate `SecurityEventType` on execution.
   b. Return `ToolResponse.failure(403, ...)` if `WorkspaceContext.userId()` is blank.
   c. Never propagate `BudgetExceededException` — let it bubble to `AgentStateMachineRunner`.
   d. Be annotated `@ApplicationScoped`.
   e. Be discovered automatically by `ToolRegistry` via CDI — no manual registration.
7. Test commands always use `clean` to prevent stale class false-negatives.
   Example: `./mvnw clean test -pl arcclaw-core`

---

### Component A1 — SystemEnvironmentContextEnricher

**Module:** `arcclaw-core`
**Package:** `com.arcclaw.core.agent.reasoning`
**New file:** `SystemEnvironmentContextEnricher.java`

**What to build:**
Implement the `ContextEnricher` SPI to inject host environment metadata into the agent's
system prompt automatically. Modelled on JavaClaw's `AgentEnvironment` singleton but adapted
to ArcClaw's CDI `ContextEnricher` interface.

**Contract:**
```java
@ApplicationScoped
public class SystemEnvironmentContextEnricher implements ContextEnricher {
    @Inject ArcClawInfo arcClawInfo;
    @Inject ToolRegistry toolRegistry;

    @Override
    public String enrich() {
        // Return a markdown block that SystemPromptBuilder will inject.
        // MUST include: working directory, Git repo detection (.git dir),
        // OS name + arch, Java version, ArcClaw version, comma-separated
        // list of registered tool names (toolRegistry.registeredToolNames()).
    }
}
```

**Rules:**
- Git detection: check `new File(System.getProperty("user.dir"), ".git").exists()` — never shell out.
- The enricher MUST be idempotent and stateless (called once per session start).
- Do NOT log secrets or environment variables that could contain credentials.
- The returned string must be valid markdown (no raw HTML).

**Test:** `SystemEnvironmentContextEnricherTest` in `arcclaw-core`
- Assert the returned string contains the actual working directory.
- Assert it contains "Java" and a version string.
- Assert it contains at least one tool name when `ToolRegistry` has tools registered.
- Use a real CDI test (`@QuarkusTest`) or a pure unit test with a mock `ToolRegistry`.

---

### Component A2 — CheckListTool

**Module:** `arcclaw-core`
**Package:** `com.arcclaw.core.tools`
**New files:**
- `CheckListTool.java`
- `CheckListEvent.java` (CDI event payload)
**Modified files:**
- `ToolCapability.java` — add `MANAGE_CHECKLIST("CheckListTool")`

**What to build:**
A structured progress-tracking tool the agent uses for multi-step tasks. Ported from JavaClaw's
`CheckListTool` but implementing ArcClaw's `AgentTool` interface and firing a CDI event instead
of a direct callback.

**Contract:**

```java
// CheckListEvent.java — CDI event, fired by CheckListTool on every update
public record CheckListEvent(String sessionId, CheckList checkList) {}

// CheckListTool.java
@ApplicationScoped
public class CheckListTool implements AgentTool {

    @Inject Event<CheckListEvent> checkListEvents;
    @Inject AuditService auditService;

    @Override public String name() { return ToolCapability.MANAGE_CHECKLIST.toolName(); }
    @Override public boolean requiresSandbox() { return false; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.READ_FS; }
    @Override public boolean requiresManualApproval(Map<String, Object> params) { return false; }

    @Override
    public JsonObjectSchema parametersSchema() {
        // Single parameter: "checkList" — an array of objects each with:
        // "content" (string, required), "status" (enum: pending/in_progress/completed),
        // "activeForm" (string, required)
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        // 1. Parse params into CheckList record (see data model below)
        // 2. Validate (see validation rules below)
        // 3. Fire CheckListEvent via CDI Event<CheckListEvent>
        // 4. Audit: SecurityEventType.TOOL_EXECUTED
        // 5. Return ToolResponse.success("Checklist updated. Continue tracking progress.")
    }
}
```

**Data model (nested records inside `CheckListTool`):**
```java
public record CheckList(List<CheckListItem> checkList) {
    public record CheckListItem(String content, Status status, String activeForm) {}
    public enum Status { pending, in_progress, completed }
}
```

**Validation rules (throw `IllegalArgumentException` on violation):**
1. `checkList` must not be null or empty.
2. Each item's `content` must not be blank.
3. Each item's `activeForm` must not be blank.
4. Each item's `status` must not be null.
5. At most ONE item may have `status == in_progress` at any time.
   If > 1 in_progress items are present → reject with a clear message naming the count.

**Test:** `CheckListToolTest` in `arcclaw-core`
- Test: valid single in_progress item → succeeds, CDI event is fired.
- Test: two in_progress items → `IllegalArgumentException`.
- Test: blank `content` → `IllegalArgumentException`.
- Test: blank `activeForm` → `IllegalArgumentException`.
- Test: null `status` → `IllegalArgumentException`.
- Test: all items completed, none in_progress → valid.
- Test: empty checklist → `IllegalArgumentException`.
Use `@QuarkusTest` or mock CDI event with `Event<CheckListEvent>` spy.

---

### Component A3 — ClaudeCodeOAuthProviderFactory

**Module:** `arcclaw-app`
**Package:** `com.arcclaw.app.routing`
**New files:**
- `ClaudeCodeOAuthTokenExtractor.java`
- `ClaudeCodeProviderFactory.java`
**Modified files:**
- `application.yaml` — add comment block documenting `arcclaw.llm.provider=claude-code`
- `.env.example` — add `# ARCCLAW_LLM_PROVIDER=claude-code  # Use Claude Code OAuth — no API key needed`

**What to build:**
Allow users who have Anthropic Claude Code installed locally to use its stored OAuth token as
an LLM backend — zero API key setup. Implements `RoutingChatModelProvider` SPI.

**ClaudeCodeOAuthTokenExtractor contract:**
```java
/**
 * Reads the OAuth token stored by Claude Code CLI from ~/.claude/credentials.json.
 * Returns Optional.empty() if the file does not exist or cannot be parsed.
 * MUST NOT throw on missing file — callers treat Optional.empty() as "not configured".
 */
@ApplicationScoped
public class ClaudeCodeOAuthTokenExtractor {

    private static final Path CREDENTIALS_PATH =
        Path.of(System.getProperty("user.home"), ".claude", "credentials.json");

    public Optional<String> extractToken() {
        // Read the JSON file, extract the accessToken (or bearer token) field.
        // Use Jackson ObjectMapper (already a dependency).
        // Catch all I/O and JSON exceptions — return Optional.empty() on any failure.
        // MUST NOT log the raw token value — log only "Claude Code credentials found/not found".
    }
}
```

**ClaudeCodeProviderFactory contract:**
```java
@ApplicationScoped
public class ClaudeCodeProviderFactory implements RoutingChatModelProvider {

    @Inject ClaudeCodeOAuthTokenExtractor tokenExtractor;
    @Inject SecretVault secretVault;

    @Override public String providerId() { return "claude-code"; }

    @Override
    public ChatModel create(LlmModelConfig config) {
        // 1. Try SecretVault first (vault key: "claude-code-oauth-token").
        // 2. Fall back to tokenExtractor.extractToken().
        // 3. If token found and not in vault → store in vault for future use.
        // 4. If no token → throw IllegalStateException with helpful message
        //    pointing user to `claude login` CLI command.
        // 5. Build AnthropicChatModel (LangChain4j) with the token as the API key.
        //    Model name defaults to "claude-sonnet-4-5" if config.modelName() is blank
        //    or equals the Ollama default "llama3.2".
    }
}
```

**Rules:**
- The extracted token MUST be stored via `SecretVault` on first successful extraction so that
  subsequent restarts do not re-read the file.
- The vault key MUST use the system session constant (see Architecture Invariant 8.2).
- Log only at INFO: "Claude Code provider: token loaded from vault/file". Never log the token.

**Test:** `ClaudeCodeOAuthTokenExtractorTest` in `arcclaw-app`
- Test: credentials file missing → `Optional.empty()`, no exception.
- Test: valid credentials.json with `accessToken` field → returns token value.
- Test: malformed JSON → `Optional.empty()`, no exception.
- Test: credentials file present but `accessToken` field missing → `Optional.empty()`.
Create a temp credentials file in `@TempDir` for each test case.

---

### Component A4 — McpSelfRegisterTool

**Module:** `arcclaw-mcp`
**Package:** `com.arcclaw.mcp.tool`
**New file:** `McpSelfRegisterTool.java`
**Modified files:**
- `ToolCapability.java` — add `REGISTER_MCP_SERVER("add_mcp_server")`

**What to build:**
Allow the agent itself (not just a human via REST) to register new MCP servers in natural
language. Wraps the existing `McpClientService.connect()` — no new transport logic. Always
requires HITL approval because this is a high-trust system-configuration operation.

**Contract:**
```java
@ApplicationScoped
public class McpSelfRegisterTool implements AgentTool {

    @Inject McpClientService mcpClientService;
    @Inject AuditService auditService;

    private static final Pattern SAFE_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");

    @Override public String name() { return ToolCapability.REGISTER_MCP_SERVER.toolName(); }
    @Override public boolean requiresSandbox() { return false; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.NETWORK; }
    @Override public boolean requiresManualApproval(Map<String, Object> params) { return true; }

    @Override
    public String description() {
        return "Registers and connects a new external MCP (Model Context Protocol) server. " +
               "Supports streamable-HTTP and stdio transport types. " +
               "Always requires human approval before connecting.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        // Required: "name" (string), "transport" (enum: http, stdio)
        // Conditional on transport=http: "url" (string)
        // Conditional on transport=stdio: "command" (string), "args" (string, optional)
        // Optional for both: "headers" or "env" (string, key=value lines)
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        // 1. Validate userId via WorkspaceContext (403 if blank).
        // 2. Validate "name" matches SAFE_NAME_PATTERN — return failure(400) if not.
        // 3. Build McpServerConfig from params based on transport type.
        // 4. Call mcpClientService.connect(cfg) — hot connect, no restart.
        // 5. Audit with SecurityEventType.TOOL_EXECUTED and server name in detail.
        // 6. Return success with the server name and transport type.
        // On any exception: return ToolResponse.failure(500, message) — never propagate.
    }
}
```

**Rules:**
- `name` parameter MUST match `[a-zA-Z0-9_-]+` — reject with `ToolResponse.failure(400, ...)` if not.
- Do NOT accept a `url` that resolves to `localhost` or `127.0.0.1` if
  `arcclaw.security.block-loopback-mcp` is `true` (default: false — just read the config,
  do not add the property, only honour it if already present in application.yaml).
- The `headers` / `env` parameters, if provided, MUST be treated as opaque strings and
  MUST NOT be logged at any level.

**Test:** `McpSelfRegisterToolTest` in `arcclaw-mcp`
- Test: valid HTTP params → `mcpClientService.connect()` called with correct config.
- Test: invalid name (contains `/`) → `ToolResponse` with 400 status, connect not called.
- Test: blank userId → 403 failure, connect not called.
- Test: `McpClientService.connect()` throws `McpConnectionException` → failure response, no propagation.
Mock `McpClientService` with Mockito.

---

### Component A5 — BraveWebSearchTool

**Module:** `arcclaw-core` (tool definition) + config in `arcclaw-app`
**Package:** `com.arcclaw.core.tools`
**New file:** `BraveWebSearchTool.java`
**Modified files:**
- `ToolCapability.java` — add `WEB_SEARCH("web_search")`
- `AgentTool.java` — add `default boolean isEnabled() { return true; }`
- `ToolRegistry` — filter `isEnabled()` during registration scan
- `application.yaml` — add commented `arcclaw.tools.brave.api-key: ""`
- `.env.example` — add `# ARCCLAW_BRAVE_API_KEY=your_key  # Enables web_search tool`

**Contract:**
```java
@ApplicationScoped
public class BraveWebSearchTool implements AgentTool {

    private static final String BRAVE_SEARCH_URL =
        "https://api.search.brave.com/res/v1/web/search";

    @ConfigProperty(name = "arcclaw.tools.brave.api-key", defaultValue = "")
    String apiKey;

    @Inject AuditService auditService;

    @Override public String name() { return ToolCapability.WEB_SEARCH.toolName(); }
    @Override public boolean requiresSandbox() { return false; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.NETWORK; }
    @Override public boolean requiresManualApproval(Map<String, Object> params) { return false; }
    @Override public boolean isEnabled() { return !apiKey.isBlank(); }

    @Override
    public String description() {
        return "Searches the web using Brave Search API. Use for current events, " +
               "technical documentation lookups, and factual research. " +
               "Returns top results with titles, URLs, and snippets.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        // Required: "query" (string)
        // Optional: "count" (integer, default 5, max 20)
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        // 1. Guard: if apiKey is blank → return ToolResponse.failure(503, "Brave Search not configured").
        // 2. Validate userId (403 if blank).
        // 3. Build GET request to BRAVE_SEARCH_URL:
        //    - Header: X-Subscription-Token: {apiKey}
        //    - Query param: q={encoded query}, count={count clamped to } 
        // 4. Use java.net.http.HttpClient (JDK — no new dep) with Duration.ofSeconds(10) timeout.
        // 5. Parse JSON response: extract web.results[].{title, url, description}.
        // 6. Format as: "1. **Title**\n   URL\n   Snippet\n"
        // 7. If formatted string > 4096 chars: truncate at last complete entry + "\n[results truncated]"
        // 8. Audit: SecurityEventType.TOOL_EXECUTED with query (NOT results) as detail.
        // 9. Return ToolResponse.success(formattedResults).
        // On any HTTP error or JSON parse failure: return ToolResponse.failure(502, message).
        // MUST NOT log the apiKey at any level.
    }
}
```

**Test:** `BraveWebSearchToolTest` in `arcclaw-core`
- Test: blank apiKey → `ToolResponse` failure with 503, no HTTP call made.
- Test: blank userId → 403 failure.
- Test: `count` > 20 → clamped to 20 in the outbound request.
- Test: HTTP 401 from Brave → failure response, no exception propagated.
- Test: valid mock response with 3 results → formatted string contains all 3 titles.
Use `WireMock` (if already in test classpath) or `com.sun.net.httpserver.HttpServer` to mock
the Brave endpoint. Do NOT make real network calls in tests.

---

## Phase 77 Delivery Checklist
- [ ] A1: `SystemEnvironmentContextEnricher` + test green
- [ ] A2: `CheckListTool` + `CheckListEvent` + `ToolCapability.MANAGE_CHECKLIST` + tests green
- [ ] A3: `ClaudeCodeOAuthTokenExtractor` + `ClaudeCodeProviderFactory` + tests green
- [ ] A4: `McpSelfRegisterTool` + `ToolCapability.REGISTER_MCP_SERVER` + tests green
- [ ] A5: `BraveWebSearchTool` + `AgentTool.isEnabled()` default + `ToolCapability.WEB_SEARCH` + tests green
- [ ] All existing tests still pass: `./mvnw clean test`
- [ ] No new Maven dependencies introduced
- [ ] No modifications to `AgentLoop`, `AgentStateMachineRunner`, `ToolInvoker`, or `ToolRegistry` core logic

---

# PROMPT — PHASE 78: arcclaw-channel-web + UI REST Endpoints (Tier A)

Role: Senior Java Architect & Senior Java Developer
Task: Phase 78 — Two deliverables in one phase:
  (A) Create a new Maven module `arcclaw-channel-web` implementing the full 6-interface
      Channel SPI so the browser participates in the same MessagePipeline as Telegram.
  (B) Add three new REST resources to `arcclaw-app` that the Lit frontend (Phase 79) will
      consume: AgentStatusResource (SSE), HitlResource, and ChatStreamResource (SSE).
Zero changes to arcclaw-core. No static HTML frontend is produced in this phase —
the browser UI is delivered in Phase 79 as the `arcclaw-ui` Lit module.

---

## Architecture Context and Other References
Refer @Architecture_Invariants.md (§1.4 Channel abstraction, §4.5 Channel plugin contract)
Refer @CHANNEL_SPI.md  (the 6-interface contract and step-by-step guide)
Refer @arcclaw-channel-telegram/ (the Telegram module is the reference implementation)
Refer @arcclaw-channel-api/src/main/java/com/arcclaw/channel/api/ (all SPI interfaces)
Refer @arcclaw-app/src/main/java/com/arcclaw/app/session/SessionRegistry.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/pipeline/MessagePipeline.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/hitl/HitlService.java (if exists)
Refer @arcclaw-core/src/main/java/com/arcclaw/core/agent/AgentRoleEnum.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/agent/AutonomyLevel.java

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Every new public class must have at least one test.
2. No changes to `arcclaw-core`, `arcclaw-channel-api`, or any existing module's source.
   The ONLY permitted changes to existing files:
   a. Root `pom.xml` — add `arcclaw-channel-web` to `<modules>`.
   b. `ChannelType.java` — add `WEB` enum constant.
   c. `arcclaw-app/pom.xml` — add `arcclaw-channel-web` as a dependency.
   d. `application.yaml` — add `arcclaw.channel.web.*` config block (see below).
3. All existing tests must remain green.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. NO static HTML file is included in `arcclaw-channel-web`. The Lit SPA is a separate
   module delivered in Phase 79 (`arcclaw-ui`). The Quarkus CORS config already points to
   `localhost:3000` — that is where the frontend will be served by Vite dev server.
6. WebSocket sessions are scoped to a single browser tab. Multiple tabs = multiple sessions.
7. Test commands: `./mvnw clean test -pl arcclaw-channel-web,arcclaw-app`

---

## Part A — arcclaw-channel-web Module

### Module Setup

**New module:** `arcclaw-channel-web`

`pom.xml` must declare:
- Parent: `com.arcclaw:arcclaw-parent`
- Dependencies: `arcclaw-channel-api`, `arcclaw-core`, Quarkus WebSockets Next
  (use `quarkus-websockets-next` from BOM — preferred over legacy `quarkus-websockets`),
  standard Quarkus CDI.
- Include `jandex-maven-plugin` (mandatory per Architecture Invariant §15).
- Include `src/main/resources/META-INF/beans.xml` with `bean-discovery-mode="annotated"`.
- Do NOT include Quarkus Qute — this module has no server-side templates.

**Package root:** `com.arcclaw.channel.web`

---

### Component B1 — ChannelType.WEB

**Modified file:** `arcclaw-channel-api/.../ChannelType.java`
Add: `WEB`
No other changes to this file.

---

### Component B2 — WebChannelDaemon

**File:** `com.arcclaw.channel.web.WebChannelDaemon`

```java
@ApplicationScoped
public class WebChannelDaemon implements ChannelDaemon {

    @ConfigProperty(name = "arcclaw.channel.web.enabled", defaultValue = "true")
    boolean enabled;

    @Inject WebSocketSessionStore sessionStore;

    @Override public ChannelType channelType() { return ChannelType.WEB; }
    @Override public boolean isConfigured() { return enabled; }

    @Override
    public void start() {
        // WebSocket endpoint is registered by Quarkus automatically via @WebSocket.
        // No-op beyond logging.
        log.infof("Web channel daemon started — WebSocket endpoint /ws/chat active.");
    }

    @Override
    public void stop() {
        int count = sessionStore.all().size();
        sessionStore.closeAll();
        log.infof("Web channel daemon stopped — closed %d WebSocket sessions.", count);
    }
}
```

---

### Component B3 — WebSocketSessionStore

**File:** `com.arcclaw.channel.web.WebSocketSessionStore`

```java
@ApplicationScoped
public class WebSocketSessionStore {
    // ConcurrentHashMap<String sessionId, WebSocketConnection>
    // Use Quarkus WebSockets Next WebSocketConnection type.
    // Thread-safe — concurrent connects/disconnects are expected.

    public void register(String sessionId, WebSocketConnection conn);
    public void unregister(String sessionId);
    public Optional<WebSocketConnection> get(String sessionId);
    public Collection<WebSocketConnection> all();
    public void closeAll();  // called by WebChannelDaemon.stop()
}
```

`sessionId` is assigned on WebSocket open and stored in the connection's user data
under key `"arcclawSessionId"`.

---

### Component B4 — WebChatEndpoint (WebSocket server)

**File:** `com.arcclaw.channel.web.WebChatEndpoint`

Use Quarkus WebSockets Next `@WebSocket(path = "/ws/chat")`.

```java
@WebSocket(path = "/ws/chat")
public class WebChatEndpoint {

    @Inject WebSocketSessionStore sessionStore;
    @Inject MessagePipeline messagePipeline;
    @Inject WorkspaceManager workspaceManager;

    @OnOpen
    public void onOpen(WebSocketConnection conn) {
        String sessionId = UUID.randomUUID().toString();
        conn.userData().put("arcclawSessionId", sessionId);
        sessionStore.register(sessionId, conn);
        // Send handshake: {"type":"connected","sessionId":"<id>"}
        conn.sendText(buildConnectedJson(sessionId));
    }

    @OnTextMessage
    public void onMessage(String message, WebSocketConnection conn) {
        // 1. Parse JSON: {"type":"message","text":"<user text>"}
        // 2. If type != "message" → ignore silently.
        // 3. Extract sessionId from conn.userData().
        // 4. Build ChannelMessage using CliMessage(ChannelType.WEB, ...) as interim.
        //    // TODO: add WebMessage sealed type in arcclaw-channel-api
        // 5. Build ChannelUser with userId=sessionId, channelType=ChannelType.WEB.
        // 6. Build MessageContext.
        // 7. Run messagePipeline.handle(context) on a virtual thread:
        //    Thread.ofVirtual().start(() -> messagePipeline.handle(context));
    }

    @OnClose
    public void onClose(WebSocketConnection conn) {
        String sessionId = (String) conn.userData().get("arcclawSessionId");
        if (sessionId != null) {
            sessionStore.unregister(sessionId);
            workspaceManager.terminateSession(sessionId);
        }
    }

    @OnError
    public void onError(WebSocketConnection conn, Throwable t) {
        log.errorf(t, "WebSocket error for session %s", conn.userData().get("arcclawSessionId"));
        String sessionId = (String) conn.userData().get("arcclawSessionId");
        if (sessionId != null) sessionStore.unregister(sessionId);
    }
}
```

---

### Component B5 — WebChannelSender

**File:** `com.arcclaw.channel.web.WebChannelSender`

```java
@ApplicationScoped
public class WebChannelSender implements ChannelSender {

    @Inject WebSocketSessionStore sessionStore;
    @Inject WebReplyRenderer renderer;

    @Override public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public void send(String platformChatId, List<ReplyNode> nodes) {
        String rendered = renderer.render(nodes);
        String payload = Json.createObjectBuilder()
            .add("type", "message")
            .add("role", "assistant")
            .add("text", rendered)
            .build().toString();

        sessionStore.get(platformChatId).ifPresentOrElse(
            conn -> {
                try {
                    conn.sendText(payload).await().atMost(Duration.ofSeconds(5));
                } catch (Exception e) {
                    log.warnf("Failed to send to session %s: %s", platformChatId, e.getMessage());
                    sessionStore.unregister(platformChatId);
                }
            },
            () -> log.warnf("WebChannelSender: no WS session for id=%s", platformChatId)
        );
    }
}
```

---

### Component B6 — WebReplyRenderer

**File:** `com.arcclaw.channel.web.WebReplyRenderer`

```java
@ApplicationScoped
public class WebReplyRenderer implements ReplyRenderer {

    @Override public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public String render(List<ReplyNode> nodes) {
        // Walk the ReplyNode tree using the same pattern as TelegramMarkdownV2Renderer
        // but emit plain CommonMark markdown (no Telegram-specific escaping).
        // Text nodes → text as-is.
        // Code blocks → ```lang\n...\n```
        // Lists → standard markdown bullet/numbered list.
        // Return a single concatenated String.
        // Study TelegramMarkdownV2Renderer.java for the ReplyNode traversal pattern.
        // Do NOT copy the Telegram-specific escape logic.
    }
}
```

---

### Component B7 — WebAllowlist

**File:** `com.arcclaw.channel.web.WebAllowlist`

```java
@ApplicationScoped
public class WebAllowlist implements ChannelAllowlist {

    @ConfigProperty(name = "arcclaw.channel.web.require-auth", defaultValue = "false")
    boolean requireAuth;

    @Override public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public boolean isAllowed(ChannelUser user) {
        // requireAuth=false (local dev default) → always true.
        // requireAuth=true → Phase 78 stub: return false for any user.
        // Full allowlist enforcement is Phase 80+.
        return !requireAuth;
    }
}
```

---

### Component B8 — WebJobNotifier

**File:** `com.arcclaw.channel.web.WebJobNotifier`

```java
@ApplicationScoped
public class WebJobNotifier implements ChannelJobNotifier {

    @Inject WebChannelSender sender;

    @Override public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public void notifyJobCompleted(String platformChatId, String renderedAnswer, String correlationId) {
        // Build a single TextNode with renderedAnswer.
        // Call sender.send(platformChatId, List.of(textNode)).
        // Lost notifications (closed tab) are acceptable for Phase 78 — sender logs a warn.
    }
}
```

---

### Component B9 — WebChannelPlugin

**File:** `com.arcclaw.channel.web.WebChannelPlugin`

```java
@ApplicationScoped
public class WebChannelPlugin implements ChannelPlugin {

    @Inject WebChannelSender sender;
    @Inject WebReplyRenderer renderer;
    @Inject WebAllowlist allowlist;
    @Inject WebJobNotifier jobNotifier;
    @Inject WebChannelDaemon daemon;

    @Override public ChannelType channelType() { return ChannelType.WEB; }
    @Override public ChannelSender sender()          { return sender; }
    @Override public ReplyRenderer renderer()        { return renderer; }
    @Override public ChannelAllowlist allowlist()    { return allowlist; }
    @Override public ChannelJobNotifier jobNotifier(){ return jobNotifier; }
    @Override public ChannelDaemon daemon()          { return daemon; }
}
```

---

### Configuration Block (application.yaml addition)

```yaml
arcclaw:
  channel:
    web:
      enabled: true           # Set false to disable web channel entirely
      require-auth: false     # Set true to enforce allowlist in production
  cors:
    origins: "http://localhost:3000"  # Lit dev server — already present, verify this exists
```

---

## Part B — New UI REST Resources in arcclaw-app

These three resources power the Lit frontend. They belong in `arcclaw-app`, not in
`arcclaw-channel-web`, because they expose read-only views of engine state — not channel
routing logic.

**Module:** `arcclaw-app`
**Package:** `com.arcclaw.app.ui`
**Base path:** `/api/v1` (must match existing API base path in `application.yaml`)

---

### Component B10 — AgentStatusDto and AgentEvent (DTOs)

**New files:**
- `com.arcclaw.app.ui.dto.AgentStatusDto`
- `com.arcclaw.app.ui.dto.AgentEvent`

```java
// AgentStatusDto — serialized as JSON, consumed by arc-agent-card Lit component
public record AgentStatusDto(
    String agentName,           // "Vishnu", "Saraswati", etc. — from AgentRoleEnum
    String role,                // "coordinator", "researcher", etc.
    String status,              // "idle" | "active" | "waiting_approval" | "error"
    String currentTask,         // nullable — current task description
    String autonomyLevel,       // "SUPERVISED" | "SEMI_AUTONOMOUS" | "AUTONOMOUS"
    long tokenCount             // accumulated token usage for this session
) {}

// AgentEvent — SSE payload pushed to arc-shell via /api/v1/agents/events
public record AgentEvent(
    String eventType,           // "agent-state" | "hitl-pending" | "checklist-update"
    String agentName,           // which agent changed state (nullable for hitl-pending)
    String payload              // JSON string with the event-specific data
) {}
```

---

### Component B11 — AgentStatusResource

**New file:** `com.arcclaw.app.ui.AgentStatusResource`

```java
@Path("agents")
@RunOnVirtualThread
@Produces(MediaType.APPLICATION_JSON)
public class AgentStatusResource {

    @Inject AgentStateView agentStateView;  // read-only view of current agent states
                                             // Inspect existing services — this may be
                                             // SessionRegistry, AgentLoop state, or a new
                                             // lightweight read model. Use whatever exposes
                                             // agent name + status in the codebase.

    @GET
    public List<AgentStatusDto> listAgents() {
        // Return current status of all configured agent roles.
        // If no session is active, all agents report status="idle".
        // Map AgentRoleEnum + AutonomyLevel to the DTO fields.
    }

    @GET
    @Path("events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<AgentEvent> agentEventStream() {
        // Return a Mutiny Multi that emits AgentEvent items.
        // Subscribe to CDI events: AgentStateChangedEvent, CheckListEvent, HitlPendingEvent.
        // Use Multi.createFrom().emitter(...) with a CDI observer that feeds the emitter.
        // The stream stays open until the client disconnects.
        // Emit a keepalive comment every 15 seconds to prevent proxy timeouts.
    }
}
```

**Implementation note:** If `AgentStateChangedEvent` does not yet exist as a CDI event type,
create `com.arcclaw.core.agent.event.AgentStateChangedEvent` record in `arcclaw-core`:
```java
public record AgentStateChangedEvent(String agentName, String newStatus, String currentTask) {}
```
Fire it from `AgentStateMachineRunner` at state transitions. This is the only permitted
addition to `arcclaw-core` in this phase — no logic changes.

---

### Component B12 — PendingApprovalDto and HitlResource

**New files:**
- `com.arcclaw.app.ui.dto.PendingApprovalDto`
- `com.arcclaw.app.ui.HitlResource`

```java
// PendingApprovalDto — serialized as JSON, consumed by arc-hitl-panel Lit component
public record PendingApprovalDto(
    String taskId,
    String toolName,
    Map<String, Object> params,
    Instant requestedAt,
    String agentName
) {}
```

```java
@Path("hitl")
@RunOnVirtualThread
@Produces(MediaType.APPLICATION_JSON)
public class HitlResource {

    @Inject HitlService hitlService;  // existing service in arcclaw-core

    @GET
    public List<PendingApprovalDto> listPending() {
        // Return all tasks currently suspended awaiting human approval.
        // Map HitlService.getPendingApprovals() to PendingApprovalDto.
    }

    @POST
    @Path("{taskId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response approve(@PathParam("taskId") String taskId) {
        // Call hitlService.approve(taskId).
        // Return 200 OK with {"status":"approved","taskId":"<id>"}.
        // Return 404 if taskId not found.
        // Return 409 if task already resolved.
    }

    @POST
    @Path("{taskId}/reject")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response reject(@PathParam("taskId") String taskId, String reason) {
        // Call hitlService.reject(taskId, reason).
        // Return 200 OK with {"status":"rejected","taskId":"<id>"}.
        // Return 404 if taskId not found.
        // reason may be blank — default to "Rejected by user" if blank.
    }
}
```

---

### Component B13 — ChatRequest and ChatStreamResource

**New files:**
- `com.arcclaw.app.ui.dto.ChatRequest`
- `com.arcclaw.app.ui.ChatStreamResource`

```java
// ChatRequest — JSON body for POST /api/v1/chat/stream
public record ChatRequest(
    String input,       // user message text — required, must not be blank
    String sessionId    // existing session ID, or null to create a new session
) {}
```

```java
@Path("chat")
@RunOnVirtualThread
public class ChatStreamResource {

    @Inject MessagePipeline messagePipeline;
    @Inject SessionRegistry sessionRegistry;

    @POST
    @Path("stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> streamChat(ChatRequest req) {
        // 1. Validate req.input() is not blank — emit SSE error event and complete if so.
        // 2. If req.sessionId() is null → generate a new UUID session ID.
        // 3. Build MessageContext with ChannelType.WEB and the session ID.
        // 4. Return a Multi<String> that:
        //    a. Feeds agent token output token-by-token as SSE data events.
        //    b. Emits a final SSE event {"type":"done","sessionId":"<id>"} when the
        //       agent completes its response.
        //    c. On error: emits SSE event {"type":"error","message":"<msg>"} and completes.
        // 5. The pipeline runs on the virtual thread provided by @RunOnVirtualThread.
        //    Use Multi.createFrom().emitter() and push tokens via the AgentOutputObserver.
        // NOTE: If the existing MessagePipeline does not support streaming token output,
        // run the full pipeline synchronously, then emit the complete response as a single
        // SSE data event. Add a // TODO: wire streaming token emitter comment.
    }
}
```

---

## Phase 78 Tests

**`WebReplyRendererTest`** (unit test, no CDI):
- Plain text node → exact text string.
- Code block node → fenced code block in output.
- Nested nodes → correct concatenation order.
- Empty node list → empty string.

**`WebAllowlistTest`** (unit test):
- `requireAuth=false` → `isAllowed()` returns true for any `ChannelUser`.
- `requireAuth=true` → `isAllowed()` returns false (Phase 78 stub).

**`WebSocketSessionStoreTest`** (unit test):
- Register + get → returns the registered connection.
- Register + unregister + get → `Optional.empty()`.
- `closeAll()` → calls `close()` on all registered connections (mock).
- Concurrent register/unregister from 10 threads → no `ConcurrentModificationException`.

**`WebChannelPluginDiscoveryTest`** (`@QuarkusTest`):
- Assert `ChannelPluginRegistry.find(ChannelType.WEB)` returns a non-empty `Optional`.
- Assert all 5 components are non-null via the plugin accessor methods.
- Model after `TelegramChannelRouteStartupTest.java`.

**`AgentStatusResourceTest`** (`@QuarkusTest`):
- `GET /api/v1/agents` → 200, JSON array with at least one agent (mock `AgentStateView`).
- Each element has non-null `agentName`, `role`, `status`, `autonomyLevel`.

**`HitlResourceTest`** (`@QuarkusTest`):
- `GET /api/v1/hitl` → 200, empty array when no pending approvals.
- `POST /api/v1/hitl/{id}/approve` with valid id → 200.
- `POST /api/v1/hitl/{id}/approve` with unknown id → 404.
- `POST /api/v1/hitl/{id}/reject` with blank reason → uses default message.

**`ChatStreamResourceTest`** (`@QuarkusTest`):
- `POST /api/v1/chat/stream` with blank input → SSE error event emitted.
- `POST /api/v1/chat/stream` with valid input → SSE stream opens, `done` event emitted.

---

## Phase 78 Delivery Checklist
- [ ] `arcclaw-channel-web` module created: `pom.xml`, `beans.xml`, jandex plugin
- [ ] `ChannelType.WEB` added
- [ ] All 6 Channel SPI interfaces implemented: Daemon, Sender, Renderer, Allowlist, JobNotifier, Plugin
- [ ] `WebChatEndpoint` Quarkus WebSockets Next handler routes through `MessagePipeline`
- [ ] `AgentStatusResource`, `HitlResource`, `ChatStreamResource` in `arcclaw-app`
- [ ] `AgentStateChangedEvent` CDI event record in `arcclaw-core` (additive only)
- [ ] All new tests green: `./mvnw clean test -pl arcclaw-channel-web,arcclaw-app`
- [ ] All existing tests still pass: `./mvnw clean test`
- [ ] NO static HTML/HTMX file added anywhere (frontend is Phase 79)
- [ ] CORS config in `application.yaml` allows `http://localhost:3000`

---

# PROMPT — PHASE 79: arcclaw-ui — Lit Frontend Module (Tier A)

Role: Senior Frontend Engineer + TypeScript Architect
Task: Phase 79 — Build the `arcclaw-ui` browser frontend as a standalone Maven module
using Vite 6, Lit 3, and TypeScript 5. This is the only source of browser UI for ArcClaw.
It replaces all previous references to HTMX, vanilla JS, or Qute-rendered HTML in the
browser layer. The Quarkus backend (Phases 77–78) serves only JSON + SSE + WebSocket.

**Prerequisites:**
- Phase 78 must be complete and running (`./mvnw quarkus:dev` on port 8080).
- `/api/v1/agents`, `/api/v1/hitl`, `/api/v1/chat/stream`, `/ws/chat` must be reachable.

---

## Architecture Context
Refer @arcclaw-app/src/main/resources/application.yaml (CORS origins, API base path)
Refer @Lit-4.md (component specs and design rationale)
Refer @arcclaw-core/src/main/java/com/arcclaw/core/agent/AgentRoleEnum.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/agent/AutonomyLevel.java

**Design principles (enforced throughout):**
- One SSE connection owned by `arc-shell`; all child components receive data via `@property()`.
- No `localStorage` for secrets. API key held in `@state()` on `arc-shell` (in-memory only).
- Shadow DOM on all components — no style leakage.
- Hash routing: `#/agents`, `#/hitl`, `#/skills`, `#/chat`, `#/onboarding`.
- Backend serves JSON only — frontend owns all rendering.
- No Redux, Zustand, or external state library. Lit `@state()` + Context API only.

---

## Module Structure

**New Maven module:** `arcclaw-ui`

```
arcclaw/
├── arcclaw-core/
├── arcclaw-app/         ← Quarkus backend, port 8080
├── arcclaw-ui/          ← NEW: Lit frontend, port 3000 (Vite dev server)
│   ├── pom.xml
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   ├── index.html
│   └── src/
│       ├── main.ts
│       ├── types/
│       │   ├── agent.ts
│       │   ├── hitl.ts
│       │   └── chat.ts
│       ├── api/
│       │   ├── agents.ts
│       │   ├── hitl.ts
│       │   └── chat.ts
│       └── components/
│           ├── arc-shell.ts
│           ├── arc-agent-grid.ts
│           ├── arc-agent-card.ts
│           ├── arc-hitl-panel.ts
│           ├── arc-chat.ts
│           ├── arc-skill-manager.ts
│           ├── arc-provider-switcher.ts
│           └── arc-login.ts
```

---

## Maven Module Setup

**`arcclaw-ui/pom.xml`:**
```xml
<parent>
    <groupId>com.arcclaw</groupId>
    <artifactId>arcclaw-parent</artifactId>
    <version><!-- inherit --></version>
</parent>
<artifactId>arcclaw-ui</artifactId>
<packaging>pom</packaging>  <!-- No Java sources -->

<build>
    <plugins>
        <plugin>
            <groupId>com.github.eirslett</groupId>
            <artifactId>frontend-maven-plugin</artifactId>
            <version>1.15.0</version>
            <configuration>
                <nodeVersion>v22.11.0</nodeVersion>
                <npmVersion>10.9.0</npmVersion>
                <workingDirectory>${project.basedir}</workingDirectory>
            </configuration>
            <executions>
                <execution>
                    <id>install-node-npm</id>
                    <goals><goal>install-node-and-npm</goal></goals>
                </execution>
                <execution>
                    <id>npm-install</id>
                    <goals><goal>npm</goal></goals>
                    <configuration><arguments>install</arguments></configuration>
                </execution>
                <execution>
                    <id>npm-build</id>
                    <goals><goal>npm</goal></goals>
                    <phase>package</phase>
                    <configuration><arguments>run build</arguments></configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

**Root `pom.xml`:** Add `arcclaw-ui` to `<modules>` after `arcclaw-app`.

---

## package.json

```json
{
  "name": "arcclaw-ui",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "lit": "^3.2.0"
  },
  "devDependencies": {
    "@types/node": "^22.0.0",
    "typescript": "^5.6.0",
    "vite": "^6.0.0"
  }
}
```

---

## vite.config.ts

```typescript
import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    target: 'es2022',
  },
});
```

---

## index.html

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>ArcClaw</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: system-ui, sans-serif; background: #0d1117; color: #c9d1d9; height: 100vh; }
  </style>
</head>
<body>
  <arc-shell></arc-shell>
  <script type="module" src="/src/main.ts"></script>
</body>
</html>
```

---

## src/main.ts

```typescript
// Register all custom elements — importing them registers them with the browser.
import './components/arc-shell';
import './components/arc-agent-grid';
import './components/arc-agent-card';
import './components/arc-hitl-panel';
import './components/arc-chat';
import './components/arc-skill-manager';
import './components/arc-provider-switcher';
import './components/arc-login';
```

---

## src/types/agent.ts

```typescript
export type AgentStatus = 'idle' | 'active' | 'waiting_approval' | 'error';
export type AutonomyLevel = 'SUPERVISED' | 'SEMI_AUTONOMOUS' | 'AUTONOMOUS';

export interface AgentStatusDto {
  agentName: string;
  role: string;
  status: AgentStatus;
  currentTask: string | null;
  autonomyLevel: AutonomyLevel;
  tokenCount: number;
}

export interface AgentEvent {
  eventType: 'agent-state' | 'hitl-pending' | 'checklist-update';
  agentName: string | null;
  payload: string;  // JSON string — parse based on eventType
}
```

---

## src/types/hitl.ts

```typescript
export interface PendingApprovalDto {
  taskId: string;
  toolName: string;
  params: Record<string, unknown>;
  requestedAt: string;  // ISO-8601
  agentName: string;
}
```

---

## src/types/chat.ts

```typescript
export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  timestamp: number;
}

export interface ChatRequest {
  input: string;
  sessionId: string | null;
}
```

---

## src/api/agents.ts

```typescript
export async function fetchAgents(apiKey: string): Promise<AgentStatusDto[]> {
  const res = await fetch('/api/v1/agents', {
    headers: { 'Authorization': `Bearer ${apiKey}` },
  });
  if (!res.ok) throw new Error(`agents fetch failed: ${res.status}`);
  return res.json();
}

export function openAgentEventSource(apiKey: string): EventSource {
  // EventSource does not support custom headers in the browser.
  // Pass API key as a query param: /api/v1/agents/events?token=<key>
  // The backend must accept ?token= as an alternative to Authorization header.
  // Document this requirement in a comment — backend adjustment is a follow-up.
  return new EventSource(`/api/v1/agents/events?token=${encodeURIComponent(apiKey)}`);
}
```

---

## src/api/hitl.ts

```typescript
import type { PendingApprovalDto } from '../types/hitl';

export async function fetchPendingApprovals(apiKey: string): Promise<PendingApprovalDto[]> {
  const res = await fetch('/api/v1/hitl', {
    headers: { 'Authorization': `Bearer ${apiKey}` },
  });
  if (!res.ok) throw new Error(`hitl fetch failed: ${res.status}`);
  return res.json();
}

export async function approveTask(taskId: string, apiKey: string): Promise<void> {
  const res = await fetch(`/api/v1/hitl/${taskId}/approve`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${apiKey}` },
  });
  if (!res.ok) throw new Error(`approve failed: ${res.status}`);
}

export async function rejectTask(taskId: string, reason: string, apiKey: string): Promise<void> {
  const res = await fetch(`/api/v1/hitl/${taskId}/reject`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'text/plain',
    },
    body: reason,
  });
  if (!res.ok) throw new Error(`reject failed: ${res.status}`);
}
```

---

## src/api/chat.ts

```typescript
import type { ChatRequest } from '../types/chat';

/**
 * Opens an SSE stream for a chat message.
 * Returns a ReadableStream<string> of token chunks.
 * The stream ends with a JSON chunk: {"type":"done","sessionId":"<id>"}
 */
export async function streamChat(
  req: ChatRequest,
  apiKey: string,
  onToken: (token: string) => void,
  onDone: (sessionId: string) => void,
  onError: (message: string) => void,
): Promise<void> {
  const res = await fetch('/api/v1/chat/stream', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(req),
  });

  if (!res.ok || !res.body) {
    onError(`Stream failed: ${res.status}`);
    return;
  }

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += value;

    // SSE lines start with "data: "
    const lines = buffer.split('\n');
    buffer = lines.pop() ?? '';

    for (const line of lines) {
      if (!line.startsWith('data: ')) continue;
      const data = line.slice(6).trim();
      if (!data) continue;
      try {
        const parsed = JSON.parse(data);
        if (parsed.type === 'done') {
          onDone(parsed.sessionId);
        } else if (parsed.type === 'error') {
          onError(parsed.message);
        } else {
          onToken(data);  // raw token text
        }
      } catch {
        onToken(data);  // not JSON → raw token
      }
    }
  }
}
```

---

## src/components/arc-login.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, state } from 'lit/decorators.js';

/**
 * Login prompt shown when no API key is present in arc-shell.
 * Fires a 'login' CustomEvent with detail: { apiKey: string }.
 * API key is NEVER written to localStorage or sessionStorage.
 */
@customElement('arc-login')
export class ArcLogin extends LitElement {
  @state() private _key = '';

  static styles = css`
    :host {
      display: flex; align-items: center; justify-content: center;
      height: 100vh; background: #0d1117;
    }
    .card {
      background: #161b22; border: 1px solid #30363d; border-radius: 12px;
      padding: 2rem; width: 420px; display: flex; flex-direction: column; gap: 1rem;
    }
    h1 { color: #58a6ff; font-size: 1.4rem; margin: 0; }
    p { color: #8b949e; font-size: 0.875rem; line-height: 1.5; }
    input {
      background: #0d1117; border: 1px solid #30363d; border-radius: 6px;
      color: #c9d1d9; padding: 0.5rem 0.75rem; font-size: 0.9rem; width: 100%;
    }
    button {
      background: #238636; border: none; border-radius: 6px; color: white;
      cursor: pointer; padding: 0.5rem 1rem; font-size: 0.9rem;
    }
    button:hover { background: #2ea043; }
  `;

  private _submit() {
    if (!this._key.trim()) return;
    this.dispatchEvent(new CustomEvent('login', { detail: { apiKey: this._key.trim() }, bubbles: true, composed: true }));
  }

  render() {
    return html`
      <div class="card">
        <h1>🦅 ArcClaw</h1>
        <p>Enter your ArcClaw API key to continue. The key is stored in memory only and
           cleared when you close the browser tab.</p>
        <input
          type="password"
          placeholder="arcclaw-key-..."
          .value="${this._key}"
          @input="${(e: InputEvent) => this._key = (e.target as HTMLInputElement).value}"
          @keydown="${(e: KeyboardEvent) => e.key === 'Enter' && this._submit()}"
        />
        <button @click="${this._submit}">Connect</button>
      </div>
    `;
  }
}
```

---

## src/components/arc-shell.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import type { AgentStatusDto, AgentEvent } from '../types/agent';
import type { PendingApprovalDto } from '../types/hitl';
import { fetchAgents, openAgentEventSource } from '../api/agents';
import { fetchPendingApprovals } from '../api/hitl';

type Route = 'agents' | 'hitl' | 'chat' | 'skills' | 'onboarding';

/**
 * Top-level application shell.
 * Owns: API key (in-memory), single SSE connection, routing state,
 * agent list, and pending HITL approvals.
 * Fans data down to child components via @property() bindings.
 */
@customElement('arc-shell')
export class ArcShell extends LitElement {
  @state() private _apiKey: string | null = null;
  @state() private _route: Route = 'agents';
  @state() private _agents: AgentStatusDto[] = [];
  @state() private _pendingApprovals: PendingApprovalDto[] = [];
  @state() private _sseError = false;
  private _sse: EventSource | null = null;

  static styles = css`
    :host { display: flex; height: 100vh; overflow: hidden; }
    nav {
      width: 200px; background: #161b22; border-right: 1px solid #30363d;
      display: flex; flex-direction: column; padding: 1rem 0;
    }
    nav a {
      color: #8b949e; padding: 0.6rem 1.25rem; text-decoration: none;
      font-size: 0.9rem; cursor: pointer; border-left: 3px solid transparent;
      display: flex; align-items: center; gap: 0.5rem;
    }
    nav a.active { color: #58a6ff; border-left-color: #58a6ff; background: #0d1117; }
    nav a:hover:not(.active) { background: #1c2128; color: #c9d1d9; }
    .badge {
      background: #da3633; color: white; border-radius: 999px;
      font-size: 0.7rem; padding: 0 6px; min-width: 18px; text-align: center;
    }
    main { flex: 1; overflow-y: auto; padding: 1.5rem; }
    .logo { color: #58a6ff; font-size: 1.1rem; font-weight: 600; padding: 0.5rem 1.25rem 1rem; }
    .sse-error {
      background: #da3633; color: white; font-size: 0.8rem;
      padding: 0.3rem 1rem; text-align: center;
    }
  `;

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('hashchange', this._onHashChange.bind(this));
    this._onHashChange();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._sse?.close();
    window.removeEventListener('hashchange', this._onHashChange.bind(this));
  }

  private _onHashChange() {
    const hash = window.location.hash.replace('#/', '') as Route;
    const validRoutes: Route[] = ['agents', 'hitl', 'chat', 'skills', 'onboarding'];
    this._route = validRoutes.includes(hash) ? hash : 'agents';
  }

  private async _onLogin(e: CustomEvent<{ apiKey: string }>) {
    this._apiKey = e.detail.apiKey;
    await this._loadInitialData();
    this._connectSSE();
  }

  private async _loadInitialData() {
    if (!this._apiKey) return;
    try {
      [this._agents, this._pendingApprovals] = await Promise.all([
        fetchAgents(this._apiKey),
        fetchPendingApprovals(this._apiKey),
      ]);
    } catch (err) {
      console.error('Initial data load failed:', err);
    }
  }

  private _connectSSE() {
    if (!this._apiKey) return;
    this._sse = openAgentEventSource(this._apiKey);
    this._sse.addEventListener('agent-state', (e: MessageEvent) => {
      this._handleAgentEvent(JSON.parse(e.data) as AgentEvent);
    });
    this._sse.addEventListener('hitl-pending', async () => {
      // Re-fetch full list on any HITL change — simple and reliable.
      if (this._apiKey) {
        this._pendingApprovals = await fetchPendingApprovals(this._apiKey);
      }
    });
    this._sse.onerror = () => { this._sseError = true; };
  }

  private _handleAgentEvent(event: AgentEvent) {
    this._sseError = false;
    if (event.eventType === 'agent-state' && event.agentName) {
      const payload = JSON.parse(event.payload);
      this._agents = this._agents.map(a =>
        a.agentName === event.agentName ? { ...a, ...payload } : a
      );
    }
  }

  private _navigate(route: Route) {
    window.location.hash = `#/${route}`;
    this._route = route;
  }

  render() {
    if (!this._apiKey) {
      return html`<arc-login @login="${this._onLogin}"></arc-login>`;
    }

    const hitlCount = this._pendingApprovals.length;

    return html`
      <nav>
        <div class="logo">🦅 ArcClaw
        Continuing the updated prompts document from exactly where it was cut off — inside `arc-shell.ts` render method: 

***

````markdown
        </div>
        ${this._sseError ? html`<div class="sse-error">⚠ Live updates disconnected</div>` : ''}
        <a class="${this._route === 'agents' ? 'active' : ''}" @click="${() => this._navigate('agents')}">
          🤖 Agents
        </a>
        <a class="${this._route === 'hitl' ? 'active' : ''}" @click="${() => this._navigate('hitl')}">
          ✋ Approvals
          ${hitlCount > 0 ? html`<span class="badge">${hitlCount}</span>` : ''}
        </a>
        <a class="${this._route === 'chat' ? 'active' : ''}" @click="${() => this._navigate('chat')}">
          💬 Chat
        </a>
        <a class="${this._route === 'skills' ? 'active' : ''}" @click="${() => this._navigate('skills')}">
          🛠 Skills
        </a>
        <a class="${this._route === 'onboarding' ? 'active' : ''}" @click="${() => this._navigate('onboarding')}">
          ⚙ Setup
        </a>
      </nav>
      <main>
        ${this._route === 'agents'    ? html`<arc-agent-grid .agents="${this._agents}" .apiKey="${this._apiKey}"></arc-agent-grid>` : ''}
        ${this._route === 'hitl'      ? html`<arc-hitl-panel .approvals="${this._pendingApprovals}" .apiKey="${this._apiKey}" @approval-action="${this._loadInitialData.bind(this)}"></arc-hitl-panel>` : ''}
        ${this._route === 'chat'      ? html`<arc-chat .apiKey="${this._apiKey}"></arc-chat>` : ''}
        ${this._route === 'skills'    ? html`<arc-skill-manager .apiKey="${this._apiKey}"></arc-skill-manager>` : ''}
        ${this._route === 'onboarding'? html`<arc-provider-switcher .apiKey="${this._apiKey}"></arc-provider-switcher>` : ''}
      </main>
    `;
  }
}
```

***

## src/components/arc-agent-card.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import type { AgentStatusDto, AgentStatus, AutonomyLevel } from '../types/agent';

/**
 * Displays one agent persona card.
 * Maps directly to AgentRoleEnum + AutonomyLevel in the backend.
 * Visual affordances:
 *   - SUPERVISED  → amber border, lock icon badge
 *   - SEMI_AUTONOMOUS → blue border
 *   - AUTONOMOUS  → green border, lightning badge
 */
@customElement('arc-agent-card')
export class ArcAgentCard extends LitElement {
  @property({ type: Object }) agent!: AgentStatusDto;

  static styles = css`
    :host { display: block; }
    .card {
      background: #161b22; border-radius: 10px; padding: 1rem;
      border-left: 4px solid var(--autonomy-color, #30363d);
      display: flex; flex-direction: column; gap: 0.5rem;
    }
    .header { display: flex; align-items: center; gap: 0.5rem; }
    .name { font-weight: 600; font-size: 1rem; color: #c9d1d9; }
    .role { font-size: 0.75rem; color: #8b949e; text-transform: uppercase; letter-spacing: 0.05em; }
    .status-badge {
      font-size: 0.7rem; padding: 2px 8px; border-radius: 999px;
      font-weight: 600; text-transform: uppercase;
    }
    .status-idle       { background: #21262d; color: #8b949e; }
    .status-active     { background: #1f6feb33; color: #58a6ff; }
    .status-waiting    { background: #9e6a0333; color: #d29922; }
    .status-error      { background: #da363333; color: #f85149; }
    .task { font-size: 0.8rem; color: #8b949e; font-style: italic; min-height: 1.2em; }
    .tokens { font-size: 0.75rem; color: #484f58; }
    .autonomy-icon { font-size: 0.9rem; }
  `;

  private _autonomyColor(level: AutonomyLevel): string {
    return { SUPERVISED: '#d29922', SEMI_AUTONOMOUS: '#58a6ff', AUTONOMOUS: '#3fb950' }[level] ?? '#30363d';
  }

  private _autonomyIcon(level: AutonomyLevel): string {
    return { SUPERVISED: '🔒', SEMI_AUTONOMOUS: '🔵', AUTONOMOUS: '⚡' }[level] ?? '';
  }

  private _statusClass(status: AgentStatus): string {
    return { idle: 'status-idle', active: 'status-active', waiting_approval: 'status-waiting', error: 'status-error' }[status] ?? 'status-idle';
  }

  render() {
    const { agentName, role, status, currentTask, autonomyLevel, tokenCount } = this.agent;
    return html`
      <div class="card" style="--autonomy-color: ${this._autonomyColor(autonomyLevel)}">
        <div class="header">
          <span class="name">${agentName}</span>
          <span class="autonomy-icon">${this._autonomyIcon(autonomyLevel)}</span>
          <span class="status-badge ${this._statusClass(status)}">${status.replace('_', ' ')}</span>
        </div>
        <div class="role">${role}</div>
        <div class="task">${currentTask ?? 'No active task'}</div>
        <div class="tokens">~${tokenCount.toLocaleString()} tokens</div>
      </div>
    `;
  }
}
```

***

## src/components/arc-agent-grid.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import type { AgentStatusDto } from '../types/agent';
import './arc-agent-card';

@customElement('arc-agent-grid')
export class ArcAgentGrid extends LitElement {
  @property({ type: Array }) agents: AgentStatusDto[] = [];
  @property({ type: String }) apiKey = '';

  static styles = css`
    :host { display: block; }
    h2 { color: #c9d1d9; margin-bottom: 1rem; font-size: 1.1rem; }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
      gap: 1rem;
    }
    .empty { color: #8b949e; font-style: italic; }
  `;

  render() {
    return html`
      <h2>Agent Status</h2>
      ${this.agents.length === 0
        ? html`<p class="empty">No agents running. Start a session via Chat.</p>`
        : html`
          <div class="grid">
            ${this.agents.map(a => html`<arc-agent-card .agent="${a}"></arc-agent-card>`)}
          </div>`
      }
    `;
  }
}
```

***

## src/components/arc-hitl-panel.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { PendingApprovalDto } from '../types/hitl';
import { approveTask, rejectTask } from '../api/hitl';

/**
 * Human-in-the-loop approval panel.
 * This is the most critical component in ArcClaw UI — no equivalent in JavaClaw.
 * Displayed as a persistent view under #/hitl route.
 * Fires 'approval-action' event upward after any approve/reject so arc-shell
 * can re-fetch the pending list.
 */
@customElement('arc-hitl-panel')
export class ArcHitlPanel extends LitElement {
  @property({ type: Array }) approvals: PendingApprovalDto[] = [];
  @property({ type: String }) apiKey = '';
  @state() private _processing = new Set<string>();
  @state() private _rejectReason: Record<string, string> = {};

  static styles = css`
    :host { display: block; }
    h2 { color: #c9d1d9; margin-bottom: 1rem; font-size: 1.1rem; }
    .empty { color: #8b949e; font-style: italic; }
    .card {
      background: #161b22; border: 1px solid #30363d; border-radius: 10px;
      padding: 1rem; margin-bottom: 1rem;
    }
    .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
    .tool-name { color: #f0883e; font-weight: 600; font-size: 0.95rem; }
    .agent-name { color: #8b949e; font-size: 0.8rem; }
    .requested-at { color: #484f58; font-size: 0.75rem; }
    .params {
      background: #0d1117; border-radius: 6px; padding: 0.75rem;
      font-family: monospace; font-size: 0.8rem; color: #c9d1d9;
      white-space: pre-wrap; margin-bottom: 0.75rem; max-height: 200px; overflow-y: auto;
    }
    .actions { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
    .btn-approve {
      background: #238636; border: none; border-radius: 6px; color: white;
      cursor: pointer; padding: 0.4rem 1rem; font-size: 0.85rem;
    }
    .btn-approve:hover { background: #2ea043; }
    .btn-reject {
      background: transparent; border: 1px solid #da3633; border-radius: 6px;
      color: #f85149; cursor: pointer; padding: 0.4rem 1rem; font-size: 0.85rem;
    }
    .btn-reject:hover { background: #da363322; }
    .reason-input {
      flex: 1; min-width: 180px;
      background: #0d1117; border: 1px solid #30363d; border-radius: 6px;
      color: #c9d1d9; padding: 0.4rem 0.6rem; font-size: 0.8rem;
    }
    .spinner { color: #8b949e; font-size: 0.85rem; }
  `;

  private async _approve(taskId: string) {
    this._processing = new Set(this._processing).add(taskId);
    try {
      await approveTask(taskId, this.apiKey);
      this.dispatchEvent(new CustomEvent('approval-action', { bubbles: true, composed: true }));
    } catch (e) {
      console.error('approve failed', e);
    } finally {
      const next = new Set(this._processing);
      next.delete(taskId);
      this._processing = next;
    }
  }

  private async _reject(taskId: string) {
    const reason = this._rejectReason[taskId] ?? '';
    this._processing = new Set(this._processing).add(taskId);
    try {
      await rejectTask(taskId, reason, this.apiKey);
      this.dispatchEvent(new CustomEvent('approval-action', { bubbles: true, composed: true }));
    } catch (e) {
      console.error('reject failed', e);
    } finally {
      const next = new Set(this._processing);
      next.delete(taskId);
      this._processing = next;
    }
  }

  render() {
    return html`
      <h2>Pending Approvals (${this.approvals.length})</h2>
      ${this.approvals.length === 0
        ? html`<p class="empty">No approvals waiting. Agents are running freely.</p>`
        : this.approvals.map(a => html`
          <div class="card">
            <div class="card-header">
              <span class="tool-name">🔧 ${a.toolName}</span>
              <span class="agent-name">by ${a.agentName}</span>
              <span class="requested-at">${new Date(a.requestedAt).toLocaleTimeString()}</span>
            </div>
            <pre class="params">${JSON.stringify(a.params, null, 2)}</pre>
            <div class="actions">
              ${this._processing.has(a.taskId)
                ? html`<span class="spinner">Processing…</span>`
                : html`
                  <input
                    class="reason-input"
                    type="text"
                    placeholder="Rejection reason (optional)"
                    .value="${this._rejectReason[a.taskId] ?? ''}"
                    @input="${(e: InputEvent) => {
                      this._rejectReason = { ...this._rejectReason, [a.taskId]: (e.target as HTMLInputElement).value };
                    }}"
                  />
                  <button class="btn-approve" @click="${() => this._approve(a.taskId)}">✓ Approve</button>
                  <button class="btn-reject"  @click="${() => this._reject(a.taskId)}">✗ Reject</button>
                `}
            </div>
          </div>
        `)
      }
    `;
  }
}
```

***

## src/components/arc-chat.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { ChatMessage } from '../types/chat';
import { streamChat } from '../api/chat';

/**
 * Real-time chat with the ArcClaw agent cluster.
 * Uses SSE token streaming via POST /api/v1/chat/stream.
 * Unlike JavaClaw's WebSocket approach, SSE is simpler and aligns with
 * the existing Multi<String> reactive streams in Quarkus.
 */
@customElement('arc-chat')
export class ArcChat extends LitElement {
  @property({ type: String }) apiKey = '';
  @state() private _messages: ChatMessage[] = [];
  @state() private _input = '';
  @state() private _streaming = false;
  @state() private _sessionId: string | null = null;
  @state() private _streamingText = '';

  static styles = css`
    :host { display: flex; flex-direction: column; height: calc(100vh - 6rem); }
    h2 { color: #c9d1d9; margin-bottom: 1rem; font-size: 1.1rem; flex-shrink: 0; }
    .messages {
      flex: 1; overflow-y: auto; display: flex; flex-direction: column;
      gap: 0.75rem; padding-bottom: 1rem;
    }
    .msg { max-width: 80%; padding: 0.6rem 0.9rem; border-radius: 10px; font-size: 0.9rem; line-height: 1.5; }
    .msg-user {
      align-self: flex-end; background: #1f6feb; color: white;
      border-bottom-right-radius: 2px;
    }
    .msg-assistant {
      align-self: flex-start; background: #161b22; color: #c9d1d9;
      border: 1px solid #30363d; border-bottom-left-radius: 2px;
    }
    .msg-streaming {
      align-self: flex-start; background: #161b22; color: #c9d1d9;
      border: 1px solid #58a6ff44; border-bottom-left-radius: 2px;
      font-style: italic;
    }
    .typing { display: flex; gap: 4px; padding: 0.5rem; }
    .typing span {
      width: 8px; height: 8px; background: #58a6ff; border-radius: 50%;
      animation: bounce 1s infinite;
    }
    .typing span:nth-child(2) { animation-delay: 0.2s; }
    .typing span:nth-child(3) { animation-delay: 0.4s; }
    @keyframes bounce { 0%, 60%, 100% { transform: translateY(0); } 30% { transform: translateY(-6px); } }
    .input-row {
      display: flex; gap: 0.5rem; flex-shrink: 0;
      border-top: 1px solid #30363d; padding-top: 1rem;
    }
    textarea {
      flex: 1; background: #161b22; border: 1px solid #30363d; border-radius: 8px;
      color: #c9d1d9; padding: 0.5rem 0.75rem; font-size: 0.9rem; resize: none;
      font-family: inherit; min-height: 44px; max-height: 120px;
    }
    button {
      background: #238636; border: none; border-radius: 8px; color: white;
      cursor: pointer; padding: 0.5rem 1.25rem; font-size: 0.9rem; align-self: flex-end;
    }
    button:disabled { background: #21262d; color: #484f58; cursor: not-allowed; }
    pre, code { font-family: 'Fira Code', monospace; font-size: 0.82rem; }
    pre { background: #0d1117; padding: 0.75rem; border-radius: 6px; overflow-x: auto; }
  `;

  private async _send() {
    const text = this._input.trim();
    if (!text || this._streaming) return;

    this._messages = [...this._messages, {
      id: crypto.randomUUID(), role: 'user', text, timestamp: Date.now(),
    }];
    this._input = '';
    this._streaming = true;
    this._streamingText = '';

    await streamChat(
      { input: text, sessionId: this._sessionId },
      this.apiKey,
      (token) => { this._streamingText += token; },
      (sessionId) => {
        this._sessionId = sessionId;
        this._messages = [...this._messages, {
          id: crypto.randomUUID(), role: 'assistant', text: this._streamingText, timestamp: Date.now(),
        }];
        this._streamingText = '';
        this._streaming = false;
        this._scrollBottom();
      },
      (errMsg) => {
        this._messages = [...this._messages, {
          id: crypto.randomUUID(), role: 'assistant',
          text: `⚠ Error: ${errMsg}`, timestamp: Date.now(),
        }];
        this._streaming = false;
        this._streamingText = '';
      },
    );
  }

  private _scrollBottom() {
    this.updateComplete.then(() => {
      const el = this.shadowRoot?.querySelector('.messages');
      if (el) el.scrollTop = el.scrollHeight;
    });
  }

  render() {
    return html`
      <h2>Chat</h2>
      <div class="messages">
        ${this._messages.map(m => html`
          <div class="msg ${m.role === 'user' ? 'msg-user' : 'msg-assistant'}">
            ${m.text}
          </div>
        `)}
        ${this._streaming && this._streamingText
          ? html`<div class="msg msg-streaming">${this._streamingText}▌</div>`
          : ''}
        ${this._streaming && !this._streamingText
          ? html`<div class="msg msg-assistant"><div class="typing"><span></span><span></span><span></span></div></div>`
          : ''}
      </div>
      <div class="input-row">
        <textarea
          placeholder="Ask ArcClaw anything…"
          .value="${this._input}"
          @input="${(e: InputEvent) => this._input = (e.target as HTMLTextAreaElement).value}"
          @keydown="${(e: KeyboardEvent) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this._send(); } }}"
        ></textarea>
        <button ?disabled="${this._streaming || !this._input.trim()}" @click="${this._send}">
          ${this._streaming ? '…' : 'Send'}
        </button>
      </div>
    `;
  }
}
```

***

## src/components/arc-skill-manager.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

interface Skill {
  name: string;
  description: string;
  enabled: boolean;
}

/**
 * Read-only skill viewer backed by GET /api/v1/skills.
 * Phase 79 scope: display only. Enable/disable is Phase 80+.
 */
@customElement('arc-skill-manager')
export class ArcSkillManager extends LitElement {
  @property({ type: String }) apiKey = '';
  @state() private _skills: Skill[] = [];
  @state() private _loading = true;
  @state() private _error = '';

  static styles = css`
    :host { display: block; }
    h2 { color: #c9d1d9; margin-bottom: 1rem; font-size: 1.1rem; }
    .list { display: flex; flex-direction: column; gap: 0.5rem; }
    .skill {
      background: #161b22; border: 1px solid #30363d; border-radius: 8px;
      padding: 0.75rem 1rem; display: flex; align-items: center; gap: 1rem;
    }
    .skill-name { color: #c9d1d9; font-weight: 500; flex: 1; }
    .skill-desc { color: #8b949e; font-size: 0.8rem; flex: 2; }
    .pill { font-size: 0.7rem; padding: 2px 8px; border-radius: 999px; }
    .pill-on  { background: #1f6feb33; color: #58a6ff; }
    .pill-off { background: #21262d; color: #8b949e; }
    .error { color: #f85149; font-size: 0.875rem; }
    .loading { color: #8b949e; font-style: italic; }
  `;

  async connectedCallback() {
    super.connectedCallback();
    await this._load();
  }

  private async _load() {
    this._loading = true;
    this._error = '';
    try {
      const res = await fetch('/api/v1/skills', {
        headers: { 'Authorization': `Bearer ${this.apiKey}` },
      });
      if (!res.ok) throw new Error(`${res.status}`);
      this._skills = await res.json();
    } catch (e: unknown) {
      this._error = `Failed to load skills: ${e instanceof Error ? e.message : e}`;
    } finally {
      this._loading = false;
    }
  }

  render() {
    if (this._loading) return html`<p class="loading">Loading skills…</p>`;
    if (this._error)   return html`<p class="error">${this._error}</p>`;
    return html`
      <h2>Skills (${this._skills.length})</h2>
      <div class="list">
        ${this._skills.map(s => html`
          <div class="skill">
            <span class="skill-name">${s.name}</span>
            <span class="skill-desc">${s.description}</span>
            <span class="pill ${s.enabled ? 'pill-on' : 'pill-off'}">${s.enabled ? 'enabled' : 'disabled'}</span>
          </div>
        `)}
      </div>
    `;
  }
}
```

***

## src/components/arc-provider-switcher.ts

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

interface ProviderStatus {
  providerId: string;
  modelName: string;
  active: boolean;
}

/**
 * Shows active LLM provider and model. Links to /onboarding for reconfiguration.
 * Phase 79 scope: display + navigate to onboarding. Switching live is Phase 80.
 */
@customElement('arc-provider-switcher')
export class ArcProviderSwitcher extends LitElement {
  @property({ type: String }) apiKey = '';
  @state() private _provider: ProviderStatus | null = null;

  static styles = css`
    :host { display: block; }
    h2 { color: #c9d1d9; margin-bottom: 1rem; font-size: 1.1rem; }
    .card {
      background: #161b22; border: 1px solid #30363d; border-radius: 10px;
      padding: 1.25rem; display: flex; flex-direction: column; gap: 0.75rem;
      max-width: 420px;
    }
    .provider-id { color: #58a6ff; font-weight: 600; font-size: 1rem; }
    .model-name  { color: #c9d1d9; font-size: 0.875rem; }
    .label       { color: #8b949e; font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.05em; }
    .btn {
      background: #21262d; border: 1px solid #30363d; border-radius: 6px;
      color: #c9d1d9; cursor: pointer; padding: 0.4rem 1rem; font-size: 0.85rem;
      text-align: center;
    }
    .btn:hover { background: #1c2128; }
    a { color: #58a6ff; text-decoration: none; }
  `;

  async connectedCallback() {
    super.connectedCallback();
    try {
      const res = await fetch('/api/v1/provider/active', {
        headers: { 'Authorization': `Bearer ${this.apiKey}` },
      });
      if (res.ok) this._provider = await res.json();
    } catch { /* optional endpoint — fail silently */ }
  }

  render() {
    return html`
      <h2>LLM Provider</h2>
      <div class="card">
        ${this._provider ? html`
          <div>
            <div class="label">Provider</div>
            <div class="provider-id">${this._provider.providerId}</div>
          </div>
          <div>
            <div class="label">Model</div>
            <div class="model-name">${this._provider.modelName}</div>
          </div>
        ` : html`<p style="color:#8b949e">Provider info unavailable.</p>`}
        <a href="#/onboarding">
          <div class="btn">⚙ Reconfigure via Setup Wizard →</div>
        </a>
      </div>
    `;
  }
}
```

***

## Phase 79 Tests

All tests live in `arcclaw-ui/src/__tests__/` and use Vitest + `@web/test-runner` or
`@open-wc/testing`. Add to `package.json`:

```json
"devDependencies": {
  "vitest": "^2.0.0",
  "@open-wc/testing": "^4.0.0",
  "@web/test-runner": "^0.19.0"
},
"scripts": {
  "test": "vitest run"
}
```

**`arc-agent-card.test.ts`:**
- Render with `status='idle'` → contains class `status-idle`.
- Render with `status='waiting_approval'` → contains class `status-waiting` and amber border color.
- Render with `autonomyLevel='AUTONOMOUS'` → contains ⚡ icon.
- Render with `autonomyLevel='SUPERVISED'` → contains 🔒 icon.

**`arc-hitl-panel.test.ts`:**
- Empty approvals array → renders "No approvals waiting" text.
- One approval → renders tool name, agent name, params JSON.
- Click Approve → `approveTask` API called with correct taskId.
- Click Reject with reason → `rejectTask` API called with reason string.
- Click Reject with blank reason → `rejectTask` still called (backend defaults the message).
- `approval-action` CustomEvent is fired after approve.

**`arc-login.test.ts`:**
- Renders password input and Connect button.
- Enter key in input → `login` CustomEvent fired with `apiKey` detail.
- Empty key → `login` NOT fired.

**`arc-chat.test.ts`:**
- Initial render → no messages, typing area visible.
- Enter with blank input → `streamChat` NOT called.
- Shift+Enter → does NOT submit (adds newline only).

***

## Phase 79 Delivery Checklist
- [ ] `arcclaw-ui` module added to root `pom.xml`
- [ ] `frontend-maven-plugin` configured — `mvn package` runs `npm install && npm run build`
- [ ] All 7 components implemented: `arc-shell`, `arc-agent-card`, `arc-agent-grid`, `arc-hitl-panel`, `arc-chat`, `arc-skill-manager`, `arc-provider-switcher`
- [ ] `arc-login` implemented — no localStorage usage anywhere
- [ ] `src/api/*.ts` wrappers typed against DTO records from Phase 78
- [ ] Vite proxy configured: `/api` → `http://localhost:8080`, `/ws` → `ws://localhost:8080`
- [ ] Hash routing working: `#/agents`, `#/hitl`, `#/chat`, `#/skills`, `#/onboarding`
- [ ] Single SSE connection owned by `arc-shell`, fanned out to children via `@property()`
- [ ] All component tests green: `npm test`
- [ ] `npm run build` produces `dist/` without errors
- [ ] `mvn clean package -pl arcclaw-ui` succeeds

***

# PROMPT — PHASE 80: arcclaw-onboarding — Guided Setup Wizard (Tier A)

Role: Senior Java Architect & Senior Java Developer + Frontend TypeScript Engineer
Task: Phase 80 — Implement the guided first-run onboarding wizard for ArcClaw as two
coordinated deliverables:
  (A) New Maven module `arcclaw-onboarding` — pure JSON REST API (no Qute, no HTMX).
  (B) New Lit component `arc-onboarding.ts` added to `arcclaw-ui` (Phase 79 must be done).
All secrets collected by the wizard go through `SecretVault` — never plain YAML or .env.
This is a strict improvement over JavaClaw's wizard which writes plaintext to application.yaml.

**Prerequisites:** Phase 78 (backend), Phase 79 (`arcclaw-ui` scaffold) must be complete.

***

## Architecture Context and Other References
Refer @Architecture_Invariants.md (§8 Sandbox and security rules — vault ownership)
Refer @arcclaw-core/src/main/java/com/arcclaw/core/security/SecretVault.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/security/VaultConstants.java
Refer @arcclaw-app/src/main/java/com/arcclaw/app/routing/LlmModelFactory.java
Refer @arcclaw-app/src/main/java/com/arcclaw/app/mcp/McpConnectResource.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/skill/SkillRegistry.java

***

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Every JAX-RS resource must have at least one `@QuarkusTest` integration test.
2. All secrets MUST be written to `SecretVault` — never to `application.yaml` or `.env`.
3. The wizard MUST be idempotent: re-running overwrites previous vault entries.
4. Wizard step state is stored server-side in a `@SessionScoped` CDI bean.
5. All POST endpoints return JSON (not HTML fragments — this is not HTMX).
6. All existing tests must remain green.
7. Test commands: `./mvnw clean test -pl arcclaw-onboarding`

***

## Part A — arcclaw-onboarding Maven Module

### Module Setup

**New module:** `arcclaw-onboarding`

`pom.xml`:
- Parent: `com.arcclaw:arcclaw-parent`
- Dependencies: `arcclaw-core`, Quarkus RESTEasy Reactive, Quarkus CDI.
- Do NOT include Quarkus Qute — all rendering is done by the Lit frontend.
- Include `jandex-maven-plugin` and `beans.xml` with `bean-discovery-mode="annotated"`.

**Root `pom.xml`:** Add `arcclaw-onboarding` to `<modules>`.
**`arcclaw-app/pom.xml`:** Add `arcclaw-onboarding` as a dependency.
**Package root:** `com.arcclaw.onboarding`

***

### Component C1 — OnboardingStepProvider SPI

**File:** `com.arcclaw.onboarding.spi.OnboardingStepProvider`

```java
/**
 * SPI for contributing additional steps to the onboarding wizard.
 * All @ApplicationScoped implementations are discovered via CDI Instance<T>.
 * TelegramOnboardingProvider and BraveOnboardingProvider will implement this in future phases.
 */
public interface OnboardingStepProvider {
    String stepId();           // e.g. "telegram", "brave"
    int order();               // display order after core steps 1-5
    String title();            // human-readable step title for progress bar
    boolean isConfigured();    // true if this plugin is already fully set up
}
```

No implementations in Phase 80. The interface is the deliverable.

***

### Component C2 — OnboardingSession

**File:** `com.arcclaw.onboarding.OnboardingSession`

```java
@SessionScoped
public class OnboardingSession implements Serializable {

    public enum Step {
        WELCOME(1), PROVIDER(2), CREDENTIALS(3), SYSTEM_PROMPT(4), MCP(5), COMPLETE(6);
        public final int number;
        Step(int n) { this.number = n; }
        public Step next() {
            Step[] vals = values();
            int idx = ordinal() + 1;
            return idx < vals.length ? vals[idx] : COMPLETE;
        }
        public boolean isAfter(Step other) { return this.number > other.number; }
    }

    private Step currentStep = Step.WELCOME;
    private String selectedProvider;  // "openai" | "groq" | "ollama" | "anthropic" | "claude-code"
    private String selectedModel;
    private boolean credentialsValidated = false;

    // Getters + setters for all fields.

    /**
     * Returns true only if target.number <= currentStep.number + 1.
     * Prevents URL-hacking to skip steps.
     */
    public boolean canProceedTo(Step target) {
        return target.number <= currentStep.number + 1;
    }

    public void advance() { this.currentStep = currentStep.next(); }
}
```

***

### Component C3 — OnboardingResource (JSON REST controller)

**File:** `com.arcclaw.onboarding.OnboardingResource`

Base path: `/onboarding`
All responses: `application/json`
All POST bodies: `application/json`

```
GET  /onboarding/status          → { step, stepName, provider, model, credentialsOk }
POST /onboarding/step/1          → advance to step 2; returns { nextStep: 2 }
POST /onboarding/step/2          → body: { provider, model }; save to session; returns { nextStep: 3 }
POST /onboarding/step/3          → body: { apiKey }; validate + store in vault; returns { nextStep: 4, valid: true/false }
POST /onboarding/step/4          → body: { systemPrompt }; store in vault; returns { nextStep: 5 }
POST /onboarding/step/5          → body: { mcpServers: [{name,transport,url?,...}] }; register each; returns { nextStep: 6, registered: [names] }
GET  /onboarding/complete        → { complete: true, chatUrl: "/#/chat", pluginSteps: [{stepId, title, isConfigured}] }
```

**All POST handlers MUST:**
1. Call `session.canProceedTo(targetStep)` — return HTTP 403 if false.
2. Perform the action (vault write, validation, etc.).
3. Call `session.advance()`.
4. Return JSON with `nextStep` number.

**Step 2 — Provider defaults (model pre-population):**
```java
private static final Map<String, String> DEFAULT_MODELS = Map.of(
    "groq",        "llama-3.1-8b-instant",
    "openai",      "gpt-4o-mini",
    "ollama",      "llama3.2",
    "anthropic",   "claude-haiku-4-5",
    "claude-code", "claude-sonnet-4-5"
);
```

**Step 3 — Credentials validation:**
```java
// 1. Write apiKey to vault: VaultConstants.LLM_API_KEY (or the relevant constant).
// 2. Create a test ChatModel via LlmModelFactory using session.selectedProvider + session.selectedModel.
// 3. Send a minimal test prompt: "Reply with the single word: ok"
// 4. If response contains "ok" (case-insensitive) → credentialsValidated = true, return { nextStep: 4, valid: true }.
// 5. If LLM call throws any exception → do NOT advance; return { nextStep: 3, valid: false, error: "..." }.
// 6. For claude-code provider: skip API key write (OAuth token handled by ClaudeCodeProviderFactory).
//    Advance directly after token detection succeeds.
// Timeout the test call at 15 seconds. Use a virtual thread via @RunOnVirtualThread.
```

**Step 4 — System prompt:**
```java
// Store body.systemPrompt in vault under VaultConstants.SYSTEM_PROMPT_KEY (create if missing).
// If body.systemPrompt is blank → use default: "You are ArcClaw, a helpful AI assistant."
// Return { nextStep: 5 }.
```

**Step 5 — MCP registration:**
```java
// For each item in body.mcpServers:
//   Build McpServerConfig and call mcpClientService.connect(cfg).
//   Collect server names of successfully connected servers.
//   Log failures but do not abort — partial success is OK.
// Return { nextStep: 6, registered: ["server1", "server2"] }.
// Empty mcpServers list is valid (user skips MCP setup).
```

**GET /onboarding/complete:**
```java
// Return:
// {
//   "complete": true,
//   "chatUrl": "/#/chat",
//   "provider": session.selectedProvider,
//   "model": session.selectedModel,
//   "pluginSteps": [ { stepId, title, isConfigured } for each OnboardingStepProvider ]
// }
// Discover OnboardingStepProvider implementations via @Inject Instance<OnboardingStepProvider>.
```

***

### Component C4 — OnboardingResource DTOs

**File:** `com.arcclaw.onboarding.dto.*`

```java
public record OnboardingStatusDto(
    int step,
    String stepName,
    String provider,
    String model,
    boolean credentialsOk
) {}

public record Step2Request(String provider, String model) {}
public record Step3Request(String apiKey) {}
public record Step4Request(String systemPrompt) {}

public record McpServerRequest(
    String name,
    String transport,   // "http" | "stdio"
    String url,         // nullable for stdio
    String command,     // nullable for http
    String args,        // nullable
    String env          // nullable, key=value lines
) {}
public record Step5Request(List<McpServerRequest> mcpServers) {}

public record StepResponse(int nextStep) {}
public record Step3Response(int nextStep, boolean valid, String error) {}
public record Step5Response(int nextStep, List<String> registered) {}
public record CompleteResponse(boolean complete, String chatUrl, String provider, String model, List<PluginStepDto> pluginSteps) {}
public record PluginStepDto(String stepId, String title, boolean isConfigured) {}
```

***

### Phase 80 Tests (Part A)

**`OnboardingSessionTest`** (unit test):
- Initial step is WELCOME (1).
- `canProceedTo(PROVIDER)` returns true from WELCOME.
- `canProceedTo(CREDENTIALS)` returns false from WELCOME (skip attempt).
- `advance()` moves to next step correctly.
- `advance()` from COMPLETE stays at COMPLETE.

**`OnboardingResourceTest`** (`@QuarkusTest`):
- `GET /onboarding/status` → 200, step=1.
- `POST /onboarding/step/2` before step/1 → 403.
- `POST /onboarding/step/1` → 200, nextStep=2.
- `POST /onboarding/step/2` with `{"provider":"groq","model":"llama-3.1-8b-instant"}` → 200, nextStep=3.
- `POST /onboarding/step/3` with blank apiKey for claude-code provider → handled gracefully.
- `POST /onboarding/step/5` with empty mcpServers → 200, registered=[].
- `GET /onboarding/complete` → complete=true.

***

## Part B — arc-onboarding Lit Component (in arcclaw-ui)

**New file:** `arcclaw-ui/src/components/arc-onboarding.ts`

**Also register in:** `arcclaw-ui/src/main.ts` — add `import './components/arc-onboarding';`
**Also render in:** `arc-shell.ts` — the `#/onboarding` route already renders `<arc-provider-switcher>`;
replace it with `<arc-onboarding>`.

```typescript
import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

interface OnboardingStatus {
  step: number;
  stepName: string;
  provider: string | null;
  model: string | null;
  credentialsOk: boolean;
}

const PROVIDER_DEFAULTS: Record<string, string> = {
  groq: 'llama-3.1-8b-instant',
  openai: 'gpt-4o-mini',
  ollama: 'llama3.2',
  anthropic: 'claude-haiku-4-5',
  'claude-code': 'claude-sonnet-4-5',
};

const STEP_TITLES = ['Welcome', 'Provider', 'Credentials', 'System Prompt', 'MCP', 'Complete'];

@customElement('arc-onboarding')
export class ArcOnboarding extends LitElement {
  @property({ type: String }) apiKey = '';

  @state() private _status: OnboardingStatus | null = null;
  @state() private _loading = false;
  @state() private _error = '';

  // Step 2 fields
  @state() private _provider = 'groq';
  @state() private _model = PROVIDER_DEFAULTS['groq'];

  // Step 3 fields
  @state() private _apiKeyInput = '';
  @state() private _credError = '';

  // Step 4 fields
  @state() private _systemPrompt = '';

  // Step 5 fields
  @state() private _mcpName = '';
  @state() private _mcpTransport: 'http' | 'stdio' = 'http';
  @state() private _mcpUrl = '';
  @state() private _mcpCommand = '';
  @state() private _mcpServers: Array<{ name: string; transport: string; url?: string; command?: string }> = [];

  static styles = css`
    :host { display: block; max-width: 600px; margin: 0 auto; }
    h2 { color: #c9d1d9; margin-bottom: 1.5rem; font-size: 1.1rem; }
    .progress { display: flex; gap: 0.5rem; margin-bottom: 2rem; }
    .step-dot {
      flex: 1; height: 4px; border-radius: 2px; background: #21262d;
      transition: background 0.3s;
    }
    .step-dot.done    { background: #3fb950; }
    .step-dot.current { background: #58a6ff; }
    .card {
      background: #161b22; border: 1px solid #30363d; border-radius: 12px; padding: 1.5rem;
    }
    .step-title { color: #58a6ff; font-size: 1rem; font-weight: 600; margin-bottom: 1rem; }
    label { display: block; color: #8b949e; font-size: 0.8rem; margin-bottom: 0.3rem; margin-top: 0.75rem; }
    input, select, textarea {
      width: 100%; background: #0d1117; border: 1px solid #30363d; border-radius: 6px;
      color: #c9d1d9; padding: 0.5rem 0.75rem; font-size: 0.875rem; font-family: inherit;
    }
    textarea { resize: vertical; min-height: 100px; }
    .radio-group { display: flex; flex-direction: column; gap: 0.5rem; margin-top: 0.5rem; }
    .radio-option { display: flex; align-items: center; gap: 0.5rem; cursor: pointer; color: #c9d1d9; font-size: 0.875rem; }
    .actions { display: flex; justify-content: flex-end; margin-top: 1.5rem; }
    .btn {
      background: #238636; border: none; border-radius: 6px; color: white;
      cursor: pointer; padding: 0.5rem 1.5rem; font-size: 0.9rem;
    }
    .btn:disabled { background: #21262d; color: #484f58; cursor: not-allowed; }
    .btn-secondary {
      background: transparent; border: 1px solid #30363d; border-radius: 6px;
      color: #c9d1d9; cursor: pointer; padding: 0.5rem 1rem; font-size: 0.875rem;
      margin-right: 0.5rem;
    }
    .error { color: #f85149; font-size: 0.8rem; margin-top: 0.5rem; }
    .success { color: #3fb950; font-size: 0.8rem; margin-top: 0.5rem; }
    .mcp-list { margin-top: 0.5rem; display: flex; flex-direction: column; gap: 0.3rem; }
    .mcp-item {
      background: #0d1117; border-radius: 6px; padding: 0.4rem 0.75rem;
      font-size: 0.8rem; color: #c9d1d9; display: flex; gap: 0.5rem;
    }
    .complete-box { text-align: center; padding: 1rem 0; }
    .complete-box .check { font-size: 3rem; }
    .complete-box p { color: #8b949e; margin: 0.5rem 0; }
    .complete-box a { color: #58a6ff; text-decoration: none; }
  `;

  async connectedCallback() {
    super.connectedCallback();
    await this._fetchStatus();
  }

  private async _fetchStatus() {
    try {
      const res = await fetch('/onboarding/status', {
        headers: { 'Authorization': `Bearer ${this.apiKey}` },
      });
      if (res.ok) {
        this._status = await res.json();
        if (this._status?.provider) this._provider = this._status.provider;
        if (this._status?.model)    this._model    = this._status.model;
      }
    } catch { this._error = 'Failed to load onboarding status.'; }
  }

  private async _post(url: string, body: unknown): Promise<unknown> {
    this._loading = true;
    this._error = '';
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${this.apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      const json = await res.json();
      if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`);
      await this._fetchStatus();
      return json;
    } catch (e: unknown) {
      this._error = e instanceof Error ? e.message : String(e);
      return null;
    } finally {
      this._loading = false;
    }
  }

  private _renderProgress(currentStep: number) {
    return html`
      <div class="progress">
        ${STEP_TITLES.map((_, i) => html`
          <div class="step-dot ${i + 1 < currentStep ? 'done' : i + 1 === currentStep ? 'current' : ''}"></div>
        `)}
      </div>
    `;
  }

  // ── Step renderers ─────────────────────────────────────────────────────────

  private _step1() {
    return html`
      <div class="step-title">Welcome to ArcClaw</div>
      <p style="color:#8b949e;line-height:1.6;font-size:0.9rem">
        This wizard will guide you through configuring your LLM provider, API credentials,
        system prompt, and optional MCP servers. All secrets are stored in ArcClaw's encrypted
        vault — never in plain text files.
      </p>
      <div class="actions">
        <button class="btn" @click="${() => this._post('/onboarding/step/1', {})}">
          Get Started →
        </button>
      </div>
    `;
  }

  private _step2() {
    return html`
      <div class="step-title">Choose LLM Provider</div>
      <div class="radio-group">
        ${Object.keys(PROVIDER_DEFAULTS).map(p => html`
          <label class="radio-option">
            <input type="radio" name="provider" .value="${p}"
              ?checked="${this._provider === p}"
              @change="${() => { this._provider = p; this._model = PROVIDER_DEFAULTS[p]; }}"
            />
            ${p === 'claude-code' ? `${p} (uses local OAuth — no API key needed)` : p}
          </label>
        `)}
      </div>
      <label>Model Name</label>
      <input type="text" .value="${this._model}"
        @input="${(e: InputEvent) => this._model = (e.target as HTMLInputElement).value}"
      />
      <div class="actions">
        <button class="btn" ?disabled="${this._loading}"
          @click="${() => this._post('/onboarding/step/2', { provider: this._provider, model: this._model })}">
          Next →
        </button>
      </div>
    `;
  }

  private _step3() {
    const isClaudeCode = this._provider === 'claude-code';
    return html`
      <div class="step-title">Credentials</div>
      ${isClaudeCode
        ? html`<p style="color:#8b949e;font-size:0.875rem">
            Using Claude Code OAuth. ArcClaw will read your local
            <code style="color:#c9d1d9">~/.claude/credentials.json</code> automatically.
            Click Next to validate.
          </p>`
        : html`
          <label>API Key for <strong style="color:#c9d1d9">${this._provider}</strong></label>
          <input type="password" placeholder="sk-..." .value="${this._apiKeyInput}"
            @input="${(e: InputEvent) => this._apiKeyInput = (e.target as HTMLInputElement).value}"
          />
        `}
      ${this._credError ? html`<div class="error">${this._credError}</div>` : ''}
      <div class="actions">
        <button class="btn" ?disabled="${this._loading || (!isClaudeCode && !this._apiKeyInput.trim())}"
          @click="${async () => {
            const res = await this._post('/onboarding/step/3', { apiKey: this._apiKeyInput }) as { valid: boolean; error?: string } | null;
            if (res && !res.valid) {
              this._credError = res.error ?? 'Validation failed. Check your API key.';
              await this._fetchStatus(); // stay on step 3
            } else {
              this._credError = '';
            }
          }}">
          ${this._loading ? 'Validating…' : 'Validate & Next →'}
        </button>
      </div>
    `;
  }

  private _step4() {
    return html`
      <div class="step-title">System Prompt</div>
      <p style="color:#8b949e;font-size:0.875rem;margin-bottom:0.75rem">
        Define your agent's persona and behavior. Leave blank for the default.
      </p>
      <textarea .value="${this._systemPrompt}"
        placeholder="You are ArcClaw, a helpful AI assistant…"
        @input="${(e: InputEvent) => this._systemPrompt = (e.target as HTMLTextAreaElement).value}"
      ></textarea>
      <div class="actions">
        <button class="btn" ?disabled="${this._loading}"
          @click="${() => this._post('/onboarding/step/4', { systemPrompt: this._systemPrompt })}">
          Next →
        </button>
      </div>
    `;
  }

  private _step5() {
    return html`
      <div class="step-title">MCP Servers (Optional)</div>
      <p style="color:#8b949e;font-size:0.875rem;margin-bottom:0.75rem">
        Add external Model Context Protocol servers for your agents to use. You can skip this.
      </p>
      <label>Transport</label>
      <select @change="${(e: Event) => this._mcpTransport = (e.target as HTMLSelectElement).value as 'http' | 'stdio'}">
        <option value="http">HTTP</option>
        <option value="stdio">stdio</option>
      </select>
      <label>Name</label>
      <input type="text" placeholder="my-mcp-server" .value="${this._mcpName}"
        @input="${(e: InputEvent) => this._mcpName = (e.target as HTMLInputElement).value}"
      />
      ${this._mcpTransport === 'http' ? html`
        <label>URL</label>
        <input type="text" placeholder="https://mcp.example.com" .value="${this._mcpUrl}"
          @input="${(e: InputEvent) => this._mcpUrl = (e.target as HTMLInputElement).value}"
        />
      ` : html`
        <label>Command</label>
        <input type="text" placeholder="npx mcp-server" .value="${this._mcpCommand}"
          @input="${(e: InputEvent) => this._mcpCommand = (e.target as HTMLInputElement).value}"
        />
      `}
      <button class="btn-secondary" style="margin-top:0.75rem"
        @click="${() => {
          if (!this._mcpName.trim()) return;
          this._mcpServers = [...this._mcpServers, {
            name: this._mcpName, transport: this._mcpTransport,
            url: this._mcpUrl || undefined, command: this._mcpCommand || undefined,
          }];
          this._mcpName = ''; this._mcpUrl = ''; this._mcpCommand = '';
        }}">
        + Add Server
      </button>
      <div class="mcp-list">
        ${this._mcpServers.map(s => html`<div class="mcp-item">🔌 ${s.name} (${s.transport})</div>`)}
      </div>
      <div class="actions">
        <button class="btn-secondary" @click="${() => this._post('/onboarding/step/5', { mcpServers: [] })}">
          Skip
        </button>
        <button class="btn" ?disabled="${this._loading}"
          @click="${() => this._post('/onboarding/step/5', { mcpServers: this._mcpServers })}">
          ${this._mcpServers.length > 0 ? 'Register & Finish' : 'Finish'}
        </button>
      </div>
    `;
  }

  private _stepComplete() {
    return html`
      <div class="complete-box">
        <div class="check">✅</div>
        <h3 style="color:#3fb950;margin:0.75rem 0">ArcClaw is Ready!</h3>
        <p>Provider: <strong style="color:#c9d1d9">${this._status?.provider}</strong>
           &nbsp;·&nbsp; Model: <strong style="color:#c9d1d9">${this._status?.model}</strong></p>
        <p style="margin-top:1rem">
          <a href="#/chat">→ Start chatting</a>
          &nbsp;|&nbsp;
          <a href="#/agents">→ View agents</a>
        </p>
      </div>
    `;
  }

  render() {
    if (!this._status) return html`<p style="color:#8b949e">Loading…</p>`;
    const step = this._status.step;

    return html`
      <h2>Setup Wizard</h2>
      <div class="card">
        ${this._renderProgress(step)}
        ${this._error ? html`<div class="error">${this._error}</div>` : ''}
        ${step === 1 ? this._step1() : ''}
        ${step === 2 ? this._step2() : ''}
        ${step === 3 ? this._step3() : ''}
        ${step === 4 ? this._step4() : ''}
        ${step === 5 ? this._step5() : ''}
        ${step === 6 ? this._stepComplete() : ''}
      </div>
    `;
  }
}
```

***

## Phase 80 Delivery Checklist
- [ ] `arcclaw-onboarding` module created: `pom.xml`, `beans.xml`, jandex plugin, no Qute
- [ ] `OnboardingStepProvider` SPI interface created
- [ ] `OnboardingSession` `@SessionScoped` bean with `canProceedTo()` + `advance()`
- [ ] `OnboardingResource` at `/onboarding` — all 7 endpoints return JSON
- [ ] Step 3 validates credentials via LLM test call with 15s timeout
- [ ] Step 5 registers MCP servers via `McpClientService` — partial failure is non-fatal
- [ ] All secrets written to `SecretVault` — zero writes to `.env` or `application.yaml`
- [ ] `arc-onboarding.ts` Lit component added to `arcclaw-ui`, registered in `main.ts`
- [ ] `arc-shell.ts` `#/onboarding` route updated to render `<arc-onboarding>`
- [ ] All `@QuarkusTest` integration tests green: `./mvnw clean test -pl arcclaw-onboarding`
- [ ] `npm test` still green in `arcclaw-ui`
- [ ] All existing tests still pass: `./mvnw clean test`

***

# PROMPT — PHASE 81: arcclaw-tool-playwright — Browser Tool (Tier B)

Role: Senior Java Architect & Senior Java Developer
Task: Phase 81 — Add a Playwright browser tool to ArcClaw as a new Maven module
`arcclaw-tool-playwright`. Ported from JavaClaw's `PlaywrightBrowserTool` plugin but
adapted to ArcClaw's `AgentTool` interface, `ToolCapability` enum, and sandbox model.
When `arcclaw.tools.playwright.enabled` is false (default), the tool is excluded from
`ToolRegistry` via `isEnabled()` — no Playwright process is started.

**Prerequisite:** Phase 77 (Phase 77 added `AgentTool.isEnabled()` default method).

***

## Architecture Context and Other References
Refer @Architecture_Invariants.md (§9 Sandbox rules — requiresSandbox usage)
Refer @arcclaw-core/src/main/java/com/arcclaw/core/tools/AgentTool.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/tools/ToolCapability.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/tools/BraveWebSearchTool.java (isEnabled pattern)
Refer @arcclaw-app/src/main/java/com/arcclaw/app/security/AuditService.java

***

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Every new public class must have at least one test.
2. No changes to `arcclaw-core` beyond adding `BROWSER_NAVIGATE` to `ToolCapability`.
3. All existing tests must remain green.
4. Playwright processes MUST NOT start if `isEnabled()` returns false.
5. Test commands: `./mvnw clean test -pl arcclaw-tool-playwright`

***

### Module Setup

**New module:** `arcclaw-tool-playwright`

`pom.xml`:
- Parent: `com.arcclaw:arcclaw-parent`
- Dependencies: `arcclaw-core`, `arcclaw-app`, Quarkus CDI,
  `com.microsoft.playwright:playwright:1.49.0` (official Maven artifact).
- Include `jandex-maven-plugin` and `beans.xml` with `bean-discovery-mode="annotated"`.
- The Playwright dependency downloads browser binaries on first use via `Playwright.create()`.
  Document this in a `README.md` in the module root.

**Root `pom.xml`:** Add `arcclaw-tool-playwright` to `<modules>`.
**`arcclaw-app/pom.xml`:** Add `arcclaw-tool-playwright` as a dependency.
**Package root:** `com.arcclaw.tool.playwright`

**`ToolCapability.java` modification** (one line addition):
- Add `BROWSER_NAVIGATE("browser_navigate")`

***

### Component D1 — PlaywrightBrowserTool

**File:** `com.arcclaw.tool.playwright.PlaywrightBrowserTool`

```java
@ApplicationScoped
public class PlaywrightBrowserTool implements AgentTool {

    @ConfigProperty(name = "arcclaw.tools.playwright.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "arcclaw.tools.playwright.headless", defaultValue = "true")
    boolean headless;

    @ConfigProperty(name = "arcclaw.tools.playwright.timeout-ms", defaultValue = "30000")
    int timeoutMs;

    @Inject AuditService auditService;

    @Override public String name()          { return ToolCapability.BROWSER_NAVIGATE.toolName(); }
    @Override public boolean requiresSandbox() { return true; }  // run inside Podman when sandbox is active
    @Override public ToolOperation requiredOperation() { return ToolOperation.NETWORK; }
    @Override public boolean requiresManualApproval(Map<String, Object> params) { return false; }
    @Override public boolean isEnabled()    { return enabled; }

    @Override
    public String description() {
        return "Navigates to a URL using a real headless browser (Playwright) and returns " +
               "the visible page text content. Use for reading web pages that require " +
               "JavaScript rendering or are not accessible via Brave Search snippets.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        // Required: "url" (string)
        // Optional: "action" (enum: "navigate" | "extract_text" | "screenshot")
        //   - navigate: load the URL and return the full page text
        //   - extract_text (default): same as navigate but strips boilerplate (nav, footer)
        //   - screenshot: return base64-encoded PNG of the page (max 1MB)
        // Optional: "selector" (string, CSS selector) — return only text within that element
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        // 1. Guard: if !enabled → return ToolResponse.failure(503, "Playwright tool not enabled").
        // 2. Validate userId (403 if blank).
        // 3. Extract "url" param — return failure(400) if blank.
        // 4. Validate URL is http or https — reject file://, javascript:, etc.
        // 5. Extract "action" (default: "extract_text"), "selector" (default: null).
        // 6. Execute via PlaywrightPageRunner.run(url, action, selector, headless, timeoutMs).
        // 7. Audit: SecurityEventType.TOOL_EXECUTED with url as detail (NOT full content).
        // 8. Return ToolResponse.success(result).
        // On any Playwright exception: return ToolResponse.failure(502, message) — never propagate.
        // Content truncation: if result > 8192 chars, truncate at last sentence boundary
        //   and append "\n[content truncated]".
    }
}
```

***

### Component D2 — PlaywrightPageRunner

**File:** `com.arcclaw.tool.playwright.PlaywrightPageRunner`

This class isolates all Playwright API calls, making `PlaywrightBrowserTool` unit-testable
by mocking `PlaywrightPageRunner`.

```java
@ApplicationScoped
public class PlaywrightPageRunner {

    private static final Pattern SAFE_URL_PATTERN =
        Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    /**
     * Launches a browser, navigates to url, returns result based on action.
     * Each call creates and destroys a fresh browser context — no shared state.
     *
     * @param url       the target URL (must match SAFE_URL_PATTERN)
     * @param action    "navigate" | "extract_text" | "screenshot"
     * @param selector  optional CSS selector to scope extraction
     * @param headless  whether to run headless
     * @param timeoutMs navigation timeout in milliseconds
     * @return result string (page text or base64 PNG)
     */
    public String run(String url, String action, String selector,
                      boolean headless, int timeoutMs) {
        // Use try-with-resources:
        // try (Playwright playwright = Playwright.create()) {
        //   Browser browser = playwright.chromium().launch(
        //       new BrowserType.LaunchOptions().setHeadless(headless));
        //   BrowserContext context = browser.newContext();
        //   Page page = context.newPage();
        //   page.setDefaultNavigationTimeout(timeoutMs);
        //   page.navigate(url);
        //   page.waitForLoadState(LoadState.NETWORKIDLE);
        //
        //   switch (action) {
        //     case "screenshot":
        //       byte[] png = page.screenshot();
        //       return Base64.getEncoder().encodeToString(png);
        //     case "extract_text":
        //       // Use page.locator(selector ?? "body").innerText()
        //       // Strip consecutive blank lines (collapse >2 newlines to 2)
        //     case "navigate":
        //     default:
        //       return selector != null
        //           ? page.locator(selector).innerText()
        //           : page.content();   // full HTML
        //   }
        // }
        // Collapse >2 consecutive newlines to 2 in any text result.
        // MUST NOT log page content.
    }
}
```

***

### Component D3 — application.yaml additions

```yaml
arcclaw:
  tools:
    playwright:
      enabled: false        # Set true to enable browser_navigate tool
      headless: true        # Set false for visible browser (debug only)
      timeout-ms: 30000     # Navigation timeout in milliseconds
```

Also add to `.env.example`:
```
# ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED=true   # Enables browser_navigate tool (requires Playwright install)
```

***

### Phase 81 Tests

**`PlaywrightBrowserToolTest`** (unit test, mock `PlaywrightPageRunner`):
- `enabled=false` → `isEnabled()` returns false, no browser launched.
- Blank URL → `ToolResponse.failure(400, ...)`.
- URL with `javascript:` scheme → rejected with 400.
- URL with `file://` scheme → rejected with 400.
- Valid URL, action=extract_text → `PlaywrightPageRunner.run()` called with correct args.
- `PlaywrightPageRunner.run()` throws → `ToolResponse.failure(502, ...)` returned.
- Result > 8192 chars → response is truncated and ends with `[content truncated]`.
- Blank userId → 403 failure.
Mock `PlaywrightPageRunner` with Mockito. Do NOT launch a real browser in unit tests.

**`PlaywrightPageRunnerIntegrationTest`** (integration test, `@Tag("integration")`):
- Mark with `@Tag("integration")` — excluded from normal `mvn test`, runs only with
  `mvn test -Dgroups=integration`.
- Test: navigate to `https://example.com` → result contains "Example Domain".
- Test: extract_text with selector `h1` → result contains "Example Domain".
Skip if `ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED` env var is not set to `true`.

***

## Phase 81 Delivery Checklist
- [ ] `arcclaw-tool-playwright` module created: `pom.xml`, `beans.xml`, jandex plugin
- [ ] `ToolCapability.BROWSER_NAVIGATE` added
- [ ] `PlaywrightBrowserTool` implements `AgentTool` — `isEnabled()` gates all execution
- [ ] `PlaywrightPageRunner` isolates Playwright API calls — mockable in tests
- [ ] `requiresSandbox = true` set — Podman isolation honoured when sandbox is active
- [ ] URL scheme validation: only `http://` and `https://` accepted
- [ ] Content truncated at 8192 chars with `[content truncated]` suffix
- [ ] `application.yaml` and `.env.example` updated with opt-in config
- [ ] Unit tests green without launching a real browser: `./mvnw clean test -pl arcclaw-tool-playwright`
- [ ] Integration test tagged `@Tag("integration")` and skipped in normal CI
- [ ] All existing tests still pass: `./mvnw clean test`
- [ ] `README.md` in module root documents Playwright binary download on first run

***

# Global Delivery Checklist (All Phases)

Run in this order. Each phase's tests must be green before starting the next.

```
Phase 77: ./mvnw clean test -pl arcclaw-core,arcclaw-app,arcclaw-mcp
Phase 78: ./mvnw clean test -pl arcclaw-channel-web,arcclaw-app
Phase 79: cd arcclaw-ui && npm test && npm run build
Phase 80: ./mvnw clean test -pl arcclaw-onboarding && cd arcclaw-ui && npm test
Phase 81: ./mvnw clean test -pl arcclaw-tool-playwright

Full suite:  ./mvnw clean test
Full build:  ./mvnw clean package
```

## Module Dependency Graph (after all phases)

```
arcclaw-parent
├── arcclaw-channel-api      (ChannelType.WEB added)
├── arcclaw-core             (AgentTool.isEnabled, AgentStateChangedEvent, A1-A5 tools)
├── arcclaw-mcp              (McpSelfRegisterTool)
├── arcclaw-rag
├── arcclaw-channel-telegram
├── arcclaw-channel-web      ← NEW (Phase 78A)
├── arcclaw-onboarding       ← NEW (Phase 80A)
├── arcclaw-tool-playwright  ← NEW (Phase 81)
├── arcclaw-app              (depends on all above; AgentStatusResource, HitlResource, ChatStreamResource)
└── arcclaw-ui               ← NEW (Phase 79, Vite+Lit, no Java)
```

## What Is NOT Ported From JavaClaw

| JavaClaw pattern | Reason skipped | ArcClaw equivalent |
|---|---|---|
| `FileSystemTaskRepository` | ArcClaw SQLite is superior | `StatefulAgentStore` |
| `FileSystemChatMemoryRepository` | ArcClaw compaction model is better | `PersistentChatMemoryStore` |
| `JobRunr` scheduler | ArcClaw cron integrates with HITL | `AgentCronScheduler` |
| `ConfigurationManager` (write YAML) | Insecure — plaintext secrets | `SecretVault` |
| `ChannelRegistry.getLatestChannel()` | Architecturally fragile | `ChannelPluginRegistry.find(type)` |
| Qute templates in browser layer | Replaced by Lit SPA | `arcclaw-ui` components |
| HTMX CDN fragments | Replaced by SSE + JSON API | `AgentStatusResource`, SSE streams |
````

---

## Summary of Every Key Change Made

**Regarding the JavaClaw source code question** — you do not need to share it. The gap analysis file (`JavaClaw_vs_ArcClaw_Gap_Analysis-2.md`) already captured every integration contract, file to port, and adaptation strategy with enough precision to generate production-quality prompts. [file:2]

**What changed from the original prompts:** [file:3]

- **Phase 77** — Kept verbatim. All 5 Java backend capabilities unchanged [file:3]
- **Phase 78** — Stripped the HTMX `index.html` requirement entirely (Component B10 in original is deleted). Added three new backend REST resources (`AgentStatusResource`, `HitlResource`, `ChatStreamResource`) that the Lit frontend needs. Also upgraded to Quarkus WebSockets Next instead of legacy WebSockets [file:4]
- **Phase 79** — Completely replaced: instead of Qute+HTMX onboarding, this is now the full `arcclaw-ui` Vite 6 + Lit 3 + TypeScript module with all 7 components, typed API wrappers, hash routing, and the single SSE fan-out architecture described in `Lit-4.md` [file:4]
- **Phase 80** — New phase: `arcclaw-onboarding` backend now returns JSON (no Qute), paired with `arc-onboarding.ts` Lit component. Credentials still go through `SecretVault`, `@SessionScoped` step-guard still enforced
- **Phase 81** — New phase: Playwright browser tool, opt-in via config, `requiresSandbox=true`, URL scheme validation, content truncation