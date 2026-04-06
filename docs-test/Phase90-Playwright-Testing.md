## ArcClaw Playwright Testing Guide

### 1. Prerequisites — Browser Binary Setup

Before any integration test runs, Chromium must be installed. Do this once per machine: [playwright](https://playwright.dev/java/docs/browsers)

```bash
# Chromium only (recommended — ~200 MB vs ~600 MB for all browsers)
./mvnw exec:java -e \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install --with-deps chromium"
```

`--with-deps` also installs OS-level shared libraries (`libnss3`, `libatk`, `libgbm1`, etc.) that Chromium requires on Linux. Without it, headless Chromium fails silently. [playwright](https://playwright.dev/java/docs/browsers)

Verify the install worked:
```bash
./mvnw exec:java -e \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="show-browsers"
```

***

### 2. Unit Tests — No Browser Required

These run in normal CI with zero setup. The browser never starts.

```bash
./mvnw clean test -pl arcclaw-tool-playwright
```

The mock pattern using `WorkspaceContext.run()` (as established in Phase 90):

```java
private ToolResponse runWithContext(Map<String, Object> params) throws Exception {
    return WorkspaceContext.run(
        "sess-1", "test-user-123", "chat-1",
        AutonomyLevel.FULL, "researcher",
        () -> tool.execute(params)
    );
}
```

Run only a single test class during development:
```bash
./mvnw test -pl arcclaw-tool-playwright \
  -Dtest=PlaywrightBrowserToolTest
```

Run a single test method:
```bash
./mvnw test -pl arcclaw-tool-playwright \
  -Dtest=PlaywrightBrowserToolTest#execute_returns400_whenFileScheme
```

***

### 3. Integration Tests — Real Browser

```bash
# Run all integration tests
ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED=true \
  ./mvnw test -pl arcclaw-tool-playwright -Dgroups=integration

# Run a single integration test method
ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED=true \
  ./mvnw test -pl arcclaw-tool-playwright \
  -Dgroups=integration \
  -Dtest=PlaywrightPageRunnerIntegrationTest#navigate_http404_throwsPlaywrightException
```

Without the env var, `@BeforeAll assumeTrue` skips the entire suite cleanly — no failure in CI. [playwright](https://playwright.dev/java/docs/running-tests)

***

### 4. Debugging a Failing Integration Test

**Step 1 — Switch to headed mode** to watch the browser live: [currents](https://currents.dev/posts/when-tests-should-run-headless-vs-headed-in-playwright)

```java
// Temporarily in PlaywrightPageRunner.run() for debugging
Browser browser = playwright.chromium().launch(
    new BrowserType.LaunchOptions()
        .setHeadless(false)        // see the browser
        .setSlowMo(500)            // 500ms pause between each action
);
```

Then run the specific failing test — the browser window opens and you can watch exactly what happens.

**Step 2 — Add `page.pause()`** to freeze execution at a specific point and inspect the DOM interactively: [youtube](https://www.youtube.com/watch?v=rhzrFiKfWwY)

```java
Response response = page.navigate(url);
page.pause();   // browser freezes here — open DevTools, inspect selectors
```

**Step 3 — Capture a screenshot on failure** to see exactly what the page looked like:

```java
} catch (TimeoutError e) {
    // debug only — remove before committing
    page.screenshot(new Page.ScreenshotOptions()
        .setPath(Paths.get("target/debug-" + System.currentTimeMillis() + ".png")));
    page.waitForLoadState(LoadState.LOAD);
}
```

***

### 5. Common Failures & Fixes

| Symptom | Cause | Fix |
|---|---|---|
| `Executable doesn't exist at ~/.cache/ms-playwright/...` | Browser binary not installed | Run `install --with-deps chromium` |
| `TimeoutError` on `NETWORKIDLE` | Site has persistent polling/WebSocket | Already handled — degrades to `LOAD` |
| `page.locator().innerText()` returns empty string | Selector not found or content is in an iframe | Try `"body"` as fallback selector; check for frames |
| `Navigation returned HTTP 403` wraps as `502` to agent | Site blocks headless UA | Add `setUserAgent()` to `BrowserContext.newContext()` if needed |
| `normalizeWhitespace` not collapsing newlines | The `\\\\n` bug (fixed in Phase 90) | Confirm regex is `"\\n{3,}"` not `"\\\\n{3,}"` |
| Test passes locally, fails in CI | Missing `--with-deps` on Linux | Re-run install with `--with-deps` flag |

***

### 6. Useful `PLAYWRIGHT_*` Environment Variables

These work with Playwright Java without any code changes: [stackoverflow](https://stackoverflow.com/questions/77843438/how-do-i-get-playwright-to-use-chromium-when-deploying-on-render-either-with-or)

```bash
# Point to a custom browser binary (avoid re-downloading in CI)
PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers

# Skip automatic browser download entirely (use pre-installed system browser)
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# Enable verbose Playwright internal logs (useful for diagnosing launch failures)
DEBUG=pw:api
```

***

### 7. Quick Reference

```bash
# One-time binary setup
./mvnw exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install --with-deps chromium"

# Unit tests (CI — no browser)
./mvnw clean test -pl arcclaw-tool-playwright

# Integration tests (local — real browser)
ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED=true ./mvnw test -pl arcclaw-tool-playwright -Dgroups=integration

# Full project (confirm nothing broken)
./mvnw clean test

# Single test method
./mvnw test -pl arcclaw-tool-playwright -Dtest=ClassName#methodName
```