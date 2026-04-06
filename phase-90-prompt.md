# PROMPT — PHASE 90: arcclaw-tool-playwright — Browser Tool (Tier B)

**Role:** Senior Java Architect & Senior Java Developer
**Task:** Phase 90 — Add a Playwright browser tool to ArcClaw as a new Maven module
`arcclaw-tool-playwright`. Implemented from scratch against Playwright Java **1.58.0**,
adapted to ArcClaw's `AgentTool` interface, `ToolCapability` enum, and sandbox model.
When `arcclaw.tools.playwright.enabled` is false (default), the tool is excluded from
`ToolRegistry` via `isEnabled()` — no Playwright process is ever started.

**Prerequisite:** Phase 77 (added `AgentTool.isEnabled()` default method).

---

## Architecture Context and Other References

Refer @Architecture_Invariants.md
Refer @arcclaw-core/src/main/java/com/arcclaw/core/tools/AgentTool.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/tools/ToolCapability.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/tools/SerperWebSearchTool.java (isEnabled pattern and parametersSchema builder pattern)
Refer @arcclaw-app/src/main/java/com/arcclaw/app/security/AuditService.java

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. Every new public class must have at least one test.
2. No changes to `arcclaw-core` beyond adding `BROWSER_NAVIGATE` to `ToolCapability`.
3. All existing tests must remain green.
4. Playwright processes MUST NOT start if `isEnabled()` returns false.
5. Test commands: `./mvnw clean test -pl arcclaw-tool-playwright`

---

## Module Setup

Follow New Module Creation challanges - Refer @Architecture_Invariants.md
Also do refer other modules before generating.. below is high level only.

**New module:** `arcclaw-tool-playwright`

### `pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
           http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.arcclaw</groupId>
    <artifactId>arcclaw-parent</artifactId>
    <version>${revision}</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>arcclaw-tool-playwright</artifactId>
  <name>ArcClaw :: Tool :: Playwright</name>

  <dependencies>
    <dependency>
      <groupId>com.arcclaw</groupId>
      <artifactId>arcclaw-core</artifactId>
    </dependency>
    <dependency>
      <groupId>com.arcclaw</groupId>
      <artifactId>arcclaw-app</artifactId>
    </dependency>
    <!-- Quarkus CDI -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-arc</artifactId>
    </dependency>
    <!-- Playwright Java 1.58.0 -->
    <dependency>
      <groupId>com.microsoft.playwright</groupId>
      <artifactId>playwright</artifactId>
      <version>1.58.0</version>
    </dependency>
    <!-- Test -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-junit5</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>io.smallrye</groupId>
        <artifactId>jandex-maven-plugin</artifactId>
        <executions>
          <execution>
            <id>make-index</id>
            <goals><goal>jandex</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

### `src/main/resources/META-INF/beans.xml`

```xml
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
         https://jakarta.ee/xml/ns/jakartaee/beans_3_0.xsd"
       version="3.0"
       bean-discovery-mode="annotated">
</beans>
```

**Root `pom.xml`:** Add `arcclaw-tool-playwright` to `<modules>`.

**`arcclaw-app/pom.xml`:** Add:

```xml
<dependency>
  <groupId>com.arcclaw</groupId>
  <artifactId>arcclaw-tool-playwright</artifactId>
</dependency>
```

**Package root:** `com.arcclaw.tool.playwright`

---

## ToolCapability.java Modification

In `arcclaw-core`, add **one line** to the `ToolCapability` enum:

```java
BROWSER_NAVIGATE("browser_navigate")
```

No other changes to `arcclaw-core`.

---

## Component D1 — PlaywrightBrowserTool

**File:** `com.arcclaw.tool.playwright.PlaywrightBrowserTool`

