#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start the official Playwright MCP Server (requires Node.js 20+)"
Write-Host '▶   Starting Playwright MCP Server on stdio...'
Write-Host '    (Keep this terminal open — the server runs in the foreground)'
Write-Host '    Then in another terminal: mise run jar:serve -Damsha.tools.playwright.mcp-enabled=true'
Write-Host ''
$env:PLAYWRIGHT_MCP_HEADLESS = '1'
npx @playwright/mcp
