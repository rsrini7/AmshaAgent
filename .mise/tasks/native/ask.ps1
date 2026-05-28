#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = 'Run native binary - one-shot task. Set $env:GOAL="..." then: mise run native:ask'
$ErrorActionPreference = 'Stop'
$goal = if ($env:GOAL) { $env:GOAL } else { '' }
if (-not $goal) {
    Write-Host 'Usage:  $env:GOAL="Your goal here"; mise run native:ask'
    Write-Host 'Roles:  coordinator - researcher - architect - synthesizer - debugger'
    exit 1
}
$suffix = if ($IsWindows) { '-runner.exe' } else { '-runner' }
$runner = "arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT$suffix"
if (-not (Test-Path $runner)) {
    Write-Host "❌  Native binary not found. Run: mise run build:native-local (or build:native-linux for Linux)"; exit 1
}
if (-not $IsWindows) { $env:LD_PRELOAD = '/lib/x86_64-linux-gnu/libm.so.6' }
$cmdArgs = @('ask', $goal)
if ($env:ROLE) { $cmdArgs += '--role', $env:ROLE }
$env:ARCCLAW_RAG_VEC_PATH = "./lib/vec0"
$env:ARCCLAW_DATA_DIR = "./data"
& "./$runner" @cmdArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
