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
    @Inject ArcClawInfo arcClawInfo;          // version
    @Inject ToolRegistry toolRegistry;         // list active tool names

    @Override
    public String enrich() {
        // Return a markdown block that the SystemPromptBuilder will inject.
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
- Use a real CDI test (Quarkus `@QuarkusTest`) or a pure unit test with a mock `ToolRegistry`.

---

### Component A2 — CheckListTool

**Module:** `arcclaw-core`
**Package:** `com.arcclaw.core.tools`
**New files:**
- `CheckListTool.java`
- `CheckListEvent.java` (CDI event payload)
**Modified files:**
- `ToolCapability.java` — add `MANAGE_CHECKLIST("check_list")`

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
        // Read the JSON file, extract the access_token (or bearer token) field.
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
    @Inject SecretVault secretVault;            // store token on first use

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
        // 2. Validate "name" matches SAFE_NAME_PATTERN — return failure if not.
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
- `application.yaml` — add commented `arcclaw.tools.brave.api-key: ""` with instructions
- `.env.example` — add `# ARCCLAW_BRAVE_API_KEY=your_key  # Enables web_search tool`

**What to build:**
Gives the Researcher (and other) agent roles real web search via the Brave Search API.
When `ARCCLAW_BRAVE_API_KEY` is not set, the tool self-reports as unconfigured and is excluded
from `ToolRegistry` discovery automatically (see rule below).

**Contract:**
```java
@ApplicationScoped
public class BraveWebSearchTool implements AgentTool {

    private static final String BRAVE_SEARCH_URL =
        "https://api.search.brave.com/res/v1/web/search";

    @ConfigProperty(name = "arcclaw.tools.brave.api-key", defaultValue = "")
    String apiKey;

    @Inject AuditService auditService;

    // CDI producer guard: tool is only registered when apiKey is non-blank.
    // Use @Produces + @ApplicationScoped with Instance<BraveWebSearchTool> in ToolRegistry,
    // OR implement a isEnabled() default method on AgentTool (see note below).

    @Override public String name() { return ToolCapability.WEB_SEARCH.toolName(); }
    @Override public boolean requiresSandbox() { return false; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.NETWORK; }
    @Override public boolean requiresManualApproval(Map<String, Object> params) { return false; }

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
        // 3. Build GET request to BRAVE_SEARCH_URL with:
        //    - Header: X-Subscription-Token: {apiKey}
        //    - Query param: q={encoded query}, count={count}
        // 4. Use java.net.http.HttpClient (already JDK, no new dep) with 10s timeout.
        // 5. Parse JSON response: extract web.results[].{title, url, description}.
        // 6. Format as a numbered markdown list: "1. **Title**\n   URL\n   Snippet\n"
        // 7. Audit: SecurityEventType.TOOL_EXECUTED with query (NOT results) as detail.
        // 8. Return ToolResponse.success(formattedResults).
        // On any HTTP error or JSON parse failure: return ToolResponse.failure(502, message).
        // MUST NOT log the apiKey at any level.
    }
}
```

**Note on tool guard:** If `AgentTool` does not have an `isEnabled()` default method yet,
add one:
```java
// In AgentTool.java (default method — no breaking change to existing implementors)
default boolean isEnabled() { return true; }
```
Then in `ToolRegistry`, filter `isEnabled()` during registration scan. `BraveWebSearchTool`
overrides `isEnabled()` to return `!apiKey.isBlank()`.

**Rules:**
- The API key MUST NOT appear in any log line, exception message, or `ToolResponse` output.
- Response truncation: if the formatted result string exceeds 4096 characters, truncate
  at the last complete result entry and append `\n[results truncated]`.
- Timeout: use `Duration.ofSeconds(10)` — never block indefinitely.
- `count` parameter: clamp to range [1, 20] — do not trust LLM-supplied values.

**Test:** `BraveWebSearchToolTest` in `arcclaw-core`
- Test: blank apiKey → `ToolResponse` failure with 503, no HTTP call made.
- Test: blank userId → 403 failure.
- Test: `count` > 20 → clamped to 20 in the outbound request.
- Test: HTTP 401 from Brave → failure response, no exception propagated.
- Test: valid mock response with 3 results → formatted string contains all 3 titles.
Use `WireMock` (if already in test classpath) or a `MockWebServer` / `HttpServer` to mock
the Brave endpoint. Do NOT make real network calls in tests.

---

## Delivery Checklist
- [ ] A1: `SystemEnvironmentContextEnricher` + test green
- [ ] A2: `CheckListTool` + `CheckListEvent` + `ToolCapability.MANAGE_CHECKLIST` + tests green
- [ ] A3: `ClaudeCodeOAuthTokenExtractor` + `ClaudeCodeProviderFactory` + tests green
- [ ] A4: `McpSelfRegisterTool` + `ToolCapability.REGISTER_MCP_SERVER` + tests green
- [ ] A5: `BraveWebSearchTool` + `AgentTool.isEnabled()` default + `ToolCapability.WEB_SEARCH` + tests green
- [ ] All existing tests still pass: `./mvnw clean test`
- [ ] No new Maven dependencies introduced
- [ ] No modifications to `AgentLoop`, `AgentStateMachineRunner`, `ToolInvoker`, or `ToolRegistry` core logic
```

---

```
# PROMPT — PHASE 78: Browser Chat UI — arcclaw-channel-web (Tier A)

Role: Senior Java Architect & Senior Java Developer
Task: Phase 78 — Implement a browser-based real-time chat interface for ArcClaw by creating
a new Maven module `arcclaw-channel-web` that fully implements the existing 6-interface
Channel SPI. Zero changes to arcclaw-core. The web channel participates in the same
MessagePipeline as Telegram.

---

## Architecture Context and Other References
Refer @Architecture_Invariants.md (§1.4 Channel abstraction, §4.5 Channel plugin contract)
Refer @CHANNEL_SPI.md  (the 6-interface contract and step-by-step guide)
Refer @arcclaw-channel-telegram/ (the Telegram module is the reference implementation)
Refer @arcclaw-channel-api/src/main/java/com/arcclaw/channel/api/ (all SPI interfaces)
Refer @arcclaw-app/src/main/java/com/arcclaw/app/session/SessionRegistry.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/pipeline/MessagePipeline.java

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Every new public class must have at least one test.
2. No changes to `arcclaw-core`, `arcclaw-channel-api`, or any existing module's source.
   The ONLY permitted change to existing files is:
   a. Root `pom.xml` — add `arcclaw-channel-web` to `<modules>`.
   b. `ChannelType.java` — add `WEB` enum constant.
   c. `arcclaw-app/pom.xml` — add `arcclaw-channel-web` as a dependency.
   d. `application.yaml` — add `arcclaw.channel.web.*` config block (see below).
3. All existing tests must remain green.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. The static frontend (`index.html`) must be self-contained — no npm build, no bundler.
   Pure HTML + vanilla JS + HTMX 2.x loaded from CDN.
6. WebSocket sessions are scoped to a single browser tab. Multiple tabs = multiple sessions.
7. Test commands: `./mvnw clean test -pl arcclaw-channel-web`

---

### Module Setup

**New module:** `arcclaw-channel-web`

`pom.xml` must declare:
- Parent: `com.arcclaw:arcclaw-parent`
- Dependencies: `arcclaw-channel-api`, `arcclaw-core`, Quarkus WebSockets Next (or
  `quarkus-websockets` — use whichever is already in the BOM), Quarkus Qute (for
  optional server-side fragments), standard Quarkus CDI.
- Include `jandex-maven-plugin` (mandatory per Architecture Invariant §15).
- Include `src/main/resources/META-INF/beans.xml` with `bean-discovery-mode="annotated"`.

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

    @Override
    public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public boolean isConfigured() { return enabled; }

    @Override
    public void start() {
        // The WebSocket endpoint is registered by Quarkus automatically via @ServerEndpoint.
        // This method is a no-op — just log startup confirmation.
        // Do NOT start any background threads here.
        log.infof("Web channel daemon started — WebSocket endpoint active.");
    }

    @Override
    public void stop() {
        // Close all open WebSocket sessions via WebSocketSessionStore.closeAll().
        // Log the count of sessions closed.
    }
}
```

---

### Component B3 — WebSocketSessionStore

**File:** `com.arcclaw.channel.web.WebSocketSessionStore`

In-memory registry mapping `sessionId → javax.websocket.Session` (or Quarkus WebSocket
session type — use whatever the WS dependency provides).

```java
@ApplicationScoped
public class WebSocketSessionStore {