```java
package com.arcclaw.tool.playwright;

import com.arcclaw.app.security.AuditService;
import com.arcclaw.app.security.SecurityEventType;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolCapability;
import com.arcclaw.core.tools.ToolOperation;
import com.arcclaw.core.tools.ToolResponse;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class PlaywrightBrowserTool implements AgentTool {

    private static final int MAX_CONTENT_CHARS = 9092;
    private static final Pattern SAFE_URL_PATTERN =
        Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    @ConfigProperty(name = "arcclaw.tools.playwright.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "arcclaw.tools.playwright.headless", defaultValue = "true")
    boolean headless;

    @ConfigProperty(name = "arcclaw.tools.playwright.timeout-ms", defaultValue = "30000")
    int timeoutMs;

    @Inject
    PlaywrightPageRunner pageRunner;

    @Inject
    AuditService auditService;

    @Override
    public String name() {
        return ToolCapability.BROWSER_NAVIGATE.toolName();
    }

    @Override
    public boolean requiresSandbox() {
        // Run inside Podman when sandbox is active; degrades to host execution when unavailable
        return true;
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.NETWORK;
    }

    @Override
    public boolean requiresManualApproval(Map<String, Object> params) {
        // Browser navigation is network-only; no shell execution approval required
        return false;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String description() {
        return "Navigates to a URL using a real headless browser (Playwright/Chromium) and " +
               "returns the visible page text content. Use for reading web pages that require " +
               "JavaScript rendering or are not accessible via plain HTTP fetch. " +
               "Supports text extraction, scoped CSS selector extraction, and PNG screenshot.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("url",
                "The full URL to navigate to. Must begin with http:// or https://.")
            .addEnumProperty("action",
                List.of("navigate", "extract_text", "screenshot"),
                "navigate = return full raw HTML; " +
                "extract_text = return visible body text, stripped of nav/footer boilerplate (default); " +
                "screenshot = return base64-encoded PNG of the viewport (capped at 1 MB).")
            .addStringProperty("selector",
                "Optional CSS selector to scope text extraction to a specific element " +
                "(e.g. 'article', 'main', '#content'). Ignored for action=navigate and action=screenshot.")
            .required("url")
            .build();
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        // Guard 1: tool disabled
        if (!enabled) {
            return ToolResponse.failure(503, "Playwright browser tool is not enabled. " +
                "Set arcclaw.tools.playwright.enabled=true to activate.");
        }

        // Guard 2: userId
        String userId = (String) params.get("userId");
        if (userId == null || userId.isBlank()) {
            return ToolResponse.failure(403, "userId is required.");
        }

        // Guard 3: url present
        String url = (String) params.get("url");
        if (url == null || url.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'url' is required.");
        }

        // Guard 4: URL scheme — only http / https
        if (!SAFE_URL_PATTERN.matcher(url.trim()).matches()) {
            return ToolResponse.failure(400,
                "Unsupported URL scheme. Only http:// and https:// are allowed. Got: " + url);
        }

        String action   = params.getOrDefault("action", "extract_text").toString();
        String selector = (String) params.get("selector");   // nullable

        try {
            String result = pageRunner.run(url.trim(), action, selector, headless, timeoutMs);

            // Truncate text results at sentence boundary; screenshots are pre-capped in runner
            if (!"screenshot".equals(action) && result.length() > MAX_CONTENT_CHARS) {
                result = truncateAtSentence(result, MAX_CONTENT_CHARS) + "\n[content truncated]";
            }

            // Audit with URL only — never log page content
            auditService.record(SecurityEventType.TOOL_EXECUTED, userId, url);

            return ToolResponse.success(result);

        } catch (Exception e) {
            // Never propagate Playwright exceptions; wrap as 502
            return ToolResponse.failure(502,
                "Browser navigation failed: " + e.getMessage());
        }
    }

    /**
     * Truncates text at the last sentence-ending punctuation (. ! ?) at or before maxLen.
     * Falls back to a hard cut at maxLen if no sentence boundary is found.
     */
    private String truncateAtSentence(String text, int maxLen) {
        String sub = text.substring(0, maxLen);
        int lastSentence = Math.max(
            sub.lastIndexOf('.'),
            Math.max(sub.lastIndexOf('!'), sub.lastIndexOf('?'))
        );
        return lastSentence > maxLen / 2 ? sub.substring(0, lastSentence + 1) : sub;
    }
}
```

