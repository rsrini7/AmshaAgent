#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Run native binary - Telegram daemon (requires TELEGRAM_BOT_TOKEN in .env)"
$ErrorActionPreference = 'Stop'
if (-not $env:TELEGRAM_BOT_TOKEN) { Write-Host '❌  TELEGRAM_BOT_TOKEN is not set in .env'; exit 1 }
$suffix = if ($IsWindows) { '-runner.exe' } else { '-runner' }
$runner = "app/target/app-1.0.0-SNAPSHOT$suffix"
if (-not (Test-Path $runner)) {
    Write-Host "❌  Native binary not found. Run: mise run build:native-local (or build:native-linux for Linux)"; exit 1
}
if (-not $IsWindows) { $env:LD_PRELOAD = '/lib/x86_64-linux-gnu/libm.so.6' }
$env:AMSHA_RAG_VEC_PATH = "./lib/vec0"
$env:AMSHA_DATA_DIR = "./data"
& "./$runner" attach
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