    // ConcurrentHashMap<String sessionId, <WebSocket session type>>
    // Thread-safe — concurrent connects/disconnects are expected.

    void register(String sessionId, <WsSession> session);
    void unregister(String sessionId);
    Optional<<WsSession>> get(String sessionId);
    Collection<<WsSession>> all();            // used by stop() and broadcast
    void closeAll();                          // called by WebChannelDaemon.stop()
}
```

`sessionId` is assigned on WebSocket handshake (see B4). It is stored in the WebSocket
session's user properties map under key `"arcclawSessionId"`.

---

### Component B4 — WebChatEndpoint (WebSocket server)

**File:** `com.arcclaw.channel.web.WebChatEndpoint`

This class is the WebSocket `@ServerEndpoint("/ws/chat")`.

**Lifecycle:**

`@OnOpen`:
1. Generate a new `sessionId = UUID.randomUUID().toString()`.
2. Store `sessionId` in the WS session's user properties.
3. Register in `WebSocketSessionStore`.
4. Send a JSON handshake message: `{"type":"connected","sessionId":"<id>"}`.

`@OnMessage(String message)`:
1. Parse JSON: `{"type":"message","text":"<user text>"}`.
2. If `type != "message"` → ignore silently.
3. Build `ChannelMessage` (use `ChannelMessage.CliMessage` or add a new
   `ChannelMessage.WebMessage` record if needed — see note below).
4. Build `ChannelUser` with `userId = sessionId`, `channelType = ChannelType.WEB`.
5. Build `MessageContext` with the above.
6. Inject `MessagePipeline` and call `messagePipeline.handle(context)` on a virtual thread
   (use `Thread.ofVirtual().start(...)`) so the WS handler thread is not blocked.

`@OnClose`:
1. Unregister from `WebSocketSessionStore`.
2. Call `WorkspaceManager.terminateSession(sessionId)`.

`@OnError`:
1. Log the error.
2. Unregister from `WebSocketSessionStore` (session is dead).

**Note on ChannelMessage:**
If `ChannelMessage` is a sealed interface with specific permitted types (inspect the file),
add a `WebMessage` record as a new permitted type. If adding permitted types to a sealed
interface requires touching `arcclaw-channel-api`, then instead re-use `CliMessage`
(substituting `ChannelType.WEB`) — this is acceptable as a pragmatic first-cut and must
be noted in a `// TODO: add WebMessage sealed type` comment.

