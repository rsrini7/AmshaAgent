#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Stop and remove PostgreSQL container"
$ErrorActionPreference = 'Stop'
$container = 'amsha-pg'
$existing = podman ps -a --filter name=$container --no-trunc 2>&1 | Select-String $container
if (-not $existing) {
    Write-Host "ℹ️   No PostgreSQL container found."
    exit 0
}
Write-Host "▶   Stopping container '$container'..."
podman stop $container 2>&1 | Out-Null
podman rm $container 2>&1 | Out-Null
Write-Host "✅  PostgreSQL container stopped and removed."
