#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Verify SQLite FTS5 is available (required for full-text search)"
$result = sqlite3 ':memory:' 'CREATE VIRTUAL TABLE t USING fts5(x);' 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host '✅  FTS5 is available.'
} else {
    Write-Host '❌  FTS5 not available.'
    if (-not $IsWindows) { Write-Host '    Fix: sudo apt install sqlite3' }
    exit 1
}
