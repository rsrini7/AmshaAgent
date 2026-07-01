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
# Pre-flight: remove stale containers that would block compose up
# Fixed container_name entries (e.g. prometheus) collide across projects.
foreach ($name in @('prometheus', 'amshaagentinternal-otel-collector-1', 'amshaagentinternal-jaeger-1', 'amshaagentinternal-phoenix-1')) {
    $exists = podman ps -a --filter "name=^/$($name)$" --format '{{.ID}}' 2>$null
    if ($exists) {
        Write-Host "🧹  Removing stale container: $name"
        podman rm -f $name 2>$null | Out-Null
    }
}
$composeOut = podman compose up -d otel-collector jaeger phoenix prometheus 2>&1
if ($LASTEXITCODE -ne 0) {
    # "proxy already running" — stale gvproxy inside the VM. Bounce the machine and retry.
    if ($composeOut -match 'proxy already running') {
        Write-Host '🔄  Podman proxy stale — restarting machine...'
        podman machine stop 2>&1 | Out-Null
        Start-Sleep -Seconds 2
        podman machine start 2>&1 | Out-Null
        Start-Sleep -Seconds 3
        # Re-run cleanup after restart (stale containers may persist)
        foreach ($name in @('prometheus', 'amshaagentinternal-otel-collector-1', 'amshaagentinternal-jaeger-1', 'amshaagentinternal-phoenix-1')) {
            $exists = podman ps -a --filter "name=^/$($name)$" --format '{{.ID}}' 2>$null
            if ($exists) { podman rm -f $name 2>$null | Out-Null }
        }
        podman compose up -d otel-collector jaeger phoenix prometheus
        if ($LASTEXITCODE -ne 0) { Write-Host '❌  Observability stack failed even after machine restart'; exit 1 }
    } else {
        Write-Host $composeOut
        Write-Host '❌  Failed to start observability stack'
        exit 1
    }
}
Write-Host '✅  Observability stack started:'
Write-Host '    Jaeger UI  -> http://localhost:16686'
Write-Host '    Phoenix UI -> http://localhost:6006'
Write-Host '    Prometheus -> http://localhost:9090'
