# arcclaw-tool-playwright

Playwright headless browser tool for ArcClaw. Provides the `browser_navigate` capability,
allowing agents to read JavaScript-rendered pages that are inaccessible to plain HTTP fetch.

## Browser Binary Download

Playwright downloads browser binaries automatically on first use via `Playwright.create()`.

Since Playwright **1.57**, the default Chromium channel is **Chrome for Testing**
(not open-source Chromium). First run downloads ~200 MB.

To pre-install only Chromium (recommended for CI/server environments):

```bash
./mvnw exec:java -e \
  -pl arcclaw-tool-playwright \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install chromium"
```

To install all browsers (Chromium, Firefox, WebKit):

```bash
./mvnw exec:java -e \
  -pl arcclaw-tool-playwright \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install"
```

## Enabling the Tool

Disabled by default. Enable in `application.yaml`:

```yaml
arcclaw:
  tools:
    playwright:
      enabled: true
```

Or via environment variable:

```bash
export ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED=true
```

## Configuration Properties

| Property | Default | Description |
|---|---|---|
| `arcclaw.tools.playwright.enabled` | `false` | Enable/disable the tool |
| `arcclaw.tools.playwright.headless` | `true` | Headless mode (false for debug) |
| `arcclaw.tools.playwright.timeout-ms` | `30000` | Navigation timeout in ms |
| `arcclaw.tools.playwright.mcp-enabled` | `false` | Enable Playwright MCP Server mode (uses @playwright/mcp via STDIO) |
| `arcclaw.tools.playwright.mcp-command` | `npx` | Command to launch the Playwright MCP Server |
| `arcclaw.tools.playwright.mcp-args` | `@playwright/mcp` | Arguments for the MCP Server command |

## Running Tests

Unit tests (no browser required):

```bash
./mvnw clean test -pl arcclaw-tool-playwright
```

Integration tests (requires real browser):

```bash
ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED=true \
  ./mvnw test -pl arcclaw-tool-playwright -Dgroups=integration
```
