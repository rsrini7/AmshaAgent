#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Open arcclaw-meta.db in sqlite3 interactive CLI"
$ErrorActionPreference = 'Stop'
$dataDir = if ($env:ARCCLAW_DATA_DIR) { $env:ARCCLAW_DATA_DIR } else { './data' }
$db = "$dataDir/arcclaw-meta.db"
if (-not (Test-Path $db)) {
    Write-Host "❌  Database not found at: $db"
    Write-Host '    Has ArcClaw been started at least once?'
    exit 1
}
Write-Host "Opening $db ..."
sqlite3 $db
