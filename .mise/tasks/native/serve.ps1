#!/usr/bin/env pwsh
#MISE description = "Run native binary - REST daemon on :8080"
$ErrorActionPreference = 'Stop'
$suffix = if ($IsWindows) { '-runner.exe' } else { '-runner' }
$runner = "arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT$suffix"
if (-not (Test-Path $runner)) {
    Write-Host "❌  Native binary not found. Run: mise run build:native-local (or build:native-linux for Linux)"; exit 1
}
if (-not $IsWindows) { $env:LD_PRELOAD = '/lib/x86_64-linux-gnu/libm.so.6' }
$env:ARCCLAW_RAG_VEC_PATH = "./lib/vec0"
$env:ARCCLAW_DATA_DIR = "./data"
& "./$runner" serve
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