---

## Component D2 — PlaywrightPageRunner

**File:** `com.arcclaw.tool.playwright.PlaywrightPageRunner`

This class isolates all Playwright API calls so `PlaywrightBrowserTool` is unit-testable
by mocking this bean. It must never log page content.

```java
package com.arcclaw.tool.playwright;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotScale;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Base64;
import java.util.regex.Pattern;

@ApplicationScoped
public class PlaywrightPageRunner {

    private static final Pattern SAFE_URL_PATTERN =
        Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    // 1 MB hard cap for screenshot bytes before Base64 encoding
    private static final int MAX_SCREENSHOT_BYTES = 1_048_576;

    /**
     * Launches a fresh Chromium browser context, navigates to url, returns result.
     * Each call creates and destroys its own Playwright + Browser instance — no shared state.
     *
     * @param url       target URL (must match SAFE_URL_PATTERN)
     * @param action    "navigate" | "extract_text" | "screenshot"
     * @param selector  optional CSS selector to scope extraction (null = full body)
     * @param headless  run headless (true in production)
     * @param timeoutMs navigation timeout in milliseconds
     * @return result string: visible text, raw HTML, or base64 PNG
     * @throws PlaywrightException on navigation or execution failure
     */
    public String run(String url, String action, String selector,
                      boolean headless, int timeoutMs) {

        if (!SAFE_URL_PATTERN.matcher(url).matches()) {
            throw new IllegalArgumentException("Unsafe URL rejected: " + url);
        }

        // Each invocation owns a fresh Playwright instance — no session bleed between users
        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless)
                // NOTE: devtools option was removed in Playwright 1.58.
                // To open DevTools in debug mode use:
                //   .setArgs(List.of("--auto-open-devtools-for-tabs"))
            );

            try (BrowserContext context = browser.newContext()) {
                Page page = context.newPage();
                page.setDefaultNavigationTimeout(timeoutMs);

                // Navigate and check HTTP status — silent 4xx/5xx would return error HTML
                Response response = page.navigate(url);
                if (response != null && !response.ok()) {
                    throw new PlaywrightException(
                        "Navigation returned HTTP " + response.status() +
                        " " + response.statusText() + " for: " + url);
                }

                // Wait for network idle; degrade to LOAD for sites that poll continuously
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(timeoutMs));
                } catch (TimeoutError e) {
                    // Site has persistent network activity (analytics, WebSocket, etc.)
                    // Degrade gracefully — page content is still usable
                    page.waitForLoadState(LoadState.LOAD);
                }

                return switch (action) {
                    case "screenshot" -> captureScreenshot(page);
                    case "navigate"   -> normalizeWhitespace(page.content());
                    default           -> extractText(page, selector); // "extract_text" + fallback
                };
            }
        }
    }

    private String extractText(Page page, String selector) {
        String scope = (selector != null && !selector.isBlank()) ? selector : "body";
        String text  = page.locator(scope).innerText();
        return normalizeWhitespace(text);
    }

    private String captureScreenshot(Page page) {
        byte[] png = page.screenshot(
            new Page.ScreenshotOptions().setFullPage(false));

        // If over 1 MB, re-shoot at CSS scale (reduces size on hi-DPI displays)
        if (png.length > MAX_SCREENSHOT_BYTES) {
            png = page.screenshot(new Page.ScreenshotOptions()
                .setFullPage(false)
                .setScale(ScreenshotScale.CSS));
        }

        return Base64.getEncoder().encodeToString(png);
    }

    /** Collapses 3+ consecutive newlines to 2; trims leading/trailing whitespace. */
    private String normalizeWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\n{3,}", "\n\n").trim();
    }
}
```

---

## Component D3 — application.yaml additions

