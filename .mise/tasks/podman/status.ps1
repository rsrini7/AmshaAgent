#!/usr/bin/env pwsh
#MISE description = "Print Podman version, running containers, and tool-runner image status"
Write-Host '-- Podman version ───────────────────────────────'
$pv = podman --version 2>&1; if ($LASTEXITCODE -eq 0) { Write-Host "  $pv" } else { Write-Host '  podman not found' }
Write-Host '-- Running containers ───────────────────────────'
$ps = podman ps 2>&1; if ($LASTEXITCODE -eq 0) { Write-Host $ps } else { Write-Host '  (podman not running)' }
Write-Host '-- arcclaw-tool-runner image ────────────────────'
$img = (podman images 2>&1) | Select-String 'arcclaw-tool-runner'
if ($img) { Write-Host "  $img" } else { Write-Host '  Image not found -- run: mise run sandbox:build' }
Write-Host '-- DOCKER_HOST ──────────────────────────────────'
Write-Host "  $($env:DOCKER_HOST ?? '(not set)')"
