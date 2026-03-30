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

