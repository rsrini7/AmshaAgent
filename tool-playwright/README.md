# amsha-tool-playwright

Playwright headless browser tool for Amsha Agent. Provides the `browser_navigate` capability,
allowing agents to read JavaScript-rendered pages that are inaccessible to plain HTTP fetch.

## Browser Binary Download

Playwright downloads browser binaries automatically on first use via `Playwright.create()`.

Since Playwright **1.57**, the default Chromium channel is **Chrome for Testing**
(not open-source Chromium). First run downloads ~200 MB.

To pre-install only Chromium (recommended for CI/server environments):

```bash
./mvnw exec:java -e \
  -pl amsha-tool-playwright \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install chromium"
```

To install all browsers (Chromium, Firefox, WebKit):

```bash
./mvnw exec:java -e \
  -pl amsha-tool-playwright \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install"
```

## Enabling the Tool

Disabled by default. Enable in `application.yaml`:

```yaml
amsha:
  tools:
    playwright:
      enabled: true
```

Or via environment variable:

```bash
export AMSHA_TOOLS_PLAYWRIGHT_ENABLED=true
```

## Configuration Properties

| Property | Default | Description |
|---|---|---|
| `amsha.tools.playwright.enabled` | `false` | Enable/disable the tool |
| `amsha.tools.playwright.headless` | `true` | Headless mode (false for debug) |
| `amsha.tools.playwright.timeout-ms` | `30000` | Navigation timeout in ms |
| `amsha.tools.playwright.mcp-enabled` | `false` | Enable Playwright MCP Server mode (uses @playwright/mcp via STDIO) |
| `amsha.tools.playwright.mcp-headless` | `true` | Set to false to show browser window (`PLAYWRIGHT_MCP_HEADLESS` env var) |
| `amsha.tools.playwright.mcp-command` | `npx` | Command to launch the Playwright MCP Server |
| `amsha.tools.playwright.mcp-args` | `@playwright/mcp` | Arguments for the MCP Server command |

## Running Tests

Unit tests (no browser required):

```bash
./mvnw clean test -pl amsha-tool-playwright
```

Integration tests (requires real browser):

```bash
AMSHA_TOOLS_PLAYWRIGHT_ENABLED=true \
  ./mvnw test -pl amsha-tool-playwright -Dgroups=integration
```