---

### Component B5 — WebChannelSender

**File:** `com.arcclaw.channel.web.WebChannelSender`

Implements `ChannelSender`. Converts a `List<ReplyNode>` to a JSON string and pushes it
to the correct WebSocket session.

```java
@ApplicationScoped
public class WebChannelSender implements ChannelSender {

    @Inject WebSocketSessionStore sessionStore;
    @Inject WebReplyRenderer renderer;

    @Override
    public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public void send(String platformChatId, List<ReplyNode> nodes) {
        // platformChatId == sessionId (see WebAllowlist / WebJobNotifier for the mapping)
        String rendered = renderer.render(nodes);
        String payload = Json.createObjectBuilder()
            .add("type", "message")
            .add("role", "assistant")
            .add("text", rendered)
            .build().toString();

        sessionStore.get(platformChatId)
            .ifPresentOrElse(
                ws -> sendText(ws, payload),
                () -> log.warnf("WebChannelSender: no WS session for id=%s", platformChatId)
            );
    }

    private void sendText(<WsSession> ws, String text) {
        // Send synchronously. Catch all exceptions and log — never propagate to caller.
        // If the session is closed, call sessionStore.unregister(id).
    }
}
```

---

### Component B6 — WebReplyRenderer

**File:** `com.arcclaw.channel.web.WebReplyRenderer`