Add to `arcclaw-app/src/main/resources/application.yaml`:

```yaml
arcclaw:
  tools:
    playwright:
      enabled: false        # Set true to enable browser_navigate tool (requires binary install)
      headless: true        # Set false for visible browser window (debug only)
      timeout-ms: 30000     # Navigation timeout in milliseconds
```

Add to `.env.example`:

```
# ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED=true
# Enables browser_navigate tool. Playwright downloads Chrome for Testing binaries
# on first run (~200 MB). Install browsers manually with:
#   ./mvnw exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI \
#     -Dexec.args="install chromium"
```

---

## Component D4 — README.md (module root)

Create `arcclaw-tool-playwright/README.md`:

```markdown
# arcclaw-tool-playwright

Playwright headless browser tool for ArcClaw. Provides the `browser_navigate` capability,
allowing agents to read JavaScript-rendered pages that are inaccessible to plain HTTP fetch.

## Browser Binary Download

Playwright downloads browser binaries automatically on first use via `Playwright.create()`.

Since Playwright **1.57**, the default Chromium channel is **Chrome for Testing**
(not open-source Chromium). This is ~200 MB on first run.

To pre-install only Chromium (recommended for CI/server environments):

```bash
./mvnw exec:java -e \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install chromium"
```

To install all browsers (Chromium, Firefox, WebKit):

```bash
./mvnw exec:java -e \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install"
```

## Enabling the Tool

The tool is **disabled by default**. Set in `application.yaml` or via environment variable:

```yaml
arcclaw:
  tools:
    playwright:
      enabled: true
```

or:

```bash
export ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED=true
```

## Configuration Properties

| Property | Default | Description |
|---|---|---|
| `arcclaw.tools.playwright.enabled` | `false` | Enable/disable the tool |
| `arcclaw.tools.playwright.headless` | `true` | Headless mode (set false for debug) |
| `arcclaw.tools.playwright.timeout-ms` | `30000` | Navigation timeout in ms |

## Running Tests

Unit tests (no browser required):

```bash
./mvnw clean test -pl arcclaw-tool-playwright
```

Integration tests (requires real browser + `ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED=true`):

```bash
./mvnw test -pl arcclaw-tool-playwright -Dgroups=integration
```
```

---

## Phase 90 Tests

### PlaywrightBrowserToolTest (unit test)

**File:** `src/test/java/com/arcclaw/tool/playwright/PlaywrightBrowserToolTest.java`

Mock `PlaywrightPageRunner` with Mockito. Do NOT start a real browser in unit tests.

Cover all of the following cases:

