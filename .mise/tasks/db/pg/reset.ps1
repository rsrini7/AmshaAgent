#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Drop and recreate the arcclaw database (wipes all data)"
$ErrorActionPreference = 'Stop'
$container = 'arcclaw-pg'
$existing = podman ps --filter name=$container --no-trunc 2>&1 | Select-String $container
if (-not $existing) {
    Write-Host "❌  PostgreSQL container is not running. Run: mise run db:pg:start"
    exit 1
}
Write-Host "⚠️   WARNING: This will permanently delete all PostgreSQL data."
Write-Host "      Creating a new database requires Flyway clean + migrate."
$confirm = Read-Host "Type 'reset' to confirm"
if ($confirm -ne 'reset') { Write-Host "Aborted."; exit 1 }
podman exec $container psql -U arcclaw -d postgres -c "DROP DATABASE IF EXISTS arcclaw WITH (FORCE);" 2>&1 | Out-Null
podman exec $container psql -U arcclaw -d postgres -c "CREATE DATABASE arcclaw;" 2>&1 | Out-Null
Write-Host "✅  Database 'arcclaw' recreated. Run 'mise run jar:pg' to apply Flyway migrations."