Implements `ReplyRenderer`. Converts a `ReplyNode` tree to plain markdown text (same
content the Telegram renderer produces, without Telegram's MarkdownV2 escape rules).

```java
@ApplicationScoped
public class WebReplyRenderer implements ReplyRenderer {

    @Override
    public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public String render(List<ReplyNode> nodes) {
        // Walk the ReplyNode tree using the same pattern as TelegramMarkdownV2Renderer
        // but emit plain CommonMark markdown (no Telegram-specific escaping).
        // Text nodes → text as-is.
        // Code blocks → ```lang\n...\n```
        // Lists → standard markdown bullet/numbered list.
        // Return a single concatenated String.
    }
}
```

Study `TelegramMarkdownV2Renderer.java` for the `ReplyNode` traversal pattern.
Do NOT copy the Telegram-specific escape logic.

---

### Component B7 — WebAllowlist

**File:** `com.arcclaw.channel.web.WebAllowlist`

Implements `ChannelAllowlist`.

```java
@ApplicationScoped
public class WebAllowlist implements ChannelAllowlist {

    @ConfigProperty(name = "arcclaw.channel.web.require-auth", defaultValue = "false")
    boolean requireAuth;

    @ConfigProperty(name = "arcclaw.channel.web.allowed-origins", defaultValue = "")
    String allowedOrigins; // comma-separated, empty means all

    @Override
    public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public boolean isAllowed(ChannelUser user) {
        // When requireAuth=false (default for local dev) → always true.
        // When requireAuth=true → check user.userId() against a configured allowlist.
        // For Phase 78, requireAuth=false is the only tested path.
        // Return true for requireAuth=false.
    }
}
```

---

### Component B8 — WebJobNotifier

**File:** `com.arcclaw.channel.web.WebJobNotifier`

Implements `ChannelJobNotifier`. Sends cron-job completion notifications back to the
browser session that triggered the cron.

```java
@ApplicationScoped
public class WebJobNotifier implements ChannelJobNotifier {

    @Inject WebChannelSender sender;

    @Override
    public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public void notifyJobCompleted(String platformChatId, String renderedAnswer, String correlationId) {
        // Build a single TextNode with the renderedAnswer.
        // Call sender.send(platformChatId, List.of(textNode)).
        // If the session is gone (sender logs a warn), this is a no-op — cron notifications
        // can be lost if the browser tab is closed. This is acceptable for Phase 78.
    }
}
```

---

### Component B9 — WebChannelPlugin

**File:** `com.arcclaw.channel.web.WebChannelPlugin`

Composite registration bean — wires all 5 components as required by Channel SPI §4.5.

```java
@ApplicationScoped
public class WebChannelPlugin implements ChannelPlugin {

    @Inject WebChannelSender sender;
    @Inject WebReplyRenderer renderer;
    @Inject WebAllowlist allowlist;
    @Inject WebJobNotifier jobNotifier;
    @Inject WebChannelDaemon daemon;

    @Override public ChannelType channelType() { return ChannelType.WEB; }
    @Override public ChannelSender sender()           { return sender; }
    @Override public ReplyRenderer renderer()         { return renderer; }
    @Override public ChannelAllowlist allowlist()      { return allowlist; }
    @Override public ChannelJobNotifier jobNotifier()  { return jobNotifier; }
    @Override public ChannelDaemon daemon()            { return daemon; }
}
```

---

### Component B10 — Static Frontend (index.html)

**File:** `src/main/resources/META-INF/resources/index.html`

A self-contained single-file chat UI. No build step. No npm.

**Requirements:**
- Load HTMX 2.x from CDN: `https://unpkg.com/htmx.org@2/dist/htmx.min.js`
- Load HTMX WebSocket extension: `https://unpkg.com/htmx-ext-ws@2/ws.js`
- On page load: connect to `ws://{location.host}/ws/chat` via HTMX `hx-ext="ws"`.
- Display a scrollable message history area (user messages right-aligned, assistant left).
- Display a text input and Send button (also send on Enter key).
- On receiving `{"type":"connected","sessionId":"..."}` → store sessionId, show "Connected".
- On receiving `{"type":"message","role":"assistant","text":"..."}` → render text as markdown
  using `marked.js` from CDN and append to history.
- On send: POST JSON `{"type":"message","text":"<input value>"}` over the WS connection.
- Show a typing indicator (three animated dots) while waiting for a response.
  Remove it when the assistant message arrives.
- Handle WebSocket disconnect: show a "Reconnecting..." banner and auto-reconnect after 3s.

**Visual requirements:**
- Clean, minimal dark-theme UI (no CSS framework required — hand-written CSS is fine).
- Messages must be visually distinct: user messages in a blue/teal bubble, assistant in a
  dark neutral bubble.
- Code blocks in assistant messages must use a monospace font with syntax highlighting
  (`highlight.js` from CDN is acceptable).
- Responsive: works on mobile viewport (min-width 320px).

**No server-side template rendering is needed for Phase 78.** The HTML file is served as
a static resource by Quarkus.

---

### Configuration Block (application.yaml addition)

```yaml
arcclaw:
  channel:
    web:
      enabled: true              # Set to false to disable the web channel entirely
      require-auth: false        # Set to true to enforce allowlist in production
      allowed-origins: ""        # Empty = all origins; comma-separated for restriction
```

---

### Tests

**`WebReplyRendererTest`** (unit test, no CDI needed):
- Plain text node → exact text string.
- Code block node → fenced code block in output.
- Nested nodes → correct concatenation order.
- Empty node list → empty string.

**`WebAllowlistTest`** (unit test):
- `requireAuth=false` → `isAllowed()` returns true for any `ChannelUser`.
- `requireAuth=true` → `isAllowed()` returns false for unknown user (Phase 78 — full
  allowlist implementation is Phase 79+, just assert the default-false path).

**`WebSocketSessionStoreTest`** (unit test):
- Register + get → returns the registered session.
- Register + unregister + get → `Optional.empty()`.
- `closeAll()` → calls `session.close()` on all registered sessions (use a mock).
- Concurrent register/unregister from 10 threads → no `ConcurrentModificationException`.

**`WebChannelPluginDiscoveryTest`** (`@QuarkusTest`):
- Assert `ChannelPluginRegistry.find(ChannelType.WEB)` returns a non-empty `Optional`.
- Assert all 5 components are non-null via the plugin accessor methods.
- Model this test on `TelegramChannelRouteStartupTest.java`.

---

## Delivery Checklist
- [ ] `arcclaw-channel-web` module created with correct `pom.xml` and `beans.xml`
- [ ] `ChannelType.WEB` added
- [ ] All 6 SPI interfaces implemented: Daemon, Sender, Renderer, Allowlist, JobNotifier, Plugin
- [ ] `WebChatEndpoint` WebSocket handler routes messages through `MessagePipeline`
- [ ] `index.html` static frontend connects via WS, renders markdown, shows typing indicator
- [ ] `WebChannelPlugin` discovered by `ChannelPluginRegistry` at startup
- [ ] All new tests green: `./mvnw clean test -pl arcclaw-channel-web`
- [ ] All existing tests still pass: `./mvnw clean test`
- [ ] No changes to `arcclaw-core` source (only allowed changes: ChannelType enum, pom.xml modules, app pom.xml dep, application.yaml)
```

---

```
# PROMPT — PHASE 79: Guided Onboarding Web UI — arcclaw-onboarding (Tier A)

Role: Senior Java Architect & Senior Java Developer
Task: Phase 79 — Implement a guided first-run onboarding wizard for ArcClaw as a new Maven
module `arcclaw-onboarding`. Ported conceptually from JavaClaw's 6-step HTMX wizard but
adapted to ArcClaw's Quarkus stack, Qute templates, SecretVault, and existing REST
architecture. Zero changes to arcclaw-core business logic.

**Prerequisite:** Phase 78 (arcclaw-channel-web) must be complete. Step 6 links to the
chat UI. If Phase 78 is not done, Step 6 links to the REST API docs instead.

---

## Architecture Context and Other References
Refer @Architecture_Invariants.md (§8 Sandbox and security rules — vault ownership)
Refer @arcclaw-core/src/main/java/com/arcclaw/core/security/SecretVault.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/security/VaultConstants.java
Refer @arcclaw-app/src/main/java/com/arcclaw/app/routing/LlmModelFactory.java
Refer @arcclaw-app/src/main/java/com/arcclaw/app/mcp/McpConnectResource.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/skill/SkillRegistry.java
Refer @arcclaw-channel-telegram/ (reference for ChannelDaemon.isConfigured() pattern)

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Every JAX-RS resource must have at least one integration test (`@QuarkusTest`).
2. No new Maven module for Qute templates — Qute is already in the Quarkus BOM.
3. All secrets collected by the wizard MUST be written to `SecretVault`, never to
   `application.yaml` or `.env`. This is a strict improvement over JavaClaw.
4. The wizard MUST be idempotent: re-running it overwrites previous vault entries.
5. Every form submission uses HTMX `hx-post` — full-page reloads are forbidden.
6. The wizard state (current step) is stored server-side in a `@SessionScoped` CDI bean —
   not in a cookie or URL parameter.
7. All existing tests must remain green.
8. Test commands: `./mvnw clean test -pl arcclaw-onboarding`

---

### Module Setup

**New module:** `arcclaw-onboarding`

`pom.xml`:
- Parent: `com.arcclaw:arcclaw-parent`
- Dependencies: `arcclaw-core`, `arcclaw-channel-api`, `arcclaw-app` (for `LlmModelFactory`,
  `McpConnectResource` via REST client), Quarkus RESTEasy Reactive, Quarkus Qute,
  Quarkus CDI.
- Include `jandex-maven-plugin` and `beans.xml` (Architecture Invariant §15).

**Package root:** `com.arcclaw.onboarding`

**Root `pom.xml`:** Add `arcclaw-onboarding` to `<modules>`.
**`arcclaw-app/pom.xml`:** Add `arcclaw-onboarding` as a dependency.

---

### Component C1 — OnboardingStepProvider SPI

**File:** `com.arcclaw.onboarding.spi.OnboardingStepProvider`

```java
/**
 * SPI for contributing additional steps to the onboarding wizard.
 * Channel plugins and tool plugins implement this to inject their own configuration step.
 * All @ApplicationScoped implementations are discovered automatically via CDI.
 */
public interface OnboardingStepProvider {

    /** Unique step ID, e.g. "telegram", "brave", "playwright". */
    String stepId();

    /** Display order within the optional-steps section (after core steps 1-5). */
    int order();

    /** Human-readable step title shown in the progress bar. */
    String title();

    /** Qute template name (relative to templates/onboarding/steps/), e.g. "telegram.html". */
    String templateName();

    /** Returns true if this step is already fully configured (used to pre-tick the progress bar). */
    boolean isConfigured();
}
```

**No implementations in this phase** — the interface is the deliverable. TelegramOnboardingProvider
(Phase 74) and BraveOnboardingProvider (Phase 74) will implement it.

---

### Component C2 — OnboardingSession (wizard state)

**File:** `com.arcclaw.onboarding.OnboardingSession`

```java
/**
 * Server-side session state for the wizard. Prevents step-skipping and
 * tracks which steps have been completed.
 *
 * Scope: @SessionScoped so each browser session has independent state.
 * This requires that the arcclaw-onboarding JAX-RS resources are accessed via
 * a session-enabled transport (Quarkus cookie-based session is acceptable).
 */
@SessionScoped
public class OnboardingSession implements Serializable {

    public enum Step {
        WELCOME(1),
        PROVIDER(2),
        CREDENTIALS(3),
        SYSTEM_PROMPT(4),
        MCP(5),
        COMPLETE(6);

        public final int number;
        Step(int n) { this.number = n; }

        public Step next() { /* return next enum constant, or COMPLETE if last */ }
        public boolean isAfter(Step other) { return this.number > other.number; }
    }

    private Step currentStep = Step.WELCOME;
    private String selectedProvider;    // "openai", "groq", "ollama", "claude-code", etc.
    private String selectedModel;
    private boolean credentialsValidated = false;

    // Getters, setters, and a canProceedTo(Step target) method:
    // canProceedTo(target) returns true only if target.number <= currentStep.number + 1
    // This prevents URL-hacking to skip steps.
    public boolean canProceedTo(Step target) { ... }
    public void advance() { this.currentStep = currentStep.next(); }
}
```

---

### Component C3 — OnboardingResource (JAX-RS controller)

**File:** `com.arcclaw.onboarding.OnboardingResource`

JAX-RS resource at path `/onboarding`. All endpoints are `@Path`-mapped and return
Qute `TemplateInstance` (rendered HTML fragments for HTMX swap).

```
GET  /onboarding              → render Step 1 (Welcome) or current step if session exists
GET  /onboarding/step/{n}     → render step N (guard: canProceedTo check)
POST /onboarding/step/1       → advance to step 2 (no data needed)
POST /onboarding/step/2       → save provider selection → advance to step 3
POST /onboarding/step/3       → validate credentials → save to vault → advance to step 4
POST /onboarding/step/4       → save system prompt text → advance to step 5
POST /onboarding/step/5       → register MCP server(s) (optional) → advance to step 6
GET  /onboarding/complete     → step 6 (summary + links)
GET  /onboarding/status       → JSON: { "step": 3, "provider": "groq", "credentialsOk": true }
```

All POST handlers that modify vault state must:
1. Validate the session `canProceedTo` the next step — return 403 if not.
2. Perform the action.
3. Call `session.advance()`.
4. Return the HTML fragment for the NEXT step (HTMX swaps it into the `#wizard-body` div).

---

### Step Implementations

#### Step 1 — Welcome (`POST /onboarding/step/1`)
- No data. Just advance the session.
- Template: `templates/onboarding/steps/step1-welcome.html`
- Content: Project name, brief description, link to GitHub, "Get Started" button.

#### Step 2 — Provider Selection (`POST /onboarding/step/2`)
- Form field: `provider` (radio: `groq`, `openai`, `ollama`, `anthropic`, `claude-code`).
- Save `provider` to `OnboardingSession.selectedProvider`.
- Show a model-name input pre-populated with the default for the chosen provider:
  - groq → `llama-3.1-8b-instant`
  - openai → `gpt-4o-mini`
  - ollama → `llama3.2`
  - anthropic → `claude-haiku-4-5`
  - claude-code → `claude-sonnet-4-5`
- Template: `templates/onboarding/steps/step2-provider.html`

#### Step 3 — Credentials (`POST /onboarding/step/3`)
- Form fields: `apiKey` (string), `modelName` (string).
- For provider `ollama` or `claude-code`: `apiKey` is optional (show helper text).
- **Validation:** Make a test LLM call using `LlmModelFactory.createModel(...)` with a
  one-token prompt ("Hi"). If the call succeeds → save to vault. If it fails → return
  the step 3 HTML with an `<div class="error">` block containing the error message.
  Do NOT advance the session on failure.
- **Vault storage:**
  - Key `"onboarding-llm-provider"` → provider string
  - Key `"onboarding-llm-api-key"` → apiKey
  - Key `"onboarding-llm-model"` → modelName
  - Use system session identifier (VaultConstants) per Architecture Invariant §8.2.
- `credentialsValidated = true` on success.
- Template: `templates/onboarding/steps/step3-credentials.html`

#### Step 4 — System Prompt (`POST /onboarding/step/4`)
- Form field: `systemPrompt` (textarea, pre-populated with a sensible default that includes
  placeholder slots for name, role, and preferences — modelled on JavaClaw's AGENT.md).
- Save the text to vault under key `"onboarding-system-prompt"`.
- This value is read by `SystemPromptBuilder` if present in vault (add that check in Phase 74
  — for now, just save it).
- Template: `templates/onboarding/steps/step4-prompt.html`

#### Step 5 — MCP Servers (`POST /onboarding/step/5`)
- Optional step. Form fields: `mcpName`, `mcpUrl`, `mcpTransport` (http/stdio).
- On submit with non-blank `mcpUrl`: call `McpConnectResource` logic directly
  (inject the service, not via HTTP — same JVM).
- On submit with blank fields: skip MCP registration and advance.
- Show the list of already-registered MCP servers (read from `McpClientService`).
- Include a "Skip" button that POSTs without any MCP data.
- Template: `templates/onboarding/steps/step5-mcp.html`

#### Step 6 — Complete (`GET /onboarding/complete`)
- Summary: display the configured provider, model, and any registered MCP servers.
- Links:
  - "Open Chat" → `/` (chat UI from Phase 78, or `/q/swagger-ui` if Phase 78 not present)
  - "View Skills" → `/skill` (existing `SkillResource` endpoint)
  - "System Info" → `/system-info` (existing `SystemInfoResource`)
- Template: `templates/onboarding/steps/step6-complete.html`

---

### Component C4 — Base Layout Template

**File:** `templates/onboarding/base.html`

Qute base layout including:
- HTMX 2.x from CDN.
- A step progress bar showing steps 1–6 with the current step highlighted.
- A `{#insert body /}` slot for step content.
- The `#wizard-body` div that HTMX targets for partial swaps.
- Minimal CSS: clean light theme, centered card layout, max-width 640px, step indicators
  as numbered circles connected by a horizontal line.

---

### Component C5 — Onboarding Redirect

**Modified file:** `arcclaw-app` — add a redirect rule.

When `GET /` is received AND none of the vault keys `"onboarding-llm-provider"` exists →
redirect to `/onboarding`.

This should be implemented as a `ContainerRequestFilter` or a simple check in the
root resource, NOT as a change to `AgentResource` or any core class.

```java
@ApplicationScoped
@Provider
public class OnboardingRedirectFilter implements ContainerRequestFilter {

    @Inject SecretVault vault;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!ctx.getUriInfo().getPath().startsWith("/onboarding") &&
            !ctx.getUriInfo().getPath().startsWith("/ws") &&
            !vault.get("onboarding-llm-provider", SYSTEM_SESSION).isPresent()) {
            ctx.abortWith(Response.temporaryRedirect(URI.create("/onboarding")).build());
        }
    }
}
```

---

### Tests

**`OnboardingSessionTest`** (unit test):
- `canProceedTo(Step.CREDENTIALS)` when currentStep is WELCOME → false (must go to PROVIDER first).
- `canProceedTo(Step.PROVIDER)` when currentStep is WELCOME → true.
- `advance()` from WELCOME → currentStep == PROVIDER.
- `advance()` from COMPLETE → currentStep stays COMPLETE (no wraparound).

**`OnboardingResourceTest`** (`@QuarkusTest`):
- `GET /onboarding` → 200, HTML contains step 1 content.
- `POST /onboarding/step/2` with valid provider → 200, HTML contains step 3 form.
- `POST /onboarding/step/3` with invalid key (mock LlmModelFactory to throw) → 200,
  HTML contains `class="error"`, session NOT advanced.
- `POST /onboarding/step/3` with valid key (mock success) → vault write called,
  session advanced.
- `POST /onboarding/step/5` with blank MCP fields → advances without calling McpClientService.
- `GET /onboarding/complete` → 200, HTML contains provider name from session.
- Skip-step attack: `GET /onboarding/step/4` when session is on step 2 → 403.

**`OnboardingRedirectFilterTest`** (`@QuarkusTest`):
- `GET /` when vault has no `onboarding-llm-provider` → 307 redirect to `/onboarding`.
- `GET /` when vault has `onboarding-llm-provider` → passes through (not redirected).
- `GET /ws/chat` → never redirected (WebSocket must not be interrupted).

---

## Delivery Checklist
- [ ] `arcclaw-onboarding` module created with `pom.xml`, `beans.xml`, jandex plugin
- [ ] `OnboardingStepProvider` SPI interface defined
- [ ] `OnboardingSession` `@SessionScoped` bean with step guards
- [ ] `OnboardingResource` all 8 endpoints implemented
- [ ] All 6 step templates render correct HTMX forms
- [ ] Step 3 credentials validation calls LLM before writing to vault
- [ ] All secrets written to `SecretVault` — nothing written to `.env` or `application.yaml`
- [ ] `OnboardingRedirectFilter` redirects unconfigured root path
- [ ] All new tests green: `./mvnw clean test -pl arcclaw-onboarding`
- [ ] All existing tests still pass: `./mvnw clean test`
- [ ] No modifications to `arcclaw-core` source files
- [ ] No modifications to `AgentLoop`, `ToolRegistry`, `SecretVaultImpl` internals
```

---

**Execution order:** Phase 77 → Phase 78 → Phase 79. Each is independently mergeable but logically they build on each other (Phase 79's Step 6 "Open Chat" link needs Phase 78's `/` route). Phase 77 can run in parallel with Phase 78 since they touch different modules entirely.