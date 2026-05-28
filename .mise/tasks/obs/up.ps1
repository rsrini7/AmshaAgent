#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start observability stack: OTel + Jaeger + Phoenix + Prometheus via compose"
$ErrorActionPreference = 'Stop'
# Podman machine check: try a simple command; if it fails, ask user before starting
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
                if ($LASTEXITCODE -ne 0) { Write-Host '❌  Podman machine failed to restart'; exit 1 }
            } else {
                Write-Host '❌  Podman machine failed to start'; exit 1
            }
        }
        Start-Sleep -Seconds 3
    } else {
        Write-Host '❌  Podman is required for the observability stack.'
        exit 1
    }
}
podman compose up -d otel-collector jaeger phoenix prometheus
if ($LASTEXITCODE -ne 0) { Write-Host '❌  Failed to start observability stack'; exit 1 }
Write-Host '✅  Observability stack started:'
Write-Host '    Jaeger UI  -> http://localhost:16686'
Write-Host '    Phoenix UI -> http://localhost:6006'
Write-Host '    Prometheus -> http://localhost:9090'
