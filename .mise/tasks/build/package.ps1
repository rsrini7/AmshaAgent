#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Build package quarkus-run.jar for SQLite backend (default)"
$ErrorActionPreference = 'Stop'
Write-Host '▶   Building quarkus-run.jar for SQLite backend...'
$mvnw = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
& $mvnw 'package' '-pl' 'amsha-app' '-am' '-DskipTests' '-q'
if ($LASTEXITCODE -ne 0) { Write-Host "❌  Build failed."; exit $LASTEXITCODE }
Write-Host '✅  SQLite JAR ready.'