```java
@ExtendWith(MockitoExtension.class)
class PlaywrightBrowserToolTest {

    @Mock PlaywrightPageRunner pageRunner;
    @Mock AuditService auditService;

    PlaywrightBrowserTool tool;

    @BeforeEach
    void setUp() {
        tool = new PlaywrightBrowserTool();
        // inject mocks + set config fields via reflection or constructor injection
        // enabled = true for positive-path tests; headless = true; timeoutMs = 5000
    }

    // ── Disabled state ─────────────────────────────────────────────────────────
    @Test void isEnabled_returnsFalse_whenConfigFalse() { /* set enabled=false; assert false */ }
    @Test void execute_returns503_whenToolDisabled()    { /* enabled=false; execute → 503 */ }

    // ── Input validation ───────────────────────────────────────────────────────
    @Test void execute_returns400_whenUrlBlank()        { /* url="" → 400 */ }
    @Test void execute_returns400_whenUrlNull()         { /* url=null → 400 */ }
    @Test void execute_returns400_whenJavascriptScheme(){ /* url="javascript:alert(1)" → 400 */ }
    @Test void execute_returns400_whenFileScheme()      { /* url="file:///etc/passwd" → 400 */ }
    @Test void execute_returns400_whenFtpScheme()       { /* url="ftp://host/file" → 400 */ }
    @Test void execute_returns403_whenUserIdBlank()     { /* userId="" → 403 */ }

    // ── Happy path ────────────────────────────────────────────────────────────
    @Test void execute_callsRunnerWithCorrectArgs_extractText() {
        // url="https://example.com", action="extract_text", selector=null
        // verify pageRunner.run("https://example.com", "extract_text", null, true, 5000)
        // assert ToolResponse.isSuccess()
    }
    @Test void execute_callsRunnerWithSelector() {
        // url="https://example.com", action="extract_text", selector="h1"
        // verify runner called with selector="h1"
    }
    @Test void execute_defaultsActionToExtractText_whenAbsent() {
        // params has no "action" key
        // verify runner called with action="extract_text"
    }

    // ── Error handling ────────────────────────────────────────────────────────
    @Test void execute_returns502_whenRunnerThrows() {
        // pageRunner.run(...) throws RuntimeException("connection refused")
        // assert ToolResponse.failure(502, ...)
        // verify no exception propagates
    }

    // ── Truncation ────────────────────────────────────────────────────────────
    @Test void execute_truncatesResultOver9092Chars() {
        // pageRunner returns string of 10000 chars ending with "end of content."
        // assert response.content().endsWith("[content truncated]")
        // assert response.content().length() <= 9092 + "[content truncated]".length()
    }
    @Test void execute_doesNotTruncateResultUnder9092Chars() {
        // pageRunner returns "short text"
        // assert response.content() equals "short text" exactly
    }

    // ── Screenshot ────────────────────────────────────────────────────────────
    @Test void execute_doesNotTruncate_screenshotResult() {
        // action="screenshot"; runner returns base64 string > 9092 chars
        // screenshot results must NOT be truncated (already capped in runner)
        // assert response.content() == runner output verbatim
    }
}
```

### PlaywrightPageRunnerIntegrationTest (integration test)

**File:** `src/test/java/com/arcclaw/tool/playwright/PlaywrightPageRunnerIntegrationTest.java`

```java
@Tag("integration")
class PlaywrightPageRunnerIntegrationTest {

    PlaywrightPageRunner runner = new PlaywrightPageRunner();

    @BeforeAll
    static void checkEnv(TestInfo info) {
        String enabled = System.getenv("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED");
        assumeTrue("true".equalsIgnoreCase(enabled),
            "Skipping integration tests: ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED is not set to true");
    }

    @Test
    void navigate_extractText_returnsPageContent() {
        String result = runner.run("https://example.com", "extract_text", null, true, 30000);
        assertThat(result).containsIgnoringCase("Example Domain");
    }

    @Test
    void navigate_selectorH1_returnsHeading() {
        String result = runner.run("https://example.com", "extract_text", "h1", true, 30000);
        assertThat(result).containsIgnoringCase("Example Domain");
    }

    @Test
    void navigate_screenshot_returnsBase64Png() {
        String result = runner.run("https://example.com", "screenshot", null, true, 30000);
        // Base64 PNG starts with iVBORw (PNG magic bytes encoded)
        assertThat(result).startsWith("iVBORw");
    }

    @Test
    void navigate_http404_throwsPlaywrightException() {
        assertThatThrownBy(() ->
            runner.run("https://httpstat.us/404", "extract_text", null, true, 30000))
            .isInstanceOf(PlaywrightException.class)
            .hasMessageContaining("404");
    }
}
```

Run integration tests with:
```bash
ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED=true ./mvnw test -pl arcclaw-tool-playwright -Dgroups=integration
```

---

## Playwright 1.58 — Known API Constraints

The following Playwright features are **removed or unavailable** in 1.58. Do not use them:

| Removed | Replacement |
|---|---|
| `BrowserType.LaunchOptions.setDevtools()` | `setArgs(List.of("--auto-open-devtools-for-tabs"))` |
| `_react` / `_vue` selector engines | Standard CSS / `getByRole()` locators |
| `:light` selector suffix | Standard CSS selectors |
| `page.accessibility()` (removed in 1.57) | External Axe library if needed |

