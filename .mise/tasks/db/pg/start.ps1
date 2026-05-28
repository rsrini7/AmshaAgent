#!/usr/bin/env pwsh
#MISE description = "Start PostgreSQL 16 container via Podman (for dev/postgresql profile)"
$ErrorActionPreference = 'Stop'

# Ensure Podman machine is running before container operations
$test = podman ps --noheading 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host '⚠️   Podman is not running.'
    $confirm = Read-Host 'Start Podman machine now? [Y/n]'
    if ($confirm -eq '' -or $confirm -eq 'y' -or $confirm -eq 'Y') {
        $startResult = podman machine start 2>&1
        if ($LASTEXITCODE -ne 0) {
            if ($startResult -match 'already running') {
                Write-Host '⚠️   Podman machine reported "already running" but connection failed — forcing restart...'
                podman machine stop 2>&1 | Out-Null; Start-Sleep -Seconds 2
                podman machine start 2>&1
                if ($LASTEXITCODE -ne 0) { Write-Host "❌  Podman machine failed to restart. Run: podman machine init"; exit 1 }
            } else {
                Write-Host "❌  Podman machine failed to start. Run: podman machine init"; exit 1
            }
        }
        Write-Host "✅  Podman machine started."
        Start-Sleep -Seconds 3
    } else {
        Write-Host "❌  Podman is required for PostgreSQL."
        exit 1
    }
}

$container = 'arcclaw-pg'
$existing = podman ps -a --filter name=$container --no-trunc 2>&1 | Select-String $container
if ($existing) {
    $status = podman ps --filter name=$container --no-trunc 2>&1 | Select-String $container
    if ($status) {
        Write-Host "✅  PostgreSQL container '$container' is already running."
        exit 0
    }
    Write-Host "ℹ️   Container '$container' exists but stopped — starting..."
    podman start $container
    if ($LASTEXITCODE -ne 0) { Write-Host "❌  Failed to start container."; exit 1 }
    Write-Host "✅  Container started."
    exit 0
}
Write-Host "▶   Creating PostgreSQL container '$container'..."
podman run -d --name $container `
    -e POSTGRES_DB=arcclaw `
    -e POSTGRES_USER=arcclaw `
    -e POSTGRES_PASSWORD=changeme `
    -p 5432:5432 `
    --health-cmd='pg_isready -U arcclaw' `
    --health-interval=3s `
    --health-timeout=2s `
    --health-retries=5 `
    pgvector/pgvector:0.8.0-pg16
if ($LASTEXITCODE -ne 0) { Write-Host "❌  Failed to create container. Try: podman pull pgvector/pgvector:0.8.0-pg16"; exit 1 }
Write-Host "⏳  Waiting for PostgreSQL to become healthy..."
$ready = $false
for ($i = 0; $i -lt 15; $i++) {
    $status = podman inspect $container | ConvertFrom-Json | Select-Object -ExpandProperty State | Select-Object -ExpandProperty Health | Select-Object -ExpandProperty Status
    if ($status -eq 'healthy') { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) {
    Write-Host "⚠️   Health check did not become healthy — container may still be starting."
    podman logs -n 5 $container 2>&1 | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "✅  PostgreSQL 16 is ready on port 5432."
}
