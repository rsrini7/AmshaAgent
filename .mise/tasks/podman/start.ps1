#!/usr/bin/env pwsh
#MISE description = "macOS: start the Podman machine"
$ErrorActionPreference = 'Stop'
if ($IsWindows) { Write-Host 'ℹ️   podman:start is macOS-only.'; exit 0 }
podman machine start

# ── Memory sanity check ─────────────────────────────────────────────────────
$vmMemMB = [int]((podman machine inspect podman-machine-default 2>&1 | Select-String '"Memory":\s*(\d+)').Matches[0].Groups[1].Value)
if ($vmMemMB -lt 4096) {
    Write-Host "⚠️   Podman VM has only ${vmMemMB} MB RAM. Native builds need ≥4 GB (6 GB recommended)."
    Write-Host '    Fix: podman machine stop; podman machine set podman-machine-default --memory 6144; podman machine start'
} else {
    Write-Host "✅  Podman machine started (${vmMemMB} MB RAM)."
}
