#!/usr/bin/env pwsh
#MISE description = "Check PostgreSQL container health"
$container = 'arcclaw-pg'
$existing = podman ps -a --filter name=$container --no-trunc 2>&1 | Select-String $container
if (-not $existing) {
    Write-Host '❌  PostgreSQL container not found. Run: mise run db:pg:start'
    exit 1
}
$state = podman inspect $container | ConvertFrom-Json | Select-Object -ExpandProperty State
$status = $state.Status
$health = $state.Health.Status
Write-Host "✅  Container status: $status | Health: $health"
$ports = podman port $container 2>&1
if ($ports) { $ports | ForEach-Object { Write-Host "  $_" } }
