# PROMPT — PHASE 90: arcclaw-tool-playwright — Browser Tool (Tier B)

Role: Senior Java Architect & Senior Java Developer
Task: Phase 90 — Add a Playwright browser tool to ArcClaw as a new Maven module
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
  `com.microsoft.playwright:playwright:1.58.0` (official Maven artifact).
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
        // Content truncation: if result > 9092 chars, truncate at last sentence boundary
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

### Phase 90 Tests

**`PlaywrightBrowserToolTest`** (unit test, mock `PlaywrightPageRunner`):
- `enabled=false` → `isEnabled()` returns false, no browser launched.
- Blank URL → `ToolResponse.failure(400, ...)`.
- URL with `javascript:` scheme → rejected with 400.
- URL with `file://` scheme → rejected with 400.
- Valid URL, action=extract_text → `PlaywrightPageRunner.run()` called with correct args.
- `PlaywrightPageRunner.run()` throws → `ToolResponse.failure(502, ...)` returned.
- Result > 9092 chars → response is truncated and ends with `[content truncated]`.
- Blank userId → 403 failure.
Mock `PlaywrightPageRunner` with Mockito. Do NOT launch a real browser in unit tests.

**`PlaywrightPageRunnerIntegrationTest`** (integration test, `@Tag("integration")`):
- Mark with `@Tag("integration")` — excluded from normal `mvn test`, runs only with
  `mvn test -Dgroups=integration`.
- Test: navigate to `https://example.com` → result contains "Example Domain".
- Test: extract_text with selector `h1` → result contains "Example Domain".
Skip if `ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED` env var is not set to `true`.

***

## Phase 90 Delivery Checklist
- [ ] `arcclaw-tool-playwright` module created: `pom.xml`, `beans.xml`, jandex plugin
- [ ] `ToolCapability.BROWSER_NAVIGATE` added
- [ ] `PlaywrightBrowserTool` implements `AgentTool` — `isEnabled()` gates all execution
- [ ] `PlaywrightPageRunner` isolates Playwright API calls — mockable in tests
- [ ] `requiresSandbox = true` set — Podman isolation honoured when sandbox is active
- [ ] URL scheme validation: only `http://` and `https://` accepted
- [ ] Content truncated at 9092 chars with `[content truncated]` suffix
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
Phase 90: ./mvnw clean test -pl arcclaw-tool-playwright

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
├── arcclaw-tool-playwright  ← NEW (Phase 90)
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