Since **1.57**, headless Chromium uses **Chrome for Testing** binaries (not open-source
Chromium). Document this in README. No code change required.

---

## Phase 90 Delivery Checklist

```
Module
- [ ] arcclaw-tool-playwright module created: pom.xml, beans.xml, jandex plugin
- [ ] Package root: com.arcclaw.tool.playwright

Core change
- [ ] ToolCapability.BROWSER_NAVIGATE added (one line, arcclaw-core only)

PlaywrightBrowserTool
- [ ] implements AgentTool — isEnabled() gates all execution
- [ ] requiresSandbox = true — Podman isolation honoured when available
- [ ] requiresManualApproval returns false
- [ ] URL scheme validation: only http:// and https:// accepted
- [ ] 403 on blank/missing userId
- [ ] 400 on blank/missing URL
- [ ] 400 on non-http(s) schemes (file://, javascript:, ftp://, etc.)
- [ ] 503 when tool disabled
- [ ] 502 on any Playwright exception (never propagates)
- [ ] Text results truncated at sentence boundary at 9092 chars + "[content truncated]"
- [ ] Screenshot results NOT truncated (capped in runner at 1 MB)
- [ ] Audit: SecurityEventType.TOOL_EXECUTED with url (NOT page content)
- [ ] parametersSchema uses JsonObjectSchema.builder() (same pattern as BraveWebSearchTool)

PlaywrightPageRunner
- [ ] Each call: fresh Playwright + Browser + BrowserContext — no shared state
- [ ] page.navigate() response checked — non-2xx throws PlaywrightException → 502
- [ ] NETWORKIDLE with timeout; degrades gracefully to LOAD on TimeoutError
- [ ] "extract_text": page.locator(selector ?? "body").innerText()
- [ ] "navigate": page.content() (full raw HTML)
- [ ] "screenshot": page.screenshot(), re-shot at ScreenshotScale.CSS if > 1 MB
- [ ] Whitespace normalised: 3+ consecutive newlines collapsed to 2
- [ ] MUST NOT log page content at any log level
- [ ] setDevtools NOT used (removed in 1.58)

Config
- [ ] application.yaml updated: enabled/headless/timeout-ms under arcclaw.tools.playwright
- [ ] .env.example updated with ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED comment
- [ ] README.md in module root: binary download note, Chrome for Testing note (since 1.57),
      install command (chromium only), config table, test commands

Tests
- [ ] Unit tests green without launching a real browser: ./mvnw clean test -pl arcclaw-tool-playwright
- [ ] All 11 unit test cases covered (disabled, validation, happy path, errors, truncation)
- [ ] Integration test tagged @Tag("integration") — skipped in normal CI
- [ ] Integration test uses assumeTrue on ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED env var
- [ ] All existing project tests still pass: ./mvnw clean test
```

---

## Global Delivery Checklist (All Phases)

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
├── arcclaw-app              (depends on all above)
└── arcclaw-ui               ← NEW (Phase 79, Vite+Lit, no Java)
```

## What Is NOT Ported from JavaClaw

| JavaClaw pattern | Reason skipped | ArcClaw equivalent |
|---|---|---|
| `FileSystemTaskRepository` | ArcClaw SQLite is superior | `StatefulAgentStore` |
| `FileSystemChatMemoryRepository` | ArcClaw compaction model is better | `PersistentChatMemoryStore` |
| `JobRunr` scheduler | ArcClaw cron integrates with HITL | `AgentCronScheduler` |
| `ConfigurationManager` (write YAML) | Insecure — plaintext secrets | `SecretVault` |
| `ChannelRegistry.getLatestChannel()` | Architecturally fragile | `ChannelPluginRegistry.find(type)` |
| Qute templates in browser layer | Replaced by Lit SPA | `arcclaw-ui` components |
| HTMX CDN fragments | Replaced by SSE + JSON API | `AgentStatusResource`, SSE streams |
