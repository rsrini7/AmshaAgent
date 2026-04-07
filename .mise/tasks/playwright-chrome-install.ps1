#!/usr/bin/env pwsh
# [MISE] description="Install Playwright Chromium browser binary (idempotent)"
# [MISE] depends=["setup:wsl-crlf"]
#MISE quiet=false

$ErrorActionPreference = "Stop"

# Resolve browser cache path — respect mise-pinned env var if set
$browsersPath = if ($env:PLAYWRIGHT_BROWSERS_PATH) {
    $env:PLAYWRIGHT_BROWSERS_PATH
} elseif ($IsWindows) {
    "$env:LOCALAPPDATA\ms-playwright"
} else {
    "$HOME/.cache/ms-playwright"
}

# Guard — skip if chromium directory already exists
if (Test-Path "$browsersPath/chromium-*" -PathType Container) {
    Write-Host "✓ Playwright Chromium already installed at $browsersPath" -ForegroundColor Green
    exit 0
}

Write-Host "→ Installing Playwright Chromium into $browsersPath ..." -ForegroundColor Cyan

& ./mvnw exec:java -e `
    "-pl" "arcclaw-tool-playwright" `
    "-Dexec.mainClass=com.microsoft.playwright.CLI" `
    "-Dexec.args=install --with-deps chromium"

if ($LASTEXITCODE -ne 0) {
    Write-Error "✗ Playwright Chromium installation failed (exit $LASTEXITCODE)"
    exit $LASTEXITCODE
}

Write-Host "✓ Playwright Chromium installed" -ForegroundColor Green